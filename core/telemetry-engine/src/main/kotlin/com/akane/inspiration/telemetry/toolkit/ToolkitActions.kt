package com.akane.inspiration.telemetry.toolkit

import com.akane.inspiration.ipc.model.BgDexoptReason
import com.akane.inspiration.ipc.model.BgDexoptState
import com.akane.inspiration.ipc.model.ToolkitCode
import com.akane.inspiration.ipc.model.ToolkitResult
import com.akane.inspiration.ipc.model.ToolkitStatus
import com.akane.inspiration.ipc.toolkit.AppOp
import com.akane.inspiration.ipc.toolkit.AppOpMode
import com.akane.inspiration.ipc.toolkit.DozeProfile
import com.akane.inspiration.ipc.toolkit.DozeRaw
import com.akane.inspiration.ipc.toolkit.PackageNames
import com.akane.inspiration.ipc.toolkit.RestrictionLift
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
import com.akane.inspiration.ipc.model.BgDexoptStatus as ServiceBgDexoptStatus

/** Client-side result codes stored in `toolkit_action.code`; they sit above the service's [ToolkitCode] range. */
object ToolkitClientCode {
    const val NOT_CONFIRMED = 100
    const val NO_SERVICE = 101
    const val STALE_SERVICE = 102

    /**
     * A PENDING row settled without crediting it with a read-back, so it is never reported as done and never joins a
     * chain: its effect cannot be read back (force-stop), or the read-back came too late to tell its write from a later
     * change (B-01, see `ToolkitActions.promptReadBack`).
     */
    const val UNVERIFIED = 103

    /**
     * A DONE write whose service reply carried no "before" value (appop, bucket). The change happened and is
     * recorded as DONE, but it cannot be restored, so it is flagged rather than reported as a failure.
     */
    const val BEFORE_MISSING = 104

    /** The privileged call threw after the PENDING row existed; the uncertain row remains PENDING for readback and the error is rethrown. */
    const val WRITE_ERROR = 105
}

/**
 * The privileged calls [ToolkitActions] needs. [GatewayBackend] adapts the raw [ToolkitGateway]. Internal:
 * a feature that could build one could write without confirmation or an audit row.
 */
internal interface ToolkitBackend {
    suspend fun setAppOp(
        pkg: String,
        op: AppOp,
        mode: AppOpMode,
    ): GatewayReply<ToolkitResult>

    suspend fun getAppOps(pkg: String): GatewayReply<ToolkitResult>

    suspend fun setStandbyBucket(
        pkg: String,
        bucket: StandbyBucket,
    ): GatewayReply<ToolkitResult>

    suspend fun getStandbyBucket(pkg: String): GatewayReply<ToolkitResult>

    suspend fun applyDozeProfile(
        profile: DozeProfile,
        expectedBeforeRaw: String?,
    ): GatewayReply<ToolkitResult>

    suspend fun getDozeState(): GatewayReply<ToolkitResult>

    suspend fun restoreDozeRaw(
        rawOrNull: String?,
        expectedCurrentRaw: String?,
    ): GatewayReply<ToolkitResult>

    suspend fun forceStop(pkg: String): GatewayReply<ToolkitResult>

    suspend fun startBgDexopt(): GatewayReply<ToolkitResult>

    suspend fun bgDexoptStatus(): GatewayReply<ServiceBgDexoptStatus>

    suspend fun cancelBgDexopt(): GatewayReply<ToolkitResult>
}

internal class GatewayBackend(
    private val gateway: ToolkitGateway,
) : ToolkitBackend {
    override suspend fun setAppOp(
        pkg: String,
        op: AppOp,
        mode: AppOpMode,
    ) = gateway.setAppOp(pkg, op, mode)

    override suspend fun getAppOps(pkg: String) = gateway.getAppOps(pkg)

    override suspend fun setStandbyBucket(
        pkg: String,
        bucket: StandbyBucket,
    ) = gateway.setStandbyBucket(pkg, bucket)

    override suspend fun getStandbyBucket(pkg: String) = gateway.getStandbyBucket(pkg)

    override suspend fun applyDozeProfile(
        profile: DozeProfile,
        expectedBeforeRaw: String?,
    ) = gateway.applyDozeProfile(profile, expectedBeforeRaw)

    override suspend fun getDozeState() = gateway.getDozeState()

    override suspend fun restoreDozeRaw(
        rawOrNull: String?,
        expectedCurrentRaw: String?,
    ) = gateway.restoreDozeRaw(rawOrNull, expectedCurrentRaw)

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

    /** Deletes the settled rows [ids] in one step; a PENDING row is never deleted. */
    suspend fun delete(ids: List<Long>)
}

internal class SnapshotStoreAudit(
    private val store: SnapshotStore,
) : ToolkitAuditStore {
    override suspend fun insert(record: ToolkitActionRecord) = store.insertToolkitAction(record)

    override suspend fun update(record: ToolkitActionRecord) = store.updateToolkitAction(record)

    override suspend fun recent(limit: Int) = store.toolkitActions(limit)

    override suspend fun pending() = store.pendingToolkitActions()

    override suspend fun delete(ids: List<Long>) = store.deleteToolkitActions(ids)
}

