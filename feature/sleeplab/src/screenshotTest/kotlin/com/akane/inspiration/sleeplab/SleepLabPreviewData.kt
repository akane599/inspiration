package com.akane.inspiration.sleeplab

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import com.akane.inspiration.designsystem.chart.model.TimeRange
import com.akane.inspiration.designsystem.theme.InspirationTheme
import com.akane.inspiration.diagnostics.analysis.IntervalAnalysis
import com.akane.inspiration.diagnostics.analysis.SegmentKind
import com.akane.inspiration.diagnostics.analysis.Timeline
import com.akane.inspiration.diagnostics.analysis.TimelineSegment
import com.akane.inspiration.diagnostics.dictionary.DiagnosticDictionary
import com.akane.inspiration.diagnostics.dictionary.DictionaryEntry
import com.akane.inspiration.diagnostics.dictionary.EntryKind
import com.akane.inspiration.diagnostics.dictionary.LocalizedText
import com.akane.inspiration.diagnostics.dictionary.MatchType
import com.akane.inspiration.diagnostics.model.AwakeViolation
import com.akane.inspiration.diagnostics.model.Culprit
import com.akane.inspiration.diagnostics.model.CulpritScope
import com.akane.inspiration.diagnostics.model.DiagnosticReport
import com.akane.inspiration.diagnostics.model.RuleId
import com.akane.inspiration.diagnostics.model.Severity
import com.akane.inspiration.diagnostics.rules.NO_SCREEN_OFF_FIGURES
import com.akane.inspiration.diagnostics.rules.NO_SCREEN_OFF_TIME
import com.akane.inspiration.diagnostics.story.BatteryStory
import com.akane.inspiration.telemetry.diff.ResolvedReference
import com.akane.inspiration.telemetry.dump.KernelWakelock
import com.akane.inspiration.telemetry.dump.UidAlarm
import com.akane.inspiration.telemetry.dump.UidWakelock
import com.akane.inspiration.telemetry.interval.IntervalMetrics
import com.akane.inspiration.telemetry.snapshot.BatterySnapshot
import com.akane.inspiration.telemetry.snapshot.BatteryState
import com.akane.inspiration.telemetry.snapshot.CaptureTrigger
import com.akane.inspiration.telemetry.snapshot.CpuFreqDelta
import com.akane.inspiration.telemetry.snapshot.DiffValidity
import com.akane.inspiration.telemetry.snapshot.SleepStats
import com.akane.inspiration.telemetry.snapshot.SnapshotClock
import com.akane.inspiration.telemetry.snapshot.SnapshotDiff
import com.akane.inspiration.telemetry.snapshot.SnapshotKind
import com.akane.inspiration.telemetry.snapshot.UidDelta
import com.akane.inspiration.telemetry.snapshot.isApplicationUid
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** A synthetic night in UTC, so every machine renders the same labels. Previews never touch Shizuku. */
internal object SleepLabPreviewData {
    val zone: ZoneId = ZoneId.of("UTC")

    private const val MIN = 60_000L
    private const val HOUR = 60 * MIN
    private val dayStart = Instant.parse("2026-10-16T20:00:00Z").toEpochMilli()
    private val end = dayStart + 14 * HOUR

    private fun at(
        hour: Int,
        minute: Int,
        dayOffset: Int = 0,
    ) = Instant.parse("2026-10-16T00:00:00Z").toEpochMilli() + dayOffset * 24 * HOUR + hour * HOUR + minute * MIN

    private fun seg(
        kind: SegmentKind,
        from: Long,
        to: Long,
    ) = TimelineSegment(kind, from, to)

