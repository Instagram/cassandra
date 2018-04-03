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
import java.net.InetAddress;
import java.nio.channels.ReadableByteChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ning.compress.lzf.LZFOutputStream;
import org.apache.cassandra.SchemaLoader;
import org.apache.cassandra.db.ColumnFamilyStore;
import org.apache.cassandra.db.marshal.AsciiType;
import org.apache.cassandra.dht.IPartitioner;
import org.apache.cassandra.dht.Range;
import org.apache.cassandra.dht.Token;
import org.apache.cassandra.io.util.DataOutputStreamPlus;
import org.apache.cassandra.net.MessagingService;
import org.apache.cassandra.rocksdb.RocksDBCF;
import org.apache.cassandra.rocksdb.RocksDBEngine;
import org.apache.cassandra.rocksdb.RocksDBUtils;
import org.apache.cassandra.service.StorageService;
import org.apache.cassandra.streaming.StreamPlan;
import org.apache.cassandra.streaming.StreamResultFuture;
import org.apache.cassandra.streaming.StreamSession;
import org.apache.cassandra.streaming.StreamState;
import org.apache.cassandra.streaming.messages.StreamMessage;
import org.apache.cassandra.utils.FBUtilities;

import static org.junit.Assert.fail;

public class RocksDBStreamTransferTest extends RocksDBStreamTestBase
{
    private static final Logger LOGGER = LoggerFactory.getLogger(RocksDBStreamTransferTest.class);

    public static final InetAddress LOCAL = FBUtilities.getBroadcastAddress();

    @BeforeClass
    public static void classSetUp()
    {
        RocksDBStreamTestBase.classSetUp();
        SchemaLoader.prepareServer();
        StorageService.instance.initServer();
    }

    @Test
    public void testEmptyStreamPlan() throws Exception
    {
        StreamResultFuture futureResult = new StreamPlan("StreamingTransferTest").execute();
        final UUID planId = futureResult.planId;
        Futures.addCallback(futureResult, new FutureCallback<StreamState>()
        {
            public void onSuccess(StreamState result)
            {
                assert planId.equals(result.planId);
                assert result.description.equals("StreamingTransferTest");
                assert result.sessions.isEmpty();
            }

            public void onFailure(Throwable t)
            {
                fail();
            }
        });
        futureResult.get(100, TimeUnit.MILLISECONDS);
    }

    @Test
    public void testTransferFullRange() throws Throwable
    {
        int numberOfKeys = 1000;

        // Create table one and insert some data for streaming.
        createTable("CREATE TABLE %s (p TEXT, v TEXT, PRIMARY KEY (p))");
        ColumnFamilyStore outCfs = getCurrentColumnFamilyStore();
        for (int i = 0; i < numberOfKeys; i ++)
        {
            execute("INSERT INTO %s(p, v) values (?, ?)", "p" + i, "v" + i);
        }

        // Create table two and for receiving streamed data.
        createTable("CREATE TABLE %s (p TEXT, v TEXT, PRIMARY KEY (p))");
        ColumnFamilyStore inCfs = getCurrentColumnFamilyStore();

        // Verifies all data are not streamed.
        for (int i = 0; i < numberOfKeys; i ++)
        {
            assertRows(execute("SELECT v FROM %s WHERE p=?", "p" + i));
        }

        // Use customized outgoing message serializer so that table one is streamed to table two.
        StreamMessage.Serializer<RocksDBOutgoingMessage> replaced = RocksDBOutgoingMessage.SERIALIZER;
        try
        {
            RocksDBOutgoingMessage.SERIALIZER = new CustomRocksDBOutgoingMessageSerailizer(RocksDBEngine.getRocksDBCF(outCfs.metadata.cfId));
            List<Range<Token>> ranges = new ArrayList<>();
            ranges.add(
                      new Range<Token>(RocksDBUtils.getMinToken(inCfs.getPartitioner()),
                                       RocksDBUtils.getMaxToken(inCfs.getPartitioner())));
            transferRanges(inCfs, ranges);
        } finally
        {
            RocksDBOutgoingMessage.SERIALIZER = replaced;
        }

        // Verifies all data are streamed.
        for (int i = 0; i < numberOfKeys; i ++)
        {
            assertRows(execute("SELECT v FROM %s WHERE p=?", "p" + i), row("v" + i));
        }
    }

    @Test
    public void testTransferPartialRange() throws Throwable
    {
        int numberOfKeys = 1000;

        // Create table one and insert some data for streaming.
        createTable("CREATE TABLE %s (p TEXT, v TEXT, PRIMARY KEY (p))");
        ColumnFamilyStore outCfs = getCurrentColumnFamilyStore();
        for (int i = 0; i < numberOfKeys; i ++)
        {
            execute("INSERT INTO %s(p, v) values (?, ?)", "p" + i, "v" + i);
        }

        // Create table two and for receiving streamed data.
        createTable("CREATE TABLE %s (p TEXT, v TEXT, PRIMARY KEY (p))");
        ColumnFamilyStore inCfs = getCurrentColumnFamilyStore();

        // Verifies all data are not streamed.
        for (int i = 0; i < numberOfKeys; i ++)
        {
            assertRows(execute("SELECT v FROM %s WHERE p=?", "p" + i));
        }

        IPartitioner partitioner = inCfs.getPartitioner();
        List<Range<Token>> ranges = new ArrayList<>();
        Token minToken = RocksDBUtils.getMinToken(partitioner);
        Token maxToken = RocksDBUtils.getMaxToken(partitioner);
        Token midToken = partitioner.midpoint(minToken, maxToken);
        ranges.add(new Range<Token>(minToken, midToken));

        // Use customized outgoing message serializer so that table one is streamed to table two.
        StreamMessage.Serializer<RocksDBOutgoingMessage> replaced = RocksDBOutgoingMessage.SERIALIZER;
        try
        {
            RocksDBOutgoingMessage.SERIALIZER = new CustomRocksDBOutgoingMessageSerailizer(RocksDBEngine.getRocksDBCF(outCfs.metadata.cfId));
            transferRanges(inCfs, ranges);
        } finally
        {
            RocksDBOutgoingMessage.SERIALIZER = replaced;
        }

        // Verifies all data are streamed.
        for (int i = 0; i < numberOfKeys; i ++)
        {
            String key = "p" + i;
            Token token = partitioner.getToken(AsciiType.instance.decompose(key));
            if (inRanges(token, ranges))
            {
                assertRows(execute("SELECT v FROM %s WHERE p=?", "p" + i), row("v" + i));
            }
            else
            {
                assertRows(execute("SELECT v FROM %s WHERE p=?", "p" + i));
            }
        }
    }


