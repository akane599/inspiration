package com.akane.inspiration.ipc

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku

class ShizukuManagerImpl private constructor(
    private val context: Context,
    private val facade: ShizukuFacade,
    private val scope: CoroutineScope,
    private val applicationInfoProvider: ShizukuApplicationInfoProvider,
    private val ioDispatcher: CoroutineDispatcher,
    @Suppress("unused") ignored: Unit,
) : ShizukuManager {
    constructor(
        context: Context,
        facade: ShizukuFacade = RealShizukuFacade,
        scope: CoroutineScope,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) : this(context, facade, scope, androidApplicationInfoProvider(context), ioDispatcher, Unit)

    internal constructor(
        context: Context,
        facade: ShizukuFacade,
        scope: CoroutineScope,
        applicationInfoProvider: ShizukuApplicationInfoProvider,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) : this(context, facade, scope, applicationInfoProvider, ioDispatcher, Unit)

    private val mutableState = MutableStateFlow<ShizukuState>(ShizukuState.NotRunning)
    private val mutableTelemetryService = MutableStateFlow<ITelemetryUserService?>(null)
    private val bindingLock = Any()

    override val state: StateFlow<ShizukuState> = mutableState.asStateFlow()
    override val telemetryService: StateFlow<ITelemetryUserService?> = mutableTelemetryService.asStateFlow()

    private var started = false
    private var serviceBound = false
    private var serviceGeneration = 0
    private var activeServiceUsers = 0
    private var bindingHolders = 0
    private var applicationInfo: ShizukuApplicationInfo? = null
    private var serviceArgs: Shizuku.UserServiceArgs? = null

    /**
     * Set by an unbind(remove=true) until the destroyed process's death reaches [userServiceConnection].
     * Shizuku keeps one connection stub per service class and remove=true does not clear it, so a bind
     * issued before that death would share the stub: the old death would then disconnect the new binding
     * and swallow its connect (SQ-114). Binds are deferred until the death (or [UNBIND_SETTLE_TIMEOUT_MS]).
     */
    private var awaitingUnboundDeath = false
    private var settleTimeout: Job? = null
    private var idleUnbind: Job? = null

    private val userServiceConnection =
        object : ServiceConnection {
            override fun onServiceConnected(
                name: ComponentName,
                service: IBinder,
            ) {
                synchronized(bindingLock) {
                    // A late connect from a binding already unbound must not resurrect a dying service.
                    if (serviceBound && !awaitingUnboundDeath) {
                        mutableTelemetryService.value = ITelemetryUserService.Stub.asInterface(service)
                    }
                }
            }

            override fun onServiceDisconnected(name: ComponentName) {
                synchronized(bindingLock) {
                    if (awaitingUnboundDeath) {
                        // The unbound process died and Shizuku dropped its stub: a fresh bind is safe now.
                        finishUnbindSettleLocked()
                        return
                    }
                    serviceBound = false
                    serviceGeneration += 1
                    serviceArgs = null
                    mutableTelemetryService.value = null
                }
            }
        }

    private val binderReceivedListener =
        Shizuku.OnBinderReceivedListener {
            updateState()
        }

    private val binderDeadListener =
        Shizuku.OnBinderDeadListener {
            synchronized(bindingLock) {
                serviceBound = false
                serviceGeneration += 1
                serviceArgs = null
                awaitingUnboundDeath = false
                settleTimeout?.cancel()
                settleTimeout = null
                idleUnbind?.cancel()
                idleUnbind = null
                mutableTelemetryService.value = null
                mutableState.value = ShizukuState.NotRunning
            }
        }

    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                updateState()
            } else {
                setUnavailableState(ShizukuState.PermissionDenied)
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

    /**
     * [block] runs on [ioDispatcher]: every service call is a blocking Binder round trip into the shell-UID
     * process (a `cmd` run can take seconds), and callers include `viewModelScope` on the main thread.
     */
    override suspend fun <T> withTelemetryService(
        timeoutMs: Long,
        block: suspend (ITelemetryUserService) -> T,
    ): T? {
        if (!acquireServiceUse()) {
            return null
        }
        try {
            val service = withTimeoutOrNull(timeoutMs) { telemetryService.filterNotNull().first() } ?: return null
            val serviceGeneration = synchronized(bindingLock) { serviceGeneration }
            return try {
                val result = withContext(ioDispatcher) { block(service) }
                if (synchronized(bindingLock) { serviceGeneration == this.serviceGeneration }) {
                    result
                } else {
                    null
                }
            } catch (_: RemoteException) {
                null
            }
        } finally {
            releaseServiceUse()
        }
    }

    override fun holdBinding(): AutoCloseable {
        synchronized(bindingLock) {
            bindingHolders += 1
            bindIfRequiredLocked()
        }
        return BindingHolder()
    }

    override fun stop() {
        if (!started) {
            return
        }
        started = false
        facade.removeBinderReceivedListener(binderReceivedListener)
        facade.removeBinderDeadListener(binderDeadListener)
        facade.removeRequestPermissionResultListener(permissionResultListener)
        synchronized(bindingLock) {
            unbindTelemetryServiceLocked()
            mutableTelemetryService.value = null
        }
    }

    private fun acquireServiceUse(): Boolean =
        synchronized(bindingLock) {
            if (mutableState.value !is ShizukuState.Ready || applicationInfo == null) {
                false
            } else {
                activeServiceUsers += 1
                bindIfRequiredLocked()
                true
            }
        }

    private fun releaseServiceUse() {
        synchronized(bindingLock) {
            activeServiceUsers -= 1
            scheduleIdleUnbindLocked()
        }
    }

    /**
     * When the last user or holder goes, the service lingers [IDLE_UNBIND_DELAY_MS] before unbind(remove=true),
     * so screen handovers and the back-to-back calls of one sheet reuse it instead of killing and restarting a
     * process each time. Any new user/holder cancels it; stop() and an unavailable state unbind at once. Kept
     * short so the service still goes away right after the app leaves the foreground (H13).
     */
    private fun scheduleIdleUnbindLocked() {
        if (activeServiceUsers != 0 || bindingHolders != 0 || !serviceBound) {
            return
        }
        idleUnbind?.cancel()
        idleUnbind =
            scope.launch {
                delay(IDLE_UNBIND_DELAY_MS)
                synchronized(bindingLock) {
                    idleUnbind = null
                    if (activeServiceUsers == 0 && bindingHolders == 0) {
                        unbindTelemetryServiceLocked()
                    }
                }
            }
    }

    private fun finishUnbindSettleLocked() {
        awaitingUnboundDeath = false
        settleTimeout?.cancel()
        settleTimeout = null
        bindIfRequiredLocked()
    }

    private fun updateState() {
        val info = applicationInfoProvider.load()
        if (info == null) {
            setUnavailableState(ShizukuState.NotInstalled)
            return
        }
        val isPreV11 = facade.isPreV11()
        if (!facade.pingBinder() || isPreV11) {
            if (isPreV11) {
                logUnsupportedVersion()
            }
            setUnavailableState(ShizukuState.NotRunning)
            return
        }

        when (facade.checkSelfPermission()) {
            PackageManager.PERMISSION_GRANTED -> {
                synchronized(bindingLock) {
                    applicationInfo = info
                    mutableState.value = ShizukuState.Ready(facade.getUid(), facade.getVersion())
                    bindIfRequiredLocked()
                }
            }

            else -> {
                val unavailableState =
                    if (facade.shouldShowRequestPermissionRationale()) {
                        ShizukuState.PermissionDenied
                    } else {
                        ShizukuState.PermissionRequired
                    }
                setUnavailableState(unavailableState)
            }
        }
    }

    private fun setUnavailableState(newState: ShizukuState) {
        synchronized(bindingLock) {
            applicationInfo = null
            unbindTelemetryServiceLocked()
            mutableTelemetryService.value = null
            mutableState.value = newState
        }
    }

    private fun bindIfRequiredLocked() {
        val info = applicationInfo ?: return
        if (activeServiceUsers == 0 && bindingHolders == 0) {
            return
        }
        idleUnbind?.cancel()
        idleUnbind = null
        if (serviceBound || awaitingUnboundDeath) {
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

    private fun unbindTelemetryServiceLocked() {
        idleUnbind?.cancel()
        idleUnbind = null
        val args = serviceArgs ?: return
        if (serviceBound) {
            facade.unbindUserService(args, userServiceConnection, true)
            awaitingUnboundDeath = true
            settleTimeout?.cancel()
            settleTimeout =
                scope.launch {
                    delay(UNBIND_SETTLE_TIMEOUT_MS)
                    synchronized(bindingLock) {
                        settleTimeout = null
                        if (awaitingUnboundDeath) finishUnbindSettleLocked()
                    }
                }
        }
        serviceBound = false
        serviceArgs = null
        serviceGeneration += 1
        mutableTelemetryService.value = null
    }

    private inner class BindingHolder : AutoCloseable {
        private var closed = false

        override fun close() {
            synchronized(bindingLock) {
                if (closed) {
                    return
                }
                closed = true
                bindingHolders -= 1
                scheduleIdleUnbindLocked()
            }
        }
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

        /** Idle linger before unbind(remove=true); short on purpose (H13). */
        const val IDLE_UNBIND_DELAY_MS = 3_000L

        /** Fallback when an unbound service's death never arrives (e.g. it never started). */
        const val UNBIND_SETTLE_TIMEOUT_MS = 2_000L
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