    /** Screen on until 23:10 and from 07:00, doze in between, two maintenance windows, two wake-ups. */
    val timeline =
        Timeline(
            segments =
                listOf(
                    seg(SegmentKind.SCREEN_ON, dayStart, at(23, 10)),
                    seg(SegmentKind.LIGHT_IDLE, at(23, 12), at(23, 40)),
                    seg(SegmentKind.DEEP_IDLE, at(23, 40), at(1, 0, 1)),
                    seg(SegmentKind.MAINTENANCE, at(1, 0, 1), at(1, 6, 1)),
                    seg(SegmentKind.LIGHT_IDLE, at(1, 6, 1), at(1, 30, 1)),
                    seg(SegmentKind.DEEP_IDLE, at(1, 30, 1), at(3, 30, 1)),
                    seg(SegmentKind.AWAKE, at(3, 30, 1), at(3, 34, 1)),
                    seg(SegmentKind.LIGHT_IDLE, at(3, 34, 1), at(4, 0, 1)),
                    seg(SegmentKind.DEEP_IDLE, at(4, 0, 1), at(5, 45, 1)),
                    seg(SegmentKind.MAINTENANCE, at(5, 45, 1), at(5, 52, 1)),
                    seg(SegmentKind.LIGHT_IDLE, at(5, 52, 1), at(6, 40, 1)),
                    seg(SegmentKind.AWAKE, at(6, 40, 1), at(7, 0, 1)),
                    seg(SegmentKind.SCREEN_ON, at(7, 0, 1), end),
                ),
            levelPoints = emptyList(),
            temperaturePoints = emptyList(),
            wakePulsesMs = listOf(at(3, 30, 1), at(7, 0, 1)),
            from = dayStart,
            to = end,
        )

    val window = TimeRange.of(dayStart, end)

    private fun uid(
        uid: Int,
        pkg: String?,
        wakelocks: List<UidWakelock> = emptyList(),
        alarms: List<UidAlarm> = emptyList(),
    ) = UidDelta(
        uid = uid,
        packageName = pkg,
        packageChanged = false,
        valid = true,
        totalMah = 1.0,
        fgMah = null,
        bgMah = null,
        fgsMah = null,
        cachedMah = null,
        componentsMah = emptyMap(),
        cpuUserMs = 0,
        cpuSystemMs = 0,
        fgActivityMs = 0,
        fgServiceMs = 0,
        partialWakelocks = wakelocks,
        wakeupAlarms = alarms,
        sensors = emptyList(),
    )

    private fun wake(
        name: String,
        durationMs: Long,
        count: Long,
    ) = UidWakelock(name, durationMs, bgDurationMs = 0, count = count)

    private val uids =
        listOf(
            uid(
                10_101,
                "com.example.chatly",
                listOf(wake("*job*/com.example.chatly/.SyncJob", 18 * MIN + 20_000, 41), wake("ChatConnection", 95_000, 6)),
                listOf(
                    UidAlarm("*walarm*:com.example.chatly.ACTION_KEEPALIVE", 38),
                    UidAlarm("*walarm*:com.example.chatly.ACTION_SYNC", 12),
                ),
            ),
            uid(
                10_102,
                "com.example.mailbox",
                listOf(wake("NlpWakelock", 7 * MIN + 5_000, 22)),
                listOf(UidAlarm("*walarm*:com.example.mailbox.POLL", 24)),
            ),
            uid(
                10_103,
                "com.example.maps",
                listOf(wake("LocationManagerService", 42_000, 9)),
                listOf(UidAlarm("*alarm*:com.example.maps.TICK", 5)),
            ),
            uid(10_104, "com.example.photos", listOf(wake("*sync*/com.example.photos", 12_000, 3))),
            uid(1_000, null, listOf(wake("GnssLocationProvider", 3_000, 2))),
        )

    private val kernel =
        listOf(
            KernelWakelock("wlan_rx_wake", 11 * MIN + 30_000, 214),
            KernelWakelock("alarmtimer", 4 * MIN + 10_000, 96),
            KernelWakelock("qcom_rx_wakelock", 48_000, 31),
            KernelWakelock("battery", 9_000, 12),
        )

