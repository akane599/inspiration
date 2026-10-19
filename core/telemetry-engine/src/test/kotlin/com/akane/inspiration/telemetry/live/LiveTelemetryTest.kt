package com.akane.inspiration.telemetry.live

import com.akane.inspiration.ipc.model.HistoryEvent
import com.akane.inspiration.telemetry.calibration.CalibrationInput
import com.akane.inspiration.telemetry.calibration.CalibrationLocks
import com.akane.inspiration.telemetry.calibration.CalibrationStart
import com.akane.inspiration.telemetry.calibration.CalibrationStore
import com.akane.inspiration.telemetry.calibration.PowerCalibrator
import com.akane.inspiration.telemetry.capacity.ChargeSegment
import com.akane.inspiration.telemetry.capacity.SegmentVerdict
import com.akane.inspiration.telemetry.common.Clocks
import com.akane.inspiration.telemetry.snapshot.BatterySnapshot
import com.akane.inspiration.telemetry.snapshot.CaptureTrigger
import com.akane.inspiration.telemetry.snapshot.LiveReading
import com.akane.inspiration.telemetry.snapshot.SnapshotClock
import com.akane.inspiration.telemetry.snapshot.SnapshotKind
import com.akane.inspiration.telemetry.source.FullRead
import com.akane.inspiration.telemetry.source.KernelRead
import com.akane.inspiration.telemetry.source.LightRead
import com.akane.inspiration.telemetry.source.TelemetrySource
import com.akane.inspiration.telemetry.store.ChargeSessionRecord
import com.akane.inspiration.telemetry.store.HealthSample
import com.akane.inspiration.telemetry.store.Marker
import com.akane.inspiration.telemetry.store.SnapshotStore
import com.akane.inspiration.telemetry.store.TelemetrySample
import com.akane.inspiration.telemetry.store.ToolkitActionRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors

@OptIn(ExperimentalCoroutinesApi::class)
class LiveTelemetryTest {
    @Test
    fun `readings stops kernel polling when collection cancels`() =
        runTest {
            val source = CountingSource()

            val readings =
                LiveTelemetry(source, PowerCalibrator(), TestClocks())
                    .readings(periodMs = 1_500)
                    .take(1)
                    .toList()

            assertEquals(1, readings.size)
            assertEquals(1, source.kernelCalls)
            testScheduler.advanceTimeBy(10_000)
            assertEquals(1, source.kernelCalls)
        }

    @Test
    fun `reading carries charge counter and thermal status and normalizes an inverted kernel sign`() =
        runTest {
            val source =
                CountingSource(
                    light =
                        lightRead().copy(
                            plugged = true,
                            status = "charging",
                            currentMa = -900,
                            chargeCounterUah = 3_000_000L,
                            thermalStatus = 2,
                        ),
                )

            val reading =
                LiveTelemetry(source, PowerCalibrator(), TestClocks())
                    .readings()
                    .take(1)
                    .toList()
                    .single()

            assertEquals(900, reading.currentMa)
            assertEquals(3_000_000L, reading.chargeCounterUah)
            assertEquals(2, reading.thermalStatus)
            assertEquals("the battery's own status, not just the plug", "charging", reading.status)
        }

    @Test
    fun `live calibration persists across ticks so near zero and skewed currents keep their unit`() =
        runTest {
            val unplugged = lightRead()
            val held = lightRead().copy(plugged = true, status = "full")
            val ticks =
                listOf(
                    // Unplugged at a real load: settles µA and the sign convention.
                    tick(unplugged, sysfsCurrent = -300_000, bmCurrent = -300_000),
                    tick(unplugged, sysfsCurrent = -310_000, bmCurrent = -310_000),
                    tick(unplugged, sysfsCurrent = -290_000, bmCurrent = -290_000),
                    // Held at a charge limit: a few mA, BatteryManager skewed or reading zero.
                    tick(held, sysfsCurrent = 1_500, bmCurrent = 1_000),
                    tick(held, sysfsCurrent = 15_000, bmCurrent = 11_000),
                    tick(held, sysfsCurrent = 700, bmCurrent = 0),
                    // A load that doubled between the sysfs and BatteryManager reads.
                    tick(unplugged, sysfsCurrent = -400_000, bmCurrent = -800_000),
                )
            val source = ScriptedSource(ticks)

            val currents =
                LiveTelemetry(source, PowerCalibrator(), TestClocks())
                    .readings(periodMs = 1)
                    .take(ticks.size)
                    .toList()
                    .map { it.currentMa }

            assertEquals(listOf(-300, -310, -290, 2, 15, 1, -400), currents)
        }

