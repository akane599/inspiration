package com.akane.inspiration.health

import com.akane.inspiration.ipc.model.HistoryEvent
import com.akane.inspiration.ipc.model.HistoryKind
import com.akane.inspiration.telemetry.app.KernelCapacitySnapshot
import com.akane.inspiration.telemetry.snapshot.LiveReading
import com.akane.inspiration.telemetry.store.ChargeSessionRecord
import com.akane.inspiration.telemetry.store.HealthSample
import com.akane.inspiration.telemetry.store.TelemetrySample
import kotlinx.collections.immutable.toImmutableList
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/** How far back the screen looks for the last charge (history and persisted samples). */
internal const val DATA_WINDOW_MS = 24L * 3_600_000

/** Period of the live charge reading while the screen is visible and plugged in; a curve needs no 1.5 s detail. */
internal const val CURVE_PERIOD_MS = 3_000L

/** Persisted samples further apart than this are not the same charge (used when history has no plug events). */
internal const val SESSION_GAP_MS = 30L * 60_000

/** A live sample is held over at most this long; a longer hole (screen closed) is "not observed", not a value. */
internal const val SAMPLE_MAX_GAP_MS = 30_000L

/** A history LEVEL event is held over at most this long (the system logs one per percent, so minutes apart). */
internal const val HISTORY_MAX_GAP_MS = 20L * 60_000

/** Heat above this while charging is counted as wear time (rule of thumb: lithium-ion ages faster when warm). */
internal const val HOT_TEMPERATURE_C = 40.0

/**
 * Voltage at or above this is counted as high-voltage time. 4.35 V is where lithium-ion cells rated for 4.40-4.45 V
 * spend their last, most stressful charge phase; a plain 4.2 V cell never reaches it, which then reads as "none".
 * A rule of thumb for a plain-language finding, not a chemistry model.
 */
internal const val HIGH_VOLTAGE_MV = 4_350

/**
 * Selected device-severity marker threshold (`PowerManager.THERMAL_STATUS_MODERATE`). The OEM defines the
 * thermal limits behind this status; it is neither an app-measured temperature nor evidence of charge limiting.
 */
internal const val THERMAL_STATUS_MODERATE = 2

/** Chart series are thinned to about this many points (first and last always kept). */
internal const val MAX_SERIES_POINTS = 240

/** Throttle markers (the edges of each stretch) are thinned to this many on a very ragged series. */
internal const val MAX_THROTTLE_MARKERS = 12

/** The wear chart needs two records to draw a line. */
internal const val MIN_WEAR_SAMPLES = 2

/** A curve needs at least two points (and two distinct levels) to be a line. */
private const val MIN_CURVE_POINTS = 2

/** One moment of a charge, from whichever source has it. Null readings are unknown, never zero. */
internal data class CurvePoint(
    val wallMs: Long,
    val levelPct: Int,
    val watts: Double?,
    val currentMa: Int?,
    val voltageMv: Int?,
    val temperatureC: Double?,
    val thermalStatus: Int?,
    val fromHistory: Boolean,
)

internal fun TelemetrySample.toCurvePoint(): CurvePoint? {
    if (plugged != true) return null
    val level = levelPct ?: return null
    return CurvePoint(
        wallMs = wallMs,
        levelPct = level,
        watts = watts,
        currentMa = currentMa,
        voltageMv = voltageMv,
        temperatureC = temperatureDeciC?.let { it / 10.0 },
        thermalStatus = thermalStatus,
        fromHistory = false,
    )
}

/** A live reading is a charging point unless it says it is unplugged. */
internal fun LiveReading.toCurvePoint(): CurvePoint? {
    if (plugged == false) return null
    val level = levelPct ?: return null
    return CurvePoint(
        wallMs = wallMs,
        levelPct = level,
        watts = watts,
        currentMa = currentMa,
        voltageMv = voltageMv,
        temperatureC = temperatureDeciC?.let { it / 10.0 },
        thermalStatus = thermalStatus,
        fromHistory = false,
    )
}