    private fun cpu(
        policy: Int,
        freqKhz: Long,
        minutes: Long,
    ) = CpuFreqDelta(policy, freqKhz, minutes * MIN)

    private val cpuTime =
        listOf(
            cpu(0, 300_000, 410),
            cpu(0, 691_200, 52),
            cpu(0, 1_075_200, 31),
            cpu(0, 1_478_400, 20),
            cpu(0, 1_804_800, 14),
            cpu(0, 1_996_800, 9),
            cpu(0, 576_000, 22),
            cpu(4, 633_600, 480),
            cpu(4, 1_113_600, 38),
            cpu(4, 1_555_200, 12),
            cpu(4, 2_054_400, 8),
            cpu(4, 2_572_800, 5),
            cpu(7, 806_400, 520),
            cpu(7, 1_593_600, 18),
            cpu(7, 2_342_400, 7),
            cpu(7, 2_841_600, 4),
        )

    private val sleep =
        SleepStats(
            deepSleepMs = 8 * HOUR + 12 * MIN,
            screenOffDeepSleepMs = 7 * HOUR + 40 * MIN,
            screenOffAwakeMs = 50 * MIN,
            screenOffMs = 9 * HOUR,
            screenOnMs = 5 * HOUR,
            deepSleepRatio = 0.587,
        )

    private fun clock(wallMs: Long) = SnapshotClock(wallMs, wallMs, wallMs, 1, statsStartMs = 0, statsDurationMs = wallMs)

    private fun snapshot(wallMs: Long) =
        BatterySnapshot(
            id = wallMs,
            kind = SnapshotKind.FULL,
            trigger = CaptureTrigger.APP_OPEN,
            clock = clock(wallMs),
            battery = BatteryState(80, false, null, 300, 4_000, -300, null, null),
            system = null,
            uids = emptyList(),
            deviceComponentsMah = emptyMap(),
            kernelWakelocks = emptyList(),
            wakeupReasons = emptyList(),
            cpuFreq = emptyList(),
            availability = emptyMap(),
            truncatedUids = 0,
        )

    private fun resolved(
        perUid: Boolean,
        from: Long = dayStart,
        to: Long = end,
    ) = ResolvedReference(
        ref = snapshot(from),
        now = snapshot(to),
        boundaryFromWallMs = from,
        boundaryToWallMs = to,
        coveredFromWallMs = from,
        coveredToWallMs = to,
        gapMs = 0,
        perUidAvailable = perUid,
    )

    private fun diff(
        kernelValid: Boolean = true,
        batteryStatsValid: Boolean = true,
    ) = SnapshotDiff(
        ref = clock(dayStart),
        now = clock(end),
        validity = DiffValidity(batteryStats = batteryStatsValid, kernelCounters = kernelValid, problems = emptyList()),
        durationMs = end - dayStart,
        uids = uids,
        deviceComponentsMah = emptyMap(),
        kernelWakelocks = if (kernelValid) kernel else emptyList(),
        sleep = sleep,
        dischargedMah = 1_200.0,
        signalStrengthMs = emptyList(),
        signalScanningMs = null,
        mobileRadioActiveMs = null,
        cpuFreqDelta = if (kernelValid) cpuTime else emptyList(),
    )

    private fun analysis(
        perUid: Boolean = true,
        kernelValid: Boolean = true,
        report: DiagnosticReport = DiagnosticReport(emptyList(), emptyMap(), 0),
        diff: SnapshotDiff = diff(kernelValid),
    ) = IntervalAnalysis(
        resolved = resolved(perUid),
        diff = diff,
        metrics =
            IntervalMetrics(
                fromWallMs = dayStart,
                toWallMs = end,
                screenOnMs = 5 * HOUR,
                screenOffMs = 9 * HOUR,
                unknownMs = 0,
                chargingMs = 0,
                startLevel = 90,
                endLevel = 50,
                levelUsedPct = 40,
                levelDropScreenOn = 20,
                levelDropScreenOff = 5,
                screenOnPctPerHour = 4.0,
                screenOffPctPerHour = 0.6,
                discontinuities = emptyList(),
            ),
        report = report,
        story = BatteryStory(emptyList()),
    )

