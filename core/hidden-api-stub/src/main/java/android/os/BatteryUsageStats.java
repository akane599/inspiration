package android.os;

import com.android.internal.os.BatteryStatsHistoryIterator;
import java.io.Closeable;
import java.util.List;

/**
 * Hidden framework battery-usage result type present since API 31. The real class implements
 * {@code Closeable} on the releases checked (android-35/36/37.0); callers check that at runtime.
 */
public final class BatteryUsageStats implements Closeable {
    public static final int AGGREGATE_BATTERY_CONSUMER_SCOPE_DEVICE = 0;

    public long getStatsStartTimestamp() {
        throw new RuntimeException("Stub!");
    }

    public long getStatsEndTimestamp() {
        throw new RuntimeException("Stub!");
    }

    public long getStatsDuration() {
        throw new RuntimeException("Stub!");
    }

    public double getConsumedPower() {
        throw new RuntimeException("Stub!");
    }

    public AggregateBatteryConsumer getAggregateBatteryConsumer(int scope) {
        throw new RuntimeException("Stub!");
    }

    public List<UidBatteryConsumer> getUidBatteryConsumers() {
        throw new RuntimeException("Stub!");
    }

    public int getDischargePercentage() {
        throw new RuntimeException("Stub!");
    }

    @Override
    public void close() {
        throw new RuntimeException("Stub!");
    }

    /**
     * Requires {@code BatteryUsageStatsQuery.Builder.includeBatteryHistory()}.
     */
    public BatteryStatsHistoryIterator iterateBatteryStatsHistory() {
        throw new RuntimeException("Stub!");
    }
}
