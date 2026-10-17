package com.akane.inspiration.ipc

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Binder
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import rikka.shizuku.Shizuku

class ShizukuManagerImplTest {
    @Test
    fun start_reportsNotInstalledWhenManagerPackageIsAbsent() = runTest {
        val fixture = Fixture(this, applicationInfo = null)

        fixture.manager.start()

        assertSame(ShizukuState.NotInstalled, fixture.manager.state.value)
        assertEquals(1, fixture.facade.addedBinderReceivedListeners)
    }

    @Test
    fun start_reportsNotRunningWhenBinderIsUnavailable() = runTest {
        val fixture = Fixture(this).apply { facade.binderAlive = false }

        fixture.manager.start()

        assertSame(ShizukuState.NotRunning, fixture.manager.state.value)
        assertEquals(0, fixture.facade.bindCalls)
    }

    @Test
    fun start_reportsPermissionRequiredBeforePermissionRequest() = runTest {
        val fixture = Fixture(this).apply {
            facade.permissionResult = PackageManager.PERMISSION_DENIED
            facade.showPermissionRationale = false
        }

        fixture.manager.start()

        assertSame(ShizukuState.PermissionRequired, fixture.manager.state.value)
        fixture.manager.requestPermission()
        assertEquals(1, fixture.facade.permissionRequests)
    }

    @Test
    fun deniedPermissionResultUpdatesStateWithoutBinding() = runTest {
        val fixture = Fixture(this).apply {
            facade.permissionResult = PackageManager.PERMISSION_DENIED
        }
        fixture.manager.start()

        fixture.facade.deliverPermissionResult(PackageManager.PERMISSION_DENIED)

        assertSame(ShizukuState.PermissionDenied, fixture.manager.state.value)
        assertEquals(0, fixture.facade.bindCalls)
    }

    @Test
    fun grantedPermissionReportsReadyWithoutBinding() = runTest {
        val fixture = Fixture(this).apply {
            facade.permissionResult = PackageManager.PERMISSION_DENIED
        }
        fixture.manager.start()

        fixture.facade.permissionResult = PackageManager.PERMISSION_GRANTED
        fixture.facade.deliverPermissionResult(PackageManager.PERMISSION_GRANTED)

        assertEquals(ShizukuState.Ready(2000, 13), fixture.manager.state.value)
        assertEquals(0, fixture.facade.bindCalls)
    }

    @Test
    fun withTelemetryServiceUnbindsWhenLastUserCompletes() = runTest {
        val fixture = Fixture(this)
        fixture.manager.start()

        val result = async { fixture.manager.withTelemetryService(1_000L) { "complete" } }
        runCurrent()
        assertEquals(1, fixture.facade.bindCalls)
        fixture.facade.deliverServiceConnected()

        assertEquals("complete", result.await())
        assertEquals(1, fixture.facade.unbindCalls)
        assertTrue(fixture.facade.lastUnbindRemove)
    }

    @Test
    fun bindingHolderKeepsServiceBoundUntilClosed() = runTest {
        val fixture = Fixture(this)
        fixture.manager.start()

        val holder = fixture.manager.holdBinding()
        assertEquals(1, fixture.facade.bindCalls)
        fixture.facade.deliverServiceConnected()

        assertEquals(Unit, fixture.manager.withTelemetryService(1_000L) { Unit })
        assertEquals(0, fixture.facade.unbindCalls)

        holder.close()
        holder.close()
        assertEquals(1, fixture.facade.unbindCalls)
        assertTrue(fixture.facade.lastUnbindRemove)
    }

    @Test
    fun withTelemetryServiceReturnsNullAfterTimeout() = runTest {
        val fixture = Fixture(this)
        fixture.manager.start()

        assertNull(fixture.manager.withTelemetryService(1L) { Unit })
        assertEquals(1, fixture.facade.bindCalls)
        assertEquals(1, fixture.facade.unbindCalls)
    }

    @Test
    fun withTelemetryServiceReturnsNullOnBinderDeathDuringBlock() = runTest {
        val fixture = Fixture(this)
        fixture.manager.start()

        val result = async {
            fixture.manager.withTelemetryService(1_000L) {
                fixture.facade.deliverBinderDeath()
                Unit
            }
        }
        runCurrent()
        fixture.facade.deliverServiceConnected()

        assertNull(result.await())
        assertEquals(0, fixture.facade.unbindCalls)
    }

    @Test
    fun concurrentTelemetryServiceUsersShareOneBind() = runTest {
        val fixture = Fixture(this)
        fixture.manager.start()

        val first = async { fixture.manager.withTelemetryService(1_000L) { "first" } }
        val second = async { fixture.manager.withTelemetryService(1_000L) { "second" } }
        runCurrent()
        assertEquals(1, fixture.facade.bindCalls)
        fixture.facade.deliverServiceConnected()

        assertEquals("first", first.await())
        assertEquals("second", second.await())
        assertEquals(1, fixture.facade.unbindCalls)
    }

    @Test
    fun binderDeathReportsNotRunningAndClearsTelemetryService() = runTest {
        val fixture = Fixture(this)
        fixture.manager.start()

        fixture.facade.deliverBinderDeath()

        assertSame(ShizukuState.NotRunning, fixture.manager.state.value)
        assertNull(fixture.manager.telemetryService.value)
    }