    @Test
    public void testTransferFullRangeWhereLeftIsLarger() throws Throwable
    {
        int numberOfKeys = 1000;

        // Create table one and insert some data for streaming.
        createTable("CREATE TABLE %s (p TEXT, v TEXT, PRIMARY KEY (p))");
        ColumnFamilyStore outCfs = getCurrentColumnFamilyStore();
        for (int i = 0; i < numberOfKeys; i ++)
        {
            execute("INSERT INTO %s(p, v) values (?, ?)", "p" + i, "v" + i);
        }

        // Create table two and for receiving streamed data.
        createTable("CREATE TABLE %s (p TEXT, v TEXT, PRIMARY KEY (p))");
        ColumnFamilyStore inCfs = getCurrentColumnFamilyStore();

        // Verifies all data are not streamed.
        for (int i = 0; i < numberOfKeys; i ++)
        {
            assertRows(execute("SELECT v FROM %s WHERE p=?", "p" + i));
        }

        // Use customized outgoing message serializer so that table one is streamed to table two.
        StreamMessage.Serializer<RocksDBOutgoingMessage> replaced = RocksDBOutgoingMessage.SERIALIZER;
        try
        {
            RocksDBOutgoingMessage.SERIALIZER = new CustomRocksDBOutgoingMessageSerailizer(RocksDBEngine.getRocksDBCF(outCfs.metadata.cfId));
            List<Range<Token>> ranges = new ArrayList<>();

            IPartitioner partitioner = inCfs.getPartitioner();
            Token rangeEnd = partitioner.midpoint(RocksDBUtils.getMinToken(partitioner), RocksDBUtils.getMaxToken(partitioner));
            Token rangeStart = partitioner.midpoint(RocksDBUtils.getMinToken(partitioner), RocksDBUtils.getMaxToken(partitioner)).increaseSlightly();
            ranges.add(new Range<Token>(rangeStart, rangeEnd));
            transferRanges(inCfs, ranges);
        } finally
        {
            RocksDBOutgoingMessage.SERIALIZER = replaced;
        }

        // Verifies all data are streamed.
        for (int i = 0; i < numberOfKeys; i ++)
        {
            assertRows(execute("SELECT v FROM %s WHERE p=?", "p" + i), row("v" + i));
        }
    }

    private void verifyConnectionsAreClosed() throws InterruptedException
    {
        outer:
        for (int i = 0; i <= 100; i++)
        {
            for (MessagingService.SocketThread socketThread : MessagingService.instance().getSocketThreads())
                if (!socketThread.connections.isEmpty())
                {
                    Thread.sleep(100);
                    continue outer;
                }
            return;
        }
        fail("Streaming connections remain registered in MessagingService");
    }

    private void transferRanges(ColumnFamilyStore cfs, List<Range<Token>> ranges) throws Exception
    {
        StreamPlan streamPlan = new StreamPlan("StreamingTransferTest").transferRanges(LOCAL, cfs.keyspace.getName(), ranges, cfs.getColumnFamilyName());
        streamPlan.execute().get();
        verifyConnectionsAreClosed();

        try
        {
            streamPlan.transferRanges(LOCAL, cfs.keyspace.getName(), ranges, cfs.getColumnFamilyName());
            fail("Should have thrown exception");
        }
        catch (RuntimeException e)
        {
        }
    }

    /**
     * Cassandra only support stream between same Column Families, which prevent us from doing integration test.
     * Here we customize the RocksDBOutgoingMessageSerializer so that we could stream from another cfs.
     */
    static class CustomRocksDBOutgoingMessageSerailizer implements StreamMessage.Serializer<RocksDBOutgoingMessage> {

        private final RocksDBCF alternativeDBToStreamFrom;

        public CustomRocksDBOutgoingMessageSerailizer(RocksDBCF readFromDB)
        {
            this.alternativeDBToStreamFrom = readFromDB;
        }

        public RocksDBOutgoingMessage deserialize(ReadableByteChannel in, int version, StreamSession session) throws IOException
        {
            throw new UnsupportedOperationException("Not allowed to call deserialize on an outgoing file");
        }

        public void serialize(RocksDBOutgoingMessage message, DataOutputStreamPlus out, int version, StreamSession session) throws IOException
        {
            message.startTransfer();
            try
            {
                RocksDBMessageHeader.SERIALIZER.seriliaze(message.header, out);
                RocksDBStreamWriter writer = new RocksDBStreamWriter(alternativeDBToStreamFrom, message.ranges, session, 0);
                LZFOutputStream stream = new LZFOutputStream(out);
                writer.write(stream);
                stream.flush();
                session.fileSent(message.cfId, message.sequenceNumber, 0);
            }
            finally
            {
                message.finishTransfer();;
            }
        }
    }
}
