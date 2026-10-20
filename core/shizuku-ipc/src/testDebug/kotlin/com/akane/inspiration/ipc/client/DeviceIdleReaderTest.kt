package com.akane.inspiration.ipc.client

import android.os.IBinder
import android.os.RemoteException
import com.akane.inspiration.ipc.model.Reasons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceIdleReaderTest {
    private class FakePowerManager(
        private val deep: Boolean = true,
        private val light: Boolean = false,
        private val failure: Throwable? = null,
    ) : PowerManagerCalls {
        override fun isDeviceIdleMode(): Boolean {
            failure?.let { throw it }
            return deep
        }

        override fun isLightDeviceIdleMode(): Boolean = light
    }

    private fun reader(
        whitelist: DeviceIdleCalls = DeviceIdleCalls { arrayOf("com.android.phone", "com.example.app") },
        power: PowerManagerCalls = FakePowerManager(),
        idleBinder: () -> IBinder? = { fakeBinder },
        powerBinder: () -> IBinder? = { fakeBinder },
    ) = DeviceIdleReader(
        DeviceIdleClient(idleBinder) { whitelist },
        PowerManagerClient(powerBinder) { power },
    )

    @Test
    fun `combines idle flags and whitelist`() {
        val snapshot = reader().read()
        assertTrue(snapshot.available)
        assertNull(snapshot.reason)
        assertTrue(snapshot.deepIdle)
        assertFalse(snapshot.lightIdle)
        assertEquals(listOf("com.android.phone", "com.example.app"), snapshot.powerSaveWhitelist)
    }

    @Test
    fun `null whitelist array reads as empty`() {
        val snapshot = reader(whitelist = DeviceIdleCalls { null }).read()
        assertTrue(snapshot.available)
        assertTrue(snapshot.powerSaveWhitelist.isEmpty())
    }

    @Test
    fun `power manager failure makes the snapshot unavailable`() {
        val snapshot = reader(power = FakePowerManager(failure = SecurityException("x"))).read()
        assertUnavailable(Reasons.PERMISSION, snapshot.available, snapshot.reason)
    }

    @Test
    fun `whitelist failure makes the snapshot unavailable`() {
        val snapshot = reader(whitelist = DeviceIdleCalls { throw RemoteException() }).read()
        assertUnavailable(Reasons.REMOTE, snapshot.available, snapshot.reason)
    }

    @Test
    fun `null binders map to remote`() {
        val noIdle = reader(idleBinder = { null }).read()
        assertUnavailable(Reasons.REMOTE, noIdle.available, noIdle.reason)
        val noPower = reader(powerBinder = { null }).read()
        assertUnavailable(Reasons.REMOTE, noPower.available, noPower.reason)
    }

    @Test
    fun `client results expose the raw reads`() {
        assertEquals(
            BinderResult.Ok(IdleFlags(deepIdle = false, lightIdle = true)),
            PowerManagerClient({ fakeBinder }) { FakePowerManager(deep = false, light = true) }.readIdleFlags(),
        )
        assertEquals(
            BinderResult.Failed(Reasons.NO_SUCH_METHOD),
            DeviceIdleClient({ fakeBinder }) { DeviceIdleCalls { throw NoSuchMethodError("x") } }.readWhitelist(),
        )
    }
}
