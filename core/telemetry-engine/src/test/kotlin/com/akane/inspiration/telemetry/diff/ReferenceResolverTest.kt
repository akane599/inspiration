package com.akane.inspiration.telemetry.diff

import com.akane.inspiration.ipc.model.HistoryEvent
import com.akane.inspiration.ipc.model.HistoryKind
import com.akane.inspiration.telemetry.capacity.ChargeSegment
import com.akane.inspiration.telemetry.capacity.SegmentVerdict
import com.akane.inspiration.telemetry.snapshot.BatterySnapshot
import com.akane.inspiration.telemetry.snapshot.BatteryState
import com.akane.inspiration.telemetry.snapshot.CaptureTrigger
import com.akane.inspiration.telemetry.snapshot.LiveReading
import com.akane.inspiration.telemetry.snapshot.SnapshotClock
import com.akane.inspiration.telemetry.snapshot.SnapshotKind
import com.akane.inspiration.telemetry.snapshot.SourceAvailability
import com.akane.inspiration.telemetry.store.ChargeSessionRecord
import com.akane.inspiration.telemetry.store.HISTORY_SOURCE
import com.akane.inspiration.telemetry.store.HealthSample
import com.akane.inspiration.telemetry.store.Marker
import com.akane.inspiration.telemetry.store.SnapshotStore
import com.akane.inspiration.telemetry.store.TelemetrySample
import com.akane.inspiration.telemetry.store.ToolkitActionRecord
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every [SnapshotStore] read that returns a [BatterySnapshot] is a full load in the Room store (the snapshot row
 * plus its child tables), so a resolution should load only `ref` and `end` and read other capture times through
 * the clock-only [SnapshotStore.latestClockByTrigger].
 */
class ReferenceResolverTest {
    @Test
    fun `last 24h loads only the reference and the end snapshot`() =
        runTest {
            val store =
                LoadCountingStore(
                    listOf(snapshot(NOW - 20 * HOUR_MS, CaptureTrigger.APP_OPEN), snapshot(NOW - 1_000L, CaptureTrigger.APP_OPEN)),
                )

            val resolved = ReferenceResolver(store).resolve(ReferenceKind.Last24h, NOW)!!

            assertEquals(NOW - 1_000L, resolved.now.clock.wallMs)
            assertEquals(listOf("lastAtOrBefore", "firstAtOrAfter"), store.loads)
        }

    @Test
    fun `since unplugged without history reads the disconnect time without loading that snapshot`() =
        runTest {
            val store =
                LoadCountingStore(
                    listOf(
                        snapshot(NOW - 5 * HOUR_MS, CaptureTrigger.POWER_DISCONNECTED),
                        snapshot(NOW - 1_000L, CaptureTrigger.APP_OPEN),
                    ),
                )

            val resolved = ReferenceResolver(store).resolve(ReferenceKind.SinceUnplugged, NOW, emptyList())!!

            assertEquals(NOW - 5 * HOUR_MS, resolved.boundaryFromWallMs)
            assertEquals(listOf("lastAtOrBefore", "firstAtOrAfter"), store.loads)
        }

    @Test
    fun `screen off without history reads both capture times without loading them`() =
        runTest {
            val store =
                LoadCountingStore(
                    listOf(
                        snapshot(NOW - 3 * HOUR_MS, CaptureTrigger.SCREEN_OFF),
                        snapshot(NOW - 2 * HOUR_MS, CaptureTrigger.SCREEN_ON),
                        snapshot(NOW - 1_000L, CaptureTrigger.APP_OPEN),
                    ),
                )

            val resolved = ReferenceResolver(store).resolve(ReferenceKind.SinceScreenOff, NOW, emptyList())!!

            assertEquals(NOW - 3 * HOUR_MS, resolved.boundaryFromWallMs)
            assertEquals(NOW - 2 * HOUR_MS, resolved.now.clock.wallMs)
            assertEquals(listOf("lastAtOrBefore", "firstAtOrAfter"), store.loads)
        }