    private val labels =
        mapOf(
            "com.example.chatly" to "Chatly",
            "com.example.mailbox" to "Mailbox",
            "com.example.maps" to "Maps",
            "com.example.photos" to "Photos",
        )

    private fun ui(
        perUid: Boolean = true,
        kernelValid: Boolean = true,
        report: DiagnosticReport = DiagnosticReport(emptyList(), emptyMap(), 0),
        diff: SnapshotDiff = diff(kernelValid),
    ) = sleepLabAnalysis(analysis(perUid, kernelValid, report, diff), labels)

    /** The night's top partial wakelock, kernel wakelock and wake-up alarm, as Rule 2 picks them. */
    private val culprits =
        listOf(
            Culprit(EntryKind.PARTIAL, "*job*/com.example.chatly/.SyncJob", 10_101, 18 * MIN + 20_000, 41),
            Culprit(EntryKind.KERNEL, "wlan_rx_wake", null, 11 * MIN + 30_000, 214),
            Culprit(EntryKind.ALARM, "*walarm*:com.example.chatly.ACTION_KEEPALIVE", 10_101, null, 38),
        )

    private fun awakeReport(
        severity: Severity,
        ratio: Double,
        scope: CulpritScope,
        skipped: Int = 0,
    ) = DiagnosticReport(listOf(AwakeViolation(severity, ratio, 9 * HOUR, scope, culprits)), emptyMap(), skipped)

    private val hypnogram = HypnogramUi.Ready(hypnogramData(timeline))

    val apps = SleepLabState(ui(), hypnogram, SleepTab.AppWakelocks, slice = null, refreshing = false)
    val kernelTab = apps.copy(tab = SleepTab.KernelWakelocks)
    val alarmsTab = apps.copy(tab = SleepTab.Alarms)

    /** Per-app snapshots are missing at one end: the app lists say so, the kernel list and CPU still work. */
    val needsFull = apps.copy(analysis = ui(perUid = false))
    val needsFullAlarms = needsFull.copy(tab = SleepTab.Alarms)

    /** A screen-off-only night awake a quarter of the time: the culprit card leads the screen. */
    val culprit = apps.copy(analysis = ui(report = awakeReport(Severity.RED, 0.24, CulpritScope.SCREEN_OFF_ONLY)))

    /** A period with screen-on time: the culprits are whole-interval contributors, and two apps went unchecked. */
    val culpritWholeInterval =
        apps.copy(analysis = ui(report = awakeReport(Severity.AMBER, 0.093, CulpritScope.WHOLE_INTERVAL_CONTRIBUTORS, skipped = 2)))

    /** Too little screen-off time to check: the card says so instead of implying the night was fine. */
    val culpritNotEvaluable =
        apps.copy(analysis = ui(report = DiagnosticReport(emptyList(), mapOf(RuleId.AWAKE_VIOLATION to NO_SCREEN_OFF_TIME), 0)))

    /**
     * No app's owner was proven at both ends (a missed identity budget or a truncated usage list): the app tab keeps
     * only the platform UID's row and counts the apps it left out, and the alarm tab says so instead of "nothing".
     */
    val appsUnchecked =
        apps.copy(
            analysis =
                ui(
                    diff = diff().let { d -> d.copy(uids = d.uids.map { if (isApplicationUid(it.uid)) it.copy(valid = false) else it }) },
                ),
        )
    val appsUncheckedAlarms = appsUnchecked.copy(tab = SleepTab.Alarms)

