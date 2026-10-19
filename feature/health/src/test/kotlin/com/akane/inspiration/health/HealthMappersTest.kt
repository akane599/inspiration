package com.akane.inspiration.health

import com.akane.inspiration.ipc.model.HistoryKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

class HealthMappersTest {
    @Test
    fun `thermal markers use reported device severity rather than measured heat or charging power`() {
        for (status in listOf(null, 0, 1, 2, 3, 6)) {
            // Keep temperature, voltage and power identical: only reported device severity changes.
            val points =
                (0..1).map {
                    point(NOW_MS + it * SEC_MS, level = 40 + it, tempC = 20.0, thermal = status)
                }
            val curve = curveUi(points, liveActive = false).ready()
            assertEquals("known status=$status", status != null, curve.throttleKnown)
            assertEquals("moderate marker status=$status", status != null && status >= 2, curve.throttle != null)
            assertEquals(0L, wearAnalysis(points, null, NOW_MS, liveActive = false)?.hotMs)
            assertTrue(
                curve.series
                    .first { it.metric == CurveMetric.Watts }
                    .points
                    .all { it.y == 8f },
            )
        }

        val hotWithoutModerateStatus =
            (0..1).map {
                point(NOW_MS + it * SEC_MS, level = 40 + it, tempC = 50.0, thermal = 1)
            }
        assertNull(curveUi(hotWithoutModerateStatus, liveActive = false).ready().throttle)
        assertEquals(SEC_MS, wearAnalysis(hotWithoutModerateStatus, null, NOW_MS, liveActive = false)?.hotMs)
    }

    @Test
    fun `a single moderate status observation uses the one battery level summary`() {
        val points =
            listOf(
                point(NOW_MS, level = 40, thermal = null),
                point(NOW_MS + SEC_MS, level = 41, thermal = 2),
            )
        val curve = curveUi(points, liveActive = false).ready()
        assertTrue(curve.throttleKnown)
        assertEquals(ThrottleUi(count = 1, fromLevelPct = 41, toLevelPct = 41), curve.throttle)
    }

    @Test
    fun `localized thermal summaries describe recorded device status and never charge limiting`() {
        for (turkish in listOf(false, true)) {
            val directory = if (turkish) "values-tr" else "values"
            val factory =
                DocumentBuilderFactory.newInstance().apply {
                    setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                }
            val nodes =
                factory
                    .newDocumentBuilder()
                    .parse(File("src/main/res/$directory/strings.xml"))
                    .getElementsByTagName("string")
            val strings =
                (0 until nodes.length).associate { i ->
                    val node = nodes.item(i)
                    node.attributes.getNamedItem("name").nodeValue to node.textContent
                }
            val keys = listOf("health_throttle_key", "health_throttle_none", "health_throttle_range", "health_throttle_at")
            for (key in keys) {
                val text = strings.getValue(key).lowercase(Locale.ROOT)
                assertTrue("$directory/$key names device status", text.contains(if (turkish) "cihaz" else "device thermal status"))
                assertTrue("$directory/$key names the selected severity", text.contains(if (turkish) "orta" else "moderate"))
                assertFalse("$directory/$key must not diagnose charge limiting", text.contains(if (turkish) "şarj" else "charge"))
                assertFalse("$directory/$key must not invent a temperature threshold", text.contains("°"))
                if (key != "health_throttle_key") {
                    assertTrue("$directory/$key scopes the claim to readings", text.contains(if (turkish) "kaydedilen" else "recorded"))
                }
            }
            val range = strings.getValue("health_throttle_range")
            val at = strings.getValue("health_throttle_at")
            assertTrue(range.contains("%1\$s") && range.contains("%2\$s"))
            assertTrue(at.contains("%1\$s"))
            val locale = if (turkish) Locale.forLanguageTag("tr") else Locale.ENGLISH
            val renderedRange = String.format(locale, range, "58%", "67%")
            assertTrue(renderedRange.contains("58%") && renderedRange.contains("67%"))
            assertTrue(String.format(locale, at, "58%").contains("58%"))
        }
    }

    @Test
    fun `shutdown and unplugged boot exclude postboot discharge from charge history`() {
        val events =
            listOf(
                event(MIN_MS, HistoryKind.PLUGGED),
                event(2 * MIN_MS, HistoryKind.SHUTDOWN),
                event(3 * MIN_MS, HistoryKind.START).copy(plugType = 0),
                event(4 * MIN_MS, level = 80),
                event(5 * MIN_MS, level = 79),
            )
        val span = lastChargeSpan(events)
        assertEquals(ChargeSpan(MIN_MS, 2 * MIN_MS), span)
        assertTrue(chargeSession(span, emptyList(), events.mapNotNull { it.toCurvePoint() }, false).isEmpty())
    }

