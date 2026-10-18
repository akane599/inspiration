package android.os;

/**
 * Hidden framework UID battery-consumer type present since API 31.
 */
public final class UidBatteryConsumer extends BatteryConsumer {
    public int getUid() {
        throw new RuntimeException("Stub!");
    }

    public String getPackageWithHighestDrain() {
        throw new RuntimeException("Stub!");
    }
}
