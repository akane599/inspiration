package android.os;

/**
 * Hidden framework power-manager binder interface.
 */
public interface IPowerManager extends IInterface {
    boolean isDeviceIdleMode() throws RemoteException;

    boolean isLightDeviceIdleMode() throws RemoteException;

    abstract class Stub extends Binder implements IPowerManager {
        public static IPowerManager asInterface(IBinder obj) {
            throw new RuntimeException("Stub!");
        }
    }
}
