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

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import javax.management.MBeanServer;
import javax.management.ObjectName;

import com.google.common.base.Throwables;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.cassandra.db.ColumnFamilyStore;
import org.apache.cassandra.dht.IPartitioner;
import org.apache.cassandra.dht.Range;
import org.apache.cassandra.dht.Token;
import org.apache.cassandra.io.util.BufferedDataOutputStreamPlus;
import org.apache.cassandra.io.util.DataInputPlus;
import org.apache.cassandra.io.util.FileUtils;
import org.apache.cassandra.metrics.RocksDBTableMetrics;
import org.apache.cassandra.rocksdb.encoding.RowKeyEncoder;
import org.apache.cassandra.rocksdb.encoding.orderly.Bytes;
import org.apache.cassandra.rocksdb.streaming.RocksDBMessageHeader;
import org.apache.cassandra.rocksdb.streaming.RocksDBStreamReader;
import org.apache.cassandra.rocksdb.streaming.RocksDBStreamWriter;
import org.apache.cassandra.rocksdb.tools.SanityCheckUtils;
import org.apache.cassandra.rocksdb.tools.StreamingConsistencyCheckUtils;
import org.apache.cassandra.streaming.StreamSession;
import org.apache.cassandra.utils.ByteBufferUtil;
import org.apache.cassandra.utils.FBUtilities;
import org.apache.cassandra.utils.JVMStabilityInspector;
import org.apache.cassandra.utils.Hex;
import org.rocksdb.BlockBasedTableConfig;
import org.rocksdb.BloomFilter;
import org.rocksdb.CassandraCompactionFilter;
import org.rocksdb.CassandraValueMergeOperator;
import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyOptions;
import org.rocksdb.CompactionPriority;
import org.rocksdb.CompressionType;
import org.rocksdb.DBOptions;
import org.rocksdb.Env;
import org.rocksdb.FlushOptions;
import org.rocksdb.OptionsUtil;
import org.rocksdb.ReadOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.Statistics;
import org.rocksdb.StatsLevel;
import org.rocksdb.WriteOptions;

import static org.apache.cassandra.rocksdb.RocksDBConfigs.MERGE_OPERANDS_LIMIT;
import static org.apache.cassandra.rocksdb.RocksDBConfigs.ROCKSDB_DIR;

/**
 * A wrapper around RocksDB instance.
 */
public class RocksDBCF implements RocksDBCFMBean
{
    private static final Logger logger = LoggerFactory.getLogger(RocksDBCF.class);
    private final UUID cfId;
    private final ColumnFamilyStore cfs;
    private final IPartitioner partitioner;
    private final RocksDBEngine engine;
    private final RocksDB rocksDB;
    private final Statistics stats;
    private final RocksDBTableMetrics rocksMetrics;
    private final CassandraCompactionFilter compactionFilter;
    private final CassandraValueMergeOperator mergeOperator;

    private final ReadOptions readOptions;
    private final WriteOptions disableWAL;
    private final FlushOptions flushOptions;

    private final String rocksDBTableDir;

