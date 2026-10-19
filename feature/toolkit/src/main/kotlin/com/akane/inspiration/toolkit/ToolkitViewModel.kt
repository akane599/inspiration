package com.akane.inspiration.toolkit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.akane.inspiration.ipc.ShizukuState
import com.akane.inspiration.ipc.toolkit.AppOpMode
import com.akane.inspiration.telemetry.app.AppEntry
import com.akane.inspiration.telemetry.app.attempt
import com.akane.inspiration.telemetry.store.ToolkitActionRecord
import com.akane.inspiration.telemetry.toolkit.BgDexoptStatus
import com.akane.inspiration.telemetry.toolkit.DozeState
import com.akane.inspiration.telemetry.toolkit.Override
import com.akane.inspiration.telemetry.toolkit.ToolkitAction
import com.akane.inspiration.telemetry.toolkit.ToolkitOutcome
import com.akane.inspiration.telemetry.toolkit.ToolkitPlan
import com.akane.inspiration.telemetry.toolkit.ToolkitRefusal
import com.akane.inspiration.telemetry.toolkit.ToolkitWarning
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val HISTORY_LIMIT = 20

/** A toolkit call threw instead of answering (a Room error from the audit log, say): there is no code, only the failure. */
private val CALL_FAILED = ToolkitNotice.Rejected(Problem.Failed(code = null))

/**
 * MVI ViewModel of the Toolkit screen (H5). Everything privileged goes through `deps.toolkit` (plan, confirm,
 * execute, read back); the screen shows what the system reports after each write, never what was asked for.
 *
 * Work follows visibility ([ToolkitIntent.Visible] to [ToolkitIntent.Hidden], H13): the history, the active
 * overrides and the bg-dexopt status are collected only meanwhile. The binding of a running dexopt job is held
 * by `ToolkitActions` itself, so this class never calls `holdBinding()`. The package an action targets is the
 * one of the app opened from the in-app list, and a restore only takes an id of a shown override (H12).
 */