    @Test
    fun `a boundary past now still needs a snapshot at or before now`() =
        runTest {
            val slice = ReferenceKind.TimeSlice(NOW - HOUR_MS, NOW + HOUR_MS)
            val after = snapshot(NOW + 1_000L, CaptureTrigger.APP_OPEN)

            assertNull(ReferenceResolver(LoadCountingStore(listOf(after))).resolve(slice, NOW, emptyList()))

            val before = snapshot(NOW - 1_000L, CaptureTrigger.APP_OPEN)
            val resolved = ReferenceResolver(LoadCountingStore(listOf(before, after))).resolve(slice, NOW, emptyList())!!
            assertEquals(before, resolved.ref)
            assertEquals(after, resolved.now)
        }

    @Test
    fun `the end snapshot alone after the boundary is never paired with itself`() =
        runTest {
            val end = snapshot(NOW - 1_000L, CaptureTrigger.APP_OPEN)
            val resolver = ReferenceResolver(LoadCountingStore(listOf(snapshot(NOW - 30 * HOUR_MS, CaptureTrigger.APP_OPEN), end)))

            assertNull(resolver.resolve(ReferenceKind.Last24h, NOW, emptyList()))
        }

    @Test
    fun `a full end falls back to the earlier light unplug anchor without per-uid data`() =
        runTest {
            val unplug = snapshot(NOW - 5 * HOUR_MS, CaptureTrigger.POWER_DISCONNECTED, SnapshotKind.LIGHT)
            val end = snapshot(NOW - 1_000L, CaptureTrigger.APP_OPEN)

            val resolved =
                ReferenceResolver(
                    LoadCountingStore(listOf(unplug, end)),
                ).resolve(ReferenceKind.SinceUnplugged, NOW, emptyList())!!

            assertEquals(unplug, resolved.ref)
            assertEquals(end, resolved.now)
            assertFalse(resolved.perUidAvailable)
            assertEquals(unplug.clock.wallMs, resolved.coveredFromWallMs)
        }

    @Test
    fun `light only snapshots resolve against an earlier light row or not at all`() =
        runTest {
            val unplug = snapshot(NOW - 5 * HOUR_MS, CaptureTrigger.POWER_DISCONNECTED, SnapshotKind.LIGHT)

            assertNull(ReferenceResolver(LoadCountingStore(listOf(unplug))).resolve(ReferenceKind.SinceUnplugged, NOW, emptyList()))

            val screenOn = snapshot(NOW - HOUR_MS, CaptureTrigger.SCREEN_ON, SnapshotKind.LIGHT)
            val resolved =
                ReferenceResolver(LoadCountingStore(listOf(unplug, screenOn))).resolve(ReferenceKind.SinceUnplugged, NOW, emptyList())!!
            assertEquals(unplug, resolved.ref)
            assertEquals(screenOn, resolved.now)
            assertFalse(resolved.perUidAvailable)
        }

    @Test
    fun `the full reference lookup keeps a stats start stepped within the session tolerance`() =
        runTest {
            // A wall-clock step shifts the reported stats start; the store matches the end's session within
            // BatteryStatsSession.START_TOLERANCE_MS, so the earlier row just past it is a different session.
            val outside = STATS_START + BatteryStatsSession.START_TOLERANCE_MS + 1
            val otherSession = snapshot(NOW - 20 * HOUR_MS, CaptureTrigger.APP_OPEN, statsStartMs = outside)
            val stepped = snapshot(NOW - 19 * HOUR_MS, CaptureTrigger.APP_OPEN, statsStartMs = STATS_START + 1_500L)
            val end = snapshot(NOW - 1_000L, CaptureTrigger.APP_OPEN)
            val store = LoadCountingStore(listOf(otherSession, stepped, end))

            val resolved = ReferenceResolver(store).resolve(ReferenceKind.Last24h, NOW, emptyList())!!

            assertEquals(stepped, resolved.ref)
            assertEquals(end, resolved.now)
            assertTrue(resolved.perUidAvailable)
            assertEquals(listOf("lastAtOrBefore", "firstAtOrAfter"), store.loads)
        }

