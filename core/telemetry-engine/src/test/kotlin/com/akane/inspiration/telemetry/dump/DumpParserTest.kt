package com.akane.inspiration.telemetry.dump

import com.akane.inspiration.telemetry.proto.AlarmManagerServiceDumpProto
import com.akane.inspiration.telemetry.proto.BatteryStatsProto
import com.akane.inspiration.telemetry.proto.BatteryStatsServiceDumpProto
import com.akane.inspiration.telemetry.proto.SystemProto
import com.akane.inspiration.telemetry.proto.TimerProto
import com.akane.inspiration.telemetry.proto.UidProto
import com.google.protobuf.ByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DumpParserTest {
    @Test
    fun batteryStatsFixturesExposeExpectedRawCounts() {
        val expected =
            listOf(
                FixtureCounts(api = 35, uids = 66, kernelWakelocks = 66, uidWakelocks = 25, wakeupAlarms = 3),
                FixtureCounts(api = 36, uids = 70, kernelWakelocks = 68, uidWakelocks = 133, wakeupAlarms = 5),
                FixtureCounts(api = 37, uids = 70, kernelWakelocks = 69, uidWakelocks = 113, wakeupAlarms = 4),
            )

        expected.forEach { counts ->
            val dump = BatteryStatsServiceDumpProto.parseFrom(fixture("batterystats-api${counts.api}.pb"))
            val stats = dump.batterystats
            assertEquals(counts.uids, stats.uidsCount)
            assertEquals(counts.kernelWakelocks, stats.system.kernelWakelockCount)
            assertEquals(counts.uidWakelocks, stats.uidsList.sumOf { it.wakelocksCount })
            assertEquals(counts.wakeupAlarms, stats.uidsList.sumOf { it.wakeupAlarmCount })
        }
    }

    @Test
    fun alarmFixturesExposeAlarmStatsAndPendingAlarms() {
        val expectedPending = listOf(56, 49, 49)
        (35..37).forEachIndexed { index, api ->
            val bytes = fixture("alarm-api$api.pb")
            val dump = AlarmManagerServiceDumpProto.parseFrom(bytes)
            assertEquals(5, dump.alarmStatsCount)
            assertEquals(expectedPending[index], dump.pendingAlarmsCount)
            assertEquals(
                dump.alarmStatsList.sumOf { it.filtersList.size },
                (DumpParser.parseAlarm(bytes) as DumpParse.Ok).value.filterStats.size,
            )
        }
    }

    @Test
    fun alarmParserRejectsZeroBytesFromAClosedWriter() {
        assertEquals(DumpParse.Unavailable("malformed alarm dump"), DumpParser.parseAlarm(byteArrayOf()))
    }

    @Test
    fun alarmParserAcceptsNonemptyDeviceMessagesWithoutAlarms() {
        (35..37).forEach { api ->
            // Keep the genuine dump's metadata/unknown fields, but remove both alarm lists.
            val bytes =
                AlarmManagerServiceDumpProto
                    .parseFrom(fixture("alarm-api$api.pb"))
                    .toBuilder()
                    .clearAlarmStats()
                    .clearPendingAlarms()
                    .build()
                    .toByteArray()

            assertTrue("api$api retains a nonempty root", bytes.isNotEmpty())
            assertEquals("api$api", DumpParse.Ok(AlarmDump(emptyList())), DumpParser.parseAlarm(bytes))
        }
    }

    @Test
    fun parserMergesKernelWakelocksAndSumsWakeupAlarmsByTag() {
        val timer =
            TimerProto
                .newBuilder()
                .setDurationMs(4)
                .setCount(2)
                .build()
        val stats =
            BatteryStatsProto
                .newBuilder()
                .setReportVersion(36)
                .setSystem(
                    SystemProto
                        .newBuilder()
                        .addKernelWakelock(
                            SystemProto.KernelWakelock
                                .newBuilder()
                                .setName("")
                                .setTotal(timer),
                        ).addKernelWakelock(
                            SystemProto.KernelWakelock
                                .newBuilder()
                                .setName("")
                                .setTotal(timer),
                        ).build(),
                ).addUids(
                    UidProto
                        .newBuilder()
                        .setUid(1000)
                        .addWakeupAlarm(
                            UidProto.WakeupAlarm
                                .newBuilder()
                                .setName("tag")
                                .setCount(2),
                        ).addWakeupAlarm(
                            UidProto.WakeupAlarm
                                .newBuilder()
                                .setName("tag")
                                .setCount(3),
                        ).build(),
                ).build()

        val result =
            DumpParser.parseBatteryStats(
                BatteryStatsServiceDumpProto
                    .newBuilder()
                    .setBatterystats(stats)
                    .build()
                    .toByteArray(),
            )

        assertTrue(result is DumpParse.Ok)
        val snapshot = (result as DumpParse.Ok).value
        assertEquals(listOf(KernelWakelock("<unnamed>", 8, 4)), snapshot.kernelWakelocks)
        assertEquals(listOf(UidAlarm("tag", 5)), snapshot.uids.single().wakeupAlarms)
    }

    @Test
    fun parserReadsSignalBinsScanningRadioActiveAndUidSensorsExactly() {
        fun timer(
            durationMs: Long,
            count: Long = 0,
        ) = TimerProto.newBuilder().setDurationMs(durationMs).setCount(count)

        fun bin(
            name: Int,
            ms: Long,
        ) = SystemProto.PhoneSignalStrength
            .newBuilder()
            .setName(name)
            .setTotal(timer(ms))

        fun sensor(
            id: Int,
            total: Long,
            count: Long,
            bg: Long,
        ) = UidProto.Sensor
            .newBuilder()
            .setId(id)
            .setApportioned(timer(total, count))
            .setBackground(timer(bg))
        val stats =
            BatteryStatsProto
                .newBuilder()
                .setReportVersion(36)
                .setSystem(
                    SystemProto
                        .newBuilder()
                        .setMisc(SystemProto.Misc.newBuilder().setMobileRadioActiveDurationMs(4_321))
                        .addPhoneSignalStrength(bin(3, 300))
                        .addPhoneSignalStrength(bin(0, 100))
                        .addPhoneSignalStrength(bin(4, 500))
                        .addPhoneSignalStrength(bin(1, 200))
                        .addPhoneSignalStrength(bin(2, 250))
                        .addPhoneSignalStrength(bin(9, 999))
                        .setSignalScanning(timer(777)),
                ).addUids(
                    UidProto
                        .newBuilder()
                        .setUid(10_050)
                        .addSensors(sensor(-10_000, total = 9_000, count = 3, bg = 2_000))
                        .addSensors(sensor(21, total = 1_100, count = 5, bg = 0))
                        .addSensors(sensor(34, total = 60, count = 1, bg = 60))
                        .addSensors(sensor(34, total = 40, count = 2, bg = 10)),
                ).addUids(UidProto.newBuilder().setUid(10_051))
                .build()

        val snapshot = (parse(stats) as DumpParse.Ok).value

        assertEquals(listOf(100L, 200L, 250L, 300L, 500L), snapshot.system.signalStrengthMs)
        assertEquals(777L, snapshot.system.signalScanningMs)
        assertEquals(4_321L, snapshot.system.mobileRadioActiveMs)
        assertEquals(
            listOf(
                UidSensor(handle = -10_000, totalMs = 9_000, bgMs = 2_000, count = 3),
                UidSensor(handle = 21, totalMs = 1_100, bgMs = 0, count = 5),
                UidSensor(handle = 34, totalMs = 100, bgMs = 70, count = 3),
            ),
            snapshot.uids.first().sensors,
        )
        assertTrue(
            snapshot.uids
                .last()
                .sensors
                .isEmpty(),
        )
    }

    @Test
    fun parserLeavesNewFieldsEmptyWhenAbsentAndZeroFillsMissingBins() {
        val bare = (parse(BatteryStatsProto.newBuilder().setReportVersion(36).build()) as DumpParse.Ok).value
        assertTrue(bare.system.signalStrengthMs.isEmpty())
        assertNull(bare.system.signalScanningMs)
        assertNull(bare.system.mobileRadioActiveMs)

        val partial =
            BatteryStatsProto
                .newBuilder()
                .setReportVersion(36)
                .setSystem(
                    SystemProto.newBuilder().addPhoneSignalStrength(
                        SystemProto.PhoneSignalStrength
                            .newBuilder()
                            .setName(2)
                            .setTotal(TimerProto.newBuilder().setDurationMs(8)),
                    ),
                ).build()
        assertEquals(listOf(0L, 0L, 8L, 0L, 0L), (parse(partial) as DumpParse.Ok).value.system.signalStrengthMs)
    }

    // The vendored fixtures are real emulator dumps and carry the F2 fields (no signal_scanning); pinning
    // them corroborates the field numbers against genuine device output, not only the synthetic message.
    @Test
    fun realFixturesParseWithPinnedSignalRadioAndSensorValues() {
        data class Expected(
            val api: Int,
            val bins: List<Long>,
            val radioMs: Long,
            val sensors: Map<Int, List<UidSensor>>,
        )
        listOf(
            Expected(
                35,
                listOf(0, 0, 0, 0, 20_090),
                20_090,
                mapOf(
                    1000 to listOf(UidSensor(0, 5_691, 5_691, 1)),
                    10143 to listOf(UidSensor(0, 502, 0, 1)),
                ),
            ),
            Expected(
                36,
                listOf(0, 0, 12_135, 7_997, 0),
                20_132,
                mapOf(
                    1000 to listOf(UidSensor(0, 5_680, 5_680, 1)),
                    10146 to listOf(UidSensor(1_600_940_398, 240, 0, 2), UidSensor(1_601_335_158, 239, 0, 2)),
                ),
            ),
            Expected(
                37,
                listOf(0, 0, 8_846, 11_244, 0),
                19_245,
                mapOf(
                    1000 to listOf(UidSensor(0, 5_663, 5_663, 1)),
                    10221 to listOf(UidSensor(1_600_940_398, 0, 0, 0), UidSensor(1_601_335_158, 0, 0, 0)),
                ),
            ),
        ).forEach { expected ->
            val result = DumpParser.parseBatteryStats(fixture("batterystats-api${expected.api}.pb"))
            assertTrue("api${expected.api}", result is DumpParse.Ok)
            val snapshot = (result as DumpParse.Ok).value
            assertEquals("api${expected.api} bins", expected.bins, snapshot.system.signalStrengthMs)
            assertNull("api${expected.api} scanning", snapshot.system.signalScanningMs)
            assertEquals("api${expected.api} radio", expected.radioMs, snapshot.system.mobileRadioActiveMs)
            assertEquals(
                "api${expected.api} sensors",
                expected.sensors,
                snapshot.uids.filter { it.sensors.isNotEmpty() }.associate { it.uid to it.sensors },
            )
        }
    }

    private fun parse(stats: BatteryStatsProto) =
        DumpParser.parseBatteryStats(
            BatteryStatsServiceDumpProto
                .newBuilder()
                .setBatterystats(stats)
                .build()
                .toByteArray(),
        )

    @Test
    fun parserRejectsMalformedRootsAndAcceptsNewerReports() {
        assertTrue(DumpParser.parseBatteryStats(byteArrayOf()).isUnavailable())
        assertTrue(DumpParser.parseBatteryStats(byteArrayOf(0)).isUnavailable())
        assertTrue(DumpParser.parseBatteryStats("Permission Denial".encodeToByteArray()).isUnavailable())

        val futureStats = BatteryStatsProto.newBuilder().setReportVersion(37).build()
        val futureResult =
            DumpParser.parseBatteryStats(
                BatteryStatsServiceDumpProto
                    .newBuilder()
                    .setBatterystats(futureStats)
                    .build()
                    .toByteArray(),
            )
        assertTrue(futureResult is DumpParse.Ok)
    }

    @Test
    fun parserPreservesApi37UnknownEnumAndAcceptsInvalidUtf8() {
        val api37 = BatteryStatsServiceDumpProto.parseFrom(fixture("batterystats-api37.pb"))
        assertTrue(
            api37.batterystats.uidsList
                .flatMap { it.jobCompletionList }
                .flatMap { it.reasonCountList }
                .any { it.name == 14 },
        )

        val invalidName = ByteString.copyFrom(byteArrayOf(0xff.toByte(), 0xfe.toByte()))
        val stats =
            BatteryStatsProto
                .newBuilder()
                .setReportVersion(36)
                .setSystem(
                    SystemProto
                        .newBuilder()
                        .addKernelWakelock(
                            SystemProto.KernelWakelock
                                .newBuilder()
                                .setNameBytes(invalidName)
                                .build(),
                        ).build(),
                ).build()
        assertTrue(
            DumpParser.parseBatteryStats(
                BatteryStatsServiceDumpProto
                    .newBuilder()
                    .setBatterystats(stats)
                    .build()
                    .toByteArray(),
            ) is DumpParse.Ok,
        )
    }

    private fun fixture(name: String): ByteArray = checkNotNull(javaClass.getResourceAsStream("/proto/$name")).use { it.readBytes() }

    private fun DumpParse<*>.isUnavailable(): Boolean = this is DumpParse.Unavailable

    private data class FixtureCounts(
        val api: Int,
        val uids: Int,
        val kernelWakelocks: Int,
        val uidWakelocks: Int,
        val wakeupAlarms: Int,
    )
}