    @Test
    fun `a milliamp kernel reads milliamps from the first unplugged tick`() =
        runTest {
            // The HAL passes current_now through: sysfs and BatteryManager both hold the kernel's mA.
            val ticks = listOf(tick(lightRead(), sysfsCurrent = -400, bmCurrent = -400))

            val reading =
                LiveTelemetry(ScriptedSource(ticks), PowerCalibrator(), TestClocks())
                    .readings(periodMs = 1)
                    .take(1)
                    .toList()
                    .single()

            assertEquals(-400, reading.currentMa)
        }

    @Test
    fun `a milliamp kernel only ever plugged reads milliamps when an earlier process settled the unit`() =
        runTest {
            val calibrationStore = MemoryCalibrationStore()
            // An earlier process saw the device unplugged: the unit, sign and cell layout settled and were saved.
            currents(calibrationStore, List(3) { tick(lightRead(), sysfsCurrent = -400, bmCurrent = -400) })
            assertEquals(
                CalibrationLocks(
                    sysfsMilliamps = true,
                    batteryManagerMilliamps = true,
                    dischargePositive = false,
                    perCellCurrent = false,
                    sysfsMicrovolts = true,
                ),
                calibrationStore.saved?.locks,
            )
            // This process starts on the charger and stays there: a 2 A charge, sysfs and BatteryManager agreeing.
            val charging = lightRead().copy(plugged = true, status = "charging")
            val pluggedTicks = List(3) { tick(charging, sysfsCurrent = 2_000, bmCurrent = 2_000) }

            assertEquals(listOf(2_000, 2_000, 2_000), currents(calibrationStore, pluggedTicks))
            // Without the earlier process plugged readings are no evidence: the unit is unknown, so is the current.
            assertEquals(listOf(null, null, null), currents(MemoryCalibrationStore(), pluggedTicks))
        }

    @Test
    fun `after an OS update the units settled on the old build still read a plugged-only milliamp kernel`() =
        runTest {
            // The new build's store hands the old build's locks back as guesses: no longer locks, still a lean.
            val afterUpdate =
                MemoryCalibrationStore(
                    CalibrationStart(
                        guesses =
                            CalibrationLocks(
                                sysfsMilliamps = true,
                                batteryManagerMilliamps = true,
                                dischargePositive = false,
                                perCellCurrent = false,
                                sysfsMicrovolts = true,
                            ),
                    ),
                )
            val charging = lightRead().copy(plugged = true, status = "charging")

            val currents = currents(afterUpdate, List(3) { tick(charging, sysfsCurrent = 2_000, bmCurrent = 2_000) })

            assertEquals(listOf(2_000, 2_000, 2_000), currents)
            val saved = checkNotNull(afterUpdate.saved)
            assertNull("a guess never becomes a lock by itself", saved.locks.sysfsMilliamps)
            assertNull(saved.locks.batteryManagerMilliamps)
            assertNull(saved.locks.dischargePositive)
            assertEquals("kept for the next process on this build", true, saved.guesses.sysfsMilliamps)
        }

