package com.akane.inspiration.xray

import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.akane.inspiration.diagnostics.analysis.AnalysisState
import com.akane.inspiration.ipc.toolkit.AppOp
import com.akane.inspiration.ipc.toolkit.AppOpMode
import com.akane.inspiration.ipc.toolkit.StandbyBucket
import com.akane.inspiration.telemetry.app.RefreshResult
import com.akane.inspiration.telemetry.diff.ReferenceKind
import com.akane.inspiration.telemetry.toolkit.AppToolkitState
import com.akane.inspiration.telemetry.toolkit.Override
import com.akane.inspiration.telemetry.toolkit.OverrideKind
import com.akane.inspiration.telemetry.toolkit.ToolkitAction
import com.akane.inspiration.telemetry.toolkit.ToolkitOutcome
import com.akane.inspiration.telemetry.toolkit.ToolkitPlan
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * MVI ViewModel of the X-Ray screen (H5). It reads the shared analysis and Shizuku state only while the route
 * is visible ([XRayIntent.Visible] to [XRayIntent.Hidden], H13). Nothing polls: refresh is asked for once per
 * start. Quick actions go through `deps.toolkit` alone; the package they act on is the one of the app the
 * user opened from the in-app list, never anything an Intent or URI carries (H12).
 */
class XRayViewModel(private val deps: XRayDependencies) : ViewModel() {
    private val _state = MutableStateFlow(XRayState())
    val state: StateFlow<XRayState> = _state.asStateFlow()

    private val _effects = Channel<XRayEffect>(Channel.BUFFERED)
    val effects: Flow<XRayEffect> = _effects.receiveAsFlow()

    private var visibleJob: Job? = null
    private var refreshJob: Job? = null
    private var controlsJob: Job? = null
    private var overridesJob: Job? = null
    private var planJob: Job? = null
    private var visible = false
    private var pending: ActionRequest? = null

    fun onIntent(intent: XRayIntent) {
        when (intent) {
            XRayIntent.Visible -> onVisible()
            XRayIntent.Hidden -> onHidden()
            is XRayIntent.SelectTab -> _state.update { it.copy(tab = intent.tab) }
            XRayIntent.Capture -> startRefresh()
            XRayIntent.ClearSlice -> deps.selection.clearSlice()
            is XRayIntent.OpenApp -> openApp(intent.uid)
            XRayIntent.CloseApp -> closeApp()
            XRayIntent.ReloadActions -> reloadDetail()
            is XRayIntent.Act -> act(intent.request)
            XRayIntent.ConfirmPending -> confirmPending()
            XRayIntent.DismissConfirmation -> dismissConfirmation()
        }
    }

    // ---- visibility ------------------------------------------------------------------------

    private fun onVisible() {
        visible = true
        visibleJob?.cancel()
        visibleJob = viewModelScope.launch {
            launch {
                combine(deps.analysis, deps.selection.selected) { analysis, selected -> analysis to selected }
                    .collectLatest { (analysis, selected) -> show(analysis, selected) }
            }
            // The first value is the current state, so an open detail sheet is re-read on every start.
            launch { deps.shizukuState.collect { if (_state.value.detail != null) reloadDetail() } }
        }
        startRefresh()
    }

    private fun onHidden() {
        visible = false
        visibleJob?.cancel()
        controlsJob?.cancel()
        overridesJob?.cancel()
        // A confirmation must not outlive the screen it was asked on, and neither may one that is still being
        // planned: that plan is dropped, so no dialog appears after the user left. A write already running finishes.
        val planning = planJob?.isActive == true
        cancelPlanning()
        pending = null
        _state.update { current ->
            val detail = current.detail
            current.copy(
                confirmation = null,
                detail = if (planning && detail != null) detail.copy(busy = false) else detail,
            )
        }
        // Results of work done for a screen that is gone are not shown when it comes back.
        while (_effects.tryReceive().isSuccess) Unit
    }

    private fun cancelPlanning() {
        planJob?.cancel()
        planJob = null
    }

    /** Effects are one-shot results for the visible screen: nothing is queued for a screen that is not there. */
    private fun emit(effect: XRayEffect) {
        if (visible) _effects.trySend(effect)
    }

    private fun startRefresh() {
        if (refreshJob?.isActive == true) return
        _state.update { it.copy(refreshing = true) }
        refreshJob = viewModelScope.launch {
            val result = try {
                deps.refresh()
            } finally {
                _state.update { it.copy(refreshing = false) }
            }
            if (result is RefreshResult.Failed) emit(XRayEffect.RefreshFailed)
        }
    }

    // ---- analysis --------------------------------------------------------------------------

    private suspend fun show(analysis: AnalysisState, selected: ReferenceKind) {
        when (analysis) {
            AnalysisState.Loading -> _state.update { it.copy(status = XRayStatus.LOADING, slice = XRayMapper.slice(selected, null)) }
            AnalysisState.Empty -> _state.update { it.copy(status = XRayStatus.EMPTY, slice = XRayMapper.slice(selected, null)) }
            is AnalysisState.Failed -> _state.update { it.copy(status = XRayStatus.FAILED, slice = XRayMapper.slice(selected, null)) }
            is AnalysisState.Ready -> showReady(analysis, selected)
        }
    }