/** Only LEVEL events: level, temperature and voltage. The history carries no current, so power stays null. */
internal fun HistoryEvent.toCurvePoint(): CurvePoint? {
    if (kind != HistoryKind.LEVEL || levelPct !in 0..100) return null
    return CurvePoint(
        wallMs = wallMs,
        levelPct = levelPct,
        watts = null,
        currentMa = null,
        // The history stores 0 when the field was not logged, and no charging battery is at 0 degrees or 0 V.
        voltageMv = voltageMv.takeIf { it > 0 },
        temperatureC = temperatureDeciC.takeIf { it > 0 }?.let { it / 10.0 },
        thermalStatus = null,
        fromHistory = true,
    )
}

/** A stretch of plugged-in time. [endMs] is null while the charge is still going. */
internal data class ChargeSpan(
    val startMs: Long,
    val endMs: Long?,
)

/**
 * [lastCharge]'s answer. When [startsBeforeWindow] is set, the window opened mid-charge: the plug-in is older than the
 * window, and [span] starts at the window's first event only because nothing earlier was read.
 */
internal data class LastCharge(
    val span: ChargeSpan?,
    val startsBeforeWindow: Boolean,
) {
    /** [span] starting at [plugInMs], the plug-in found before the window, when there is one. */
    fun startingAt(plugInMs: Long?): ChargeSpan? =
        if (span != null && startsBeforeWindow && plugInMs != null && plugInMs < span.startMs) span.copy(startMs = plugInMs) else span
}

/** The most recent plug-in span in [events]; see [lastCharge]. */
internal fun lastChargeSpan(events: List<HistoryEvent>): ChargeSpan? = lastCharge(events).span

/**
 * The most recent plug-in span in [events]. A window that opens mid-charge (first plug event is an unplug, also one
 * right after an unplug reset, or a reset while plugged in) starts the span at the window's first event and says so,
 * so the caller can look further back for the plug-in ([scanPlugIn]). A reset while plugged in does not restart the
 * charge it falls in.
 */
internal fun lastCharge(events: List<HistoryEvent>): LastCharge {
    val sorted = events.sortedBy { it.wallMs }
    val windowStart = sorted.firstOrNull()?.wallMs
    var open: Long? = null
    var openBeforeWindow = false
    var last: LastCharge? = null
    var baselineKnown = false
    // An unplug RESET met before any plug event: the window may have opened mid-charge.
    var firstUnplugResetAt: Long? = null

    fun close(
        start: Long?,
        end: Long,
        beforeWindow: Boolean,
    ) {
        if (start != null && end > start) last = LastCharge(ChargeSpan(start, end), beforeWindow)
    }

    for (event in sorted) {
        when (event.kind) {
            HistoryKind.RESET -> {
                if (event.plugType == 0) {
                    // An unplug after a deep charge resets battery stats: that RESET carries plugType 0 and is the end
                    // of the charge. Its plugged flag is still set, so an UNPLUGGED follows at the same instant.
                    close(open, event.wallMs, openBeforeWindow)
                    if (open == null && !baselineKnown) firstUnplugResetAt = event.wallMs
                    open = null
                } else if (open == null) {
                    // Plugged in at a reset with no plug-in seen: before any plug event, the window opened mid-charge.
                    openBeforeWindow = !baselineKnown
                    open = if (openBeforeWindow) windowStart else event.wallMs
                }
                baselineKnown = true
            }

            HistoryKind.START -> {
                open = event.wallMs.takeIf { event.plugType != 0 }
                openBeforeWindow = false
                baselineKnown = true
            }

            HistoryKind.SHUTDOWN -> {
                close(open, event.wallMs, openBeforeWindow)
                open = null
                baselineKnown = true
            }

            HistoryKind.PLUGGED -> {
                if (open == null) {
                    open = event.wallMs
                    openBeforeWindow = false
                }
                baselineKnown = true
            }

            HistoryKind.UNPLUGGED -> {
                // Only the UNPLUGGED of that reset's own instant may still fall back; a later one follows a known
                // unplugged baseline (a reset on battery has no UNPLUGGED of its own).
                val midCharge = !baselineKnown || event.wallMs == firstUnplugResetAt
                val start = open
                if (start != null) close(start, event.wallMs, openBeforeWindow) else if (midCharge) close(windowStart, event.wallMs, true)
                open = null
                baselineKnown = true
            }
        }
    }
    return open?.let { LastCharge(ChargeSpan(it, null), openBeforeWindow) } ?: last ?: LastCharge(null, startsBeforeWindow = false)
}

