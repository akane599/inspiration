package android.os;

/**
 * Hidden framework battery-stats base type. Only {@link HistoryItem}'s fields are stubbed; their
 * types are identical in the android-35, android-36 and android-37.0 sources.
 */
public abstract class BatteryStats {
    public static final class HistoryItem {
        public long time;
        public long currentTime;
        public byte cmd;
        public byte batteryLevel;
        public byte batteryPlugType;
        public short batteryTemperature;
        public short batteryVoltage;
        public int batteryChargeUah;
        public int states;
        public int states2;
    }
}