    private suspend fun showReady(ready: AnalysisState.Ready, selected: ReferenceKind) {
        val resolved = ready.analysis.resolved
        // The session recomputes after a selection change; until it has, its result belongs to the old selection.
        if (selected is ReferenceKind.TimeSlice &&
            (resolved.boundaryFromWallMs != selected.fromWallMs || resolved.boundaryToWallMs != selected.toWallMs)
        ) {
            _state.update { it.copy(status = XRayStatus.LOADING, slice = XRayMapper.slice(selected, null)) }
            return
        }
        val diff = ready.analysis.diff
        val components = XRayMapper.components(diff).toImmutableList()
        val apps = withIdentities(XRayMapper.apps(diff, resolved.perUidAvailable)).toImmutableList()
        val slice = XRayMapper.slice(selected, resolved)
        _state.update { current ->
            current.copy(
                status = XRayStatus.READY,
                components = components,
                perUidAvailable = resolved.perUidAvailable,
                breakdownAvailable = diff.validity.batteryStats,
                apps = apps,
                slice = slice,
                detail = current.detail?.let { detail ->
                    apps.firstOrNull { it.uid == detail.app.uid }?.let { detail.copy(app = it) } ?: detail
                },
            )
        }
    }

    private suspend fun withIdentities(rows: List<AppRow>): List<AppRow> = rows.map { row ->
        val pkg = row.packageName ?: return@map row
        row.copy(label = deps.appLabels.label(pkg), icon = deps.appLabels.icon(pkg)?.asImageBitmap())
    }

    // ---- detail sheet ----------------------------------------------------------------------

    private fun openApp(uid: Int) {
        val app = _state.value.apps.firstOrNull { it.uid == uid } ?: return
        cancelPlanning()
        pending = null
        _state.update { it.copy(detail = AppDetail(app = app, actions = ActionsState.Loading), confirmation = null) }
        reloadDetail()
    }

    private fun closeApp() {
        controlsJob?.cancel()
        overridesJob?.cancel()
        cancelPlanning()
        pending = null
        _state.update { it.copy(detail = null, confirmation = null) }
    }

    /** Reads the open app's settings and overrides back from the system (never from what was last asked). */
    private fun reloadDetail() {
        val detail = _state.value.detail ?: return
        val uid = detail.app.uid
        controlsJob?.cancel()
        overridesJob?.cancel()
        val pkg = detail.app.packageName
        if (pkg == null) {
            updateDetail(uid) { it.copy(actions = ActionsState.NoPackage, overrides = persistentListOf()) }
            return
        }
        val blocked = deps.shizukuState.value.blockedReason()
        if (blocked != null) {
            updateDetail(uid) { it.copy(actions = ActionsState.Blocked(blocked), overrides = persistentListOf()) }
            return
        }
        updateDetail(uid) { it.copy(actions = ActionsState.Loading) }
        controlsJob = viewModelScope.launch {
            val actions = readActions(pkg)
            updateDetail(uid) { it.copy(actions = actions) }
        }
        overridesJob = viewModelScope.launch {
            deps.toolkit.activeOverrides().collect { all ->
                val rows = all.filter { it.pkg == pkg && it.kind != OverrideKind.DOZE }.map(::overrideRow)
                updateDetail(uid) { it.copy(overrides = rows.toImmutableList()) }
            }
        }
    }

    private suspend fun readActions(pkg: String): ActionsState {
        deps.shizukuState.value.blockedReason()?.let { return ActionsState.Blocked(it) }
        return when (val outcome = deps.toolkit.appState(pkg)) {
            is ToolkitOutcome.Done -> ActionsState.Loaded(controlsOf(outcome.value))
            else -> ActionsState.Unavailable(problemOf(outcome))
        }
    }

    private fun controlsOf(state: AppToolkitState): AppControls {
        val mode = state.appOps[AppOp.RUN_ANY_IN_BACKGROUND]
        return AppControls(
            restricted = mode == AppOpMode.IGNORE,
            restrictionEditable = mode != null,
            bucket = state.bucket,
            bucketRaw = state.bucketRaw,
        )
    }

    private fun overrideRow(override: Override) = OverrideRow(
        actionId = override.actionId,
        kind = override.kind,
        appOp = override.appOp,
        bucket = override.currentRaw?.trim()?.toIntOrNull()?.let { raw -> StandbyBucket.entries.firstOrNull { it.rawValue == raw } },
        sinceMs = override.sinceMs,
        restorable = override.restorable,
    )

    private fun updateDetail(uid: Int, transform: (AppDetail) -> AppDetail) {
        _state.update { current ->
            val detail = current.detail
            if (detail != null && detail.app.uid == uid) current.copy(detail = transform(detail)) else current
        }
    }

    // ---- quick actions ---------------------------------------------------------------------