    /*
     * Captures land after the history event they record: the broadcast is posted once the framework has noted
     * the event, then delivered and read (redroid: SCREEN_OFF capture +26 ms, SCREEN_ON capture +1 ms). The
     * cases below offset every capture from its history time instead of placing it exactly on it.
     */

    @Test
    fun `since screen off ends at the screen-on capture that lands just after the history event`() =
        runTest {
            // The redroid store of audit N-02: a charge job ran during the screen-off period.
            val off = NOW - 45_000L
            val on = NOW - 5_600L
            val offCapture = snapshot(off + 26, CaptureTrigger.SCREEN_OFF, SnapshotKind.LIGHT)
            val chargeJob = snapshot(off + 36, CaptureTrigger.CHARGE_JOB)
            val onCapture = snapshot(on + 1, CaptureTrigger.SCREEN_ON, SnapshotKind.LIGHT)
            val appOpen = snapshot(on + 3, CaptureTrigger.APP_OPEN)
            val store = LoadCountingStore(listOf(offCapture, chargeJob, onCapture, appOpen))
            val events = listOf(history(off, HistoryKind.SCREEN_OFF), history(on, HistoryKind.SCREEN_ON))

            val resolved = ReferenceResolver(store).resolve(ReferenceKind.SinceScreenOff, NOW, events)!!

            assertEquals(off, resolved.boundaryFromWallMs)
            assertEquals(on + 1, resolved.boundaryToWallMs)
            assertEquals(chargeJob, resolved.ref)
            assertEquals(onCapture, resolved.now)
            assertEquals(on - off - 35, resolved.coveredToWallMs - resolved.coveredFromWallMs)
        }

    @Test
    fun `since screen off resolves from the broadcast captures when they lag the history by seconds`() =
        runTest {
            val off = NOW - 3 * HOUR_MS
            val on = NOW - HOUR_MS
            val offCapture = snapshot(off + 2_000L, CaptureTrigger.SCREEN_OFF, SnapshotKind.LIGHT)
            val onCapture = snapshot(on + 1_500L, CaptureTrigger.SCREEN_ON, SnapshotKind.LIGHT)
            val store = LoadCountingStore(listOf(offCapture, onCapture, snapshot(NOW - 1_000L, CaptureTrigger.APP_OPEN)))
            val events = listOf(history(off, HistoryKind.SCREEN_OFF), history(on, HistoryKind.SCREEN_ON))

            val resolved = ReferenceResolver(store).resolve(ReferenceKind.SinceScreenOff, NOW, events)!!

            assertEquals(off, resolved.boundaryFromWallMs)
            assertEquals(offCapture, resolved.ref)
            assertEquals(onCapture, resolved.now)
            assertEquals(2_000L, resolved.gapMs)
        }

    @Test
    fun `since screen off does not reach past the capture slack for an end`() =
        runTest {
            val off = NOW - 3 * HOUR_MS
            val on = NOW - HOUR_MS
            val offCapture = snapshot(off + 20L, CaptureTrigger.SCREEN_OFF, SnapshotKind.LIGHT)
            // Delivered only when the app became active again, a minute into the screen-on period.
            val deferredOn = snapshot(on + 60_000L, CaptureTrigger.SCREEN_ON, SnapshotKind.LIGHT)
            val events = listOf(history(off, HistoryKind.SCREEN_OFF), history(on, HistoryKind.SCREEN_ON))

            assertNull(
                ReferenceResolver(LoadCountingStore(listOf(offCapture, deferredOn))).resolve(ReferenceKind.SinceScreenOff, NOW, events),
            )
        }

