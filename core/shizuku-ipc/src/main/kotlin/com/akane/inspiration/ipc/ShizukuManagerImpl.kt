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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.job
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

    /**
     * Set when [UNBIND_SETTLE_TIMEOUT_MS] bound before the unbound process's death arrived. The new binding
     * then shares that process's connection stub, so its late death still reaches [userServiceConnection]
     * (SQ-118). That one disconnect is swallowed while the new binder is alive; a dead or not-yet-connected
     * new binder is handled as a real disconnect.
     */
    private var unboundDeathOwed = false
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
                    if (unboundDeathOwed) {
                        unboundDeathOwed = false
                        if (mutableTelemetryService.value?.asBinder()?.isBinderAlive == true) {
                            return // the old process's late death, not ours
                        }
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
                unboundDeathOwed = false
                settleTimeout?.cancel()
                settleTimeout = null
                idleUnbind?.cancel()
                idleUnbind = null
                mutableTelemetryService.value = null
                mutableState.value = ShizukuState.NotRunning
            }
        }

    /**
     * The result only says what the user tapped. Whether a deny was stored (so Shizuku will not ask again) is
     * Shizuku's rationale flag, which [refreshState] reads: a one-time deny must stay requestable.
     */
    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { _, _ -> updateState() }

    override fun requestPermission() {
        if (!started) {
            return
        }
        // Shizuku sends no callback for a grant made in its own app, and a stale state must not block the dialog.
        updateState()
        if (mutableState.value !is ShizukuState.PermissionRequired) {
            return
        }
        try {
            facade.requestPermission(PERMISSION_REQUEST_CODE)
        } catch (_: RuntimeException) {
            synchronized(bindingLock) { transportLostLocked() }
        }
    }

    override fun refresh() {
        if (started) {
            updateState()
        }
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
     *
     * [timeoutMs] covers reaching Ready and binding. In a cold process Shizuku pushes its binder some time after
     * start(), so NotRunning is waited out; any other state that is not Ready returns null at once.
     */
    override suspend fun <T> withTelemetryService(
        timeoutMs: Long,
        block: suspend (ITelemetryUserService) -> T,
    ): T? {
        var acquired = false
        try {
            // The service and its generation are read under one lock, so a rebind in between cannot pass.
            val (service, serviceGeneration) =
                withTimeoutOrNull(timeoutMs) {
                    acquired = awaitServiceUse()
                    if (!acquired) return@withTimeoutOrNull null
                    telemetryService
                        .mapNotNull { currentServiceAndGeneration() }
                        .first()
                } ?: return null
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
            if (acquired) releaseServiceUse()
        }
    }

    /**
     * Acquires a service use, first waiting while Shizuku is NotRunning: in a cold process that is the state
     * before the binder push, which a call made right away must outlast. Any other state that is not Ready
     * (no permission, not installed, a state added later) cannot turn into Ready by itself, so it fails at once.
     */
    private suspend fun awaitServiceUse(): Boolean {
        while (true) {
            if (acquireServiceUse()) return true
            if (!started || mutableState.value !is ShizukuState.NotRunning) return false
            mutableState.first { it !is ShizukuState.NotRunning }
        }
    }

    private fun currentServiceAndGeneration(): Pair<ITelemetryUserService, Int>? =
        synchronized(bindingLock) {
            mutableTelemetryService.value?.let { it to serviceGeneration }
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
            // Without it a still-open holder/user would rebind on the next death or settle timeout.
            applicationInfo = null
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
                    // cancel() cannot stop a body already waiting for the lock; every cancel also replaces/nulls it.
                    if (idleUnbind !== coroutineContext.job) return@launch
                    idleUnbind = null
                    if (activeServiceUsers == 0 && bindingHolders == 0) {
                        unbindTelemetryServiceLocked()
                    }
                }
            }
    }

    private fun finishUnbindSettleLocked(byTimeout: Boolean = false) {
        awaitingUnboundDeath = false
        unboundDeathOwed = byTimeout
        settleTimeout?.cancel()
        settleTimeout = null
        bindIfRequiredLocked()
    }

    private fun updateState() {
        try {
            refreshState()
        } catch (_: RuntimeException) {
            synchronized(bindingLock) { transportLostLocked() }
        }
    }

    private fun refreshState() {
        val info = applicationInfoProvider.load()
        if (info == null) {
            setUnavailableState(ShizukuState.NotInstalled)
            return
        }
        val isPreV11 = facade.isPreV11()
        if (!facade.pingBinder() || isPreV11) {
            if (isPreV11) {
                logUnsupportedVersion("Shizuku versions before v11 are unsupported")
            }
            setUnavailableState(ShizukuState.NotRunning)
            return
        }
        // Checked before the permission: granting cannot help an old server. -1 means the server neither sent its
        // version on attach nor answers a caller without access; the refresh after the grant judges it then.
        val serverVersion = facade.getVersion()
        if (serverVersion in 0 until MIN_SERVER_VERSION) {
            logUnsupportedVersion("Shizuku server API $serverVersion is unsupported; v$MIN_SERVER_VERSION+ is required")
            setUnavailableState(ShizukuState.UnsupportedServer(serverVersion))
            return
        }

        when (facade.checkSelfPermission()) {
            PackageManager.PERMISSION_GRANTED -> {
                synchronized(bindingLock) {
                    applicationInfo = info
                    mutableState.value = ShizukuState.Ready(facade.getUid(), serverVersion)
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

    /** Owners keep their counts and still release normally after transport loss. */
    private fun transportLostLocked() {
        serviceBound = false
        serviceArgs = null
        serviceGeneration += 1
        awaitingUnboundDeath = false
        unboundDeathOwed = false
        settleTimeout?.cancel()
        settleTimeout = null
        idleUnbind?.cancel()
        idleUnbind = null
        applicationInfo = null
        mutableTelemetryService.value = null
        mutableState.value = ShizukuState.NotRunning
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
        try {
            facade.bindUserService(args, userServiceConnection)
        } catch (_: RuntimeException) {
            transportLostLocked()
        }
    }

    private fun unbindTelemetryServiceLocked() {
        idleUnbind?.cancel()
        idleUnbind = null
        val args = serviceArgs ?: return
        if (serviceBound) {
            try {
                facade.unbindUserService(args, userServiceConnection, true)
            } catch (_: RuntimeException) {
                transportLostLocked()
                return
            }
            awaitingUnboundDeath = true
            // Any owed death now arrives as the awaited one: the shared stub's died() fires once for both.
            unboundDeathOwed = false
            settleTimeout?.cancel()
            settleTimeout =
                scope.launch {
                    delay(UNBIND_SETTLE_TIMEOUT_MS)
                    synchronized(bindingLock) {
                        if (settleTimeout !== coroutineContext.job) return@launch
                        settleTimeout = null
                        if (awaitingUnboundDeath) finishUnbindSettleLocked(byTimeout = true)
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

    private fun logUnsupportedVersion(message: String) {
        try {
            Log.w(TAG, message)
        } catch (_: RuntimeException) {
            // Android local unit tests provide a stub Log implementation.
        }
    }

    private companion object {
        const val PERMISSION_REQUEST_CODE = 1
        const val TAG = "ShizukuManager"

        /** Older servers build TelemetryUserService without a Context, and it then rejects every caller. */
        const val MIN_SERVER_VERSION = 13

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