    @Test
    fun `boot and reset use the plug baseline and never infer a charge across a known boundary`() {
        for (kind in listOf(HistoryKind.START, HistoryKind.RESET)) {
            val plugged = listOf(event(MIN_MS, kind), event(2 * MIN_MS, level = 50), event(3 * MIN_MS, level = 51))
            val span = lastChargeSpan(plugged)
            assertEquals(ChargeSpan(MIN_MS, null), span)
            assertEquals(2, chargeSession(span, emptyList(), plugged.mapNotNull { it.toCurvePoint() }, false).size)
            val unplugged = listOf(event(MIN_MS, kind).copy(plugType = 0), event(2 * MIN_MS, HistoryKind.UNPLUGGED))
            assertNull(lastChargeSpan(unplugged))
            // A plugged baseline without a graceful shutdown must replace the previous open charge.
            assertEquals(ChargeSpan(MIN_MS, null), lastChargeSpan(listOf(event(0, HistoryKind.PLUGGED)) + plugged))
        }
    }

    @Test
    fun `an unplug reset ends the overnight charge instead of dropping it`() {
        // Plugged at 23:10, unplugged at 07:05 past the reset threshold: AOSP writes RESET with plugType 0, and the
        // plugged flag it still carries clears into an UNPLUGGED right after it.
        val events =
            listOf(
                event(0, HistoryKind.PLUGGED),
                event(HOUR_MS, level = 60),
                event(8 * HOUR_MS, HistoryKind.RESET).copy(plugType = 0),
                event(8 * HOUR_MS, HistoryKind.SCREEN_ON).copy(plugType = 0),
                event(8 * HOUR_MS, HistoryKind.UNPLUGGED).copy(plugType = 0),
                event(9 * HOUR_MS, level = 99).copy(plugType = 0),
            )

        val span = lastChargeSpan(events)

        assertEquals(ChargeSpan(0, 8 * HOUR_MS), span)
        assertEquals(span, effectiveChargeSpan(span, emptyList(), hasLive = false))
        // A reset while still plugged keeps charging from the reset, and a reset on battery opens nothing.
        assertEquals(ChargeSpan(8 * HOUR_MS, null), lastChargeSpan(events.take(2) + event(8 * HOUR_MS, HistoryKind.RESET)))
        assertNull(lastChargeSpan(listOf(event(8 * HOUR_MS, HistoryKind.RESET).copy(plugType = 0), event(9 * HOUR_MS))))
    }

    @Test
    fun `an unplug reset still ends a charge whose plug-in is before the window`() {
        // Plugged at 23:10, unplugged with a reset at 07:05, Health opened at 23:30 the next day: the 24 h window
        // begins mid-charge, so the PLUGGED is outside it and only the charging levels, the RESET and its UNPLUGGED remain.
        val events =
            listOf(
                event(30 * MIN_MS, level = 80),
                event(2 * HOUR_MS, level = 95),
                event(8 * HOUR_MS, HistoryKind.RESET).copy(plugType = 0),
                event(8 * HOUR_MS, HistoryKind.SCREEN_ON).copy(plugType = 0),
                event(8 * HOUR_MS, HistoryKind.UNPLUGGED).copy(plugType = 0),
                event(9 * HOUR_MS, level = 99).copy(plugType = 0),
            )

        assertEquals(ChargeSpan(30 * MIN_MS, 8 * HOUR_MS), lastChargeSpan(events))
    }

    @Test
    fun `a reset on battery with no unplug after it invents no charge`() {
        // A manual reset on battery: plugType 0 and no plugged flag, so no UNPLUGGED follows it.
        val events =
            listOf(
                event(HOUR_MS, level = 60).copy(plugType = 0),
                event(2 * HOUR_MS, HistoryKind.RESET).copy(plugType = 0),
                event(2 * HOUR_MS, HistoryKind.SCREEN_OFF).copy(plugType = 0),
                event(3 * HOUR_MS, level = 59).copy(plugType = 0),
            )

        assertNull(lastChargeSpan(events))
        // A later charge in the window is still found.
        val charged =
            events + event(4 * HOUR_MS, HistoryKind.PLUGGED) + event(5 * HOUR_MS, HistoryKind.UNPLUGGED).copy(plugType = 0)
        assertEquals(ChargeSpan(4 * HOUR_MS, 5 * HOUR_MS), lastChargeSpan(charged))
    }

    @Test
    fun `retained live buffer does not reopen a completed charge`() {
        val span = ChargeSpan(0, 60 * MIN_MS)
        val buffered = listOf(point(55 * MIN_MS), point(59 * MIN_MS))
        assertEquals(span, effectiveChargeSpan(span, buffered, hasLive = true))
        val history = listOf(point(0, fromHistory = true), point(5 * MIN_MS, fromHistory = true))
        assertEquals(0L, chargeSession(span, buffered, history, true).first().wallMs)
    }

