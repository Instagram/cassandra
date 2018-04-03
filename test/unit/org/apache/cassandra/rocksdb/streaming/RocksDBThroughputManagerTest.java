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

package org.apache.cassandra.rocksdb.streaming;

import java.io.IOException;
import java.util.ArrayList;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;


import org.rocksdb.RocksDBException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class RocksDBThroughputManagerTest extends RocksDBStreamTestBase
{
    private static class DummyStreamWriter extends RocksDBStreamWriter {

        long outgoingBytes = 0;

        public DummyStreamWriter()
        {
            super(null, new ArrayList<>(), createDummySession(), 0);
        }

        public void increaseOutgoingBytes(long value)
        {
            this.outgoingBytes = value;
        }

        @Override
        public long getOutgoingBytes()
        {
            return outgoingBytes;
        }
    }

    private static class DummySstableWriter extends RocksDBSStableWriter {

        long incomingBytes = 0;

        public DummySstableWriter() throws IOException, RocksDBException
        {
            super(null);
        }

        public void increaseIncomingBytes(long value)
        {
            this.incomingBytes = value;
        }

        @Override
        public long getIncomingBytes()
        {
            return incomingBytes;
        }
    }

    @BeforeClass
    public static void classSetUp()
    {
        RocksDBStreamTestBase.classSetUp();
        // Disable periodically calcuate the throughput.
        System.setProperty("cassandra.rocksdb.throughput.peek_interval_ms", "0");

    }

    @AfterClass
    public static void classTeardown() throws Exception
    {
        System.clearProperty("cassandra.rocksdb.stream.dir");
    }

    @Test
    public void testReportsThroughput() throws IOException, RocksDBException
    {
        RocksDBThroughputManager manager = RocksDBThroughputManager.getInstance();
        manager.calcluateThroughput();
        assertEquals(manager.getIncomingThroughput(), 0);
        assertEquals(manager.getOutgoingThroughput(), 0);

        DummyStreamWriter writer = new DummyStreamWriter();
        writer.increaseOutgoingBytes(100000L);

        manager.calcluateThroughput();
        assertEquals(manager.getIncomingThroughput(), 0);
        assertTrue(manager.getOutgoingThroughput() > 0);

        DummySstableWriter sstableWriter = new DummySstableWriter();
        sstableWriter.increaseIncomingBytes(100000L);

        manager.calcluateThroughput();
        assertEquals(manager.getOutgoingThroughput(), 0);
        assertTrue(manager.getIncomingThroughput() > 0);
    }
}
