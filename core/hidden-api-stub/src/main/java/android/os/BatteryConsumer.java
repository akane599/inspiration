package android.os;

/**
 * Hidden framework battery-consumer base type present since API 31.
 */
public class BatteryConsumer {
    public static final int POWER_COMPONENT_ANY = -1;
    public static final int POWER_COMPONENT_SCREEN = 0;
    public static final int POWER_COMPONENT_CPU = 1;
    public static final int POWER_COMPONENT_BLUETOOTH = 2;
    public static final int POWER_COMPONENT_CAMERA = 3;
    public static final int POWER_COMPONENT_AUDIO = 4;
    public static final int POWER_COMPONENT_VIDEO = 5;
    public static final int POWER_COMPONENT_FLASHLIGHT = 6;
    public static final int POWER_COMPONENT_SYSTEM_SERVICES = 7;
    public static final int POWER_COMPONENT_MOBILE_RADIO = 8;
    public static final int POWER_COMPONENT_SENSORS = 9;
    public static final int POWER_COMPONENT_GNSS = 10;
    public static final int POWER_COMPONENT_WIFI = 11;
    public static final int POWER_COMPONENT_WAKELOCK = 12;
    public static final int POWER_COMPONENT_MEMORY = 13;
    public static final int POWER_COMPONENT_PHONE = 14;
    public static final int POWER_COMPONENT_AMBIENT_DISPLAY = 15;
    public static final int POWER_COMPONENT_IDLE = 16;
    public static final int POWER_COMPONENT_REATTRIBUTED_TO_OTHER_CONSUMERS = 17;
    public static final int POWER_COMPONENT_BASE = 18;
    public static final int POWER_COMPONENT_COUNT = 19;

    public static final int PROCESS_STATE_ANY = -1;
    public static final int PROCESS_STATE_UNSPECIFIED = 0;
    public static final int PROCESS_STATE_FOREGROUND = 1;
    public static final int PROCESS_STATE_BACKGROUND = 2;
    public static final int PROCESS_STATE_FOREGROUND_SERVICE = 3;
    public static final int PROCESS_STATE_CACHED = 4;
    public static final int PROCESS_STATE_COUNT = 5;

    public double getConsumedPower() {
        throw new RuntimeException("Stub!");
    }

    public double getConsumedPower(int componentId) {
        throw new RuntimeException("Stub!");
    }
}