    @Test
    fun preV11ShizukuIsNotRunning() = runTest {
        val fixture = Fixture(this).apply { facade.preV11 = true }

        fixture.manager.start()

        assertSame(ShizukuState.NotRunning, fixture.manager.state.value)
        assertEquals(0, fixture.facade.bindCalls)
    }

    @Test
    fun startTwiceRegistersListenersOnce() = runTest {
        val fixture = Fixture(this)

        fixture.manager.start()
        fixture.manager.start()

        assertEquals(1, fixture.facade.addedBinderReceivedListeners)
        assertEquals(1, fixture.facade.addedBinderDeadListeners)
        assertEquals(1, fixture.facade.addedPermissionListeners)
    }

    @Test
    fun stopUnregistersListenersAndUnbindsHeldService() = runTest {
        val fixture = Fixture(this)
        fixture.manager.start()
        val holder = fixture.manager.holdBinding()

        fixture.manager.stop()
        holder.close()

        assertEquals(1, fixture.facade.removedBinderReceivedListeners)
        assertEquals(1, fixture.facade.removedBinderDeadListeners)
        assertEquals(1, fixture.facade.removedPermissionListeners)
        assertEquals(1, fixture.facade.unbindCalls)
        assertTrue(fixture.facade.lastUnbindRemove)
    }

    private class Fixture(
        scope: TestScope,
        applicationInfo: ShizukuApplicationInfo? = ShizukuApplicationInfo(42, true),
    ) {
        val facade = FakeShizukuFacade()
        val manager = ShizukuManagerImpl(
            context = TestContext(),
            facade = facade,
            scope = scope,
            applicationInfoProvider = ShizukuApplicationInfoProvider { applicationInfo },
        )
    }

    private class TestContext : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this

        override fun getPackageName(): String = "com.akane.inspiration"
    }

    private class FakeShizukuFacade : ShizukuFacade {
        var binderAlive = true
        var preV11 = false
        var permissionResult = PackageManager.PERMISSION_GRANTED
        var showPermissionRationale = false
        var addedBinderReceivedListeners = 0
        var addedBinderDeadListeners = 0
        var addedPermissionListeners = 0
        var removedBinderReceivedListeners = 0
        var removedBinderDeadListeners = 0
        var removedPermissionListeners = 0
        var permissionRequests = 0
        var bindCalls = 0
        var unbindCalls = 0
        var lastUnbindRemove = false
        private var binderReceivedListener: Shizuku.OnBinderReceivedListener? = null
        private var binderDeadListener: Shizuku.OnBinderDeadListener? = null
        private var permissionListener: Shizuku.OnRequestPermissionResultListener? = null
        private var serviceConnection: ServiceConnection? = null

        override fun pingBinder(): Boolean = binderAlive

        override fun getUid(): Int = 2000

        override fun getVersion(): Int = 13

        override fun isPreV11(): Boolean = preV11

        override fun checkSelfPermission(): Int = permissionResult

        override fun shouldShowRequestPermissionRationale(): Boolean = showPermissionRationale

        override fun requestPermission(requestCode: Int) {
            permissionRequests += 1
        }

        override fun addBinderReceivedListenerSticky(listener: Shizuku.OnBinderReceivedListener) {
            addedBinderReceivedListeners += 1
            binderReceivedListener = listener
        }

        override fun removeBinderReceivedListener(listener: Shizuku.OnBinderReceivedListener) {
            removedBinderReceivedListeners += 1
            if (binderReceivedListener === listener) {
                binderReceivedListener = null
            }
        }

        override fun addBinderDeadListener(listener: Shizuku.OnBinderDeadListener) {
            addedBinderDeadListeners += 1
            binderDeadListener = listener
        }

        override fun removeBinderDeadListener(listener: Shizuku.OnBinderDeadListener) {
            removedBinderDeadListeners += 1
            if (binderDeadListener === listener) {
                binderDeadListener = null
            }
        }

        override fun addRequestPermissionResultListener(listener: Shizuku.OnRequestPermissionResultListener) {
            addedPermissionListeners += 1
            permissionListener = listener
        }

        override fun removeRequestPermissionResultListener(listener: Shizuku.OnRequestPermissionResultListener) {
            removedPermissionListeners += 1
            if (permissionListener === listener) {
                permissionListener = null
            }
        }

        override fun bindUserService(args: Shizuku.UserServiceArgs, connection: ServiceConnection) {
            bindCalls += 1
            serviceConnection = connection
        }

        override fun unbindUserService(
            args: Shizuku.UserServiceArgs,
            connection: ServiceConnection,
            remove: Boolean,
        ) {
            unbindCalls += 1
            lastUnbindRemove = remove
        }

        fun deliverPermissionResult(result: Int) {
            permissionListener?.onRequestPermissionResult(1, result)
        }

        fun deliverBinderDeath() {
            binderDeadListener?.onBinderDead()
        }

        fun deliverServiceConnected() {
            serviceConnection?.onServiceConnected(ComponentName("test", "service"), Binder())
        }
    }
}