    @Test
    fun `samples beyond previous charge retain the current session while ingestion lags`() {
        val previous = ChargeSpan(0, 30 * MIN_MS)
        val buffered = listOf(point(2 * HOUR_MS), point(2 * HOUR_MS + SEC_MS))
        assertEquals(ChargeSpan(2 * HOUR_MS, null), effectiveChargeSpan(previous, buffered, true))
    }

    @Test
    fun `history after and between foreground observations remains available`() {
        val history = (0..12).map { point(it * 5 * MIN_MS, fromHistory = true) }
        val samples =
            listOf(10 * MIN_MS, 10 * MIN_MS + 10 * SEC_MS, 40 * MIN_MS, 40 * MIN_MS + 10 * SEC_MS)
                .map { point(it) }
        val result = chargeSession(ChargeSpan(0, 60 * MIN_MS), samples, history, false)
        assertTrue(result.any { it.wallMs == 25 * MIN_MS && it.fromHistory })
        assertTrue(result.any { it.wallMs == 60 * MIN_MS && it.fromHistory })
        assertEquals(1, result.count { it.wallMs == 10 * MIN_MS })
        assertFalse(result.single { it.wallMs == 10 * MIN_MS }.fromHistory)
    }

    // ---- charge curve ---------------------------------------------------------------------------

    /** 11 readings 10 s apart, level 20..70, with moderate-or-higher device thermal status at 50 % and 55 %. */
    private fun chargingRun() =
        (0..10).map { i ->
            point(
                wallMs = NOW_MS + i * 10 * SEC_MS,
                level = 20 + i * 5,
                watts = 5.0 + i,
                currentMa = 1_000 + i * 100,
                voltageMv = 3_800 + i * 10,
                tempC = 30.0 + i * 0.5,
                thermal = if (i == 6 || i == 7) 2 + (i - 6) else 0,
            )
        }

    private fun CurveUi.ready(): CurveUi.Ready = this as CurveUi.Ready

    @Test
    fun `curve draws four metrics against battery level`() {
        val curve = curveUi(chargingRun(), liveActive = false).ready()

        assertEquals(
            listOf(CurveMetric.Watts, CurveMetric.Current, CurveMetric.Voltage, CurveMetric.Temperature),
            curve.series.map { it.metric },
        )
        assertEquals(CurveSource.Recorded, curve.source)
        assertEquals(20, curve.startLevelPct)
        assertEquals(70, curve.endLevelPct)
        val watts = curve.series.first { it.metric == CurveMetric.Watts }
        assertEquals(ChartPoint(20f, 5f), watts.points.first())
        assertEquals(ChartPoint(70f, 15f), watts.points.last())
        assertEquals(5f, watts.min, 0f)
        assertEquals(15f, watts.max, 0f)
        assertEquals(15f, watts.latest, 0f)
        assertEquals(
            35f,
            curve.series
                .first { it.metric == CurveMetric.Temperature }
                .points
                .last()
                .y,
            0f,
        )
    }

    @Test
    fun `device thermal status from moderate up is marked on every series and summarised`() {
        val curve = curveUi(chargingRun(), liveActive = false).ready()

        assertTrue(curve.throttleKnown)
        assertEquals(ThrottleUi(count = 2, fromLevelPct = 50, toLevelPct = 55), curve.throttle)
        for (series in curve.series) {
            assertEquals("markers of ${series.metric}", listOf(50f, 55f), series.throttled.map { it.x })
        }
        val temperature = curve.series.first { it.metric == CurveMetric.Temperature }
        assertEquals(33f, temperature.throttled.first().y, 0f)
    }

    @Test
    fun `light device status is below the selected marker threshold and unknown status is not a finding`() {
        val light = chargingRun().map { it.copy(thermalStatus = 1) }
        val lightCurve = curveUi(light, liveActive = false).ready()
        assertTrue(lightCurve.throttleKnown)
        assertNull(lightCurve.throttle)
        assertTrue(lightCurve.series.all { it.throttled.isEmpty() })

        val unknown = chargingRun().map { it.copy(thermalStatus = null) }
        val unknownCurve = curveUi(unknown, liveActive = false).ready()
        assertFalse("without device status even a sample-scoped negative would be a guess", unknownCurve.throttleKnown)
        assertNull(unknownCurve.throttle)
    }

    @Test
    fun `a live curve says live`() {
        assertEquals(CurveSource.Live, curveUi(chargingRun(), liveActive = true).ready().source)
    }

    @Test
    fun `history events alone give level, temperature and voltage but never power or current`() {
        val history =
            (0..5).map {
                point(NOW_MS + it * MIN_MS, level = 30 + it * 10, watts = null, currentMa = null, thermal = null, fromHistory = true)
            }
        val curve = curveUi(history, liveActive = false).ready()

        assertEquals(CurveSource.History, curve.source)
        assertEquals(listOf(CurveMetric.Voltage, CurveMetric.Temperature), curve.series.map { it.metric })
        assertFalse(curve.throttleKnown)
        assertNull(curve.detailFromLevelPct)
    }

