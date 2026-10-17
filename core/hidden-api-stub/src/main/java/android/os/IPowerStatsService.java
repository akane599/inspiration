package android.os;

/**
 * Hidden framework power-stats binder interface present since API 34.
 */
public interface IPowerStatsService extends IInterface {
    void getSupportedPowerMonitors(ResultReceiver resultReceiver) throws RemoteException;

    void getPowerMonitorReadings(int[] powerMonitorIndices, ResultReceiver resultReceiver)
            throws RemoteException;

    abstract class Stub extends Binder implements IPowerStatsService {
        public static IPowerStatsService asInterface(IBinder obj) {
            throw new RuntimeException("Stub!");
        }
    }
}
