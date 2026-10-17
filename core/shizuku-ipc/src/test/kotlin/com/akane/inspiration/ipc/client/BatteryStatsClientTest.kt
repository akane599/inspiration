package com.akane.inspiration.ipc.client

import android.os.RemoteException
import com.akane.inspiration.ipc.model.Reasons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.InvocationTargetException

class BatteryStatsClientTest {
    private fun client(calls: FakeBatteryStatsCalls, sdkInt: Int = 35) =
        BatteryStatsClient({ fakeBinder }, sdkInt) { calls }

    private fun queryCallsFor(sdkInt: Int): List<String> {
        val calls = FakeBatteryStatsCalls()
        client(calls, sdkInt).readUsage(maxAgeMs = 0L)
        return calls.builder.calls
    }

    @Test
    fun `api 31 sets max age and power models only`() {
        assertEquals(listOf("setMaxStatsAgeMs(0)", "includePowerModels"), queryCallsFor(31))
    }

    @Test
    fun `api 33 and 34 add process state and virtual uids and keep power models`() {
        val expected = listOf(
            "setMaxStatsAgeMs(0)",
            "includeProcessStateData",
            "includeVirtualUids",
            "includePowerModels",
        )
        assertEquals(expected, queryCallsFor(33))
        assertEquals(expected, queryCallsFor(34))
    }

    @Test
    fun `api 35 and 37 drop power models`() {
        val expected = listOf("setMaxStatsAgeMs(0)", "includeProcessStateData", "includeVirtualUids")
        assertEquals(expected, queryCallsFor(35))
        assertEquals(expected, queryCallsFor(37))
    }

    @Test
    fun `max age is passed through`() {
        val calls = FakeBatteryStatsCalls()
        client(calls).readUsage(maxAgeMs = 5_000L)
        assertEquals("setMaxStatsAgeMs(5000)", calls.builder.calls.first())
    }

    @Test
    fun `success carries totals and remaining times`() {
        val snapshot = client(FakeBatteryStatsCalls()).readUsage(0L)
        assertTrue(snapshot.available)
        assertNull(snapshot.reason)
        assertEquals(1_000L, snapshot.statsStartMs)
        assertEquals(2_000L, snapshot.statsEndMs)
        assertEquals(42.5, snapshot.dischargedPowerMah, 0.0)
        assertEquals(3_600_000L, snapshot.batteryTimeRemainingMs)
        assertEquals(-1L, snapshot.chargeTimeRemainingMs)
    }

    @Test
    fun `null binder maps to remote without calling the service`() {
        var factoryCalls = 0
        val snapshot = BatteryStatsClient({ null }, 35) {
            factoryCalls++
            FakeBatteryStatsCalls()
        }.readUsage(0L)
        assertUnavailable(Reasons.REMOTE, snapshot.available, snapshot.reason)
        assertEquals(0, factoryCalls)
    }

    @Test
    fun `empty stats list maps to remote`() {
        val snapshot = client(FakeBatteryStatsCalls(usage = null)).readUsage(0L)
        assertUnavailable(Reasons.REMOTE, snapshot.available, snapshot.reason)
    }

    @Test
    fun `each failure type maps to its reason`() {
        val cases = listOf(
            RemoteException() to Reasons.REMOTE,
            SecurityException("no BATTERY_STATS") to Reasons.PERMISSION,
            NoSuchMethodError("includeProcessStateData") to Reasons.NO_SUCH_METHOD,
            NoSuchFieldError("mData") to Reasons.NO_SUCH_METHOD,
            ClassNotFoundException("android.os.BatteryConsumer\$Key") to Reasons.NO_SUCH_METHOD,
            NoSuchMethodException("getKey") to Reasons.NO_SUCH_METHOD,
            IncompatibleClassChangeError("changed") to Reasons.NO_SUCH_METHOD,
            InvocationTargetException(SecurityException("denied")) to Reasons.PERMISSION,
            IllegalStateException("bad parcel") to Reasons.REMOTE,
        )
        for ((failure, reason) in cases) {
            val snapshot = client(FakeBatteryStatsCalls(failure = failure)).readUsage(0L)
            assertFalse("${failure::class.simpleName}", snapshot.available)
            assertEquals("${failure::class.simpleName}", reason, snapshot.reason)
        }
    }

    @Test
    fun `throwing binder source maps to its reason`() {
        val snapshot = BatteryStatsClient({ throw SecurityException("x") }, 35) { FakeBatteryStatsCalls() }
            .readUsage(0L)
        assertUnavailable(Reasons.PERMISSION, snapshot.available, snapshot.reason)
    }
}

internal fun assertUnavailable(expectedReason: String, available: Boolean, reason: String?) {
    assertFalse(available)
    assertEquals(expectedReason, reason)
}