    private fun act(request: ActionRequest) {
        val detail = _state.value.detail ?: return
        val pkg = detail.app.packageName ?: return
        if (detail.busy || _state.value.confirmation != null) return
        if (detail.actions !is ActionsState.Loaded || deps.shizukuState.value.blockedReason() != null) return
        // A restore only ever targets a change of this app that read-back still shows.
        if (request is ActionRequest.Restore && detail.overrides.none { it.actionId == request.actionId }) return
        val uid = detail.app.uid
        updateDetail(uid) { it.copy(busy = true, notice = null) }
        planJob = viewModelScope.launch {
            val plan = planOf(request, pkg)
            // The plan is only a read: if the screen or the sheet went away while it ran, nothing follows from it.
            ensureActive()
            when (plan) {
                // The write is its own job, so leaving cancels a plan but never a write already in flight.
                ToolkitPlan.Ready -> viewModelScope.launch { execute(uid, request, pkg, confirmed = false) }
                is ToolkitPlan.NeedsConfirmation -> {
                    pending = request
                    _state.update { current ->
                        current.copy(
                            confirmation = Confirmation(
                                request = request,
                                target = detail.app.label ?: pkg,
                                warnings = (listOf(plan.warning) + plan.others).distinct().toImmutableList(),
                            ),
                        )
                    }
                    updateDetail(uid) { it.copy(busy = false) }
                }
                is ToolkitPlan.Refused ->
                    updateDetail(uid) { it.copy(busy = false, notice = ActionNotice.Rejected(Problem.Refused(plan.reason))) }
            }
        }
    }

    private fun confirmPending() {
        val request = pending ?: return
        val detail = _state.value.detail ?: return
        val pkg = detail.app.packageName ?: return
        pending = null
        _state.update { it.copy(confirmation = null) }
        val uid = detail.app.uid
        updateDetail(uid) { it.copy(busy = true, notice = null) }
        viewModelScope.launch { execute(uid, request, pkg, confirmed = true) }
    }

    private fun dismissConfirmation() {
        pending = null
        _state.update { it.copy(confirmation = null) }
    }

    private suspend fun planOf(request: ActionRequest, pkg: String): ToolkitPlan = when (request) {
        is ActionRequest.SetRestricted -> deps.toolkit.plan(ToolkitAction.SetAppOp(pkg, AppOp.RUN_ANY_IN_BACKGROUND, modeOf(request)))
        is ActionRequest.SetBucket -> deps.toolkit.plan(ToolkitAction.SetBucket(pkg, request.bucket))
        ActionRequest.ForceStop -> deps.toolkit.plan(ToolkitAction.ForceStop(pkg))
        is ActionRequest.Restore -> deps.toolkit.planRestore(request.actionId)
    }

    private suspend fun execute(uid: Int, request: ActionRequest, pkg: String, confirmed: Boolean) {
        val notice = when (request) {
            is ActionRequest.SetRestricted ->
                noticeOf(deps.toolkit.execute(ToolkitAction.SetAppOp(pkg, AppOp.RUN_ANY_IN_BACKGROUND, modeOf(request)), confirmed)) {
                    if (request.restricted) Change.Restricted else Change.Unrestricted
                }
            is ActionRequest.SetBucket ->
                noticeOf(deps.toolkit.execute(ToolkitAction.SetBucket(pkg, request.bucket), confirmed)) { Change.BucketSet(request.bucket) }
            ActionRequest.ForceStop ->
                noticeOf(deps.toolkit.execute(ToolkitAction.ForceStop(pkg), confirmed)) { Change.ForceStopped }
            is ActionRequest.Restore ->
                noticeOf(deps.toolkit.restore(request.actionId, confirmed)) { Change.Restored }
        }
        // Whatever happened, the controls show what the system reports now.
        val actions = readActions(pkg)
        updateDetail(uid) { it.copy(busy = false, notice = notice, actions = actions) }
    }

    private fun modeOf(request: ActionRequest.SetRestricted) = if (request.restricted) AppOpMode.IGNORE else AppOpMode.DEFAULT

    private inline fun noticeOf(outcome: ToolkitOutcome<*>, change: () -> Change): ActionNotice =
        if (outcome is ToolkitOutcome.Done) ActionNotice.Applied(change()) else ActionNotice.Rejected(problemOf(outcome))

    private fun problemOf(outcome: ToolkitOutcome<*>): Problem = when (outcome) {
        is ToolkitOutcome.Refused -> Problem.Refused(outcome.reason)
        is ToolkitOutcome.Unavailable -> Problem.Unavailable(outcome.reason)
        is ToolkitOutcome.Failed -> Problem.Failed(outcome.code)
        is ToolkitOutcome.Done -> Problem.Failed(null)
    }

    companion object {
        /** The factory the route hands to `viewModel(...)`; the ViewModel only ever sees [XRayDependencies]. */
        fun factory(deps: XRayDependencies): ViewModelProvider.Factory = viewModelFactory {
            initializer { XRayViewModel(deps) }
        }
    }
}
