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
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import javax.management.MBeanServer;
import javax.management.ObjectName;

import com.google.common.base.Throwables;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.cassandra.db.ColumnFamilyStore;
import org.apache.cassandra.db.DecoratedKey;
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
import org.apache.cassandra.utils.FBUtilities;
import org.apache.cassandra.utils.Hex;
import org.apache.cassandra.utils.Pair;
import org.rocksdb.BlockBasedTableConfig;
import org.rocksdb.BloomFilter;
import org.rocksdb.CassandraCompactionFilter;
import org.rocksdb.CassandraValueMergeOperator;
import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyOptions;
import org.rocksdb.CompactionPriority;
import org.rocksdb.DBOptions;
import org.rocksdb.Env;
import org.rocksdb.FlushOptions;
import org.rocksdb.IndexType;
import org.rocksdb.OptionsUtil;
import org.rocksdb.ReadOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.Statistics;
import org.rocksdb.StatsLevel;
import org.rocksdb.WriteOptions;

import static org.apache.cassandra.rocksdb.RocksDBConfigs.MERGE_OPERANDS_LIMIT;
import static org.apache.cassandra.rocksdb.RocksDBConfigs.NUM_SHARD;
import static org.apache.cassandra.rocksdb.RocksDBConfigs.ROCKSDB_DIR;

/**
 * A wrapper around RocksDB instance.
 */
public class RocksDBCF implements RocksDBCFMBean
{
    private static final Logger logger = LoggerFactory.getLogger(RocksDBCF.class);
    private static final int SCHEMA_VERSION = 0;
    private final UUID cfID;
    private final ColumnFamilyStore cfs;
    private final IPartitioner partitioner;
    private final RocksDBEngine engine;
    private final RocksDBTableMetrics rocksMetrics;
    private final String mbeanName;
    private final CassandraCompactionFilter compactionFilter;
    private final CassandraValueMergeOperator mergeOperator;

    private final ReadOptions readOptions;
    private final WriteOptions disableWAL;
    private final FlushOptions flushOptions;

    private final int gcGraceSeconds;
    private final boolean purgeTtlOnExpiration;

    private final List<RocksDB> rocksDBLists;
    private final Statistics stats;
    private boolean closed = false; // indicate whether close() function is called or not.
    private final ReentrantReadWriteLock lockForClosedFlag = new ReentrantReadWriteLock(true); // protect 'closed' field

