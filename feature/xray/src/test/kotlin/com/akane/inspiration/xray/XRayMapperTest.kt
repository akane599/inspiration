package com.akane.inspiration.xray

import com.akane.inspiration.telemetry.diff.ReferenceKind
import com.akane.inspiration.telemetry.diff.SnapshotDiffer
import com.akane.inspiration.telemetry.dump.UidAlarm
import com.akane.inspiration.telemetry.dump.UidWakelock
import com.akane.inspiration.telemetry.snapshot.SnapshotKind
import com.akane.inspiration.telemetry.snapshot.SourceAvailability
import com.akane.inspiration.telemetry.snapshot.UidSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XRayMapperTest {
    @Test
    fun `truncated usage never displays whole-session uid energy or incomplete component totals`() {
        val previousUid =
            UidSnapshot(
                uid = 10_001,
                packageName = MAPS,
                totalMah = 40.0,
                fgMah = null,
                bgMah = null,
                fgsMah = null,
                cachedMah = null,
                componentsMah = emptyMap(),
                cpuUserMs = 0,
                cpuSystemMs = 0,
                fgActivityMs = 0,
                fgServiceMs = 0,
                partialWakelocks = emptyList(),
                wakeupAlarms = emptyList(),
            )
        val ref =
            snapshotAt(1_000).let { snapshot ->
                snapshot.copy(
                    kind = SnapshotKind.FULL,
                    clock = snapshot.clock.copy(statsStartMs = 100_000, statsDurationMs = 1_000),
                    uids = listOf(previousUid),
                    deviceComponentsMah = mapOf("CPU" to 4.0),
                )
            }
        val now =
            ref.copy(
                clock = ref.clock.copy(wallMs = 2_000, elapsedRealtimeMs = 2_000, statsDurationMs = 2_000),
                uids = listOf(previousUid.copy(totalMah = 50.0)),
                deviceComponentsMah = mapOf("CPU" to 5.0),
            )
        val complete = XRayMapper.apps(SnapshotDiffer.diff(ref, now), perUidAvailable = true)
        assertEquals(10.0, complete.single().totalMah, 0.0)

        for ((refTruncated, nowTruncated) in listOf(1 to 0, 0 to 1, 1 to 1)) {
            val dropped =
                ref.copy(
                    truncatedUids = refTruncated,
                    uids = listOf(if (refTruncated > 0) previousUid.copy(totalMah = 0.0) else previousUid),
                )
            val retained = now.copy(truncatedUids = nowTruncated)
            val diff = SnapshotDiffer.diff(dropped, retained)
            assertTrue(XRayMapper.apps(diff, perUidAvailable = true).isEmpty())
            assertEquals(1.0, XRayMapper.components(diff).single { it.key == "CPU" }.mah!!, 0.0)

            val summed = mapOf("usage" to SourceAvailability(true, "device_from_uid_sum"))
            val incompleteAggregate =
                SnapshotDiffer.diff(
                    dropped.copy(availability = summed),
                    retained.copy(availability = summed),
                )
            assertTrue(XRayMapper.components(incompleteAggregate).isEmpty())
        }
    }

    @Test
    fun `missing dump leaves energy visible and detail counters unavailable`() {
        val base =
            diffOf(
                uids =
                    listOf(
                        uidDelta(
                            10_001,
                            "app",
                            3.0,
                            wakelocks = listOf(UidWakelock("held", 1_000, 0, 1)),
                            alarms = listOf(UidAlarm("alarm", 1)),
                        ),
                    ),
            )
        val rows = XRayMapper.apps(base.copy(validity = base.validity.copy(dumpCounters = false, alarmDumpCounters = false)), true)
        val app = rows.single()
        assertEquals(3.0, app.totalMah, 0.0)
        org.junit.Assert.assertFalse(app.dumpCountersAvailable)
        org.junit.Assert.assertFalse(app.alarmsAvailable)
        assertTrue(app.wakelocks.isEmpty())
        assertTrue(app.alarms.isEmpty())
    }

    @Test
    fun `components are ranked by energy with a share of the listed total`() {
        val rows =
            XRayMapper.components(
                diffOf(components = mapOf("SCREEN" to 50.0, "CPU" to 30.0, "MOBILE_RADIO" to 15.0, "WIFI" to 5.0)),
            )

        assertEquals(listOf("SCREEN", "CPU", "MOBILE_RADIO", "WIFI"), rows.take(4).map { it.key })
        assertEquals(50.0, rows[0].sharePct!!, 1e-9)
        assertEquals(5.0, rows[3].sharePct!!, 1e-9)
    }

    @Test
    fun `a component the snapshots do not carry has no figures, never zero or an estimate`() {
        val rows = XRayMapper.components(diffOf(components = mapOf("SCREEN" to 10.0, "CPU" to 10.0)))

        val missing = rows.filter { it.key in setOf("IDLE", "BLUETOOTH", "GNSS", "SENSORS", "WIFI", "MOBILE_RADIO") }
        assertEquals(6, missing.size)
        missing.forEach {
            assertNull(it.key, it.mah)
            assertNull(it.key, it.sharePct)
        }
        // present rows come first, the unavailable ones after, in the spec's order
        assertEquals(listOf("SCREEN", "CPU"), rows.take(2).map { it.key })
        assertEquals(listOf("MOBILE_RADIO", "WIFI", "IDLE", "BLUETOOTH", "GNSS", "SENSORS"), rows.drop(2).map { it.key })
    }

    @Test
    fun `a component the diff marks invalid is unavailable and outside the total`() {
        val rows =
            XRayMapper.components(
                diffOf(components = mapOf("SCREEN" to 30.0, "CPU" to 10.0, "WIFI" to 999.0), invalidComponents = setOf("WIFI")),
            )

        val wifi = rows.single { it.key == "WIFI" }
        assertNull(wifi.mah)
        assertEquals(75.0, rows.single { it.key == "SCREEN" }.sharePct!!, 1e-9)
    }

    @Test
    fun `components outside the featured eight are folded into one other row`() {
        val rows = XRayMapper.components(diffOf(components = mapOf("SCREEN" to 10.0, "CAMERA" to 4.0, "AUDIO" to 6.0)))

        val other = rows.single { it.key == XRayMapper.OTHER_KEY }
        assertEquals(10.0, other.mah!!, 1e-9)
    }

    @Test
    fun `invalid battery statistics give no component rows`() {
        assertTrue(XRayMapper.components(diffOf(components = mapOf("SCREEN" to 10.0), batteryStats = false)).isEmpty())
        assertTrue(XRayMapper.components(diffOf(components = emptyMap())).isEmpty())
    }

    @Test
    fun `apps are ranked by energy, biggest first, with shares of the app total`() {
        val apps = XRayMapper.apps(sampleDiff(), perUidAvailable = true)

        assertEquals(listOf(CHATTY, MAPS), apps.map { it.packageName })
        assertEquals(75.0, apps[0].sharePct, 1e-9)
        assertEquals(25.0, apps[1].sharePct, 1e-9)
    }

    @Test
    fun `equal energy ranks by uid so the order is stable`() {
        val diff = diffOf(uids = listOf(uidDelta(20, "b", 5.0), uidDelta(10, "a", 5.0)))

        assertEquals(listOf(10, 20), XRayMapper.apps(diff, true).map { it.uid })
    }

    @Test
    fun `without per-app data there are no app rows`() {
        assertTrue(XRayMapper.apps(sampleDiff(), perUidAvailable = false).isEmpty())
        assertTrue(XRayMapper.apps(diffOf(uids = sampleDiff().uids, batteryStats = false), perUidAvailable = true).isEmpty())
    }

    @Test
    fun `invalid, package-changed, empty and non-finite uids are left out`() {
        val diff =
            diffOf(
                uids =
                    listOf(
                        uidDelta(1, "ok", 3.0),
                        uidDelta(2, "invalid", 9.0, valid = false),
                        uidDelta(3, "changed", 9.0, packageChanged = true),
                        uidDelta(4, "zero", 0.0),
                        uidDelta(5, "nan", Double.NaN),
                        uidDelta(6, "negative", -1.0),
                    ),
            )

        assertEquals(listOf("ok"), XRayMapper.apps(diff, true).map { it.packageName })
    }

    @Test
    fun `an app's components are grouped into the bar's hues and ranked in the detail`() {
        val app = XRayMapper.apps(sampleDiff(), true).first { it.packageName == CHATTY }

        assertEquals(
            mapOf(ComponentGroup.CPU to 40.0, ComponentGroup.RADIOS to 20.0),
            app.slices.associate { it.group to it.mah },
        )
        assertEquals(listOf("CPU", "MOBILE_RADIO", "WIFI"), app.components.map { it.key })
        assertEquals(40.0 / 60.0 * 100.0, app.components[0].sharePct!!, 1e-9)
    }

    @Test
    fun `wakelocks and alarms are the busiest few, with the alarm dump used only when the stats have none`() {
        val wakelocks = (1..7).map { UidWakelock("wl$it", durationMs = it * 1000L, bgDurationMs = 0, count = it.toLong()) }
        val diff =
            diffOf(
                uids =
                    listOf(
                        uidDelta(1, "a", 5.0, wakelocks = wakelocks, alarms = listOf(UidAlarm("sync", 3), UidAlarm("idle", 0))),
                    ),
            )

        val app = XRayMapper.apps(diff, true).single()

        assertEquals(listOf("wl7", "wl6", "wl5", "wl4", "wl3"), app.wakelocks.map { it.name })
        assertEquals(listOf(AlarmRow("sync", 3)), app.alarms.toList())
    }

    @Test
    fun `the slice banner only exists for a time slice, and a gap shows past five minutes`() {
        val slice = ReferenceKind.TimeSlice(1_000L, 9_000L)

        assertNull(XRayMapper.slice(ReferenceKind.SinceUnplugged, resolvedOf()))
        assertNull(XRayMapper.slice(slice, resolvedOf(1_000L, 9_000L, gapMs = 5 * 60_000L))?.gapMs)
        assertEquals(6 * 60_000L, XRayMapper.slice(slice, resolvedOf(1_000L, 9_000L, gapMs = 6 * 60_000L))?.gapMs)
        // while nothing resolves, the request is shown and no covered range is claimed
        val pending = XRayMapper.slice(slice, null)!!
        assertEquals(1_000L, pending.requestedFromMs)
        assertNull(pending.coveredFromMs)
    }
}
