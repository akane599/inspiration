package com.akane.inspiration.ipc

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Binder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import rikka.shizuku.Shizuku

/**
 * withTelemetryService in a cold process. Shizuku pushes its binder asynchronously (the server calls the
 * provider some time after Application.onCreate), so start() first reports NotRunning. The charge job and the
 * app-open refresh call withTelemetryService right away: they must wait for Ready inside their own timeout
 * instead of failing at once. States that cannot turn into Ready by themselves still fail at once.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ShizukuManagerReadyWaitTest {
    @Test
    fun aCallMadeBeforeTheColdStartBinderPushWaitsForItWithinItsTimeout() =
        runTest {
            val facade = LateBinderFacade()
            val manager = manager(facade)
            manager.start()
            assertSame("cold process: no binder yet", ShizukuState.NotRunning, manager.state.value)

            val call = async { manager.withTelemetryService(2_000L) { "read" } }
            runCurrent()
            // The Shizuku server's binder push lands 200 ms later, well inside the call's timeout.
            advanceTimeBy(200L)
            facade.pushBinder()
            advanceUntilIdle()

            assertEquals("read", call.await())
            assertEquals(1, facade.bindCalls)
            manager.stop()
        }

    @Test
    fun aCallWhileShizukuStaysNotRunningGivesUpAtItsTimeoutWithoutBinding() =
        runTest {
            val facade = LateBinderFacade()
            val manager = manager(facade)
            manager.start()

            assertNull(manager.withTelemetryService(2_000L) { "read" })

            assertEquals("the call used its whole timeout", 2_000L, currentTime)
            assertEquals(0, facade.bindCalls)
            manager.stop()
        }

    @Test
    fun aBinderPushThatEndsInAPermissionPromptEndsTheWaitAtOnce() =
        runTest {
            val facade = LateBinderFacade(permission = PackageManager.PERMISSION_DENIED)
            val manager = manager(facade)
            manager.start()

            val call = async { manager.withTelemetryService(2_000L) { "read" } }
            runCurrent()
            advanceTimeBy(200L)
            facade.pushBinder()
            runCurrent()

            assertSame(ShizukuState.PermissionRequired, manager.state.value)
            assertNull(call.await())
            assertEquals("the wait ended at the push, not at the timeout", 200L, currentTime)
            assertEquals(0, facade.bindCalls)
            manager.stop()
        }

    @Test
    fun statesThatCannotBecomeReadyByThemselvesFailAtOnce() =
        runTest {
            val terminal =
                listOf(
                    // No binder and no manager app: nothing will push one (a Sui binder would already be here).
                    ShizukuState.NotInstalled to LateBinderFacade(binderAlive = false),
                    ShizukuState.PermissionRequired to
                        LateBinderFacade(binderAlive = true, permission = PackageManager.PERMISSION_DENIED),
                    ShizukuState.PermissionDenied to
                        LateBinderFacade(binderAlive = true, permission = PackageManager.PERMISSION_DENIED, rationale = true),
                )
            terminal.forEach { (expected, facade) ->
                val manager = manager(facade, managerInstalled = expected != ShizukuState.NotInstalled)
                manager.start()
                assertSame(expected, manager.state.value)

                assertNull(manager.withTelemetryService(2_000L) { "read" })

                assertEquals("$expected must not wait", 0L, currentTime)
                assertEquals(0, facade.bindCalls)
                manager.stop()
            }
        }

    @Test
    fun aCallBeforeStartFailsAtOnce() =
        runTest {
            val facade = LateBinderFacade(binderAlive = true)
            val manager = manager(facade)

            assertNull(manager.withTelemetryService(2_000L) { "read" })

            assertEquals(0L, currentTime)
            assertEquals(0, facade.bindCalls)
        }

    private fun TestScope.manager(
        facade: LateBinderFacade,
        managerInstalled: Boolean = true,
    ) = ShizukuManagerImpl(
        context = TestContext(),
        facade = facade,
        scope = this,
        applicationInfoProvider = ShizukuApplicationInfoProvider { ShizukuApplicationInfo(42, true) },
        managerApp = ShizukuManagerAppProbe { managerInstalled },
        ioDispatcher = StandardTestDispatcher(testScheduler),
    )

    private class TestContext : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this

        override fun getPackageName(): String = "com.akane.inspiration"
    }

    /** Shizuku whose binder has not been received yet (unless [binderAlive]); a bind connects at once. */
    private class LateBinderFacade(
        @Volatile var binderAlive: Boolean = false,
        private val permission: Int = PackageManager.PERMISSION_GRANTED,
        private val rationale: Boolean = false,
    ) : ShizukuFacade {
        var bindCalls = 0
        private var binderReceivedListener: Shizuku.OnBinderReceivedListener? = null

        /** The Shizuku server's asynchronous binder push (ShizukuProvider "sendBinder"). */
        fun pushBinder() {
            binderAlive = true
            binderReceivedListener?.onBinderReceived()
        }

        override fun pingBinder(): Boolean = binderAlive

        override fun getUid(): Int = 2000

        override fun getVersion(): Int = 13

        override fun isPreV11(): Boolean = false

        override fun checkSelfPermission(): Int = permission

        override fun shouldShowRequestPermissionRationale(): Boolean = rationale

        override fun requestPermission(requestCode: Int) = Unit

        override fun addBinderReceivedListenerSticky(listener: Shizuku.OnBinderReceivedListener) {
            binderReceivedListener = listener
            if (binderAlive) listener.onBinderReceived()
        }

        override fun removeBinderReceivedListener(listener: Shizuku.OnBinderReceivedListener) {
            if (binderReceivedListener === listener) binderReceivedListener = null
        }

        override fun addBinderDeadListener(listener: Shizuku.OnBinderDeadListener) = Unit

        override fun removeBinderDeadListener(listener: Shizuku.OnBinderDeadListener) = Unit

        override fun addRequestPermissionResultListener(listener: Shizuku.OnRequestPermissionResultListener) = Unit

        override fun removeRequestPermissionResultListener(listener: Shizuku.OnRequestPermissionResultListener) = Unit

        override fun bindUserService(
            args: Shizuku.UserServiceArgs,
            connection: ServiceConnection,
        ) {
            bindCalls += 1
            connection.onServiceConnected(ComponentName("test", "service"), Binder())
        }

        override fun unbindUserService(
            args: Shizuku.UserServiceArgs,
            connection: ServiceConnection,
            remove: Boolean,
        ) = Unit
    }
}
