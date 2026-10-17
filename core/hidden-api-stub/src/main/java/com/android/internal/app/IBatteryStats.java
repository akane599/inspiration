package com.android.internal.app;

import android.os.BatteryUsageStats;
import android.os.BatteryUsageStatsQuery;
import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.RemoteException;

import java.util.List;

/**
 * Hidden framework binder interface present since API 31.
 */
public interface IBatteryStats extends IInterface {
    List<BatteryUsageStats> getBatteryUsageStats(List<BatteryUsageStatsQuery> queries)
            throws RemoteException;

    long computeBatteryTimeRemaining() throws RemoteException;

    long computeChargeTimeRemaining() throws RemoteException;

    abstract class Stub extends Binder implements IBatteryStats {
        public static IBatteryStats asInterface(IBinder obj) {
            throw new RuntimeException("Stub!");
        }
    }
}
