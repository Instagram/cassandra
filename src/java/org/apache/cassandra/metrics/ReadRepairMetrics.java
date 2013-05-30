package org.apache.cassandra.metrics;

import java.util.concurrent.TimeUnit;

import com.yammer.metrics.Metrics;
import com.yammer.metrics.core.Counter;
import com.yammer.metrics.core.Meter;
import com.yammer.metrics.core.MetricName;
import com.yammer.metrics.util.RatioGauge;

/**
 * Metrics related to Read Repair.
 */
public class ReadRepairMetrics {
	private static class ReadRepairRepairedRatioGauge extends RatioGauge {

		@Override
		protected double getDenominator() {
			return attempted.oneMinuteRate();
		}

		@Override
		protected double getNumerator() {
			return repaired.oneMinuteRate();
		}
		
	}
	
	public static final Meter repaired = Metrics.newMeter(new MetricName("org.apache.cassandra.metrics", "ReadRepair", "Repaired"), "Repaired", TimeUnit.SECONDS);
    public static final Meter attempted = Metrics.newMeter(new MetricName("org.apache.cassandra.metrics", "ReadRepair", "Attempted"), "Attempted", TimeUnit.SECONDS);
    static {
		Metrics.newGauge(new MetricName("org.apache.cassandra.metrics", "ReadRepair", "RepairedRatio"), new ReadRepairRepairedRatioGauge());
    }
}