    @Test
    fun `a captured screen-off period newer than the history wins over it`() =
        runTest {
            val events = listOf(history(NOW - 5 * HOUR_MS, HistoryKind.SCREEN_OFF), history(NOW - 4 * HOUR_MS, HistoryKind.SCREEN_ON))
            val offCapture = snapshot(NOW - 2 * HOUR_MS, CaptureTrigger.SCREEN_OFF, SnapshotKind.LIGHT)
            val onCapture = snapshot(NOW - HOUR_MS, CaptureTrigger.SCREEN_ON, SnapshotKind.LIGHT)
            val store =
                LoadCountingStore(
                    listOf(snapshot(NOW - 4 * HOUR_MS + 10L, CaptureTrigger.SCREEN_ON, SnapshotKind.LIGHT), offCapture, onCapture),
                )

            val resolved = ReferenceResolver(store).resolve(ReferenceKind.SinceScreenOff, NOW, events)!!

            assertEquals(offCapture.clock.wallMs, resolved.boundaryFromWallMs)
            assertEquals(onCapture.clock.wallMs, resolved.boundaryToWallMs)
            assertEquals(offCapture, resolved.ref)
            assertEquals(onCapture, resolved.now)
        }

    @Test
    fun `stale history loses to a newer capture past the history horizon`() =
        runTest {
            // Day 1: an ingest stores this unplug and screen-on. Day 2: the device charged overnight and was
            // unplugged and woken, but no ingest has run since (Shizuku down after a reboot).
            val dayOne = NOW - 30 * HOUR_MS
            val ingest = snapshot(dayOne + HOUR_MS, CaptureTrigger.APP_OPEN, ingestedHistory = true)
            val unplug = snapshot(NOW - 2 * HOUR_MS, CaptureTrigger.POWER_DISCONNECTED, SnapshotKind.LIGHT)
            val screenOn = snapshot(NOW - HOUR_MS, CaptureTrigger.SCREEN_ON, SnapshotKind.LIGHT)
            val failedRefresh = snapshot(NOW - 1_000L, CaptureTrigger.APP_OPEN, SnapshotKind.LIGHT)
            val store = LoadCountingStore(listOf(ingest, unplug, screenOn, failedRefresh))
            val events = listOf(history(dayOne, HistoryKind.UNPLUGGED), history(dayOne + 10L, HistoryKind.SCREEN_ON))
            val resolver = ReferenceResolver(store)

            assertEquals(ingest.clock.wallMs, store.historyHorizonWallMs())
            assertEquals(unplug.clock.wallMs, resolver.resolve(ReferenceKind.SinceUnplugged, NOW, events)!!.boundaryFromWallMs)
            assertEquals(screenOn.clock.wallMs, resolver.resolve(ReferenceKind.ScreenOnSession, NOW, events)!!.boundaryFromWallMs)
        }

    @Test
    fun `a capture of an event the history holds keeps the history time as the start`() =
        runTest {
            val unplugged = NOW - 5 * HOUR_MS
            val screenOn = NOW - 3 * HOUR_MS
            // Lagging captures (+800 ms) and captures deferred to the app open whose ingest stored the events
            // (500 ms past that ingest's clock) both belong to the history's events.
            val ingest = snapshot(NOW - 1_000L, CaptureTrigger.APP_OPEN, ingestedHistory = true)
            val events = listOf(history(unplugged, HistoryKind.UNPLUGGED), history(screenOn, HistoryKind.SCREEN_ON))
            val captureSets =
                listOf(
                    listOf(
                        snapshot(unplugged + 800L, CaptureTrigger.POWER_DISCONNECTED, SnapshotKind.LIGHT),
                        snapshot(screenOn + 800L, CaptureTrigger.SCREEN_ON, SnapshotKind.LIGHT),
                    ),
                    listOf(
                        snapshot(NOW - 500L, CaptureTrigger.POWER_DISCONNECTED, SnapshotKind.LIGHT),
                        snapshot(NOW - 499L, CaptureTrigger.SCREEN_ON, SnapshotKind.LIGHT),
                    ),
                )

            for (captures in captureSets) {
                val store = LoadCountingStore(captures + ingest)
                assertEquals(ingest.clock.wallMs, store.historyHorizonWallMs())
                val resolver = ReferenceResolver(store)
                assertEquals(unplugged, resolver.resolve(ReferenceKind.SinceUnplugged, NOW, events)!!.boundaryFromWallMs)
                assertEquals(screenOn, resolver.resolve(ReferenceKind.ScreenOnSession, NOW, events)!!.boundaryFromWallMs)
            }
        }