/**
 * One step of the search for the plug-in of a charge that was already going when a window opened. [startMs] is the
 * plug-in when [settled], otherwise the oldest time the charge is known to have been going (null when none yet).
 */
internal data class PlugInScan(
    val startMs: Long?,
    val settled: Boolean,
)

/**
 * Scans [earlier], history that ends before the window (or before the previous step's [pluggedSinceMs]), newest first.
 * A PLUGGED, or a START while plugged in (a boot restarts the span, as in [lastCharge]), is the plug-in. A RESET while
 * plugged in only shows the charge was going then, so the scan goes on past it. An unplug, a shutdown, or a START or
 * RESET on battery settles the search on what was found so far: the plug-in after it was not recorded.
 */
internal fun scanPlugIn(
    earlier: List<HistoryEvent>,
    pluggedSinceMs: Long?,
): PlugInScan {
    var since = pluggedSinceMs
    for (event in earlier.sortedByDescending { it.wallMs }) {
        when (event.kind) {
            HistoryKind.PLUGGED -> return PlugInScan(event.wallMs, settled = true)
            HistoryKind.START -> return PlugInScan(if (event.plugType != 0) event.wallMs else since, settled = true)
            HistoryKind.RESET -> if (event.plugType != 0) since = event.wallMs else return PlugInScan(since, settled = true)
            HistoryKind.UNPLUGGED, HistoryKind.SHUTDOWN -> return PlugInScan(since, settled = true)
        }
    }
    return PlugInScan(since, settled = false)
}

/** The newest run of points no more than [SESSION_GAP_MS] apart. */
internal fun lastRun(points: List<CurvePoint>): List<CurvePoint> {
    if (points.isEmpty()) return points
    val sorted = points.sortedBy { it.wallMs }
    var start = sorted.lastIndex
    while (start > 0 && sorted[start].wallMs - sorted[start - 1].wallMs <= SESSION_GAP_MS) start--
    return sorted.subList(start, sorted.size)
}

/**
 * [persisted] (in time order, one point per time) and [live] (strictly increasing times) as one list in time order,
 * where a persisted point wins over a live point at the same time. It is
 * `(persisted + live).distinctBy { it.wallMs }.sortedBy { it.wallMs }` in one linear pass. A live tick still sorts
 * afterwards ([lastRun], [chargeSession]), but only points already in time order, or two such runs back to back (the
 * history's and the samples'), which the JVM's object sort (TimSort) handles in linear time.
 */
internal fun mergeByTime(
    persisted: List<CurvePoint>,
    live: List<CurvePoint>,
): List<CurvePoint> {
    if (live.isEmpty()) return persisted
    val merged = ArrayList<CurvePoint>(persisted.size + live.size)
    var i = 0
    var j = 0
    while (i < persisted.size || j < live.size) {
        when {
            j == live.size -> {
                merged += persisted[i++]
            }

            i == persisted.size -> {
                merged += live[j++]
            }

            persisted[i].wallMs < live[j].wallMs -> {
                merged += persisted[i++]
            }

            persisted[i].wallMs > live[j].wallMs -> {
                merged += live[j++]
            }

            else -> {
                merged += persisted[i++]
                j++
            }
        }
    }
    return merged
}

/** Resolve the same span for both the curve points and their duration analysis. */
internal fun effectiveChargeSpan(
    span: ChargeSpan?,
    samples: List<CurvePoint>,
    hasLive: Boolean,
): ChargeSpan? {
    val newest = samples.maxOfOrNull { it.wallMs }
    val extendsCompleted = span?.endMs?.let { end -> newest != null && newest > end } ?: false
    return if (hasLive && (span == null || extendsCompleted)) {
        lastRun(samples).firstOrNull()?.let { ChargeSpan(it.wallMs, null) }
    } else {
        span
    }
}

/**
 * The points of the charge to draw and analyse: history outside continuous observed sample runs, merged with recorded
 * samples. Samples win where both exist because they carry power and current as well.
 *
 * [span] is the history's view of the charge. While a live reading is flowing the history may not have ingested the
 * plug-in yet and would still point at the previous charge, so a live reading outside an open span re-derives the
 * span from the samples themselves.
 */
