package com.akane.inspiration.health

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.akane.inspiration.telemetry.app.KernelCapacitySnapshot
import com.akane.inspiration.telemetry.app.RefreshResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
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

/** The source's blocking-Binder/deadline regression lives in AndroidTelemetrySourceDeadlineTest. */
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

    @Test
    fun `a kernel deadline miss allows persisted health and recorded charge data to load`() =
        runTest(dispatcher) {
            val start = NOW_MS - HOUR_MS
            val persisted =
                FakeHealthDeps().apply {
                    healthSamples = listOf(healthSample(start, fcc = 4_100.0), healthSample(NOW_MS, fcc = 4_000.0))
                    sessions = listOf(sessionRecord(NOW_MS, estimateMah = 3_950.0))
                    samples = listOf(sample(start, level = 30), sample(start + 10 * SEC_MS, level = 31))
                    refreshResult = RefreshResult.Debounced(NOW_MS)
                }
            // The total source deadline returns null even though an abandoned Binder call has not finished.
            val kernelResult = CompletableDeferred<KernelCapacitySnapshot?>()
            val deps =
                object : HealthDependencies by persisted {
                    override suspend fun kernelBattery(): KernelCapacitySnapshot? = kernelResult.await()
                }
            val store = ViewModelStore()
            val vm = ViewModelProvider(store, HealthViewModel.factory(deps))[HealthViewModel::class.java]
            try {
                vm.onIntent(HealthIntent.Visible)
                runCurrent()
                assertEquals(HealthPhase.Loading, vm.state.value.phase)

                kernelResult.complete(null)
                runCurrent()

                val state = vm.state.value
                assertEquals(HealthPhase.Ready, state.phase)
                assertEquals(4_000.0, state.card?.reported?.mah ?: Double.NaN, 0.0)
                assertEquals(3_950.0, state.card?.measured?.mah ?: Double.NaN, 0.0)
                assertTrue(state.wear is WearUi.Ready)
                assertEquals(CurveSource.Recorded, (state.curve as CurveUi.Ready).source)
                assertFalse(state.refreshing)
            } finally {
                store.clear()
            }
        }
}
