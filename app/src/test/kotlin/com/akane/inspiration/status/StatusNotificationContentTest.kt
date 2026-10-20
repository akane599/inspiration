package com.akane.inspiration.status

import com.akane.inspiration.designsystem.component.format.TelemetryFormat
import com.akane.inspiration.designsystem.component.format.TelemetryUnits
import com.akane.inspiration.telemetry.status.UnplugSummary
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneOffset
import java.util.Locale

class StatusNotificationContentTest {
    private val english = StatusStrings(
        percent = "%1\$s%%",
        titleSinceUnplug = "%1\$s since unplug · %2\$s",
        titleSinceTime = "%1\$s since %2\$s · %3\$s",
        detail = "Screen on %1\$s · off %2\$s · Deep sleep %3\$s",
        charging = "Charging · %1\$s",
        pluggedNotCharging = "Plugged in, not charging · %1\$s",
        chargingHint = "Drain stats start when you unplug",
    )
    private val turkish = StatusStrings(
        percent = "%%%1\$s",
        titleSinceUnplug = "Şarjdan çıkarıldığından beri %1\$s · %2\$s",
        titleSinceTime = "%2\$s itibarıyla %1\$s · %3\$s",
        detail = "Ekran açık %1\$s · kapalı %2\$s · Derin uyku %3\$s",
        charging = "Şarj oluyor · %1\$s",
        pluggedNotCharging = "Şarja takılı, şarj olmuyor · %1\$s",
        chargingHint = "Tüketim istatistikleri şarjdan çıkardığınızda başlar",
    )
    private val enFormat = TelemetryFormat(Locale.ENGLISH, TelemetryUnits.English)
    private val trFormat = TelemetryFormat(
        Locale.forLanguageTag("tr"),
        TelemetryUnits.English.copy(percentPerHour = "%/sa", hour = "sa", minute = "dk"),
    )

    private fun discharging(
        dropPct: Int = 18,
        elapsedMs: Long = 6 * HOUR_MS + 12 * MINUTE_MS,
        on: Double? = 9.4,
        off: Double? = 0.6,
        deepSleep: Double? = 0.92,
        anchorIsUnplug: Boolean = true,
        anchorWallMs: Long = 0L,
    ) = UnplugSummary.Discharging(
        levelNow = 64,
        dropPct = dropPct,
        elapsedMs = elapsedMs,
        screenOnPctPerHour = on,
        screenOffPctPerHour = off,
        deepSleepRatio = deepSleep,
        anchorIsUnplug = anchorIsUnplug,
        anchorWallMs = anchorWallMs,
    )

    private fun build(summary: UnplugSummary, strings: StatusStrings, format: TelemetryFormat) =
        StatusNotificationContent.build(summary, strings, format, ZoneOffset.UTC)

    @Test
    fun `english discharging shows drain duration rates and deep sleep`() {
        val text = build(discharging(), english, enFormat)

        assertEquals("−18% since unplug · 6\u00A0h\u00A012\u00A0min", text.title)
        assertEquals("Screen on 9.4\u00A0%/h · off 0.6\u00A0%/h · Deep sleep 92%", text.text)
    }

    @Test
    fun `turkish discharging uses the localised separator percent and units`() {
        val text = build(discharging(), turkish, trFormat)

        assertEquals("Şarjdan çıkarıldığından beri −%18 · 6\u00A0sa\u00A012\u00A0dk", text.title)
        assertEquals("Ekran açık 9,4\u00A0%/sa · kapalı 0,6\u00A0%/sa · Derin uyku %92", text.text)
    }

    @Test
    fun `parts that could not be computed read as a dash`() {
        val text = build(discharging(on = null, off = null, deepSleep = null), english, enFormat)

        assertEquals("Screen on — · off — · Deep sleep —", text.text)
    }

    @Test
    fun `no drop has no minus sign`() {
        val text = build(discharging(dropPct = 0, elapsedMs = 20 * MINUTE_MS), english, enFormat)

        assertEquals("0% since unplug · 20\u00A0min", text.title)
    }

    @Test
    fun `no unplug anchor says since the time of the oldest snapshot`() {
        val anchor = 14 * HOUR_MS + 20 * MINUTE_MS
        val summary = discharging(anchorIsUnplug = false, anchorWallMs = anchor)

        assertEquals("−18% since 14:20 · 6\u00A0h\u00A012\u00A0min", build(summary, english, enFormat).title)
        assertEquals("14:20 itibarıyla −%18 · 6\u00A0sa\u00A012\u00A0dk", build(summary, turkish, trFormat).title)
    }

    @Test
    fun `an anchor more than a day old includes the date`() {
        val summary = discharging(anchorIsUnplug = false, anchorWallMs = 0L, elapsedMs = 30 * HOUR_MS)

        assertEquals("−18% since 1\u00A0Jan\u00A000:00 · 30\u00A0h\u00A000\u00A0min", build(summary, english, enFormat).title)
    }

    @Test
    fun `charging shows the level and the hint`() {
        val en = build(UnplugSummary.Charging(64), english, enFormat)
        val tr = build(UnplugSummary.Charging(64), turkish, trFormat)

        assertEquals("Charging · 64%", en.title)
        assertEquals("Drain stats start when you unplug", en.text)
        assertEquals("Şarj oluyor · %64", tr.title)
        assertEquals("Tüketim istatistikleri şarjdan çıkardığınızda başlar", tr.text)
    }

    @Test
    fun `charging with an unknown level reads as a dash`() {
        assertEquals("Charging · —", build(UnplugSummary.Charging(null), english, enFormat).title)
    }

    @Test
    fun `plugged in but not charging says so, never charging, and keeps the hint`() {
        val en = build(UnplugSummary.PluggedNotCharging(80), english, enFormat)
        val tr = build(UnplugSummary.PluggedNotCharging(80), turkish, trFormat)

        assertEquals("Plugged in, not charging · 80%", en.title)
        assertEquals("Drain stats start when you unplug", en.text)
        assertEquals("Şarja takılı, şarj olmuyor · %80", tr.title)
        assertEquals("Tüketim istatistikleri şarjdan çıkardığınızda başlar", tr.text)
        assertEquals("Plugged in, not charging · —", build(UnplugSummary.PluggedNotCharging(null), english, enFormat).title)
    }

    private companion object {
        const val MINUTE_MS = 60_000L
        const val HOUR_MS = 60 * MINUTE_MS
    }
}