internal fun chargeSession(
    span: ChargeSpan?,
    samples: List<CurvePoint>,
    history: List<CurvePoint>,
    hasLive: Boolean,
): List<CurvePoint> {
    val effective = effectiveChargeSpan(span, samples, hasLive)
    val sampleSession =
        if (effective == null) {
            lastRun(samples)
        } else {
            samples.filter { it.wallMs >= effective.startMs && (effective.endMs == null || it.wallMs <= effective.endMs) }
        }.sortedBy { it.wallMs }
    val historySession =
        if (effective == null) {
            emptyList()
        } else {
            history.filter { point ->
                if (point.wallMs < effective.startMs || (effective.endMs != null && point.wallMs > effective.endMs)) {
                    false
                } else {
                    val match = sampleSession.binarySearchBy(point.wallMs) { it.wallMs }
                    if (match >= 0) {
                        false
                    } else {
                        val next = -match - 1
                        val covered =
                            next > 0 && next < sampleSession.size &&
                                sampleSession[next].wallMs - sampleSession[next - 1].wallMs <= SAMPLE_MAX_GAP_MS
                        !covered
                    }
                }
            }
        }
    return (historySession + sampleSession).sortedBy { it.wallMs }
}

private fun CurvePoint.throttled(): Boolean = (thermalStatus ?: 0) >= THERMAL_STATUS_MODERATE

/** Keeps at most [max] points, always the first and last, spread evenly. */
internal fun <T> thin(
    points: List<T>,
    max: Int,
): List<T> {
    if (points.size <= max || max < 2) return points
    val step = (points.size - 1).toDouble() / (max - 1)
    return List(max) { i -> points[Math.round(i * step).toInt()] }
}

/**
 * Marks each unbroken throttled stretch by its first and last point (one point when it is a single reading), so a long
 * stretch reads as a range instead of a smear of overlapping markers.
 */
private fun throttleEdges(valued: List<Pair<CurvePoint, Float>>): List<ChartPoint> {
    val edges = ArrayList<ChartPoint>()

    fun mark(index: Int) = edges.add(ChartPoint(valued[index].first.levelPct.toFloat(), valued[index].second))
    var runStart = -1
    for (i in valued.indices) {
        val hit = valued[i].first.throttled()
        if (hit && runStart < 0) runStart = i
        val runEnds = runStart >= 0 && (!hit || i == valued.lastIndex)
        if (runEnds) {
            val last = if (hit) i else i - 1
            mark(runStart)
            if (last != runStart) mark(last)
            runStart = -1
        }
    }
    return edges
}

private fun series(
    metric: CurveMetric,
    points: List<CurvePoint>,
    value: (CurvePoint) -> Float?,
): CurveSeriesUi? {
    val valued = points.mapNotNull { p -> value(p)?.takeIf(Float::isFinite)?.let { p to it } }
    if (valued.size < MIN_CURVE_POINTS) return null
    val line = valued.map { (p, v) -> ChartPoint(p.levelPct.toFloat(), v) }
    return CurveSeriesUi(
        metric = metric,
        points = thin(line, MAX_SERIES_POINTS).toImmutableList(),
        throttled = thin(throttleEdges(valued), MAX_THROTTLE_MARKERS).toImmutableList(),
        min = valued.minOf { it.second },
        max = valued.maxOf { it.second },
        latest = valued.last().second,
    )
}

