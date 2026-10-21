package com.akane.inspiration.telemetry.capture

import com.akane.inspiration.ipc.model.BatteryHistorySnapshot
import com.akane.inspiration.ipc.model.BatteryUsageSnapshot
import com.akane.inspiration.ipc.model.HistoryEvent
import com.akane.inspiration.ipc.model.HistoryKind
import com.akane.inspiration.ipc.model.UidUsage
import com.akane.inspiration.telemetry.calibration.CalibrationLocks
import com.akane.inspiration.telemetry.calibration.CalibrationStart
import com.akane.inspiration.telemetry.calibration.CalibrationStore
import com.akane.inspiration.telemetry.common.Clocks
import com.akane.inspiration.telemetry.dump.AlarmDump
import com.akane.inspiration.telemetry.dump.AlarmFilterStat
import com.akane.inspiration.telemetry.dump.DumpParse
import com.akane.inspiration.telemetry.dump.DumpSnapshot
import com.akane.inspiration.telemetry.dump.SystemTimers
import com.akane.inspiration.telemetry.dump.UidAlarm
import com.akane.inspiration.telemetry.dump.UidDump
import com.akane.inspiration.telemetry.dump.UidSensor
import com.akane.inspiration.telemetry.snapshot.BatterySnapshot
import com.akane.inspiration.telemetry.snapshot.CaptureTrigger
import com.akane.inspiration.telemetry.snapshot.LiveReading
import com.akane.inspiration.telemetry.snapshot.SnapshotKind
import com.akane.inspiration.telemetry.snapshot.SourceAvailability
import com.akane.inspiration.telemetry.source.FullRead
import com.akane.inspiration.telemetry.source.LightRead
import com.akane.inspiration.telemetry.source.TelemetrySource
import com.akane.inspiration.telemetry.store.ChargeSessionRecord
import com.akane.inspiration.telemetry.store.HISTORY_PAGE_CAP_REASON
import com.akane.inspiration.telemetry.store.HISTORY_SOURCE
import com.akane.inspiration.telemetry.store.HealthSample
import com.akane.inspiration.telemetry.store.Marker
import com.akane.inspiration.telemetry.store.RETENTION_WINDOW_MS
import com.akane.inspiration.telemetry.store.SnapshotStore
import com.akane.inspiration.telemetry.store.TelemetrySample
import com.akane.inspiration.telemetry.store.ToolkitActionRecord
import com.akane.inspiration.telemetry.store.ingestedHistory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotCapturerTest {
    @Test
    fun `full capture maps process states and uses uid components when device aggregate is absent`() =
        runTest {
            val store = FakeStore()
            val source =
                FakeSource(
                    usage =
                        BatteryUsageSnapshot(
                            available = true,
                            reason = null,
                            statsStartMs = 10,
                            statsEndMs = 20,
                            dischargedPowerMah = 1.0,
                            batteryTimeRemainingMs = 0,
                            chargeTimeRemainingMs = 0,
                            uids =
                                listOf(
                                    UidUsage(
                                        12345,
                                        "pkg",
                                        2.0,
                                        mapOf("cpu" to 1.5),
                                        mapOf(
                                            "FOREGROUND" to 1.0,
                                            "BACKGROUND" to 0.2,
                                            "FOREGROUND_SERVICE" to 0.3,
                                            "CACHED" to 0.5,
                                        ),
                                    ),
                                ),
                        ),
                )
            val capturer = SnapshotCapturer(source, store, TestClocks())

            capturer.captureFull(CaptureTrigger.APP_OPEN, deadlineMs = 100, ingestHistory = false)

            val saved = store.snapshots.single()
            assertEquals(1.0, saved.uids.single().fgMah!!, 0.0)
            assertEquals(0.2, saved.uids.single().bgMah!!, 0.0)
            assertEquals(0.3, saved.uids.single().fgsMah!!, 0.0)
            assertEquals(0.5, saved.uids.single().cachedMah!!, 0.0)
            assertEquals(1.5, saved.deviceComponentsMah.getValue("cpu"), 0.0)
            assertEquals("device_from_uid_sum", saved.availability.getValue("usage").reason)
        }

    @Test
    fun `full capture carries dump uid sensors and system signal timers into the snapshot`() =
        runTest {
            val store = FakeStore()
            val sensors = listOf(UidSensor(-10_000, 900, 100, 2), UidSensor(7, 50, 0, 1))
            val dump =
                DumpSnapshot(
                    reportVersion = 36,
                    system =
                        SystemTimers(
                            startClockTimeMs = 10,
                            totalRealtimeMs = 0,
                            totalUptimeMs = 0,
                            batteryRealtimeMs = 0,
                            batteryUptimeMs = 0,
                            screenOffRealtimeMs = 0,
                            screenOffUptimeMs = 0,
                            screenDozeMs = 0,
                            screenOnMs = 0,
                            interactiveMs = 0,
                            deepDozeMs = 0,
                            deepDozeCount = 0,
                            lightDozeMs = 0,
                            dischargeTotalMah = 0.0,
                            dischargeScreenOffMah = 0.0,
                            chargeTimeRemainingMs = null,
                            dischargeTimeRemainingMs = null,
                            signalStrengthMs = listOf(1, 2, 3, 4, 5),
                            signalScanningMs = 6,
                            mobileRadioActiveMs = 7,
                        ),
                    kernelWakelocks = emptyList(),
                    wakeupReasons = emptyList(),
                    uids =
                        listOf(
                            UidDump(12345, emptyList(), emptyList(), 0, 0, 0, 0, sensors),
                        ),
                )
            val usage =
                BatteryUsageSnapshot(
                    available = true,
                    reason = null,
                    statsStartMs = 10,
                    statsEndMs = 20,
                    dischargedPowerMah = 1.0,
                    batteryTimeRemainingMs = 0,
                    chargeTimeRemainingMs = 0,
                    uids = listOf(UidUsage(12345, "pkg", 2.0, emptyMap(), emptyMap())),
                )
            val capturer = SnapshotCapturer(FakeSource(usage = usage, batteryStats = DumpParse.Ok(dump)), store, TestClocks())

            capturer.captureFull(CaptureTrigger.APP_OPEN, deadlineMs = 100, ingestHistory = false)

            val saved = store.snapshots.single()
            assertEquals(sensors, saved.uids.single().sensors)
            assertEquals(listOf(1L, 2L, 3L, 4L, 5L), saved.system!!.signalStrengthMs)
            assertEquals(6L, saved.system.signalScanningMs)
            assertEquals(7L, saved.system.mobileRadioActiveMs)
        }

    @Test
    fun `alarm dump wakeups are carried separately and never added to batterystats alarm counts`() =
        runTest {
            val store = FakeStore()
            val dump =
                DumpSnapshot(
                    reportVersion = 36,
                    system =
                        SystemTimers(
                            startClockTimeMs = 10,
                            totalRealtimeMs = 0,
                            totalUptimeMs = 0,
                            batteryRealtimeMs = 0,
                            batteryUptimeMs = 0,
                            screenOffRealtimeMs = 0,
                            screenOffUptimeMs = 0,
                            screenDozeMs = 0,
                            screenOnMs = 0,
                            interactiveMs = 0,
                            deepDozeMs = 0,
                            deepDozeCount = 0,
                            lightDozeMs = 0,
                            dischargeTotalMah = 0.0,
                            dischargeScreenOffMah = 0.0,
                            chargeTimeRemainingMs = null,
                            dischargeTimeRemainingMs = null,
                        ),
                    kernelWakelocks = emptyList(),
                    wakeupReasons = emptyList(),
                    uids =
                        listOf(
                            UidDump(12345, emptyList(), listOf(UidAlarm("sync", 5), UidAlarm("sync", 2)), 0, 0, 0, 0, emptyList()),
                        ),
                )
            val alarm =
                AlarmDump(
                    filterStats =
                        listOf(
                            AlarmFilterStat(12345, "pkg", "sync", count = 9, wakeupCount = 4),
                            AlarmFilterStat(12345, "pkg", "quiet", count = 3, wakeupCount = 0),
                            // A uid the dump never saw: it still gets a snapshot row for its alarm-dump wakeups.
                            AlarmFilterStat(20000, "other", "tick", count = 8, wakeupCount = 6),
                        ),
                )
            val capturer =
                SnapshotCapturer(
                    FakeSource(batteryStats = DumpParse.Ok(dump), alarm = DumpParse.Ok(alarm)),
                    store,
                    TestClocks(),
                )

            capturer.captureFull(CaptureTrigger.APP_OPEN, deadlineMs = 100, ingestHistory = false)

            val uids =
                store.snapshots
                    .single()
                    .uids
                    .associateBy { it.uid }
            val known = uids.getValue(12345)
            // Batterystats tags keep their own counts (duplicates summed); the dump's count/wakeupCount stay out.
            assertEquals(listOf(UidAlarm("sync", 7)), known.wakeupAlarms)
            assertEquals(listOf(UidAlarm("sync", 4)), known.alarmDumpWakeups)
            val dumpOnly = uids.getValue(20000)
            assertEquals(emptyList<UidAlarm>(), dumpOnly.wakeupAlarms)
            assertEquals(listOf(UidAlarm("tick", 6)), dumpOnly.alarmDumpWakeups)
        }

    @Test
    fun `full capture persists history and advances cursor`() =
        runTest {
            val store = FakeStore()
            val source =
                FakeSource(
                    history =
                        listOf(
                            page(truncated = true, historyEvent(5)),
                            page(truncated = false, historyEvent(9)),
                        ),
                )
            val capturer = SnapshotCapturer(source, store, TestClocks())

            capturer.captureFull(CaptureTrigger.APP_OPEN, deadlineMs = 100, ingestHistory = true)
            capturer.captureFull(CaptureTrigger.APP_OPEN, deadlineMs = 100, ingestHistory = true)

            assertEquals(2, store.history.size)
            assertEquals(10L to 9L, store.cursor)
            assertEquals(10L to 9L, source.receivedCursors.last())
        }

    @Test
    fun `only a capture whose every history page was read marks the history horizon`() =
        runTest {
            val complete = listOf(page(truncated = true, historyEvent(5)), page(truncated = false, historyEvent(9)))
            val partial = listOf(page(truncated = true, historyEvent(5)), BatteryHistorySnapshot.unavailable("remote"))
            val stored =
                listOf(complete, partial, emptyList()).map { pages ->
                    val store = FakeStore()
                    SnapshotCapturer(FakeSource(history = pages), store, TestClocks())
                        .captureFull(CaptureTrigger.APP_OPEN, deadlineMs = 100, ingestHistory = true)
                    store.snapshots.single()
                }
            val light = FakeStore().also { SnapshotCapturer(FakeSource(), it, TestClocks()).captureLight(CaptureTrigger.SCREEN_ON) }

            assertEquals(listOf(true, false, false), stored.map { it.ingestedHistory() })
            assertFalse(light.snapshots.single().ingestedHistory())
        }

    @Test
    fun `a read whose last page is still truncated keeps its pages but does not mark the history horizon`() =
        runTest {
            // The source stopped at its page cap with the last page still full: newer events are unread.
            val store = FakeStore()
            val capped = listOf(page(truncated = true, historyEvent(5)), page(truncated = true, historyEvent(9)))

            SnapshotCapturer(FakeSource(history = capped), store, TestClocks())
                .captureFull(CaptureTrigger.APP_OPEN, deadlineMs = 100, ingestHistory = true)

            assertEquals(listOf(historyEvent(5), historyEvent(9)), store.history)
            assertEquals("the next ingest continues the drain from the last stored event", 10L to 9L, store.cursor)
            val snapshot = store.snapshots.single()
            assertEquals(SourceAvailability(true, HISTORY_PAGE_CAP_REASON), snapshot.availability[HISTORY_SOURCE])
            assertFalse(snapshot.ingestedHistory())
        }

    @Test
    fun `an ingest's history is complete only when every page was read up to the newest event`() =
        runTest {
            val complete = listOf(page(truncated = true, historyEvent(5)), page(truncated = false, historyEvent(9)))
            val capped = listOf(page(truncated = true, historyEvent(5)), page(truncated = true, historyEvent(9)))
            val failedLater = listOf(page(truncated = true, historyEvent(5)), BatteryHistorySnapshot.unavailable("remote"))
            val failedWithoutReason =
                listOf(page(truncated = true, historyEvent(5)), BatteryHistorySnapshot.unavailable("remote").copy(reason = null))

            val ingests =
                listOf(complete, capped, failedLater, failedWithoutReason, emptyList()).map { pages ->
                    SnapshotCapturer(FakeSource(history = pages), FakeStore(), TestClocks())
                        .ingestHistory(CaptureTrigger.CHARGE_JOB, deadlineMs = 100)
                }

            assertEquals(listOf(true, false, false, false, false), ingests.map { it.historyComplete })
            val unexplained = FakeStore()
            SnapshotCapturer(FakeSource(history = failedWithoutReason), unexplained, TestClocks())
                .captureFull(CaptureTrigger.APP_OPEN, deadlineMs = 100, ingestHistory = true)
            assertFalse(
                "a failed page with no reason still leaves the read incomplete",
                unexplained.snapshots.single().ingestedHistory(),
            )
        }

    @Test
    fun `session restart reingestion stores only new history events`() =
        runTest {
            val old = historyEvent(5)
            val new = historyEvent(9)
            val store = FakeStore()
            val source =
                FakeSource(
                    histories =
                        listOf(
                            listOf(page(truncated = false, old, statsStartMs = 10L)),
                            listOf(page(truncated = false, old, new, statsStartMs = 20L, sessionChanged = true)),
                        ),
                )
            val capturer = SnapshotCapturer(source, store, TestClocks())

            capturer.captureFull(CaptureTrigger.APP_OPEN, deadlineMs = 100, ingestHistory = true)
            capturer.captureFull(CaptureTrigger.APP_OPEN, deadlineMs = 100, ingestHistory = true)

            assertEquals(listOf(old, new), store.history)
            assertEquals(20L to 9L, store.cursor)
            assertEquals(10L to 5L, source.receivedCursors.last())
        }

    @Test
    fun `a relabelled re-page does not bring back history the retention already pruned`() =
        runTest {
            val nowMs = 400L * DAY_MS
            val cutoffMs = nowMs - RETENTION_WINDOW_MS
            // A charge that started before the cutoff and is still running: the prune deleted its PLUGGED row and
            // kept the rest. Re-inserted, that PLUGGED would open the new run and pair with its first UNPLUGGED.
            val plugged = HistoryEvent(100, cutoffMs - 1, HistoryKind.PLUGGED, 40, 1_000_000, 250, 4000, 1)
            val level = HistoryEvent(200, cutoffMs, HistoryKind.LEVEL, 60, 1_500_000, 250, 4100, 1)
            val unplugged = HistoryEvent(300, nowMs - 1, HistoryKind.UNPLUGGED, 90, 2_000_000, 250, 4200, 0)
            val store = FakeStore()
            store.insertHistory(listOf(level), statsStartMs = 10L)
            store.cursor = 10L to 200L
            val source =
                FakeSource(
                    history = listOf(page(truncated = false, plugged, level, unplugged, statsStartMs = 20L, sessionChanged = true)),
                )

            SnapshotCapturer(source, store, TestClocks(wallMs = nowMs))
                .captureFull(CaptureTrigger.APP_OPEN, deadlineMs = 100, ingestHistory = true)

            assertEquals(listOf(level, unplugged), store.history)
            assertEquals(20L to 300L, store.cursor)
        }

    @Test
    fun `the ingest measures the retention in the prune's boot frame after a forward clock jump`() =
        runTest {
            val realNowMs = 400L * DAY_MS
            val elapsedMs = 10L * DAY_MS
            // This boot's earliest snapshot puts "now" at realNowMs; the wall clock has since jumped a year ahead.
            val store = FakeStore().apply { minClockOffsetMs = realNowMs - elapsedMs }
            val keptByPrune = historyEvent(5).copy(wallMs = realNowMs - RETENTION_WINDOW_MS)
            val prunedByPrune = historyEvent(6).copy(wallMs = realNowMs - RETENTION_WINDOW_MS - 1)
            val source = FakeSource(history = listOf(page(truncated = false, prunedByPrune, keptByPrune)))
            val clocks = TestClocks(wallMs = realNowMs + 365L * DAY_MS, elapsedMs = elapsedMs)

            SnapshotCapturer(source, store, clocks).ingestHistory(CaptureTrigger.APP_OPEN, deadlineMs = 100)

            assertEquals(listOf(keptByPrune), store.history)
            assertEquals(10L to 5L, store.cursor)
        }

    @Test
    fun `each available history page goes to the store once, unfiltered, with its session, truncation and cutoff`() =
        runTest {
            val nowMs = 400L * DAY_MS
            val cutoffMs = nowMs - RETENTION_WINDOW_MS
            val pruned = historyEvent(5).copy(wallMs = cutoffMs - 1)
            val kept = historyEvent(9).copy(wallMs = cutoffMs)
            val store = FakeStore()
            val pages =
                listOf(
                    page(truncated = true, pruned, statsStartMs = 20L),
                    BatteryHistorySnapshot.unavailable("remote"),
                    page(truncated = false, kept, statsStartMs = 20L),
                )

            SnapshotCapturer(FakeSource(history = pages), store, TestClocks(wallMs = nowMs))
                .ingestHistory(CaptureTrigger.APP_OPEN, deadlineMs = 100)

            assertEquals(
                listOf(PageCall(20L, listOf(pruned), true, cutoffMs), PageCall(20L, listOf(kept), false, cutoffMs)),
                store.pageCalls,
            )
            assertEquals(listOf(kept), store.history)
        }

    @Test
    fun `the default page write filters on the cutoff, inserts, moves the cursor and returns the kept count`() =
        runTest {
            val store = FakeStore()
            val below = historyEvent(5).copy(wallMs = 99)
            val atCutoff = historyEvent(6).copy(wallMs = 100)
            val above = historyEvent(7).copy(wallMs = 101)

            val kept = store.persistHistoryPage(30L, listOf(below, atCutoff, above), truncated = false, cutoffWallMs = 100)
            val keptNone = store.persistHistoryPage(30L, listOf(historyEvent(8).copy(wallMs = 1)), truncated = true, cutoffWallMs = 100)

            assertEquals(2, kept)
            assertEquals(0, keptNone)
            assertEquals(listOf(atCutoff, above), store.history)
            assertEquals("the cursor follows the last event, kept or not", 30L to 8L, store.cursor)
        }

    @Test
    fun `overlapping history ingests run one at a time, each from the cursor the last one left`() =
        runTest {
            val store = FakeStore()
            val gate = CompletableDeferred<Unit>()
            val source =
                object : FakeSource(
                    histories =
                        listOf(
                            listOf(page(truncated = false, historyEvent(100))),
                            listOf(page(truncated = false, historyEvent(200))),
                        ),
                ) {
                    override suspend fun full(
                        trigger: CaptureTrigger,
                        historyCursor: Pair<Long, Long>?,
                    ): FullRead {
                        val first = receivedCursors.isEmpty()
                        val read = super.full(trigger, historyCursor)
                        if (first) gate.await() // e.g. the app-open refresh, stalled in a slow dump
                        return read
                    }
                }
            val capturer = SnapshotCapturer(source, store, TestClocks())

            val slow = async { capturer.ingestHistory(CaptureTrigger.APP_OPEN, deadlineMs = 10_000) }
            runCurrent()
            val fast = async { capturer.ingestHistory(CaptureTrigger.CHARGE_JOB, deadlineMs = 10_000) }
            runCurrent()
            assertEquals("the second ingest waits for the first", 1, source.receivedCursors.size)

            gate.complete(Unit)
            slow.await()
            fast.await()

            assertEquals(listOf(0L to 0L, 10L to 100L), source.receivedCursors)
            assertEquals(10L to 200L, store.cursor)
        }

    @Test
    fun `full capture deadline records miss`() =
        runTest {
            val store = FakeStore()
            val source =
                object : FakeSource() {
                    override suspend fun full(
                        trigger: CaptureTrigger,
                        historyCursor: Pair<Long, Long>?,
                    ): FullRead {
                        delay(100)
                        return super.full(trigger, historyCursor)
                    }
                }

            val result =
                SnapshotCapturer(source, store, TestClocks()).captureFull(
                    CaptureTrigger.MARKER,
                    deadlineMs = 1,
                    ingestHistory = false,
                )

            assertTrue(result is CaptureResult.Missed)
            assertEquals(CaptureTrigger.MARKER, store.misses.single().first)
        }

    @Test
    fun `a miss is recorded even when the caller's guard with the same deadline already fired`() =
        runTest {
            val store = FakeStore()
            val source =
                object : FakeSource() {
                    override suspend fun full(
                        trigger: CaptureTrigger,
                        historyCursor: Pair<Long, Long>?,
                    ): FullRead {
                        delay(5_000)
                        return super.full(trigger, historyCursor)
                    }
                }

            withTimeoutOrNull(2_000) {
                SnapshotCapturer(source, store, TestClocks()).captureFull(
                    CaptureTrigger.POWER_CONNECTED,
                    deadlineMs = 2_000,
                    ingestHistory = false,
                )
            }

            assertEquals(CaptureTrigger.POWER_CONNECTED, store.misses.single().first)
        }

    @Test
    fun `history ingest persists history without saving a capture snapshot`() =
        runTest {
            val store = FakeStore()
            val capturer =
                SnapshotCapturer(
                    FakeSource(history = listOf(page(truncated = false, historyEvent(9)))),
                    store,
                    TestClocks(),
                )

            val ingest = capturer.ingestHistory(CaptureTrigger.CHARGE_JOB, deadlineMs = 100)

            assertEquals(listOf(historyEvent(9)), store.history)
            assertTrue(ingest.historyComplete)
            assertTrue(store.snapshots.isEmpty())
        }

    @Test
    fun `light and full captures store the light current calibrated by the persisted calibration`() =
        runTest {
            for (bmPositive in listOf(true, false)) {
                val settled = calibrationStore(CalibrationLocks(
                    batteryManagerMilliamps = false,
                    dischargePositive = !bmPositive,
                    batteryManagerDischargePositive = bmPositive,
                ))
                val store = FakeStore()
                val rawCurrent = if (bmPositive) 300 else -300
                val capturer = SnapshotCapturer(FakeSource(lightCurrentMa = rawCurrent), store, TestClocks(), settled)

                capturer.captureLight(CaptureTrigger.SCREEN_ON)
                capturer.captureFull(CaptureTrigger.APP_OPEN, deadlineMs = 100, ingestHistory = false)

                assertEquals(listOf<Int?>(-300, -300), store.snapshots.map { it.battery.currentMa })
                assertEquals(listOf(SnapshotKind.LIGHT, SnapshotKind.FULL), store.snapshots.map { it.kind })
            }
        }

    @Test
    fun `a light current whose unit is not settled is not stored`() =
        runTest {
            val unsettled = calibrationStore(CalibrationLocks(batteryManagerDischargePositive = false))
            val store = FakeStore()
            val capturer = SnapshotCapturer(FakeSource(lightCurrentMa = 300), store, TestClocks(), unsettled)

            capturer.captureLight(CaptureTrigger.SCREEN_ON)
            capturer.captureFull(CaptureTrigger.APP_OPEN, deadlineMs = 100, ingestHistory = false)

            assertEquals(listOf<Int?>(null, null), store.snapshots.map { it.battery.currentMa })
        }

    @Test
    fun `a sysfs sign alone cannot supply current for light or full captures`() =
        runTest {
            val sysfsOnly = calibrationStore(CalibrationLocks(batteryManagerMilliamps = false, dischargePositive = true))
            val store = FakeStore()
            val capturer = SnapshotCapturer(FakeSource(lightCurrentMa = -300), store, TestClocks(), sysfsOnly)
            capturer.captureLight(CaptureTrigger.SCREEN_ON)
            capturer.captureFull(CaptureTrigger.APP_OPEN, deadlineMs = 100, ingestHistory = false)
            assertEquals(listOf<Int?>(null, null), store.snapshots.map { it.battery.currentMa })
        }

    @Test
    fun `without a calibration store the light current is stored as read`() =
        runTest {
            val store = FakeStore()
            SnapshotCapturer(FakeSource(lightCurrentMa = 300), store, TestClocks()).captureLight(CaptureTrigger.SCREEN_ON)

            assertEquals(300, store.snapshots.single().battery.currentMa)
        }

    @Test
    fun `a BOOT capture that continues the newest capture's boot is stored as a stopped-state exit`() =
        runTest {
            // Android 15 sends BOOT_COMPLETED when a force-stopped app leaves the stopped state: no reboot between.
            val store = FakeStore()
            SnapshotCapturer(FakeSource(lightClock = clock(elapsedMs = 60_000, bootCount = 7)), store, TestClocks())
                .captureLight(CaptureTrigger.SCREEN_OFF)

            SnapshotCapturer(FakeSource(lightClock = clock(elapsedMs = 90_000, bootCount = 7)), store, TestClocks())
                .captureLight(CaptureTrigger.BOOT)

            assertEquals(listOf(CaptureTrigger.SCREEN_OFF, CaptureTrigger.STOPPED_STATE_EXIT), store.snapshots.map { it.trigger })
        }

    @Test
    fun `a BOOT capture after a reboot stays BOOT`() =
        runTest {
            val afterOtherBootCount = clock(elapsedMs = 90_000, bootCount = 8)
            // BOOT_COUNT unreadable on both sides: elapsedRealtime running backwards is the reboot.
            val afterElapsedReset = clock(elapsedMs = 30_000, bootCount = 0)
            val previous = listOf(clock(elapsedMs = 60_000, bootCount = 7), clock(elapsedMs = 60_000, bootCount = 0))

            for ((before, after) in previous.zip(listOf(afterOtherBootCount, afterElapsedReset))) {
                val store = FakeStore()
                SnapshotCapturer(FakeSource(lightClock = before), store, TestClocks()).captureLight(CaptureTrigger.SCREEN_OFF)
                SnapshotCapturer(FakeSource(lightClock = after), store, TestClocks()).captureLight(CaptureTrigger.BOOT)

                assertEquals(CaptureTrigger.BOOT, store.snapshots.last().trigger)
            }
        }

    @Test
    fun `a light reading taken earlier is stored with its own clock, not the source's current one`() =
        runTest {
            val earlier = SnapshotCapturer(FakeSource(lightClock = clock(elapsedMs = 10_000, bootCount = 7)), FakeStore(), TestClocks()).lightReading()
            val store = FakeStore()
            SnapshotCapturer(FakeSource(lightClock = clock(elapsedMs = 16_000, bootCount = 7)), store, TestClocks())
                .captureLight(CaptureTrigger.SCREEN_OFF, earlier)

            assertEquals(SnapshotKind.LIGHT, store.snapshots.single().kind)
            assertEquals(earlier.clock, store.snapshots.single().clock)
        }

    @Test
    fun `a stopped-state BOOT compares with the last stored capture even when an earlier boot's clock ran ahead`() =
        runTest {
            // Boot 7 ran with its wall clock an hour ahead; boot 8 has the right time, so its rows sort before boot 7's
            // by wall time. The BOOT_COMPLETED of a stopped-state exit in boot 8 continues boot 8's last capture.
            val store = FakeStore()
            val previousBoot = clock(elapsedMs = 5_000_000, bootCount = 7, wallMs = 10 * HOUR_MS)
            val thisBoot = clock(elapsedMs = 100, bootCount = 8, wallMs = 9 * HOUR_MS)
            SnapshotCapturer(FakeSource(lightClock = previousBoot), store, TestClocks()).captureLight(CaptureTrigger.SCREEN_OFF)
            SnapshotCapturer(FakeSource(lightClock = thisBoot), store, TestClocks()).captureLight(CaptureTrigger.SCREEN_ON)

            val stoppedStateExit = clock(elapsedMs = 200, bootCount = 8, wallMs = 9 * HOUR_MS + 100)
            SnapshotCapturer(FakeSource(lightClock = stoppedStateExit), store, TestClocks()).captureLight(CaptureTrigger.BOOT)

            assertEquals(CaptureTrigger.STOPPED_STATE_EXIT, store.snapshots.last().trigger)
        }

    @Test
    fun `a BOOT capture with nothing stored before it stays BOOT`() =
        runTest {
            val store = FakeStore()
            SnapshotCapturer(FakeSource(), store, TestClocks()).captureLight(CaptureTrigger.BOOT)

            assertEquals(CaptureTrigger.BOOT, store.snapshots.single().trigger)
        }

    @Test
    fun `only a BOOT capture is relabelled`() =
        runTest {
            val store = FakeStore()
            val capturer = SnapshotCapturer(FakeSource(), store, TestClocks())

            capturer.captureLight(CaptureTrigger.SCREEN_OFF)
            capturer.captureLight(CaptureTrigger.PACKAGE_REPLACED)

            assertEquals(listOf(CaptureTrigger.SCREEN_OFF, CaptureTrigger.PACKAGE_REPLACED), store.snapshots.map { it.trigger })
        }

    private fun clock(
        elapsedMs: Long,
        bootCount: Int,
        wallMs: Long = 1_000_000 + elapsedMs,
    ) = com.akane.inspiration.telemetry.snapshot.SnapshotClock(
        wallMs = wallMs,
        elapsedRealtimeMs = elapsedMs,
        uptimeMs = elapsedMs,
        bootCount = bootCount,
        statsStartMs = null,
        statsDurationMs = null,
    )

    private fun calibrationStore(locks: CalibrationLocks) =
        object : CalibrationStore {
            override fun load(): CalibrationStart = CalibrationStart(locks)

            override fun save(start: CalibrationStart) = Unit
        }

    @Test
    fun `capturing an ingested read saves its snapshot without a second source read`() =
        runTest {
            val store = FakeStore()
            val source = FakeSource(history = listOf(page(truncated = false, historyEvent(9))))
            val capturer = SnapshotCapturer(source, store, TestClocks())

            val result = capturer.captureFull(capturer.ingestHistory(CaptureTrigger.CHARGE_JOB, deadlineMs = 100))

            assertEquals(1, source.receivedCursors.size)
            assertEquals(CaptureResult.Saved(1, SnapshotKind.FULL), result)
            assertEquals(CaptureTrigger.CHARGE_JOB, store.snapshots.single().trigger)
            assertEquals(listOf(historyEvent(9)), store.history)
        }

    @Test
    fun `capturing an ingest whose read timed out records a miss without retrying`() =
        runTest {
            val store = FakeStore()
            val source =
                object : FakeSource() {
                    override suspend fun full(
                        trigger: CaptureTrigger,
                        historyCursor: Pair<Long, Long>?,
                    ): FullRead {
                        receivedCursors += historyCursor
                        delay(1_000)
                        return super.full(trigger, historyCursor)
                    }
                }
            val capturer = SnapshotCapturer(source, store, TestClocks())

            val ingest = capturer.ingestHistory(CaptureTrigger.CHARGE_JOB, deadlineMs = 100)
            val result = capturer.captureFull(ingest)

            assertEquals(false, ingest.historyComplete)
            assertEquals(CaptureResult.Missed("deadline_or_service_unavailable"), result)
            assertEquals(1, source.receivedCursors.size)
            assertEquals(listOf(CaptureTrigger.CHARGE_JOB to "deadline_or_service_unavailable"), store.misses)
            assertTrue(store.snapshots.isEmpty())
        }

    private open class FakeSource(
        private val history: List<BatteryHistorySnapshot> = emptyList(),
        private val histories: List<List<BatteryHistorySnapshot>> = emptyList(),
        private val usage: BatteryUsageSnapshot = BatteryUsageSnapshot.unavailable("not_needed"),
        private val batteryStats: DumpParse<DumpSnapshot> = DumpParse.Unavailable("not_needed"),
        private val alarm: DumpParse<AlarmDump> = DumpParse.Unavailable("not_needed"),
        private val lightCurrentMa: Int? = -100,
        private val lightClock: com.akane.inspiration.telemetry.snapshot.SnapshotClock =
            com.akane.inspiration.telemetry.snapshot
                .SnapshotClock(1, 2, 3, 4, null, null),
    ) : TelemetrySource {
        val receivedCursors = mutableListOf<Pair<Long, Long>?>()
        private var fullReadCount = 0

        override suspend fun full(
            trigger: CaptureTrigger,
            historyCursor: Pair<Long, Long>?,
        ): FullRead {
            receivedCursors += historyCursor
            val pageSet = histories.getOrElse(fullReadCount++) { history }
            return FullRead(
                light = light(),
                usage = usage,
                batteryStats = batteryStats,
                alarm = alarm,
                kernel = null,
                cpuTimes = emptyList(),
                history = pageSet,
            )
        }

        override fun light(): LightRead =
            LightRead(
                clock = lightClock,
                levelPct = 70,
                plugged = false,
                status = "discharging",
                temperatureDeciC = 250,
                voltageMv = 4000,
                currentMa = lightCurrentMa,
                chargeCounterUah = 1_000_000,
            )
    }

    private class FakeStore : SnapshotStore {
        var cursor: Pair<Long, Long>? = null
        var minClockOffsetMs: Long? = null
        val history = mutableListOf<HistoryEvent>()
        val pageCalls = mutableListOf<PageCall>()
        private val historyKeys = mutableSetOf<Triple<Long, Int, Long>>()
        val misses = mutableListOf<Pair<CaptureTrigger, String>>()
        val snapshots = mutableListOf<BatterySnapshot>()

        override suspend fun insert(snapshot: BatterySnapshot): Long {
            snapshots += snapshot
            return snapshots.size.toLong()
        }

        override suspend fun byId(id: Long): BatterySnapshot? = null

        override suspend fun latest(kind: SnapshotKind?): BatterySnapshot? =
            snapshots.filter { kind == null || it.kind == kind }.maxByOrNull { it.clock.wallMs }

        override suspend fun latestClock() = snapshots.lastOrNull()?.clock

        override suspend fun firstAtOrAfter(
            wallMs: Long,
            kind: SnapshotKind?,
            statsStartMs: Long?,
            bootCount: Int?,
        ): BatterySnapshot? = null

        override suspend fun lastAtOrBefore(
            wallMs: Long,
            kind: SnapshotKind?,
        ): BatterySnapshot? = null

        override suspend fun latestByTrigger(trigger: CaptureTrigger): BatterySnapshot? = null

        override suspend fun snapshotsBetween(
            fromWallMs: Long,
            toWallMs: Long,
        ): List<BatterySnapshot> = emptyList()

        override suspend fun insertHistory(
            events: List<HistoryEvent>,
            statsStartMs: Long,
        ) {
            events.forEach { event ->
                if (historyKeys.add(Triple(event.historyTimeMs, event.kind, event.wallMs))) {
                    history += event
                }
            }
        }

        override suspend fun historyBetween(
            fromWallMs: Long,
            toWallMs: Long,
        ): List<HistoryEvent> = history

        override suspend fun persistHistoryPage(
            statsStartMs: Long,
            events: List<HistoryEvent>,
            truncated: Boolean,
            cutoffWallMs: Long,
        ): Int {
            pageCalls += PageCall(statsStartMs, events, truncated, cutoffWallMs)
            return super.persistHistoryPage(statsStartMs, events, truncated, cutoffWallMs)
        }

        override suspend fun historyCursor(): Pair<Long, Long>? = cursor

        override suspend fun setHistoryCursor(
            statsStartMs: Long,
            lastHistoryTimeMs: Long,
        ) {
            cursor = statsStartMs to lastHistoryTimeMs
        }

        override suspend fun addMarker(
            snapshotId: Long,
            label: String,
            wallMs: Long,
        ): Long = 0

        override suspend fun marker(id: Long): Pair<Long, BatterySnapshot>? = null

        override suspend fun insertSamples(samples: List<LiveReading>) = Unit

        override suspend fun upsertChargeSession(
            statsStartMs: Long,
            segment: com.akane.inspiration.telemetry.capacity.ChargeSegment,
            verdict: com.akane.inspiration.telemetry.capacity.SegmentVerdict,
        ) = Unit

        override suspend fun acceptedEstimatesChronological(limit: Int): List<Double> = emptyList()

        override suspend fun recordMiss(
            wallMs: Long,
            trigger: CaptureTrigger,
            reason: String,
        ) {
            yield() // A Room write is a cancellation point.
            misses += trigger to reason
        }

        override suspend fun minClockOffsetMs(bootCount: Int): Long? = minClockOffsetMs

        override suspend fun prune(nowWallMs: Long) = Unit

        override suspend fun markers(): List<Marker> = emptyList()

        override suspend fun samplesBetween(
            from: Long,
            to: Long,
        ): List<TelemetrySample> = emptyList()

        override suspend fun chargeSessions(): List<ChargeSessionRecord> = emptyList()

        override suspend fun healthSamples(): List<HealthSample> = emptyList()

        override suspend fun insertHealthSample(sample: HealthSample) = Unit

        override suspend fun insertToolkitAction(record: ToolkitActionRecord): Long = 0

        override suspend fun updateToolkitAction(record: ToolkitActionRecord) = Unit

        override suspend fun toolkitActions(limit: Int): List<ToolkitActionRecord> = emptyList()

        override suspend fun pendingToolkitActions(): List<ToolkitActionRecord> = emptyList()
    }

    private data class PageCall(
        val statsStartMs: Long,
        val events: List<HistoryEvent>,
        val truncated: Boolean,
        val cutoffWallMs: Long,
    )

    private class TestClocks(
        private val wallMs: Long = 1L,
        private val elapsedMs: Long = 2L,
    ) : Clocks {
        override fun wallMs(): Long = wallMs

        override fun elapsedRealtimeMs(): Long = elapsedMs

        override fun uptimeMs(): Long = 3L

        override fun bootCount(): Int = 4
    }

    private fun page(
        truncated: Boolean,
        vararg events: HistoryEvent,
        statsStartMs: Long = 10L,
        sessionChanged: Boolean = false,
    ) = BatteryHistorySnapshot(
        available = true,
        reason = null,
        statsStartMs = statsStartMs,
        sessionChanged = sessionChanged,
        events = events.toList(),
        truncated = truncated,
    )

    private fun historyEvent(time: Long) = HistoryEvent(time, time, HistoryKind.LEVEL, 70, 1_000_000, 250, 4000, 0)

    private companion object {
        const val HOUR_MS = 60L * 60L * 1_000L
        const val DAY_MS = 24L * HOUR_MS
    }
}