class ToolkitViewModel(
    private val deps: ToolkitDependencies,
) : ViewModel() {
    private val _state = MutableStateFlow(ToolkitState())
    val state: StateFlow<ToolkitState> = _state.asStateFlow()

    private val _effects = Channel<ToolkitEffect>(Channel.BUFFERED)
    val effects: Flow<ToolkitEffect> = _effects.receiveAsFlow()

    private var visibleJob: Job? = null
    private var detailJob: Job? = null
    private var allApps: List<AppItem> = emptyList()

    private data class PreparedRequest(
        val request: ToolkitRequest,
        val action: ToolkitAction?,
        val target: String?,
        val generation: Long,
    )

    private var pending: PreparedRequest? = null
    private var generation = 0L
    private var planning = false

    private fun invalidatePlanning() {
        generation++
        pending = null
        if (planning) {
            planning = false
            _state.update { it.copy(busy = false) }
        }
    }

    /** True between Visible and Hidden. Anything that finishes while hidden is kept off the screen (confirmations, effects). */
    private var visible = false

    fun onIntent(intent: ToolkitIntent) {
        when (intent) {
            ToolkitIntent.Visible -> onVisible()
            ToolkitIntent.Hidden -> onHidden()
            ToolkitIntent.Reload -> reload()
            ToolkitIntent.Grant -> grant()
            is ToolkitIntent.SetQuery -> setQuery(intent.query)
            is ToolkitIntent.OpenApp -> openApp(intent.packageName)
            ToolkitIntent.CloseApp -> closeApp()
            ToolkitIntent.ReloadApp -> loadDetail()
            is ToolkitIntent.Act -> act(intent.request)
            ToolkitIntent.ConfirmPending -> confirmPending()
            ToolkitIntent.DismissConfirmation -> dismissConfirmation()
            ToolkitIntent.DismissResetReport -> _state.update { it.copy(resetReport = null) }
            ToolkitIntent.StartDexopt -> startDexopt()
            ToolkitIntent.CancelDexopt -> cancelDexopt()
            is ToolkitIntent.SetStatusNotification -> deps.setStatusNotificationEnabled(intent.enabled)
            ToolkitIntent.OpenNotificationSettings -> _effects.trySend(ToolkitEffect.OpenNotificationSettings)
        }
    }

    // ---- visibility and access -------------------------------------------------------------

    private fun onVisible() {
        visible = true
        // An effect from a previous visit is stale: nothing replays when the screen comes back.
        while (_effects.tryReceive().isSuccess) Unit
        visibleJob?.cancel()
        // The user may have changed this in system settings while the screen was away.
        val blocked = deps.notificationsBlocked()
        _state.update { it.copy(statusNotification = it.statusNotification.copy(blocked = blocked)) }
        visibleJob =
            viewModelScope.launch {
                launch {
                    deps.statusNotificationEnabled.collect { enabled ->
                        _state.update { it.copy(statusNotification = it.statusNotification.copy(enabled = enabled)) }
                    }
                }
                // The log is local, so it is shown whatever Shizuku says. A log that cannot be read keeps what it showed.
                launch { attempt { deps.toolkit.history(HISTORY_LIMIT).collect { showHistory(it) } } }
                deps.shizukuState.collectLatest { follow(it) }
            }
    }

    private fun onHidden() {
        visible = false
        visibleJob?.cancel()
        detailJob?.cancel()
        // A confirmation must not outlive the screen it was asked on, and one still being planned is dropped
        // when it arrives ([ask]). A write already running finishes.
        invalidatePlanning()
        _state.update { it.copy(confirmation = null) }
    }

    private suspend fun follow(shizuku: ShizukuState) {
        val reason = shizuku.blockedReason()
        if (reason != null) {
            block(reason)
            return
        }
        coroutineScope {
            // A read that throws (a Room error from the audit log) stops only its own card; the rest stays usable.
            launch { attempt { deps.toolkit.activeOverrides().collect { showOverrides(it) } } }
            launch { attempt { deps.toolkit.bgDexoptStatus().collect { status -> setDexopt { it.copy(status = status) } } } }
            load()
        }
    }

    private fun block(reason: BlockedReason) {
        invalidatePlanning()
        detailJob?.cancel()
        allApps = emptyList()
        _state.update {
            it.copy(
                status = ToolkitPhase.BLOCKED,
                blocked = reason,
                doze = DozeSection(),
                apps = AppsSection(),
                overrides = persistentListOf(),
                overridesNotice = null,
                dexopt = DexoptSection(),
                detail = null,
                confirmation = null,
            )
        }
    }

    private fun grant() {
        if (_state.value.blocked?.canGrant == true) deps.requestShizukuPermission()
    }

    private fun reload() {
        if (!visible) return
        val status = _state.value.status
        if (status != ToolkitPhase.FAILED && status != ToolkitPhase.READY) return
        onVisible()
    }

    // ---- reading ---------------------------------------------------------------------------

    private suspend fun load() {
        _state.update {
            if (it.status ==
                ToolkitPhase.READY
            ) {
                it.copy(blocked = null)
            } else {
                it.copy(status = ToolkitPhase.LOADING, blocked = null)
            }
        }
        // A read that throws instead of answering is the same error state, with the same retry.
        val doze = attempt { deps.toolkit.dozeState() }
        if (doze == null || doze is ToolkitOutcome.Unavailable) {
            val problem = doze?.let(ToolkitMapper::problem) ?: Problem.Failed(code = null)
            _state.update { it.copy(status = ToolkitPhase.FAILED, doze = DozeSection(DozeLoad.Unavailable(problem))) }
            return
        }
        showDoze(doze)
        _state.update { it.copy(status = ToolkitPhase.READY) }
        loadApps()
        if (_state.value.detail != null) loadDetail()
    }

    /** A null [outcome] is a read that threw instead of answering. */
    private fun showDoze(outcome: ToolkitOutcome<DozeState>?) {
        val load =
            when (outcome) {
                is ToolkitOutcome.Done -> DozeLoad.Loaded(ToolkitMapper.doze(outcome.value))
                null -> DozeLoad.Unavailable(Problem.Failed(code = null))
                else -> DozeLoad.Unavailable(ToolkitMapper.problem(outcome))
            }
        _state.update { it.copy(doze = it.doze.copy(load = load)) }
    }

    private suspend fun loadApps() {
        val entries =
            try {
                deps.installedApps()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        if (entries == null) {
            _state.update { it.copy(apps = it.apps.copy(phase = AppsPhase.FAILED)) }
            return
        }
        allApps =
            entries
                .map { appItem(it) }
                .sortedWith(
                    compareByDescending<AppItem> { it.restricted }
                        .thenBy(
                            String.CASE_INSENSITIVE_ORDER,
                        ) { it.label }
                        .thenBy { it.packageName },
                )
        _state.update { it.copy(apps = it.apps.copy(phase = AppsPhase.READY)) }
        applyQuery()
    }

    private suspend fun appItem(entry: AppEntry): AppItem =
        AppItem(
            packageName = entry.packageName,
            label = entry.label.ifBlank { deps.appLabels.label(entry.packageName) },
            restricted = entry.restricted,
        )

    private fun setQuery(query: String) {
        _state.update { it.copy(apps = it.apps.copy(query = query)) }
        applyQuery()
    }

    private fun applyQuery() {
        _state.update { current ->
            val needle = current.apps.query.trim()
            val shown =
                if (needle.isEmpty()) {
                    allApps
                } else {
                    allApps.filter { it.label.contains(needle, ignoreCase = true) || it.packageName.contains(needle, ignoreCase = true) }
                }
            current.copy(apps = current.apps.copy(items = shown.toImmutableList(), total = allApps.size))
        }
    }

    private suspend fun showHistory(rows: List<ToolkitActionRecord>) {
        val items = rows.map { row -> ToolkitMapper.history(row, row.pkg?.let { deps.appLabels.label(it) }) }
        _state.update { it.copy(history = items.toImmutableList()) }
    }

    private suspend fun showOverrides(all: List<Override>) {
        val items = all.map { override -> overrideItem(override) }
        _state.update { it.copy(overrides = items.toImmutableList()) }
        val restrictedPackages = all.mapNotNull { it.pkg }.toSet()
        allApps =
            allApps
                .map { it.copy(restricted = it.packageName in restrictedPackages) }
                .sortedWith(
                    compareByDescending<AppItem> { it.restricted }
                        .thenBy(
                            String.CASE_INSENSITIVE_ORDER,
                        ) { it.label }
                        .thenBy { it.packageName },
                )
        applyQuery()
    }

    private suspend fun overrideItem(override: Override): OverrideItem =
        ToolkitMapper.overrideItem(override, override.pkg?.let { deps.appLabels.label(it) })

    // ---- app detail ------------------------------------------------------------------------

    private fun openApp(packageName: String) {
        if (_state.value.status != ToolkitPhase.READY) return
        val app = allApps.firstOrNull { it.packageName == packageName } ?: return
        invalidatePlanning()
        _state.update { it.copy(detail = AppDetail(app = app, controls = ControlsState.Loading), confirmation = null) }
        loadDetail()
    }

    private fun closeApp() {
        detailJob?.cancel()
        invalidatePlanning()
        _state.update { it.copy(detail = null, confirmation = null) }
    }

    /** Reads the open app's settings back from the system (never from what was last asked). */
    private fun loadDetail() {
        val app = _state.value.detail?.app ?: return
        detailJob?.cancel()
        _state.update { current -> current.copy(detail = current.detail?.copy(controls = ControlsState.Loading)) }
        detailJob = viewModelScope.launch { showControls(app.packageName) }
    }

    private suspend fun showControls(pkg: String) {
        val controls =
            when (val outcome = attempt { deps.toolkit.appState(pkg) }) {
                is ToolkitOutcome.Done -> ControlsState.Loaded(ToolkitMapper.controls(outcome.value))

                // A read that threw instead of answering: the controls offer a retry, never a guess.
                null -> ControlsState.Unavailable(Problem.Failed(code = null))

                else -> ControlsState.Unavailable(ToolkitMapper.problem(outcome))
            }
        _state.update { current ->
            val detail = current.detail
            if (detail != null && detail.app.packageName == pkg) current.copy(detail = detail.copy(controls = controls)) else current
        }
    }

    // ---- doze, app and override actions ----------------------------------------------------

    private fun act(request: ToolkitRequest) {
        val current = _state.value
        if (current.status != ToolkitPhase.READY || current.busy || current.confirmation != null) return
        if (!accepts(request, current)) return
        val prepared = PreparedRequest(request, actionOf(request), targetOf(request), generation)
        if (request == ToolkitRequest.ResetAll) {
            // Reset all has no single plan: the user always confirms it, with the number of changes it restores.
            ask(Confirmation(request = request, target = null, warnings = persistentListOf(), count = current.overrides.size), prepared)
            return
        }
        planning = true
        _state.update { it.copy(busy = true) }
        showNotice(request, null)
        viewModelScope.launch {
            val plan = attempt { planOf(prepared) }
            if (prepared.generation != generation) return@launch
            planning = false
            when (plan) {
                null -> {
                    finish(prepared, CALL_FAILED)
                }

                ToolkitPlan.Ready -> {
                    executeContained(prepared, confirmed = false)
                }

                is ToolkitPlan.NeedsConfirmation -> {
                    ask(confirmationOf(prepared, listOf(plan.warning) + plan.others), prepared)
                    _state.update { it.copy(busy = false) }
                }

                is ToolkitPlan.Refused -> {
                    finish(prepared, ToolkitNotice.Rejected(Problem.Refused(plan.reason)))
                }
            }
        }
    }

    /** Only requests the visible screen could have produced are acted on. */
    private fun accepts(
        request: ToolkitRequest,
        state: ToolkitState,
    ): Boolean =
        when (request) {
            is ToolkitRequest.ApplyDoze -> {
                state.doze.load is DozeLoad.Loaded
            }

            is ToolkitRequest.SetAppOp, is ToolkitRequest.SetBucket, ToolkitRequest.ForceStop -> {
                state.detail?.controls is ControlsState.Loaded
            }

            is ToolkitRequest.Restore -> {
                state.overrides.any { it.actionId == request.actionId && it.restorable }
            }

            ToolkitRequest.ResetAll -> {
                state.overrides.isNotEmpty()
            }
        }

    private fun actionOf(request: ToolkitRequest): ToolkitAction? {
        val pkg =
            _state.value.detail
                ?.app
                ?.packageName
        return when (request) {
            is ToolkitRequest.ApplyDoze -> {
                ToolkitAction.ApplyDoze(request.profile)
            }

            is ToolkitRequest.SetAppOp -> {
                pkg?.let { ToolkitAction.SetAppOp(it, request.op, if (request.ignored) AppOpMode.IGNORE else AppOpMode.ALLOW) }
            }

            is ToolkitRequest.SetBucket -> {
                pkg?.let { ToolkitAction.SetBucket(it, request.bucket) }
            }

            ToolkitRequest.ForceStop -> {
                pkg?.let { ToolkitAction.ForceStop(it) }
            }

            is ToolkitRequest.Restore, ToolkitRequest.ResetAll -> {
                null
            }
        }
    }

    private suspend fun planOf(prepared: PreparedRequest): ToolkitPlan =
        when (val request = prepared.request) {
            is ToolkitRequest.Restore -> deps.toolkit.planRestore(request.actionId)
            ToolkitRequest.ResetAll -> ToolkitPlan.Ready
            else -> prepared.action?.let { deps.toolkit.plan(it) } ?: ToolkitPlan.Refused(ToolkitRefusal.INVALID_ARGUMENT)
        }

    private fun confirmationOf(
        prepared: PreparedRequest,
        warnings: List<ToolkitWarning>,
        stale: Boolean = false,
    ) = Confirmation(request = prepared.request, target = prepared.target, warnings = warnings.distinct().toImmutableList(), stale = stale)

    private fun targetOf(request: ToolkitRequest): String? =
        when (request) {
            is ToolkitRequest.SetAppOp, is ToolkitRequest.SetBucket, ToolkitRequest.ForceStop -> {
                _state.value.detail
                    ?.app
                    ?.label
            }

            is ToolkitRequest.Restore -> {
                _state.value.overrides
                    .firstOrNull { it.actionId == request.actionId }
                    ?.label
            }

            is ToolkitRequest.ApplyDoze, ToolkitRequest.ResetAll -> {
                null
            }
        }

    private fun ask(
        confirmation: Confirmation,
        prepared: PreparedRequest,
    ) {
        if (!visible || prepared.generation != generation) return
        pending = prepared
        _state.update { it.copy(confirmation = confirmation) }
    }

    private fun confirmPending() {
        val prepared = pending ?: return
        if (_state.value.busy) return
        pending = null
        _state.update { it.copy(confirmation = null, busy = true) }
        viewModelScope.launch { executeContained(prepared, confirmed = true) }
    }

    private fun dismissConfirmation() {
        pending = null
        _state.update { it.copy(confirmation = null) }
    }

    /**
     * [execute], with a call that throws instead of answering (the audit row of a write cannot be stored on a
     * full disk, say) reported as a failed action: the screen shows what the system reports now and is usable again.
     */
    private suspend fun executeContained(
        prepared: PreparedRequest,
        confirmed: Boolean,
    ) {
        if (attempt { execute(prepared, confirmed) } == null) finish(prepared, CALL_FAILED)
    }

    private suspend fun execute(
        prepared: PreparedRequest,
        confirmed: Boolean,
    ) {
        val request = prepared.request
        if (request == ToolkitRequest.ResetAll) {
            resetAll()
            return
        }
        val outcome: ToolkitOutcome<*> =
            when (request) {
                is ToolkitRequest.Restore -> {
                    deps.toolkit.restore(request.actionId, confirmed)
                }

                else -> {
                    val action =
                        prepared.action ?: return finish(prepared, ToolkitNotice.Rejected(Problem.Refused(ToolkitRefusal.INVALID_ARGUMENT)))
                    deps.toolkit.execute(action, confirmed)
                }
            }
        if (outcome is ToolkitOutcome.Refused && outcome.reason == ToolkitRefusal.STALE) {
            askAgain(prepared)
            return
        }
        finish(
            prepared,
            if (outcome is ToolkitOutcome.Done<*>) {
                ToolkitNotice.Applied(
                    changeOf(request),
                )
            } else {
                ToolkitNotice.Rejected(ToolkitMapper.problem(outcome))
            },
        )
    }

    /** The setting moved between plan and write: read it again and let the user decide with the value that is there now. */
    private suspend fun askAgain(prepared: PreparedRequest) {
        val request = prepared.request
        if (prepared.generation != generation) {
            _state.update { it.copy(busy = false) }
            return
        }
        refresh(request)
        val plan = planOf(prepared)
        if (prepared.generation != generation) {
            _state.update { it.copy(busy = false) }
            return
        }
        when (plan) {
            ToolkitPlan.Ready -> {
                ask(confirmationOf(prepared, emptyList(), stale = true), prepared)
            }

            is ToolkitPlan.NeedsConfirmation -> {
                ask(confirmationOf(prepared, listOf(plan.warning) + plan.others, stale = true), prepared)
            }

            is ToolkitPlan.Refused -> {
                showNotice(request, ToolkitNotice.Rejected(Problem.Refused(plan.reason)))
            }
        }
        _state.update { it.copy(busy = false) }
    }

    private fun changeOf(request: ToolkitRequest): Change =
        when (request) {
            is ToolkitRequest.ApplyDoze -> Change.DozeApplied(request.profile)
            is ToolkitRequest.SetAppOp -> Change.AppOpSet(request.op, request.ignored)
            is ToolkitRequest.SetBucket -> Change.BucketSet(request.bucket)
            ToolkitRequest.ForceStop -> Change.ForceStopped
            is ToolkitRequest.Restore, ToolkitRequest.ResetAll -> Change.Restored
        }

    /** Whatever happened, the screen then shows what the system reports now. Never throws: [refresh] contains its reads. */
    private suspend fun finish(
        prepared: PreparedRequest,
        notice: ToolkitNotice,
    ) {
        refresh(prepared.request)
        if (prepared.generation == generation) showNotice(prepared.request, notice)
        _state.update { it.copy(busy = false) }
    }

    private suspend fun refresh(request: ToolkitRequest) {
        val touchesDoze = request is ToolkitRequest.ApplyDoze || request is ToolkitRequest.Restore || request == ToolkitRequest.ResetAll
        if (touchesDoze) showDoze(attempt { deps.toolkit.dozeState() })
        val open =
            _state.value.detail
                ?.app
                ?.packageName
        if (open != null && request !is ToolkitRequest.ApplyDoze) showControls(open)
    }

    private fun showNotice(
        request: ToolkitRequest,
        notice: ToolkitNotice?,
    ) {
        _state.update { current ->
            when (request) {
                is ToolkitRequest.ApplyDoze -> {
                    current.copy(doze = current.doze.copy(notice = notice))
                }

                is ToolkitRequest.SetAppOp, is ToolkitRequest.SetBucket, ToolkitRequest.ForceStop -> {
                    current.copy(detail = current.detail?.copy(notice = notice))
                }

                is ToolkitRequest.Restore -> {
                    current.copy(overridesNotice = notice)
                }

                // Only a reset that failed as a whole gets a notice; a finished one has its report instead.
                ToolkitRequest.ResetAll -> {
                    current.copy(overridesNotice = notice)
                }
            }
        }
    }

    /**
     * `ToolkitActions.resetAll()` restores each active override to the original of its chain and reports each
     * item; the items are the ones `activeOverrides` shows. A failed item does not stop the others.
     */
    private suspend fun resetAll() {
        val lines =
            deps.toolkit.resetAll().map { item ->
                ResetLine(
                    item = overrideItem(item.override),
                    result =
                        if (item.outcome is ToolkitOutcome.Done) {
                            ResetResult.Restored
                        } else {
                            ResetResult.NotRestored(
                                ToolkitMapper.problem(item.outcome),
                            )
                        },
                )
            }
        val report = ResetReport(lines.toImmutableList())
        refresh(ToolkitRequest.ResetAll)
        _state.update { it.copy(resetReport = report, overridesNotice = null, busy = false) }
        if (visible) _effects.trySend(ToolkitEffect.ResetFinished(restored = report.restored, total = report.lines.size))
    }

    // ---- bg-dexopt -------------------------------------------------------------------------

    private fun startDexopt() {
        val dexopt = _state.value.dexopt
        if (_state.value.status != ToolkitPhase.READY || dexopt.busy) return
        if (dexopt.status is BgDexoptStatus.Running || dexopt.status == BgDexoptStatus.Unknown) return
        setDexopt { it.copy(busy = true, notice = null) }
        viewModelScope.launch {
            val outcome = attempt { deps.toolkit.startBgDexopt() }
            setDexopt { it.copy(busy = false, notice = dexoptNotice(outcome, Change.DexoptStarted)) }
        }
    }

    /** Cancel is system-side and idempotent, so it is offered for an UNKNOWN job as well as a RUNNING one. */
    private fun cancelDexopt() {
        val dexopt = _state.value.dexopt
        if (_state.value.status != ToolkitPhase.READY || dexopt.busy) return
        if (dexopt.status !is BgDexoptStatus.Running && dexopt.status != BgDexoptStatus.Unknown) return
        setDexopt { it.copy(busy = true, notice = null) }
        viewModelScope.launch {
            val outcome = attempt { deps.toolkit.cancelBgDexopt() }
            setDexopt { it.copy(busy = false, notice = dexoptNotice(outcome, Change.DexoptCancelled)) }
        }
    }

    /** A null [outcome] is a call that threw instead of answering (the job's audit row could not be stored, say). */
    private fun dexoptNotice(
        outcome: ToolkitOutcome<*>?,
        change: Change,
    ): ToolkitNotice =
        when (outcome) {
            is ToolkitOutcome.Done -> ToolkitNotice.Applied(change)
            null -> CALL_FAILED
            else -> ToolkitNotice.Rejected(ToolkitMapper.problem(outcome))
        }

    private fun setDexopt(transform: (DexoptSection) -> DexoptSection) {
        _state.update { it.copy(dexopt = transform(it.dexopt)) }
    }

    companion object {
        /** The factory the route hands to `viewModel(...)`; the ViewModel only ever sees [ToolkitDependencies]. */
        fun factory(deps: ToolkitDependencies): ViewModelProvider.Factory =
            viewModelFactory {
                initializer { ToolkitViewModel(deps) }
            }
    }
}
