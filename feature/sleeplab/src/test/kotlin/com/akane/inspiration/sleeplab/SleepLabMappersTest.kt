package com.akane.inspiration.sleeplab

import com.akane.inspiration.designsystem.chart.model.HypnogramData
import com.akane.inspiration.designsystem.chart.model.HypnogramStage
import com.akane.inspiration.designsystem.chart.model.StageSegment
import com.akane.inspiration.designsystem.chart.model.TimeRange
import com.akane.inspiration.diagnostics.analysis.AnalysisState
import com.akane.inspiration.diagnostics.analysis.SegmentKind
import com.akane.inspiration.diagnostics.analysis.Timeline
import com.akane.inspiration.diagnostics.analysis.TimelineSegment
import com.akane.inspiration.diagnostics.model.SensorKind
import com.akane.inspiration.diagnostics.rules.DiagnosticInput
import com.akane.inspiration.diagnostics.rules.DiagnosticRuleEngine
import com.akane.inspiration.telemetry.diff.ReferenceKind
import com.akane.inspiration.telemetry.diff.SnapshotDiffer
import com.akane.inspiration.telemetry.dump.KernelWakelock
import com.akane.inspiration.telemetry.dump.SystemTimers
import com.akane.inspiration.telemetry.dump.UidAlarm
import com.akane.inspiration.telemetry.snapshot.BatterySnapshot
import com.akane.inspiration.telemetry.snapshot.CaptureTrigger
import com.akane.inspiration.telemetry.snapshot.CpuFreqDelta
import com.akane.inspiration.telemetry.snapshot.CpuFreqTime
import com.akane.inspiration.telemetry.snapshot.SleepStats
import com.akane.inspiration.telemetry.snapshot.SnapshotKind
import com.akane.inspiration.telemetry.snapshot.SourceAvailability
import org.junit.Assert.assertEquals
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepLabMappersTest {
    @Test
    fun `identity changes do not rank lifetime counters as interval activity`() {
        val changed =
            uidDelta(
                10_001,
                wakelocks = listOf(wakelock("sync", 60_000, 100)),
                alarms = listOf(UidAlarm("tick", 100)),
            ).copy(packageChanged = true)
        val diff = snapshotDiff(uids = listOf(changed))
        assertTrue((appWakelockRows(diff, true, emptyMap()) as ListUi.Rows).rows.isEmpty())
        assertTrue((alarmRows(diff, true, emptyMap()) as ListUi.Rows).rows.isEmpty())
    }

    /** Audit B-04: one boot and one battery-stats session, so a missing source is "not captured", never a restart. */
    @Test
    fun `missing counter sources render unavailable with their cause rather than empty or cumulative rows`() {
        val base = snapshotDiff(kernel = listOf(KernelWakelock("wlan", 1_000, 1)), cpu = listOf(CpuFreqDelta(0, 1_000, 10)))
        val diff =
            base.copy(
                validity =
                    base.validity.copy(
                        dumpCounters = false,
                        kernelWakelocks = false,
                        cpuFreq = false,
                        alarmDumpCounters = false,
                    ),
            )
        val notCaptured = ListUi.Unavailable(UnavailableCause.NotCaptured)
        assertEquals(notCaptured, appWakelockRows(diff, true, emptyMap()))
        assertEquals(notCaptured, kernelWakelockRows(diff))
        assertEquals(notCaptured, alarmRows(diff, true, emptyMap()))
        assertEquals("two full ends: the kernel reported none", CpuUi.NoData, cpuUi(diff, fullAtBothEnds = true))
        assertEquals("a light end never reads them", CpuUi.NotCaptured, cpuUi(diff, fullAtBothEnds = false))
    }

    // --- Timeline -> HypnogramData ---

    private fun timeline(
        segments: List<TimelineSegment>,
        pulses: List<Long> = emptyList(),
        from: Long = 0,
        to: Long = 1_000,
    ) = Timeline(segments, emptyList(), emptyList(), pulses, from, to)

    private fun stages(data: HypnogramData) = data.segments.map { Triple(it.startMs, it.endMs, it.stage) }

    @Test
    fun `doze stages map one to one and the window is the extent of the screen-off data`() {
        val data =
            hypnogramData(
                timeline(
                    listOf(
                        segment(SegmentKind.LIGHT_IDLE, 100, 200),
                        segment(SegmentKind.DEEP_IDLE, 200, 500),
                        segment(SegmentKind.MAINTENANCE, 500, 520),
                        segment(SegmentKind.LIGHT_IDLE, 520, 600),
                        segment(SegmentKind.AWAKE, 600, 700),
                    ),
                ),
            )

        assertEquals(
            listOf(
                Triple(100L, 200L, HypnogramStage.LIGHT_IDLE),
                Triple(200L, 500L, HypnogramStage.DEEP_IDLE),
                Triple(500L, 520L, HypnogramStage.MAINTENANCE),
                Triple(520L, 600L, HypnogramStage.LIGHT_IDLE),
                Triple(600L, 700L, HypnogramStage.AWAKE),
            ),
            stages(data),
        )
        assertEquals(TimeRange.of(100, 700), data.window)
    }

    @Test
    fun `stage segments are clipped to the screen-off spans and screen-on time is dropped`() {
        // Screen on 300..400. A doze gap that began before it and ran through it is cut into two pieces.
        val data =
            hypnogramData(
                timeline(
                    listOf(
                        segment(SegmentKind.SCREEN_ON, 300, 400),
                        segment(SegmentKind.CHARGING, 0, 1_000),
                        segment(SegmentKind.AWAKE, 250, 450),
                        segment(SegmentKind.DEEP_IDLE, 450, 900),
                    ),
                ),
            )

        assertEquals(
            listOf(
                Triple(250L, 300L, HypnogramStage.AWAKE),
                Triple(400L, 450L, HypnogramStage.AWAKE),
                Triple(450L, 900L, HypnogramStage.DEEP_IDLE),
            ),
            stages(data),
        )
    }

    @Test
    fun `maintenance windows survive and keep their own stage`() {
        val data =
            hypnogramData(
                timeline(
                    listOf(
                        segment(SegmentKind.LIGHT_IDLE, 0, 100),
                        segment(SegmentKind.MAINTENANCE, 100, 108),
                        segment(SegmentKind.LIGHT_IDLE, 108, 200),
                    ),
                ),
            )

        assertEquals(1, data.segments.count { it.stage == HypnogramStage.MAINTENANCE })
        assertEquals(StageSegment(100, 108, HypnogramStage.MAINTENANCE), data.segments.single { it.stage == HypnogramStage.MAINTENANCE })
    }

    @Test
    fun `wake pulses in a screen-off span are kept, including the screen-on that ends it`() {
        val data =
            hypnogramData(
                timeline(
                    listOf(
                        segment(SegmentKind.SCREEN_ON, 500, 800),
                        segment(SegmentKind.LIGHT_IDLE, 100, 300),
                        segment(SegmentKind.DEEP_IDLE, 300, 500),
                    ),
                    // 200: a doze exit. 500: the SCREEN_ON that ends the span. 650: inside screen-on, not a wake-up
                    // of a sleeping device. 50 and 900 fall outside the data.
                    pulses = listOf(50, 200, 500, 650, 900),
                ),
            )

        assertEquals(listOf(200L, 500L), data.wakePulsesMs.toList())
    }

    @Test
    fun `a pulse outside the window of the data is dropped`() {
        val data = hypnogramData(timeline(listOf(segment(SegmentKind.LIGHT_IDLE, 400, 600)), pulses = listOf(100, 500, 800)))

        assertEquals(listOf(500L), data.wakePulsesMs.toList())
    }

    @Test
    fun `no segments at all is empty with the timeline window`() {
        val data = hypnogramData(timeline(emptyList(), pulses = listOf(10, 20)))

        assertTrue(data.segments.isEmpty())
        assertTrue("pulses without data are not drawn", data.wakePulsesMs.isEmpty())
        assertEquals(TimeRange.of(0, 1_000), data.window)
    }

    @Test
    fun `a window that is all screen-on has no screen-off span and is empty`() {
        val data =
            hypnogramData(
                timeline(
                    listOf(
                        segment(SegmentKind.SCREEN_ON, 0, 1_000),
                        segment(SegmentKind.AWAKE, 100, 900),
                        segment(SegmentKind.CHARGING, 0, 50),
                    ),
                    pulses = listOf(0),
                ),
            )

        assertTrue(data.segments.isEmpty())
        assertTrue(data.wakePulsesMs.isEmpty())
    }

    @Test
    fun `screen-off time with no doze history is empty rather than invented`() {
        val data = hypnogramData(timeline(listOf(segment(SegmentKind.SCREEN_ON, 0, 100), segment(SegmentKind.CHARGING, 200, 300))))

        assertTrue(data.segments.isEmpty())
    }

    // --- Hatching ---

    private fun hypnogram(
        window: TimeRange,
        vararg segments: Pair<Long, Long>,
    ) = HypnogramData.of(
        window,
        segments.map { StageSegment(it.first, it.second, HypnogramStage.LIGHT_IDLE) },
        emptyList(),
    )

    @Test
    fun `a window the segments cover end to end draws no hatching`() {
        assertFalse(hasHatchedGaps(hypnogram(TimeRange.of(0, 10_000), 0L to 4_000L, 4_000L to 10_000L)))
        assertFalse(
            "overlapping segments still leave nothing uncovered",
            hasHatchedGaps(
                hypnogram(
                    TimeRange.of(0, 10_000),
                    0L to 6_000L,
                    5_000L to 10_000L,
                ),
            ),
        )
    }

    @Test
    fun `a visible hole before, between or after the segments is hatching`() {
        assertTrue("before", hasHatchedGaps(hypnogram(TimeRange.of(0, 10_000), 1_000L to 10_000L)))
        assertTrue("between", hasHatchedGaps(hypnogram(TimeRange.of(0, 10_000), 0L to 4_000L, 5_000L to 10_000L)))
        assertTrue("after", hasHatchedGaps(hypnogram(TimeRange.of(0, 10_000), 0L to 9_000L)))
    }

    @Test
    fun `a hole too small to draw is not hatching, and an empty window never is`() {
        // 10 ms is under the 50 ms (0.5% of the window) the chart can show as a band.
        assertFalse(hasHatchedGaps(hypnogram(TimeRange.of(0, 10_000), 0L to 4_990L, 5_000L to 10_000L)))
        assertFalse(hasHatchedGaps(hypnogram(TimeRange.of(5, 5))))
    }

    // --- Time slice ---

    @Test
    fun `slice banner appears only for a time slice and keeps requested and covered apart`() {
        val kind = ReferenceKind.TimeSlice(10 * HOUR_MS, 20 * HOUR_MS)
        val analysis =
            ready(
                intervalAnalysis(
                    resolved(
                        boundaryFrom = 10 * HOUR_MS,
                        boundaryTo = 20 * HOUR_MS,
                        coveredFrom = 10 * HOUR_MS + 8 * MIN_MS,
                        coveredTo =
                            20 * HOUR_MS,
                    ),
                ),
                kind = kind,
            )

        assertNull(sliceUi(ReferenceKind.SinceUnplugged, analysis))
        val slice = sliceUi(kind, analysis)!!
        assertEquals(TimeRange.of(10 * HOUR_MS, 20 * HOUR_MS), slice.requested)
        assertEquals(TimeRange.of(10 * HOUR_MS + 8 * MIN_MS, 20 * HOUR_MS), slice.covered)
    }

    @Test
    fun `a stale analysis leaves coverage unknown`() {
        val kind = ReferenceKind.TimeSlice(10 * HOUR_MS, 20 * HOUR_MS)
        val other =
            ready(
                intervalAnalysis(resolved(boundaryFrom = 1 * HOUR_MS, boundaryTo = 2 * HOUR_MS)),
                kind = ReferenceKind.TimeSlice(1 * HOUR_MS, 2 * HOUR_MS),
            )

        assertNull("a different interval's analysis must not supply coverage", sliceUi(kind, other)!!.covered)
        assertNull(sliceUi(kind, AnalysisState.Loading)!!.covered)
    }

    // --- App wakelocks ---

    @Test
    fun `app wakelocks rank by duration then count and carry the owner label`() {
        val diff =
            snapshotDiff(
                uids =
                    listOf(
                        uidDelta(10_001, "com.chat", wakelocks = listOf(wakelock("sync", 5_000, 3), wakelock("push", 90_000, 2))),
                        uidDelta(10_002, "com.mail", wakelocks = listOf(wakelock("fetch", 5_000, 9))),
                        uidDelta(10_003, null, wakelocks = listOf(wakelock("orphan", 1_000, 1))),
                    ),
            )

        val rows = (appWakelockRows(diff, perUidAvailable = true, labels = mapOf("com.chat" to "Chat")) as ListUi.Rows).rows

        assertEquals(listOf("push", "fetch", "sync", "orphan"), rows.map { it.name })
        assertEquals("Chat", rows[0].appLabel)
        assertEquals("a package without a known label shows its package name", "com.mail", rows[1].appLabel)
        assertNull("an unknown owner has no label and the UI shows the UID", rows[3].appLabel)
        assertEquals(10_003, rows[3].uid)
    }

    @Test
    fun `app wakelocks leave out invalid uids and rows with nothing in them`() {
        val diff =
            snapshotDiff(
                uids =
                    listOf(
                        uidDelta(10_001, valid = false, wakelocks = listOf(wakelock("negative", 99_000, 9))),
                        uidDelta(10_002, wakelocks = listOf(wakelock("idle", 0, 0), wakelock("counted", 0, 4))),
                    ),
            )

        val rows = (appWakelockRows(diff, true, emptyMap()) as ListUi.Rows).rows

        assertEquals(listOf("counted"), rows.map { it.name })
    }

    @Test
    fun `a ranked list caps its rows and counts what it hides`() {
        val many = (1..MAX_ROWS + 7).map { wakelock("lock$it", it * 1_000L, 1) }
        val diff = snapshotDiff(uids = listOf(uidDelta(10_001, wakelocks = many)))

        val list = appWakelockRows(diff, true, emptyMap()) as ListUi.Rows

        assertEquals(MAX_ROWS, list.rows.size)
        assertEquals(7, list.hidden)
        assertEquals("the longest is first", "lock${MAX_ROWS + 7}", list.rows.first().name)
    }

    @Test
    fun `without full snapshots at both ends per-app tabs say so and the kernel tab is unaffected`() {
        val diff =
            snapshotDiff(
                uids = listOf(uidDelta(10_001, wakelocks = listOf(wakelock("a", 1_000, 1)), alarms = listOf(UidAlarm("t", 2)))),
                kernel = listOf(KernelWakelock("wlan", 5_000, 3)),
            )

        assertEquals(ListUi.NeedsFullSnapshots, appWakelockRows(diff, perUidAvailable = false, labels = emptyMap()))
        assertEquals(ListUi.NeedsFullSnapshots, alarmRows(diff, perUidAvailable = false, labels = emptyMap()))
        assertTrue(kernelWakelockRows(diff) is ListUi.Rows)
        assertTrue(
            "a diff the resolver marked per-uid-less must not even look up labels",
            visiblePackages(intervalAnalysis(resolved(perUidAvailable = false), diff)).isEmpty(),
        )
    }

    @Test
    fun `reset battery stats make the per-app tabs unavailable`() {
        // Both ends name their battery-stats session, so an invalid one is a reset.
        val diff = snapshotDiff(batteryStatsValid = false)
        val reset = ListUi.Unavailable(UnavailableCause.StatsReset)

        assertEquals(reset, appWakelockRows(diff, perUidAvailable = true, labels = emptyMap()))
        assertEquals(reset, alarmRows(diff, perUidAvailable = true, labels = emptyMap()))
    }

    @Test
    fun `battery stats an end never read are not captured, not reset`() {
        val base = snapshotDiff(batteryStatsValid = false)
        val diff = base.copy(now = base.now.copy(statsStartMs = null, statsDurationMs = null))
        val notCaptured = ListUi.Unavailable(UnavailableCause.NotCaptured)

        assertEquals(notCaptured, appWakelockRows(diff, perUidAvailable = true, labels = emptyMap()))
        assertEquals(notCaptured, alarmRows(diff, perUidAvailable = true, labels = emptyMap()))
    }

    /** Audit B-03: apps the period could not check are not "nothing recorded". */
    @Test
    fun `per-app tabs that left every app out are not an empty period`() {
        val diff =
            snapshotDiff(
                uids =
                    listOf(
                        uidDelta(10_001, valid = false, wakelocks = listOf(wakelock("unproven", 99_000, 9)), alarms = listOf(UidAlarm("t", 3))),
                        uidDelta(10_002, wakelocks = listOf(wakelock("old owner", 5_000, 2))).copy(packageChanged = true),
                    ),
            )

        val nothingRecorded = ListUi.Rows(persistentListOf<Nothing>(), hidden = 0)
        assertNotEquals(nothingRecorded, appWakelockRows(diff, true, emptyMap()))
        assertNotEquals(nothingRecorded, alarmRows(diff, true, emptyMap()))
        val twoLeftOut = ListUi.Rows(persistentListOf<Nothing>(), hidden = 0, excluded = 2)
        assertEquals(twoLeftOut, appWakelockRows(diff, true, emptyMap()))
        assertEquals(twoLeftOut, alarmRows(diff, true, emptyMap()))
        assertEquals("kernel wakelocks belong to no app", 0, (kernelWakelockRows(diff) as ListUi.Rows).excluded)
    }

    @Test
    fun `per-app tabs with rows still count the apps they left out`() {
        val diff =
            snapshotDiff(
                uids =
                    listOf(
                        uidDelta(10_001, wakelocks = listOf(wakelock("sync", 5_000, 2)), alarms = listOf(UidAlarm("t", 2))),
                        uidDelta(10_002, valid = false, wakelocks = listOf(wakelock("unproven", 99_000, 9))),
                    ),
            )

        val apps = appWakelockRows(diff, true, emptyMap()) as ListUi.Rows
        val alarms = alarmRows(diff, true, emptyMap()) as ListUi.Rows

        assertEquals(listOf("sync"), apps.rows.map { it.name })
        assertEquals(1, apps.excluded)
        assertEquals(listOf(10_001), alarms.rows.map { it.uid })
        assertEquals(1, alarms.excluded)
    }

    @Test
    fun `a night whose app owners were never proven says so in both per-app tabs`() {
        // SnapshotDiffer marks every app UID invalid when either end lacks the UID-identity proof.
        val diff =
            snapshotDiff(
                uids =
                    listOf(
                        uidDelta(1_000, "android", wakelocks = listOf(wakelock("system", 2_000, 1))),
                        uidDelta(10_001, valid = false, wakelocks = listOf(wakelock("sync", 40 * MIN_MS, 3)), alarms = listOf(UidAlarm("t", 30))),
                        uidDelta(10_002, valid = false, alarms = listOf(UidAlarm("poll", 12))),
                    ),
            )
        val report = DiagnosticRuleEngine { SensorKind.OTHER }.evaluate(DiagnosticInput(diff, intervalMetrics(), true))

        val ui = sleepLabAnalysis(intervalAnalysis(diff = diff, report = report), emptyMap())

        assertEquals(report.skippedInvalidUids, (ui.appWakelocks as ListUi.Rows).excluded)
        assertEquals(listOf(1_000), (ui.appWakelocks as ListUi.Rows).rows.map { it.uid })
        assertEquals(ListUi.Rows(persistentListOf<Nothing>(), hidden = 0, excluded = 2), ui.alarms)
    }

    @Test
    fun `an empty but valid interval is an empty list, not an error state`() {
        val diff = snapshotDiff()

        assertEquals(0, (appWakelockRows(diff, true, emptyMap()) as ListUi.Rows).rows.size)
        assertEquals(0, (kernelWakelockRows(diff) as ListUi.Rows).rows.size)
        assertEquals(0, (alarmRows(diff, true, emptyMap()) as ListUi.Rows).rows.size)
    }

    // --- Kernel wakelocks ---

    @Test
    fun `kernel wakelocks rank by duration and drop names whose delta went negative`() {
        val diff =
            snapshotDiff(
                kernel =
                    listOf(
                        KernelWakelock("alarmtimer", 30_000, 5),
                        KernelWakelock("wlan", 120_000, 2),
                        KernelWakelock("broken", -5, 1),
                        KernelWakelock("quiet", 0, 0),
                    ),
                invalidKernel = setOf("broken"),
            )

        val rows = (kernelWakelockRows(diff) as ListUi.Rows).rows

        assertEquals(listOf("wlan", "alarmtimer"), rows.map { it.name })
        assertEquals(120_000L, rows[0].durationMs)
        assertEquals(2L, rows[0].count)
    }

    @Test
    fun `kernel wakelocks are unavailable when the kernel counters cannot be compared`() {
        val diff = snapshotDiff(kernel = listOf(KernelWakelock("wlan", 1_000, 1)), kernelCountersValid = false)

        assertEquals(ListUi.Unavailable(UnavailableCause.DeviceRestarted), kernelWakelockRows(diff))
    }

    // --- Alarms ---

    @Test
    fun `alarms group per app with the busiest tag first and rank by total wake-ups`() {
        val diff =
            snapshotDiff(
                uids =
                    listOf(
                        uidDelta(
                            10_001,
                            "com.chat",
                            alarms = listOf(UidAlarm("*walarm*:sync", 4), UidAlarm("*walarm*:ping", 9), UidAlarm("idle", 0)),
                        ),
                        uidDelta(10_002, "com.mail", alarms = listOf(UidAlarm("*walarm*:fetch", 20))),
                        uidDelta(10_003, "com.none", alarms = listOf(UidAlarm("zero", 0))),
                    ),
            )

        val rows = (alarmRows(diff, true, mapOf("com.mail" to "Mail")) as ListUi.Rows).rows

        assertEquals(listOf("com.mail", "com.chat"), rows.map { it.packageName })
        assertEquals("Mail", rows[0].appLabel)
        assertEquals(13L, rows[1].wakeups)
        assertEquals(listOf("*walarm*:ping", "*walarm*:sync"), rows[1].tags.map { it.tag })
    }

    @Test
    fun `the alarm dump counter is used only when batterystats has none and is never added to it`() {
        val diff =
            snapshotDiff(
                uids =
                    listOf(
                        uidDelta(
                            10_001,
                            "com.both",
                            alarms = listOf(UidAlarm("a", 3)),
                            alarmDump = listOf(UidAlarm("a", 3), UidAlarm("b", 8)),
                        ),
                        uidDelta(10_002, "com.dump", alarmDump = listOf(UidAlarm("only", 5))),
                    ),
            )

        val rows = (alarmRows(diff, true, emptyMap()) as ListUi.Rows).rows.associateBy { it.packageName }

        assertEquals("batterystats only; the overlapping dump is not summed in", 3L, rows.getValue("com.both").wakeups)
        assertEquals(5L, rows.getValue("com.dump").wakeups)
    }

    // --- Label lookups ---

    @Test
    fun `only the packages of the shown rows need a label`() {
        val many = (1..MAX_ROWS + 3).map { uidDelta(10_000 + it, "pkg.$it", wakelocks = listOf(wakelock("w", it * 1_000L, 1))) }
        val analysis = intervalAnalysis(diff = snapshotDiff(uids = many + uidDelta(20_000, "com.alarm", alarms = listOf(UidAlarm("t", 1)))))

        val packages = visiblePackages(analysis)

        assertEquals("the three shortest wakelock owners are not shown", MAX_ROWS + 1, packages.size)
        assertTrue("pkg.1" !in packages)
        assertTrue("com.alarm" in packages)
        assertEquals(packages.size, packages.distinct().size)
    }

    // --- CPU residency ---

    private fun cpu(
        policy: Int,
        freq: Long,
        ms: Long,
    ) = CpuFreqDelta(policy, freq, ms)

    @Test
    fun `cpu time groups per policy with shares of the cluster total and ascending frequencies`() {
        val diff =
            snapshotDiff(
                cpu =
                    listOf(
                        cpu(0, 1_800_000, 600),
                        cpu(0, 600_000, 300),
                        cpu(0, 1_200_000, 100),
                        cpu(4, 2_400_000, 50),
                        cpu(4, 900_000, 150),
                    ),
            )

        val clusters = (cpuUi(diff, fullAtBothEnds = true) as CpuUi.Ready).clusters

        assertEquals(listOf(0, 4), clusters.map { it.policy })
        assertEquals(listOf(ClusterRole.Little, ClusterRole.Big), clusters.map { it.role })
        val little = clusters[0]
        assertEquals(1_000L, little.totalMs)
        assertEquals(listOf(600_000L, 1_200_000L, 1_800_000L), little.rows.map { it.freqKhz })
        assertEquals(listOf(0.3f, 0.1f, 0.6f), little.rows.map { it.share })
        assertEquals(0f, little.otherShare, 0f)
        assertEquals(0.75f, clusters[1].rows.single { it.freqKhz == 900_000L }.share, 0.0001f)
    }

    @Test
    fun `a cluster lists its busiest frequencies and sums the rest`() {
        val deltas = (1..8).map { cpu(0, it * 100_000L, it * 100L) } // total 3600, the five busiest are 8..4 = 3000
        val diff = snapshotDiff(cpu = deltas)

        val cluster = (cpuUi(diff, fullAtBothEnds = true) as CpuUi.Ready).clusters.single()

        assertEquals(MAX_FREQ_ROWS, cluster.rows.size)
        assertEquals(listOf(400_000L, 500_000L, 600_000L, 700_000L, 800_000L), cluster.rows.map { it.freqKhz })
        assertEquals(600f / 3_600f, cluster.otherShare, 0.0001f)
        assertEquals(1f, cluster.rows.sumOf { it.share.toDouble() }.toFloat() + cluster.otherShare, 0.001f)
    }

    @Test
    fun `three clusters are little mid prime and other counts stay generic`() {
        fun roles(vararg policies: Int) =
            (cpuUi(snapshotDiff(cpu = policies.map { cpu(it, 1_000_000, 10) }), fullAtBothEnds = true) as CpuUi.Ready).clusters.map { it.role }

        assertEquals(listOf(ClusterRole.Little, ClusterRole.Mid, ClusterRole.Prime), roles(7, 0, 4))
        assertEquals(listOf(ClusterRole.Generic), roles(0))
        assertEquals(List(4) { ClusterRole.Generic }, roles(0, 2, 4, 6))
    }

    @Test
    fun `invalid kernel counters make cpu residency explicitly unavailable even if rows exist`() {
        val diff = snapshotDiff(cpu = listOf(cpu(0, 1_000_000, 500)), kernelCountersValid = false)

        assertEquals(CpuUi.Unavailable, cpuUi(diff, fullAtBothEnds = true))
        assertEquals("a restart outranks a light end", CpuUi.Unavailable, cpuUi(diff, fullAtBothEnds = false))
    }

    @Test
    fun `valid counters with no time in state is no data, and zero-time rows are ignored`() {
        assertEquals(CpuUi.NoData, cpuUi(snapshotDiff(), fullAtBothEnds = true))
        assertEquals(CpuUi.NoData, cpuUi(snapshotDiff(cpu = listOf(cpu(0, 1_000_000, 0), cpu(0, 2_000_000, -5))), fullAtBothEnds = true))
    }

    @Test
    fun `deep sleep comes from the sleep stats and is null when the interval cannot measure it`() {
        assertEquals(DeepSleepUi(0.7, 7 * HOUR_MS), deepSleepUi(snapshotDiff()))
        assertEquals(DeepSleepUi(null, null), deepSleepUi(snapshotDiff(sleep = null)))
        assertEquals(
            "a suspend duration without a ratio is not shown",
            DeepSleepUi(null, null),
            deepSleepUi(snapshotDiff(sleep = SleepStats(5 * HOUR_MS, null, null, null, null, deepSleepRatio = null))),
        )
    }

    @Test
    fun `deep sleep needs fifteen minutes of screen off before it is shown`() {
        // Even valid persisted timers need the shared reporting floor, including a measured 0%.
        for (screenOffMs in listOf(15 * MIN_MS - 1, 15 * MIN_MS)) {
            val (ref, now) = persistedSleepSnapshots(screenOffMs, screenOffAwakeMs = screenOffMs)
            val diff = SnapshotDiffer.diff(ref, now)

            assertEquals(0.0, diff.sleep!!.deepSleepRatio!!, 0.0)
            val expected = if (screenOffMs < 15 * MIN_MS) DeepSleepUi(null, null) else DeepSleepUi(0.0, 0L)
            assertEquals(expected, deepSleepUi(diff))
        }
    }

    @Test
    fun `persisted BatteryStats sleep survives reboot while CPU residency remains unavailable`() {
        val (ref, now) = persistedSleepSnapshots()
        val diff = SnapshotDiffer.diff(ref, now)

        assertTrue(diff.validity.batteryStats)
        assertTrue(diff.validity.dumpCounters)
        assertFalse(diff.validity.kernelCounters)
        assertFalse(diff.validity.cpuFreq)
        assertTrue(diff.cpuFreqDelta.isEmpty())
        val sleep = diff.sleep!!
        assertEquals(30 * MIN_MS, sleep.screenOffMs)
        assertEquals(25 * MIN_MS, sleep.screenOffDeepSleepMs)
        assertEquals(5.0 / 6.0, sleep.deepSleepRatio!!, 0.0)

        val expected = DeepSleepUi(5.0 / 6.0, 25 * MIN_MS)
        assertEquals(expected, deepSleepUi(diff))
        val ui = sleepLabAnalysis(intervalAnalysis(diff = diff), emptyMap())
        assertEquals(expected, ui.deepSleep)
        assertEquals(CpuUi.Unavailable, ui.cpu)
    }

    @Test
    fun `cross boot sleep is unavailable when the BatteryStats epoch changes or is unknown`() {
        val (ref, now) = persistedSleepSnapshots()
        val changedStart = now.clock.statsStartMs!! + HOUR_MS
        val changed =
            now.copy(
                clock = now.clock.copy(statsStartMs = changedStart),
                system = now.system!!.copy(startClockTimeMs = changedStart),
            )
        val intervals =
            listOf(
                ref to changed,
                ref.copy(clock = ref.clock.copy(statsStartMs = null)) to now,
                ref to now.copy(clock = now.clock.copy(statsStartMs = null)),
            )

        for ((before, after) in intervals) {
            val diff = SnapshotDiffer.diff(before, after)
            assertFalse(diff.validity.batteryStats)
            assertFalse(diff.validity.dumpCounters)
            assertNull(diff.sleep)
            assertEquals(DeepSleepUi(null, null), deepSleepUi(diff))
        }
    }

    @Test
    fun `cross boot sleep needs observed dump timers at both ends`() {
        val (ref, now) = persistedSleepSnapshots()
        val unavailableDump = mapOf("dump" to SourceAvailability(false, "unavailable"))
        val intervals =
            listOf(
                ref.copy(system = null) to now,
                ref to now.copy(system = null),
                ref.copy(availability = unavailableDump) to now,
                ref to now.copy(availability = unavailableDump),
            )

        for ((before, after) in intervals) {
            val diff = SnapshotDiffer.diff(before, after)
            assertNull(diff.sleep)
            assertEquals(DeepSleepUi(null, null), deepSleepUi(diff))
        }
    }

    @Test
    fun `raw kernel fallback stays same boot only and never invents a screen off ratio`() {
        val (fullRef, fullNow) = persistedSleepSnapshots()
        val ref =
            fullRef.copy(
                kind = SnapshotKind.LIGHT,
                system = null,
                availability = mapOf("dump" to SourceAvailability(false, "light_snapshot")),
            )
        val now = fullNow.copy(kind = SnapshotKind.LIGHT, system = null, availability = ref.availability)
        val sameBoot =
            now.copy(
                clock =
                    now.clock.copy(
                        bootCount = ref.clock.bootCount,
                        elapsedRealtimeMs = ref.clock.elapsedRealtimeMs + 30 * MIN_MS,
                        uptimeMs = ref.clock.uptimeMs + 5 * MIN_MS,
                    ),
            )
        val fallback = SnapshotDiffer.diff(ref, sameBoot)

        assertTrue(fallback.validity.kernelCounters)
        val sleep = fallback.sleep!!
        assertEquals(25 * MIN_MS, sleep.deepSleepMs)
        assertNull(sleep.screenOffMs)
        assertNull(sleep.deepSleepRatio)
        assertEquals(DeepSleepUi(null, null), deepSleepUi(fallback))

        val crossBoot = SnapshotDiffer.diff(ref, now)
        assertFalse(crossBoot.validity.kernelCounters)
        assertNull(crossBoot.sleep)
        assertEquals(DeepSleepUi(null, null), deepSleepUi(crossBoot))
    }

    /** A FULL marker spans a reboot; persisted timers measure only accumulated on-battery time, not power-off time. */
    private fun persistedSleepSnapshots(
        screenOffMs: Long = 30 * MIN_MS,
        screenOffAwakeMs: Long = 5 * MIN_MS,
    ): Pair<BatterySnapshot, BatterySnapshot> {
        val statsStart = NOW_MS - 6 * HOUR_MS
        val system =
            SystemTimers(
                startClockTimeMs = statsStart,
                totalRealtimeMs = 4 * HOUR_MS,
                totalUptimeMs = 2 * HOUR_MS,
                batteryRealtimeMs = HOUR_MS,
                batteryUptimeMs = 20 * MIN_MS,
                screenOffRealtimeMs = HOUR_MS,
                screenOffUptimeMs = 20 * MIN_MS,
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
        val ref =
            snapshot(NOW_MS - 2 * HOUR_MS).copy(
                trigger = CaptureTrigger.MARKER,
                clock =
                    snapshotClock(NOW_MS - 2 * HOUR_MS).copy(
                        elapsedRealtimeMs = 4 * HOUR_MS,
                        uptimeMs = 2 * HOUR_MS,
                        statsStartMs = statsStart,
                        statsDurationMs = 4 * HOUR_MS,
                    ),
                system = system,
                cpuFreq = listOf(CpuFreqTime(0, 1_000_000, 1_000)),
                availability = mapOf("dump" to SourceAvailability(true, null), "cpu" to SourceAvailability(true, null)),
            )
        val now =
            ref.copy(
                id = NOW_MS,
                trigger = CaptureTrigger.APP_OPEN,
                clock =
                    ref.clock.copy(
                        wallMs = NOW_MS,
                        elapsedRealtimeMs = MIN_MS,
                        uptimeMs = MIN_MS / 2,
                        bootCount = ref.clock.bootCount + 1,
                        statsDurationMs = 4 * HOUR_MS + screenOffMs,
                    ),
                system =
                    system.copy(
                        totalRealtimeMs = system.totalRealtimeMs + screenOffMs,
                        totalUptimeMs = system.totalUptimeMs + screenOffAwakeMs,
                        batteryRealtimeMs = system.batteryRealtimeMs + screenOffMs,
                        batteryUptimeMs = system.batteryUptimeMs + screenOffAwakeMs,
                        screenOffRealtimeMs = system.screenOffRealtimeMs + screenOffMs,
                        screenOffUptimeMs = system.screenOffUptimeMs + screenOffAwakeMs,
                    ),
                cpuFreq = listOf(CpuFreqTime(0, 1_000_000, 2_000)),
            )
        return ref to now
    }

    @Test
    fun `the whole analysis carries all three tabs, cpu and deep sleep`() {
        val diff =
            snapshotDiff(
                uids =
                    listOf(
                        uidDelta(10_001, "com.chat", wakelocks = listOf(wakelock("sync", 2_000, 1)), alarms = listOf(UidAlarm("t", 2))),
                    ),
                kernel = listOf(KernelWakelock("wlan", 3_000, 1)),
                cpu = listOf(cpu(0, 1_000_000, 10)),
            )

        val ui = sleepLabAnalysis(intervalAnalysis(diff = diff), mapOf("com.chat" to "Chat"))

        assertEquals("sync", (ui.appWakelocks as ListUi.Rows).rows.single().name)
        assertEquals("wlan", (ui.kernelWakelocks as ListUi.Rows).rows.single().name)
        assertEquals(2L, (ui.alarms as ListUi.Rows).rows.single().wakeups)
        assertTrue(ui.cpu is CpuUi.Ready)
        assertEquals(0.7, ui.deepSleep.ratio!!, 0.0)
    }
}
