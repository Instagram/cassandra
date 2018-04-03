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

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.BeforeClass;

import org.apache.cassandra.config.ColumnDefinition;
import org.apache.cassandra.cql3.CQLTester;
import org.apache.cassandra.cql3.ColumnIdentifier;
import org.apache.cassandra.db.ColumnFamilyStore;
import org.apache.cassandra.db.DecoratedKey;
import org.apache.cassandra.db.PartitionColumns;
import org.apache.cassandra.db.SinglePartitionReadCommand;
import org.apache.cassandra.db.Slices;
import org.apache.cassandra.db.filter.ClusteringIndexSliceFilter;
import org.apache.cassandra.db.filter.ColumnFilter;
import org.apache.cassandra.db.filter.DataLimits;
import org.apache.cassandra.db.filter.RowFilter;
import org.apache.cassandra.db.rows.Row;
import org.apache.cassandra.db.rows.UnfilteredRowIterator;
import org.apache.cassandra.engine.StorageEngine;
import org.apache.cassandra.io.util.FileUtils;
import org.apache.cassandra.net.MessagingService;
import org.apache.cassandra.utils.ByteBufferUtil;
import org.apache.cassandra.utils.FBUtilities;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;

public class RocksDBTestBase extends CQLTester
{
    @BeforeClass
    public static void classSetUp()
    {
        System.setProperty("cassandra.rocksdb.dir", "/tmp/rocksdbtest/" + UUID.randomUUID());
        System.setProperty("cassandra.rocksdb.stream.dir", "/tmp/rocksdbteststream/" + UUID.randomUUID());
        
        RocksDBConfigs.ROCKSDB_KEYSPACE = CQLTester.KEYSPACE;
        RocksDBConfigs.NUM_SHARD = 1;
        File rocksdbdir = new File(RocksDBConfigs.ROCKSDB_DIR);
        if (rocksdbdir.exists())
        {
            FileUtils.deleteRecursive(rocksdbdir);
        }
    }

    protected List<Row> queryEngine(SinglePartitionReadCommand readCommand)
    {
        ColumnFamilyStore cfs = getCurrentColumnFamilyStore();
        UnfilteredRowIterator result = cfs.engine.queryStorage(cfs, readCommand);
        ArrayList<Row> rows = new ArrayList<>();
        while (result.hasNext())
        {
            rows.add((Row) result.next());
        }
        return rows;
    }

    /**
     * Force the readCommand to query Cassandra storage engine instead of plugged one by
     * temporarily set cfs.engine to be null during read request. This function is not thread safe.
     */
    protected List<Row> queryCassandraStorage(SinglePartitionReadCommand readCommand)
    {
        ColumnFamilyStore cfs = getCurrentColumnFamilyStore();
        StorageEngine engine = cfs.engine;
        try {
            cfs.engine = null;
            UnfilteredRowIterator result = readCommand.queryMemtableAndDisk(cfs, null);
            ArrayList<Row> rows = new ArrayList<>();
            while (result.hasNext())
            {
                rows.add((Row) result.next());
            }
            return rows;
        } finally
        {
            cfs.engine = engine;
        }
    }

    protected void triggerCompaction() throws RocksDBException
    {
        rocksDBFlush();
        RocksDBCF rocksDBCF = RocksDBEngine.getRocksDBCF(getCurrentColumnFamilyStore().metadata.cfId);
        rocksDBCF.compactRange();
    }

    protected void rocksDBFlush() throws RocksDBException
    {
        RocksDBCF rocksDBCF = RocksDBEngine.getRocksDBCF(getCurrentColumnFamilyStore().metadata.cfId);
        rocksDBCF.forceFlush();
    }

    protected SinglePartitionReadCommand readCommand(String keystr, String column)
    {

        ColumnFamilyStore cfs = getCurrentColumnFamilyStore();
        DecoratedKey key = cfs.metadata.decorateKey(ByteBufferUtil.bytes(keystr));
        ColumnDefinition columnDef = cfs.metadata.getColumnDefinition(new ColumnIdentifier(column, true));
        ColumnFilter columnFilter = ColumnFilter.selection(PartitionColumns.of(columnDef));
        ClusteringIndexSliceFilter sliceFilter = new ClusteringIndexSliceFilter(Slices.ALL, false);
        return SinglePartitionReadCommand.create(cfs.metadata,
                                                 FBUtilities.nowInSeconds(),
                                                 key,
                                                 columnFilter,
                                                 sliceFilter);
    }

    protected int numOfRocksdbKeysInPartition(String partitionKey, ColumnFamilyStore cfs)
    {
        return getCurrentEngine().dumpPartition(cfs, partitionKey, Integer.MAX_VALUE).split("\n").length;
    }

    private StorageEngine getCurrentEngine()
    {
        return getCurrentColumnFamilyStore().engine;
    }
}
