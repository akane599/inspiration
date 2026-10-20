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
import java.util.concurrent.atomic.AtomicLong

class ShizukuManagerImpl private constructor(
    private val context: Context,
    private val facade: ShizukuFacade,
    private val scope: CoroutineScope,
    private val applicationInfoProvider: ShizukuApplicationInfoProvider,
    private val managerApp: ShizukuManagerAppProbe,
    private val ioDispatcher: CoroutineDispatcher,
    @Suppress("unused") ignored: Unit,
) : ShizukuManager {
    constructor(
        context: Context,
        facade: ShizukuFacade = RealShizukuFacade,
        scope: CoroutineScope,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) : this(
        context,
        facade,
        scope,
        androidApplicationInfoProvider(context),
        androidManagerAppProbe(context),
        ioDispatcher,
        Unit,
    )

    internal constructor(
        context: Context,
        facade: ShizukuFacade,
        scope: CoroutineScope,
        applicationInfoProvider: ShizukuApplicationInfoProvider,
        managerApp: ShizukuManagerAppProbe,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) : this(context, facade, scope, applicationInfoProvider, managerApp, ioDispatcher, Unit)

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
     * Whether this process has had a Shizuku binder. Once it has, a missing manager app (Sui) cannot mean "not
     * installed": a lost binder is a stopped server, which may come back and which callers wait for (N-08).
     */
    @Volatile private var binderSeen = false

    /**
     * Set by an unbind(remove=true) until the destroyed process's death reaches [userServiceConnection].
     * Shizuku keeps one connection stub per service class and remove=true does not clear it, so a bind
     * issued before that death would share the stub: the old death would then disconnect the new binding
     * and swallow its connect (SQ-114). Binds are deferred until the death (or [UNBIND_SETTLE_TIMEOUT_MS]).
     * Every death that drops a binding also sets it until Shizuku has cleaned the dying stub up
     * ([settleAfterStubCleanupLocked]). A daemon death or transport loss clears it.
     */
    private var stubCleanupFence = false

    /**
     * Set when [UNBIND_SETTLE_TIMEOUT_MS] bound before the unbound process's death arrived. The new binding
     * then shares that process's connection stub, so its late death still reaches [userServiceConnection]
     * (SQ-118). That one disconnect is swallowed while the new binder is alive; a dead or not-yet-connected
     * new binder is handled as a real disconnect. Shizuku's died() then clears and evicts the shared stub and
     * never fires again, so it will not report the new process's death: [spentStubGeneration] marks that.
     */
    private var unboundDeathOwed = false

    /**
     * The [serviceGeneration] whose binding sits on a connection stub that already spent its once-only died()
     * on the previous process (the swallowed owed death above). Only the manager's own death link on the
     * accepted binder, or a call failing on that dead binder, can then end the binding (F-17).
     */
    private var spentStubGeneration = NO_GENERATION
    private var settleTimeout: Job? = null
    private var idleUnbind: Job? = null

    /**
     * Binds and unbinds decided under [bindingLock], in decision order. Each is a two-way binder call into the
     * Shizuku server, so one drainer issues them on [ioDispatcher] with the lock released (R-08): Main
     * ([holdBinding], [refresh], the settle post) never waits for the server, and neither does a thread that only
     * wants the lock. A single drainer keeps the order, so a bind decided after an unbind never reaches the server
     * first. The state is updated when a transaction is decided; the drainer re-checks it before a bind.
     */
    private val pendingTransactions = ArrayDeque<ServiceTransaction>()
    private var draining = false

    /** Bumped by each transport loss: a transaction decided before one cannot report that loss again. */
    private var transportEpoch = 0

    /**
     * [refresh] runs on IO while Shizuku's listeners refresh on Main. Each read takes a ticket, and only a newer
     * ticket than the last applied one (or a transport loss) may publish its state, so a slower, older read can
     * never overwrite a newer one.
     */
    private val refreshTickets = AtomicLong()
    private var appliedRefreshTicket = 0L

    /** This app's own package cannot change while the process lives; a failed read is retried next time. */
    private val ownApplicationInfo by lazy(LazyThreadSafetyMode.PUBLICATION) { applicationInfoProvider.load() }

    private val userServiceConnection =
        object : ServiceConnection {
            override fun onServiceConnected(
                name: ComponentName,
                service: IBinder,
            ) {
                synchronized(bindingLock) {
                    // A late connect from a binding already unbound must not resurrect a dying service.
                    if (serviceBound && !stubCleanupFence) {
                        mutableTelemetryService.value = ITelemetryUserService.Stub.asInterface(service)
                        linkServiceDeathLocked(service)
                    }
                }
            }

            override fun onServiceDisconnected(name: ComponentName) {
                synchronized(bindingLock) {
                    if (stubCleanupFence) {
                        settleAfterStubCleanupLocked()
                        return
                    }
                    if (unboundDeathOwed) {
                        unboundDeathOwed = false
                        if (mutableTelemetryService.value?.asBinder()?.isBinderAlive == true) {
                            spentStubGeneration = serviceGeneration
                            return // the old process's late death, not ours
                        }
                    }
                    // An unexpected death (or an owed one the new binding cannot outlive) leaves the dying stub
                    // cached just like an unbind does, so it is fenced the same way (N-11).
                    serviceBound = false
                    serviceGeneration += 1
                    serviceArgs = null
                    mutableTelemetryService.value = null
                    settleAfterStubCleanupLocked()
                }
            }
        }

    private val binderReceivedListener =
        Shizuku.OnBinderReceivedListener {
            updateState()
        }

    /**
     * The daemon's death is transport loss: it also drops [applicationInfo], so neither a holder nor a later
     * service-death settle binds against the dead daemon before a new binder brings Ready.
     */
    private val binderDeadListener =
        Shizuku.OnBinderDeadListener {
            binderSeen = true // it may die before the received listener ever read it
            synchronized(bindingLock) { transportLostLocked() }
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
                // The call may beat the death notice; a dead binder must not keep failing every later call.
                val binder = service.asBinder()
                if (!binder.isBinderAlive) synchronized(bindingLock) { serviceBinderDiedLocked(binder) }
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
     *
     * The decision is made under [bindingLock], where Ready is published: a state read after a separate failed
     * acquire could see a Ready that arrived in between and give up although the use was available (F-16).
     */
    private suspend fun awaitServiceUse(): Boolean {
        while (true) {
            when (acquireServiceUse()) {
                ServiceUse.ACQUIRED -> return true

                ServiceUse.UNAVAILABLE -> return false

                // StateFlow.first sees the current value, so a Ready published after the decision is not missed.
                ServiceUse.AWAIT_RUNNING -> mutableState.first { it !is ShizukuState.NotRunning }
            }
        }
    }

    /**
     * Shizuku's stub links the same binder, so this link is only a backstop: [serviceBinderDiedLocked] acts
     * only when that stub can no longer report the death.
     */
    private fun linkServiceDeathLocked(service: IBinder) {
        try {
            service.linkToDeath({ synchronized(bindingLock) { serviceBinderDiedLocked(service) } }, 0)
        } catch (_: RemoteException) {
            serviceBinderDiedLocked(service) // already dead
        }
    }

    /**
     * Ends the binding whose accepted [binder] died, when its Shizuku stub is spent (F-17). While the stub is
     * live, its died() posts onServiceDisconnected and evicts the stub only afterwards; dropping the binding
     * here first would let a bind share that dying stub (SQ-114), so the stub stays in charge. A spent stub is
     * evicted by the same died() message that spent it, which may still be running on Main when this binder
     * dies, so binds wait for that message like after any other death (N-11).
     */
    private fun serviceBinderDiedLocked(binder: IBinder) {
        if (mutableTelemetryService.value?.asBinder() !== binder) return // already dropped or replaced
        if (spentStubGeneration != serviceGeneration) return
        spentStubGeneration = NO_GENERATION
        serviceBound = false
        serviceGeneration += 1
        serviceArgs = null
        mutableTelemetryService.value = null
        settleAfterStubCleanupLocked()
    }

    private fun currentServiceAndGeneration(): Pair<ITelemetryUserService, Int>? =
        synchronized(bindingLock) {
            mutableTelemetryService.value?.let { it to serviceGeneration }
        }

    /** Safe on Main: it only decides under [bindingLock]; the bind itself is issued on [ioDispatcher]. */
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

    private fun acquireServiceUse(): ServiceUse =
        synchronized(bindingLock) {
            when {
                mutableState.value is ShizukuState.Ready && applicationInfo != null -> {
                    activeServiceUsers += 1
                    bindIfRequiredLocked()
                    ServiceUse.ACQUIRED
                }

                started && mutableState.value is ShizukuState.NotRunning -> {
                    ServiceUse.AWAIT_RUNNING
                }

                else -> {
                    ServiceUse.UNAVAILABLE
                }
            }
        }

    private enum class ServiceUse { ACQUIRED, AWAIT_RUNNING, UNAVAILABLE }

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

    /**
     * Fences binds until Shizuku has cleaned up the connection stub whose death dropped the binding. Shizuku
     * 13.1.5's died() calls onServiceDisconnected, then clears the stub's connections and evicts it, in one Main
     * message; a bind before that would join the dying stub, and its connect would reach nobody (SQ-114, N-11).
     * Non-immediate Main dispatch is the ordering fence, so [stubCleanupFence] stays set until this post runs.
     * The settle then rebinds if users or holders remain.
     */
    private fun settleAfterStubCleanupLocked() {
        stubCleanupFence = true
        settleTimeout?.cancel()
        settleTimeout =
            scope.launch(Dispatchers.Main) {
                synchronized(bindingLock) {
                    // A cancelled timeout/settle may already be waiting for this lock.
                    if (settleTimeout !== coroutineContext.job) return@launch
                    settleTimeout = null
                    if (stubCleanupFence) finishUnbindSettleLocked()
                }
            }
    }

    private fun finishUnbindSettleLocked(byTimeout: Boolean = false) {
        stubCleanupFence = false
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

    /** Reads Shizuku outside [bindingLock]: these are binder calls too, and only the result is published under it. */
    private fun refreshState() {
        val ticket = refreshTickets.incrementAndGet()
        // The binder decides, not the manager app: Sui (root) hands ShizukuProvider a binder with no manager app
        // installed (N-08). The manager app only tells NotInstalled from NotRunning while this process has never
        // had a binder; after one, its absence is a stopped server.
        if (!facade.pingBinder()) {
            val canReturn = binderSeen || managerApp.isInstalled()
            setUnavailableState(if (canReturn) ShizukuState.NotRunning else ShizukuState.NotInstalled, ticket)
            return
        }
        binderSeen = true
        if (facade.isPreV11()) {
            logUnsupportedVersion("Shizuku versions before v11 are unsupported")
            setUnavailableState(ShizukuState.NotRunning, ticket)
            return
        }
        // Checked before the permission: granting cannot help an old server. -1 means the server neither sent its
        // version on attach nor answers a caller without access; the refresh after the grant judges it then.
        val serverVersion = facade.getVersion()
        if (serverVersion in 0 until MIN_SERVER_VERSION) {
            logUnsupportedVersion("Shizuku server API $serverVersion is unsupported; v$MIN_SERVER_VERSION+ is required")
            setUnavailableState(ShizukuState.UnsupportedServer(serverVersion), ticket)
            return
        }

        when (facade.checkSelfPermission()) {
            PackageManager.PERMISSION_GRANTED -> {
                val info = ownApplicationInfo
                val ready = ShizukuState.Ready(facade.getUid(), serverVersion)
                synchronized(bindingLock) {
                    if (!claimRefreshLocked(ticket)) return
                    applicationInfo = info
                    mutableState.value = ready
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
                setUnavailableState(unavailableState, ticket)
            }
        }
    }

    private fun claimRefreshLocked(ticket: Long): Boolean {
        if (ticket <= appliedRefreshTicket) return false
        appliedRefreshTicket = ticket
        return true
    }

    private fun setUnavailableState(
        newState: ShizukuState,
        ticket: Long,
    ) {
        synchronized(bindingLock) {
            if (!claimRefreshLocked(ticket)) return
            applicationInfo = null
            unbindTelemetryServiceLocked()
            mutableTelemetryService.value = null
            mutableState.value = newState
        }
    }

    /**
     * Owners keep their counts and still release normally after transport loss. A refresh that started before the
     * loss may have read the dead server, so it is not published; the next binder's refresh is.
     */
    private fun transportLostLocked() {
        transportEpoch += 1
        appliedRefreshTicket = maxOf(appliedRefreshTicket, refreshTickets.get())
        serviceBound = false
        serviceArgs = null
        serviceGeneration += 1
        stubCleanupFence = false
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
        if (serviceBound || stubCleanupFence) {
            return
        }
        val args =
            shizukuUserServiceArgs(
                ComponentName(context.packageName, TELEMETRY_USER_SERVICE_CLASS),
                info,
            )
        serviceArgs = args
        serviceBound = true
        enqueueTransactionLocked(ServiceTransaction(bind = true, args, transportEpoch))
    }

    private fun unbindTelemetryServiceLocked() {
        idleUnbind?.cancel()
        idleUnbind = null
        val args = serviceArgs ?: return
        // A bind still queued never reached Shizuku: dropping it leaves no process or connection stub to wait for.
        val bindIssued = !pendingTransactions.removeAll { it.bind && it.args === args }
        if (serviceBound && bindIssued) {
            enqueueTransactionLocked(ServiceTransaction(bind = false, args, transportEpoch))
            stubCleanupFence = true
            // Any owed death now arrives as the awaited one: the shared stub's died() fires once for both.
            unboundDeathOwed = false
            settleTimeout?.cancel()
            settleTimeout =
                scope.launch {
                    delay(UNBIND_SETTLE_TIMEOUT_MS)
                    synchronized(bindingLock) {
                        if (settleTimeout !== coroutineContext.job) return@launch
                        settleTimeout = null
                        if (stubCleanupFence) finishUnbindSettleLocked(byTimeout = true)
                    }
                }
        }
        serviceBound = false
        serviceArgs = null
        serviceGeneration += 1
        mutableTelemetryService.value = null
    }

    private class ServiceTransaction(
        val bind: Boolean,
        val args: Shizuku.UserServiceArgs,
        val transportEpoch: Int,
    )

    private fun enqueueTransactionLocked(transaction: ServiceTransaction) {
        pendingTransactions.addLast(transaction)
        if (draining) return
        draining = true
        scope.launch(ioDispatcher) { drainTransactions() }
    }

    /** Issues [pendingTransactions] one at a time with [bindingLock] released. */
    private fun drainTransactions() {
        try {
            while (true) {
                val transaction = synchronized(bindingLock) { nextTransactionLocked() } ?: return
                try {
                    if (transaction.bind) {
                        facade.bindUserService(transaction.args, userServiceConnection)
                    } else {
                        facade.unbindUserService(transaction.args, userServiceConnection, true)
                    }
                } catch (_: RuntimeException) {
                    synchronized(bindingLock) {
                        // A loss handled since (perhaps with a new server up by now) must not be reported again.
                        if (transaction.transportEpoch == transportEpoch) transportLostLocked()
                    }
                }
            }
        } catch (t: Throwable) {
            synchronized(bindingLock) { draining = false } // the next decision starts a new drainer
            throw t
        }
    }

    /**
     * Ends the drain (clearing [draining] under the same lock as the empty check, so no decision is stranded) or
     * returns the next transaction. A bind whose binding has ended since (unbound, disconnected or lost) would bind
     * for nobody and is dropped. An unbind is always issued: the order guarantees no later bind precedes it, and
     * removing a service the server no longer knows is harmless.
     */
    private fun nextTransactionLocked(): ServiceTransaction? {
        while (true) {
            val next = pendingTransactions.removeFirstOrNull()
            if (next == null) {
                draining = false
                return null
            }
            if (!next.bind || next.args === serviceArgs) return next
        }
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
        const val NO_GENERATION = -1
        const val TAG = "ShizukuManager"

        /** Older servers build TelemetryUserService without a Context, and it then rejects every caller. */
        const val MIN_SERVER_VERSION = 13

        /** Idle linger before unbind(remove=true); short on purpose (H13). */
        const val IDLE_UNBIND_DELAY_MS = 3_000L

        /**
         * Fallback when an unbound service's death never arrives (e.g. it never started). It stays below the
         * service's bounded shutdown on purpose: a longer wait would eat most of the callers' 5 s bind window
         * whenever no death comes, and a slower exit is handled by the spent-stub recovery (F-17).
         */
        const val UNBIND_SETTLE_TIMEOUT_MS = 2_000L
        const val TELEMETRY_USER_SERVICE_CLASS =
            "com.akane.inspiration.ipc.service.TelemetryUserService"
    }
}

private fun androidApplicationInfoProvider(context: Context): ShizukuApplicationInfoProvider =
    ShizukuApplicationInfoProvider {
        val packageInfo =
            try {
                context.packageManager.getPackageInfo(context.packageName, 0)
            } catch (e: PackageManager.NameNotFoundException) {
                // Not expected for the running app; refreshState's caller reports a failed read as NotRunning.
                throw IllegalStateException("own package ${context.packageName} is not readable", e)
            }
        ShizukuApplicationInfo(
            versionCode = packageInfo.longVersionCode,
            debuggable = (packageInfo.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_DEBUGGABLE != 0,
        )
    }

private fun androidManagerAppProbe(context: Context): ShizukuManagerAppProbe =
    ShizukuManagerAppProbe {
        try {
            // Visible through <queries> in the app manifest.
            context.packageManager.getPackageInfo(SHIZUKU_MANAGER_PACKAGE, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

private const val SHIZUKU_MANAGER_PACKAGE = "moe.shizuku.privileged.api"
