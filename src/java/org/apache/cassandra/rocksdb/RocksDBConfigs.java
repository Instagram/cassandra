package org.apache.cassandra.rocksdb;

import java.io.File;

import org.apache.cassandra.exceptions.ConfigurationException;
import org.rocksdb.CompressionType;
import org.rocksdb.IndexType;

public class RocksDBConfigs
{
    public static final CompressionType COMPRESSION_TYPE = CompressionType.LZ4_COMPRESSION;
    // RocksDB lowest level compression type
    public static CompressionType BOTTOMMOST_COMPRESSION =
        CompressionType.getCompressionType(System.getProperty("cassandra.rocksdb.bottommost_compression"));

    // Paths for storing RocksDB files.
    public static String ROCKSDB_DIR = System.getProperty("cassandra.rocksdb.dir", null);
    public static String STREAMING_TMPFILE_PATH_DIR = System.getProperty("cassandra.rocksdb.stream.dir", null);

    static
    {
        if (ROCKSDB_DIR == null)
        {
            ROCKSDB_DIR = System.getProperty("cassandra.storagedir", null);
            if (ROCKSDB_DIR == null)
                throw new ConfigurationException("cassandra.rocksdb.dir is missing and -Dcassandra.storagedir is not set", false);
            ROCKSDB_DIR += File.separator + "rocksdb";
        }

        if (STREAMING_TMPFILE_PATH_DIR == null)
        {
            STREAMING_TMPFILE_PATH_DIR = System.getProperty("cassandra.storagedir", null);
            if (STREAMING_TMPFILE_PATH_DIR == null)
                throw new ConfigurationException("cassandra.rocksdb.stream.dir is missing and -Dcassandra.storagedir is not set", false);
            STREAMING_TMPFILE_PATH_DIR += File.separator + "rocksdbstream";
        }
    }
    public static File STREAMING_TMPFILE_PATH = new File(STREAMING_TMPFILE_PATH_DIR);

    // Max levels for RocksDB. 7 is the default value.
    public static final int MAX_LEVELS = Integer.getInteger("cassandra.rocksdb.max_levels", 7);

    // Tables created in this keyspace is backed by RocksDB.
    public static String ROCKSDB_KEYSPACE = System.getProperty("cassandra.rocksdb.keyspace", "rocksdb");

    // RocksDB stopped write while number of level 0 sstables exceed this threshold. By default we set it to
    // a very large number to trade read performance for high stream throughput.
    public static final int LEVEL0_STOP_WRITES_TRIGGER = Integer.getInteger("cassandra.rocksdb.level0_stop_writes_trigger", 1024);

    // Number of rocksdb concurrent background compactions
    public static final int BACKGROUD_COMPACTIONS = Integer.getInteger("cassandra.rocksdb.background_compactions", 4);

    // rateBytesPerSecond for RocksDB's rate limiter, which is used to control write rate of flush and compaction.
    // It's per keyspace, and shared across all RocksDB instances within the same keyspace.
    public static final int RATE_MBYTES_PER_SECOND = Integer.getInteger("cassandra.rocksdb.rate_mbytes_per_second", 150);

    // rocksdb block cache size, default to be 1G per rocksdb instance
    public static final int BLOCK_CACHE_SIZE_MBYTES = Integer.getInteger("cassandra.rocksdb.block_cache_size_mbytes", 128);

    // disable level_compaction_dynamic_level_bytes or not, defaut is false
    public static final boolean DYNAMIC_LEVEL_BYTES_DISABLED = Boolean.getBoolean("cassandra.rocksdb.disable_dynamic_level_bytes");

    // rocksdb write buffer size, default to be 4G per rocksdb instance
    public static final int WRITE_BUFFER_SIZE_MBYTES = Integer.getInteger("cassandra.rocksdb.write_buffer_size_mbytes", 512);

    public static final int MAX_MBYTES_FOR_LEVEL_BASE = Integer.getInteger("cassandra.rocksdb.max_mbytes_for_level_base", 1024);

    // disable write to rocksdb commit log or not, default is false
    public static final boolean DISABLE_WRITE_TO_COMMITLOG = Boolean.getBoolean("cassandra.rocksdb.disable_write_to_commitlog");

    // Limit the number of operands in merge operator, 0 means no limit
    public static final int MERGE_OPERANDS_LIMIT = Integer.getInteger("cassandra.rocksdb.merge_operands_limit", 0);

    // number of sharded rocksdb instance in one Cassandra table
    public static int NUM_SHARD = Integer.getInteger("cassandra.rocksdb.num_shard", 1);

    public static final boolean CACHE_INDEX_AND_FILTER_BLOCKS = Boolean.getBoolean("cassandra.rocksdb.cache_index_and_filter_blocks");

    public static final boolean PIN_L0_FILTER_AND_INDEX_BLOCKS_IN_CACHE = Boolean.getBoolean("cassandra.rocksdb.pin_l0_filter_and_index_blocks_in_cache");

    // The indexing type that rocksdb will use
    public static final String TABLE_INDEX_TYPE = System.getProperty("cassandra.rocksdb.table_index_type", IndexType.kBinarySearch.toString());

    // Number of cuncurrent flushing tasks when flush/drain is called.
    public static final int FLUSH_CONCURRENCY = Integer.getInteger("cassandra.rocksdb.flush_concurrency", 8);

    /**
     * Streaming configs
     */
    // On the receiver side, we create a sstable and feed it to RocksDB every 512MB received.
    public static final long SSTABLE_INGEST_THRESHOLD = Long.parseLong(System.getProperty("cassandra.rocksdb.stream.sst_size", "536870912"));

    // The read buffer size while reading RocksDB and sending into output stream.
    public static final long STREAMING_READ_AHEAD_BUFFER_SIZE = Long.getLong("cassandra.rocksdb.stream.readahead_size", 10L * 1024 * 1024);

    /**
     * Testing configs
     */
    // Once enabled, the writes are written to both Cassandra and RocksDB which is future used to caclulate
    // the consistency and correctness of RocksDB.
    public static boolean ROCKSDB_DOUBLE_WRITE = Boolean.getBoolean("cassandra.rocksdb.double_write");

    static
    {
        if (BOTTOMMOST_COMPRESSION == CompressionType.NO_COMPRESSION)
        {
            BOTTOMMOST_COMPRESSION = COMPRESSION_TYPE;
        }
    }
}
