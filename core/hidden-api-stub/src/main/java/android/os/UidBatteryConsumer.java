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

    /**
     * API 33+ process-state accessor from AOSP: getTimeInProcessStateMs(int).
     *
     * @since 33
     */
    public long getTimeInProcessStateMs(int state) {
        throw new RuntimeException("Stub!");
    }
}
