package org.apache.cassandra.metrics;

import java.util.concurrent.TimeUnit;

import com.yammer.metrics.Metrics;
import com.yammer.metrics.core.Counter;
import com.yammer.metrics.core.Gauge;
import com.yammer.metrics.core.Meter;
import com.yammer.metrics.core.MetricName;
import com.yammer.metrics.util.RatioGauge;

/**
 * Metrics related to Read Repair.
 */
public class ReadRepairMetrics {
	/*
	private static class ReadRepairRepairedRatioGauge extends RatioGauge {

		@Override
		protected double getDenominator() {
			return attempted.fifteenMinuteRate();
		}

		@Override
		protected double getNumerator() {
			return repaired.fifteenMinuteRate();
		}
		
	}
	*/
	
	public static final Meter repaired_blocking = Metrics.newMeter(new MetricName("org.apache.cassandra.metrics", "ReadRepair", "RepairedBlocking"), "RepairedBlocking", TimeUnit.SECONDS);
	public static final Meter repaired_background = Metrics.newMeter(new MetricName("org.apache.cassandra.metrics", "ReadRepair", "RepairedBackground"), "RepairedBackground", TimeUnit.SECONDS);
    public static final Meter attempted = Metrics.newMeter(new MetricName("org.apache.cassandra.metrics", "ReadRepair", "Attempted"), "Attempted", TimeUnit.SECONDS);
    //public static final Gauge<Double> ratio = Metrics.newGauge(new MetricName("org.apache.cassandra.metrics", "ReadRepair", "RepairedRatio"), new ReadRepairRepairedRatioGauge());
}