    @Test
    fun `a current whose unit nothing supports yet is neither shown nor stored`() =
        runTest {
            val stored = java.util.concurrent.CopyOnWriteArrayList<LiveReading>()
            // A fresh install set up over USB: plugged from the first tick, sysfs and BatteryManager both 2 A in mA.
            val charging = lightRead().copy(plugged = true, status = "charging")
            val ticks = List(2) { tick(charging, sysfsCurrent = 2_000, bmCurrent = 2_000) }

            val readings =
                LiveTelemetry(ScriptedSource(ticks), PowerCalibrator(), TestClocks(), SampleStore { stored += it })
                    .readings(periodMs = 1)
                    .take(ticks.size)
                    .toList()

            assertTrue(stored.isNotEmpty())
            for (reading in readings + stored) {
                assertNull("not 2 mA, nor 2 A guessed", reading.currentMa)
                assertNull(reading.watts)
                assertEquals("voltage, level and temperature stay", 4_000, reading.voltageMv)
                assertEquals(50, reading.levelPct)
                assertEquals(250, reading.temperatureDeciC)
            }
        }

    @Test
    fun `without a calibration input a full battery's current keeps its sign`() =
        runTest {
            // Held at full on the charger, the battery supplies a little of the load.
            val source = CountingSource(lightRead().copy(plugged = true, status = "full", currentMa = -20))

            val reading =
                LiveTelemetry(source, PowerCalibrator(), TestClocks())
                    .readings()
                    .take(1)
                    .toList()
                    .single()

            assertEquals(-20, reading.currentMa)
        }

    @Test
    fun `a weak charger on a fresh process keeps the sign an earlier process proved unplugged`() =
        runTest {
            val calibrationStore = MemoryCalibrationStore()
            currents(calibrationStore, List(3) { tick(lightRead(), sysfsCurrent = -300_000, bmCurrent = -300_000) })
            // Status "charging" while the battery still loses 250 mA to the load.
            val weakCharger = lightRead().copy(plugged = true, status = "charging")

            val currents = currents(calibrationStore, List(6) { tick(weakCharger, sysfsCurrent = -250_000, bmCurrent = -250_000) })

            assertEquals(List(6) { -250 }, currents)
            assertEquals("a plugged status never settles the sign", false, calibrationStore.saved?.locks?.dischargePositive)
        }

    @Test
    fun `a failing calibration store costs only the head start`() =
        runTest {
            val failing =
                object : CalibrationStore {
                    var saves = 0

                    override fun load(): CalibrationStart = error("unreadable")

                    override fun save(start: CalibrationStart) {
                        saves++
                        error("disk full")
                    }
                }

            val currents = currents(failing, List(4) { tick(lightRead(), sysfsCurrent = -400, bmCurrent = -400) })

            assertEquals(List(4) { -400 }, currents)
            assertTrue("a failed save is retried", failing.saves > 1)
        }

    @Test
    fun `reading leaves thermal status null when the source cannot read it`() =
        runTest {
            val reading =
                LiveTelemetry(CountingSource(), PowerCalibrator(), TestClocks())
                    .readings()
                    .take(1)
                    .toList()
                    .single()

            assertEquals(-100, reading.currentMa)
            assertEquals(null, reading.thermalStatus)
        }

    @Test
    fun `readings never reads the kernel on the collector's main thread`() {
        val mainThread = Executors.newSingleThreadExecutor { Thread(it, "main-sim") }
        try {
            val main = mainThread.asCoroutineDispatcher()
            val source = CountingSource()

            val collectedOn =
                runBlocking(main) {
                    LiveTelemetry(source, PowerCalibrator(), TestClocks())
                        .readings(periodMs = 1_500)
                        .take(1)
                        .map { Thread.currentThread().name.substringBefore(" @") }
                        .toList()
                        .single()
                }

            assertEquals("the collector stays on main", "main-sim", collectedOn)
            assertEquals(1, source.kernelThreads.size)
            assertNotEquals("kernelReading must not run on main", "main-sim", source.kernelThreads.single())
        } finally {
            mainThread.shutdownNow()
        }
    }

