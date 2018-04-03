package org.apache.cassandra.rocksdb;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import org.apache.cassandra.db.ColumnFamilyStore;
import org.apache.cassandra.db.SinglePartitionReadCommand;
import org.apache.cassandra.rocksdb.tools.RowIteratorSanityCheck;
import org.apache.cassandra.rocksdb.tools.SanityCheckUtils;

import static org.junit.Assert.assertEquals;

public class RocksDBDoubleWriteSanityCheckTest extends RocksDBTestBase
{
    @BeforeClass
    public static void classSetUp()
    {
        RocksDBTestBase.classSetUp();
        RocksDBConfigs.ROCKSDB_DOUBLE_WRITE = true;
    }

    @Test
    public void test() throws Throwable
    {
        // There is some race condition between test set up and tear down which prevents me from spliting this into multiple tests.
        // Took me some time to debug but I failed to find the root cause. I'll leave it as it's now.
        createTable("CREATE TABLE %s (p text, v text, PRIMARY KEY (p))");
        execute("INSERT INTO %s(p, v) values (?, ?)", "p1", "v0");
        execute("INSERT INTO %s(p, v) values (?, ?)", "p2", "v0");
        execute("INSERT INTO %s(p, v) values (?, ?)", "p3", "v0");

        SinglePartitionReadCommand readCommand = readCommand("p1", "v");

        // Assert both RocksDB and Cassandra hava data.
        assertEquals(1, queryEngine(readCommand).size());
        assertEquals(1, queryCassandraStorage(readCommand).size());

        // Verify the correctness of report.
        ColumnFamilyStore cfs = getCurrentColumnFamilyStore();
        RowIteratorSanityCheck.Report report = SanityCheckUtils.checkSanity(cfs, false, 0, false);
        assertEquals(report.partitions, 3);
        assertEquals(report.cassandraMissingPartitions, 0);
        assertEquals(report.rocksDBMissingPartitions, 0);
        assertEquals(report.mismatcPartitions, 0);
        assertEquals(report.partitionDeletionMismatch, 0);
        assertEquals(report.rangeTombstoneSkipped, 0);
        assertEquals(report.rows, 3);
        assertEquals(report.cassandraMissingRows, 0);
        assertEquals(report.rocksDBMissingRows, 0);
        assertEquals(report.mismatchRows, 0);

        // Verify limit works.
        int limit = 1;
        report = SanityCheckUtils.checkSanity(cfs, false, limit, false);
        assertEquals(report.partitions, limit);
    }
}
