package com.akane.inspiration.ipc

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Binder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors

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
        advanceTimeBy(LINGER_MS + 1)
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
        advanceTimeBy(LINGER_MS + 1)
        assertEquals(1, fixture.facade.unbindCalls)
        assertTrue(fixture.facade.lastUnbindRemove)
    }

    @Test
    fun withTelemetryServiceReturnsNullAfterTimeout() = runTest {
        val fixture = Fixture(this)
        fixture.manager.start()

        assertNull(fixture.manager.withTelemetryService(1L) { Unit })
        advanceTimeBy(LINGER_MS + 1)
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
        advanceTimeBy(LINGER_MS + 1)
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

    // SQ-114: holder-to-holder handover (Cockpit/Health -> Toolkit) and back-to-back single calls (Toolkit app
    // sheet). Shizuku reuses one connection set per service class, and unbind(remove=true) leaves it in place,
    // so the old process's death reaches the new binding's connection and drops the new process's connect.

    @Test
    fun handoverAfterUnbindSurvivesTheOldServicesLateDeath() = runTest {
        val fixture = Fixture(this)
        fixture.manager.start()
        val first = fixture.manager.holdBinding()
        fixture.facade.deliverServiceConnected()
        first.close()
        advanceTimeBy(LINGER_MS + 1)
        assertEquals(1, fixture.facade.unbindCalls)

        val second = fixture.manager.holdBinding()
        fixture.facade.deliverServiceDied() // the unbound (old) process exits after the new acquire
        runCurrent()
        fixture.facade.deliverServiceConnected() // the new process

        assertNotNull("the new binding must receive its service", fixture.manager.telemetryService.value)
        assertEquals(2, fixture.facade.bindCalls)
        assertEquals("ok", fixture.manager.withTelemetryService(1_000L) { "ok" })
        assertEquals("no duplicate bindUserService for the same args", 2, fixture.facade.bindCalls)

        second.close()
        advanceUntilIdle()
        assertEquals("the rebound service is unbound when its last holder goes (H13)", 2, fixture.facade.unbindCalls)
    }

    @Test
    fun noSecondBindIsIssuedWhileTheUnboundServiceIsStillDying() = runTest {
        val fixture = Fixture(this)
        fixture.manager.start()
        fixture.manager.holdBinding().apply { fixture.facade.deliverServiceConnected() }.close()
        advanceTimeBy(LINGER_MS + 1)

        val holder = fixture.manager.holdBinding()
        val result = async { fixture.manager.withTelemetryService(5_000L) { "answered" } }
        runCurrent()
        assertEquals("the bind waits for the old process's death", 1, fixture.facade.bindCalls)

        fixture.facade.deliverServiceDied()
        runCurrent()
        assertEquals(2, fixture.facade.bindCalls)
        fixture.facade.deliverServiceConnected()

        assertEquals("answered", result.await())
        assertEquals(2, fixture.facade.bindCalls)
        holder.close()
    }

    @Test
    fun aDeferredBindStillHappensWhenTheOldDeathNeverArrives() = runTest {
        val fixture = Fixture(this)
        fixture.manager.start()
        fixture.manager.holdBinding().apply { fixture.facade.deliverServiceConnected() }.close()
        advanceTimeBy(LINGER_MS + 1)

        val holder = fixture.manager.holdBinding()
        runCurrent()
        assertEquals(1, fixture.facade.bindCalls)
        advanceTimeBy(SETTLE_TIMEOUT_MS + 1)

        assertEquals(2, fixture.facade.bindCalls)
        holder.close()
    }

    @Test
    fun unbindClearsTheDeadBinderForCallers() = runTest {
        val fixture = Fixture(this)
        fixture.manager.start()
        val holder = fixture.manager.holdBinding()
        fixture.facade.deliverServiceConnected()
        assertNotNull(fixture.manager.telemetryService.value)

        holder.close()
        advanceTimeBy(LINGER_MS + 1)

        assertEquals(1, fixture.facade.unbindCalls)
        assertNull("callers must not grab the binder of a service being destroyed", fixture.manager.telemetryService.value)
    }

    @Test
    fun aLateConnectFromTheUnboundServiceIsIgnored() = runTest {
        val fixture = Fixture(this)
        fixture.manager.start()
        fixture.manager.holdBinding().close() // unbound before the service ever connected
        advanceTimeBy(LINGER_MS + 1)
        assertEquals(1, fixture.facade.unbindCalls)

        fixture.facade.deliverServiceConnected()

        assertNull(fixture.manager.telemetryService.value)
    }

    @Test
    fun reacquiringWithinTheLingerKeepsTheServiceWithoutKillingIt() = runTest {
        val fixture = Fixture(this)
        fixture.manager.start()
        val first = fixture.manager.holdBinding()
        fixture.facade.deliverServiceConnected()

        first.close()
        advanceTimeBy(LINGER_MS / 2)
        val second = fixture.manager.holdBinding()
        advanceTimeBy(LINGER_MS * 2)

        assertEquals(0, fixture.facade.unbindCalls)
        assertEquals(1, fixture.facade.bindCalls)
        assertNotNull(fixture.manager.telemetryService.value)
        second.close()
    }

    @Test
    fun theIdleLingerIsShortAndBounded() = runTest {
        val fixture = Fixture(this)
        fixture.manager.start()
        val result = async { fixture.manager.withTelemetryService(1_000L) { Unit } }
        runCurrent()
        fixture.facade.deliverServiceConnected()
        result.await()

        advanceTimeBy(LINGER_MS - 1)
        assertEquals("the service lingers briefly for the next caller", 0, fixture.facade.unbindCalls)
        advanceTimeBy(2)
        assertEquals(1, fixture.facade.unbindCalls)
        assertTrue(fixture.facade.lastUnbindRemove)
    }

    @Test
    fun withTelemetryServiceRunsBlockOffTheCallersMainThread() {
        val mainThread = Executors.newSingleThreadExecutor { Thread(it, "main-sim") }
        try {
            val main = mainThread.asCoroutineDispatcher()
            val facade = FakeShizukuFacade()
            val manager = ShizukuManagerImpl(
                context = TestContext(),
                facade = facade,
                scope = CoroutineScope(main),
                applicationInfoProvider = ShizukuApplicationInfoProvider { ShizukuApplicationInfo(42, true) },
            )
            manager.start()
            val holder = manager.holdBinding()
            facade.deliverServiceConnected()

            val threads = runBlocking(main) {
                val caller = Thread.currentThread().name.substringBefore(" @")
                caller to manager.withTelemetryService(5_000L) { Thread.currentThread().name.substringBefore(" @") }
            }

            holder.close()
            assertEquals("main-sim", threads.first)
            assertNotNull(threads.second)
            assertNotEquals("the service block must not run on the caller's (main) thread", "main-sim", threads.second)
        } finally {
            mainThread.shutdownNow()
        }
    }

    private companion object {
        /** Mirrors the manager's idle linger and unbind-settle timeout (literals, so the test reads standalone). */
        const val LINGER_MS = 3_000L
        const val SETTLE_TIMEOUT_MS = 2_000L
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
            ioDispatcher = StandardTestDispatcher(scope.testScheduler),
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

        /**
         * Mirrors rikka.shizuku.ShizukuServiceConnection (api 13.1.5): one connection set per user-service
         * class, reused across binds. unbindUserService(remove=true) does NOT clear it; the old process's
         * died() notifies every connection in it, then clears the set (a later connected() reaches nobody).
         */
        private val connections = linkedSetOf<ServiceConnection>()

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
            connections += connection
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
            connections.toList().forEach { it.onServiceConnected(ComponentName("test", "service"), Binder()) }
        }

        /** The bound process exited (e.g. after unbind remove=true): Shizuku's died() path. */
        fun deliverServiceDied() {
            val notified = connections.toList()
            connections.clear()
            notified.forEach { it.onServiceDisconnected(ComponentName("test", "service")) }
        }
    }
}