    public RocksDBCF(ColumnFamilyStore cfs) throws RocksDBException
    {
        this.cfs = cfs;
        cfId = cfs.metadata.cfId;
        partitioner = cfs.getPartitioner();
        engine = (RocksDBEngine) cfs.engine;

        rocksDBTableDir = String.format("%s/%s/%s-%s",
                ROCKSDB_DIR, cfs.keyspace.getName(), cfs.name, ByteBufferUtil.bytesToHex(ByteBufferUtil.bytes(cfId)));
        FileUtils.createDirectory(ROCKSDB_DIR);
        FileUtils.createDirectory(rocksDBTableDir);

        final long writeBufferSize = RocksDBConfigs.WRITE_BUFFER_SIZE_MBYTES * 1024 * 1024L;
        final long softPendingCompactionBytesLimit = 100 * 64 * 1073741824L;
        
        int gcGraceSeconds = cfs.metadata.params.gcGraceSeconds;
        boolean purgeTtlOnExpiration = cfs.metadata.params.purgeTtlOnExpiration;
        compactionFilter = new CassandraCompactionFilter(purgeTtlOnExpiration, gcGraceSeconds);
        mergeOperator = new CassandraValueMergeOperator(gcGraceSeconds, MERGE_OPERANDS_LIMIT);
        
        DBOptions dbOptions = new DBOptions();
        List<ColumnFamilyDescriptor> cfDescs = new ArrayList<>();

        boolean loadedLatestOptions = false;
        try
        {
            OptionsUtil.loadLatestOptions(rocksDBTableDir, Env.getDefault(), dbOptions, cfDescs, false);
            loadedLatestOptions = true;
        }
        catch (RocksDBException ex)
        {
            logger.warn("Failed to load lastest RocksDB options for cf {}.{}",
                        cfs.keyspace.getName(), cfs.name);
        }

        // create options
        if (!loadedLatestOptions) {
            // db options
            dbOptions.setCreateIfMissing(true);
            dbOptions.setAllowConcurrentMemtableWrite(true);
            dbOptions.setEnableWriteThreadAdaptiveYield(true);
            dbOptions.setBytesPerSync(1024 * 1024);
            dbOptions.setWalBytesPerSync(1024 * 1024);
            dbOptions.setMaxBackgroundCompactions(RocksDBConfigs.BACKGROUD_COMPACTIONS);
            dbOptions.setBaseBackgroundCompactions(RocksDBConfigs.BACKGROUD_COMPACTIONS);
            dbOptions.setMaxBackgroundFlushes(4);
            dbOptions.setMaxSubcompactions(8);

            // column family options
            cfDescs.clear();

            ColumnFamilyOptions columnFamilyOptions = new ColumnFamilyOptions();
            columnFamilyOptions.setNumLevels(RocksDBConfigs.MAX_LEVELS);
            columnFamilyOptions.setCompressionType(CompressionType.LZ4_COMPRESSION);
            columnFamilyOptions.setWriteBufferSize(writeBufferSize);
            columnFamilyOptions.setMaxWriteBufferNumber(4);
            columnFamilyOptions.setMaxBytesForLevelBase(RocksDBConfigs.MAX_MBYTES_FOR_LEVEL_BASE * 1024 * 1024L);
            columnFamilyOptions.setSoftPendingCompactionBytesLimit(softPendingCompactionBytesLimit);
            columnFamilyOptions.setHardPendingCompactionBytesLimit(8 * softPendingCompactionBytesLimit);
            columnFamilyOptions.setCompactionPriority(CompactionPriority.MinOverlappingRatio);
            columnFamilyOptions.setLevel0SlowdownWritesTrigger(RocksDBConfigs.LEVEL0_STOP_WRITES_TRIGGER);
            columnFamilyOptions.setLevel0StopWritesTrigger(RocksDBConfigs.LEVEL0_STOP_WRITES_TRIGGER);
            columnFamilyOptions.setLevelCompactionDynamicLevelBytes(!RocksDBConfigs.DYNAMIC_LEVEL_BYTES_DISABLED);

            final org.rocksdb.BloomFilter bloomFilter = new BloomFilter(10, false);
            final BlockBasedTableConfig tableOptions = new BlockBasedTableConfig();
            tableOptions.setFilter(bloomFilter);
            tableOptions.setBlockCacheSize(RocksDBConfigs.BLOCK_CACHE_SIZE_MBYTES * 1024 * 1024L);
            columnFamilyOptions.setTableFormatConfig(tableOptions);
            ColumnFamilyDescriptor columnFamilyDescriptor = new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY, columnFamilyOptions);
            cfDescs.add(columnFamilyDescriptor);
        }

        assert cfDescs.size() == 1;
        assert Arrays.equals(cfDescs.get(0).columnFamilyName(), RocksDB.DEFAULT_COLUMN_FAMILY);

        ColumnFamilyOptions cfOptions = cfDescs.get(0).columnFamilyOptions();
        cfOptions.setMergeOperator(mergeOperator);
        cfOptions.setCompactionFilter(compactionFilter);

        stats = new Statistics();
        stats.setStatsLevel(StatsLevel.EXCEPT_DETAILED_TIMERS);
        
        dbOptions.setStatistics(stats);
        dbOptions.setRateLimiter(engine.rateLimiter);
        
        rocksDB = RocksDB.open(dbOptions, rocksDBTableDir, cfDescs, new ArrayList<>(1));
        logger.info("Open rocksdb instance for cf {}.{} with path:{}, gcGraceSeconds:{}, purgeTTL:{}",
                    cfs.keyspace.getName(), cfs.name, rocksDBTableDir,
                    gcGraceSeconds, purgeTtlOnExpiration);

