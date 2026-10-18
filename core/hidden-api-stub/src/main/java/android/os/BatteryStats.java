package android.os;

/**
 * Hidden framework battery-stats base type. Only {@link HistoryItem}'s fields are stubbed; their
 * types are identical in the android-35, android-36 and android-37.0 sources and match the API 34
 * framework.jar (dexdump). {@code batteryVoltage} is deliberately absent: it is {@code char} on
 * API 34 and {@code short} on 35+, and ART links field reads by type, so it is read reflectively.
 */
public abstract class BatteryStats {
    public static final class HistoryItem {
        public long time;
        public long currentTime;
        public byte cmd;
        public byte batteryLevel;
        public byte batteryPlugType;
        public short batteryTemperature;
        public int batteryChargeUah;
        public int states;
        public int states2;
    }
}
