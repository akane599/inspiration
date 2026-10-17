package android.os;

/**
 * Hidden framework battery-usage query type present since API 31.
 */
public final class BatteryUsageStatsQuery {
    private BatteryUsageStatsQuery() {
        throw new RuntimeException("Stub!");
    }

    public static final class Builder {
        public Builder setMaxStatsAgeMs(long maxStatsAgeMs) {
            throw new RuntimeException("Stub!");
        }

        /**
         * Present since API 33.
         */
        public Builder includeProcessStateData() {
            throw new RuntimeException("Stub!");
        }

        /**
         * @deprecated Since API 35 (Android 15), this method is a no-op.
         */
        @Deprecated
        public Builder includePowerModels() {
            throw new RuntimeException("Stub!");
        }

        /**
         * Present since API 33.
         */
        public Builder includeVirtualUids() {
            throw new RuntimeException("Stub!");
        }

        public Builder includeBatteryHistory() {
            throw new RuntimeException("Stub!");
        }

        /**
         * Present since API 36.
         */
        public Builder setPreferredHistoryDurationMs(long preferredHistoryDurationMs) {
            throw new RuntimeException("Stub!");
        }

        public BatteryUsageStatsQuery build() {
            throw new RuntimeException("Stub!");
        }
    }
}
