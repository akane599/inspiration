package com.akane.inspiration.database.store

import androidx.test.core.app.ApplicationProvider
import com.akane.inspiration.database.InspirationDatabase
import com.akane.inspiration.ipc.model.HistoryEvent
import com.akane.inspiration.ipc.model.HistoryKind
import com.akane.inspiration.telemetry.dump.KernelWakelock
import com.akane.inspiration.telemetry.dump.SystemTimers
import com.akane.inspiration.telemetry.dump.UidAlarm
import com.akane.inspiration.telemetry.dump.UidWakelock
import com.akane.inspiration.telemetry.snapshot.BatterySnapshot
import com.akane.inspiration.telemetry.snapshot.BatteryState
import com.akane.inspiration.telemetry.snapshot.CaptureTrigger
import com.akane.inspiration.telemetry.snapshot.CpuFreqTime
import com.akane.inspiration.telemetry.snapshot.LiveReading
import com.akane.inspiration.telemetry.snapshot.SnapshotClock
import com.akane.inspiration.telemetry.snapshot.SnapshotKind
import com.akane.inspiration.telemetry.snapshot.SourceAvailability
import com.akane.inspiration.telemetry.snapshot.UidSnapshot
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoomSnapshotStoreTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var databaseName: String
    private lateinit var database: InspirationDatabase
    private lateinit var store: RoomSnapshotStore

    @Before
    fun setUp() {
        databaseName = "snapshot-store-${System.nanoTime()}.db"
        database = InspirationDatabase.create(context, databaseName)
        store = RoomSnapshotStore(database)
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun `round trips full light and partial availability snapshots`() = runBlocking {
        listOf(
            fullSnapshot(),
            fullSnapshot(
                kind = SnapshotKind.LIGHT,
                trigger = CaptureTrigger.SCREEN_OFF,
                wallMs = 2_000L,
                system = null,
                uids = emptyList(),
                deviceComponentsMah = emptyMap(),
                kernelWakelocks = emptyList(),
                wakeupReasons = emptyList(),
                cpuFreq = emptyList(),
            ),
            fullSnapshot(
                trigger = CaptureTrigger.BATTERY_LOW,
                wallMs = 3_000L,
                system = null,
                availability = mapOf(
                    "usage" to SourceAvailability(false, "permission_denied"),
                    "kernel" to SourceAvailability(true, null),
                ),
            ),
        ).forEach { snapshot ->
            val id = store.insert(snapshot)
            assertEquals(snapshot.copy(id = id), store.byId(id))
        }
    }

    @Test
    fun `lookup methods honor filters and ordering`() = runBlocking {
        val old = store.insert(fullSnapshot(wallMs = 1_000L, statsStartMs = 10L, bootCount = 1))
        val wrongKind = store.insert(
            fullSnapshot(
                kind = SnapshotKind.LIGHT,
                trigger = CaptureTrigger.SCREEN_OFF,
                wallMs = 2_000L,
                statsStartMs = 10L,
                bootCount = 1,
            ),
        )
        val wrongSession = store.insert(fullSnapshot(wallMs = 3_000L, statsStartMs = 20L, bootCount = 1))
        val match = store.insert(fullSnapshot(wallMs = 4_000L, statsStartMs = 10L, bootCount = 1))
        val wrongBoot = store.insert(fullSnapshot(wallMs = 5_000L, statsStartMs = 10L, bootCount = 2))

        assertEquals(match, store.firstAtOrAfter(1_500L, SnapshotKind.FULL, 10L, 1)?.id)
        assertEquals(wrongKind, store.lastAtOrBefore(2_500L, SnapshotKind.LIGHT)?.id)
        assertEquals(wrongBoot, store.latestByTrigger(CaptureTrigger.APP_OPEN)?.id)
        assertEquals(wrongBoot, store.latest()?.id)
        assertEquals(old, store.lastAtOrBefore(1_000L, SnapshotKind.FULL)?.id)
        assertNotNull(store.byId(wrongSession))
    }

    @Test
    fun `history deduplicates and cursor round trips`() = runBlocking {
        val event = HistoryEvent(
            historyTimeMs = 10L,
            wallMs = 20L,
            kind = HistoryKind.SCREEN_ON,
            levelPct = 50,
            chargeUah = 100_000L,
            temperatureDeciC = 280,
            voltageMv = 4_000,
            plugType = 1,
        )
        store.insertHistory(listOf(event, event), statsStartMs = 7L)
        store.setHistoryCursor(statsStartMs = 7L, lastHistoryTimeMs = 10L)

        assertEquals(listOf(event), store.historyBetween(0L, 100L))
        assertEquals(7L to 10L, store.historyCursor())
    }

    @Test
    fun `prune delegates retention to database sql`() = runBlocking {
        val now = 100L * DAY_MS
        val retained = store.insert(fullSnapshot(wallMs = now - 31L * DAY_MS))
        val pruned = store.insert(fullSnapshot(wallMs = now - 31L * DAY_MS + 1L))
        store.addMarker(retained, "baseline", now)
        store.insertSamples(
            listOf(
                LiveReading(now - 31L * DAY_MS, 1L, 1, 10, 4_000, 40.0, 300, 50, false),
                LiveReading(now - 31L * DAY_MS + 1L, 2L, 1, 20, 4_200, 84.0, 320, 60, false),
            ),
        )

        store.prune(now)

        assertNotNull(store.byId(retained))
        assertNull(store.byId(pruned))
        assertTrue(database.telemetryDao().hourly(((now - 31L * DAY_MS) / HOUR_MS) * HOUR_MS) != null)
    }

    private fun fullSnapshot(
        kind: SnapshotKind = SnapshotKind.FULL,
        trigger: CaptureTrigger = CaptureTrigger.APP_OPEN,
        wallMs: Long = 1_000L,
        statsStartMs: Long? = 10L,
        bootCount: Int = 1,
        system: SystemTimers? = timers(),
        uids: List<UidSnapshot> = listOf(uidSnapshot()),
        deviceComponentsMah: Map<String, Double> = linkedMapOf("screen" to 1.5, "wifi" to 2.5),
        kernelWakelocks: List<KernelWakelock> = listOf(KernelWakelock("kernel", 30L, 3L)),
        wakeupReasons: List<KernelWakelock> = listOf(KernelWakelock("reason", 40L, 4L)),
        cpuFreq: List<CpuFreqTime> = listOf(CpuFreqTime(0, 300_000L, 500L)),
        availability: Map<String, SourceAvailability> = linkedMapOf(
            "cpu" to SourceAvailability(true, null),
            "usage" to SourceAvailability(false, "unavailable"),
        ),
    ) = BatterySnapshot(
        id = 0L,
        kind = kind,
        trigger = trigger,
        clock = SnapshotClock(wallMs, wallMs + 1L, wallMs + 2L, bootCount, statsStartMs, 9L),
        battery = BatteryState(60, false, "Discharging", 300, 4_000, -200, 1_234_000L, 4.5),
        system = system,
        uids = uids,
        deviceComponentsMah = deviceComponentsMah,
        kernelWakelocks = kernelWakelocks,
        wakeupReasons = wakeupReasons,
        cpuFreq = cpuFreq,
        availability = availability,
        truncatedUids = 2,
    )

    private fun uidSnapshot() = UidSnapshot(
        uid = 10_001,
        packageName = "com.example.app",
        totalMah = 3.0,
        fgMah = 1.0,
        bgMah = 2.0,
        fgsMah = 0.5,
        cachedMah = 0.1,
        componentsMah = linkedMapOf("cpu" to 1.5, "wifi" to 1.5),
        cpuUserMs = 11L,
        cpuSystemMs = 12L,
        fgActivityMs = 13L,
        fgServiceMs = 14L,
        partialWakelocks = listOf(UidWakelock("partial", 15L, 16L, 17L)),
        wakeupAlarms = listOf(UidAlarm("alarm", 18L)),
    )

    private fun timers() = SystemTimers(
        startClockTimeMs = 1L,
        totalRealtimeMs = 2L,
        totalUptimeMs = 3L,
        batteryRealtimeMs = 4L,
        batteryUptimeMs = 5L,
        screenOffRealtimeMs = 6L,
        screenOffUptimeMs = 7L,
        screenDozeMs = 8L,
        screenOnMs = 9L,
        interactiveMs = 10L,
        deepDozeMs = 11L,
        deepDozeCount = 12L,
        lightDozeMs = 13L,
        dischargeTotalMah = 14.0,
        dischargeScreenOffMah = 15.0,
        chargeTimeRemainingMs = 16L,
        dischargeTimeRemainingMs = 17L,
    )

    private companion object {
        const val HOUR_MS = 60L * 60L * 1_000L
        const val DAY_MS = 24L * HOUR_MS
    }
}