    @Test
    fun `one failed optional insert keeps the same collector fresh and later inserts recover`() {
        val dispatcher = StandardTestDispatcher()
        runTest(dispatcher) {
            val source = CountingSource(lightRead().copy(plugged = true), advanceClock = true)
            var insertAttempts = 0
            val persisted = mutableListOf<LiveReading>()
            val store =
                SampleStore { samples ->
                    insertAttempts++
                    if (insertAttempts == 1) error("temporary storage failure")
                    persisted += samples
                }
            val telemetry = LiveTelemetry(source, PowerCalibrator(), TestClocks(), store, dispatcher)
            val readings = mutableListOf<LiveReading>()
            val upstreamFailures = mutableListOf<Throwable>()
            // Health/Cockpit catch upstream errors and retain their collector Job after it completes.
            val collector =
                backgroundScope.launch {
                    telemetry.readings(periodMs = 1_000).catch { upstreamFailures += it }.collect { readings += it }
                }

            runCurrent()
            advanceTimeBy(2_000)
            runCurrent()

            assertTrue("a sample write error must not complete the live job", collector.isActive)
            assertTrue(upstreamFailures.isEmpty())
            assertEquals(3, source.kernelCalls)
            assertEquals(listOf(1L, 2L, 3L), readings.map { it.wallMs })
            assertEquals(3, insertAttempts)
            assertEquals(listOf(2L, 3L), persisted.map { it.wallMs })

            collector.cancelAndJoin()
            advanceTimeBy(10_000)
            runCurrent()
            assertEquals("no polling after lifecycle cancellation", 3, source.kernelCalls)

            val restarted =
                backgroundScope.launch {
                    telemetry.readings(periodMs = 1_000).collect { readings += it }
                }
            runCurrent()
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals("recollection starts exactly one poller", 5, source.kernelCalls)
            assertEquals(listOf(1L, 2L, 3L, 4L, 5L), readings.map { it.wallMs })
            assertEquals(5, insertAttempts)
            restarted.cancelAndJoin()
        }
    }

    @Test
    fun `cancellation from optional insertion propagates without another kernel read`() {
        val dispatcher = StandardTestDispatcher()
        runTest(dispatcher) {
            val source = CountingSource(lightRead().copy(plugged = true))
            val cancelled = CancellationException("sample write cancelled")
            var insertAttempts = 0
            val store =
                SampleStore {
                    insertAttempts++
                    throw cancelled
                }
            val readings = mutableListOf<LiveReading>()

            val result =
                runCatching {
                    withTimeout(5_000) {
                        LiveTelemetry(source, PowerCalibrator(), TestClocks(), store, dispatcher)
                            .readings(periodMs = 1_000)
                            .collect { readings += it }
                    }
                }

            assertPropagated(cancelled, result.exceptionOrNull())
            assertEquals(1, source.kernelCalls)
            assertEquals(1, insertAttempts)
            assertEquals(1, readings.size)
            advanceTimeBy(10_000)
            runCurrent()
            assertEquals(1, source.kernelCalls)
        }
    }

    @Test
    fun `downstream collector failure escapes emit and never reaches optional insertion`() {
        val dispatcher = StandardTestDispatcher()
        runTest(dispatcher) {
            val source = CountingSource(lightRead().copy(plugged = true))
            val failure = IllegalStateException("collector failed")
            var insertAttempts = 0
            val store = SampleStore { insertAttempts++ }

            val result =
                runCatching {
                    withTimeout(5_000) {
                        LiveTelemetry(source, PowerCalibrator(), TestClocks(), store, dispatcher)
                            .readings(periodMs = 1_000)
                            .collect { throw failure }
                    }
                }

            assertPropagated(failure, result.exceptionOrNull())
            assertEquals(1, source.kernelCalls)
            assertEquals(0, insertAttempts)
        }
    }

