package com.akane.inspiration.ipc

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku

class ShizukuManagerImpl private constructor(
    private val context: Context,
    private val facade: ShizukuFacade,
    @Suppress("unused") private val scope: CoroutineScope,
    private val applicationInfoProvider: ShizukuApplicationInfoProvider,
    @Suppress("unused") ignored: Unit,
) : ShizukuManager {
    constructor(
        context: Context,
        facade: ShizukuFacade = RealShizukuFacade,
        scope: CoroutineScope,
    ) : this(context, facade, scope, androidApplicationInfoProvider(context), Unit)

    internal constructor(
        context: Context,
        facade: ShizukuFacade,
        scope: CoroutineScope,
        applicationInfoProvider: ShizukuApplicationInfoProvider,
    ) : this(context, facade, scope, applicationInfoProvider, Unit)

    private val mutableState = MutableStateFlow<ShizukuState>(ShizukuState.NotRunning)
    private val mutableTelemetryService = MutableStateFlow<ITelemetryUserService?>(null)

    override val state: StateFlow<ShizukuState> = mutableState.asStateFlow()
    override val telemetryService: StateFlow<ITelemetryUserService?> = mutableTelemetryService.asStateFlow()

    private var started = false
    private var serviceBound = false
    private var applicationInfo: ShizukuApplicationInfo? = null
    private var serviceArgs: Shizuku.UserServiceArgs? = null

    private val userServiceConnection =
        object : ServiceConnection {
            override fun onServiceConnected(
                name: ComponentName,
                service: IBinder,
            ) {
                mutableTelemetryService.value = ITelemetryUserService.Stub.asInterface(service)
            }

            override fun onServiceDisconnected(name: ComponentName) {
                serviceBound = false
                mutableTelemetryService.value = null
            }
        }

    private val binderReceivedListener =
        Shizuku.OnBinderReceivedListener {
            updateState()
        }

    private val binderDeadListener =
        Shizuku.OnBinderDeadListener {
            serviceBound = false
            mutableTelemetryService.value = null
            mutableState.value = ShizukuState.NotRunning
        }

    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                updateState()
            } else {
                mutableState.value = ShizukuState.PermissionDenied
                mutableTelemetryService.value = null
            }
        }

    override fun requestPermission() {
        if (!started || mutableState.value !is ShizukuState.PermissionRequired) {
            return
        }
        facade.requestPermission(PERMISSION_REQUEST_CODE)
    }

    override fun start() {
        if (started) {
            return
        }
        started = true
        facade.addBinderReceivedListenerSticky(binderReceivedListener)
        facade.addBinderDeadListener(binderDeadListener)
        facade.addRequestPermissionResultListener(permissionResultListener)
        updateState()
    }

    override fun stop() {
        if (!started) {
            return
        }
        started = false
        facade.removeBinderReceivedListener(binderReceivedListener)
        facade.removeBinderDeadListener(binderDeadListener)
        facade.removeRequestPermissionResultListener(permissionResultListener)
        unbindTelemetryService()
        mutableTelemetryService.value = null
    }

    private fun updateState() {
        val info = applicationInfoProvider.load()
        applicationInfo = info
        if (info == null) {
            mutableTelemetryService.value = null
            mutableState.value = ShizukuState.NotInstalled
            return
        }
        val isPreV11 = facade.isPreV11()
        if (!facade.pingBinder() || isPreV11) {
            if (isPreV11) {
                logUnsupportedVersion()
            }
            mutableTelemetryService.value = null
            mutableState.value = ShizukuState.NotRunning
            return
        }

        when (facade.checkSelfPermission()) {
            PackageManager.PERMISSION_GRANTED -> {
                mutableState.value = ShizukuState.Ready(facade.getUid(), facade.getVersion())
                bindTelemetryService(info)
            }

            else -> {
                mutableTelemetryService.value = null
                mutableState.value =
                    if (facade.shouldShowRequestPermissionRationale()) {
                        ShizukuState.PermissionDenied
                    } else {
                        ShizukuState.PermissionRequired
                    }
            }
        }
    }

    private fun bindTelemetryService(info: ShizukuApplicationInfo) {
        if (serviceBound) {
            return
        }
        val args =
            shizukuUserServiceArgs(
                ComponentName(context.packageName, TELEMETRY_USER_SERVICE_CLASS),
                info,
            )
        serviceArgs = args
        serviceBound = true
        facade.bindUserService(args, userServiceConnection)
    }

    private fun unbindTelemetryService() {
        val args = serviceArgs ?: return
        if (serviceBound) {
            facade.unbindUserService(args, userServiceConnection, true)
        }
        serviceBound = false
        serviceArgs = null
    }

    private fun logUnsupportedVersion() {
        try {
            Log.w(TAG, "Shizuku versions before v11 are unsupported")
        } catch (_: RuntimeException) {
            // Android local unit tests provide a stub Log implementation.
        }
    }

    private companion object {
        const val PERMISSION_REQUEST_CODE = 1
        const val TAG = "ShizukuManager"
        const val TELEMETRY_USER_SERVICE_CLASS =
            "com.akane.inspiration.ipc.service.TelemetryUserService"
    }
}

private fun androidApplicationInfoProvider(context: Context): ShizukuApplicationInfoProvider =
    ShizukuApplicationInfoProvider {
        try {
            // Null means the Shizuku manager is absent (visible via <queries> in the app manifest).
            context.packageManager.getPackageInfo(SHIZUKU_MANAGER_PACKAGE, 0)
            context.packageManager.getPackageInfo(context.packageName, 0).let { packageInfo ->
                ShizukuApplicationInfo(
                    versionCode = packageInfo.longVersionCode,
                    debuggable = (packageInfo.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_DEBUGGABLE != 0,
                )
            }
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }

private const val SHIZUKU_MANAGER_PACKAGE = "moe.shizuku.privileged.api"
