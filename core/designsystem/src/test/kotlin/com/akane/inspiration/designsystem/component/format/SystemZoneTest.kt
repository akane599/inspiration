package com.akane.inspiration.designsystem.component.format

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.ZoneId
import java.util.TimeZone

class SystemZoneTest {
    private lateinit var saved: TimeZone

    @Before
    fun pinDefault() {
        saved = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("America/Sao_Paulo"))
    }

    @After
    fun restoreDefault() {
        TimeZone.setDefault(saved)
    }

    @Test
    fun theBroadcastZoneWinsOverAStaleProcessDefault() {
        assertEquals(ZoneId.of("Asia/Tokyo"), zoneAfterChange("Asia/Tokyo"))
    }

    @Test
    fun aMissingExtraFallsBackToTheSystemDefault() {
        assertEquals(ZoneId.of("America/Sao_Paulo"), zoneAfterChange(null))
    }

    @Test
    fun anUnknownOrMalformedZoneFallsBackToTheSystemDefault() {
        assertEquals(ZoneId.of("America/Sao_Paulo"), zoneAfterChange("Not/AZone"))
        assertEquals(ZoneId.of("America/Sao_Paulo"), zoneAfterChange("+99:00"))
        assertEquals(ZoneId.of("America/Sao_Paulo"), zoneAfterChange(""))
    }

    @Test
    fun anEqualZoneIsEqualSoTheStateWriteIsSkipped() {
        // mutableStateOf's structural policy compares with equals: a broadcast naming the zone already held must not
        // count as a change, or every chart would lose its skipping and draw cache for nothing.
        assertEquals(ZoneId.systemDefault(), zoneAfterChange("America/Sao_Paulo"))
    }
}
