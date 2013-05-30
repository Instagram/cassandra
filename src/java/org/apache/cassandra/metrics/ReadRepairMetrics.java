package org.apache.cassandra.metrics;

import com.yammer.metrics.Metrics;
import com.yammer.metrics.core.Counter;
import com.yammer.metrics.core.MetricName;

/**
 * Metrics related to Read Repair.
 */
public class ReadRepairMetrics {

    public static final Counter meter = Metrics.newCounter(new MetricName("org.apache.cassandra.metrics", "ReadRepair", "Repaired"));
    //public static final Counter load2 = Metrics.newCounter(new MetricName("org.apache.cassandra.metrics", "Storage", "Load2"));

    public static void mark() {
    	meter.inc();
    }
}
