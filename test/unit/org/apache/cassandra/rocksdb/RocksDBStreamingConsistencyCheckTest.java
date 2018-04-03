/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.cassandra.rocksdb;

import java.util.Set;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.db.ColumnFamilyStore;
import org.apache.cassandra.rocksdb.tools.StreamingConsistencyCheckUtils;
import org.apache.cassandra.utils.FBUtilities;

import static org.junit.Assert.assertEquals;

public class RocksDBStreamingConsistencyCheckTest extends RocksDBTestBase
{
    @BeforeClass
    public static void classSetUp()
    {
        RocksDBTestBase.classSetUp();
    }

    @Before
    public void beforeTest() throws Throwable
    {
        // Override CqlTester.beforeTest to use LocalStrategy for replication, which enables us to test
        // RocksDBStreamingConsistencyCheck without setting up multiple nodes.
        schemaChange(String.format("CREATE KEYSPACE IF NOT EXISTS %s WITH replication = {'class': 'LocalStrategy'}", KEYSPACE),
                     false /* skip validation as LocalStrategy is reserved for internal usage */);
        schemaChange(String.format("CREATE KEYSPACE IF NOT EXISTS %s WITH replication = {'class': 'LocalStrategy'}", KEYSPACE_PER_TEST),
                     false /* skip validation as LocalStrategy is reserved for internal usage */);
    }

    @Test
    public void testCheckReportConsistent() throws Throwable
    {
        createTable("CREATE TABLE %s (p int, v int, PRIMARY KEY (p))");
        int numberOfKeys = 100;
        for (int key = 0; key < numberOfKeys; key ++)
        {
            execute("INSERT INTO %s(p, v) values (?, ?)", key, key);
        }

        ColumnFamilyStore cfs = getCurrentColumnFamilyStore();
        assertEquals(StreamingConsistencyCheckUtils.check(cfs, numberOfKeys).size(), 0);
    }

    @Test
    public void testCheckReportInconsistent() throws Throwable
    {
        createTable("CREATE TABLE %s (p int, v int, PRIMARY KEY (p))");
        int numberOfKeys = 100;
        for (int key = 0; key < numberOfKeys; key ++)
        {
            // Skip one key to simulate inconsistent during streaming.
            if (key == 10)
                continue;
            execute("INSERT INTO %s(p, v) values (?, ?)", key, key);
        }

        ColumnFamilyStore cfs = getCurrentColumnFamilyStore();
        Set<Integer> missingKeys = StreamingConsistencyCheckUtils.check(cfs, numberOfKeys);
        assertEquals(StreamingConsistencyCheckUtils.check(cfs, numberOfKeys).size(), 1);

        for (int key : missingKeys)
        {
            assertEquals(FBUtilities.getBroadcastAddress(),
                         StreamingConsistencyCheckUtils.getLocalEndpointOfKey(cfs, key, DatabaseDescriptor.getEndpointSnitch().getDatacenter(FBUtilities.getBroadcastAddress())));
        }
    }

}
