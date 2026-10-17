package com.akane.inspiration.telemetry.toolkit

import com.akane.inspiration.ipc.model.BgDexoptReason
import com.akane.inspiration.ipc.model.BgDexoptState
import com.akane.inspiration.ipc.model.BgDexoptStatus as ServiceBgDexoptStatus
import com.akane.inspiration.ipc.model.ToolkitCode
import com.akane.inspiration.ipc.model.ToolkitResult
import com.akane.inspiration.ipc.model.ToolkitStatus
import com.akane.inspiration.ipc.toolkit.AppOp
import com.akane.inspiration.ipc.toolkit.AppOpMode
import com.akane.inspiration.ipc.toolkit.DozeProfile
import com.akane.inspiration.ipc.toolkit.DozeRaw
import com.akane.inspiration.ipc.toolkit.PackageNames
import com.akane.inspiration.ipc.toolkit.StandbyBucket
import com.akane.inspiration.telemetry.store.SnapshotStore
import com.akane.inspiration.telemetry.store.ToolkitActionRecord
import com.akane.inspiration.telemetry.store.ToolkitActionStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Client-side result codes stored in `toolkit_action.code`; they sit above the service's [ToolkitCode] range. */
object ToolkitClientCode {
    const val NOT_CONFIRMED = 100
    const val NO_SERVICE = 101
    const val STALE_SERVICE = 102

    /** A PENDING row whose effect cannot be read back (force-stop), so it is never reported as done. */
    const val UNVERIFIED = 103

    /**
     * A DONE write whose service reply carried no "before" value (appop, bucket). The change happened and is
     * recorded as DONE, but it cannot be restored, so it is flagged rather than reported as a failure.
     */
    const val BEFORE_MISSING = 104

    /** The privileged call threw after the PENDING row existed; the row is finalized FAILED and the error rethrown. */
    const val WRITE_ERROR = 105
}

/**
 * The privileged calls [ToolkitActions] needs. [GatewayBackend] adapts the raw [ToolkitGateway]. Internal:
 * a feature that could build one could write without confirmation or an audit row.
 */
internal interface ToolkitBackend {
    suspend fun setAppOp(pkg: String, op: AppOp, mode: AppOpMode): GatewayReply<ToolkitResult>

    suspend fun getAppOps(pkg: String): GatewayReply<ToolkitResult>

    suspend fun setStandbyBucket(pkg: String, bucket: StandbyBucket): GatewayReply<ToolkitResult>

    suspend fun getStandbyBucket(pkg: String): GatewayReply<ToolkitResult>

    suspend fun applyDozeProfile(profile: DozeProfile, expectedBeforeRaw: String?): GatewayReply<ToolkitResult>

    suspend fun getDozeState(): GatewayReply<ToolkitResult>

    suspend fun restoreDozeRaw(rawOrNull: String?, expectedCurrentRaw: String?): GatewayReply<ToolkitResult>

    suspend fun forceStop(pkg: String): GatewayReply<ToolkitResult>

    suspend fun startBgDexopt(): GatewayReply<ToolkitResult>

    suspend fun bgDexoptStatus(): GatewayReply<ServiceBgDexoptStatus>

    suspend fun cancelBgDexopt(): GatewayReply<ToolkitResult>
}

internal class GatewayBackend(private val gateway: ToolkitGateway) : ToolkitBackend {
    override suspend fun setAppOp(pkg: String, op: AppOp, mode: AppOpMode) = gateway.setAppOp(pkg, op, mode)

    override suspend fun getAppOps(pkg: String) = gateway.getAppOps(pkg)

    override suspend fun setStandbyBucket(pkg: String, bucket: StandbyBucket) = gateway.setStandbyBucket(pkg, bucket)

    override suspend fun getStandbyBucket(pkg: String) = gateway.getStandbyBucket(pkg)

    override suspend fun applyDozeProfile(profile: DozeProfile, expectedBeforeRaw: String?) =
        gateway.applyDozeProfile(profile, expectedBeforeRaw)

    override suspend fun getDozeState() = gateway.getDozeState()

    override suspend fun restoreDozeRaw(rawOrNull: String?, expectedCurrentRaw: String?) =
        gateway.restoreDozeRaw(rawOrNull, expectedCurrentRaw)

    override suspend fun forceStop(pkg: String) = gateway.forceStop(pkg)

    override suspend fun startBgDexopt() = gateway.startBgDexopt()

    override suspend fun bgDexoptStatus() = gateway.bgDexoptStatus()

    override suspend fun cancelBgDexopt() = gateway.cancelBgDexopt()
}

/** The slice of [SnapshotStore] the audit log uses. */
internal interface ToolkitAuditStore {
    suspend fun insert(record: ToolkitActionRecord): Long

    suspend fun update(record: ToolkitActionRecord)

    /** Newest first. */
    suspend fun recent(limit: Int): List<ToolkitActionRecord>

    /** Oldest first. */
    suspend fun pending(): List<ToolkitActionRecord>
}

internal class SnapshotStoreAudit(private val store: SnapshotStore) : ToolkitAuditStore {
    override suspend fun insert(record: ToolkitActionRecord) = store.insertToolkitAction(record)

    override suspend fun update(record: ToolkitActionRecord) = store.updateToolkitAction(record)

    override suspend fun recent(limit: Int) = store.toolkitActions(limit)

    override suspend fun pending() = store.pendingToolkitActions()
}

/**
 * The only toolkit API features see (the raw gateway stays inside the container). Every write:
 * re-validates, asks for confirmation when risky, inserts a PENDING audit row *before* the privileged
 * call, then finalizes that row with the service's own before/after. REFUSED attempts are recorded too.
 * All inputs are typed; nothing here reads an Intent or a URI.
 *
 * Reads (plan, appState, overrides) go to the service every time: the audit log is history, not state.
 *
 * Nothing polls on its own. The flows re-read when this object changes something, and the dexopt
 * status flow re-reads on a timer only while a job is RUNNING and the flow is collected. The one other
 * timer is the hold supervisor: while the dexopt binding hold is taken (job RUNNING, user-initiated) it
 * re-checks the status once a minute so the hold ends with the job even when nothing is collecting.
 *
 * Once a PENDING row exists, the privileged write, its settlement and the RESTORED update of a restored
 * original run to completion even if the calling coroutine is cancelled.
 *
 * The primary constructor and the backend/audit seams are internal, so a feature cannot build an instance
 * that skips confirmation or the audit log; the container uses the gateway constructor.
 */
