package com.akane.inspiration.telemetry.source

import com.akane.inspiration.ipc.ITelemetryUserService
import com.akane.inspiration.ipc.ShizukuManager
import com.akane.inspiration.ipc.ShizukuState
import com.akane.inspiration.ipc.model.BatteryUsageSnapshot
import com.akane.inspiration.ipc.model.DumpResult
import com.akane.inspiration.ipc.model.DumpTarget
import com.akane.inspiration.telemetry.common.Clocks
import com.akane.inspiration.telemetry.snapshot.CaptureTrigger
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Collections
import java.util.concurrent.Executors

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidTelemetrySourceTest {
    @Test
    fun `full reads run on the io dispatcher, not the caller's thread`() {
        val executor = Executors.newSingleThreadExecutor { Thread(it, IO_THREAD) }
        val shizuku = RecordingShizuku()
        try {
            val source =
                AndroidTelemetrySource(
                    context = RuntimeEnvironment.getApplication(),
                    shizuku = shizuku,
                    clocks = RecordingClocks,
                    ioDispatcher = executor.asCoroutineDispatcher(),
                )

            runBlocking { source.full(CaptureTrigger.MARKER, historyCursor = null) }

            assertEquals("light read thread", IO_THREAD, RecordingClocks.lastThread)
            assertEquals("service read thread", IO_THREAD, shizuku.lastThread)
        } finally {
            executor.shutdown()
        }
    }

    @Test
    fun `a full read takes the batterystats dump before usage, so usage can reuse the dump's stats sync`() {
        val calls = Collections.synchronizedList(mutableListOf<String>())
        val service =
            object : ITelemetryUserService.Default() {
                override fun openDump(target: Int): DumpResult {
                    calls += "dump:$target"
                    return DumpResult.unavailable("not_under_test")
                }

                override fun readBatteryUsage(maxAgeMs: Long): BatteryUsageSnapshot {
                    calls += "usage:$maxAgeMs"
                    return BatteryUsageSnapshot.unavailable("not_under_test")
                }
            }
        val source =
            AndroidTelemetrySource(
                context = RuntimeEnvironment.getApplication(),
                shizuku = DirectShizuku(service),
                clocks = RecordingClocks,
            )

        runBlocking { source.full(CaptureTrigger.MARKER, historyCursor = null) }

        assertEquals(
            listOf(
                "dump:${DumpTarget.BATTERYSTATS_PROTO}",
                "usage:$USAGE_MAX_STATS_AGE_MS",
                "dump:${DumpTarget.ALARM_PROTO}",
            ),
            calls.toList(),
        )
        assertTrue("a zero max age forces a second sync after the dump's", USAGE_MAX_STATS_AGE_MS > 0L)
    }

    private class DirectShizuku(
        private val service: ITelemetryUserService,
    ) : ShizukuManager {
        override val state: StateFlow<ShizukuState> = MutableStateFlow(ShizukuState.Ready(2000, 13))
        override val telemetryService: StateFlow<ITelemetryUserService?> = MutableStateFlow(service)

        override fun requestPermission() = Unit

        override fun start() = Unit

        override suspend fun <T> withTelemetryService(
            timeoutMs: Long,
            block: suspend (ITelemetryUserService) -> T,
        ): T? = block(service)

        override fun holdBinding(): AutoCloseable = AutoCloseable {}

        override fun stop() = Unit
    }

    private object RecordingClocks : Clocks {
        var lastThread: String? = null

        override fun wallMs(): Long = 1L.also { lastThread = Thread.currentThread().name.substringBefore(" @") }

        override fun elapsedRealtimeMs(): Long = 2L

        override fun uptimeMs(): Long = 3L

        override fun bootCount(): Int = 4
    }

    private class RecordingShizuku : ShizukuManager {
        var lastThread: String? = null
        override val state: StateFlow<ShizukuState> get() = error("unused")
        override val telemetryService: StateFlow<ITelemetryUserService?> = MutableStateFlow(null)

        override fun requestPermission() = Unit

        override fun start() = Unit

        override suspend fun <T> withTelemetryService(
            timeoutMs: Long,
            block: suspend (ITelemetryUserService) -> T,
        ): T? {
            lastThread = Thread.currentThread().name.substringBefore(" @")
            return null
        }

        override fun holdBinding(): AutoCloseable = AutoCloseable {}

        override fun stop() = Unit
    }

    private companion object {
        // Debug-mode coroutines append " @coroutine#n" to the thread name, which the recorders strip.
        const val IO_THREAD = "telemetry-io-test"
    }
}
