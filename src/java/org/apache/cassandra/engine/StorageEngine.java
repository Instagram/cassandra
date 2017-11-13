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

package org.apache.cassandra.engine;

import java.util.Collection;
import java.util.UUID;

import org.apache.cassandra.db.ColumnFamilyStore;
import org.apache.cassandra.db.SinglePartitionReadCommand;
import org.apache.cassandra.db.partitions.PartitionUpdate;
import org.apache.cassandra.db.rows.UnfilteredRowIterator;
import org.apache.cassandra.dht.Range;
import org.apache.cassandra.dht.Token;
import org.apache.cassandra.engine.streaming.AbstractStreamReceiveTask;
import org.apache.cassandra.engine.streaming.AbstractStreamTransferTask;
import org.apache.cassandra.streaming.StreamSession;
import org.apache.cassandra.streaming.StreamSummary;

public interface StorageEngine
{

    void openColumnFamilyStore(ColumnFamilyStore cfs);

    void apply(final ColumnFamilyStore cfs,
               final PartitionUpdate partitionUpdate,
               final boolean writeCommitLog);

    UnfilteredRowIterator queryStorage(ColumnFamilyStore cfs,
                                       SinglePartitionReadCommand readCommand);

    void forceFlush(final ColumnFamilyStore cfs);

    void truncate(final ColumnFamilyStore cfs);

    void close(final ColumnFamilyStore cfs);

    void invalidate(final ColumnFamilyStore cfs);

    void setCompactionThroughputMbPerSec(int throughputMbPerSec);

    void forceMajorCompaction(ColumnFamilyStore cfs);

    long load();

    /**
     *  Streaming APIs
     */
    AbstractStreamTransferTask getStreamTransferTask(StreamSession session,
                                                     UUID cfId,
                                                     Collection<Range<Token>> ranges);

    AbstractStreamReceiveTask getStreamReceiveTask(StreamSession session,
                                                   StreamSummary summary);

    boolean cleanUpRanges(final ColumnFamilyStore cfs);

    boolean doubleWrite();

    /**
     * Dump low level partition data for debugging purpose
     * used by 'nodetool dumppartition'.
     */
    String dumpPartition(ColumnFamilyStore cfs, String partitionKey, int limit);
}