    @Test
    fun `history followed by live samples reports where power and current begin`() {
        val history =
            (0..2).map {
                point(NOW_MS + it * MIN_MS, level = 10 + it * 10, watts = null, currentMa = null, thermal = null, fromHistory = true)
            }
        val samples = (0..3).map { point(NOW_MS + 3 * MIN_MS + it * 10 * SEC_MS, level = 30 + it) }
        val curve = curveUi(history + samples, liveActive = false).ready()

        assertEquals(CurveSource.Recorded, curve.source)
        assertEquals(30, curve.detailFromLevelPct)
        assertEquals(
            "power exists only where samples exist",
            4,
            curve.series
                .first { it.metric == CurveMetric.Watts }
                .points.size,
        )
        assertEquals(
            7,
            curve.series
                .first { it.metric == CurveMetric.Temperature }
                .points.size,
        )
    }

    @Test
    fun `a charge with fewer than two levels or no readings is no curve`() {
        assertEquals(CurveUi.None, curveUi(emptyList(), liveActive = false))
        assertEquals(CurveUi.None, curveUi(listOf(point(NOW_MS)), liveActive = false))
        assertEquals(
            "a flat level has no curve",
            CurveUi.None,
            curveUi(listOf(point(NOW_MS, level = 100), point(NOW_MS + SEC_MS, level = 100)), liveActive = false),
        )
        val blank =
            (0..3).map {
                point(
                    NOW_MS + it * SEC_MS,
                    level = 40 + it,
                    watts = null,
                    currentMa = null,
                    voltageMv = null,
                    tempC = null,
                )
            }
        assertEquals(CurveUi.None, curveUi(blank, liveActive = false))
    }

    @Test
    fun `long series are thinned and keep both ends`() {
        val many = (0 until 1_000).map { point(NOW_MS + it * SEC_MS, level = 1 + it / 11, thermal = if (it % 2 == 0) 2 else 0) }
        val curve = curveUi(many, liveActive = false).ready()

        val watts = curve.series.first()
        assertEquals(MAX_SERIES_POINTS, watts.points.size)
        assertEquals(MAX_THROTTLE_MARKERS, watts.throttled.size)
        assertEquals(1f, watts.points.first().x, 0f)
        assertEquals((1 + 999 / 11).toFloat(), watts.points.last().x, 0f)
        assertEquals(500, curve.throttle?.count)
    }

    @Test
    fun `a throttled stretch is marked by its first and last point, a lone reading once`() {
        // Levels 10..17; throttled at 11-13 (a stretch), 15 (alone) and 17 (alone, at the very end).
        val status = listOf(0, 2, 3, 2, 0, 2, 0, 2)
        val run = status.mapIndexed { i, s -> point(NOW_MS + i * 10 * SEC_MS, level = 10 + i, thermal = s) }
        val watts = curveUi(run, liveActive = false).ready().series.first()

        assertEquals(listOf(11f, 13f, 15f, 17f), watts.throttled.map { it.x })
    }

    @Test
    fun `thin keeps short lists untouched`() {
        assertEquals(listOf(1, 2, 3), thin(listOf(1, 2, 3), 240))
        assertEquals(listOf(0, 999), thin((0..999).toList(), 2))
    }

    @Test
    fun `persisted samples map only while plugged and with a level`() {
        assertNull(sample(NOW_MS, plugged = false).toCurvePoint())
        assertNull(sample(NOW_MS, plugged = null).toCurvePoint())
        assertNull(sample(NOW_MS, level = null).toCurvePoint())
        val mapped = sample(NOW_MS, level = 61, tempDeciC = 415, thermalStatus = 3).toCurvePoint()
        assertEquals(41.5, mapped?.temperatureC ?: 0.0, 1e-9)
        assertEquals(3, mapped?.thermalStatus)
        assertFalse(mapped?.fromHistory ?: true)
    }

    @Test
    fun `history maps LEVEL events only, with a zero temperature or voltage as missing`() {
        assertNull(event(NOW_MS, kind = HistoryKind.SCREEN_ON).toCurvePoint())
        assertNull(event(NOW_MS, kind = HistoryKind.PLUGGED).toCurvePoint())
        val mapped = event(NOW_MS, level = 55, tempDeciC = 0, voltageMv = 0).toCurvePoint()
        assertNotNull(mapped)
        assertNull(mapped?.temperatureC)
        assertNull(mapped?.voltageMv)
        assertNull(mapped?.watts)
        assertNull(mapped?.currentMa)
        assertTrue(mapped?.fromHistory ?: false)
        assertEquals(41.0, event(NOW_MS, tempDeciC = 410).toCurvePoint()?.temperatureC ?: 0.0, 1e-9)
    }

    @Test
    fun `a live reading that says unplugged is not a charging point`() {
        assertNull(liveReading(plugged = false).toCurvePoint())
        assertNotNull(liveReading(plugged = true).toCurvePoint())
        assertNotNull(liveReading(plugged = null).toCurvePoint())
        assertNull(liveReading(levelPct = null).toCurvePoint())
    }

