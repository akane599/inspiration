package android.os;

import java.util.List;

/**
 * Hidden framework battery-usage result type present since API 31.
 */
public final class BatteryUsageStats {
    public long getStatsStartTimestamp() {
        throw new RuntimeException("Stub!");
    }

    public long getStatsEndTimestamp() {
        throw new RuntimeException("Stub!");
    }

    public double getConsumedPower() {
        throw new RuntimeException("Stub!");
    }

    public List<UidBatteryConsumer> getUidBatteryConsumers() {
        throw new RuntimeException("Stub!");
    }

    public int getDischargePercentage() {
        throw new RuntimeException("Stub!");
    }
}