    /**
     * The media providers' shared UID, reported as `android.process.media`, which names none of its five apps: the
     * culprit card and the app tab say "shared by 5 apps", as X-Ray does, instead of naming one of them (B-08).
     */
    val sharedOwner =
        apps.copy(
            analysis =
                sleepLabAnalysis(
                    analysis(
                        report =
                            DiagnosticReport(
                                listOf(
                                    AwakeViolation(
                                        Severity.RED,
                                        0.24,
                                        9 * HOUR,
                                        CulpritScope.SCREEN_OFF_ONLY,
                                        listOf(Culprit(EntryKind.PARTIAL, "MediaScannerService", 10_011, 21 * MIN, 17)),
                                    ),
                                ),
                                emptyMap(),
                                0,
                            ),
                        diff = diff().let { d -> d.copy(uids = d.uids + uid(10_011, null, listOf(wake("MediaScannerService", 21 * MIN, 17)))) },
                    ),
                    labels,
                    sharedBy = mapOf(10_011 to 5),
                ),
        )

    /** The device rebooted inside the period: kernel wakelocks and CPU residency cannot be compared. */
    val counterReset = apps.copy(analysis = ui(kernelValid = false), tab = SleepTab.KernelWakelocks)

    /** Without a battery-stats dump that compares, only the kernel's own suspend clock is left (as SnapshotDiffer). */
    private val kernelOnlySleep = SleepStats(sleep.deepSleepMs, null, null, null, null, null)
    private val noScreenOffFigures = DiagnosticReport(emptyList(), mapOf(RuleId.AWAKE_VIOLATION to NO_SCREEN_OFF_FIGURES), 0)

    /**
     * One end is a light snapshot in the same boot (audit B-04): the culprit card, the kernel tab and the CPU card
     * each say that end lacks their counters, never a short night or a restart.
     */
    val notCaptured =
        apps.copy(
            analysis =
                ui(
                    perUid = false,
                    report = noScreenOffFigures,
                    diff =
                        diff(batteryStatsValid = false).let { d ->
                            d.copy(
                                now = d.now.copy(statsStartMs = null, statsDurationMs = null),
                                validity = d.validity.copy(kernelWakelocks = false, cpuFreq = false, alarmDumpCounters = false),
                                uids = emptyList(),
                                kernelWakelocks = emptyList(),
                                cpuFreqDelta = emptyList(),
                                sleep = kernelOnlySleep,
                            )
                        },
                ),
            tab = SleepTab.KernelWakelocks,
        )

    /** Battery stats started a new session inside one boot (audit B-04): the counters were reset, CPU still compares. */
    val statsReset =
        apps.copy(
            analysis =
                ui(
                    report = noScreenOffFigures,
                    diff =
                        diff(batteryStatsValid = false).let { d ->
                            d.copy(
                                now = d.now.copy(statsStartMs = end - 2 * HOUR, statsDurationMs = 2 * HOUR),
                                validity = d.validity.copy(kernelWakelocks = false, alarmDumpCounters = false),
                                uids = emptyList(),
                                kernelWakelocks = emptyList(),
                                sleep = kernelOnlySleep,
                            )
                        },
                ),
        )

    val hypnogramEmpty =
        apps.copy(
            hypnogram = HypnogramUi.Ready(hypnogramData(Timeline(emptyList(), emptyList(), emptyList(), emptyList(), dayStart, end))),
        )

    /** The 04:00 deep-doze stretch was never recorded: the chart hatches it, and only then does the caption explain hatching. */
    val hypnogramGap =
        apps.copy(
            hypnogram =
                HypnogramUi.Ready(
                    hypnogramData(
                        timeline.copy(
                            segments =
                                timeline.segments.filterNot {
                                    it.kind == SegmentKind.DEEP_IDLE &&
                                        it.startMs == at(4, 0, 1)
                                },
                        ),
                    ),
                ),
        )
    val hypnogramFailed = apps.copy(hypnogram = HypnogramUi.Failed)
    val hypnogramLoading = apps.copy(hypnogram = HypnogramUi.Loading)