    // ---- charge span and session ----------------------------------------------------------------

    @Test
    fun `last charge span is the newest plug in, open while still plugged`() {
        val events =
            listOf(
                event(10 * MIN_MS, HistoryKind.PLUGGED),
                event(20 * MIN_MS, HistoryKind.LEVEL),
                event(30 * MIN_MS, HistoryKind.UNPLUGGED),
                event(40 * MIN_MS, HistoryKind.PLUGGED),
            )
        assertEquals(ChargeSpan(40 * MIN_MS, null), lastChargeSpan(events))
        assertEquals(ChargeSpan(10 * MIN_MS, 30 * MIN_MS), lastChargeSpan(events.dropLast(1)))
        assertEquals("unsorted input", ChargeSpan(10 * MIN_MS, 30 * MIN_MS), lastChargeSpan(events.dropLast(1).reversed()))
    }

    @Test
    fun `a window that opens mid charge starts the span at its first event`() {
        val events = listOf(event(5 * MIN_MS, HistoryKind.LEVEL), event(25 * MIN_MS, HistoryKind.UNPLUGGED))
        assertEquals(ChargeSpan(5 * MIN_MS, 25 * MIN_MS), lastChargeSpan(events))
    }

    @Test
    fun `no plug event means no span`() {
        assertNull(lastChargeSpan(emptyList()))
        assertNull(lastChargeSpan(listOf(event(MIN_MS, HistoryKind.LEVEL), event(2 * MIN_MS, HistoryKind.SCREEN_ON))))
    }

    @Test
    fun `last run splits on a long gap`() {
        val points = listOf(0L, 1L, 2L, 40L, 41L).map { point(it * MIN_MS) }
        assertEquals(listOf(40L, 41L).map { it * MIN_MS }, lastRun(points).map { it.wallMs })
        assertTrue(lastRun(emptyList()).isEmpty())
    }

    @Test
    fun `session uses history before the samples and the samples after`() {
        val span = ChargeSpan(0, null)
        val history = (0..4).map { point(it * MIN_MS, level = 10 + it * 5, fromHistory = true, watts = null, currentMa = null) }
        val samples = (0..2).map { point(3 * MIN_MS + it * 10 * SEC_MS, level = 25 + it, fromHistory = false) }

        val session = chargeSession(span, samples, history, hasLive = false)

        assertEquals(listOf(true, true, true, false, false, false, true), session.map { it.fromHistory })
        assertEquals(listOf(0L, MIN_MS, 2 * MIN_MS), session.take(3).map { it.wallMs })
    }

    @Test
    fun `a live reading ignores a history span that ended before it`() {
        val oldSpan = ChargeSpan(0, 30 * MIN_MS)
        val oldHistory = (0..3).map { point(it * 10 * MIN_MS, level = 10 + it, fromHistory = true) }
        val live = (0..2).map { point(5 * HOUR_MS + it * 10 * SEC_MS, level = 40 + it) }

        val session = chargeSession(oldSpan, live, oldHistory, hasLive = true)

        assertEquals(live.map { it.wallMs }, session.map { it.wallMs })
    }

    @Test
    fun `without any span the newest run of samples is the charge`() {
        val samples = listOf(0L, 1L, 90L, 91L).map { point(it * MIN_MS, level = 20 + it.toInt() / 10) }
        assertEquals(
            listOf(90L, 91L).map { it * MIN_MS },
            chargeSession(null, samples, emptyList(), hasLive = false).map { it.wallMs },
        )
    }

    @Test
    fun `heat and voltage holdover caps follow only the predecessor source at exact boundaries`() {
        for (predecessorFromHistory in listOf(false, true)) {
            for (successorFromHistory in listOf(false, true)) {
                for (gapMs in listOf(30_000L, 30_001L, 1_200_000L, 1_200_001L)) {
                    val points =
                        listOf(
                            point(NOW_MS, level = 40, tempC = 45.0, voltageMv = 4_400, fromHistory = predecessorFromHistory),
                            point(NOW_MS + gapMs, level = 41, tempC = 30.0, voltageMv = 4_000, fromHistory = successorFromHistory),
                        )
                    val analysis = checkNotNull(wearAnalysis(points, null, NOW_MS + gapMs, liveActive = false))
                    val capMs = if (predecessorFromHistory) 1_200_000L else 30_000L
                    val expectedCoverageMs = if (gapMs <= capMs) gapMs else 0L
                    val case = "history predecessor=$predecessorFromHistory, history successor=$successorFromHistory, gap=$gapMs"
                    assertEquals("$case hot time", expectedCoverageMs, analysis.hotMs)
                    assertEquals("$case hot coverage", expectedCoverageMs, analysis.hotObservedMs)
                    assertEquals("$case high-voltage time", expectedCoverageMs, analysis.highVoltageMs)
                    assertEquals("$case voltage coverage", expectedCoverageMs, analysis.voltageObservedMs)
                    assertEquals("$case retains full charge duration", gapMs, analysis.chargeMs)
                }
            }
        }
    }

