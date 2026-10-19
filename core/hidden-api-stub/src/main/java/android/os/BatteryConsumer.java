package android.os;

/**
 * Hidden framework battery-consumer base type present since API 31.
 *
 * <p>The stub deliberately declares no {@code POWER_COMPONENT_*} or {@code PROCESS_STATE_*} constants. They are
 * compile-time constants that javac inlines, and some values differ between API levels. The app keeps its own
 * version-checked id tables in {@code BatteryUsageMapper}.
 */
public class BatteryConsumer {
    public double getConsumedPower() {
        throw new RuntimeException("Stub!");
    }

    public double getConsumedPower(int componentId) {
        throw new RuntimeException("Stub!");
    }
}