    @Test
    fun `a newer event right after an ingest beats the stale history when a snapshot between proves it`() =
        runTest {
            // Yesterday's unplug and screen-on are stored. The app is opened while charging (ingest at T), then
            // the device is unplugged and the screen turned off and on again within seconds of that ingest.
            val events = listOf(history(NOW - 20 * HOUR_MS, HistoryKind.UNPLUGGED), history(NOW - 20 * HOUR_MS, HistoryKind.SCREEN_ON))
            val ingestAt = NOW - HOUR_MS
            val pluggedIngest = snapshot(ingestAt, CaptureTrigger.APP_OPEN, ingestedHistory = true, plugged = true)
            val unplug = snapshot(ingestAt + 5_000L, CaptureTrigger.POWER_DISCONNECTED, SnapshotKind.LIGHT)
            val screenOff = snapshot(ingestAt + 6_000L, CaptureTrigger.SCREEN_OFF, SnapshotKind.LIGHT)
            val screenOn = snapshot(ingestAt + 8_000L, CaptureTrigger.SCREEN_ON, SnapshotKind.LIGHT)
            val end = snapshot(NOW - 1_000L, CaptureTrigger.BATTERY_OKAY, SnapshotKind.LIGHT)
            val resolver = ReferenceResolver(LoadCountingStore(listOf(pluggedIngest, unplug, screenOff, screenOn, end)))

            assertEquals(unplug.clock.wallMs, resolver.resolve(ReferenceKind.SinceUnplugged, NOW, events)!!.boundaryFromWallMs)
            assertEquals(screenOn.clock.wallMs, resolver.resolve(ReferenceKind.ScreenOnSession, NOW, events)!!.boundaryFromWallMs)

            // A POWER_CONNECTED capture proves the replug as well as a plugged-in snapshot does.
            val unpluggedIngest = pluggedIngest.copy(battery = pluggedIngest.battery.copy(plugged = false))
            val connected = snapshot(ingestAt + 2_000L, CaptureTrigger.POWER_CONNECTED, SnapshotKind.LIGHT)
            assertEquals(
                unplug.clock.wallMs,
                ReferenceResolver(LoadCountingStore(listOf(unpluggedIngest, connected, unplug, end)))
                    .resolve(ReferenceKind.SinceUnplugged, NOW, events)!!
                    .boundaryFromWallMs,
            )
        }

    @Test
    fun `a captured pair with an app open or marker between its captures is not one screen-off period`() =
        runTest {
            // API 34+: the newest pending screen broadcast replaces the older one for a cached app, so the newest
            // SCREEN_OFF and SCREEN_ON captures can bound several periods. The user opened the app in between.
            val off = snapshot(NOW - 5 * HOUR_MS, CaptureTrigger.SCREEN_OFF, SnapshotKind.LIGHT)
            val deferredOn = snapshot(NOW - HOUR_MS, CaptureTrigger.SCREEN_ON, SnapshotKind.LIGHT)
            for (proof in listOf(CaptureTrigger.APP_OPEN, CaptureTrigger.MARKER)) {
                val store = LoadCountingStore(listOf(off, snapshot(NOW - 3 * HOUR_MS, proof), deferredOn))
                assertNull(ReferenceResolver(store).resolve(ReferenceKind.SinceScreenOff, NOW, emptyList()))
            }

            // A rejected pair cannot outrank the history's period either.
            val historyOff = NOW - 8 * HOUR_MS
            val historyOn = NOW - 7 * HOUR_MS
            val events = listOf(history(historyOff, HistoryKind.SCREEN_OFF), history(historyOn, HistoryKind.SCREEN_ON))
            val historyOffCapture = snapshot(historyOff + 20L, CaptureTrigger.SCREEN_OFF, SnapshotKind.LIGHT)
            val historyOnCapture = snapshot(historyOn + 5L, CaptureTrigger.SCREEN_ON, SnapshotKind.LIGHT)
            val store =
                LoadCountingStore(
                    listOf(historyOffCapture, historyOnCapture, off, snapshot(NOW - 3 * HOUR_MS, CaptureTrigger.APP_OPEN), deferredOn),
                )
            val resolved = ReferenceResolver(store).resolve(ReferenceKind.SinceScreenOff, NOW, events)!!
            assertEquals(historyOff, resolved.boundaryFromWallMs)
            assertEquals(historyOnCapture, resolved.now)

            // Leftover: an on-period without an app open or marker leaves no capture, and the pair stands.
            val unproven = LoadCountingStore(listOf(off, snapshot(NOW - 3 * HOUR_MS, CaptureTrigger.CHARGE_JOB), deferredOn))
            assertEquals(
                off.clock.wallMs,
                ReferenceResolver(unproven).resolve(ReferenceKind.SinceScreenOff, NOW, emptyList())!!.boundaryFromWallMs,
            )
        }