        rocksMetrics = new RocksDBTableMetrics(cfs, stats);

        // Set `ignore_range_deletion` to speed up read, with the cost of read the stale(range deleted) keys
        // until compaction happens. However in our case, range deletion is only used to remove ranges
        // no longer owned by this node. In such case, stale keys would never be quried.
        readOptions = new ReadOptions().setIgnoreRangeDeletions(true);
        disableWAL = new WriteOptions().setDisableWAL(true);
        flushOptions = new FlushOptions().setWaitForFlush(true);

        registerMBean();
    }

    private void registerMBean() {
        // Register the mbean.
        String mbeanName = getMbeanName(cfs.keyspace.getName(), cfs.getTableName());
        try
        {
            MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
            mbs.registerMBean(this, new ObjectName(mbeanName));
        }
        catch (Exception e)
        {
            throw Throwables.propagate(e);
        }
    }

    private void unregisterMBean() {
        String mbeanName = getMbeanName(cfs.keyspace.getName(), cfs.getTableName());
        try
        {
            MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
            ObjectName mbean = new ObjectName(mbeanName);
            if (mbs.isRegistered(mbean)) {
                mbs.unregisterMBean(mbean);
            }
        }
        catch (Exception e)
        {
            JVMStabilityInspector.inspectThrowable(e);
            // this shouldn't block anything.
            logger.warn("Failed unregistering mbean: {}", mbeanName, e);
        }
    }


    public static String getMbeanName(String keyspace, String table)
    {
        return String.format("org.apache.cassandra.rocksdbcf:keyspace=%s,table=%s", keyspace, table);
    }

    public RocksDB getRocksDB()
    {
        return rocksDB;
    }

    public Statistics getStatistics()
    {
        return stats;
    }

    public RocksDBTableMetrics getRocksMetrics()
    {
        return rocksMetrics;
    }

    public byte[] get(byte[] key) throws RocksDBException
    {
        return rocksDB.get(readOptions, key);
    }

    public RocksDBIteratorAdapter newIterator()
    {
        return newIterator(readOptions);
    }

    public RocksDBIteratorAdapter newIterator(ReadOptions options)
    {
        rocksMetrics.rocksDBIterNew.inc();
        return new RocksDBIteratorAdapter(rocksDB.newIterator(options), rocksMetrics);
    }

    public void merge(byte[] key, byte[] value) throws RocksDBException
    {
        merge(key, value, true);
    }

    public void merge(byte[] key, byte[] value, boolean writeCommitLog) throws RocksDBException
    {
        if ((!writeCommitLog) || RocksDBConfigs.DISABLE_WRITE_TO_COMMITLOG)
        {
            rocksDB.merge(disableWAL, key, value);
        }
        else
        {
            rocksDB.merge(key, value);
        }
    }

    public void deleteRange(byte[] start, byte[] end) throws RocksDBException
    {
        rocksDB.deleteRange(start, end);
    }

    public void compactRange() throws RocksDBException
    {
        rocksDB.compactRange();
    }

    public void forceFlush() throws RocksDBException
    {
        logger.info("Flushing rocksdb table: " + cfs.name);
        rocksDB.flush(flushOptions);
    }

    public String getProperty(String property) throws RocksDBException
    {
        return rocksDB.getProperty(property);
    }

    public void truncate() throws RocksDBException
    {
        // TODO: use delete range for now, could have a better solution
        rocksDB.deleteRange(RowKeyEncoder.encodeToken(RocksDBUtils.getMinToken(partitioner)),
                            RowKeyEncoder.encodeToken(RocksDBUtils.getMaxToken(partitioner)));
    }

    protected void close() throws RocksDBException
    {
        logger.info("Closing rocksdb table: " + cfs.name);
        synchronized (engine.rocksDBFamily)
        {
            rocksDB.close();
            unregisterMBean();

            // remove the rocksdb instance, since it's not usable
            engine.rocksDBFamily.remove(cfId);
        }
    }

    protected void destroy() throws RocksDBException {
        logger.info("Deleting rocksdb table: " + cfs.name);
        rocksDB.destroyDB(rocksDBTableDir, new Options());
    }


    public String dumpPrefix(byte[] rocksKeyPrefix, int limit)
    {
        StringBuilder sb = new StringBuilder();
        try (RocksDBIteratorAdapter rocksIterator = newIterator())
        {
            rocksIterator.seek(rocksKeyPrefix);
            while (limit > 0 && rocksIterator.isValid())
            {
                byte[] key = rocksIterator.key();
                if (!Bytes.startsWith(key, rocksKeyPrefix))
                {
                    break;
                }
                sb.append("0x")
                  .append(Hex.bytesToHex(key))
                  .append('\t');

                byte[] value = rocksIterator.value();
                if (value == null)
                {
                    sb.append("null\n");
                }
                else
                {
                    sb.append("0x")
                      .append(Hex.bytesToHex(value))
                      .append('\n');
                }
                limit--;
                rocksIterator.next();
            }
        }
        return sb.toString();
    }

    public UUID getCfId()
    {
        return cfId;
    }

    @Override
    public String rocksDBSanityCheck(boolean randomStartToken, long limit, boolean verbose)
    {
        return SanityCheckUtils.checkSanity(cfs, randomStartToken, limit, verbose).toString();
    }

    @Override
    public String exportRocksDBStream(String outputFile, int limit) throws IOException, RocksDBException
    {
        Collection<Range<Token>> ranges = Arrays.asList(new Range<Token>(RocksDBUtils.getMinToken(cfs.getPartitioner()),
                                                                         RocksDBUtils.getMaxToken(cfs.getPartitioner())));
        RocksDBStreamWriter writer = new RocksDBStreamWriter(RocksDBEngine.getRocksDBCF(cfs.metadata.cfId), ranges);
        BufferedDataOutputStreamPlus out = new BufferedDataOutputStreamPlus(new FileOutputStream(outputFile));
        long startTimeMs = System.currentTimeMillis();
        writer.write(out, limit);
        out.close();
        long timeElapsedMs = Math.max(1, System.currentTimeMillis() - startTimeMs); // Avoid divde by 0 Exception.
        double streamedMB = writer.getOutgoingBytes() / (1024.0 * 1024 /* MB in bytes */);
        double throughputMBps = streamedMB / (timeElapsedMs / 1000.0f /* Ms in seconds */);
        return "Data Streamed: " + streamedMB + "MB, time elapsed: " + timeElapsedMs + " MS, throughput: " + throughputMBps + " MB/S.";
    }

    @Override
    public String ingestRocksDBStream(String inputFile) throws IOException, RocksDBException
    {
        RocksDBStreamReader reader = new RocksDBStreamReader(new RocksDBMessageHeader(cfs.metadata.cfId, 0),
                                                             new StreamSession(FBUtilities.getBroadcastAddress(), FBUtilities.getBroadcastAddress(), null, 0, false, false));
        BufferedInputStream stream = new BufferedInputStream(new FileInputStream(inputFile));
        long startTimeMs = System.currentTimeMillis();
        reader.read(new DataInputPlus.DataInputStreamPlus(stream));
        long timeElapsedMs = Math.max(1, System.currentTimeMillis() - startTimeMs); // Avoid divde by 0 Exception.
        double streamedMB = reader.getTotalIncomingBytes() / (1024.0 * 1024 /* MB in bytes */);
        double throughputMBps = streamedMB / (timeElapsedMs / 1000.0f /* Ms in seconds */);
        return "Data Streamed: " + streamedMB + "MB, time elapsed: " + timeElapsedMs + " MS, throughput: " + throughputMBps + " MB/S.";
    }

    @Override
    public String getRocksDBProperty(String property)
    {
        try
        {
            return getProperty(property);
        } catch (Throwable e) {
            logger.warn("Failed to get rocksBD property " + property, e);
            return "Failed to get property:" + property + ", reason:" + e.toString();
        }
    }

    @Override
    public String dumpPartition(String partitionKey, int limit)
    {
        try
        {
            return engine.dumpPartition(cfs, partitionKey, limit);
        }
        catch (Throwable e)
        {
            logger.warn("Failed to dump parition " + partitionKey, e);
            return "Failed to dump:" + partitionKey + ", reason:" + e.toString();
        }
    }

    @Override
    public String streamingConsistencyCheck(int expectedNumKeys)
    {
        return StreamingConsistencyCheckUtils.checkAndGenerateReport(cfs, expectedNumKeys);
    }
}
