package com.akane.inspiration.diagnostics.analysis

import com.akane.inspiration.telemetry.diff.ReferenceKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** The app-wide interval choice shared by Cockpit, X-Ray and Sleep Lab. */
class AnalysisSelection(initial: ReferenceKind = ReferenceKind.SinceUnplugged) {
    private val _selected = MutableStateFlow(initial)
    val selected: StateFlow<ReferenceKind> = _selected.asStateFlow()

    fun select(kind: ReferenceKind) {
        _selected.value = kind
    }

    /** Leaves a time slice for the default reference; any other selection is kept. */
    fun clearSlice() {
        _selected.update { if (it is ReferenceKind.TimeSlice) ReferenceKind.SinceUnplugged else it }
    }
}