    @Test
    fun `source failure still propagates instead of being treated as an optional write failure`() {
        val dispatcher = StandardTestDispatcher()
        runTest(dispatcher) {
            val failure = IllegalStateException("kernel read failed")
            val source =
                object : TelemetrySource by CountingSource() {
                    override suspend fun kernelReading(): KernelRead = throw failure
                }
            val readings = mutableListOf<LiveReading>()

            val result =
                runCatching {
                    withTimeout(5_000) {
                        LiveTelemetry(source, PowerCalibrator(), TestClocks(), ioDispatcher = dispatcher)
                            .readings(periodMs = 1_000)
                            .collect { readings += it }
                    }
                }

            assertPropagated(failure, result.exceptionOrNull())
            assertTrue(readings.isEmpty())
        }
    }

    private fun assertPropagated(
        original: Throwable,
        caught: Throwable?,
    ) {
        val actual = checkNotNull(caught) { "the flow must propagate the failure" }
        assertEquals(original.javaClass, actual.javaClass)
        assertEquals(original.message, actual.message)
        // Coroutine stacktrace recovery may copy the exception, retaining the original as its cause.
        assertSame(original, generateSequence(actual) { it.cause }.last())
    }

    private fun lightRead() =
        LightRead(
            clock = SnapshotClock(1, 2, 3, 4, null, null),
            levelPct = 50,
            plugged = false,
            status = "discharging",
            temperatureDeciC = 250,
            voltageMv = 4000,
            currentMa = -100,
            chargeCounterUah = null,
        )

    /** The currents a new [LiveTelemetry] (a new process) reads from [ticks], calibrating with [calibrationStore]. */
    private suspend fun currents(
        calibrationStore: CalibrationStore,
        ticks: List<KernelRead>,
    ): List<Int?> =
        LiveTelemetry(ScriptedSource(ticks), PowerCalibrator(), TestClocks(), calibrationStore = calibrationStore)
            .readings(periodMs = 1)
            .take(ticks.size)
            .toList()
            .map { it.currentMa }

    private class MemoryCalibrationStore(
        var saved: CalibrationStart? = null,
    ) : CalibrationStore {
        override fun load(): CalibrationStart? = saved

        override fun save(start: CalibrationStart) {
            saved = start
        }
    }

    private fun tick(
        light: LightRead,
        sysfsCurrent: Long,
        bmCurrent: Int,
    ) = KernelRead(
        kernel = null,
        calibrationInput =
            CalibrationInput(
                sysfsCurrentRaw = sysfsCurrent,
                sysfsVoltageRaw = 4_000_000L,
                bmCurrentNowUa = bmCurrent,
                bmVoltageMv = 4_000,
                isCharging = light.status == "charging",
                plugged = light.plugged == true,
            ),
        light = light,
    )

    /** Returns [ticks] in order, one per kernel read, then repeats the last (the flow may read ahead of `take`). */
    private inner class ScriptedSource(
        private val ticks: List<KernelRead>,
    ) : TelemetrySource {
        private var next = 0

        override suspend fun full(
            trigger: CaptureTrigger,
            historyCursor: Pair<Long, Long>?,
        ): FullRead? = null

        override fun light(): LightRead = ticks[next.coerceAtMost(ticks.lastIndex)].light

        override suspend fun kernelReading(): KernelRead = ticks[(next++).coerceAtMost(ticks.lastIndex)]
    }

    private inner class CountingSource(
        private val light: LightRead = lightRead(),
        private val advanceClock: Boolean = false,
    ) : TelemetrySource {
        var kernelCalls = 0
        val kernelThreads = java.util.concurrent.CopyOnWriteArrayList<String>()

        override suspend fun full(
            trigger: CaptureTrigger,
            historyCursor: Pair<Long, Long>?,
        ): FullRead? = null

        override fun light(): LightRead = light

        override suspend fun kernelReading(): KernelRead {
            kernelCalls++
            kernelThreads += Thread.currentThread().name.substringBefore(" @")
            val reading = light()
            val timed =
                if (advanceClock) {
                    reading.copy(clock = reading.clock.copy(wallMs = kernelCalls.toLong(), elapsedRealtimeMs = kernelCalls.toLong()))
                } else {
                    reading
                }
            return KernelRead(kernel = null, calibrationInput = null, light = timed)
        }
    }

