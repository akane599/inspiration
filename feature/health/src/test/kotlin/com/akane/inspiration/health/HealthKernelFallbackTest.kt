package com.akane.inspiration.health

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.akane.inspiration.telemetry.app.KernelCapacitySnapshot
import com.akane.inspiration.telemetry.app.RefreshResult
import com.akane.inspiration.telemetry.snapshot.LiveReading
import com.akane.inspiration.telemetry.store.HealthSample
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The kernel read can take its whole 2 s deadline (Shizuku installed but not running), while everything else on the
 * screen comes from Room (B-14). The source's blocking-Binder/deadline regression lives in
 * AndroidTelemetrySourceDeadlineTest.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HealthKernelFallbackTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** Recorded health data, a recorded charge, and a kernel read that answers only when [kernel] does. */
    private class SlowKernel(
        val app: FakeHealthDeps = FakeHealthDeps(),
    ) : HealthDependencies by app {
        var kernel = CompletableDeferred<KernelCapacitySnapshot?>()
        var kernelCancelled = 0
        val readings = MutableSharedFlow<LiveReading>()

        /** The stored reads the load made while the kernel read had not answered yet. */
        var storedReadsWhileKernelPending = 0

        init {
            val start = NOW_MS - HOUR_MS
            app.healthSamples = listOf(healthSample(start, fcc = 4_100.0), healthSample(NOW_MS, fcc = 4_000.0))
            app.sessions = listOf(sessionRecord(NOW_MS, estimateMah = 3_950.0))
            app.samples = listOf(sample(start, level = 30), sample(start + 10 * SEC_MS, level = 31))
            app.refreshResult = RefreshResult.Debounced(NOW_MS)
        }

        override suspend fun kernelBattery(): KernelCapacitySnapshot? {
            try {
                return kernel.await()
            } catch (e: CancellationException) {
                kernelCancelled++
                throw e
            }
        }

        override fun liveReadings(periodMs: Long): Flow<LiveReading> = readings

        override suspend fun healthSamples(): List<HealthSample> =
            app.healthSamples().also { if (!kernel.isCompleted) storedReadsWhileKernelPending++ }
    }

    private fun TestScope.open(deps: HealthDependencies): Pair<ViewModelStore, HealthViewModel> {
        val store = ViewModelStore()
        val vm = ViewModelProvider(store, testFactory(deps, dispatcher))[HealthViewModel::class.java]
        vm.onIntent(HealthIntent.Visible)
        runCurrent()
        return store to vm
    }

    private val HealthState.reportedMah: Double get() = card?.reported?.mah ?: Double.NaN

    @Test
    fun `the stored parts are shown while the kernel read waits out its deadline`() =
        runTest(dispatcher) {
            val deps = SlowKernel()
            val (store, vm) = open(deps)
            try {
                assertEquals("the stored reads run alongside the kernel read", 1, deps.storedReadsWhileKernelPending)
                assertEquals("within the grace the stored day waits", HealthPhase.Loading, vm.state.value.phase)

                advanceTimeBy(KERNEL_GRACE_MS)
                runCurrent()
                val shown = vm.state.value
                assertFalse("the kernel read is still running", deps.kernel.isCompleted)
                assertEquals(HealthPhase.Ready, shown.phase)
                assertEquals("the recorded health sample stands in", 4_000.0, shown.reportedMah, 0.0)
                assertEquals(3_950.0, shown.card?.measured?.mah ?: Double.NaN, 0.0)
                assertTrue(shown.wear is WearUi.Ready)
                assertEquals(CurveSource.Recorded, (shown.curve as CurveUi.Ready).source)

                // The total source deadline returns null even though an abandoned Binder call has not finished.
                advanceTimeBy(2_000L - KERNEL_GRACE_MS)
                deps.kernel.complete(null)
                runCurrent()

                assertEquals("a failed kernel read leaves the frame as it was", shown, vm.state.value)
                assertFalse(vm.state.value.refreshing)
            } finally {
                store.clear()
            }
        }

    @Test
    fun `the kernel's figures replace the recorded ones when the read answers late`() =
        runTest(dispatcher) {
            val deps = SlowKernel()
            val (store, vm) = open(deps)
            try {
                advanceTimeBy(KERNEL_GRACE_MS)
                runCurrent()
                assertEquals(4_000.0, vm.state.value.reportedMah, 0.0)

                deps.kernel.complete(kernelSnapshot(chargeFullMah = 4_300.0))
                runCurrent()

                assertEquals(HealthPhase.Ready, vm.state.value.phase)
                assertEquals(4_300.0, vm.state.value.reportedMah, 0.0)
                assertEquals(212, vm.state.value.card?.cycles)
            } finally {
                store.clear()
            }
        }

    @Test
    fun `a kernel read that answers within the grace lands in the first frame`() =
        runTest(dispatcher) {
            val deps = SlowKernel()
            val seen = mutableListOf<HealthState>()
            val store = ViewModelStore()
            val vm = ViewModelProvider(store, testFactory(deps, dispatcher))[HealthViewModel::class.java]
            backgroundScope.launch(dispatcher) { vm.state.collect { seen += it } }
            vm.onIntent(HealthIntent.Visible)
            runCurrent()
            try {
                advanceTimeBy(KERNEL_GRACE_MS / 2)
                deps.kernel.complete(kernelSnapshot(chargeFullMah = 4_300.0))
                runCurrent()

                assertEquals(4_300.0, vm.state.value.reportedMah, 0.0)
                assertTrue(
                    "the recorded figures never flash first: $seen",
                    seen.none { it.phase == HealthPhase.Ready && it.reportedMah != 4_300.0 },
                )
            } finally {
                store.clear()
            }
        }

    @Test
    fun `nothing stored waits for the kernel to tell empty from ready`() =
        runTest(dispatcher) {
            for (answer in listOf(kernelSnapshot(), null)) {
                val deps =
                    SlowKernel().apply {
                        app.healthSamples = emptyList()
                        app.sessions = emptyList()
                        app.samples = emptyList()
                    }
                val (store, vm) = open(deps)
                try {
                    advanceTimeBy(KERNEL_GRACE_MS)
                    runCurrent()
                    assertEquals("not Empty before the kernel has answered", HealthPhase.Loading, vm.state.value.phase)

                    deps.kernel.complete(answer)
                    runCurrent()
                    assertEquals(if (answer == null) HealthPhase.Empty else HealthPhase.Ready, vm.state.value.phase)
                } finally {
                    store.clear()
                }
            }
        }

    @Test
    fun `every stored read failing waits for the kernel to tell error from ready`() =
        runTest(dispatcher) {
            for (answer in listOf(kernelSnapshot(), null)) {
                val deps =
                    SlowKernel().apply {
                        app.healthSamples = null
                        app.sessions = null
                        app.history = null
                        app.samples = null
                    }
                val (store, vm) = open(deps)
                try {
                    advanceTimeBy(KERNEL_GRACE_MS)
                    runCurrent()
                    assertEquals("not Error before the kernel has answered", HealthPhase.Loading, vm.state.value.phase)

                    deps.kernel.complete(answer)
                    runCurrent()
                    assertEquals(if (answer == null) HealthPhase.Error else HealthPhase.Ready, vm.state.value.phase)
                } finally {
                    store.clear()
                }
            }
        }

    @Test
    fun `live points stay drawn while a reload that read them back waits for the kernel`() =
        runTest(dispatcher) {
            val deps = SlowKernel().apply { app.samples = emptyList() }
            deps.kernel.complete(kernelSnapshot())
            val (store, vm) = open(deps)
            try {
                vm.onIntent(HealthIntent.PlugChanged(true))
                runCurrent()
                val times = (0 until 3).map { NOW_MS - (2 - it) * CURVE_PERIOD_MS }
                for ((i, at) in times.withIndex()) {
                    deps.readings.emit(liveReading(at, levelPct = 41 + i))
                    runCurrent()
                }
                assertEquals(41, (vm.state.value.curve as CurveUi.Ready).startLevelPct)

                // The source stored the readings and a store tick reloads, but this load's kernel read hangs.
                deps.kernel = CompletableDeferred()
                deps.app.samples = times.mapIndexed { i, at -> sample(at, level = 41 + i) }
                deps.app.lastRefreshWallMs.value = NOW_MS
                runCurrent()
                deps.readings.emit(liveReading(NOW_MS + CURVE_PERIOD_MS, levelPct = 44))
                runCurrent()

                val curve = vm.state.value.curve as CurveUi.Ready
                assertEquals("the frame before the reload lands still draws every live point", 41, curve.startLevelPct)
                assertEquals(44, curve.endLevelPct)
                assertEquals(4, vm.heldLivePoints)

                advanceTimeBy(KERNEL_GRACE_MS)
                runCurrent()
                assertEquals("the stored twins take over once the reload lands", 1, vm.heldLivePoints)
                assertEquals(curve, vm.state.value.curve)
            } finally {
                store.clear()
            }
        }

    @Test
    fun `a reload shows the new stored day with the last kernel figures and cancels the read it replaces`() =
        runTest(dispatcher) {
            val deps = SlowKernel()
            val (store, vm) = open(deps)
            try {
                deps.kernel.complete(kernelSnapshot(chargeFullMah = 4_300.0))
                runCurrent()
                assertEquals(4_300.0, vm.state.value.reportedMah, 0.0)

                // A capture stored a newer health record; this load's kernel read hangs.
                deps.kernel = CompletableDeferred()
                deps.app.healthSamples = deps.app.healthSamples.orEmpty() + healthSample(NOW_MS + MIN_MS, fcc = 3_900.0)
                deps.app.lastRefreshWallMs.value = NOW_MS
                runCurrent()
                advanceTimeBy(KERNEL_GRACE_MS)
                runCurrent()
                val shown = vm.state.value
                assertEquals("the last kernel read stands in", 4_300.0, shown.reportedMah, 0.0)
                assertEquals(3, (shown.wear as WearUi.Ready).points.size)

                // A newer load replaces the one still waiting on the kernel.
                val hung = deps.kernel
                deps.kernel = CompletableDeferred()
                deps.app.lastRefreshWallMs.value = NOW_MS + 1
                runCurrent()
                assertEquals("the replaced load's kernel read was cancelled", 1, deps.kernelCancelled)
                hung.complete(kernelSnapshot(chargeFullMah = 1_000.0))
                deps.kernel.complete(kernelSnapshot(chargeFullMah = 4_200.0))
                runCurrent()
                assertEquals(4_200.0, vm.state.value.reportedMah, 0.0)
            } finally {
                store.clear()
            }
        }
}