    @Test
    fun `history after a foreground run does not extend measured heat or voltage evidence`() {
        val span = ChargeSpan(NOW_MS, NOW_MS + 20 * MIN_MS)
        val samples =
            listOf(
                point(NOW_MS, level = 40, tempC = 45.0, voltageMv = 4_400),
                point(NOW_MS + 3 * SEC_MS, level = 41, tempC = 45.0, voltageMv = 4_400),
            )
        val history =
            listOf(
                point(NOW_MS + 20 * MIN_MS, level = 50, tempC = 30.0, voltageMv = 4_000, fromHistory = true),
            )
        val session = chargeSession(span, samples, history, hasLive = false)
        assertEquals(listOf(false, false, true), session.map { it.fromHistory })
        val analysis = checkNotNull(wearAnalysis(session, span, NOW_MS + 20 * MIN_MS, liveActive = false))
        assertEquals(20 * MIN_MS, analysis.chargeMs)
        assertEquals(3 * SEC_MS, analysis.hotMs)
        assertEquals(3 * SEC_MS, analysis.hotObservedMs)
        assertEquals(3 * SEC_MS, analysis.highVoltageMs)
        assertEquals(3 * SEC_MS, analysis.voltageObservedMs)
        assertTrue(analysis.sparse)
    }

    // ---- heat and voltage analysis --------------------------------------------------------------

    /** 11 readings 10 s apart: hot for the first five, at high voltage for the first three. */
    private fun heatRun() =
        (0..10).map { i ->
            point(
                wallMs = NOW_MS + i * 10 * SEC_MS,
                level = 20 + i,
                tempC = if (i <= 4) 41.0 else 35.0,
                voltageMv = if (i <= 2) 4_400 else 4_000,
            )
        }

    @Test
    fun `hot and high voltage time are held per reading and counted against what was observed`() {
        val analysis = wearAnalysis(heatRun(), span = null, nowMs = NOW_MS + 100 * SEC_MS, liveActive = false)

        assertNotNull(analysis)
        assertEquals(50 * SEC_MS, analysis?.hotMs)
        assertEquals(100 * SEC_MS, analysis?.hotObservedMs)
        assertEquals(30 * SEC_MS, analysis?.highVoltageMs)
        assertEquals(100 * SEC_MS, analysis?.voltageObservedMs)
        assertEquals(41.0, analysis?.maxTemperatureC ?: 0.0, 1e-9)
        assertEquals(4_400, analysis?.maxVoltageMv)
        assertEquals(100 * SEC_MS, analysis?.chargeMs)
        assertEquals(CurveSource.Recorded, analysis?.source)
        assertFalse(analysis?.sparse ?: true)
    }

    @Test
    fun `the limits are exclusive for heat and inclusive for voltage`() {
        val run = (0..3).map { point(NOW_MS + it * 10 * SEC_MS, level = 20 + it, tempC = HOT_TEMPERATURE_C, voltageMv = HIGH_VOLTAGE_MV) }
        val analysis = wearAnalysis(run, null, NOW_MS, liveActive = false)

        assertEquals("exactly 40 degrees is not above 40", 0L, analysis?.hotMs)
        assertEquals("exactly the high-voltage limit counts", 30 * SEC_MS, analysis?.highVoltageMs)
    }

    @Test
    fun `a hole in the readings is skipped, not filled`() {
        val run =
            listOf(
                point(NOW_MS, tempC = 45.0, voltageMv = 4_400),
                point(NOW_MS + 10 * SEC_MS, tempC = 45.0, voltageMv = 4_400),
                // The screen was closed for five minutes: far more than a live sample may be held over.
                point(NOW_MS + 10 * SEC_MS + 5 * MIN_MS, tempC = 45.0, voltageMv = 4_400),
                point(NOW_MS + 20 * SEC_MS + 5 * MIN_MS, tempC = 45.0, voltageMv = 4_400),
            )
        val analysis = wearAnalysis(run, null, NOW_MS, liveActive = false)

        assertEquals(20 * SEC_MS, analysis?.hotMs)
        assertEquals("observed time excludes the hole", 20 * SEC_MS, analysis?.hotObservedMs)
        assertEquals("the charge itself still includes it", 5 * MIN_MS + 20 * SEC_MS, analysis?.chargeMs)
    }