    /** Only the optional sample-write seam is exercised; any other store call is a test failure. */
    private class SampleStore(
        private val insertion: suspend (List<LiveReading>) -> Unit,
    ) : SnapshotStore {
        override suspend fun insertSamples(samples: List<LiveReading>) = insertion(samples)

        override suspend fun insert(snapshot: BatterySnapshot): Long = error("not under test")

        override suspend fun byId(id: Long): BatterySnapshot? = error("not under test")

        override suspend fun latest(kind: SnapshotKind?): BatterySnapshot? = error("not under test")

        override suspend fun firstAtOrAfter(
            wallMs: Long,
            kind: SnapshotKind?,
            statsStartMs: Long?,
            bootCount: Int?,
        ): BatterySnapshot? = error("not under test")

        override suspend fun lastAtOrBefore(
            wallMs: Long,
            kind: SnapshotKind?,
        ): BatterySnapshot? = error("not under test")

        override suspend fun latestByTrigger(trigger: CaptureTrigger): BatterySnapshot? = error("not under test")

        override suspend fun snapshotsBetween(
            fromWallMs: Long,
            toWallMs: Long,
        ): List<BatterySnapshot> = error("not under test")

        override suspend fun insertHistory(
            events: List<HistoryEvent>,
            statsStartMs: Long,
        ): Unit = error("not under test")

        override suspend fun historyBetween(
            fromWallMs: Long,
            toWallMs: Long,
        ): List<HistoryEvent> = error("not under test")

        override suspend fun historyCursor(): Pair<Long, Long>? = error("not under test")

        override suspend fun setHistoryCursor(
            statsStartMs: Long,
            lastHistoryTimeMs: Long,
        ): Unit = error("not under test")

        override suspend fun addMarker(
            snapshotId: Long,
            label: String,
            wallMs: Long,
        ): Long = error("not under test")

        override suspend fun marker(id: Long): Pair<Long, BatterySnapshot>? = error("not under test")

        override suspend fun markers(): List<Marker> = error("not under test")

        override suspend fun samplesBetween(
            from: Long,
            to: Long,
        ): List<TelemetrySample> = error("not under test")

        override suspend fun upsertChargeSession(
            statsStartMs: Long,
            segment: ChargeSegment,
            verdict: SegmentVerdict,
        ): Unit = error("not under test")

        override suspend fun chargeSessions(): List<ChargeSessionRecord> = error("not under test")

        override suspend fun acceptedEstimatesChronological(limit: Int): List<Double> = error("not under test")

        override suspend fun healthSamples(): List<HealthSample> = error("not under test")

        override suspend fun insertHealthSample(sample: HealthSample): Unit = error("not under test")

        override suspend fun insertToolkitAction(record: ToolkitActionRecord): Long = error("not under test")

        override suspend fun updateToolkitAction(record: ToolkitActionRecord): Unit = error("not under test")

        override suspend fun toolkitActions(limit: Int): List<ToolkitActionRecord> = error("not under test")

        override suspend fun pendingToolkitActions(): List<ToolkitActionRecord> = error("not under test")

        override suspend fun recordMiss(
            wallMs: Long,
            trigger: CaptureTrigger,
            reason: String,
        ): Unit = error("not under test")

        override suspend fun prune(nowWallMs: Long): Unit = error("not under test")
    }

    private class TestClocks : Clocks {
        override fun wallMs(): Long = 1

        override fun elapsedRealtimeMs(): Long = 2

        override fun uptimeMs(): Long = 3

        override fun bootCount(): Int = 4
    }
}
