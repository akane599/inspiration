package com.akane.inspiration.telemetry.diff

import com.akane.inspiration.ipc.model.HistoryEvent
import com.akane.inspiration.ipc.model.HistoryKind
import com.akane.inspiration.telemetry.capacity.ChargeSegment
import com.akane.inspiration.telemetry.capacity.SegmentVerdict
import com.akane.inspiration.telemetry.dump.KernelWakelock
import com.akane.inspiration.telemetry.dump.UidAlarm
import com.akane.inspiration.telemetry.dump.UidSensor
import com.akane.inspiration.telemetry.dump.UidWakelock
import com.akane.inspiration.telemetry.snapshot.BatterySnapshot
import com.akane.inspiration.telemetry.snapshot.BatteryState
import com.akane.inspiration.telemetry.snapshot.CaptureTrigger
import com.akane.inspiration.telemetry.snapshot.CpuFreqDelta
import com.akane.inspiration.telemetry.snapshot.CpuFreqTime
import com.akane.inspiration.telemetry.snapshot.LiveReading
import com.akane.inspiration.telemetry.snapshot.SnapshotClock
import com.akane.inspiration.telemetry.snapshot.SnapshotKind
import com.akane.inspiration.telemetry.snapshot.SourceAvailability
import com.akane.inspiration.telemetry.snapshot.UidIdentityScope
import com.akane.inspiration.telemetry.snapshot.UidOwnerFingerprint
import com.akane.inspiration.telemetry.snapshot.UidOwnerInstall
import com.akane.inspiration.telemetry.snapshot.UidSnapshot
import com.akane.inspiration.telemetry.store.ChargeSessionRecord
import com.akane.inspiration.telemetry.store.HealthSample
import com.akane.inspiration.telemetry.store.Marker
import com.akane.inspiration.telemetry.store.SnapshotStore
import com.akane.inspiration.telemetry.store.TelemetrySample
import com.akane.inspiration.telemetry.store.ToolkitActionRecord
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotDifferTest {
    @Test
    fun `parcel membership loss withholds uid energy with or without a surviving dump row`() {
        for ((refTruncated, nowTruncated) in listOf(1 to 0, 0 to 1, 1 to 1)) {
            for (dumpRowSurvives in listOf(true, false)) {
                // The actual cumulative usage is 40 -> 50. A dropped reference usage row is either
                // absent entirely or retained by the dump union with fabricated zero energy.
                val refUids =
                    if (refTruncated == 0) {
                        listOf(uid(totalMah = 40.0))
                    } else if (dumpRowSurvives) {
                        listOf(uid(totalMah = 0.0, packageName = null))
                    } else {
                        emptyList()
                    }
                val diff =
                    SnapshotDiffer.diff(
                        snapshot(elapsed = 1_000, uids = refUids).copy(truncatedUids = refTruncated),
                        snapshot(elapsed = 2_000, duration = 2_000, uids = listOf(uid(totalMah = 50.0)))
                            .copy(truncatedUids = nowTruncated),
                    )

                assertTrue(diff.validity.batteryStats)
                assertFalse(diff.uids.single().valid)
                assertTrue(diff.uids.filter { it.valid }.isEmpty())
                assertTrue(diff.validity.problems.contains("truncated_uid_usage"))
            }
        }
    }

    @Test
    fun `complete membership keeps 40 to 50 but a newly observed uid has no proven baseline`() {
        val diff =
            SnapshotDiffer.diff(
                snapshot(elapsed = 1_000, uids = listOf(uid(totalMah = 40.0))),
                snapshot(
                    elapsed = 2_000,
                    duration = 2_000,
                    uids =
                        listOf(
                            uid(totalMah = 50.0),
                            uid(uid = 10_002, totalMah = 2.0),
                        ),
                ),
            )

        assertTrue(diff.uids.first().valid)
        assertFalse(diff.uids.last().valid)
        assertEquals(10.0, diff.uids.first().totalMah, 0.0)
        assertEquals(2.0, diff.uids.last().totalMah, 0.0)
        assertFalse(diff.validity.problems.contains("truncated_uid_usage"))
    }

    @Test
    fun `truncated usage leaves independent device session and kernel metrics available`() {
        val ref =
            discharge(
                snapshot(
                    elapsed = 1_000,
                    uids = listOf(uid(totalMah = 40.0)),
                    components = mapOf("SCREEN" to 4.0),
                    level = 80,
                    discharged = 100.0,
                    kernelWakelocks = listOf(KernelWakelock("kernel", 100, 1)),
                ),
                total = 100.0,
                screenOff = 40.0,
            ).copy(
                cpuFreq = listOf(CpuFreqTime(0, 1_000, 10)),
                availability = mapOf("usage" to SourceAvailability(true, null)),
            )
        val now =
            discharge(
                snapshot(
                    elapsed = 61_000,
                    duration = 61_000,
                    uids = listOf(uid(totalMah = 50.0)),
                    components = mapOf("SCREEN" to 6.0),
                    level = 78,
                    discharged = 112.0,
                    kernelWakelocks = listOf(KernelWakelock("kernel", 150, 2)),
                ),
                total = 112.0,
                screenOff = 45.0,
            ).copy(
                cpuFreq = listOf(CpuFreqTime(0, 1_000, 20)),
                availability = mapOf("usage" to SourceAvailability(true, null)),
            )
        val sources =
            mapOf(
                "usage" to SourceAvailability(true, null),
                "dump" to SourceAvailability(true, null),
                "cpu" to SourceAvailability(true, null),
            )
        for ((refTruncated, nowTruncated) in listOf(1 to 0, 0 to 1, 1 to 1)) {
            val diff =
                SnapshotDiffer.diff(
                    ref.copy(truncatedUids = refTruncated, availability = sources),
                    now.copy(truncatedUids = nowTruncated, availability = sources),
                )

            assertFalse(diff.uids.single().valid)
            assertTrue(diff.validity.batteryStats)
            assertTrue(diff.validity.dumpCounters)
            assertTrue(diff.validity.kernelCounters)
            assertTrue(diff.validity.kernelWakelocks)
            assertTrue(diff.validity.cpuFreq)
            assertEquals(2.0, diff.deviceComponentsMah.getValue("SCREEN"), 0.0)
            assertEquals(2, diff.levelDropPct)
            assertEquals(12.0, diff.dischargedMah!!, 0.0)
            assertEquals(12.0, diff.dischargeMah!!, 0.0)
            assertEquals(5.0, diff.dischargeScreenOffMah!!, 0.0)
            assertEquals(10L, diff.cpuFreqDelta.single().timeMs)
            assertEquals(50L, diff.kernelWakelocks.single().durationMs)
            assertNotNull(diff.sleep)
        }
    }

    @Test
    fun `device components from a truncated uid sum are withheld on either endpoint`() {
        val summed = mapOf("usage" to SourceAvailability(true, "device_from_uid_sum"))
        for ((refTruncated, nowTruncated) in listOf(1 to 0, 0 to 1, 1 to 1)) {
            val diff =
                SnapshotDiffer.diff(
                    snapshot(elapsed = 1_000, components = mapOf("SCREEN" to 4.0)).copy(
                        truncatedUids = refTruncated,
                        availability = summed,
                    ),
                    snapshot(elapsed = 2_000, duration = 2_000, components = mapOf("SCREEN" to 6.0)).copy(
                        truncatedUids = nowTruncated,
                        availability = summed,
                    ),
                )

            assertTrue(diff.validity.batteryStats)
            assertTrue(diff.deviceComponentsMah.isEmpty())
            assertTrue(diff.validity.problems.contains("truncated_device_components"))
        }
    }

    @Test
    fun `complete uid sum remains comparable to an independent aggregate with truncated uid rows`() {
        val summed = mapOf("usage" to SourceAvailability(true, "device_from_uid_sum"))
        val independent = mapOf("usage" to SourceAvailability(true, null))
        for (summedReference in listOf(true, false)) {
            val diff =
                SnapshotDiffer.diff(
                    snapshot(elapsed = 1_000, components = mapOf("SCREEN" to 4.0)).copy(
                        truncatedUids = if (summedReference) 0 else 1,
                        availability = if (summedReference) summed else independent,
                    ),
                    snapshot(elapsed = 2_000, duration = 2_000, components = mapOf("SCREEN" to 6.0)).copy(
                        truncatedUids = if (summedReference) 1 else 0,
                        availability = if (summedReference) independent else summed,
                    ),
                )

            assertEquals(2.0, diff.deviceComponentsMah.getValue("SCREEN"), 0.0)
            assertFalse(diff.validity.problems.contains("truncated_device_components"))
        }
    }

    @Test
    fun `dump outage on either endpoint preserves energy but omits incomparable counters`() {
        val observed =
            uid(totalMah = 10.0).copy(
                cpuUserMs = 7_200_000,
                fgActivityMs = 600_000,
                partialWakelocks =
                    listOf(
                        com.akane.inspiration.telemetry.dump
                            .UidWakelock("held", 3_600_000, 0, 10),
                    ),
                sensors = listOf(UidSensor(1, 300_000, 0, 2)),
            )
        val sources =
            mapOf(
                "dump" to
                    com.akane.inspiration.telemetry.snapshot
                        .SourceAvailability(true, null),
            )
        for (missingReference in listOf(true, false)) {
            val ref =
                snapshot(elapsed = 1_000, uids = listOf(if (missingReference) uid(totalMah = 10.0) else observed)).copy(
                    availability = sources.mapValues { it.value.copy(available = !missingReference) },
                )
            val now =
                snapshot(
                    elapsed = 61_000,
                    duration = 61_000,
                    uids = listOf(if (missingReference) observed.copy(totalMah = 11.0) else uid(totalMah = 11.0)),
                ).copy(
                    availability = sources.mapValues { it.value.copy(available = missingReference) },
                )
            val diff = SnapshotDiffer.diff(ref, now)
            assertTrue(diff.validity.batteryStats)
            assertFalse(diff.validity.dumpCounters)
            assertFalse(diff.validity.kernelWakelocks)
            assertTrue(diff.uids.single().valid)
            assertEquals(1.0, diff.uids.single().totalMah, 0.0)
            assertEquals(0L, diff.uids.single().cpuUserMs)
            assertTrue(
                diff.uids
                    .single()
                    .partialWakelocks
                    .isEmpty(),
            )
            assertTrue(
                diff.uids
                    .single()
                    .sensors
                    .isEmpty(),
            )
        }
    }

    @Test
    fun `stats reset omits even positive kernel wakelock deltas but retains boot CPU counters`() {
        val ref =
            snapshot(elapsed = 1_000, kernelWakelocks = listOf(KernelWakelock("wlan", 1_000, 1)))
                .copy(cpuFreq = listOf(CpuFreqTime(0, 1_000, 10)))
        for (newTime in listOf(500L, 3_000L)) {
            val now =
                snapshot(
                    elapsed = 2_000,
                    statsStart = 200_000,
                    kernelWakelocks = listOf(KernelWakelock("wlan", newTime, 3)),
                ).copy(cpuFreq = listOf(CpuFreqTime(0, 1_000, 20)))
            val diff = SnapshotDiffer.diff(ref, now)
            assertFalse(diff.validity.kernelWakelocks)
            assertTrue(diff.kernelWakelocks.isEmpty())
            assertTrue(diff.validity.kernelCounters)
            assertTrue(diff.validity.cpuFreq)
            assertEquals(10L, diff.cpuFreqDelta.single().timeMs)
        }
    }

    @Test
    fun `CPU and alarm dump recovery cannot use missing observations as zero baselines`() {
        val ref =
            snapshot(elapsed = 1_000, uids = listOf(uid(totalMah = 1.0))).copy(
                availability =
                    mapOf(
                        "cpu" to
                            com.akane.inspiration.telemetry.snapshot
                                .SourceAvailability(false, "timeout"),
                        "alarm" to
                            com.akane.inspiration.telemetry.snapshot
                                .SourceAvailability(false, "timeout"),
                    ),
            )
        val now =
            snapshot(
                elapsed = 2_000,
                duration = 2_000,
                uids = listOf(uid(totalMah = 2.0).copy(alarmDumpWakeups = listOf(UidAlarm("alarm", 100)))),
            ).copy(
                cpuFreq = listOf(CpuFreqTime(0, 1_000, 50_000)),
                availability = ref.availability.mapValues { it.value.copy(available = true) },
            )
        val diff = SnapshotDiffer.diff(ref, now)
        assertFalse(diff.validity.cpuFreq)
        assertFalse(diff.validity.alarmDumpCounters)
        assertTrue(diff.cpuFreqDelta.isEmpty())
        assertTrue(
            diff.uids
                .single()
                .alarmDumpWakeups
                .isEmpty(),
        )
        assertEquals(1.0, diff.uids.single().totalMah, 0.0)
    }

    @Test
    fun `same session computes device and uid deltas`() {
        val diff =
            SnapshotDiffer.diff(
                snapshot(
                    elapsed = 1_000,
                    uids = listOf(uid(totalMah = 2.0, components = mapOf("cpu" to 1.0))),
                    components = mapOf("screen" to 1.0),
                    level = 80,
                    discharged = 2.0,
                ),
                snapshot(
                    elapsed = 2_000,
                    duration = 2_000,
                    uids = listOf(uid(totalMah = 3.5, components = mapOf("cpu" to 1.5))),
                    components = mapOf("screen" to 1.25),
                    level = 77,
                    discharged = 2.4,
                ),
            )

        assertTrue(diff.validity.batteryStats)
        assertEquals(1.5, diff.uids.single().totalMah, 0.0)
        assertEquals(
            0.5,
            diff.uids
                .single()
                .componentsMah
                .getValue("cpu"),
            0.0,
        )
        assertEquals(0.25, diff.deviceComponentsMah.getValue("screen"), 0.0)
        assertEquals(3, diff.levelDropPct)
        assertEquals(0.4, diff.dischargedMah!!, 0.000_001)
    }

    @Test
    fun `reset reboot and session skew invalidate their counter sources`() {
        val ref = snapshot(elapsed = 1_000)
        assertFalse(SnapshotDiffer.diff(ref, snapshot(elapsed = 2_000, statsStart = 200_000)).validity.batteryStats)
        assertFalse(SnapshotDiffer.diff(ref, snapshot(elapsed = 2_000, boot = 2)).validity.kernelCounters)
        val skewed = snapshot(elapsed = 2_000, duration = 2_000, systemStart = 97_000)
        val diff = SnapshotDiffer.diff(ref, skewed)
        assertTrue(diff.validity.batteryStats)
        assertTrue(diff.validity.problems.contains("session_skew"))
    }

    @Test
    fun `negative component is reported without invalidating the battery stats session`() {
        val diff =
            SnapshotDiffer.diff(
                snapshot(
                    elapsed = 1_000,
                    uids =
                        listOf(
                            uid(totalMah = 3.0, components = mapOf("SENSORS" to 0.053898)),
                            uid(uid = 10_002, totalMah = 1.0),
                        ),
                    components = mapOf("SENSORS" to 0.053898),
                ),
                snapshot(
                    elapsed = 2_000,
                    duration = 2_000,
                    uids =
                        listOf(
                            uid(totalMah = 3.0, components = mapOf("SENSORS" to 0.045978)),
                            uid(uid = 10_002, totalMah = 1.1),
                        ),
                    components = mapOf("SENSORS" to 0.045978),
                ),
            )

        assertTrue(diff.validity.batteryStats)
        assertFalse(diff.uids.first().valid)
        assertTrue(diff.uids.last().valid)
        assertEquals(setOf("SENSORS"), diff.validity.invalidDeviceComponents)
        assertEquals(
            -0.00792,
            diff.uids
                .first()
                .componentsMah
                .getValue("SENSORS"),
            0.000_001,
        )
        assertEquals(0.1, diff.uids.last().totalMah, 0.000_001)
        assertTrue(diff.validity.problems.contains("negative_uid_component:10001:SENSORS"))
        assertTrue(diff.validity.problems.contains("negative_device_component:SENSORS"))
    }

    @Test
    fun `system timer deltas cover signal bins scanning and radio active`() {
        val diff =
            SnapshotDiffer.diff(
                withTimers(snapshot(elapsed = 1_000), listOf(10, 20, 30, 40, 50), scanning = 5, radio = 100),
                withTimers(snapshot(elapsed = 2_000, duration = 2_000), listOf(11, 25, 30, 90, 60), scanning = 9, radio = 450),
            )

        assertEquals(listOf(1L, 5L, 0L, 50L, 10L), diff.signalStrengthMs)
        assertEquals(4L, diff.signalScanningMs)
        assertEquals(350L, diff.mobileRadioActiveMs)
    }

    @Test
    fun `negative system timer deltas become null with a problem and keep the diff valid`() {
        val diff =
            SnapshotDiffer.diff(
                withTimers(snapshot(elapsed = 1_000), listOf(10, 20, 30, 40, 50), scanning = 5, radio = 100),
                withTimers(snapshot(elapsed = 2_000, duration = 2_000), listOf(10, 19, 30, 40, 50), scanning = 4, radio = 99),
            )

        assertTrue(diff.validity.batteryStats)
        assertTrue(diff.signalStrengthMs.isEmpty())
        assertNull(diff.signalScanningMs)
        assertNull(diff.mobileRadioActiveMs)
        assertTrue(diff.validity.problems.contains("negative_signal_strength:1"))
        assertTrue(diff.validity.problems.contains("negative_signal_scanning"))
        assertTrue(diff.validity.problems.contains("negative_mobile_radio_active"))
    }

    @Test
    fun `discharge deltas subtract the persisted system fields of the same stats session`() {
        val diff =
            SnapshotDiffer.diff(
                discharge(snapshot(elapsed = 1_000), total = 10.0, screenOff = 4.0),
                discharge(snapshot(elapsed = 2_000, duration = 2_000), total = 16.5, screenOff = 5.0),
            )

        assertEquals(6.5, diff.dischargeMah!!, 0.000_001)
        assertEquals(1.0, diff.dischargeScreenOffMah!!, 0.000_001)
    }

    @Test
    fun `discharge deltas use the same session tolerance as the other battery-stats counters`() {
        // A wall-clock step shifts BatteryStatsImpl's reported start by less than the session tolerance.
        val ref = discharge(snapshot(elapsed = 1_000), total = 10.0, screenOff = 4.0)
        val stepped = discharge(snapshot(elapsed = 2_000, duration = 2_000, statsStart = 101_500), total = 16.5, screenOff = 5.0)

        val diff = SnapshotDiffer.diff(ref, stepped)

        assertTrue(diff.validity.batteryStats)
        assertTrue(diff.validity.dumpCounters)
        assertEquals(6.5, diff.dischargeMah!!, 0.000_001)
        assertEquals(1.0, diff.dischargeScreenOffMah!!, 0.000_001)
    }

    @Test
    fun `discharge deltas are null when statsStart differs or a side lacks system timers`() {
        val ref = discharge(snapshot(elapsed = 1_000), total = 10.0, screenOff = 4.0)
        val reset = discharge(snapshot(elapsed = 2_000, duration = 2_000, statsStart = 102_001), total = 1.0, screenOff = 0.5)
        val mismatched = SnapshotDiffer.diff(ref, reset)
        assertNull(mismatched.dischargeMah)
        assertNull(mismatched.dischargeScreenOffMah)

        val bare = SnapshotDiffer.diff(ref, snapshot(elapsed = 2_000, duration = 2_000))
        assertNull(bare.dischargeMah)
        assertNull(bare.dischargeScreenOffMah)
        val bareRef =
            SnapshotDiffer.diff(
                snapshot(elapsed = 1_000),
                ref.copy(clock = ref.clock.copy(elapsedRealtimeMs = 2_000, statsDurationMs = 2_000)),
            )
        assertNull(bareRef.dischargeMah)

        val unknownStart = ref.copy(clock = ref.clock.copy(statsStartMs = null))
        assertNull(
            SnapshotDiffer.diff(unknownStart, unknownStart.copy(clock = unknownStart.clock.copy(elapsedRealtimeMs = 2_000))).dischargeMah,
        )
    }

    @Test
    fun `cpu freq delta is per policy and frequency sorted and only filled for valid kernel counters`() {
        val ref =
            snapshot(elapsed = 1_000).copy(
                cpuFreq = listOf(CpuFreqTime(4, 2_000_000, 50), CpuFreqTime(0, 600_000, 100), CpuFreqTime(0, 1_200_000, 10)),
            )
        val now =
            snapshot(elapsed = 2_000, duration = 2_000).copy(
                cpuFreq =
                    listOf(
                        CpuFreqTime(4, 2_000_000, 80),
                        CpuFreqTime(0, 600_000, 400),
                        CpuFreqTime(0, 1_200_000, 10),
                        CpuFreqTime(0, 1_800_000, 7),
                    ),
            )

        val diff = SnapshotDiffer.diff(ref, now)

        assertEquals(
            listOf(
                CpuFreqDelta(0, 600_000, 300),
                CpuFreqDelta(0, 1_200_000, 0),
                CpuFreqDelta(0, 1_800_000, 7),
                CpuFreqDelta(4, 2_000_000, 30),
            ),
            diff.cpuFreqDelta,
        )

        val rebooted = SnapshotDiffer.diff(ref, now.copy(clock = now.clock.copy(bootCount = 2)))
        assertFalse(rebooted.validity.kernelCounters)
        assertTrue(rebooted.cpuFreqDelta.isEmpty())
    }

    @Test
    fun `negative cpu freq entry is dropped with a problem`() {
        val ref = snapshot(elapsed = 1_000).copy(cpuFreq = listOf(CpuFreqTime(0, 600_000, 100), CpuFreqTime(0, 1_200_000, 5)))
        val now =
            snapshot(elapsed = 2_000, duration = 2_000).copy(
                cpuFreq = listOf(CpuFreqTime(0, 600_000, 90), CpuFreqTime(0, 1_200_000, 9)),
            )

        val diff = SnapshotDiffer.diff(ref, now)

        assertEquals(listOf(CpuFreqDelta(0, 1_200_000, 4)), diff.cpuFreqDelta)
        assertTrue(diff.validity.problems.contains("negative_cpu_freq:0:600000"))
    }

    @Test
    fun `system timers missing on either side give empty or null deltas`() {
        val bare = snapshot(elapsed = 1_000, systemStart = 100_000)
        val withData = withTimers(snapshot(elapsed = 2_000, duration = 2_000), listOf(1, 2, 3, 4, 5), scanning = 3, radio = 4)
        val diff = SnapshotDiffer.diff(bare, withData)

        assertTrue(diff.signalStrengthMs.isEmpty())
        assertNull(diff.signalScanningMs)
        assertNull(diff.mobileRadioActiveMs)
        assertTrue(diff.validity.problems.none { it.startsWith("negative_signal") || it.startsWith("negative_mobile") })
        assertNull(SnapshotDiffer.diff(snapshot(elapsed = 1_000), snapshot(elapsed = 2_000, duration = 2_000)).mobileRadioActiveMs)
    }

    @Test
    fun `uid sensors diff by handle and treat an added sensor as starting at zero`() {
        val gps = UidSensor(-10_000, totalMs = 1_000, bgMs = 400, count = 2)
        val hal = UidSensor(7, totalMs = 50, bgMs = 0, count = 1)
        val diff =
            SnapshotDiffer.diff(
                snapshot(elapsed = 1_000, uids = listOf(uid(totalMah = 1.0).copy(sensors = listOf(gps)))),
                snapshot(
                    elapsed = 2_000,
                    duration = 2_000,
                    uids =
                        listOf(
                            uid(totalMah = 1.0).copy(
                                sensors = listOf(gps.copy(totalMs = 1_600, bgMs = 900, count = 3), hal),
                            ),
                        ),
                ),
            )

        val delta = diff.uids.single()
        assertTrue(delta.valid)
        assertEquals(
            listOf(UidSensor(-10_000, 600, 500, 1), UidSensor(7, 50, 0, 1)),
            delta.sensors,
        )
    }

    @Test
    fun `negative uid sensor delta invalidates only that uid delta`() {
        val diff =
            SnapshotDiffer.diff(
                snapshot(
                    elapsed = 1_000,
                    uids =
                        listOf(
                            uid(totalMah = 1.0).copy(sensors = listOf(UidSensor(7, 100, 10, 2))),
                            uid(uid = 10_002, totalMah = 1.0),
                        ),
                ),
                snapshot(
                    elapsed = 2_000,
                    duration = 2_000,
                    uids =
                        listOf(
                            uid(totalMah = 1.0).copy(sensors = listOf(UidSensor(7, 90, 10, 2))),
                            uid(uid = 10_002, totalMah = 1.0),
                        ),
                ),
            )

        assertTrue(diff.validity.batteryStats)
        assertFalse(diff.uids.first().valid)
        assertTrue(diff.uids.last().valid)
        assertEquals(
            -10L,
            diff.uids
                .first()
                .sensors
                .single()
                .totalMs,
        )
        assertTrue(diff.validity.problems.contains("negative_uid_sensor:10001:7"))
        assertTrue(diff.validity.problems.contains("negative_uid:10001"))
    }

    @Test
    fun `alarm dump wakeups diff on their own and never mix into batterystats alarm deltas`() {
        val diff =
            SnapshotDiffer.diff(
                snapshot(
                    elapsed = 1_000,
                    uids =
                        listOf(
                            uid(totalMah = 1.0).copy(
                                wakeupAlarms = listOf(UidAlarm("sync", 10)),
                                alarmDumpWakeups = listOf(UidAlarm("sync", 3)),
                            ),
                        ),
                ),
                snapshot(
                    elapsed = 2_000,
                    duration = 2_000,
                    uids =
                        listOf(
                            uid(totalMah = 1.0).copy(
                                wakeupAlarms = listOf(UidAlarm("sync", 15)),
                                alarmDumpWakeups = listOf(UidAlarm("sync", 4), UidAlarm("new", 2)),
                            ),
                        ),
                ),
            )

        val delta = diff.uids.single()
        assertTrue(delta.valid)
        assertEquals(listOf(UidAlarm("sync", 5)), delta.wakeupAlarms)
        assertEquals(listOf(UidAlarm("new", 2), UidAlarm("sync", 1)), delta.alarmDumpWakeups)
    }

    @Test
    fun `negative alarm dump wakeup delta invalidates only that uid delta`() {
        val diff =
            SnapshotDiffer.diff(
                snapshot(elapsed = 1_000, uids = listOf(uid(totalMah = 1.0).copy(alarmDumpWakeups = listOf(UidAlarm("sync", 5))))),
                snapshot(
                    elapsed = 2_000,
                    duration = 2_000,
                    uids = listOf(uid(totalMah = 1.0).copy(alarmDumpWakeups = listOf(UidAlarm("sync", 2)))),
                ),
            )

        assertFalse(diff.uids.single().valid)
        assertEquals(
            -3L,
            diff.uids
                .single()
                .alarmDumpWakeups
                .single()
                .count,
        )
        assertTrue(diff.validity.problems.contains("negative_uid_alarm_dump:10001:sync"))
    }

    @Test
    fun `per uid name deltas merge duplicates sort by name and report negatives in order`() {
        val diff =
            SnapshotDiffer.diff(
                snapshot(
                    elapsed = 1_000,
                    uids =
                        listOf(
                            uid(totalMah = 1.0).copy(
                                partialWakelocks =
                                    listOf(
                                        UidWakelock("b", 100, 10, 1),
                                        UidWakelock("a", 50, 5, 1),
                                        UidWakelock("b", 20, 2, 1),
                                    ),
                                wakeupAlarms = listOf(UidAlarm("z", 2), UidAlarm("w", 2), UidAlarm("y", 1), UidAlarm("z", 3)),
                                sensors = listOf(UidSensor(7, 100, 10, 2), UidSensor(-10_000, 1_000, 400, 2), UidSensor(7, 50, 0, 1)),
                                alarmDumpWakeups = listOf(UidAlarm("s", 1), UidAlarm("s", 1)),
                            ),
                        ),
                ),
                snapshot(
                    elapsed = 2_000,
                    duration = 2_000,
                    uids =
                        listOf(
                            uid(totalMah = 1.0).copy(
                                partialWakelocks =
                                    listOf(
                                        UidWakelock("c", 7, 0, 1),
                                        UidWakelock("b", 200, 30, 3),
                                        UidWakelock("a", 40, 5, 1),
                                    ),
                                wakeupAlarms = listOf(UidAlarm("y", 4), UidAlarm("z", 9), UidAlarm("x", 1)),
                                sensors = listOf(UidSensor(7, 200, 20, 4), UidSensor(3, 5, 0, 1), UidSensor(-10_000, 900, 400, 2)),
                                alarmDumpWakeups = listOf(UidAlarm("s", 5), UidAlarm("r", 1)),
                            ),
                        ),
                ),
            )

        val delta = diff.uids.single()
        assertEquals(
            listOf(UidWakelock("a", -10, 0, 0), UidWakelock("b", 80, 18, 1), UidWakelock("c", 7, 0, 1)),
            delta.partialWakelocks,
        )
        assertEquals(
            listOf(UidAlarm("w", -2), UidAlarm("x", 1), UidAlarm("y", 3), UidAlarm("z", 4)),
            delta.wakeupAlarms,
        )
        assertEquals(
            listOf(UidSensor(-10_000, -100, 0, 0), UidSensor(3, 5, 0, 1), UidSensor(7, 50, 10, 1)),
            delta.sensors,
        )
        assertEquals(listOf(UidAlarm("r", 1), UidAlarm("s", 3)), delta.alarmDumpWakeups)
        assertFalse(delta.valid)
        assertEquals(
            listOf(
                "negative_uid_wakelock:10001:a",
                "negative_uid_alarm:10001:w",
                "negative_uid_sensor:10001:-10000",
                "negative_uid:10001",
            ),
            diff.validity.problems.filter { it.startsWith("negative_uid") },
        )
    }

    @Test
    fun `negative kernel wakelock is reported without invalidating kernel counters session`() {
        val diff =
            SnapshotDiffer.diff(
                snapshot(elapsed = 1_000, kernelWakelocks = listOf(KernelWakelock("sensor", 10, 1))),
                snapshot(elapsed = 2_000, duration = 2_000, kernelWakelocks = listOf(KernelWakelock("sensor", 9, 1))),
            )

        assertTrue(diff.validity.kernelCounters)
        assertEquals(setOf("sensor"), diff.validity.invalidKernelWakelocks)
        assertEquals(-1, diff.kernelWakelocks.single().durationMs)
        assertTrue(diff.validity.problems.contains("negative_kernel_wakelock:sensor"))
    }

    @Test
    fun `missing rows and changed proven installs never create valid zero baselines`() {
        val ref = snapshot(elapsed = 1_000, uids = listOf(uid(totalMah = 30.0)))
        val missing = SnapshotDiffer.diff(ref, snapshot(elapsed = 2_000, duration = 2_000))
        assertFalse(missing.uids.single().valid)
        val replacement =
            uid(totalMah = 50.0).copy(
                ownerInstallFingerprint = UidOwnerFingerprint.create(10_001, 7, listOf(UidOwnerInstall("test.owner", 10_001, 456))),
            )
        val changed = SnapshotDiffer.diff(ref, snapshot(elapsed = 2_000, duration = 2_000, uids = listOf(replacement)))
        assertTrue(changed.uids.single().packageChanged)
        assertFalse(changed.uids.single().valid)
        assertEquals(20.0, changed.uids.single().totalMah, 0.0)
        assertTrue(changed.validity.problems.contains("uid_identity_changed:10001"))
    }

    @Test
    fun `an unknown or process-qualified package name keeps the uid baseline`() {
        listOf(
            null to "com.example.app",
            "com.example.app" to null,
            "com.example.app" to "com.example.app:remote",
            "shared.owner.a" to "shared.owner.b",
        ).forEach { (before, after) ->
            val delta =
                SnapshotDiffer
                    .diff(
                        snapshot(elapsed = 1_000, uids = listOf(uid(totalMah = 30.0, packageName = before))),
                        snapshot(elapsed = 2_000, duration = 2_000, uids = listOf(uid(totalMah = 50.0, packageName = after))),
                    ).uids
                    .single()
            assertFalse("$before -> $after", delta.packageChanged)
            assertTrue("$before -> $after", delta.valid)
            assertEquals("$before -> $after", 20.0, delta.totalMah, 0.0)
        }
    }

    @Test
    fun `legacy malformed and cross scope identity with positive counters is unknown not changed`() {
        val ref = snapshot(elapsed = 1_000, uids = listOf(uid(totalMah = 30.0)), components = mapOf("CPU" to 1.0))
        val now = snapshot(elapsed = 2_000, duration = 2_000, uids = listOf(uid(totalMah = 50.0)), components = mapOf("CPU" to 2.0))
        val scope = requireNotNull(ref.uidIdentityScope)
        val unknowns =
            listOf(
                now.copy(uidIdentityScope = null),
                now.copy(uidIdentityScope = scope.copy(userSerial = 8)),
                now.copy(uidIdentityScope = scope.copy(userId = 1)),
                now.copy(uidIdentityScope = scope.copy(readerEpoch = "")),
                now.copy(uidIdentityScope = scope.copy(packageChangeSequence = -1)),
                now.copy(clock = now.clock.copy(bootCount = 2)),
                now.copy(uids = listOf(now.uids.single().copy(ownerInstallFingerprint = null))),
                now.copy(uids = listOf(now.uids.single().copy(ownerInstallFingerprint = "v2:" + "a".repeat(64)))),
            )
        for (unknown in unknowns) {
            val unknownRef =
                ref.copy(
                    uidIdentityScope = unknown.uidIdentityScope,
                    uids = listOf(ref.uids.single().copy(ownerInstallFingerprint = unknown.uids.single().ownerInstallFingerprint)),
                    clock = ref.clock.copy(bootCount = unknown.clock.bootCount),
                )
            for ((before, after) in listOf(ref to unknown, unknownRef to now)) {
                val result = SnapshotDiffer.diff(before, after)
                assertFalse(result.uids.single().valid)
                assertFalse(result.uids.single().packageChanged)
                assertTrue(result.validity.problems.contains("uid_identity_unknown:10001"))
                assertEquals(1.0, result.deviceComponentsMah.getValue("CPU"), 0.0)
            }
        }
        val systemRef = ref.copy(uidIdentityScope = null, uids = listOf(uid(uid = 1_000, totalMah = 30.0)))
        val systemNow = now.copy(uidIdentityScope = null, uids = listOf(uid(uid = 1_000, totalMah = 50.0)))
        assertTrue(
            SnapshotDiffer
                .diff(systemRef, systemNow)
                .uids
                .single()
                .valid,
        )
    }

    @Test
    fun `cross-process scopes in one user and boot compare app uids by owner fingerprint`() {
        val ref = snapshot(elapsed = 1_000, uids = listOf(uid(totalMah = 30.0)))
        val now = snapshot(elapsed = 2_000, duration = 2_000, uids = listOf(uid(totalMah = 50.0)))
        val scope = requireNotNull(ref.uidIdentityScope)
        val laterProcess = scope.copy(readerEpoch = "new-reader", packageChangeSequence = 42)

        val valid = SnapshotDiffer.diff(ref, now.copy(uidIdentityScope = laterProcess))
        assertTrue(valid.uids.single().valid)
        assertFalse(valid.uids.single().packageChanged)
        assertEquals(20.0, valid.uids.single().totalMah, 0.0)
        assertTrue(valid.validity.problems.none { it.startsWith("uid_identity") })

        val reinstalled =
            uid(totalMah = 50.0).copy(
                ownerInstallFingerprint = UidOwnerFingerprint.create(10_001, 7, listOf(UidOwnerInstall("test.owner", 10_001, 456))),
            )
        val changed = SnapshotDiffer.diff(ref, now.copy(uidIdentityScope = laterProcess, uids = listOf(reinstalled)))
        assertFalse(changed.uids.single().valid)
        assertTrue(changed.uids.single().packageChanged)
        assertTrue(changed.validity.problems.contains("uid_identity_changed:10001"))

        for (unknown in listOf(
            now.copy(uidIdentityScope = null),
            now.copy(uidIdentityScope = laterProcess.copy(userSerial = 8)),
        )) {
            val result = SnapshotDiffer.diff(ref, unknown)
            assertFalse(result.uids.single().valid)
            assertFalse(result.uids.single().packageChanged)
            assertTrue(result.validity.problems.contains("uid_identity_unknown:10001"))
        }
    }

    @Test
    fun `light snapshots calculate deep sleep from elapsed and uptime`() {
        val diff =
            SnapshotDiffer.diff(
                snapshot(kind = SnapshotKind.LIGHT, elapsed = 1_000, uptime = 600),
                snapshot(kind = SnapshotKind.LIGHT, elapsed = 11_000, uptime = 2_600, duration = 2_000),
            )

        assertEquals(8_000, diff.sleep!!.deepSleepMs)
        assertNull(diff.sleep.screenOffMs)
    }

    @Test
    fun `screen off resolution handles long history and consecutive off markers`() =
        runTest {
            val events =
                (0 until 2_000).flatMap { i ->
                    listOf(
                        history(3L * i, HistoryKind.SCREEN_OFF),
                        history(3L * i + 1, HistoryKind.SCREEN_OFF),
                        history(3L * i + 2, HistoryKind.SCREEN_ON),
                    )
                }
            val snapshots = listOf(snapshot(id = 1, wall = 5_998, elapsed = 5_998), snapshot(id = 2, wall = 5_999, elapsed = 5_999))
            val resolver = ReferenceResolver(FakeStore(snapshots, events, emptyMap()))
            val resolved = resolver.resolve(ReferenceKind.SinceScreenOff, 6_000)!!
            assertEquals(5_998L, resolved.boundaryFromWallMs)
            assertEquals(5_999L, resolved.boundaryToWallMs)
        }

    @Test
    fun `resolver supports all kinds and reports uncovered gaps`() =
        runTest {
            val snapshots =
                listOf(
                    snapshot(id = 1, wall = 1_000, elapsed = 1_000),
                    snapshot(id = 2, wall = 2_000, elapsed = 2_000, duration = 2_000, trigger = CaptureTrigger.SCREEN_OFF),
                    snapshot(id = 3, wall = 3_000, elapsed = 3_000, duration = 3_000, trigger = CaptureTrigger.SCREEN_ON),
                    snapshot(id = 4, wall = 5_000, elapsed = 5_000, duration = 5_000),
                )
            val store =
                FakeStore(
                    snapshots = snapshots,
                    history =
                        listOf(
                            history(1_500, HistoryKind.UNPLUGGED),
                            history(2_000, HistoryKind.SCREEN_OFF),
                            history(3_000, HistoryKind.SCREEN_ON),
                        ),
                    markers = mapOf(7L to (1_000L to snapshots.first())),
                )
            val resolver = ReferenceResolver(store)

            assertNotNull(resolver.resolve(ReferenceKind.SinceUnplugged, 5_000))
            assertEquals(2_000, resolver.resolve(ReferenceKind.SinceScreenOff, 5_000)!!.boundaryFromWallMs)
            assertNotNull(resolver.resolve(ReferenceKind.ScreenOnSession, 5_000))
            assertNotNull(resolver.resolve(ReferenceKind.Last24h, 5_000))
            assertNotNull(resolver.resolve(ReferenceKind.CustomMarker(7), 5_000))
            val sliced = resolver.resolve(ReferenceKind.TimeSlice(1_500, 4_000), 5_000)!!
            assertEquals(1_500, sliced.gapMs)
            assertTrue(sliced.perUidAvailable)
        }

    @Test
    fun `resolver falls back to LIGHT when the first FULL is after the interval`() =
        runTest {
            val off = snapshot(id = 1, wall = 2_000, elapsed = 2_000, kind = SnapshotKind.LIGHT, trigger = CaptureTrigger.SCREEN_OFF)
            val on = snapshot(id = 2, wall = 3_000, elapsed = 3_000, kind = SnapshotKind.LIGHT, trigger = CaptureTrigger.SCREEN_ON)
            val later = snapshot(id = 3, wall = 5_000, elapsed = 5_000)
            val resolver =
                ReferenceResolver(
                    FakeStore(
                        snapshots = listOf(off, on, later),
                        history = listOf(history(2_000, HistoryKind.SCREEN_OFF), history(3_000, HistoryKind.SCREEN_ON)),
                        markers = emptyMap(),
                    ),
                )

            for (kind in listOf(ReferenceKind.SinceScreenOff, ReferenceKind.TimeSlice(2_000, 3_000))) {
                val resolved = resolver.resolve(kind, 5_000)!!
                assertEquals(off, resolved.ref)
                assertEquals(on, resolved.now)
                assertEquals(0L, resolved.gapMs)
                assertFalse(resolved.perUidAvailable)
            }
        }

    private fun snapshot(
        id: Long = 0,
        kind: SnapshotKind = SnapshotKind.FULL,
        trigger: CaptureTrigger = CaptureTrigger.APP_OPEN,
        wall: Long = 1_000,
        elapsed: Long,
        uptime: Long = elapsed / 2,
        boot: Int = 1,
        statsStart: Long = 100_000,
        duration: Long = 1_000,
        systemStart: Long? = null,
        uids: List<UidSnapshot> = emptyList(),
        components: Map<String, Double> = emptyMap(),
        kernelWakelocks: List<KernelWakelock> = emptyList(),
        level: Int? = null,
        discharged: Double? = null,
    ) = BatterySnapshot(
        id = id,
        kind = kind,
        trigger = trigger,
        clock = SnapshotClock(wall, elapsed, uptime, boot, statsStart, duration),
        battery = BatteryState(level, null, null, null, null, null, null, discharged),
        system = systemStart?.let { start -> systemTimers(start) },
        uids = uids,
        deviceComponentsMah = components,
        kernelWakelocks = kernelWakelocks,
        wakeupReasons = emptyList(),
        cpuFreq = emptyList(),
        availability = emptyMap(),
        truncatedUids = 0,
        uidIdentityScope = if (kind == SnapshotKind.FULL) UidIdentityScope(0, 7, "test-reader", 0) else null,
    )

    private fun uid(
        uid: Int = 10_001,
        totalMah: Double,
        packageName: String? = "package",
        components: Map<String, Double> = emptyMap(),
    ) = UidSnapshot(
        uid = uid,
        packageName = packageName,
        totalMah = totalMah,
        fgMah = null,
        bgMah = null,
        fgsMah = null,
        cachedMah = null,
        componentsMah = components,
        cpuUserMs = 0,
        cpuSystemMs = 0,
        fgActivityMs = 0,
        fgServiceMs = 0,
        partialWakelocks = emptyList(),
        wakeupAlarms = emptyList(),
        ownerInstallFingerprint = UidOwnerFingerprint.create(uid, 7, listOf(UidOwnerInstall("test.owner", uid, 123))),
    )

    private fun systemTimers(start: Long) =
        com.akane.inspiration.telemetry.dump.SystemTimers(
            startClockTimeMs = start,
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
        )

    private fun withTimers(
        snapshot: BatterySnapshot,
        bins: List<Long>,
        scanning: Long?,
        radio: Long?,
    ) = snapshot.copy(
        system =
            systemTimers(100_000).copy(
                signalStrengthMs = bins,
                signalScanningMs = scanning,
                mobileRadioActiveMs = radio,
            ),
    )

    private fun discharge(
        snapshot: BatterySnapshot,
        total: Double,
        screenOff: Double,
    ) = snapshot.copy(
        system = systemTimers(100_000).copy(dischargeTotalMah = total, dischargeScreenOffMah = screenOff),
    )

    private fun history(
        wallMs: Long,
        kind: Int,
    ) = HistoryEvent(0, wallMs, kind, 0, -1, 0, 0, 0)

    private class FakeStore(
        private val snapshots: List<BatterySnapshot>,
        private val history: List<HistoryEvent>,
        private val markers: Map<Long, Pair<Long, BatterySnapshot>>,
    ) : SnapshotStore {
        override suspend fun insert(snapshot: BatterySnapshot): Long = error("unused")

        override suspend fun byId(id: Long): BatterySnapshot? = snapshots.firstOrNull { it.id == id }

        override suspend fun latest(kind: SnapshotKind?): BatterySnapshot? = snapshots.filterKind(kind).maxByOrNull { it.clock.wallMs }

        override suspend fun firstAtOrAfter(
            wallMs: Long,
            kind: SnapshotKind?,
            statsStartMs: Long?,
            bootCount: Int?,
        ) = snapshots
            .filterKind(kind)
            .filter { it.clock.wallMs >= wallMs }
            .filter { statsStartMs == null || it.clock.statsStartMs == statsStartMs }
            .filter { bootCount == null || it.clock.bootCount == bootCount }
            .minByOrNull { it.clock.wallMs }

        override suspend fun lastAtOrBefore(
            wallMs: Long,
            kind: SnapshotKind?,
        ) = snapshots
            .filterKind(kind)
            .filter { it.clock.wallMs <= wallMs }
            .maxByOrNull { it.clock.wallMs }

        override suspend fun latestByTrigger(trigger: CaptureTrigger) =
            snapshots.filter { it.trigger == trigger }.maxByOrNull { it.clock.wallMs }

        override suspend fun snapshotsBetween(
            fromWallMs: Long,
            toWallMs: Long,
        ) = snapshots.filter { it.clock.wallMs in fromWallMs..toWallMs }.sortedBy { it.clock.wallMs }

        override suspend fun insertHistory(
            events: List<HistoryEvent>,
            statsStartMs: Long,
        ) = Unit

        override suspend fun historyBetween(
            fromWallMs: Long,
            toWallMs: Long,
        ) = history.filter { it.wallMs in fromWallMs..toWallMs }

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

        override suspend fun marker(id: Long) = markers[id]

        override suspend fun insertSamples(samples: List<LiveReading>) = Unit

        override suspend fun upsertChargeSession(
            statsStartMs: Long,
            segment: ChargeSegment,
            verdict: SegmentVerdict,
        ) = Unit

        override suspend fun acceptedEstimatesChronological(limit: Int): List<Double> = emptyList()

        override suspend fun recordMiss(
            wallMs: Long,
            trigger: CaptureTrigger,
            reason: String,
        ) = Unit

        override suspend fun prune(nowWallMs: Long) = Unit

        override suspend fun markers(): List<Marker> = emptyList()

        override suspend fun samplesBetween(
            from: Long,
            to: Long,
        ): List<TelemetrySample> = emptyList()

        override suspend fun chargeSessions(): List<ChargeSessionRecord> = emptyList()

        override suspend fun healthSamples(): List<HealthSample> = emptyList()

        override suspend fun insertHealthSample(sample: HealthSample) = Unit

        override suspend fun insertToolkitAction(record: ToolkitActionRecord): Long = error("unused")

        override suspend fun updateToolkitAction(record: ToolkitActionRecord) = Unit

        override suspend fun toolkitActions(limit: Int): List<ToolkitActionRecord> = emptyList()

        override suspend fun pendingToolkitActions(): List<ToolkitActionRecord> = emptyList()

        private fun List<BatterySnapshot>.filterKind(kind: SnapshotKind?) = if (kind == null) this else filter { it.kind == kind }
    }
}