/** The charge curve for [points] (one charge, time-sorted); [CurveUi.None] when it has fewer than two levels. */
internal fun curveUi(
    points: List<CurvePoint>,
    liveActive: Boolean,
): CurveUi {
    if (points.size < MIN_CURVE_POINTS) return CurveUi.None
    val levels = points.map { it.levelPct }
    if (levels.min() == levels.max()) return CurveUi.None

    val sampled = points.filter { !it.fromHistory }
    val source =
        when {
            sampled.isEmpty() -> CurveSource.History
            liveActive -> CurveSource.Live
            else -> CurveSource.Recorded
        }
    val series =
        listOfNotNull(
            series(CurveMetric.Watts, points) { it.watts?.toFloat() },
            series(CurveMetric.Current, points) { it.currentMa?.toFloat() },
            series(CurveMetric.Voltage, points) { it.voltageMv?.toFloat() },
            series(CurveMetric.Temperature, points) { it.temperatureC?.toFloat() },
        )
    if (series.isEmpty()) return CurveUi.None

    val throttleKnown = points.any { it.thermalStatus != null }
    val throttledPoints = points.filter { it.throttled() }
    return CurveUi.Ready(
        source = source,
        series = series.toImmutableList(),
        startLevelPct = points.first().levelPct,
        endLevelPct = points.last().levelPct,
        detailFromLevelPct =
            if (source != CurveSource.History && points.any { it.fromHistory }) {
                sampled.first().levelPct
            } else {
                null
            },
        throttleKnown = throttleKnown,
        throttle =
            throttledPoints.takeIf { it.isNotEmpty() }?.let { hit ->
                ThrottleUi(hit.size, hit.minOf { it.levelPct }, hit.maxOf { it.levelPct })
            },
    )
}

/**
 * Time above [HOT_TEMPERATURE_C] and at or above [HIGH_VOLTAGE_MV] while charging. Each reading is held until the next
 * one, but only when the two are close enough (the gap limit depends on the source); a longer hole is skipped and
 * simply not counted as observed. Null when [points] has fewer than two points or the charge has no length.
 */
internal fun wearAnalysis(
    points: List<CurvePoint>,
    span: ChargeSpan?,
    nowMs: Long,
    liveActive: Boolean,
): WearAnalysisUi? {
    if (points.size < MIN_CURVE_POINTS) return null
    var hot = 0L
    var hotObserved = 0L
    var high = 0L
    var highObserved = 0L
    for ((a, b) in points.zipWithNext()) {
        val dt = b.wallMs - a.wallMs
        val cap = if (a.fromHistory) HISTORY_MAX_GAP_MS else SAMPLE_MAX_GAP_MS
        if (dt <= 0 || dt > cap) continue
        a.temperatureC?.let { t ->
            hotObserved += dt
            if (t > HOT_TEMPERATURE_C) hot += dt
        }
        a.voltageMv?.let { v ->
            highObserved += dt
            if (v >= HIGH_VOLTAGE_MV) high += dt
        }
    }
    val startMs = span?.startMs?.coerceAtMost(points.first().wallMs) ?: points.first().wallMs
    val endMs = if (liveActive) maxOf(nowMs, points.last().wallMs) else span?.endMs ?: points.last().wallMs
    val temperatures = points.mapNotNull { it.temperatureC }
    val voltages = points.mapNotNull { it.voltageMv }
    return WearAnalysisUi(
        source =
            if (points.none { !it.fromHistory }) {
                CurveSource.History
            } else if (liveActive) {
                CurveSource.Live
            } else {
                CurveSource.Recorded
            },
        chargeMs = (endMs - startMs).coerceAtLeast(0),
        hotMs = hot.takeIf { temperatures.isNotEmpty() },
        hotObservedMs = hotObserved,
        highVoltageMs = high.takeIf { voltages.isNotEmpty() },
        voltageObservedMs = highObserved,
        maxTemperatureC = temperatures.maxOrNull(),
        maxVoltageMv = voltages.maxOrNull(),
        sparse = points.any { it.fromHistory },
    )
}

/**
 * The health card from the live kernel read with the last recorded health sample as fallback. A figure no source
 * has stays null with the reason it is missing; nothing is derived from a guess.
 */
internal fun healthCard(
    kernel: KernelCapacitySnapshot?,
    samples: List<HealthSample>,
    sessions: List<ChargeSessionRecord>,
): HealthCardUi {
    val latest = samples.maxByOrNull { it.wallMs }
    val design = kernel?.designMah?.takeIf { it > 0.0 } ?: latest?.designMah?.takeIf { it > 0.0 }
    val full = kernel?.chargeFullMah?.takeIf { it > 0.0 } ?: latest?.fccMah?.takeIf { it > 0.0 }
    val measured = latestMeasuredMah(samples, sessions)
    val cycles = kernel?.cycleCount ?: latest?.cycleCount
    val unknown = if (kernel == null) MissingReason.NoKernelRead else MissingReason.NotReported

    fun capacity(
        mah: Double?,
        missing: MissingReason,
    ) = CapacityUi(
        mah = mah,
        pctOfDesign = if (mah != null && design != null) mah / design * PERCENT else null,
        missing = if (mah == null) missing else null,
    )

    return HealthCardUi(
        design = CapacityUi(design, pctOfDesign = null, missing = if (design == null) unknown else null),
        reported = capacity(full, unknown),
        measured = capacity(measured, MissingReason.NotMeasuredYet),
        cycles = cycles,
        cyclesMissing = if (cycles == null) unknown else null,
    )
}