    @Test
    fun `history events are held up to twenty minutes and the analysis says it is sparse`() {
        val run =
            listOf(
                point(NOW_MS, level = 40, tempC = 42.0, voltageMv = 4_000, fromHistory = true, watts = null, currentMa = null),
                point(
                    NOW_MS + 15 * MIN_MS,
                    level = 50,
                    tempC = 42.0,
                    voltageMv = 4_000,
                    fromHistory = true,
                    watts = null,
                    currentMa = null,
                ),
                point(
                    NOW_MS + 40 * MIN_MS,
                    level = 60,
                    tempC = 42.0,
                    voltageMv = 4_000,
                    fromHistory = true,
                    watts = null,
                    currentMa = null,
                ),
            )
        val analysis = wearAnalysis(run, null, NOW_MS, liveActive = false)

        assertEquals(15 * MIN_MS, analysis?.hotMs)
        assertEquals(15 * MIN_MS, analysis?.hotObservedMs)
        assertEquals(CurveSource.History, analysis?.source)
        assertTrue(analysis?.sparse ?: false)
    }

    @Test
    fun `no temperature or voltage readings is unknown, not zero`() {
        val run = (0..3).map { point(NOW_MS + it * 10 * SEC_MS, level = 20 + it, tempC = null, voltageMv = null) }
        val analysis = wearAnalysis(run, null, NOW_MS, liveActive = false)

        assertNull(analysis?.hotMs)
        assertNull(analysis?.highVoltageMs)
        assertNull(analysis?.maxTemperatureC)
        assertNull(analysis?.maxVoltageMv)
    }

    @Test
    fun `charge length follows the span and runs to now while live`() {
        val run = heatRun()
        val withSpan = wearAnalysis(run, ChargeSpan(NOW_MS - 50 * SEC_MS, NOW_MS + 130 * SEC_MS), NOW_MS, liveActive = false)
        assertEquals(180 * SEC_MS, withSpan?.chargeMs)

        val live = wearAnalysis(run, null, nowMs = NOW_MS + 160 * SEC_MS, liveActive = true)
        assertEquals(160 * SEC_MS, live?.chargeMs)
        assertEquals(CurveSource.Live, live?.source)
    }

    @Test
    fun `fewer than two points is no analysis`() {
        assertNull(wearAnalysis(emptyList(), null, NOW_MS, liveActive = false))
        assertNull(wearAnalysis(listOf(point(NOW_MS)), null, NOW_MS, liveActive = false))
    }

    // ---- health card ----------------------------------------------------------------------------

    @Test
    fun `every missing figure carries its reason and nothing is invented`() {
        val card = healthCard(kernel = null, samples = emptyList(), sessions = emptyList())

        assertNull(card.design.mah)
        assertEquals(MissingReason.NoKernelRead, card.design.missing)
        assertNull(card.reported.mah)
        assertEquals(MissingReason.NoKernelRead, card.reported.missing)
        assertNull(card.reported.pctOfDesign)
        assertNull(card.measured.mah)
        assertEquals(MissingReason.NotMeasuredYet, card.measured.missing)
        assertNull(card.cycles)
        assertEquals(MissingReason.NoKernelRead, card.cyclesMissing)
    }

    @Test
    fun `a kernel that answers but lacks a node says it does not report it`() {
        val card = healthCard(kernelSnapshot(chargeFullMah = null, cycles = null), emptyList(), emptyList())

        assertEquals(5_000.0, card.design.mah ?: 0.0, 0.0)
        assertNull(card.design.missing)
        assertNull(card.reported.mah)
        assertEquals(MissingReason.NotReported, card.reported.missing)
        assertNull(card.cycles)
        assertEquals(MissingReason.NotReported, card.cyclesMissing)
    }

    @Test
    fun `kernel figures give the share of design and the measured estimate comes from an independent session`() {
        val card =
            healthCard(
                kernelSnapshot(),
                emptyList(),
                listOf(
                    sessionRecord(endWallMs = 100, estimateMah = 4_200.0),
                    sessionRecord(endWallMs = 300, estimateMah = 3_000.0, accepted = false),
                    sessionRecord(endWallMs = 400, estimateMah = 3_100.0, independent = false),
                    sessionRecord(endWallMs = 500, estimateMah = null),
                ),
            )

        assertEquals(4_300.0, card.reported.mah ?: 0.0, 0.0)
        assertEquals(86.0, card.reported.pctOfDesign ?: 0.0, 1e-9)
        assertEquals(4_200.0, card.measured.mah ?: 0.0, 0.0)
        assertEquals(84.0, card.measured.pctOfDesign ?: 0.0, 1e-9)
        assertNull(card.measured.missing)
        assertEquals(212, card.cycles)
        assertNull(card.cyclesMissing)
    }

    @Test
    fun `without a kernel read the newest recorded health sample stands in`() {
        val card =
            healthCard(
                kernel = null,
                samples =
                    listOf(
                        healthSample(wallMs = 10, fcc = 4_000.0, cycles = 50),
                        healthSample(wallMs = 20, fcc = 4_400.0, design = 5_000.0, cycles = 70, measured = 4_100.0),
                    ),
                sessions = emptyList(),
            )

        assertEquals(4_400.0, card.reported.mah ?: 0.0, 0.0)
        assertEquals(88.0, card.reported.pctOfDesign ?: 0.0, 1e-9)
        assertEquals(4_100.0, card.measured.mah ?: 0.0, 0.0)
        assertEquals(70, card.cycles)
    }

