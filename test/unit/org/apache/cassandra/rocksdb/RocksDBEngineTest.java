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

import org.junit.Test;
import org.junit.runner.RunWith;

import org.apache.cassandra.db.ColumnFamilyStore;
import org.apache.cassandra.rocksdb.index.RocksandraClusteringColumnIndex;
import org.jboss.byteman.contrib.bmunit.BMRule;
import org.jboss.byteman.contrib.bmunit.BMUnitRunner;

import static org.junit.Assert.*;

@RunWith(BMUnitRunner.class)
public class RocksDBEngineTest extends RocksDBTestBase
{

    @Test
    public void testDumpPartition() throws Throwable
    {
        createTable("CREATE TABLE %s (p text, c text, v text, PRIMARY KEY (p, c))");
        execute("INSERT INTO %s(p, c, v) values (?, ?, ?)", "p1", "k1", "v1");
        execute("INSERT INTO %s(p, c, v) values (?, ?, ?)", "p1", "k2", "v2");
        execute("INSERT INTO %s(p, c, v) values (?, ?, ?)", "p2", "k", "v");


        ColumnFamilyStore cfs = getCurrentColumnFamilyStore();
        RocksDBEngine engine = (RocksDBEngine) cfs.engine;

        String dump = engine.dumpPartition(cfs, "'p1'", Integer.MAX_VALUE);
        assertEquals(2, dump.split("\n").length);
    }

    @Test
    public void testSetCompactionThroughput() throws Throwable
    {
        createTable("CREATE TABLE %s (p text, c text, v text, PRIMARY KEY (p, c))");

        execute("INSERT INTO %s(p, c, v) values (?, ?, ?)", "p1", "k1", "v1");
        execute("INSERT INTO %s(p, c, v) values (?, ?, ?)", "p1", "k2", "v2");

        ColumnFamilyStore cfs = getCurrentColumnFamilyStore();
        RocksDBCF cf = RocksDBEngine.getRocksDBCF(cfs.metadata.cfId);
        cf.forceFlush();

        cfs.engine.setCompactionThroughputMbPerSec(1);
        assertEquals(1, ((RocksDBEngine)cfs.engine).compactionthroughputMbPerSec);
        cfs.engine.forceMajorCompaction(cfs);

        assertTrue(RocksDBProperty.getEstimatedLiveDataSize(cf) > 0);
    }

    @Test
    public void testNumberOfRocksDBCF() throws Throwable
    {
        createTable("CREATE TABLE %s (p text, c text, v text, PRIMARY KEY (p, c))");
        execute("INSERT INTO %s(p, c, v) values (?, ?, ?)", "p1", "k1", "v1");

        ColumnFamilyStore cfs = getCurrentColumnFamilyStore();

        int originalNumRocksDBFamilySize = ((RocksDBEngine)cfs.engine).rocksDBFamily.size();

        createIndex(String.format("CREATE CUSTOM INDEX test_index ON %%s(v) USING '%s'",
                                  RocksandraClusteringColumnIndex.class.getName()));

        assertEquals(originalNumRocksDBFamilySize + 1, ((RocksDBEngine)cfs.engine).rocksDBFamily.size());
    }

    @Test
    @BMRule(name = "throw exception when merging rows",
    targetClass = "RocksDBCF",
    targetMethod = "merge",
    targetLocation = "AT ENTRY",
    action = "throw new RocksDBException(\"test exception\");")
    public void testShouldThrowStorageEngineExceptionWhenRowMergeFails() throws Throwable
    {
        createTable("CREATE TABLE %s (p text, c text, v text, j text, PRIMARY KEY (p, c, v))");
        createIndex(String.format("CREATE CUSTOM INDEX test_index ON %%s(v) USING '%s'",
                                  RocksandraClusteringColumnIndex.class.getName()));

        assertInvalidMessage("Row merge failed: test exception",
                             "INSERT INTO %s(p, c, v, j) values (?, ?, ?, ?)", "p1", "k1", "v1", "j1");
    }

    @Test
    @BMRule(name = "throw exception when merging index rows",
    targetClass = "RocksandraClusteringColumnIndex",
    targetMethod = "insert",
    targetLocation = "AT ENTRY",
    action = "throw new RuntimeException(\"test exception\");")
    public void testShouldThrowStorageEngineExceptionWhenIndexInsertionFails() throws Throwable
    {
        createTable("CREATE TABLE %s (p text, c text, v text, j text, PRIMARY KEY (p, c, v))");
        createIndex(String.format("CREATE CUSTOM INDEX test_index ON %%s(v) USING '%s'",
                                  RocksandraClusteringColumnIndex.class.getName()));
        assertInvalidMessage("Index update failed: test exception",
                             "INSERT INTO %s(p, c, v, j) values (?, ?, ?, ?)", "p1", "k1", "v1", "j1");
    }
}