/**
 * The only toolkit API features see (the raw gateway stays inside the container). Every write:
 * re-validates, asks for confirmation when risky, inserts a PENDING audit row *before* the privileged
 * call, then finalizes that row with the service's own before/after. REFUSED attempts are recorded too.
 * All inputs are typed; nothing here reads an Intent or a URI.
 *
 * Reads (plan, appState, overrides) go to the service every time: the audit log is history, not state. The
 * one exception is a chain the log proves closed (its newest `after` is its original), which no read can make
 * an override, so it is not read. The log keeps the newest [ToolkitAuditLog.RETAINED_ROWS] rows plus what an
 * override chain still needs (see [pruneClosedChains]).
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
    private val elapsedMs: () -> Long = ::monotonicMs,
) {
    /**
     * The production constructor. [supervisorScope] outlives any screen: it runs the bounded supervisor that
     * releases the dexopt binding hold when the job ends, whether or not anything is collecting. [nowMs] is the
     * wall clock for the audit rows; [elapsedMs] is a monotonic clock (elapsedRealtime in the app) for the hold
     * deadline, so a wall-clock change cannot end or stretch the hold. It also times a reconcile against the write it
     * settles, and as the time since boot it marks a row of an earlier boot as late (see `promptReadBack`).
     */
    constructor(
        gateway: ToolkitGateway,
        store: SnapshotStore,
        holdBinding: () -> AutoCloseable,
        nowMs: () -> Long = System::currentTimeMillis,
        supervisorScope: CoroutineScope = defaultSupervisorScope(),
        elapsedMs: () -> Long = ::monotonicMs,
    ) : this(
        GatewayBackend(gateway),
        SnapshotStoreAudit(store),
        holdBinding,
        nowMs,
        supervisorScope = supervisorScope,
        elapsedMs = elapsedMs,
    )

    /** Serializes execute, restore, reconcile and status handling so a PENDING row is never seen half-written. */
    private val mutex = Mutex()

    /** Guarded by [mutex]: a prior job's status/cancel cannot settle a start still awaiting admission. */
    private val dexoptStartsInFlight = mutableSetOf<Long>()

    /**
     * Guarded by [mutex]: when, on [elapsedMs], this process inserted each override row that may still be PENDING, so
     * [promptReadBack] times this process's rows on a clock nobody can set. Entries of settled rows are dropped by
     * [forgetSettled].
     */
    private val pendingSince = HashMap<Long, Long>()

    /** Bumped when the log or the real state may have changed; drives [history] and [activeOverrides]. */
    private val changes = MutableStateFlow(0L)

    /** Bumped when a dexopt start or cancel happened; wakes [bgDexoptStatus]. */
    private val dexoptEvents = MutableStateFlow(0L)

    private val holdLock = Any()
    private var dexoptHold: AutoCloseable? = null
    private var holdSupervisor: Job? = null

    // ---- reads -----------------------------------------------------------------------------

    /**
     * The app's controls. A protected app answers with why it is protected: its controls stay unavailable, and a
     * change of ours on it is undone from the overrides list (a restore only lifts a restriction).
     */
    suspend fun appState(pkg: String): ToolkitOutcome<AppToolkitState> {
        if (!PackageNames.isValid(pkg)) return ToolkitOutcome.Refused(ToolkitRefusal.INVALID_ARGUMENT, ToolkitCode.ARG_PACKAGE)
        val ops = readAppOps(pkg)
        if (ops !is ToolkitOutcome.Done) return failure(ops)
        ops.value.protection?.let { return it }
        val bucket = readBucket(pkg)
        if (bucket !is ToolkitOutcome.Done) return failure(bucket)
        bucket.value.protection?.let { return it }
        val bucketRaw = bucket.value.raw
        return ToolkitOutcome.Done(
            AppToolkitState(
                pkg = pkg,
                appOps = ops.value.modes.mapValues { (_, raw) -> observedModeOf(raw) },
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
     * object changes something. A change whose target cannot be read back now (the service is unreachable,
     * the read failed, or the app is now off limits) is listed with [Override.unverified] rather than dropped, and
     * so is a write whose outcome is still unknown (a PENDING row that could not be read back to settle it).
     */
    fun activeOverrides(): Flow<List<Override>> =
        changes.map {
            mutex.withLock {
                reconcileTargets(null)
                computeOverrides()
            }
        }

    // ---- plan / execute --------------------------------------------------------------------

    suspend fun plan(action: ToolkitAction): ToolkitPlan = mutex.withLock { planLocked(action, null) }

    suspend fun planRestore(actionId: Long): ToolkitPlan =
        mutex.withLock {
            reconcileTargets(null)
            when (val decoded = decodeRestore(actionId)) {
                is Decoded.No -> ToolkitPlan.Refused(decoded.reason)
                is Decoded.Ok -> planLocked(decoded.action, decoded.record)
            }
        }

    /**
     * Runs [action]. When it needs confirmation and [confirmed] is false it returns Refused(NOT_CONFIRMED)
     * and no privileged write is issued (the attempt is still recorded as REFUSED).
     */
    suspend fun execute(
        action: ToolkitAction,
        confirmed: Boolean = false,
    ): ToolkitOutcome<ToolkitActionRecord> =
        mutex.withLock {
            reconcileTargets(targetKey(specOf(action)))
            pruneClosedChains()
            run(action, confirmed, null)
        }

    /**
     * Undoes audit row [actionId]: the stored values are decoded through the same enums and validation
     * as any action. An override row goes back to the original of its chain (as [resetAll] does), and is
     * refused with ALREADY_RESTORED when its own `after`, or the value read back now, already is that
     * original (nothing is written then). If the current value is no longer the recorded `after`, confirmation is needed
     * (see [planRestore]). The new row carries `restoresId`; the original becomes RESTORED.
     */
    suspend fun restore(
        actionId: Long,
        confirmed: Boolean = false,
    ): ToolkitOutcome<ToolkitActionRecord> =
        mutex.withLock {
            reconcileTargets(null)
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
     * listed and nothing is written for it. An [unverified][Override.unverified] one is tried too: its restore reads
     * the target first, and is reported as not restored, with why, while that read still fails. A write whose outcome
     * is still unknown (a PENDING row its read-back could not settle) is listed but not written over: it is reported
     * as [ToolkitUnavailable.UNVERIFIED] until a read-back settles it. The user asked for this explicitly, so the
     * per-item restore is confirmed; a failure does not stop the remaining items. One lock covers the whole reset, so
     * the chains read here cannot change under it.
     */
    suspend fun resetAll(): List<ResetItem> =
        mutex.withLock {
            reconcileTargets(null)
            val rows = overrideRows()
            val unsettled = unsettledRows()
            computeOverrides(rows, unsettled).map { override -> ResetItem(override, resetOne(override, rows, unsettled)) }
        }

    /**
     * An unsettled PENDING override ([unsettledOverrides]) is not written over: [run] answers Unavailable(UNVERIFIED)
     * for a target with a PENDING row, so Reset all reports it as not restored until its read-back settles it.
     */
    private suspend fun resetOne(
        override: Override,
        rows: List<ToolkitActionRecord>,
        unsettled: List<ToolkitActionRecord>,
    ): ToolkitOutcome<ToolkitActionRecord> {
        val row = rows.firstOrNull { it.id == override.actionId } ?: unsettled.first { it.id == override.actionId }
        val action =
            restoreAction(row, chainOriginal(row, chainWith(row, rows)))
                ?: return ToolkitOutcome.Refused(ToolkitRefusal.NOT_RESTORABLE)
        // A storage error before this item's write (its write-ahead row) fails this item only: the items already
        // restored are reported as restored, not lost with the whole reset (R-05).
        return try {
            run(action, confirmed = true, restoring = row, requireExpectedCurrent = true)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            ToolkitOutcome.Failed(code = null)
        }
    }

    // ---- reconcile -------------------------------------------------------------------------

    /**
     * Finalizes rows left PENDING by a crash or kill, a lost reply, or a write the service could not verify
     * (those of an override target are also settled before that target is used again). The state is read back: DONE when it matches the
     * intended `after`, otherwise FAILED (a job whose status is gone is CANCELLED instead; a FAILED override row
     * whose read-back left its `before` stays in its chain, see [changedTarget]). Only a prompt read-back is credited
     * to the write ([promptReadBack]); a later one settles the row FAILED as unverified with no `after`, so a value the
     * platform or another tool set meanwhile is never listed or written over. A row whose
     * state cannot be read (service unreachable, or a dexopt status that is UNKNOWN or still RUNNING, or unsupported
     * while the job could still be within its cap) stays PENDING and is retried on the next call.
     */
    suspend fun reconcile() {
        mutex.withLock {
            val pending = audit.pending()
            forgetSettled(pending)
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
     * result is settled under the lock again. Its row is protected from other terminalizers until admission
     * returns: a previous job's status or an earlier cancel says nothing about this start. Once the row exists,
     * settlement and local-ID cleanup run to completion even if the caller is cancelled; a lost reply leaves
     * the durable PENDING row for recovery rather than manufacturing a terminal outcome.
     */
    suspend fun startBgDexopt(): ToolkitOutcome<ToolkitActionRecord> =
        withContext(NonCancellable) {
            val pending =
                mutex.withLock {
                    insertPending(Spec(KIND_DEXOPT, null, OP_BG_DEXOPT, null), restoresId = null, beforeRaw = null)
                        .also { dexoptStartsInFlight += it.id }
                }
            try {
                val reply =
                    try {
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
                    val outcome =
                        afterDispatch(reply) {
                            settle(pending, reply, requireBefore = false, keepPendingOnDone = true).also { settled ->
                                if (settled is ToolkitOutcome.Done) {
                                    syncHold(running = true)
                                    supersedeOtherDexoptRows(settled.value.id)
                                }
                            }
                        }
                    changes.value += 1
                    outcome
                }
            } finally {
                // The outer NonCancellable also fences this cleanup if admission or settlement throws.
                mutex.withLock {
                    dexoptStartsInFlight -= pending.id
                    // Publish completion only after status reads can stop reporting admission as UNKNOWN.
                    dexoptEvents.value += 1
                }
            }
        }

    /**
     * Cancel is system-side and idempotent, so it is valid whether or not this process started the job. A successful
     * cancel settles nothing by itself: it may have stopped a job ours was waiting behind. The service's status
     * settles the row, as for the cap or an unplug (CANCELLED once our job's client has exited, UNKNOWN when it
     * outlived every cancel), and the binding hold follows that status as it does after a start. A timed-out cancel
     * (UNVERIFIED) may still take effect, so it is followed the same way.
     */
    suspend fun cancelBgDexopt(): ToolkitOutcome<Unit> =
        mutex.withLock {
            val read = readOutcome(backend.cancelBgDexopt())
            val outcome: ToolkitOutcome<Unit> = if (read is ToolkitOutcome.Done) ToolkitOutcome.Done(Unit) else failure(read)
            if (outcome is ToolkitOutcome.Done || (outcome as? ToolkitOutcome.Unavailable)?.reason == ToolkitUnavailable.UNVERIFIED) {
                refreshDexoptLocked()
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
    fun bgDexoptStatus(): Flow<BgDexoptStatus> =
        flow {
            while (true) {
                val seen = dexoptEvents.value
                val status = mutex.withLock { refreshDexoptLocked() }
                emit(status)
                if (status is BgDexoptStatus.Running) delay(dexoptPollMs) else dexoptEvents.first { it != seen }
            }
        }.distinctUntilChanged()

    // ---- planning --------------------------------------------------------------------------

    private suspend fun planLocked(
        action: ToolkitAction,
        restoring: ToolkitActionRecord?,
    ): ToolkitPlan {
        if (isInvalid(action)) return ToolkitPlan.Refused(ToolkitRefusal.INVALID_ARGUMENT)
        return when (val prep = prepare(action, restoring)) {
            is Prep.Stop -> {
                ToolkitPlan.Refused(planRefusal(prep.outcome))
            }

            is Prep.Ok -> {
                if (alreadyRestored(action, prep, restoring)) {
                    ToolkitPlan.Refused(ToolkitRefusal.ALREADY_RESTORED)
                } else {
                    confirmationPlan(prep.warnings())
                }
            }
        }
    }

    /**
     * Whether restoring [restoring] would write what the target already reads back (the same observed state;
     * `default` is not `allow`): its chain is already at the original, whoever got it there, so nothing is written.
     */
    private fun alreadyRestored(
        action: ToolkitAction,
        prep: Prep.Ok,
        restoring: ToolkitActionRecord?,
    ): Boolean {
        if (restoring == null || restoring.kind !in OVERRIDE_KINDS) return false
        val target =
            when (action) {
                is ToolkitAction.SetAppOp -> action.mode.argv
                is ToolkitAction.SetBucket -> action.bucket.rawValue.toString()
                is ToolkitAction.RestoreDoze -> action.rawOrNull
                else -> return false
            }
        return sameState(restoring.kind, prep.current, target)
    }

    private fun confirmationPlan(warnings: List<ToolkitWarning>): ToolkitPlan =
        if (warnings.isEmpty()) ToolkitPlan.Ready else ToolkitPlan.NeedsConfirmation(warnings.first(), warnings.drop(1))

    private fun planRefusal(outcome: ToolkitOutcome<Nothing>): ToolkitRefusal =
        when (outcome) {
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

        class Stop(
            val outcome: ToolkitOutcome<Nothing>,
        ) : Prep
    }

    /** Re-validates [action] against the live state: reads back, and collects every confirmation reason. */
    private suspend fun prepare(
        action: ToolkitAction,
        restoring: ToolkitActionRecord? = null,
    ): Prep {
        val static = staticWarnings(action)
        return when (action) {
            // A protected app takes only the undo of a change of ours ([restoring]: a restore or Reset all of our own
            // chain), and only when it lifts a restriction. The service enforces the lift-only half and keeps no
            // history; a new change, even one that lifts, would start a chain whose original could never be written
            // back, so it is refused here.
            is ToolkitAction.SetAppOp -> {
                val read = readAppOps(action.pkg)
                if (read !is ToolkitOutcome.Done) return stop(read)
                read.value.protection?.let { if (restoring == null || !RestrictionLift.appOp(action.mode)) return Prep.Stop(it) }
                val current = read.value.modes[action.op]
                Prep.Ok(static, systemWarning(read.value.updatedSystem), current, restoring != null, restoring?.afterRaw)
            }

            is ToolkitAction.SetBucket -> {
                val read = readBucket(action.pkg)
                if (read !is ToolkitOutcome.Done) return stop(read)
                read.value.protection?.let { protection ->
                    val lifts = read.value.raw?.let { RestrictionLift.bucket(action.bucket, it) } ?: false
                    if (restoring == null || !lifts) return Prep.Stop(protection)
                }
                val current = read.value.raw?.toString()
                Prep.Ok(static, systemWarning(read.value.updatedSystem), current, restoring != null, restoring?.afterRaw)
            }

            is ToolkitAction.ForceStop -> {
                // Validation, hard-deny and the updated-system flag come from a read of the same package.
                val read = readAppOps(action.pkg)
                if (read !is ToolkitOutcome.Done) return stop(read)
                read.value.protection?.let { return Prep.Stop(it) }
                Prep.Ok(static, systemWarning(read.value.updatedSystem), null, false, null)
            }

            is ToolkitAction.ApplyDoze -> {
                val read = readDoze()
                if (read !is ToolkitOutcome.Done) return stop(read)
                // The value the user decided against must still be there; the write's own CAS uses this read.
                val seen = action.expectedBefore
                if (seen != null && norm(read.value.raw) != norm(seen.raw)) {
                    return Prep.Stop(ToolkitOutcome.Refused(ToolkitRefusal.STALE, ToolkitCode.STALE))
                }
                val overCustom = action.profile == DozeProfile.STANDARD && (read.value.isCustom || customDozeOriginal(read.value.raw))
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

    /**
     * Whether the doze value [current] is one of our changes still in effect over a custom original (custom,
     * then Balanced): Standard would then replace that custom value too, so it is confirmed like Standard over
     * a custom value read now.
     */
    private suspend fun customDozeOriginal(current: String?): Boolean {
        val rows = overrideRows()
        val newest = rows.firstOrNull { it.kind == KIND_DOZE } ?: return false
        if (closesChain(newest) || newest.status == ToolkitActionStatus.RESTORED || !isActive(newest, current)) return false
        val original = chainOriginal(newest, rows) ?: return false
        return DozeProfile.entries.none { it.constants == original }
    }

    private fun systemWarning(updatedSystem: Boolean): List<ToolkitWarning> =
        if (updatedSystem) listOf(ToolkitWarning.UPDATED_SYSTEM_APP) else emptyList()

    /** Reasons that need no read to know about. */
    private fun staticWarnings(action: ToolkitAction): List<ToolkitWarning> =
        when (action) {
            is ToolkitAction.ForceStop -> {
                listOf(ToolkitWarning.FORCE_STOP)
            }

            is ToolkitAction.SetBucket -> {
                if (action.bucket == StandbyBucket.RESTRICTED) listOf(ToolkitWarning.RESTRICTED_BUCKET) else emptyList()
            }

            is ToolkitAction.SetAppOp -> {
                when {
                    action.mode != AppOpMode.IGNORE -> emptyList()
                    action.op == AppOp.WAKE_LOCK -> listOf(ToolkitWarning.WAKE_LOCK_IGNORE)
                    action.op == AppOp.RUN_ANY_IN_BACKGROUND -> listOf(ToolkitWarning.RUN_ANY_IN_BACKGROUND_IGNORE)
                    else -> emptyList()
                }
            }

            is ToolkitAction.ApplyDoze, is ToolkitAction.RestoreDoze -> {
                emptyList()
            }
        }

    /** [isMalformed], or a mode the service never writes (`default` would leave background ops restricted). */
    private fun isInvalid(action: ToolkitAction): Boolean =
        isMalformed(action) || (action is ToolkitAction.SetAppOp && !action.mode.writable)

    /** Inputs that cannot be stored or replayed at all. A legacy recorded `default` intent is not malformed. */
    private fun isMalformed(action: ToolkitAction): Boolean =
        when (action) {
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
        requireExpectedCurrent: Boolean = false,
    ): ToolkitOutcome<ToolkitActionRecord> {
        val spec = specOf(action)
        val restoresId = restoring?.id
        if (isInvalid(action)) {
            return refuse(spec, restoresId, ToolkitRefusal.INVALID_ARGUMENT, ToolkitCode.ARG_VALUE)
        }
        // Reasons known without a read are checked first so an unconfirmed risky action touches nothing. A
        // restore reads first: when the target already reads its original, ALREADY_RESTORED (no row, no write)
        // outranks the confirmation, exactly as in planRestore. The check below still covers its static reasons.
        if (restoring == null && !confirmed && staticWarnings(action).isNotEmpty()) {
            return refuse(spec, restoresId, ToolkitRefusal.NOT_CONFIRMED, ToolkitClientCode.NOT_CONFIRMED)
        }
        // A same-target row that could not be settled (see reconcileTargets) may have committed; writing on top of
        // it would make it settle against this write's value later and drop out of the chain. Nothing is written.
        if (spec.kind in OVERRIDE_KINDS && audit.pending().any { targetKey(it) == targetKey(spec) }) {
            return ToolkitOutcome.Unavailable(ToolkitUnavailable.UNVERIFIED)
        }
        val prep =
            when (val prepared = prepare(action, restoring)) {
                is Prep.Stop -> return stopOutcome(spec, restoresId, prepared.outcome)
                is Prep.Ok -> prepared
            }
        if (alreadyRestored(action, prep, restoring)) return ToolkitOutcome.Refused(ToolkitRefusal.ALREADY_RESTORED)
        // Reset all confirms only the enumerated override, not a later external change. This is a fresh-read
        // guard, not atomic compare-and-set: bucket and app-op commands cannot enforce a platform-side CAS.
        if (requireExpectedCurrent && restoring != null && !sameState(restoring.kind, prep.current, restoring.afterRaw)) {
            return refuse(spec, restoresId, ToolkitRefusal.STALE, ToolkitCode.STALE)
        }
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
            val reply =
                try {
                    write(action, prep)
                } catch (e: CancellationException) {
                    // Not ours to settle: whether the write happened is unknown, so reconcile() reads it back.
                    throw e
                } catch (e: Throwable) {
                    failPending(pending)
                    changes.value += 1
                    throw e
                }
            val outcome =
                afterDispatch(reply) {
                    settle(pending, reply, requireBefore = spec.kind != KIND_DOZE, keepPendingOnDone = false).also { settled ->
                        if (settled is ToolkitOutcome.Done && restoring != null) {
                            audit.update(restoring.copy(status = ToolkitActionStatus.RESTORED))
                        }
                    }
                }
            changes.value += 1
            outcome
        }
    }

    /**
     * Runs the audit bookkeeping of a privileged call that got [reply]. A storage error here (a Room error on a full
     * disk, say) is not "the command failed" (R-05): the outcome is still the one the reply reports ([replyOutcome]),
     * so a call never sent stays unavailable and a refusal stays a refusal (a STALE one is asked again). Only a call
     * that may have changed the device (a DONE reply whose row was not stored, or a reply lost after the call was
     * sent) is "sent, but could not be confirmed"; a row left PENDING is read back before its target is used again.
     * A failure before dispatch (the write-ahead row cannot be stored) still propagates. Cancellation does too.
     */
    private inline fun <T> afterDispatch(
        reply: GatewayReply<ToolkitResult>,
        bookkeeping: () -> ToolkitOutcome<T>,
    ): ToolkitOutcome<T> =
        try {
            bookkeeping()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            if (reply is GatewayReply.Unavailable && reply.sent) {
                ToolkitOutcome.Unavailable(ToolkitUnavailable.UNVERIFIED)
            } else {
                replyOutcome(reply)
            }
        }

    private suspend fun failPending(pending: ToolkitActionRecord) {
        audit.update(pending.copy(status = ToolkitActionStatus.PENDING, endedAt = null, code = ToolkitClientCode.WRITE_ERROR))
    }

    private suspend fun write(
        action: ToolkitAction,
        prep: Prep.Ok,
    ): GatewayReply<ToolkitResult> =
        when (action) {
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

    private suspend fun insertPending(
        spec: Spec,
        restoresId: Long?,
        beforeRaw: String?,
    ): ToolkitActionRecord {
        val record = spec.record(ToolkitActionStatus.PENDING, nowMs(), restoresId, beforeRaw)
        val insertedAt = elapsedMs()
        val inserted = record.copy(id = audit.insert(record))
        if (spec.kind in OVERRIDE_KINDS) pendingSince[inserted.id] = insertedAt
        return inserted
    }

    /**
     * Finalizes [pending] from the service's [reply] and maps it to the outcome. A reply lost after the call was
     * dispatched keeps the row PENDING (the write may have committed; [reconcile] reads it back). A call that was
     * never dispatched changed nothing, so its row is final at once: FAILED with no `after`, which never joins a
     * chain (see [landedElsewhere]) and never holds a dexopt card in Unknown.
     */
    private suspend fun settle(
        pending: ToolkitActionRecord,
        reply: GatewayReply<ToolkitResult>,
        requireBefore: Boolean,
        keepPendingOnDone: Boolean,
    ): ToolkitOutcome<ToolkitActionRecord> {
        val now = nowMs()
        if (reply is GatewayReply.Unavailable) {
            val code =
                if (reply.reason == GatewayUnavailable.STALE_SERVICE) {
                    ToolkitClientCode.STALE_SERVICE
                } else {
                    ToolkitClientCode.NO_SERVICE
                }
            val settled =
                if (reply.sent) {
                    pending.copy(status = ToolkitActionStatus.PENDING, endedAt = null, code = code)
                } else {
                    pending.copy(status = ToolkitActionStatus.FAILED, endedAt = now, code = code, afterRaw = null)
                }
            audit.update(settled)
            return replyOutcome(reply)
        }
        val result = (reply as GatewayReply.Reply).value
        when {
            result.status == ToolkitStatus.UNAVAILABLE && result.code == ToolkitCode.UNVERIFIED -> {
                // Issued, outcome unknown: like a lost reply, the row stays PENDING so a read-back settles it, and a
                // committed change still joins its target's chain before anything else touches it (reconcileTargets).
                val before = result.beforeRaw ?: pending.beforeRaw
                audit.update(pending.copy(status = ToolkitActionStatus.PENDING, endedAt = null, code = result.code, beforeRaw = before))
            }

            result.status == ToolkitStatus.DONE -> {
                return settleDone(pending, result, now, requireBefore, keepPendingOnDone)
            }

            result.status == ToolkitStatus.REFUSED -> {
                audit.update(finished(pending, ToolkitActionStatus.REFUSED, result, now))
            }

            else -> {
                audit.update(finished(pending, ToolkitActionStatus.FAILED, result, now))
            }
        }
        return replyOutcome(reply)
    }

    /**
     * What the service's [reply] means for the user, from the reply alone. A DONE reply's outcome carries the row
     * [settleDone] stored, so without that row it is only "sent, but could not be confirmed".
     */
    private fun replyOutcome(reply: GatewayReply<ToolkitResult>): ToolkitOutcome<Nothing> {
        if (reply is GatewayReply.Unavailable) return ToolkitOutcome.Unavailable(unavailableFor(reply.reason))
        val result = (reply as GatewayReply.Reply).value
        return when {
            result.status == ToolkitStatus.UNAVAILABLE && result.code == ToolkitCode.UNVERIFIED -> {
                ToolkitOutcome.Unavailable(ToolkitUnavailable.UNVERIFIED, result.code)
            }

            result.status == ToolkitStatus.DONE -> {
                ToolkitOutcome.Unavailable(ToolkitUnavailable.UNVERIFIED)
            }

            result.status == ToolkitStatus.REFUSED -> {
                ToolkitOutcome.Refused(refusalFor(result.code), result.code)
            }

            result.status == ToolkitStatus.UNAVAILABLE -> {
                ToolkitOutcome.Unavailable(ToolkitUnavailable.NOT_EFFECTIVE, result.code)
            }

            else -> {
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
    ): ToolkitActionRecord =
        pending.copy(
            status = status,
            endedAt = now,
            code = result.code,
            beforeRaw = result.beforeRaw ?: pending.beforeRaw.takeIf { status != ToolkitActionStatus.DONE },
            afterRaw = result.afterRaw,
        )

    // ---- audit row shape -------------------------------------------------------------------

    /** How an action is stored in a `toolkit_action` row. Explicit strings, never enum names. */
    private class Spec(
        val kind: String,
        val pkg: String?,
        val op: String,
        val param: String?,
    ) {
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

    private fun specOf(action: ToolkitAction): Spec =
        when (action) {
            is ToolkitAction.SetAppOp -> Spec(KIND_APPOP, validPkg(action.pkg), action.op.argv, action.mode.argv)
            is ToolkitAction.SetBucket -> Spec(KIND_BUCKET, validPkg(action.pkg), OP_BUCKET, action.bucket.argv)
            is ToolkitAction.ForceStop -> Spec(KIND_FORCE_STOP, validPkg(action.pkg), OP_FORCE_STOP, null)
            is ToolkitAction.ApplyDoze -> Spec(KIND_DOZE, null, OP_APPLY_PROFILE, profileToken(action.profile))
            is ToolkitAction.RestoreDoze -> Spec(KIND_DOZE, null, OP_RESTORE_RAW, norm(action.rawOrNull))
        }

    /** Only well-formed package names are persisted, so the log never stores arbitrary caller text. */
    private fun validPkg(pkg: String): String? = pkg.takeIf { PackageNames.isValid(it) }

    private fun profileToken(profile: DozeProfile): String =
        when (profile) {
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

            KIND_FORCE_STOP -> {
                pkg?.let { ToolkitAction.ForceStop(it) }
            }

            KIND_DOZE -> {
                when (row.op) {
                    OP_APPLY_PROFILE -> {
                        DozeProfile.entries
                            .firstOrNull { profileToken(it) == row.param }
                            ?.let { ToolkitAction.ApplyDoze(it) }
                    }

                    OP_RESTORE_RAW -> {
                        ToolkitAction.RestoreDoze(row.param, null)
                    }

                    else -> {
                        null
                    }
                }
            }

            else -> {
                null
            }
        }
    }

    // ---- restore ---------------------------------------------------------------------------

    private sealed interface Decoded {
        class Ok(
            val action: ToolkitAction,
            val record: ToolkitActionRecord,
        ) : Decoded

        class No(
            val reason: ToolkitRefusal,
        ) : Decoded
    }

    private suspend fun decodeRestore(actionId: Long): Decoded {
        val all = audit.recent(MAX_ROWS)
        val record = all.firstOrNull { it.id == actionId } ?: return Decoded.No(ToolkitRefusal.UNKNOWN_ACTION)
        if (record.status == ToolkitActionStatus.RESTORED) return Decoded.No(ToolkitRefusal.ALREADY_RESTORED)
        // An unsettled override (its read-back failed just now, see unsettledOverrides): nothing is written over it
        // until a read-back settles it, which is what failed.
        if (record.status == ToolkitActionStatus.PENDING && record.kind in OVERRIDE_KINDS) return Decoded.No(ToolkitRefusal.READ_FAILED)
        // A write that moved its target to a third value is undone like a DONE one (see changedTarget).
        if ((record.status != ToolkitActionStatus.DONE && !landedElsewhere(record)) || endsOwnChain(record)) {
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
    private fun restoreAction(
        record: ToolkitActionRecord,
        beforeRaw: String?,
    ): ToolkitAction? {
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

            KIND_DOZE -> {
                ToolkitAction.RestoreDoze(beforeRaw, record.afterRaw)
            }

            else -> {
                null
            }
        }
    }

    /**
     * The value that existed before the first change of [row]'s chain: older rows of the same target join the
     * chain while each one's `after` is what the next one found (`before`). The walk covers DONE and RESTORED
     * rows alike (see [overrideRows]): a row already undone by a restore is still a link, so restoring one row
     * of a chain does not turn an older row's `before` into the original of the rows above it. A completed
     * restore or a gap (something else changed the target in between) ends it. A partial restore instead
     * anchors the original in its typed intended value, even if its confirmed `before` was external. [rows]
     * is [overrideRows], newest first. The links are matched on raw values; the answer is [originalState].
     */
    private fun chainOriginal(
        row: ToolkitActionRecord,
        rows: List<ToolkitActionRecord>,
    ): String? {
        if (legacyDefaultRestore(row)) return AppOpMode.ALLOW.argv
        partialRestore(row)?.let { return it.original }
        val key = targetKey(row)
        var original = row.beforeRaw
        for (older in rows.dropWhile { it.id != row.id }.drop(1).filter { targetKey(it) == key }) {
            if (closesChain(older) || !sameState(older.kind, older.afterRaw, original)) break
            partialRestore(older)?.let { return it.original }
            original = older.beforeRaw
        }
        return originalState(row.kind, original)
    }

    /**
     * The state a recorded original stands for. Older releases recorded an app op with no entry ("No
     * operations.", i.e. allowed) as `default`, and their "allow" wrote the stored mode `default`, which
     * Android still enforces as restricted. A recorded app-op original of `default` therefore means `allow`:
     * that is what restore writes, so a target left at `default` is still an override Reset all repairs.
     */
    private fun originalState(
        kind: String,
        raw: String?,
    ): String? = if (kind == KIND_APPOP && raw == AppOpMode.DEFAULT.argv) AppOpMode.ALLOW.argv else raw

    /** Wraps a typed restore target so an unset doze original is distinct from "not a partial restore". */
    private class PartialRestore(
        val original: String?,
    )

    /**
     * A failed restore that observably moved its target still owns its `after`, not its intended original.
     * Persisted parameters supply that original: `before` may be an external value explicitly confirmed
     * by the user, and the older history may have been pruned. Unknown or invalid actions cannot anchor it.
     */
    private fun partialRestore(row: ToolkitActionRecord): PartialRestore? {
        if (row.restoresId == null || !landedElsewhere(row)) return null
        val action = intendedAction(row) ?: return null
        if (isMalformed(action)) return null
        return when (action) {
            is ToolkitAction.SetAppOp -> PartialRestore(originalState(row.kind, action.mode.argv))
            is ToolkitAction.SetBucket -> PartialRestore(action.bucket.rawValue.toString())
            is ToolkitAction.RestoreDoze -> PartialRestore(norm(action.rawOrNull))
            else -> null
        }
    }

    private fun closesChain(row: ToolkitActionRecord): Boolean = row.restoresId != null && partialRestore(row) == null

    /**
     * A completed app-op restore by an older release, which wrote the mode `default` for Restore and Reset all.
     * Android enforces `default` as restricted, so that restore never took effect: while the target still reads
     * `default` back ([isActive]) the change it was meant to undo is still in place. The row starts no new chain
     * for the rows above it ([closesChain] still holds), but it is itself an override whose original is `allow`
     * ([chainOriginal]), so it is listed, restorable and repaired by Reset all. Any other read-back is not ours.
     */
    private fun legacyDefaultRestore(row: ToolkitActionRecord): Boolean =
        row.kind == KIND_APPOP &&
            row.status == ToolkitActionStatus.DONE &&
            closesChain(row) &&
            row.param == AppOpMode.DEFAULT.argv &&
            norm(row.afterRaw) == AppOpMode.DEFAULT.argv

    /** [closesChain] for the row itself: nothing it left can still be an override (see [legacyDefaultRestore]). */
    private fun endsOwnChain(row: ToolkitActionRecord): Boolean = closesChain(row) && !legacyDefaultRestore(row)

    /** See [ToolkitAuditLog.sameState]; [restoreAction] still requires [modeOf]. */
    private fun sameState(
        kind: String,
        current: String?,
        original: String?,
    ): Boolean = ToolkitAuditLog.sameState(kind, current, original)

    // ---- active overrides ------------------------------------------------------------------

    /**
     * Override-kind rows that changed their target ([changedTarget]), newest first. A RESTORED row is a
     * change that did happen and was undone by a newer restore row, so it still belongs to its chain;
     * dropping it would break the chain and make [chainOriginal] stop at an intermediate value.
     */
    private suspend fun overrideRows(): List<ToolkitActionRecord> = overrideRows(audit.recent(MAX_ROWS))

    private fun overrideRows(all: List<ToolkitActionRecord>): List<ToolkitActionRecord> =
        all
            .filter { it.kind in OVERRIDE_KINDS && changedTarget(it) }
            .sortedByDescending { it.id }

    /** See [ToolkitAuditLog.changedTarget], which the store's retention SQL mirrors. */
    private fun changedTarget(row: ToolkitActionRecord): Boolean = ToolkitAuditLog.changedTarget(row)

    /** See [ToolkitAuditLog.landedElsewhere]. */
    private fun landedElsewhere(row: ToolkitActionRecord): Boolean = ToolkitAuditLog.landedElsewhere(row)

    private suspend fun computeOverrides(): List<Override> = computeOverrides(overrideRows(), unsettledRows())

    /** Override rows still PENDING: called after a reconcile, these are the ones whose read-back failed. */
    private suspend fun unsettledRows(): List<ToolkitActionRecord> = audit.pending().filter { it.kind in OVERRIDE_KINDS }

    /** [rows] with [row] in its place (newest first), for a [chainOriginal] walk from a row that is not one of them. */
    private fun chainWith(
        row: ToolkitActionRecord,
        rows: List<ToolkitActionRecord>,
    ): List<ToolkitActionRecord> = if (rows.any { it.id == row.id }) rows else (rows + row).sortedByDescending { it.id }

    private suspend fun computeOverrides(
        rows: List<ToolkitActionRecord>,
        unsettled: List<ToolkitActionRecord>,
    ): List<Override> {
        // The newest write that changed each target decides; only a completed restore (or a RESTORED newest
        // row) closes the chain. A partial restore still owns its observed after while it differs from original,
        // and a legacy `default` restore still owns a `default` read-back (see legacyDefaultRestore). An unsettled
        // write on top of a target decides for it instead (see unsettledOverrides).
        val uncertain = unsettledOverrides(unsettled, rows)
        val newest = LinkedHashMap<String, ToolkitActionRecord>()
        for (row in rows) newest.putIfAbsent(targetKey(row), row)
        val candidates =
            newest.values.filter {
                targetKey(it) !in uncertain &&
                    !endsOwnChain(it) &&
                    it.status != ToolkitActionStatus.RESTORED &&
                    mayBeActive(it, rows)
            }
        val result = mutableListOf<Override>()
        result += uncertain.values
        for ((pkg, group) in candidates.filter { it.kind != KIND_DOZE }.groupBy { it.pkg }) {
            val name = pkg ?: continue
            val ops = if (group.any { it.kind == KIND_APPOP }) readAppOps(name) else null
            val bucket = if (group.any { it.kind == KIND_BUCKET }) readBucket(name) else null
            val opsRead = (ops as? ToolkitOutcome.Done)?.value
            val bucketRead = (bucket as? ToolkitOutcome.Done)?.value
            for (row in group) {
                val current =
                    when (row.kind) {
                        KIND_APPOP -> opsRead?.modes?.get(AppOp.entries.firstOrNull { it.argv == row.op })
                        else -> bucketRead?.raw?.toString()
                    }
                val override =
                    if (current != null) {
                        activeOverride(row, current, rows)
                    } else {
                        unverifiedOverride(row, if (row.kind == KIND_APPOP) ops else bucket, rows)
                    }
                val protectedApp = (if (row.kind == KIND_APPOP) opsRead?.protection else bucketRead?.protection) != null
                override?.let { result += if (protectedApp && !undoLifts(it)) it.copy(restorable = false) else it }
            }
        }
        candidates.firstOrNull { it.kind == KIND_DOZE }?.let { row ->
            val doze = readDoze()
            val override =
                if (doze is ToolkitOutcome.Done) {
                    activeOverride(
                        row,
                        doze.value.raw,
                        rows,
                    )
                } else {
                    unverifiedOverride(row, doze, rows)
                }
            override?.let { result += it }
        }
        return result.sortedByDescending { it.sinceMs }
    }

    /**
     * [row] could not be read back now ([read] is why). Its change may well still be in effect (a role or an
     * accessibility service does not undo an app-op, and a failed read changes nothing), so it stays listed, and in
     * Reset all's scope, as unverified with the value it left; dropping it would show "no active changes" over a
     * restriction that is still on the device. Only a package the service says is gone ([GONE_REFUSALS]) is dropped.
     */
    private fun unverifiedOverride(
        row: ToolkitActionRecord,
        read: ToolkitOutcome<*>?,
        rows: List<ToolkitActionRecord>,
    ): Override? {
        if (read is ToolkitOutcome.Refused && read.code in GONE_REFUSALS) return null
        val reason =
            when (read) {
                is ToolkitOutcome.Refused -> read.reason
                is ToolkitOutcome.Unavailable -> ToolkitRefusal.SERVICE_UNAVAILABLE
                else -> ToolkitRefusal.READ_FAILED
            }
        return overrideOf(row, row.afterRaw, chainOriginal(row, rows)).copy(unverified = reason)
    }

    /**
     * The newest PENDING row of each target that the reconcile before this could not settle: its read-back failed
     * or was inconclusive, so its write may well have committed. One that is a change of its own (not a restore)
     * and would leave its target off the chain original is listed as unverified, with the value it was to write,
     * in place of the settled rows of that target: dropping it would show "no active changes" over a change that is
     * probably on the device. A pending restore, or one that would put the original back, leaves the change below it
     * listed as before. Keyed by [targetKey].
     */
    private fun unsettledOverrides(
        unsettled: List<ToolkitActionRecord>,
        rows: List<ToolkitActionRecord>,
    ): Map<String, Override> {
        val result = LinkedHashMap<String, Override>()
        val seen = HashSet<String>()
        for (row in unsettled.sortedByDescending { it.id }) {
            val key = targetKey(row)
            if (!seen.add(key) || row.restoresId != null) continue
            // run() writes nothing over a PENDING target, so no settled row is newer; if one were, it would decide.
            if (rows.any { targetKey(it) == key && it.id > row.id }) continue
            val intended =
                when (val action = intendedAction(row)) {
                    is ToolkitAction.SetAppOp -> action.mode.argv
                    is ToolkitAction.SetBucket -> action.bucket.rawValue.toString()
                    is ToolkitAction.ApplyDoze -> action.profile.constants
                    else -> continue
                }
            val original = chainOriginal(row, chainWith(row, rows))
            if (sameState(row.kind, intended, original)) continue
            result[key] = overrideOf(row, intended, original).copy(unverified = ToolkitRefusal.READ_FAILED)
        }
        return result
    }

    /**
     * False when no read-back can make [row] an override, so it is not read at all: [isActive] needs the current
     * value to be its `after`, and [activeOverride] then needs that to differ from the chain original. A closed
     * chain (a toggle back by hand, say) or an unset doze value over an unset original fails one of them whatever
     * the device says. An unset doze value over a custom original (custom, Balanced, Standard) can be active.
     */
    private fun mayBeActive(
        row: ToolkitActionRecord,
        rows: List<ToolkitActionRecord>,
    ): Boolean = !sameState(row.kind, row.afterRaw, chainOriginal(row, rows))

    /** The override [row] stands for, or null when it is not in effect or its chain has come back to the original. */
    private fun activeOverride(
        row: ToolkitActionRecord,
        current: String?,
        rows: List<ToolkitActionRecord>,
    ): Override? {
        if (!isActive(row, current)) return null
        val original = chainOriginal(row, rows)
        return if (sameState(row.kind, current, original)) null else overrideOf(row, current, original)
    }

    private fun targetKey(row: ToolkitActionRecord): String = targetKey(row.kind, row.pkg, row.op)

    private fun targetKey(spec: Spec): String = targetKey(spec.kind, spec.pkg, spec.op)

    private fun targetKey(
        kind: String,
        pkg: String?,
        op: String,
    ): String = if (kind == KIND_DOZE) KIND_DOZE else "$kind|$pkg|${if (kind == KIND_APPOP) op else ""}"

    /**
     * Still ours when the value read back equals what [row] left; for doze that includes "unset" (a DONE doze
     * row's null `after` is a read-back of unset, and only [changedTarget] rows get here). Whether that differs
     * from what was there is the chain's question, not this row's: a write of the value already in place
     * (`before` == `after`) repeats the override below it, so [activeOverride] compares with [chainOriginal]
     * instead of with this row's own `before`. An unset value over an unset original is therefore no override.
     */
    private fun isActive(
        row: ToolkitActionRecord,
        current: String?,
    ): Boolean = norm(current) == norm(row.afterRaw)

    private fun overrideOf(
        row: ToolkitActionRecord,
        current: String?,
        original: String?,
    ): Override =
        Override(
            actionId = row.id,
            kind =
                when (row.kind) {
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

    /**
     * Whether undoing [override] (writing its chain original over the value read now) only lifts a restriction
     * ([RestrictionLift]), the one write a protected app takes (N-06). A lift of ours made before the app became
     * protected fails this: its undo would restrict a protected app again, so it is listed but not restorable while
     * the app stays protected, and its restore and Reset all are refused as PROTECTED_APP without a write.
     */
    private fun undoLifts(override: Override): Boolean =
        when (override.kind) {
            OverrideKind.APP_OP -> {
                override.previousRaw?.let { modeOf(it) }?.let { RestrictionLift.appOp(it) } == true
            }

            OverrideKind.BUCKET -> {
                val target = StandbyBucket.entries.firstOrNull { it.rawValue.toString() == override.previousRaw }
                val current = override.currentRaw?.toIntOrNull()
                target != null && current != null && RestrictionLift.bucket(target, current)
            }

            OverrideKind.DOZE -> {
                true
            }
        }

    /** Whether [restoreAction] could turn the chain original [original] back into an action. */
    private fun isRestorable(
        row: ToolkitActionRecord,
        original: String?,
    ): Boolean =
        when (row.kind) {
            KIND_APPOP -> original?.let { modeOf(it) } != null
            KIND_BUCKET -> StandbyBucket.entries.any { it.rawValue.toString() == original }
            else -> true
        }

    // ---- retention -------------------------------------------------------------------------

    /**
     * Bounds the log without losing a chain. The store keeps the newest [ToolkitAuditLog.RETAINED_ROWS] rows
     * and every override-chain link; this drops the links older than those that no walk of [chainOriginal]
     * can still reach in a way that changes its answer (see [closedPrefix]). A target with a PENDING row is
     * left alone: settling it may join it to the links below.
     */
    private suspend fun pruneClosedChains() {
        val all = audit.recent(MAX_ROWS)
        if (all.size <= ToolkitAuditLog.RETAINED_ROWS) return
        val oldestRetained = all[ToolkitAuditLog.RETAINED_ROWS - 1].id
        val unsettled = all.filter { it.status == ToolkitActionStatus.PENDING }.map { targetKey(it) }.toSet()
        val ids =
            overrideRows(all)
                .filter { targetKey(it) !in unsettled }
                .groupBy { targetKey(it) }
                .values
                .flatMap { links -> closedPrefix(links.reversed(), oldestRetained) }
                .map { it.id }
        if (ids.isNotEmpty()) audit.delete(ids)
    }

    /**
     * The oldest links of one target ([links], oldest first) that may go: a prefix that lies wholly before
     * [keepFrom] and ends where the chain is cut (the next link does not [continue][continues] it) or closed
     * (its `after` is its chain original again). A walk from a newer row then stops at the same place, or at
     * a `before` that is the same state as the original it would have found. The whole list goes only when
     * its newest link is a completed restore or closes its chain, so a later write cannot continue an open chain.
     */
    private fun closedPrefix(
        links: List<ToolkitActionRecord>,
        keepFrom: Long,
    ): List<ToolkitActionRecord> {
        var cut = 0
        var original: String? = null
        for ((i, link) in links.withIndex()) {
            val partial = partialRestore(link)
            original =
                when {
                    partial != null -> partial.original
                    i > 0 && continues(links[i - 1], link) -> original
                    else -> link.beforeRaw
                }
            if (link.id >= keepFrom) break
            val next = links.getOrNull(i + 1)
            val closed = sameState(link.kind, link.afterRaw, originalState(link.kind, original))
            val endsHere = if (next == null) endsOwnChain(link) || closed else !continues(link, next) || closed
            if (endsHere) cut = i + 1
        }
        return links.take(cut)
    }

    /** Whether a [chainOriginal] walk from [newer] goes on to [older], the link just below it. */
    private fun continues(
        older: ToolkitActionRecord,
        newer: ToolkitActionRecord,
    ): Boolean = !closesChain(older) && !closesChain(newer) && sameState(older.kind, older.afterRaw, newer.beforeRaw)

    // ---- reconcile -------------------------------------------------------------------------

    private sealed interface Verdict {
        data object Unreachable : Verdict

        data object Unverifiable : Verdict

        class Matched(
            val current: String?,
        ) : Verdict

        class Mismatch(
            val current: String?,
        ) : Verdict
    }

    /**
     * Settles the PENDING override rows of the target [key] (every target when null) before a chain is read
     * or a new change is written on top of it. An uncertain write that did commit must join its chain first:
     * otherwise a later change would take that row's `after` for the original, and Reset all would re-apply
     * a restriction the user removed. Rows that cannot be read back yet stay PENDING.
     */
    private suspend fun reconcileTargets(key: String?) {
        val pending = audit.pending()
        forgetSettled(pending)
        val rows = pending.filter { it.kind in OVERRIDE_KINDS && (key == null || targetKey(it) == key) }
        var settled = false
        for (row in rows) settled = reconcileRow(row) || settled
        // Only a real settlement bumps: activeOverrides re-runs on every bump, so an unreachable row must not.
        if (settled) changes.value += 1
    }

    /** Keeps [pendingSince] to the rows still PENDING ([pending] is every one of them); only those are reconciled. */
    private fun forgetSettled(pending: List<ToolkitActionRecord>) {
        if (pendingSince.isEmpty()) return
        val ids = pending.mapTo(HashSet()) { it.id }
        pendingSince.keys.retainAll(ids)
    }

    /**
     * Returns false when the row could not be read back and stays PENDING. A value read back is credited to the
     * write (DONE, or FAILED with that `after`, which keeps a clamped write in its chain) only when [promptReadBack]
     * holds. Otherwise the row is settled as unverified with no `after`, as a write known never to have been sent is
     * (N-07): the write may never have run, so the value is not ours, and Reset all and Restore must not write over it.
     */
    private suspend fun reconcileRow(row: ToolkitActionRecord): Boolean {
        val now = nowMs()
        val action = intendedAction(row)
        val verdict = if (action == null) Verdict.Mismatch(null) else verify(action)
        when (verdict) {
            Verdict.Unreachable -> {
                return false
            }

            Verdict.Unverifiable -> {
                audit.update(row.copy(status = ToolkitActionStatus.FAILED, endedAt = now, code = ToolkitClientCode.UNVERIFIED))
            }

            is Verdict.Matched -> {
                if (promptReadBack(row, now)) {
                    audit.update(
                        row.copy(status = ToolkitActionStatus.DONE, endedAt = now, code = ToolkitCode.OK, afterRaw = verdict.current),
                    )
                    val original = row.restoresId?.let { id -> audit.recent(MAX_ROWS).firstOrNull { it.id == id } }
                    if (original != null && (original.status == ToolkitActionStatus.DONE || landedElsewhere(original))) {
                        audit.update(original.copy(status = ToolkitActionStatus.RESTORED))
                    }
                } else {
                    audit.update(unattributed(row, now))
                }
            }

            is Verdict.Mismatch -> {
                if (promptReadBack(row, now)) {
                    audit.update(
                        row.copy(
                            status = ToolkitActionStatus.FAILED,
                            endedAt = now,
                            code = ToolkitCode.NOT_EFFECTIVE,
                            afterRaw = verdict.current,
                        ),
                    )
                } else {
                    audit.update(unattributed(row, now))
                }
            }
        }
        return true
    }

    /** [row] settled without a read-back of its own: FAILED, unverified, no `after`, so it is no chain link. */
    private fun unattributed(
        row: ToolkitActionRecord,
        now: Long,
    ): ToolkitActionRecord =
        row.copy(status = ToolkitActionStatus.FAILED, endedAt = now, code = ToolkitClientCode.UNVERIFIED, afterRaw = null)

    /**
     * Whether a read-back made at [now] (wall clock) is [row]'s own outcome (B-01). Right after the write it is: what
     * the write left, clamped or not. Later it may be anything the platform, the user or another tool has set since,
     * and a write that never ran looks the same, so only a read-back within [RECONCILE_ATTRIBUTION_MS] of the write
     * counts. The row stores only the wall clock, which can be set at any time, so the check fails safe: a negative
     * wall age or one past the window is late. A row this process inserted is also timed on [elapsedMs] since its
     * insert ([pendingSince]), so a wall clock set back over a long outage cannot pass it. A row an earlier process
     * inserted (one killed mid-write) has only its wall age, which must also fit in the time since boot: a row of an
     * earlier boot is late. [elapsedMs] is elapsedRealtime in the app; a clock that counts less (one that stops in deep
     * sleep) only makes more rows late.
     */
    private fun promptReadBack(
        row: ToolkitActionRecord,
        now: Long,
    ): Boolean {
        val age = now - row.startedAt
        if (age !in 0..RECONCILE_ATTRIBUTION_MS) return false
        val sinceBoot = elapsedMs()
        val insertedAt = pendingSince[row.id] ?: return age <= sinceBoot
        return sinceBoot - insertedAt in 0..RECONCILE_ATTRIBUTION_MS
    }

    private suspend fun verify(action: ToolkitAction): Verdict =
        when (action) {
            is ToolkitAction.SetAppOp -> {
                val read = readAppOps(action.pkg)
                when (read) {
                    is ToolkitOutcome.Done -> {
                        read.value.modes[action.op].let { current ->
                            if (current != null && current == action.mode.argv) Verdict.Matched(current) else Verdict.Mismatch(current)
                        }
                    }

                    is ToolkitOutcome.Refused -> {
                        refusedVerdict(read)
                    }

                    else -> {
                        Verdict.Unreachable
                    }
                }
            }

            is ToolkitAction.SetBucket -> {
                when (val read = readBucket(action.pkg)) {
                    is ToolkitOutcome.Done -> {
                        read.value.raw?.toString().let { current ->
                            if (current == action.bucket.rawValue.toString()) Verdict.Matched(current) else Verdict.Mismatch(current)
                        }
                    }

                    is ToolkitOutcome.Refused -> {
                        refusedVerdict(read)
                    }

                    else -> {
                        Verdict.Unreachable
                    }
                }
            }

            is ToolkitAction.ApplyDoze -> {
                verifyDoze(norm(action.profile.constants))
            }

            is ToolkitAction.RestoreDoze -> {
                verifyDoze(norm(action.rawOrNull))
            }

            is ToolkitAction.ForceStop -> {
                Verdict.Unverifiable
            }
        }

    /**
     * Only a refusal that says the target can never be read back again settles a row as a mismatch. A read the
     * service could not parse, a protected app whose read failed, an undecidable protection check, a caller or
     * stale refusal are inconclusive: the write may have committed, so the row stays PENDING rather than leaving
     * its chain as FAILED.
     */
    private fun refusedVerdict(read: ToolkitOutcome.Refused): Verdict =
        if (read.code in CONCLUSIVE_REFUSALS) Verdict.Mismatch(null) else Verdict.Unreachable

    private suspend fun verifyDoze(expected: String?): Verdict =
        when (val read = readDoze()) {
            is ToolkitOutcome.Done -> {
                if (norm(read.value.raw) == expected) Verdict.Matched(read.value.raw) else Verdict.Mismatch(read.value.raw)
            }

            else -> {
                Verdict.Unreachable
            }
        }

    // ---- dexopt status ---------------------------------------------------------------------

    /** Reads the job status, settles any PENDING dexopt row from it, and keeps the binding hold in step. */
    private suspend fun refreshDexoptLocked(): BgDexoptStatus {
        val pending = audit.pending().filter { it.kind == KIND_DEXOPT }
        val reply = backend.bgDexoptStatus()
        val service =
            when (reply) {
                // Cannot tell: keep whatever hold exists and do not touch the rows.
                is GatewayReply.Unavailable -> return if (pending.isEmpty() &&
                    dexoptStartsInFlight.isEmpty()
                ) {
                    BgDexoptStatus.Idle
                } else {
                    BgDexoptStatus.Unknown
                }

                is GatewayReply.Reply -> reply.value
            }
        // A freshly created service reports UNKNOWN because it cannot see a job an earlier instance left behind.
        // With no PENDING dexopt row of ours there is nothing we started that could still be running, so the
        // card is idle; UNKNOWN stays for a job we did start and cannot confirm.
        // A terminal status can still describe the previous job while a start waits in the plugged probe.
        // Keep its durable PENDING row and cancellation action until that admission has actually returned.
        // Unsupported is permanent for this platform: a start in flight will be refused, and an old row is settled
        // only once it is past the cap.
        val observed = statusOf(service)
        val status =
            when {
                observed == BgDexoptStatus.Unsupported -> observed
                dexoptStartsInFlight.isNotEmpty() && observed !is BgDexoptStatus.Running -> BgDexoptStatus.Unknown
                service.state == BgDexoptState.UNKNOWN && pending.isEmpty() -> BgDexoptStatus.Idle
                else -> observed
            }
        syncHold(running = status is BgDexoptStatus.Running, startedAtMs = (status as? BgDexoptStatus.Running)?.startedAtMs ?: 0L)
        if (pending.isNotEmpty()) {
            val settled =
                when (status) {
                    is BgDexoptStatus.Finished -> {
                        finalizeDexoptRows(
                            if (status.exitCode == 0) ToolkitActionStatus.DONE else ToolkitActionStatus.FAILED,
                            status.exitCode,
                            afterRaw = "finished",
                            now = status.endedAtMs.takeIf { it > 0 },
                        )
                    }

                    is BgDexoptStatus.Cancelled -> {
                        finalizeDexoptRows(
                            ToolkitActionStatus.CANCELLED,
                            service.reason,
                            afterRaw = "cancelled",
                            now = status.endedAtMs.takeIf { it > 0 },
                        )
                    }

                    // Proven nothing is running (a completed cancel, or a job that no longer exists).
                    BgDexoptStatus.Idle -> {
                        finalizeDexoptRows(ToolkitActionStatus.CANCELLED, BgDexoptReason.NONE, afterRaw = "idle")
                    }

                    is BgDexoptStatus.Running, BgDexoptStatus.Unknown -> {
                        0
                    }

                    // An older service version started it, and nothing here can stop or read it. Settle it once
                    // even its cap and wind-down have passed, so it no longer offers a Cancel that is always refused.
                    BgDexoptStatus.Unsupported -> {
                        val stale = nowMs() - DEXOPT_CAP_MS - DEXOPT_CAP_MARGIN_MS
                        val rows = pending.filter { it.startedAt <= stale && it.id !in dexoptStartsInFlight }
                        rows.forEach {
                            audit.update(
                                it.copy(status = ToolkitActionStatus.CANCELLED, endedAt = nowMs(), afterRaw = "unsupported"),
                            )
                        }
                        rows.size
                    }
                }
            // Only a settled row is a change: a bump re-runs the privileged override recompute, and this runs on
            // every RUNNING poll.
            if (settled > 0) changes.value += 1
        }
        return status
    }

    private fun statusOf(service: ServiceBgDexoptStatus): BgDexoptStatus =
        when (service.state) {
            BgDexoptState.RUNNING -> BgDexoptStatus.Running(service.startedAtMs)
            BgDexoptState.FINISHED -> BgDexoptStatus.Finished(service.exitCode, service.endedAtMs)
            BgDexoptState.CANCELLED -> BgDexoptStatus.Cancelled(cancelReasonOf(service.reason), service.endedAtMs)
            BgDexoptState.IDLE -> BgDexoptStatus.Idle
            else -> if (service.reason == BgDexoptReason.UNSUPPORTED_API) BgDexoptStatus.Unsupported else BgDexoptStatus.Unknown
        }

    private fun cancelReasonOf(reason: Int): BgDexoptCancelReason =
        when (reason) {
            BgDexoptReason.USER -> BgDexoptCancelReason.USER
            BgDexoptReason.UNPLUGGED -> BgDexoptCancelReason.UNPLUGGED
            BgDexoptReason.TIMEOUT -> BgDexoptCancelReason.TIMEOUT
            else -> BgDexoptCancelReason.OTHER
        }

    /** Returns the number of rows settled. */
    private suspend fun finalizeDexoptRows(
        status: ToolkitActionStatus,
        code: Int,
        afterRaw: String,
        now: Long? = null,
    ): Int {
        val ended = now ?: nowMs()
        val rows = audit.pending().filter { it.kind == KIND_DEXOPT && it.id !in dexoptStartsInFlight }
        for (row in rows) {
            audit.update(row.copy(status = status, endedAt = ended, code = code, afterRaw = afterRaw))
        }
        return rows.size
    }

    private suspend fun supersedeOtherDexoptRows(keepId: Long) {
        val ended = nowMs()
        for (row in audit.pending().filter { it.kind == KIND_DEXOPT && it.id != keepId && it.id !in dexoptStartsInFlight }) {
            audit.update(row.copy(status = ToolkitActionStatus.CANCELLED, endedAt = ended, afterRaw = "superseded"))
        }
    }

    /**
     * Keeps the binding hold in step with the job. Taking the hold also starts [superviseHold], which ends it
     * on any terminal status or at the cap, so the hold cannot outlive the job when nothing is collecting.
     * [startedAtMs] is the service's wall-clock job start when known (0 = just started, use now). The deadline
     * runs on [elapsedMs]: the wall-clock start is turned into the time left once, here, kept within one cap.
     */
    private fun syncHold(
        running: Boolean,
        startedAtMs: Long = 0L,
    ) {
        synchronized(holdLock) {
            if (running) {
                if (dexoptHold == null) {
                    dexoptHold = holdBinding()
                    val capMs = DEXOPT_CAP_MS + DEXOPT_CAP_MARGIN_MS
                    val leftMs = if (startedAtMs > 0) (startedAtMs + capMs - nowMs()).coerceIn(0L, capMs) else capMs
                    val deadline = elapsedMs() + leftMs
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
     * at [deadlineMs] on [elapsedMs] (the service's own 60-minute cap plus a margin) the hold is dropped even if
     * the service cannot be asked, leaving the rows PENDING for the next status read or [reconcile].
     */
    private suspend fun superviseHold(deadlineMs: Long) {
        while (true) {
            delay(holdCheckMs)
            if (elapsedMs() >= deadlineMs) {
                syncHold(running = false)
                return
            }
            // A terminal status makes the refresh release the hold, which cancels this very job; the row
            // updates that follow in the same refresh must still complete. A failed audit query must not
            // end supervision: retry at the next tick, keeping the monotonic hold cap independent of reads.
            try {
                withContext(NonCancellable) { mutex.withLock { refreshDexoptLocked() } }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                android.util.Log.w("ToolkitActions", "hold status read failed; will retry within cap", error)
            }
            if (!holdTaken()) return
        }
    }

    // ---- read-back plumbing ----------------------------------------------------------------

    /**
     * [protection] is set for a protected app, or one whose protection the service could not decide: the modes are
     * still a real reading, but only the undo of a change of ours that lifts a restriction ([RestrictionLift]) may be
     * written (see [prepare]). [updatedSystem] holds for such a reading too ([ToolkitResult.warning]).
     */
    private class AppOpsRead(
        val modes: Map<AppOp, String>,
        val updatedSystem: Boolean,
        val protection: ToolkitOutcome.Refused? = null,
    )

    /** [protection]: as in [AppOpsRead]. */
    private class BucketRead(
        val raw: Int?,
        val updatedSystem: Boolean,
        val protection: ToolkitOutcome.Refused? = null,
    )

    private class PackageRead(
        val result: ToolkitResult,
        val protection: ToolkitOutcome.Refused?,
    )

    /**
     * A package read. The service refuses a protected (or undecidable) package with [PROTECTION_CODES] but still
     * reports the value it read, so a change of ours stays visible and restorable; that reading is Done here with
     * its [PackageRead.protection]. A refusal without a value (the read failed too, or an older service) is not.
     */
    private fun packageRead(reply: GatewayReply<ToolkitResult>): ToolkitOutcome<PackageRead> {
        val value = (reply as? GatewayReply.Reply)?.value
        if (value != null && value.status == ToolkitStatus.REFUSED && value.code in PROTECTION_CODES && value.afterRaw != null) {
            return ToolkitOutcome.Done(PackageRead(value, ToolkitOutcome.Refused(refusalFor(value.code), value.code)))
        }
        return when (val outcome = readOutcome(reply)) {
            is ToolkitOutcome.Done -> ToolkitOutcome.Done(PackageRead(outcome.value, null))
            else -> failure(outcome)
        }
    }

    private suspend fun readAppOps(pkg: String): ToolkitOutcome<AppOpsRead> {
        val read = packageRead(backend.getAppOps(pkg))
        if (read !is ToolkitOutcome.Done) return failure(read)
        val result = read.value.result
        val modes = parseAppOps(result.afterRaw) ?: return ToolkitOutcome.Failed(ToolkitCode.PARSE)
        return ToolkitOutcome.Done(AppOpsRead(modes, result.requiresWarning, read.value.protection))
    }

    private suspend fun readBucket(pkg: String): ToolkitOutcome<BucketRead> {
        val read = packageRead(backend.getStandbyBucket(pkg))
        if (read !is ToolkitOutcome.Done) return failure(read)
        val result = read.value.result
        val raw =
            result.afterRaw
                ?.trim()
                ?.toIntOrNull() ?: return ToolkitOutcome.Failed(ToolkitCode.PARSE)
        return ToolkitOutcome.Done(BucketRead(raw, result.requiresWarning, read.value.protection))
    }

    private suspend fun readDoze(): ToolkitOutcome<DozeState> {
        val result = readOutcome(backend.getDozeState())
        if (result !is ToolkitOutcome.Done) return failure(result)
        val value = result.value
        val profile = if (value.code == ToolkitCode.DOZE_CUSTOM) null else DozeProfile.fromCode(value.code)
        if (profile == null && value.code != ToolkitCode.DOZE_CUSTOM) return ToolkitOutcome.Failed(ToolkitCode.PARSE)
        val effective =
            value.message
                .orEmpty()
                .split(',')
                .mapNotNull { pair ->
                    val ms = pair.substringAfter('=', "").toLongOrNull()
                    if (ms == null) null else pair.substringBefore('=') to ms
                }.toMap()
        return ToolkitOutcome.Done(DozeState(norm(value.afterRaw), profile, effective))
    }

    private fun readOutcome(reply: GatewayReply<ToolkitResult>): ToolkitOutcome<ToolkitResult> =
        when (reply) {
            is GatewayReply.Unavailable -> {
                ToolkitOutcome.Unavailable(unavailableFor(reply.reason))
            }

            is GatewayReply.Reply -> {
                when (reply.value.status) {
                    ToolkitStatus.DONE -> {
                        ToolkitOutcome.Done(reply.value)
                    }

                    ToolkitStatus.REFUSED -> {
                        ToolkitOutcome.Refused(refusalFor(reply.value.code), reply.value.code)
                    }

                    ToolkitStatus.UNAVAILABLE -> {
                        // UNVERIFIED: issued, outcome unknown (a timed-out dexopt cancel); reads never answer it.
                        val unverified = reply.value.code == ToolkitCode.UNVERIFIED
                        val why = if (unverified) ToolkitUnavailable.UNVERIFIED else ToolkitUnavailable.NOT_EFFECTIVE
                        ToolkitOutcome.Unavailable(why, reply.value.code)
                    }

                    else -> {
                        ToolkitOutcome.Failed(reply.value.code)
                    }
                }
            }
        }

    /** Re-types a non-Done outcome. A Done never reaches here. */
    private fun failure(outcome: ToolkitOutcome<*>): ToolkitOutcome<Nothing> =
        when (outcome) {
            is ToolkitOutcome.Refused -> outcome
            is ToolkitOutcome.Unavailable -> outcome
            is ToolkitOutcome.Failed -> outcome
            is ToolkitOutcome.Done -> ToolkitOutcome.Failed(null)
        }

    private fun unavailableFor(reason: GatewayUnavailable): ToolkitUnavailable =
        when (reason) {
            GatewayUnavailable.NO_SERVICE -> ToolkitUnavailable.NO_SERVICE
            GatewayUnavailable.STALE_SERVICE -> ToolkitUnavailable.STALE_SERVICE
        }

    private fun refusalFor(code: Int): ToolkitRefusal =
        when (code) {
            ToolkitCode.CALLER -> ToolkitRefusal.CALLER

            ToolkitCode.ARG_PACKAGE, ToolkitCode.ARG_VALUE -> ToolkitRefusal.INVALID_ARGUMENT

            ToolkitCode.NOT_INSTALLED -> ToolkitRefusal.NOT_INSTALLED

            ToolkitCode.UNSUPPORTED_USER -> ToolkitRefusal.UNSUPPORTED_USER

            ToolkitCode.UNSUPPORTED_API -> ToolkitRefusal.UNSUPPORTED_API

            ToolkitCode.DENY_APP_ID,
            ToolkitCode.DENY_PERSISTENT,
            ToolkitCode.DENY_SYSTEM,
            ToolkitCode.DENY_PROTECTED,
            ToolkitCode.DENY_OWN,
            -> ToolkitRefusal.PROTECTED_APP

            ToolkitCode.PROTECTION_UNKNOWN -> ToolkitRefusal.PROTECTION_UNKNOWN

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

        /**
         * How long after a write a read-back is still that write's own outcome ([promptReadBack]). A lost reply is
         * read back on the next toolkit use or Ready: the write waits up to the gateway's bind deadline (5 s,
         * `ToolkitGateway.DEFAULT_BIND_TIMEOUT_MS`) and its command up to its timeout (10 s plus 1 s to join the
         * reader, `CommandRunner`), and the read-back again as much, about 32 s in all. The rest is slack for the
         * service's own checks before the write, which normally take milliseconds. Platform drift, and an outage that
         * waits for the user to restart Shizuku, take far longer.
         */
        const val RECONCILE_ATTRIBUTION_MS = 60_000L

        /** The hold supervisor outlives every screen: a failure in it (a Room error settling a dexopt row) is logged, never a crash. */
        fun defaultSupervisorScope(): CoroutineScope =
            CoroutineScope(
                SupervisorJob() + Dispatchers.Default +
                    kotlinx.coroutines.CoroutineExceptionHandler {
                        _,
                        error,
                        ->
                        android.util.Log.w("ToolkitActions", "uncaught in the hold supervisor", error)
                    },
            )

        // Recovery must not truncate the retained original/restore chain. history(limit) stays bounded.
        const val MAX_ROWS = Int.MAX_VALUE

        const val KIND_APPOP = ToolkitAuditLog.KIND_APP_OP
        const val KIND_BUCKET = ToolkitAuditLog.KIND_BUCKET
        const val KIND_DOZE = ToolkitAuditLog.KIND_DOZE
        const val KIND_FORCE_STOP = ToolkitAuditLog.KIND_FORCE_STOP
        const val KIND_DEXOPT = ToolkitAuditLog.KIND_BG_DEXOPT

        const val OP_BUCKET = "standby_bucket"
        const val OP_FORCE_STOP = "force_stop"
        const val OP_APPLY_PROFILE = "apply_profile"
        const val OP_RESTORE_RAW = "restore_raw"
        const val OP_BG_DEXOPT = "bg_dexopt"

        val OVERRIDE_KINDS = ToolkitAuditLog.OVERRIDE_KINDS

        /**
         * Read refusals that prove the target is gone or off limits for good (see [refusedVerdict]). A protected app
         * is not: the service still reads it ([PROTECTION_CODES]), and a role or service can be taken away again.
         */
        val CONCLUSIVE_REFUSALS =
            setOf(
                ToolkitCode.NOT_INSTALLED,
                ToolkitCode.ARG_PACKAGE,
                ToolkitCode.DENY_APP_ID,
                ToolkitCode.DENY_PERSISTENT,
                ToolkitCode.DENY_SYSTEM,
                ToolkitCode.DENY_OWN,
            )

        /** A package no longer there to read: its override went with it, so it is not listed (see [unverifiedOverride]). */
        val GONE_REFUSALS = setOf(ToolkitCode.NOT_INSTALLED, ToolkitCode.ARG_PACKAGE)

        /** Refusals of a protected (rule d) or undecidable package, whose reads still carry the value (see [packageRead]). */
        val PROTECTION_CODES = setOf(ToolkitCode.DENY_PROTECTED, ToolkitCode.PROTECTION_UNKNOWN)

        /** See [ToolkitAuditLog.norm]. */
        fun norm(raw: String?): String? = ToolkitAuditLog.norm(raw)

        /**
         * The write that puts a recorded original back. `ignore` and `allow` are what this surface sets; a
         * recorded `default` (how older releases stored "No operations.", and what their "allow" wrote) is
         * restored as `allow`, because the stored `default` mode keeps background ops restricted.
         */
        fun modeOf(raw: String): AppOpMode? =
            when (raw) {
                AppOpMode.IGNORE.argv -> AppOpMode.IGNORE
                AppOpMode.ALLOW.argv, AppOpMode.DEFAULT.argv -> AppOpMode.ALLOW
                else -> null
            }

        /** A live reading as it is: `default` stays [AppOpMode.DEFAULT] so the UI can show it as restricted. */
        fun observedModeOf(raw: String): AppOpMode? =
            when (raw) {
                AppOpMode.IGNORE.argv -> AppOpMode.IGNORE
                AppOpMode.ALLOW.argv -> AppOpMode.ALLOW
                AppOpMode.DEFAULT.argv -> AppOpMode.DEFAULT
                else -> null
            }

        fun bucketOf(raw: Int): StandbyBucket? = StandbyBucket.entries.firstOrNull { it.rawValue == raw }
    }
}

/** The default hold-deadline clock: monotonic, so a wall-clock change cannot move it (the app passes elapsedRealtime). */
private fun monotonicMs(): Long = System.nanoTime() / 1_000_000L