    public RocksDBCF(ColumnFamilyStore cfs) throws RocksDBException
    {
        this.cfs = cfs;
        cfID = cfs.metadata.cfId;
        partitioner = cfs.getPartitioner();
        engine = (RocksDBEngine) cfs.engine;

        String rocksDBTableDir = Paths.get(ROCKSDB_DIR, "s" + SCHEMA_VERSION, cfs.keyspace.getName(), cfs.name).toString();
        FileUtils.createDirectory(ROCKSDB_DIR);
        FileUtils.createDirectory(rocksDBTableDir);

        gcGraceSeconds = cfs.metadata.params.gcGraceSeconds;
        purgeTtlOnExpiration = cfs.metadata.params.purgeTtlOnExpiration;
        compactionFilter = new CassandraCompactionFilter(purgeTtlOnExpiration, gcGraceSeconds);
        mergeOperator = new CassandraValueMergeOperator(gcGraceSeconds, MERGE_OPERANDS_LIMIT);

        assert NUM_SHARD > 0;

        stats = new Statistics();
        stats.setStatsLevel(StatsLevel.EXCEPT_DETAILED_TIMERS);

        final org.rocksdb.BloomFilter bloomFilter = new BloomFilter(10, false);
        final BlockBasedTableConfig tableOptions = new BlockBasedTableConfig();
        tableOptions.setFilter(bloomFilter);
        tableOptions.setBlockCache(engine.cache);
        tableOptions.setCacheIndexAndFilterBlocks(RocksDBConfigs.CACHE_INDEX_AND_FILTER_BLOCKS);
        tableOptions.setPinL0FilterAndIndexBlocksInCache(RocksDBConfigs.PIN_L0_FILTER_AND_INDEX_BLOCKS_IN_CACHE);
        tableOptions.setIndexType(getTableIndexType(RocksDBConfigs.TABLE_INDEX_TYPE));

        rocksDBLists = new ArrayList<>(NUM_SHARD);
        for (int i = 0; i < NUM_SHARD; i++)
        {
            String shardedDir = NUM_SHARD == 1 ? rocksDBTableDir :
                                Paths.get(rocksDBTableDir, String.valueOf(i)).toString();
            openRocksDB(shardedDir, tableOptions);
        }

        rocksMetrics = new RocksDBTableMetrics(cfs, stats);

        // Set `ignore_range_deletion` to speed up read, with the cost of read the stale(range deleted) keys
        // until compaction happens. However in our case, range deletion is only used to remove ranges
        // no longer owned by this node. In such case, stale keys would never be quried.
        readOptions = new ReadOptions().setIgnoreRangeDeletions(true);
        disableWAL = new WriteOptions().setDisableWAL(true);
        flushOptions = new FlushOptions().setWaitForFlush(true);

        // Register the mbean.
        mbeanName = getMbeanName(cfs.keyspace.getName(), cfs.getTableName());
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

    private void openRocksDB(String rocksDBTableDir, BlockBasedTableConfig tableOptions) throws RocksDBException
    {
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
            logger.warn("Failed to load latest RocksDB options for cf {}.{}",
                        cfs.keyspace.getName(), cfs.name);
        }

        final long writeBufferSize = RocksDBConfigs.WRITE_BUFFER_SIZE_MBYTES * 1024 * 1024L;
        final long softPendingCompactionBytesLimit = 64 * 1073741824L; //64G

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
            columnFamilyOptions.setCompressionType(RocksDBConfigs.COMPRESSION_TYPE);
            columnFamilyOptions.setBottommostCompressionType(RocksDBConfigs.BOTTOMMOST_COMPRESSION);
            columnFamilyOptions.setWriteBufferSize(writeBufferSize);
            columnFamilyOptions.setMaxWriteBufferNumber(4);
            columnFamilyOptions.setMaxBytesForLevelBase(RocksDBConfigs.MAX_MBYTES_FOR_LEVEL_BASE * 1024 * 1024L);
            columnFamilyOptions.setSoftPendingCompactionBytesLimit(softPendingCompactionBytesLimit);
            columnFamilyOptions.setHardPendingCompactionBytesLimit(8 * softPendingCompactionBytesLimit);
            columnFamilyOptions.setCompactionPriority(CompactionPriority.MinOverlappingRatio);
            columnFamilyOptions.setLevel0SlowdownWritesTrigger(RocksDBConfigs.LEVEL0_STOP_WRITES_TRIGGER);
            columnFamilyOptions.setLevel0StopWritesTrigger(RocksDBConfigs.LEVEL0_STOP_WRITES_TRIGGER);
            columnFamilyOptions.setLevelCompactionDynamicLevelBytes(!RocksDBConfigs.DYNAMIC_LEVEL_BYTES_DISABLED);

            ColumnFamilyDescriptor columnFamilyDescriptor = new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY, columnFamilyOptions);
            cfDescs.add(columnFamilyDescriptor);
        }

        assert cfDescs.size() == 1;
        assert Arrays.equals(cfDescs.get(0).columnFamilyName(), RocksDB.DEFAULT_COLUMN_FAMILY);

        ColumnFamilyOptions cfOptions = cfDescs.get(0).columnFamilyOptions();
        cfOptions.setMergeOperator(mergeOperator);
        cfOptions.setCompactionFilter(compactionFilter);
        cfOptions.setTableFormatConfig(tableOptions);

        dbOptions.setStatistics(stats);
        dbOptions.setRateLimiter(engine.rateLimiter);

        RocksDB rocksDB = RocksDB.open(dbOptions, rocksDBTableDir, cfDescs, new ArrayList<>(1));
        logger.info("Open rocksdb instance for cf {}.{} with path:{}, gcGraceSeconds:{}, purgeTTL:{}",
                    cfs.keyspace.getName(), cfs.name, rocksDBTableDir,
                    gcGraceSeconds, purgeTtlOnExpiration);

        rocksDBLists.add(rocksDB);
    }

    private RocksDB getRocksDBFromKey(DecoratedKey key)
    {
        return getRocksDBFromToken(key.getToken());
    }

    private RocksDB getRocksDBFromToken(Token token)
    {
        return rocksDBLists.get(Math.abs(token.hashCode()) % RocksDBConfigs.NUM_SHARD);
    }

    public static String getMbeanName(String keyspace, String table)
    {
        return String.format("org.apache.cassandra.rocksdbcf:keyspace=%s,table=%s", keyspace, table);
    }

    public RocksDB getRocksDB()
    {
        return rocksDBLists.get(0);
    }

    public RocksDB getRocksDB(int shardId)
    {
        return rocksDBLists.get(shardId);
    }

    public RocksDBTableMetrics getRocksMetrics()
    {
        return rocksMetrics;
    }

    public byte[] get(DecoratedKey partitionKey, byte[] key) throws RocksDBException
    {
        return getRocksDBFromKey(partitionKey).get(readOptions, key);
    }

    public IndexType getTableIndexType(String indexType)
    {
        try
        {
            return IndexType.valueOf(indexType);
        }
        catch (Throwable e)
        {
            logger.warn("Failed to set table index type " + indexType, e);
            logger.warn("Setting table index type to default: " + IndexType.kBinarySearch.toString());
            return IndexType.kBinarySearch;
        }
    }

    public RocksDBIteratorAdapter newIterator(DecoratedKey partitionKey)
    {
        return newIterator(partitionKey, readOptions);
    }

    public RocksDBIteratorAdapter newIterator(DecoratedKey partitionKey, ReadOptions options)
    {
        rocksMetrics.rocksDBIterNew.inc();
        RocksDB rocksDB = getRocksDBFromKey(partitionKey);
        return new RocksDBIteratorAdapter(rocksDB.newIterator(options), rocksMetrics);
    }

    public RocksDBIteratorAdapter newShardIterator(int shardId, ReadOptions options)
    {
        rocksMetrics.rocksDBIterNew.inc();
        RocksDB rocksDB = getRocksDB(shardId);
        return new RocksDBIteratorAdapter(rocksDB.newIterator(options), rocksMetrics);
    }

    public void merge(DecoratedKey partitionKey, byte[] key, byte[] value) throws RocksDBException
    {
        RocksDB rocksDB = getRocksDBFromKey(partitionKey);
        if (RocksDBConfigs.DISABLE_WRITE_TO_COMMITLOG)
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
        for (RocksDB rocksDB : rocksDBLists)
        {
            rocksDB.deleteFilesInRange(start, end);
            rocksDB.deleteRange(start, end);
        }
    }

    public void compactRange() throws RocksDBException
    {
        for (RocksDB rocksDB : rocksDBLists)
            rocksDB.compactRange();
    }

    public void forceFlush() throws RocksDBException
    {
        logger.info("Flushing rocksdb table: " + cfs.name);
        for (RocksDB rocksDB : rocksDBLists)
            rocksDB.flush(flushOptions);
    }

    public List<String> getProperty(String property) throws RocksDBException
    {
        // synchronize with close() function which modifies 'closed' field
        try
        {
            lockForClosedFlag.readLock().lock();
            // if close() function is called already, calling rocksDB.getProperty() will crash the process.
            // So return empty ArrayList instead.
            if (closed)
                return new ArrayList<>();
            List<String> properties = new ArrayList<>(rocksDBLists.size());
            for (RocksDB rocksDB : rocksDBLists)
                properties.add(rocksDB.getProperty(property));
            return properties;
        }
        finally
        {
            lockForClosedFlag.readLock().unlock();
        }
    }

    public void truncate() throws RocksDBException
    {
        // TODO: use delete range for now, could have a better solution
        for (RocksDB rocksDB : rocksDBLists)
            rocksDB.deleteRange(RowKeyEncoder.encodeToken(RocksDBUtils.getMinToken(partitioner)),
                            RowKeyEncoder.encodeToken(RocksDBUtils.getMaxToken(partitioner)));
    }

    protected void close() throws RocksDBException
    {
        logger.info("Closing rocksdb table: " + cfs.name);
        try
        {
            lockForClosedFlag.writeLock().lock();
            closed = true;
            for (RocksDB rocksDB : rocksDBLists)
                rocksDB.close();
            // remove the rocksdb instance, since it's not usable
            engine.rocksDBFamily.remove(new Pair<>(cfID, cfs.name));
        }
        finally
        {
            lockForClosedFlag.writeLock().unlock();
        }
    }

    public String dumpPrefix(DecoratedKey partitionKey, byte[] rocksKeyPrefix, int limit)
    {
        StringBuilder sb = new StringBuilder();
        try (RocksDBIteratorAdapter rocksIterator = newIterator(partitionKey))
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

    public UUID getCfID()
    {
        return cfID;
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
    public List<String> getRocksDBProperty(String property)
    {
        try
        {
            return getProperty(property);
        } catch (Throwable e) {
            logger.warn("Failed to get RocksDB property " + property, e);
            return Arrays.asList("Failed to get property:" + property + ", reason:" + e.toString());
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
            logger.warn("Failed to dump partition " + partitionKey, e);
            return "Failed to dump:" + partitionKey + ", reason:" + e.toString();
        }
    }

    @Override
    public String streamingConsistencyCheck(int expectedNumKeys)
    {
        return StreamingConsistencyCheckUtils.checkAndGenerateReport(cfs, expectedNumKeys);
    }
}