/** The newest independent capacity estimate: a stored health sample's, or an accepted charge session's. */
private fun latestMeasuredMah(
    samples: List<HealthSample>,
    sessions: List<ChargeSessionRecord>,
): Double? {
    val fromSample = samples.filter { it.measuredMah != null }.maxByOrNull { it.wallMs }
    val fromSession =
        sessions
            .filter { it.accepted && it.independent && it.estimateMah != null }
            .maxByOrNull { it.endWallMs }
    val sampleAt = fromSample?.wallMs ?: Long.MIN_VALUE
    val sessionAt = fromSession?.endWallMs ?: Long.MIN_VALUE
    return if (sessionAt > sampleAt) fromSession?.estimateMah else fromSample?.measuredMah
}

private const val PERCENT = 100.0

/** Full-charge capacity over design as a line; fewer than [MIN_WEAR_SAMPLES] usable records gives the empty state. */
internal fun wearUi(samples: List<HealthSample>): WearUi {
    val usable =
        samples
            .filter { it.designMah > 0.0 && it.fccMah > 0.0 }
            .distinctBy { it.wallMs }
            .sortedBy { it.wallMs }
    if (usable.size < MIN_WEAR_SAMPLES) return WearUi.NotEnough(usable.size)
    val startMs = usable.first().wallMs
    val pct = usable.map { (it.wallMs - startMs).toFloat() to (it.fccMah / it.designMah * PERCENT).toFloat() }
    return WearUi.Ready(
        points = thin(pct.map { ChartPoint(it.first, it.second) }, MAX_SERIES_POINTS).toImmutableList(),
        startMs = startMs,
        endMs = usable.last().wallMs,
        minPct = pct.minOf { it.second },
        maxPct = pct.maxOf { it.second },
        firstPct = pct.first().second,
        latestPct = pct.last().second,
    )
}

/** A value range padded so a line never touches the frame, at least [minSpan] tall so noise is not magnified. */
internal data class AxisRange(
    val min: Float,
    val max: Float,
)

internal fun axisRange(
    min: Float,
    max: Float,
    minSpan: Float,
): AxisRange {
    val span = maxOf(max - min, minSpan)
    val center = (min + max) / 2f
    val half = span * (1f + AXIS_PADDING) / 2f
    return AxisRange(center - half, center + half)
}

private const val AXIS_PADDING = 0.1f

/**
 * Round-numbered gridline values inside [range]: the smallest 1, 2 or 5 times a power of ten step that leaves at
 * most [maxCount] ticks, so an axis reads 5 / 10 / 15 instead of 15.7 / 8.4 / 1.2. Two ticks or more are preferred;
 * when no round step gives that, one is better than an ugly pair, and a degenerate range keeps its own ends.
 */
internal fun niceTicks(
    range: AxisRange,
    maxCount: Int = MAX_TICKS,
): List<Float> {
    val span = (range.max - range.min).toDouble()
    if (span <= 0.0) return listOf(range.min)
    val exponent = floor(log10(span)).toInt()
    val ticksByStep =
        ((exponent - 2)..(exponent + 1)).flatMap { power ->
            NICE_MULTIPLES.map { multiple ->
                val step = multiple * 10.0.pow(power)
                val first = ceil(range.min / step - TICK_EPSILON).toLong()
                val last = floor(range.max / step + TICK_EPSILON).toLong()
                (first..last).map { (it * step).toFloat() }
            }
        }
    return ticksByStep.firstOrNull { it.size in 2..maxCount }
        ?: ticksByStep.firstOrNull { it.size == 1 }
        ?: listOf(range.min, range.max)
}

private const val MAX_TICKS = 3
private const val TICK_EPSILON = 1e-9
private val NICE_MULTIPLES = doubleArrayOf(1.0, 2.0, 5.0)