    @Test
    fun `the newer of a stored measurement and a charge session wins`() {
        val sampleNewer =
            healthCard(
                kernelSnapshot(),
                listOf(healthSample(wallMs = 200, measured = 4_100.0)),
                listOf(sessionRecord(endWallMs = 100, estimateMah = 4_200.0)),
            )
        assertEquals(4_100.0, sampleNewer.measured.mah ?: 0.0, 0.0)

        val sessionNewer =
            healthCard(
                kernelSnapshot(),
                listOf(healthSample(wallMs = 100, measured = 4_100.0)),
                listOf(sessionRecord(endWallMs = 200, estimateMah = 4_200.0)),
            )
        assertEquals(4_200.0, sessionNewer.measured.mah ?: 0.0, 0.0)
    }

    @Test
    fun `a zero capacity is missing, not a figure`() {
        val card =
            healthCard(
                kernelSnapshot(chargeFullMah = 0.0, designMah = 0.0),
                listOf(healthSample(wallMs = 10, fcc = 4_000.0, design = 5_000.0)),
                emptyList(),
            )
        assertEquals("falls back to the recorded sample", 4_000.0, card.reported.mah ?: 0.0, 0.0)
        assertEquals(5_000.0, card.design.mah ?: 0.0, 0.0)
    }

    // ---- wear chart -----------------------------------------------------------------------------

    @Test
    fun `wear chart accepts capacity samples without cycle counts`() {
        val wear =
            wearUi(
                listOf(
                    healthSample(10, fcc = 4_300.0).copy(cycleCount = null),
                    healthSample(20, fcc = 4_200.0).copy(cycleCount = null),
                ),
            )

        assertTrue(wear is WearUi.Ready)
        assertEquals(2, (wear as WearUi.Ready).points.size)
        assertEquals(84f, wear.latestPct, 0f)
    }

    @Test
    fun `fewer than two usable health records is the empty state`() {
        assertEquals(WearUi.NotEnough(0), wearUi(emptyList()))
        assertEquals(WearUi.NotEnough(1), wearUi(listOf(healthSample(10))))
        assertEquals(
            "a record without a design capacity cannot be drawn",
            WearUi.NotEnough(1),
            wearUi(listOf(healthSample(10), healthSample(20, design = 0.0))),
        )
        assertEquals(
            "the same instant twice is one record",
            WearUi.NotEnough(1),
            wearUi(listOf(healthSample(10), healthSample(10, fcc = 4_000.0))),
        )
    }

    @Test
    fun `wear is capacity over design in time order`() {
        val wear =
            wearUi(
                listOf(
                    healthSample(wallMs = 3 * HOUR_MS, fcc = 4_300.0),
                    healthSample(wallMs = HOUR_MS, fcc = 4_500.0),
                    healthSample(wallMs = 2 * HOUR_MS, fcc = 4_400.0),
                ),
            ) as WearUi.Ready

        assertEquals(HOUR_MS, wear.startMs)
        assertEquals(3 * HOUR_MS, wear.endMs)
        assertEquals(listOf(0f, HOUR_MS.toFloat(), 2 * HOUR_MS.toFloat()), wear.points.map { it.x })
        assertEquals(listOf(90f, 88f, 86f), wear.points.map { it.y })
        assertEquals(90f, wear.firstPct, 0f)
        assertEquals(86f, wear.latestPct, 0f)
        assertEquals(86f, wear.minPct, 0f)
        assertEquals(90f, wear.maxPct, 0f)
    }

    // ---- axis -----------------------------------------------------------------------------------

    @Test
    fun `an axis pads the data and never zooms tighter than its minimum span`() {
        val padded = axisRange(0f, 100f, minSpan = 2f)
        assertEquals(-5f, padded.min, 1e-4f)
        assertEquals(105f, padded.max, 1e-4f)

        val flat = axisRange(30f, 32f, minSpan = 6f)
        assertEquals(27.7f, flat.min, 1e-4f)
        assertEquals(34.3f, flat.max, 1e-4f)
    }

    @Test
    fun `gridlines sit on round numbers inside the axis, at most three`() {
        assertEquals(listOf(5f, 10f, 15f), niceTicks(AxisRange(1.2f, 15.7f)))
        assertEquals(listOf(0f, 50f, 100f), niceTicks(AxisRange(-5f, 105f)))
        assertEquals(listOf(0.5f, 1f, 1.5f), niceTicks(AxisRange(0.3f, 1.7f)))
        assertEquals(listOf(0f, 5f), niceTicks(AxisRange(-0.2f, 8.4f)))
        // No round step puts two ticks in this window, so it keeps the one round value rather than ugly ends.
        assertEquals(listOf(30f), niceTicks(AxisRange(27.7f, 34.3f)))
    }
}