    @Test
    fun `since screen off with no snapshot near the history screen-on is empty, not a sliver`() =
        runTest {
            // The redroid rows of audit N-02 without the captures in the 10 s after the history SCREEN_ON: the app
            // was opened only 30 s later. The charge job 10 ms into the period must not stand for its end.
            val off = NOW - 45_000L
            val on = NOW - 5_600L
            val offCapture = snapshot(off + 26, CaptureTrigger.SCREEN_OFF, SnapshotKind.LIGHT)
            val chargeJob = snapshot(off + 36, CaptureTrigger.CHARGE_JOB)
            val events = listOf(history(off, HistoryKind.SCREEN_OFF), history(on, HistoryKind.SCREEN_ON))

            assertNull(
                ReferenceResolver(LoadCountingStore(listOf(offCapture, chargeJob, snapshot(NOW + 30_000L, CaptureTrigger.APP_OPEN))))
                    .resolve(ReferenceKind.SinceScreenOff, NOW + 30_000L, events),
            )

            // A SCREEN_ON capture that lands a few ms before the history time still closes the period.
            val earlyOn = snapshot(on - 5, CaptureTrigger.SCREEN_ON, SnapshotKind.LIGHT)
            val resolved =
                ReferenceResolver(LoadCountingStore(listOf(offCapture, chargeJob, earlyOn)))
                    .resolve(ReferenceKind.SinceScreenOff, NOW, events)!!
            assertEquals(on, resolved.boundaryToWallMs)
            assertEquals(chargeJob, resolved.ref)
            assertEquals(earlyOn, resolved.now)
        }

    @Test
    fun `the history still times a screen-off period that no snapshot closes`() =
        runTest {
            val off = NOW - 45_000L
            val on = NOW - 5_600L
            val events = listOf(history(off, HistoryKind.SCREEN_OFF), history(on, HistoryKind.SCREEN_ON))
            val store =
                LoadCountingStore(
                    listOf(snapshot(off + 26, CaptureTrigger.SCREEN_OFF, SnapshotKind.LIGHT), snapshot(NOW + 30_000L, CaptureTrigger.APP_OPEN)),
                    events,
                )
            val resolver = ReferenceResolver(store)

            assertNull(resolver.resolve(ReferenceKind.SinceScreenOff, NOW + 30_000L))
            assertEquals(HistoryWindow(off, on), resolver.historyScreenOff(NOW + 30_000L))
        }

    @Test
    fun `no history window without a completed period or when a newer captured period stands for since screen off`() =
        runTest {
            val stillOff = listOf(history(NOW - 2 * HOUR_MS, HistoryKind.SCREEN_ON), history(NOW - HOUR_MS, HistoryKind.SCREEN_OFF))
            assertNull(ReferenceResolver(LoadCountingStore(emptyList(), stillOff)).historyScreenOff(NOW))

            val older = listOf(history(NOW - 5 * HOUR_MS, HistoryKind.SCREEN_OFF), history(NOW - 4 * HOUR_MS, HistoryKind.SCREEN_ON))
            val newerCaptures =
                listOf(
                    snapshot(NOW - 2 * HOUR_MS, CaptureTrigger.SCREEN_OFF, SnapshotKind.LIGHT),
                    snapshot(NOW - HOUR_MS, CaptureTrigger.SCREEN_ON, SnapshotKind.LIGHT),
                )
            assertNull(ReferenceResolver(LoadCountingStore(newerCaptures, older)).historyScreenOff(NOW))
            assertEquals(
                HistoryWindow(NOW - 5 * HOUR_MS, NOW - 4 * HOUR_MS),
                ReferenceResolver(LoadCountingStore(emptyList(), older)).historyScreenOff(NOW),
            )
        }

