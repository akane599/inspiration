package com.android.internal.os;

import android.os.BatteryStats;
import java.util.Iterator;

/**
 * Hidden history iterator returned by {@code BatteryUsageStats.iterateBatteryStatsHistory()}.
 * The real class also implements {@code AutoCloseable} on API 34+; callers check that at runtime.
 * {@link #next()} reuses one mutable item and returns null at the end.
 */
public class BatteryStatsHistoryIterator implements Iterator<BatteryStats.HistoryItem> {
    @Override
    public boolean hasNext() {
        throw new RuntimeException("Stub!");
    }

    @Override
    public BatteryStats.HistoryItem next() {
        throw new RuntimeException("Stub!");
    }
}