class ToolkitActions internal constructor(
    private val backend: ToolkitBackend,
    private val audit: ToolkitAuditStore,
    private val holdBinding: () -> AutoCloseable,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val dexoptPollMs: Long = DEFAULT_DEXOPT_POLL_MS,
    private val supervisorScope: CoroutineScope = defaultSupervisorScope(),
    private val holdCheckMs: Long = DEFAULT_HOLD_CHECK_MS,
) {
    /**
     * The production constructor. [supervisorScope] outlives any screen: it runs the bounded supervisor that
     * releases the dexopt binding hold when the job ends, whether or not anything is collecting.
     */
    constructor(
        gateway: ToolkitGateway,
        store: SnapshotStore,
        holdBinding: () -> AutoCloseable,
        nowMs: () -> Long = System::currentTimeMillis,
        supervisorScope: CoroutineScope = defaultSupervisorScope(),
    ) : this(GatewayBackend(gateway), SnapshotStoreAudit(store), holdBinding, nowMs, supervisorScope = supervisorScope)

    /** Serializes execute, restore, reconcile and status handling so a PENDING row is never seen half-written. */
    private val mutex = Mutex()

    /** Bumped when the log or the real state may have changed; drives [history] and [activeOverrides]. */
    private val changes = MutableStateFlow(0L)

    /** Bumped when a dexopt start or cancel happened; wakes [bgDexoptStatus]. */
    private val dexoptEvents = MutableStateFlow(0L)

    private val holdLock = Any()
    private var dexoptHold: AutoCloseable? = null
    private var holdSupervisor: Job? = null

    // ---- reads -----------------------------------------------------------------------------

    suspend fun appState(pkg: String): ToolkitOutcome<AppToolkitState> {
        if (!PackageNames.isValid(pkg)) return ToolkitOutcome.Refused(ToolkitRefusal.INVALID_ARGUMENT, ToolkitCode.ARG_PACKAGE)
        val ops = readAppOps(pkg)
        if (ops !is ToolkitOutcome.Done) return failure(ops)
        val bucket = readBucket(pkg)
        if (bucket !is ToolkitOutcome.Done) return failure(bucket)
        val bucketRaw = bucket.value.raw
        return ToolkitOutcome.Done(
            AppToolkitState(
                pkg = pkg,
                appOps = ops.value.modes.mapValues { (_, raw) -> modeOf(raw) },
                bucket = bucketRaw?.let { bucketOf(it) },
                bucketRaw = bucketRaw,
                updatedSystemApp = ops.value.updatedSystem || bucket.value.updatedSystem,
            ),
        )
    }

    suspend fun dozeState(): ToolkitOutcome<DozeState> = readDoze()

    fun history(limit: Int): Flow<List<ToolkitActionRecord>> = changes.map { audit.recent(limit) }

    /**
     * Changes of ours that read-back shows are still in effect. Emits on collect and again whenever this
     * object changes something. When the service cannot be reached it emits an empty list (the UI shows
     * its blocked state for that).
     */
    fun activeOverrides(): Flow<List<Override>> = changes.map { mutex.withLock { computeOverrides() } }

    // ---- plan / execute --------------------------------------------------------------------

    suspend fun plan(action: ToolkitAction): ToolkitPlan = mutex.withLock { planLocked(action, null) }

    suspend fun planRestore(actionId: Long): ToolkitPlan = mutex.withLock {
        when (val decoded = decodeRestore(actionId)) {
            is Decoded.No -> ToolkitPlan.Refused(decoded.reason)
            is Decoded.Ok -> planLocked(decoded.action, decoded.record)
        }
    }

    /**
     * Runs [action]. When it needs confirmation and [confirmed] is false it returns Refused(NOT_CONFIRMED)
     * and no privileged write is issued (the attempt is still recorded as REFUSED).
     */
    suspend fun execute(action: ToolkitAction, confirmed: Boolean = false): ToolkitOutcome<ToolkitActionRecord> =
        mutex.withLock { run(action, confirmed, null) }

    /**
     * Undoes audit row [actionId]: the stored values are decoded through the same enums and validation
     * as any action. An override row goes back to the original of its chain (as [resetAll] does), and is
     * refused with ALREADY_RESTORED when its own `after`, or the value read back now, already is that
     * original (nothing is written then). If the current value is no longer the recorded `after`, confirmation is needed
     * (see [planRestore]). The new row carries `restoresId`; the original becomes RESTORED.
     */
    suspend fun restore(actionId: Long, confirmed: Boolean = false): ToolkitOutcome<ToolkitActionRecord> =
        mutex.withLock {
            when (val decoded = decodeRestore(actionId)) {
                is Decoded.No -> ToolkitOutcome.Refused(decoded.reason)
                is Decoded.Ok -> run(decoded.action, confirmed, decoded.record)
            }
        }

    /**
     * Restores every active override (exactly what [activeOverrides] shows) and reports each item. Each one
     * goes back to the value before the FIRST change of its chain (the user's original state), not to the
     * intermediate value the newest row recorded: a target switched on and then back by hand must not end
     * up restricted again. A target already at its original value is not an active override, so it is not
     * listed and nothing is written for it. The user asked for this explicitly, so the per-item restore is
     * confirmed; a failure does not stop the remaining items. One lock covers the whole reset, so the
     * chains read here cannot change under it.
     */
    suspend fun resetAll(): List<ResetItem> = mutex.withLock {
        val rows = overrideRows()
        computeOverrides(rows).map { override -> ResetItem(override, resetOne(override, rows)) }
    }

    private suspend fun resetOne(override: Override, rows: List<ToolkitActionRecord>): ToolkitOutcome<ToolkitActionRecord> {
        val row = rows.first { it.id == override.actionId }
        val action = restoreAction(row, chainOriginal(row, rows)) ?: return ToolkitOutcome.Refused(ToolkitRefusal.NOT_RESTORABLE)
        return run(action, confirmed = true, restoring = row)
    }

    // ---- reconcile -------------------------------------------------------------------------

    /**
     * Finalizes rows left PENDING by a crash or kill. The state is read back: DONE when it matches the
     * intended `after`, otherwise FAILED (a job whose status is gone is CANCELLED instead). A row whose
     * state cannot be read (service unreachable, or a dexopt status that is UNKNOWN or still RUNNING)
     * stays PENDING and is retried on the next call.
     */
    suspend fun reconcile() {
        mutex.withLock {
            val pending = audit.pending()
            var dexoptDone = false
            for (row in pending) {
                if (row.kind == KIND_DEXOPT) {
                    if (!dexoptDone) refreshDexoptLocked()
                    dexoptDone = true
                } else {
                    reconcileRow(row)
                }
            }
            if (pending.isNotEmpty()) changes.value += 1
        }
    }

    // ---- bg-dexopt -------------------------------------------------------------------------

    /**
     * The PENDING row is inserted under the client lock, the service call (which includes the plugged
     * probes and can take seconds) runs outside it so cancel, status and reads are not blocked, and the
     * result is settled under the lock again. Once the row exists the rest runs to completion even if
     * the caller is cancelled, so the hold is taken and the row never stays PENDING without an owner.
     */
    suspend fun startBgDexopt(): ToolkitOutcome<ToolkitActionRecord> = withContext(NonCancellable) {
        val pending = mutex.withLock {
            insertPending(Spec(KIND_DEXOPT, null, OP_BG_DEXOPT, null), restoresId = null, beforeRaw = null)
        }
        val reply = try {
            backend.startBgDexopt()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            mutex.withLock { failPending(pending) }
            changes.value += 1
            throw e
        }
        mutex.withLock {
            // The "before" value is not used for anything on a dexopt row, so its absence is not an error.
            val outcome = settle(pending, reply, requireBefore = false, keepPendingOnDone = true)
            if (outcome is ToolkitOutcome.Done) {
                syncHold(running = true)
                supersedeOtherDexoptRows(outcome.value.id)
                dexoptEvents.value += 1
            }
            changes.value += 1
            outcome
        }
    }

    /** Cancel is system-side and idempotent, so it is valid whether or not this process started the job. */
    suspend fun cancelBgDexopt(): ToolkitOutcome<Unit> = mutex.withLock {
        val read = readOutcome(backend.cancelBgDexopt())
        val outcome: ToolkitOutcome<Unit> = if (read is ToolkitOutcome.Done) ToolkitOutcome.Done(Unit) else failure(read)
        if (outcome is ToolkitOutcome.Done) {
            // The job is being cancelled; nothing needs the service kept alive any more.
            syncHold(running = false)
            finalizeDexoptRows(ToolkitActionStatus.CANCELLED, BgDexoptReason.USER, afterRaw = "cancelled")
            dexoptEvents.value += 1
            changes.value += 1
        }
        outcome
    }

    /**
     * Emits the job status now, then again on start/cancel, and every [dexoptPollMs] while RUNNING (a
     * bounded, user-initiated wait that stops when the collector does). The binding is held while the
     * status is RUNNING and released on any other status; UNKNOWN never takes the hold.
     */
    fun bgDexoptStatus(): Flow<BgDexoptStatus> = flow {
        while (true) {
            val seen = dexoptEvents.value
            val status = mutex.withLock { refreshDexoptLocked() }
            emit(status)
            if (status is BgDexoptStatus.Running) delay(dexoptPollMs) else dexoptEvents.first { it != seen }
        }
    }.distinctUntilChanged()

    /** For container teardown; normally the hold ends via [bgDexoptStatus]. */
    fun release() = syncHold(running = false)

    // ---- planning --------------------------------------------------------------------------

    private suspend fun planLocked(action: ToolkitAction, restoring: ToolkitActionRecord?): ToolkitPlan {
        if (isInvalid(action)) return ToolkitPlan.Refused(ToolkitRefusal.INVALID_ARGUMENT)
        return when (val prep = prepare(action, restoring)) {
            is Prep.Stop -> ToolkitPlan.Refused(planRefusal(prep.outcome))
            is Prep.Ok ->
                if (alreadyRestored(action, prep, restoring)) {
                    ToolkitPlan.Refused(ToolkitRefusal.ALREADY_RESTORED)
                } else {
                    confirmationPlan(prep.warnings())
                }
        }
    }

    /**
     * Whether restoring [restoring] would write what the target already reads back (same state, "allow" being
     * the default mode): its chain is already at the original, whoever got it there, so nothing is written.
     */
    private fun alreadyRestored(action: ToolkitAction, prep: Prep.Ok, restoring: ToolkitActionRecord?): Boolean {
        if (restoring == null || restoring.kind !in OVERRIDE_KINDS) return false
        val target = when (action) {
            is ToolkitAction.SetAppOp -> action.mode.argv
            is ToolkitAction.SetBucket -> action.bucket.rawValue.toString()
            is ToolkitAction.RestoreDoze -> action.rawOrNull
            else -> return false
        }
        return sameState(restoring.kind, prep.current, target)
    }

    private fun confirmationPlan(warnings: List<ToolkitWarning>): ToolkitPlan =
        if (warnings.isEmpty()) ToolkitPlan.Ready else ToolkitPlan.NeedsConfirmation(warnings.first(), warnings.drop(1))

    private fun planRefusal(outcome: ToolkitOutcome<Nothing>): ToolkitRefusal = when (outcome) {
        is ToolkitOutcome.Refused -> outcome.reason
        is ToolkitOutcome.Unavailable -> ToolkitRefusal.SERVICE_UNAVAILABLE
        is ToolkitOutcome.Failed -> ToolkitRefusal.READ_FAILED
        is ToolkitOutcome.Done -> ToolkitRefusal.OTHER
    }

    private sealed interface Prep {
        /** [current] is the value read back right now; [expectedCurrent] is set for restores. */
        class Ok(
            val static: List<ToolkitWarning>,
            val dynamic: List<ToolkitWarning>,
            val current: String?,
            val checkExpected: Boolean,
            val expectedCurrent: String?,
        ) : Prep {
            fun warnings(): List<ToolkitWarning> {
                val changed = checkExpected && norm(current) != norm(expectedCurrent)
                return static + dynamic + if (changed) listOf(ToolkitWarning.RESTORE_STATE_CHANGED) else emptyList()
            }
        }

        class Stop(val outcome: ToolkitOutcome<Nothing>) : Prep
    }

    /** Re-validates [action] against the live state: reads back, and collects every confirmation reason. */
    private suspend fun prepare(action: ToolkitAction, restoring: ToolkitActionRecord? = null): Prep {
        val static = staticWarnings(action)
        return when (action) {
            is ToolkitAction.SetAppOp -> {
                val read = readAppOps(action.pkg)
                if (read !is ToolkitOutcome.Done) return stop(read)
                val current = read.value.modes[action.op]
                Prep.Ok(static, systemWarning(read.value.updatedSystem), current, restoring != null, restoring?.afterRaw)
            }
            is ToolkitAction.SetBucket -> {
                val read = readBucket(action.pkg)
                if (read !is ToolkitOutcome.Done) return stop(read)
                val current = read.value.raw?.toString()
                Prep.Ok(static, systemWarning(read.value.updatedSystem), current, restoring != null, restoring?.afterRaw)
            }
            is ToolkitAction.ForceStop -> {
                // Validation, hard-deny and the updated-system flag come from a read of the same package.
                val read = readAppOps(action.pkg)
                if (read !is ToolkitOutcome.Done) return stop(read)
                Prep.Ok(static, systemWarning(read.value.updatedSystem), null, false, null)
            }
            is ToolkitAction.ApplyDoze -> {
                val read = readDoze()
                if (read !is ToolkitOutcome.Done) return stop(read)
                val overCustom = action.profile == DozeProfile.STANDARD && read.value.isCustom
                Prep.Ok(static, if (overCustom) listOf(ToolkitWarning.DOZE_OVER_CUSTOM) else emptyList(), read.value.raw, false, null)
            }
            is ToolkitAction.RestoreDoze -> {
                val read = readDoze()
                if (read !is ToolkitOutcome.Done) return stop(read)
                Prep.Ok(static, emptyList(), read.value.raw, true, action.expectedCurrentRaw)
            }
        }
    }

    private fun stop(outcome: ToolkitOutcome<*>): Prep.Stop = Prep.Stop(failure(outcome))

    private fun systemWarning(updatedSystem: Boolean): List<ToolkitWarning> =
        if (updatedSystem) listOf(ToolkitWarning.UPDATED_SYSTEM_APP) else emptyList()

    /** Reasons that need no read to know about. */
    private fun staticWarnings(action: ToolkitAction): List<ToolkitWarning> = when (action) {
        is ToolkitAction.ForceStop -> listOf(ToolkitWarning.FORCE_STOP)
        is ToolkitAction.SetBucket ->
            if (action.bucket == StandbyBucket.RESTRICTED) listOf(ToolkitWarning.RESTRICTED_BUCKET) else emptyList()
        is ToolkitAction.SetAppOp -> when {
            action.mode != AppOpMode.IGNORE -> emptyList()
            action.op == AppOp.WAKE_LOCK -> listOf(ToolkitWarning.WAKE_LOCK_IGNORE)
            action.op == AppOp.RUN_ANY_IN_BACKGROUND -> listOf(ToolkitWarning.RUN_ANY_IN_BACKGROUND_IGNORE)
            else -> emptyList()
        }
        is ToolkitAction.ApplyDoze, is ToolkitAction.RestoreDoze -> emptyList()
    }

    private fun isInvalid(action: ToolkitAction): Boolean = when (action) {
        is ToolkitAction.SetAppOp -> !PackageNames.isValid(action.pkg)
        is ToolkitAction.SetBucket -> !PackageNames.isValid(action.pkg)
        is ToolkitAction.ForceStop -> !PackageNames.isValid(action.pkg)
        is ToolkitAction.ApplyDoze -> false
        is ToolkitAction.RestoreDoze -> action.rawOrNull?.let { it.isNotEmpty() && !DozeRaw.isSafe(it) } ?: false
    }

    // ---- execution -------------------------------------------------------------------------

    private suspend fun run(
        action: ToolkitAction,
        confirmed: Boolean,
        restoring: ToolkitActionRecord?,
    ): ToolkitOutcome<ToolkitActionRecord> {
        val spec = specOf(action)
        val restoresId = restoring?.id
        if (isInvalid(action)) {
            return refuse(spec, restoresId, ToolkitRefusal.INVALID_ARGUMENT, ToolkitCode.ARG_VALUE)
        }
        // Reasons known without a read are checked first so an unconfirmed risky action touches nothing.
        if (!confirmed && staticWarnings(action).isNotEmpty()) {
            return refuse(spec, restoresId, ToolkitRefusal.NOT_CONFIRMED, ToolkitClientCode.NOT_CONFIRMED)
        }
        val prep = when (val prepared = prepare(action, restoring)) {
            is Prep.Stop -> return stopOutcome(spec, restoresId, prepared.outcome)
            is Prep.Ok -> prepared
        }
        if (alreadyRestored(action, prep, restoring)) return ToolkitOutcome.Refused(ToolkitRefusal.ALREADY_RESTORED)
        if (!confirmed && prep.warnings().isNotEmpty()) {
            return refuse(spec, restoresId, ToolkitRefusal.NOT_CONFIRMED, ToolkitClientCode.NOT_CONFIRMED)
        }
        // Write-ahead: the PENDING row exists before the privileged call is made. From the insert on, the
        // whole sequence (write, settle, RESTORED update) must finish even if the caller is cancelled (a
        // screen left mid-write): a cancelled caller must not leave a changed system with a PENDING row that
        // `activeOverrides` cannot see, or an original that can be restored twice.
        return withContext(NonCancellable) {
            val before = if (action is ToolkitAction.ForceStop) null else prep.current
            val pending = insertPending(spec, restoresId, beforeRaw = before)
            val reply = try {
                write(action, prep)
            } catch (e: CancellationException) {
                // Not ours to settle: whether the write happened is unknown, so reconcile() reads it back.
                throw e
            } catch (e: Throwable) {
                failPending(pending)
                changes.value += 1
                throw e
            }
            val outcome = settle(pending, reply, requireBefore = spec.kind != KIND_DOZE, keepPendingOnDone = false)
            if (outcome is ToolkitOutcome.Done && restoring != null) {
                audit.update(restoring.copy(status = ToolkitActionStatus.RESTORED))
            }
            changes.value += 1
            outcome
        }
    }

    private suspend fun failPending(pending: ToolkitActionRecord) {
        audit.update(pending.copy(status = ToolkitActionStatus.FAILED, endedAt = nowMs(), code = ToolkitClientCode.WRITE_ERROR))
    }

    private suspend fun write(action: ToolkitAction, prep: Prep.Ok): GatewayReply<ToolkitResult> = when (action) {
        is ToolkitAction.SetAppOp -> backend.setAppOp(action.pkg, action.op, action.mode)
        is ToolkitAction.SetBucket -> backend.setStandbyBucket(action.pkg, action.bucket)
        is ToolkitAction.ForceStop -> backend.forceStop(action.pkg)
        is ToolkitAction.ApplyDoze -> backend.applyDozeProfile(action.profile, prep.current)
        is ToolkitAction.RestoreDoze -> backend.restoreDozeRaw(norm(action.rawOrNull), prep.current)
    }

    /** Refusals from validation are recorded; a read that could not run (nothing attempted) is not. */
    private suspend fun stopOutcome(
        spec: Spec,
        restoresId: Long?,
        outcome: ToolkitOutcome<Nothing>,
    ): ToolkitOutcome<ToolkitActionRecord> {
        if (outcome is ToolkitOutcome.Refused) {
            return refuse(spec, restoresId, outcome.reason, outcome.code)
        }
        return outcome
    }

    private suspend fun refuse(
        spec: Spec,
        restoresId: Long?,
        reason: ToolkitRefusal,
        code: Int?,
    ): ToolkitOutcome<ToolkitActionRecord> {
        val now = nowMs()
        val record = spec.record(ToolkitActionStatus.REFUSED, now, restoresId, null).copy(endedAt = now, code = code)
        audit.insert(record)
        changes.value += 1
        return ToolkitOutcome.Refused(reason, code)
    }

    private suspend fun insertPending(spec: Spec, restoresId: Long?, beforeRaw: String?): ToolkitActionRecord {
        val record = spec.record(ToolkitActionStatus.PENDING, nowMs(), restoresId, beforeRaw)
        return record.copy(id = audit.insert(record))
    }

    /** Finalizes [pending] from the service's [reply] and maps it to the outcome. */
    private suspend fun settle(
        pending: ToolkitActionRecord,
        reply: GatewayReply<ToolkitResult>,
        requireBefore: Boolean,
        keepPendingOnDone: Boolean,
    ): ToolkitOutcome<ToolkitActionRecord> {
        val now = nowMs()
        if (reply is GatewayReply.Unavailable) {
            val code = if (reply.reason == GatewayUnavailable.STALE_SERVICE) {
                ToolkitClientCode.STALE_SERVICE
            } else {
                ToolkitClientCode.NO_SERVICE
            }
            audit.update(pending.copy(status = ToolkitActionStatus.FAILED, endedAt = now, code = code))
            return ToolkitOutcome.Unavailable(unavailableFor(reply.reason))
        }
        val result = (reply as GatewayReply.Reply).value
        return when (result.status) {
            ToolkitStatus.DONE -> settleDone(pending, result, now, requireBefore, keepPendingOnDone)
            ToolkitStatus.REFUSED -> {
                audit.update(finished(pending, ToolkitActionStatus.REFUSED, result, now))
                ToolkitOutcome.Refused(refusalFor(result.code), result.code)
            }
            ToolkitStatus.UNAVAILABLE -> {
                audit.update(finished(pending, ToolkitActionStatus.FAILED, result, now))
                ToolkitOutcome.Unavailable(ToolkitUnavailable.NOT_EFFECTIVE, result.code)
            }
            else -> {
                audit.update(finished(pending, ToolkitActionStatus.FAILED, result, now))
                ToolkitOutcome.Failed(result.code)
            }
        }
    }

    private suspend fun settleDone(
        pending: ToolkitActionRecord,
        result: ToolkitResult,
        now: Long,
        requireBefore: Boolean,
        keepPendingOnDone: Boolean,
    ): ToolkitOutcome<ToolkitActionRecord> {
        val status = if (keepPendingOnDone) ToolkitActionStatus.PENDING else ToolkitActionStatus.DONE
        // A success without a recorded "before" happened but cannot be reversed. It is still DONE (the log must
        // not say a change that took effect failed); the distinct code marks it unrestorable.
        val beforeMissing = requireBefore && result.beforeRaw == null
        val code = if (beforeMissing) ToolkitClientCode.BEFORE_MISSING else result.code
        val done = finished(pending, status, result, now).copy(endedAt = if (keepPendingOnDone) null else now, code = code)
        audit.update(done)
        return ToolkitOutcome.Done(done)
    }

    private fun finished(
        pending: ToolkitActionRecord,
        status: ToolkitActionStatus,
        result: ToolkitResult,
        now: Long,
    ): ToolkitActionRecord = pending.copy(
        status = status,
        endedAt = now,
        code = result.code,
        beforeRaw = result.beforeRaw ?: pending.beforeRaw.takeIf { status != ToolkitActionStatus.DONE },
        afterRaw = result.afterRaw,
    )

    // ---- audit row shape -------------------------------------------------------------------

    /** How an action is stored in a `toolkit_action` row. Explicit strings, never enum names. */
    private class Spec(val kind: String, val pkg: String?, val op: String, val param: String?) {
        fun record(
            status: ToolkitActionStatus,
            startedAt: Long,
            restoresId: Long?,
            beforeRaw: String?,
        ) = ToolkitActionRecord(
            startedAt = startedAt,
            kind = kind,
            pkg = pkg,
            op = op,
            param = param,
            status = status,
            beforeRaw = beforeRaw,
            restoresId = restoresId,
        )
    }

    private fun specOf(action: ToolkitAction): Spec = when (action) {
        is ToolkitAction.SetAppOp -> Spec(KIND_APPOP, validPkg(action.pkg), action.op.argv, action.mode.argv)
        is ToolkitAction.SetBucket -> Spec(KIND_BUCKET, validPkg(action.pkg), OP_BUCKET, action.bucket.argv)
        is ToolkitAction.ForceStop -> Spec(KIND_FORCE_STOP, validPkg(action.pkg), OP_FORCE_STOP, null)
        is ToolkitAction.ApplyDoze -> Spec(KIND_DOZE, null, OP_APPLY_PROFILE, profileToken(action.profile))
        is ToolkitAction.RestoreDoze -> Spec(KIND_DOZE, null, OP_RESTORE_RAW, norm(action.rawOrNull))
    }

    /** Only well-formed package names are persisted, so the log never stores arbitrary caller text. */
    private fun validPkg(pkg: String): String? = pkg.takeIf { PackageNames.isValid(it) }

    private fun profileToken(profile: DozeProfile): String = when (profile) {
        DozeProfile.STANDARD -> "standard"
        DozeProfile.BALANCED -> "balanced"
        DozeProfile.AGGRESSIVE_NIGHT -> "aggressive_night"
    }

    /** The action a stored row intended to perform, or null when the row is not understood. */
    private fun intendedAction(row: ToolkitActionRecord): ToolkitAction? {
        val pkg = row.pkg?.takeIf { PackageNames.isValid(it) }
        return when (row.kind) {
            KIND_APPOP -> {
                val op = AppOp.entries.firstOrNull { it.argv == row.op }
                val mode = AppOpMode.entries.firstOrNull { it.argv == row.param }
                if (pkg != null && op != null && mode != null) ToolkitAction.SetAppOp(pkg, op, mode) else null
            }
            KIND_BUCKET -> {
                val bucket = StandbyBucket.entries.firstOrNull { it.argv == row.param }
                if (pkg != null && bucket != null) ToolkitAction.SetBucket(pkg, bucket) else null
            }
            KIND_FORCE_STOP -> pkg?.let { ToolkitAction.ForceStop(it) }
            KIND_DOZE -> when (row.op) {
                OP_APPLY_PROFILE -> DozeProfile.entries.firstOrNull { profileToken(it) == row.param }
                    ?.let { ToolkitAction.ApplyDoze(it) }
                OP_RESTORE_RAW -> ToolkitAction.RestoreDoze(row.param, null)
                else -> null
            }
            else -> null
        }
    }

    // ---- restore ---------------------------------------------------------------------------

    private sealed interface Decoded {
        class Ok(val action: ToolkitAction, val record: ToolkitActionRecord) : Decoded

        class No(val reason: ToolkitRefusal) : Decoded
    }

    private suspend fun decodeRestore(actionId: Long): Decoded {
        val all = audit.recent(MAX_ROWS)
        val record = all.firstOrNull { it.id == actionId } ?: return Decoded.No(ToolkitRefusal.UNKNOWN_ACTION)
        if (record.status == ToolkitActionStatus.RESTORED) return Decoded.No(ToolkitRefusal.ALREADY_RESTORED)
        if (record.status != ToolkitActionStatus.DONE || record.restoresId != null) {
            return Decoded.No(ToolkitRefusal.NOT_RESTORABLE)
        }
        // An override row goes back to its chain's original, like resetAll; a chain whose own `after` already
        // is that original has nothing left to undo (writing the intermediate `before` would re-restrict).
        val original = if (record.kind in OVERRIDE_KINDS) chainOriginal(record, overrideRows(all)) else record.beforeRaw
        if (record.kind in OVERRIDE_KINDS && sameState(record.kind, record.afterRaw, original)) {
            return Decoded.No(ToolkitRefusal.ALREADY_RESTORED)
        }
        val action = restoreAction(record, original)
        return if (action == null) Decoded.No(ToolkitRefusal.NOT_RESTORABLE) else Decoded.Ok(action, record)
    }

    /** The write that puts [record]'s target back to [beforeRaw], or null when that value cannot be replayed. */
    private fun restoreAction(record: ToolkitActionRecord, beforeRaw: String?): ToolkitAction? {
        val pkg = record.pkg
        return when (record.kind) {
            KIND_APPOP -> {
                val op = AppOp.entries.firstOrNull { it.argv == record.op }
                val mode = beforeRaw?.let { modeOf(it) }
                if (pkg != null && op != null && mode != null) ToolkitAction.SetAppOp(pkg, op, mode) else null
            }
            KIND_BUCKET -> {
                val bucket = StandbyBucket.entries.firstOrNull { it.rawValue.toString() == beforeRaw }
                if (pkg != null && bucket != null) ToolkitAction.SetBucket(pkg, bucket) else null
            }
            KIND_DOZE -> ToolkitAction.RestoreDoze(beforeRaw, record.afterRaw)
            else -> null
        }
    }

    /**
     * The value that existed before the first change of [row]'s chain: older rows of the same target join the
     * chain while each one's `after` is what the next one found (`before`). The walk covers DONE and RESTORED
     * rows alike (see [overrideRows]): a row already undone by a restore is still a link, so restoring one row
     * of a chain does not turn an older row's `before` into the original of the rows above it. A restore row
     * (`restoresId` set) or a gap (something else changed the target in between) ends it. [rows] is
     * [overrideRows], newest first.
     */
    private fun chainOriginal(row: ToolkitActionRecord, rows: List<ToolkitActionRecord>): String? {
        val key = targetKey(row)
        var original = row.beforeRaw
        for (older in rows.dropWhile { it.id != row.id }.drop(1).filter { targetKey(it) == key }) {
            if (older.restoresId != null || !sameState(older.kind, older.afterRaw, original)) break
            original = older.beforeRaw
        }
        return original
    }

    /** App op modes compare by meaning ("allow" is the DEFAULT mode); everything else by value. */
    private fun sameState(kind: String, current: String?, original: String?): Boolean {
        if (kind != KIND_APPOP) return norm(current) == norm(original)
        val mode = current?.let { modeOf(it) }
        return mode != null && mode == original?.let { modeOf(it) }
    }

    // ---- active overrides ------------------------------------------------------------------

    /**
     * Successful override-kind rows, newest first: DONE rows, plus RESTORED ones. A RESTORED row is a
     * change that did happen and was undone by a newer restore row, so it still belongs to its chain;
     * dropping it would break the chain and make [chainOriginal] stop at an intermediate value.
     */
    private suspend fun overrideRows(): List<ToolkitActionRecord> = overrideRows(audit.recent(MAX_ROWS))

    private fun overrideRows(all: List<ToolkitActionRecord>): List<ToolkitActionRecord> = all
        .filter {
            (it.status == ToolkitActionStatus.DONE || it.status == ToolkitActionStatus.RESTORED) &&
                it.kind in OVERRIDE_KINDS
        }
        .sortedWith(compareByDescending<ToolkitActionRecord> { it.startedAt }.thenByDescending { it.id })

    private suspend fun computeOverrides(): List<Override> = computeOverrides(overrideRows())

    private suspend fun computeOverrides(rows: List<ToolkitActionRecord>): List<Override> {
        // The newest successful write per target decides; a newer restore row (or a RESTORED newest row)
        // means nothing is overridden.
        val newest = LinkedHashMap<String, ToolkitActionRecord>()
        for (row in rows) newest.putIfAbsent(targetKey(row), row)
        val candidates = newest.values.filter { it.restoresId == null && it.status == ToolkitActionStatus.DONE }
        val result = mutableListOf<Override>()
        for ((pkg, group) in candidates.filter { it.kind != KIND_DOZE }.groupBy { it.pkg }) {
            val name = pkg ?: continue
            val ops = if (group.any { it.kind == KIND_APPOP }) readAppOps(name) else null
            val bucket = if (group.any { it.kind == KIND_BUCKET }) readBucket(name) else null
            for (row in group) {
                val current = when (row.kind) {
                    KIND_APPOP -> (ops as? ToolkitOutcome.Done)?.value?.modes?.get(AppOp.entries.firstOrNull { it.argv == row.op })
                    else -> (bucket as? ToolkitOutcome.Done)?.value?.raw?.toString()
                }
                if (current != null) activeOverride(row, current, rows)?.let { result += it }
            }
        }
        candidates.firstOrNull { it.kind == KIND_DOZE }?.let { row ->
            val doze = readDoze()
            if (doze is ToolkitOutcome.Done) activeOverride(row, doze.value.raw, rows)?.let { result += it }
        }
        return result.sortedByDescending { it.sinceMs }
    }

    /** The override [row] stands for, or null when it is not in effect or its chain has come back to the original. */
    private fun activeOverride(row: ToolkitActionRecord, current: String?, rows: List<ToolkitActionRecord>): Override? {
        if (!isActive(row, current)) return null
        val original = chainOriginal(row, rows)
        return if (sameState(row.kind, current, original)) null else overrideOf(row, current, original)
    }

    private fun targetKey(row: ToolkitActionRecord): String =
        if (row.kind == KIND_DOZE) KIND_DOZE else "${row.kind}|${row.pkg}|${if (row.kind == KIND_APPOP) row.op else ""}"

    /** Still ours when the value read back equals what we wrote, and that differs from what was there. */
    private fun isActive(row: ToolkitActionRecord, current: String?): Boolean {
        val after = norm(row.afterRaw)
        if (norm(current) != after || after == norm(row.beforeRaw)) return false
        return row.kind != KIND_DOZE || after != null
    }

    private fun overrideOf(row: ToolkitActionRecord, current: String?, original: String?): Override = Override(
        actionId = row.id,
        kind = when (row.kind) {
            KIND_APPOP -> OverrideKind.APP_OP
            KIND_BUCKET -> OverrideKind.BUCKET
            else -> OverrideKind.DOZE
        },
        pkg = row.pkg,
        appOp = if (row.kind == KIND_APPOP) AppOp.entries.firstOrNull { it.argv == row.op } else null,
        currentRaw = current,
        previousRaw = original,
        sinceMs = row.endedAt ?: row.startedAt,
        restorable = isRestorable(row, original),
    )

    /** Whether [restoreAction] could turn the chain original [original] back into an action. */
    private fun isRestorable(row: ToolkitActionRecord, original: String?): Boolean = when (row.kind) {
        KIND_APPOP -> original?.let { modeOf(it) } != null
        KIND_BUCKET -> StandbyBucket.entries.any { it.rawValue.toString() == original }
        else -> true
    }

    // ---- reconcile -------------------------------------------------------------------------

    private sealed interface Verdict {
        data object Unreachable : Verdict

        data object Unverifiable : Verdict

        class Matched(val current: String?) : Verdict

        class Mismatch(val current: String?) : Verdict
    }

    private suspend fun reconcileRow(row: ToolkitActionRecord) {
        val now = nowMs()
        val action = intendedAction(row)
        val verdict = if (action == null) Verdict.Mismatch(null) else verify(action)
        when (verdict) {
            Verdict.Unreachable -> Unit
            Verdict.Unverifiable ->
                audit.update(row.copy(status = ToolkitActionStatus.FAILED, endedAt = now, code = ToolkitClientCode.UNVERIFIED))
            is Verdict.Matched -> {
                audit.update(
                    row.copy(status = ToolkitActionStatus.DONE, endedAt = now, code = ToolkitCode.OK, afterRaw = verdict.current),
                )
                val original = row.restoresId?.let { id -> audit.recent(MAX_ROWS).firstOrNull { it.id == id } }
                if (original != null && original.status == ToolkitActionStatus.DONE) {
                    audit.update(original.copy(status = ToolkitActionStatus.RESTORED))
                }
            }
            is Verdict.Mismatch ->
                audit.update(
                    row.copy(
                        status = ToolkitActionStatus.FAILED,
                        endedAt = now,
                        code = ToolkitCode.NOT_EFFECTIVE,
                        afterRaw = verdict.current,
                    ),
                )
        }
    }

    private suspend fun verify(action: ToolkitAction): Verdict = when (action) {
        is ToolkitAction.SetAppOp -> {
            val read = readAppOps(action.pkg)
            when (read) {
                is ToolkitOutcome.Done -> read.value.modes[action.op].let { current ->
                    if (current != null && modeOf(current) == action.mode) Verdict.Matched(current) else Verdict.Mismatch(current)
                }
                is ToolkitOutcome.Refused -> Verdict.Mismatch(null)
                else -> Verdict.Unreachable
            }
        }
        is ToolkitAction.SetBucket -> when (val read = readBucket(action.pkg)) {
            is ToolkitOutcome.Done -> read.value.raw?.toString().let { current ->
                if (current == action.bucket.rawValue.toString()) Verdict.Matched(current) else Verdict.Mismatch(current)
            }
            is ToolkitOutcome.Refused -> Verdict.Mismatch(null)
            else -> Verdict.Unreachable
        }
        is ToolkitAction.ApplyDoze -> verifyDoze(norm(action.profile.constants))
        is ToolkitAction.RestoreDoze -> verifyDoze(norm(action.rawOrNull))
        is ToolkitAction.ForceStop -> Verdict.Unverifiable
    }

    private suspend fun verifyDoze(expected: String?): Verdict = when (val read = readDoze()) {
        is ToolkitOutcome.Done ->
            if (norm(read.value.raw) == expected) Verdict.Matched(read.value.raw) else Verdict.Mismatch(read.value.raw)
        else -> Verdict.Unreachable
    }

    // ---- dexopt status ---------------------------------------------------------------------

    /** Reads the job status, settles any PENDING dexopt row from it, and keeps the binding hold in step. */
    private suspend fun refreshDexoptLocked(): BgDexoptStatus {
        val pending = audit.pending().filter { it.kind == KIND_DEXOPT }
        val reply = backend.bgDexoptStatus()
        val service = when (reply) {
            // Cannot tell: keep whatever hold exists and do not touch the rows.
            is GatewayReply.Unavailable -> return if (pending.isEmpty()) BgDexoptStatus.Idle else BgDexoptStatus.Unknown
            is GatewayReply.Reply -> reply.value
        }
        val status = statusOf(service)
        syncHold(running = status is BgDexoptStatus.Running, startedAtMs = (status as? BgDexoptStatus.Running)?.startedAtMs ?: 0L)
        if (pending.isNotEmpty()) {
            when (status) {
                is BgDexoptStatus.Finished -> finalizeDexoptRows(
                    if (status.exitCode == 0) ToolkitActionStatus.DONE else ToolkitActionStatus.FAILED,
                    status.exitCode,
                    afterRaw = "finished",
                    now = status.endedAtMs.takeIf { it > 0 },
                )
                is BgDexoptStatus.Cancelled -> finalizeDexoptRows(
                    ToolkitActionStatus.CANCELLED,
                    service.reason,
                    afterRaw = "cancelled",
                    now = status.endedAtMs.takeIf { it > 0 },
                )
                // Proven nothing is running (a completed cancel, or a job that no longer exists).
                BgDexoptStatus.Idle -> finalizeDexoptRows(ToolkitActionStatus.CANCELLED, BgDexoptReason.NONE, afterRaw = "idle")
                is BgDexoptStatus.Running, BgDexoptStatus.Unknown -> Unit
            }
            changes.value += 1
        }
        return status
    }

    private fun statusOf(service: ServiceBgDexoptStatus): BgDexoptStatus = when (service.state) {
        BgDexoptState.RUNNING -> BgDexoptStatus.Running(service.startedAtMs)
        BgDexoptState.FINISHED -> BgDexoptStatus.Finished(service.exitCode, service.endedAtMs)
        BgDexoptState.CANCELLED -> BgDexoptStatus.Cancelled(cancelReasonOf(service.reason), service.endedAtMs)
        BgDexoptState.IDLE -> BgDexoptStatus.Idle
        else -> BgDexoptStatus.Unknown
    }

    private fun cancelReasonOf(reason: Int): BgDexoptCancelReason = when (reason) {
        BgDexoptReason.USER -> BgDexoptCancelReason.USER
        BgDexoptReason.UNPLUGGED -> BgDexoptCancelReason.UNPLUGGED
        BgDexoptReason.TIMEOUT -> BgDexoptCancelReason.TIMEOUT
        else -> BgDexoptCancelReason.OTHER
    }

    private suspend fun finalizeDexoptRows(
        status: ToolkitActionStatus,
        code: Int,
        afterRaw: String,
        now: Long? = null,
    ) {
        val ended = now ?: nowMs()
        for (row in audit.pending().filter { it.kind == KIND_DEXOPT }) {
            audit.update(row.copy(status = status, endedAt = ended, code = code, afterRaw = afterRaw))
        }
    }

    private suspend fun supersedeOtherDexoptRows(keepId: Long) {
        val ended = nowMs()
        for (row in audit.pending().filter { it.kind == KIND_DEXOPT && it.id != keepId }) {
            audit.update(row.copy(status = ToolkitActionStatus.CANCELLED, endedAt = ended, afterRaw = "superseded"))
        }
    }

    /**
     * Keeps the binding hold in step with the job. Taking the hold also starts [superviseHold], which ends it
     * on any terminal status or at the cap, so the hold cannot outlive the job when nothing is collecting.
     * [startedAtMs] is the service's wall-clock job start when known (0 = just started, use now).
     */
    private fun syncHold(running: Boolean, startedAtMs: Long = 0L) {
        synchronized(holdLock) {
            if (running) {
                if (dexoptHold == null) {
                    dexoptHold = holdBinding()
                    val deadline = (startedAtMs.takeIf { it > 0 } ?: nowMs()) + DEXOPT_CAP_MS + DEXOPT_CAP_MARGIN_MS
                    holdSupervisor = supervisorScope.launch { superviseHold(deadline) }
                }
            } else {
                holdSupervisor?.cancel()
                holdSupervisor = null
                dexoptHold?.close()
                dexoptHold = null
            }
        }
    }

    private fun holdTaken(): Boolean = synchronized(holdLock) { dexoptHold != null }

    /**
     * Re-reads the job status every [holdCheckMs], only while the hold is taken (so only while the job is
     * RUNNING, and with nobody collecting). [refreshDexoptLocked] releases the hold on any terminal status;
     * at [deadlineMs] (the service's own 60-minute cap plus a margin) the hold is dropped even if the service
     * cannot be asked, leaving the rows PENDING for the next status read or [reconcile].
     */
    private suspend fun superviseHold(deadlineMs: Long) {
        while (true) {
            delay(holdCheckMs)
            if (nowMs() >= deadlineMs) {
                syncHold(running = false)
                return
            }
            // A terminal status makes the refresh release the hold, which cancels this very job; the row
            // updates that follow in the same refresh must still complete.
            withContext(NonCancellable) { mutex.withLock { refreshDexoptLocked() } }
            if (!holdTaken()) return
        }
    }

    // ---- read-back plumbing ----------------------------------------------------------------

    private class AppOpsRead(val modes: Map<AppOp, String>, val updatedSystem: Boolean)

    private class BucketRead(val raw: Int?, val updatedSystem: Boolean)

    private suspend fun readAppOps(pkg: String): ToolkitOutcome<AppOpsRead> {
        val result = readOutcome(backend.getAppOps(pkg))
        if (result !is ToolkitOutcome.Done) return failure(result)
        val modes = parseAppOps(result.value.afterRaw) ?: return ToolkitOutcome.Failed(ToolkitCode.PARSE)
        return ToolkitOutcome.Done(AppOpsRead(modes, result.value.requiresWarning))
    }

    private suspend fun readBucket(pkg: String): ToolkitOutcome<BucketRead> {
        val result = readOutcome(backend.getStandbyBucket(pkg))
        if (result !is ToolkitOutcome.Done) return failure(result)
        val raw = result.value.afterRaw?.trim()?.toIntOrNull() ?: return ToolkitOutcome.Failed(ToolkitCode.PARSE)
        return ToolkitOutcome.Done(BucketRead(raw, result.value.requiresWarning))
    }

    private suspend fun readDoze(): ToolkitOutcome<DozeState> {
        val result = readOutcome(backend.getDozeState())
        if (result !is ToolkitOutcome.Done) return failure(result)
        val value = result.value
        val profile = if (value.code == ToolkitCode.DOZE_CUSTOM) null else DozeProfile.fromCode(value.code)
        if (profile == null && value.code != ToolkitCode.DOZE_CUSTOM) return ToolkitOutcome.Failed(ToolkitCode.PARSE)
        val effective = value.message.orEmpty().split(',').mapNotNull { pair ->
            val ms = pair.substringAfter('=', "").toLongOrNull()
            if (ms == null) null else pair.substringBefore('=') to ms
        }.toMap()
        return ToolkitOutcome.Done(DozeState(norm(value.afterRaw), profile, effective))
    }

    private fun readOutcome(reply: GatewayReply<ToolkitResult>): ToolkitOutcome<ToolkitResult> = when (reply) {
        is GatewayReply.Unavailable -> ToolkitOutcome.Unavailable(unavailableFor(reply.reason))
        is GatewayReply.Reply -> when (reply.value.status) {
            ToolkitStatus.DONE -> ToolkitOutcome.Done(reply.value)
            ToolkitStatus.REFUSED -> ToolkitOutcome.Refused(refusalFor(reply.value.code), reply.value.code)
            ToolkitStatus.UNAVAILABLE -> ToolkitOutcome.Unavailable(ToolkitUnavailable.NOT_EFFECTIVE, reply.value.code)
            else -> ToolkitOutcome.Failed(reply.value.code)
        }
    }

    /** Re-types a non-Done outcome. A Done never reaches here. */
    private fun failure(outcome: ToolkitOutcome<*>): ToolkitOutcome<Nothing> = when (outcome) {
        is ToolkitOutcome.Refused -> outcome
        is ToolkitOutcome.Unavailable -> outcome
        is ToolkitOutcome.Failed -> outcome
        is ToolkitOutcome.Done -> ToolkitOutcome.Failed(null)
    }

    private fun unavailableFor(reason: GatewayUnavailable): ToolkitUnavailable = when (reason) {
        GatewayUnavailable.NO_SERVICE -> ToolkitUnavailable.NO_SERVICE
        GatewayUnavailable.STALE_SERVICE -> ToolkitUnavailable.STALE_SERVICE
    }

    private fun refusalFor(code: Int): ToolkitRefusal = when (code) {
        ToolkitCode.CALLER -> ToolkitRefusal.CALLER
        ToolkitCode.ARG_PACKAGE, ToolkitCode.ARG_VALUE -> ToolkitRefusal.INVALID_ARGUMENT
        ToolkitCode.NOT_INSTALLED -> ToolkitRefusal.NOT_INSTALLED
        ToolkitCode.DENY_APP_ID,
        ToolkitCode.DENY_PERSISTENT,
        ToolkitCode.DENY_SYSTEM,
        ToolkitCode.DENY_PROTECTED,
        ToolkitCode.DENY_OWN,
        ToolkitCode.PROTECTION_UNKNOWN,
        -> ToolkitRefusal.PROTECTED_APP
        ToolkitCode.STALE -> ToolkitRefusal.STALE
        ToolkitCode.BUSY -> ToolkitRefusal.BUSY
        ToolkitCode.NOT_PLUGGED -> ToolkitRefusal.NOT_PLUGGED
        ToolkitCode.PARSE -> ToolkitRefusal.READ_FAILED
        else -> ToolkitRefusal.OTHER
    }

    /** `getAppOps` answers `OP=mode,OP=mode,...` for every [AppOp]; anything else is unreadable. */
    private fun parseAppOps(raw: String?): Map<AppOp, String>? {
        if (raw.isNullOrEmpty()) return null
        val modes = LinkedHashMap<AppOp, String>()
        for (pair in raw.split(',')) {
            val op = AppOp.entries.firstOrNull { it.argv == pair.substringBefore('=') } ?: continue
            modes[op] = pair.substringAfter('=', "").takeIf { it.isNotEmpty() } ?: return null
        }
        return modes.takeIf { it.isNotEmpty() }
    }

    private companion object {
        const val DEFAULT_DEXOPT_POLL_MS = 5_000L

        /** How often the hold supervisor re-checks a RUNNING job nobody is watching. */
        const val DEFAULT_HOLD_CHECK_MS = 60_000L

        /** The service's own job cap (`BgDexoptController.CAP_MS`), plus slack for its wind-down. */
        const val DEXOPT_CAP_MS = 60L * 60_000L
        const val DEXOPT_CAP_MARGIN_MS = 2L * 60_000L

        fun defaultSupervisorScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        const val MAX_ROWS = 500

        const val KIND_APPOP = "appop"
        const val KIND_BUCKET = "bucket"
        const val KIND_DOZE = "doze"
        const val KIND_FORCE_STOP = "force_stop"
        const val KIND_DEXOPT = "dexopt"

        const val OP_BUCKET = "standby_bucket"
        const val OP_FORCE_STOP = "force_stop"
        const val OP_APPLY_PROFILE = "apply_profile"
        const val OP_RESTORE_RAW = "restore_raw"
        const val OP_BG_DEXOPT = "bg_dexopt"

        val OVERRIDE_KINDS = setOf(KIND_APPOP, KIND_BUCKET, KIND_DOZE)

        /** Unset and empty are the same `device_idle_constants` state. */
        fun norm(raw: String?): String? = raw?.takeIf { it.isNotEmpty() }

        /** `ignore` and `default` are what this surface can set; `allow` is what the system calls its default. */
        fun modeOf(raw: String): AppOpMode? = when (raw) {
            AppOpMode.IGNORE.argv -> AppOpMode.IGNORE
            AppOpMode.DEFAULT.argv, "allow" -> AppOpMode.DEFAULT
            else -> null
        }

        fun bucketOf(raw: Int): StandbyBucket? = StandbyBucket.entries.firstOrNull { it.rawValue == raw }
    }
}