    @Test
    fun `a screen-off period spanning a battery-stats reset starts at the reset`() =
        runTest {
            // Screen off while charging; at full charge the unplug resets battery stats, and the walker restates
            // the screen state as a SCREEN_OFF at the RESET's time (audit N-01).
            val off = NOW - 6 * HOUR_MS
            val reset = NOW - 4 * HOUR_MS
            val on = NOW - HOUR_MS
            val events =
                listOf(
                    history(off, HistoryKind.SCREEN_OFF),
                    history(reset, HistoryKind.RESET),
                    history(reset, HistoryKind.SCREEN_OFF),
                    history(reset + 50L, HistoryKind.UNPLUGGED),
                    history(on, HistoryKind.SCREEN_ON),
                )
            val unplug = snapshot(reset + 80L, CaptureTrigger.POWER_DISCONNECTED, SnapshotKind.LIGHT)
            val onCapture = snapshot(on + 1L, CaptureTrigger.SCREEN_ON, SnapshotKind.LIGHT)
            val store =
                LoadCountingStore(
                    listOf(snapshot(off + 20L, CaptureTrigger.SCREEN_OFF, SnapshotKind.LIGHT, plugged = true), unplug, onCapture),
                )

            val resolved = ReferenceResolver(store).resolve(ReferenceKind.SinceScreenOff, NOW, events)!!

            assertEquals(reset, resolved.boundaryFromWallMs)
            assertEquals(unplug, resolved.ref)
            assertEquals(onCapture, resolved.now)
        }

    private fun history(
        wallMs: Long,
        kind: Int,
    ) = HistoryEvent(0, wallMs, kind, 0, -1, 0, 0, 0)

    private fun snapshot(
        wallMs: Long,
        trigger: CaptureTrigger,
        kind: SnapshotKind = SnapshotKind.FULL,
        ingestedHistory: Boolean = false,
        plugged: Boolean = false,
        statsStartMs: Long = STATS_START,
    ) = BatterySnapshot(
        id = wallMs,
        kind = kind,
        trigger = trigger,
        clock = SnapshotClock(wallMs, wallMs, wallMs, 1, statsStartMs, null),
        battery = BatteryState(60, plugged, "discharging", 300, 4_000, -200, 1_000_000L, null),
        system = null,
        uids = emptyList(),
        deviceComponentsMah = emptyMap(),
        kernelWakelocks = emptyList(),
        wakeupReasons = emptyList(),
        cpuFreq = emptyList(),
        availability = if (ingestedHistory) mapOf(HISTORY_SOURCE to SourceAvailability(true, null)) else emptyMap(),
        truncatedUids = 0,
    )

