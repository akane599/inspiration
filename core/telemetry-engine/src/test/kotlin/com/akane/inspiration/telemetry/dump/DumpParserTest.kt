package com.akane.inspiration.telemetry.dump

import com.akane.inspiration.telemetry.proto.AlarmManagerServiceDumpProto
import com.akane.inspiration.telemetry.proto.BatteryStatsProto
import com.akane.inspiration.telemetry.proto.BatteryStatsServiceDumpProto
import com.akane.inspiration.telemetry.proto.PowerManagerServiceDumpProto
import com.akane.inspiration.telemetry.proto.SystemProto
import com.akane.inspiration.telemetry.proto.TimerProto
import com.akane.inspiration.telemetry.proto.UidProto
import com.google.protobuf.ByteString
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DumpParserTest {
    @Test
    fun batteryStatsFixturesExposeExpectedRawCounts() {
        val expected = listOf(
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
    fun powerFixturesExposeWakeLocksAndSuspendBlockers() {
        val expectedWakeLocks = listOf(2, 0, 1)
        (35..37).forEachIndexed { index, api ->
            val bytes = fixture("power-api$api.pb")
            val dump = PowerManagerServiceDumpProto.parseFrom(bytes)
            assertEquals(expectedWakeLocks[index], dump.wakeLocksCount)
            assertEquals(5, dump.suspendBlockersCount)
            assertEquals(
                expectedWakeLocks[index],
                ((DumpParser.parsePower(bytes) as DumpParse.Ok).value.heldWakeLocks.size),
            )
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
                expectedPending[index],
                (DumpParser.parseAlarm(bytes) as DumpParse.Ok).value.pendingWakeups,
            )
        }
    }

    @Test
    fun parserMergesKernelWakelocksAndSumsWakeupAlarmsByTag() {
        val timer = TimerProto.newBuilder().setDurationMs(4).setCount(2).build()
        val stats = BatteryStatsProto.newBuilder()
            .setReportVersion(36)
            .setSystem(
                SystemProto.newBuilder()
                    .addKernelWakelock(SystemProto.KernelWakelock.newBuilder().setName("").setTotal(timer))
                    .addKernelWakelock(SystemProto.KernelWakelock.newBuilder().setName("").setTotal(timer))
                    .build(),
            ).addUids(
                UidProto.newBuilder()
                    .setUid(1000)
                    .addWakeupAlarm(UidProto.WakeupAlarm.newBuilder().setName("tag").setCount(2))
                    .addWakeupAlarm(UidProto.WakeupAlarm.newBuilder().setName("tag").setCount(3))
                    .build(),
            ).build()

        val result = DumpParser.parseBatteryStats(
            BatteryStatsServiceDumpProto.newBuilder().setBatterystats(stats).build().toByteArray(),
        )

        assertTrue(result is DumpParse.Ok)
        val snapshot = (result as DumpParse.Ok).value
        assertEquals(listOf(KernelWakelock("<unnamed>", 8, 4)), snapshot.kernelWakelocks)
        assertEquals(listOf(UidAlarm("tag", 5)), snapshot.uids.single().wakeupAlarms)
    }

    @Test
    fun parserRejectsMalformedRootsAndWarnsForNewerReports() {
        assertTrue(DumpParser.parseBatteryStats(byteArrayOf()).isUnavailable())
        assertTrue(DumpParser.parseBatteryStats(byteArrayOf(0)).isUnavailable())
        assertTrue(DumpParser.parseBatteryStats("Permission Denial".encodeToByteArray()).isUnavailable())

        val futureStats = BatteryStatsProto.newBuilder().setReportVersion(37).build()
        val futureResult = DumpParser.parseBatteryStats(
            BatteryStatsServiceDumpProto.newBuilder().setBatterystats(futureStats).build().toByteArray(),
        )
        assertTrue(futureResult is DumpParse.Ok)
        assertTrue((futureResult as DumpParse.Ok).value.warnings.single().contains("37"))
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
        val stats = BatteryStatsProto.newBuilder()
            .setReportVersion(36)
            .setSystem(
                SystemProto.newBuilder().addKernelWakelock(
                    SystemProto.KernelWakelock.newBuilder()
                        .setNameBytes(invalidName)
                        .build(),
                ).build(),
            ).build()
        assertTrue(
            DumpParser.parseBatteryStats(
                BatteryStatsServiceDumpProto.newBuilder().setBatterystats(stats).build().toByteArray(),
            ) is DumpParse.Ok,
        )
    }

    @Test
    fun readerReturnsBytesOrNullAndAlwaysCloses() {
        val input = ClosingInputStream(byteArrayOf(1, 2, 3))
        assertArrayEquals(byteArrayOf(1, 2, 3), DumpReader.readAll(input))
        assertTrue(input.closed)
        assertNull(DumpReader.readAll(ByteArrayInputStream(byteArrayOf(1, 2, 3)), maxBytes = 2))

        val failing = FailingInputStream()
        assertNull(DumpReader.readAll(failing))
        assertTrue(failing.closed)
    }

    private fun fixture(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/proto/$name")).use { it.readBytes() }

    private fun DumpParse<*>.isUnavailable(): Boolean = this is DumpParse.Unavailable

    private data class FixtureCounts(
        val api: Int,
        val uids: Int,
        val kernelWakelocks: Int,
        val uidWakelocks: Int,
        val wakeupAlarms: Int,
    )

    private class ClosingInputStream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var closed = false

        override fun close() {
            closed = true
            super.close()
        }
    }

    private class FailingInputStream : InputStream() {
        var closed = false

        override fun read(): Int = throw IOException("expected")

        override fun close() {
            closed = true
        }
    }
}
