package android.os;

/**
 * Hidden framework device-idle binder interface.
 */
public interface IDeviceIdleController extends IInterface {
    String[] getFullPowerWhitelist() throws RemoteException;

    boolean isPowerSaveWhitelistApp(String name) throws RemoteException;

    abstract class Stub extends Binder implements IDeviceIdleController {
        public static IDeviceIdleController asInterface(IBinder obj) {
            throw new RuntimeException("Stub!");
        }
    }
}