    /** Records every read that returns a whole snapshot; [latestClockByTrigger] reads only a clock, like Room's. */
    private class LoadCountingStore(
        private val snapshots: List<BatterySnapshot>,
        /** The stored history, oldest first; only reads the resolver makes from the store itself see it. */
        private val history: List<HistoryEvent> = emptyList(),
    ) : SnapshotStore {
        val loads = mutableListOf<String>()

        private fun <T : BatterySnapshot?> loaded(
            name: String,
            result: T,
        ): T {
            if (result != null) loads += name
            return result
        }

        override suspend fun byId(id: Long): BatterySnapshot? = loaded("byId", snapshots.firstOrNull { it.id == id })

        override suspend fun latest(kind: SnapshotKind?): BatterySnapshot? =
            loaded("latest", snapshots.filter { kind == null || it.kind == kind }.maxByOrNull { it.clock.wallMs })

        override suspend fun firstAtOrAfter(
            wallMs: Long,
            kind: SnapshotKind?,
            statsStartMs: Long?,
            bootCount: Int?,
        ): BatterySnapshot? =
            loaded(
                "firstAtOrAfter",
                snapshots
                    .filter {
                        it.clock.wallMs >= wallMs && (kind == null || it.kind == kind) &&
                            (statsStartMs == null || BatteryStatsSession.sameStart(it.clock.statsStartMs, statsStartMs)) &&
                            (bootCount == null || it.clock.bootCount == bootCount)
                    }.minByOrNull { it.clock.wallMs },
            )

        override suspend fun lastAtOrBefore(
            wallMs: Long,
            kind: SnapshotKind?,
        ): BatterySnapshot? =
            loaded(
                "lastAtOrBefore",
                snapshots.filter { it.clock.wallMs <= wallMs && (kind == null || it.kind == kind) }.maxByOrNull { it.clock.wallMs },
            )

        override suspend fun latestByTrigger(trigger: CaptureTrigger): BatterySnapshot? =
            loaded("latestByTrigger", snapshots.filter { it.trigger == trigger }.maxByOrNull { it.clock.wallMs })

        override suspend fun latestClockByTrigger(trigger: CaptureTrigger): SnapshotClock? =
            snapshots.filter { it.trigger == trigger }.maxByOrNull { it.clock.wallMs }?.clock

        override suspend fun marker(id: Long): Pair<Long, BatterySnapshot>? = null

        override suspend fun insert(snapshot: BatterySnapshot): Long = error("unused")

        override suspend fun snapshotsBetween(
            fromWallMs: Long,
            toWallMs: Long,
        ): List<BatterySnapshot> = snapshots.filter { it.clock.wallMs in fromWallMs..toWallMs }

        override suspend fun insertHistory(
            events: List<HistoryEvent>,
            statsStartMs: Long,
        ) = Unit

        override suspend fun historyBetween(
            fromWallMs: Long,
            toWallMs: Long,
        ): List<HistoryEvent> = history.filter { it.wallMs in fromWallMs..toWallMs }

        override suspend fun historyCursor(): Pair<Long, Long>? = null

        override suspend fun setHistoryCursor(
            statsStartMs: Long,
            lastHistoryTimeMs: Long,
        ) = Unit

        override suspend fun addMarker(
            snapshotId: Long,
            label: String,
            wallMs: Long,
        ): Long = error("unused")

        override suspend fun markers(): List<Marker> = emptyList()

        override suspend fun insertSamples(samples: List<LiveReading>) = Unit

        override suspend fun samplesBetween(
            from: Long,
            to: Long,
        ): List<TelemetrySample> = emptyList()

        override suspend fun upsertChargeSession(
            statsStartMs: Long,
            segment: ChargeSegment,
            verdict: SegmentVerdict,
        ) = Unit

        override suspend fun chargeSessions(): List<ChargeSessionRecord> = emptyList()

        override suspend fun acceptedEstimatesChronological(limit: Int): List<Double> = emptyList()

        override suspend fun healthSamples(): List<HealthSample> = emptyList()

        override suspend fun insertHealthSample(sample: HealthSample) = Unit

        override suspend fun insertToolkitAction(record: ToolkitActionRecord): Long = error("unused")

        override suspend fun updateToolkitAction(record: ToolkitActionRecord) = Unit

        override suspend fun toolkitActions(limit: Int): List<ToolkitActionRecord> = emptyList()

        override suspend fun pendingToolkitActions(): List<ToolkitActionRecord> = emptyList()

        override suspend fun recordMiss(
            wallMs: Long,
            trigger: CaptureTrigger,
            reason: String,
        ) = Unit

        override suspend fun prune(nowWallMs: Long) = Unit
    }

    private companion object {
        const val HOUR_MS = 60L * 60_000L
        const val NOW = 1_790_000_000_000L
        const val STATS_START = 10L
    }
}