    val slice =
        apps.copy(
            slice =
                SliceUi(
                    requested = TimeRange.of(at(23, 0), at(7, 30, 1)),
                    covered = TimeRange.of(at(23, 8), at(7, 30, 1)),
                ),
        )

    val empty = SleepLabState.Initial.copy(analysis = AnalysisUi.Empty)
    val error = SleepLabState.Initial.copy(analysis = AnalysisUi.Error)
    val loading = SleepLabState.Initial

    /** A few dictionary entries in the shapes the real dictionary has; the screen only shows what matches. */
    val dictionary =
        DiagnosticDictionary(
            listOf(
                entry(
                    "partial_job",
                    EntryKind.PARTIAL,
                    MatchType.PREFIX,
                    "*job*/",
                    "Background job",
                    "Arka plan işi",
                    "A scheduled background job held the device awake while it ran. Frequent runs add up.",
                    "Zamanlanmış bir arka plan işi çalışırken cihazı uyanık tuttu. Sık çalışması toplamı artırır.",
                    action = "standby_bucket",
                ),
                entry(
                    "partial_nlp",
                    EntryKind.PARTIAL,
                    MatchType.EXACT,
                    "NlpWakelock",
                    "Network location",
                    "Ağ konumu",
                    "The system's network location provider is working. Apps asking for location often keep it busy.",
                    "Sistemin ağ konumu sağlayıcısı çalışıyor. Sık konum isteyen uygulamalar onu meşgul tutar.",
                ),
                entry(
                    "kernel_wlan",
                    EntryKind.KERNEL,
                    MatchType.PREFIX,
                    "wlan",
                    "Wi-Fi driver",
                    "Wi-Fi sürücüsü",
                    "The Wi-Fi driver woke the device to handle incoming packets.",
                    "Wi-Fi sürücüsü, gelen paketleri işlemek için cihazı uyandırdı.",
                ),
                entry(
                    "kernel_alarmtimer",
                    EntryKind.KERNEL,
                    MatchType.EXACT,
                    "alarmtimer",
                    "Alarm timer",
                    "Alarm zamanlayıcısı",
                    "The kernel alarm timer holds the device awake just long enough to deliver an alarm.",
                    "Çekirdek alarm zamanlayıcısı, bir alarmı iletmek için cihazı kısa süre uyanık tutar.",
                ),
                entry(
                    "alarm_prefix",
                    EntryKind.ALARM,
                    MatchType.PREFIX,
                    "*walarm*:",
                    "Wake-up alarm",
                    "Uyandıran alarm",
                    "An app scheduled an alarm that wakes the device from sleep. Many wake-ups per night drain the battery.",
                    "Bir uygulama, cihazı uykudan uyandıran bir alarm kurdu. Gece boyunca çok sayıda uyanma pili tüketir.",
                ),
            ),
        )

    private fun entry(
        id: String,
        kind: EntryKind,
        matchType: MatchType,
        value: String,
        titleEn: String,
        titleTr: String,
        bodyEn: String,
        bodyTr: String,
        action: String? = null,
    ) = DictionaryEntry(
        id = id,
        kinds = setOf(kind),
        matchType = matchType,
        value = value,
        category = "preview",
        title = LocalizedText(titleEn, titleTr),
        body = LocalizedText(bodyEn, bodyTr),
        action = action,
    )
}

@Composable
internal fun SleepLabPreviewHost(
    darkTheme: Boolean,
    state: SleepLabState,
) {
    val locale: Locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val explain = remember(locale) { { kind: EntryKind, name: String -> SleepLabPreviewData.dictionary.explain(kind, name, locale) } }
    InspirationTheme(darkTheme = darkTheme) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            SleepLabScreen(state = state, onIntent = {}, explain = explain, zone = SleepLabPreviewData.zone)
        }
    }
}
