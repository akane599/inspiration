package com.akane.inspiration.ipc

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Binder
import android.os.DeadObjectException
import android.os.IBinder
import android.os.RemoteException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.MainCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import rikka.shizuku.Shizuku
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

@OptIn(ExperimentalCoroutinesApi::class)
class ShizukuManagerImplTest {
    @Before
    fun setUpMainDispatcher() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun failedHoldBindDoesNotThrowOrLeakOwnershipAcrossReconnect() =
        runTest {
            val fixture = Fixture(this)
            fixture.manager.start()
            fixture.facade.bindFailure = true
            val holder = fixture.manager.holdBinding()
            assertSame(ShizukuState.NotRunning, fixture.manager.state.value)
            holder.close()
            fixture.facade.bindFailure = false
            fixture.facade.deliverPermissionResult(PackageManager.PERMISSION_GRANTED)
            assertEquals(1, fixture.facade.bindCalls)
            val next = fixture.manager.holdBinding()
            assertEquals(2, fixture.facade.bindCalls)
            next.close()
            advanceUntilIdle()
            assertEquals(1, fixture.facade.unbindCalls)
        }

    @Test
    fun failedServiceAcquisitionReleasesUserBeforeReconnect() =
        runTest {
            val fixture = Fixture(this)
            fixture.manager.start()
            fixture.facade.bindFailure = true
            assertNull(fixture.manager.withTelemetryService(1) { Unit })
            fixture.facade.bindFailure = false
            fixture.facade.deliverPermissionResult(PackageManager.PERMISSION_GRANTED)
            assertEquals(1, fixture.facade.bindCalls)
            advanceUntilIdle()
            assertEquals(0, fixture.facade.unbindCalls)
        }

    @Test
    fun failedIdleUnbindClearsTransportAndAllowsAnotherBind() =
        runTest {
            val fixture = Fixture(this)
            fixture.manager.start()
            val holder = fixture.manager.holdBinding()
            fixture.facade.deliverServiceConnected()
            fixture.facade.unbindFailure = true
            holder.close()
            advanceUntilIdle()
            assertSame(ShizukuState.NotRunning, fixture.manager.state.value)
            assertNull(fixture.manager.telemetryService.value)
            fixture.facade.unbindFailure = false
            fixture.facade.deliverPermissionResult(PackageManager.PERMISSION_GRANTED)
            val next = fixture.manager.holdBinding()
            assertEquals(2, fixture.facade.bindCalls)
            next.close()
            advanceUntilIdle()
        }

    @Test
    fun start_reportsNotInstalledWhenManagerPackageIsAbsent() =
        runTest {
            val fixture = Fixture(this, applicationInfo = null)

            fixture.manager.start()

            assertSame(ShizukuState.NotInstalled, fixture.manager.state.value)
            assertEquals(1, fixture.facade.addedBinderReceivedListeners)
        }

    @Test
    fun start_reportsNotRunningWhenBinderIsUnavailable() =
        runTest {
            val fixture = Fixture(this).apply { facade.binderAlive = false }

            fixture.manager.start()

            assertSame(ShizukuState.NotRunning, fixture.manager.state.value)
            assertEquals(0, fixture.facade.bindCalls)
        }

    @Test
    fun start_reportsPermissionRequiredBeforePermissionRequest() =
        runTest {
            val fixture =
                Fixture(this).apply {
                    facade.permissionResult = PackageManager.PERMISSION_DENIED
                    facade.showPermissionRationale = false
                }

            fixture.manager.start()

            assertSame(ShizukuState.PermissionRequired, fixture.manager.state.value)
            fixture.manager.requestPermission()
            assertEquals(1, fixture.facade.permissionRequests)
        }

    @Test
    fun deniedPermissionResultUpdatesStateWithoutBinding() =
        runTest {
            val fixture =
                Fixture(this).apply {
                    facade.permissionResult = PackageManager.PERMISSION_DENIED
                }
            fixture.manager.start()

            // A deny that Shizuku stored is reported through the rationale flag, not by the result itself.
            fixture.facade.showPermissionRationale = true
            fixture.facade.deliverPermissionResult(PackageManager.PERMISSION_DENIED)

            assertSame(ShizukuState.PermissionDenied, fixture.manager.state.value)
            assertEquals(0, fixture.facade.bindCalls)
        }

    @Test
    fun aOneTimeDenyThenAGrantInTheShizukuAppRecoversWithoutARestart() =
        runTest {
            val fixture =
                Fixture(this).apply {
                    facade.permissionResult = PackageManager.PERMISSION_DENIED
                }
            fixture.manager.start()
            fixture.manager.requestPermission()

            // Shizuku did not store this deny (onetime=true), so it reports no rationale and may ask again.
            fixture.facade.deliverPermissionResult(PackageManager.PERMISSION_DENIED)
            assertSame(ShizukuState.PermissionRequired, fixture.manager.state.value)

            fixture.manager.requestPermission()
            assertEquals("the second Grant tap shows the dialog again", 2, fixture.facade.permissionRequests)

            // The user allows Inspiration in the Shizuku app instead: Shizuku sends this client no callback.
            fixture.facade.permissionResult = PackageManager.PERMISSION_GRANTED
            fixture.manager.refresh() // MainActivity.onStart on the way back

            assertEquals(ShizukuState.Ready(2000, 13), fixture.manager.state.value)
            assertEquals(0, fixture.facade.bindCalls)
        }

    @Test
    fun grantTappedAfterAStoredDenyReReadsTheStateAndPicksUpAGrantMadeInShizuku() =
        runTest {
            val fixture =
                Fixture(this).apply {
                    facade.permissionResult = PackageManager.PERMISSION_DENIED
                    facade.showPermissionRationale = true
                }
            fixture.manager.start()
            assertSame(ShizukuState.PermissionDenied, fixture.manager.state.value)

            // Shizuku answers a stored deny without a dialog, so it is not asked while the deny stands.
            fixture.manager.requestPermission()
            assertSame(ShizukuState.PermissionDenied, fixture.manager.state.value)
            assertEquals(0, fixture.facade.permissionRequests)

            // Allowed in the Shizuku app, then Grant tapped back in Inspiration.
            fixture.facade.permissionResult = PackageManager.PERMISSION_GRANTED
            fixture.facade.showPermissionRationale = false
            fixture.manager.requestPermission()

            assertEquals(ShizukuState.Ready(2000, 13), fixture.manager.state.value)
            assertEquals("an access that is already granted needs no dialog", 0, fixture.facade.permissionRequests)
        }

    @Test
    fun refreshDoesNothingBeforeStartOrAfterStop() =
        runTest {
            val fixture = Fixture(this)

            fixture.manager.refresh()
            assertSame(ShizukuState.NotRunning, fixture.manager.state.value)

            fixture.manager.start()
            fixture.manager.stop()
            fixture.facade.permissionResult = PackageManager.PERMISSION_DENIED
            fixture.manager.refresh()

            assertEquals(ShizukuState.Ready(2000, 13), fixture.manager.state.value)
        }

    @Test
    fun refreshWhileReadyLeavesTheBoundServiceAlone() =
        runTest {
            val fixture = Fixture(this)
            fixture.manager.start()
            val holder = fixture.manager.holdBinding()
            fixture.facade.deliverServiceConnected()

            fixture.manager.refresh()

            assertEquals(ShizukuState.Ready(2000, 13), fixture.manager.state.value)
            assertNotNull(fixture.manager.telemetryService.value)
            assertEquals(1, fixture.facade.bindCalls)
            assertEquals(0, fixture.facade.unbindCalls)
            holder.close()
            advanceUntilIdle()
        }

    @Test
    fun grantedPermissionReportsReadyWithoutBinding() =
        runTest {
            val fixture =
                Fixture(this).apply {
                    facade.permissionResult = PackageManager.PERMISSION_DENIED
                }
            fixture.manager.start()

            fixture.facade.permissionResult = PackageManager.PERMISSION_GRANTED
            fixture.facade.deliverPermissionResult(PackageManager.PERMISSION_GRANTED)

            assertEquals(ShizukuState.Ready(2000, 13), fixture.manager.state.value)
            assertEquals(0, fixture.facade.bindCalls)
        }

    @Test
    fun withTelemetryServiceUnbindsWhenLastUserCompletes() =
        runTest {
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
    fun bindingHolderKeepsServiceBoundUntilClosed() =
        runTest {
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
    fun withTelemetryServiceReturnsNullAfterTimeout() =
        runTest {
            val fixture = Fixture(this)
            fixture.manager.start()

            assertNull(fixture.manager.withTelemetryService(1L) { Unit })
            advanceTimeBy(LINGER_MS + 1)
            assertEquals(1, fixture.facade.bindCalls)
            assertEquals(1, fixture.facade.unbindCalls)
        }

    @Test
    fun withTelemetryServiceReturnsNullOnBinderDeathDuringBlock() =
        runTest {
            val fixture = Fixture(this)
            fixture.manager.start()

            val result =
                async {
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
    fun concurrentTelemetryServiceUsersShareOneBind() =
        runTest {
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
    fun binderDeathReportsNotRunningAndClearsTelemetryService() =
        runTest {
            val fixture = Fixture(this)
            fixture.manager.start()

            fixture.facade.deliverBinderDeath()

            assertSame(ShizukuState.NotRunning, fixture.manager.state.value)
            assertNull(fixture.manager.telemetryService.value)
        }

    @Test
    fun preV11ShizukuIsNotRunning() =
        runTest {
            val fixture = Fixture(this).apply { facade.preV11 = true }

            fixture.manager.start()

            assertSame(ShizukuState.NotRunning, fixture.manager.state.value)
            assertEquals(0, fixture.facade.bindCalls)
        }

    @Test
    fun aServerOlderThanV13IsUnsupportedAndNeverReady() =
        runTest {
            // A v11/v12 server builds TelemetryUserService through its no-arg constructor, which rejects every call.
            val fixture = Fixture(this).apply { facade.serverVersion = 12 }

            fixture.manager.start()

            assertEquals(ShizukuState.UnsupportedServer(12), fixture.manager.state.value)
            assertNull(fixture.manager.withTelemetryService(1) { Unit })
            assertEquals(0, fixture.facade.bindCalls)
        }

    @Test
    fun anUnsupportedServerIsNeverAskedForPermission() =
        runTest {
            val fixture =
                Fixture(this).apply {
                    facade.serverVersion = 12
                    facade.permissionResult = PackageManager.PERMISSION_DENIED
                }
            fixture.manager.start()

            fixture.manager.requestPermission()

            assertEquals(ShizukuState.UnsupportedServer(12), fixture.manager.state.value)
            assertEquals("granting cannot help an old server", 0, fixture.facade.permissionRequests)
        }

    @Test
    fun aServerVersionUnknownBeforeTheGrantIsJudgedOnceAccessIsGranted() =
        runTest {
            val fixture =
                Fixture(this).apply {
                    facade.serverVersion = -1 // not sent on attach, and not readable without access
                    facade.permissionResult = PackageManager.PERMISSION_DENIED
                }
            fixture.manager.start()
            assertSame(ShizukuState.PermissionRequired, fixture.manager.state.value)

            fixture.facade.serverVersion = 12
            fixture.facade.permissionResult = PackageManager.PERMISSION_GRANTED
            fixture.facade.deliverPermissionResult(PackageManager.PERMISSION_GRANTED)

            assertEquals(ShizukuState.UnsupportedServer(12), fixture.manager.state.value)
        }

    @Test
    fun startTwiceRegistersListenersOnce() =
        runTest {
            val fixture = Fixture(this)

            fixture.manager.start()
            fixture.manager.start()

            assertEquals(1, fixture.facade.addedBinderReceivedListeners)
            assertEquals(1, fixture.facade.addedBinderDeadListeners)
            assertEquals(1, fixture.facade.addedPermissionListeners)
        }

    @Test
    fun stopUnregistersListenersAndUnbindsHeldService() =
        runTest {
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
    fun handoverAfterUnbindSurvivesTheOldServicesLateDeath() =
        runTest {
            val fixture = Fixture(this)
            fixture.manager.start()
            val first = fixture.manager.holdBinding()
            fixture.facade.deliverServiceConnected()
            first.close()
            advanceTimeBy(LINGER_MS + 1)
            assertEquals(1, fixture.facade.unbindCalls)

            val second = fixture.manager.holdBinding()
            fixture.facade.deliverServiceDied {
                // The callback ran, but Shizuku still caches the dying wrapper and its registrations.
                fixture.manager.refresh()
                fixture.manager.holdBinding().close()
                assertEquals("all acquisitions stay deferred until callback cleanup", 1, fixture.facade.bindCalls)
            }
            assertEquals("settling must be posted, not run inside the death callback", 1, fixture.facade.bindCalls)
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
    fun realShizukuRegistryKeepsTheReplacementConnectionAfterDeathCallbackCleanup() =
        runTest {
            val main = CallbackMainDispatcher(StandardTestDispatcher(testScheduler))
            Dispatchers.setMain(main)
            LibraryConnectionFacade().use { facade ->
                val manager =
                    ShizukuManagerImpl(
                        context = TestContext(),
                        facade = facade,
                        scope = this,
                        applicationInfoProvider = ShizukuApplicationInfoProvider { ShizukuApplicationInfo(42, true) },
                        ioDispatcher = StandardTestDispatcher(testScheduler),
                    )
                manager.start()
                val first = manager.holdBinding()
                val oldWrapper = facade.boundWrappers.single()
                facade.deliverConnected(oldWrapper, LiveBinder())
                first.close()
                advanceTimeBy(LINGER_MS + 1)
                assertEquals(1, facade.unbindCalls)

                val second = manager.holdBinding()
                main.runCallback { facade.deliverDeathCallback(oldWrapper) }
                assertEquals("no bind inside the real library callback", 1, facade.boundWrappers.size)
                runCurrent()

                val replacementWrapper = facade.boundWrappers.last()
                assertEquals(2, facade.boundWrappers.size)
                assertNotSame("cleanup must finish before choosing a fresh cached wrapper", oldWrapper, replacementWrapper)
                val replacementBinder = LiveBinder()
                facade.deliverConnected(replacementWrapper, replacementBinder)
                assertSame(replacementBinder, manager.telemetryService.value?.asBinder())
                assertEquals("ok", manager.withTelemetryService(1_000L) { "ok" })
                assertEquals("the registered replacement still receives its own death", 2, facade.boundWrappers.size)
                facade.deliverDeathCallback(replacementWrapper)
                assertNull(manager.telemetryService.value)
                second.close()
                manager.stop()
                advanceUntilIdle()
            }
        }

    @Test
    fun stopBeforeThePostedDeathSettleDoesNotRebind() =
        runTest {
            val fixture = Fixture(this)
            fixture.manager.start()
            fixture.manager
                .holdBinding()
                .apply { fixture.facade.deliverServiceConnected() }
                .close()
            advanceTimeBy(LINGER_MS + 1)
            val holder = fixture.manager.holdBinding()

            fixture.facade.deliverServiceDied()
            fixture.manager.stop()
            advanceUntilIdle()

            assertEquals("stop invalidates the pending acquisition", 1, fixture.facade.bindCalls)
            assertNull(fixture.manager.telemetryService.value)
            holder.close()
        }

    @Test
    fun daemonDeathCancelsThePostedDeathSettle() =
        runTest {
            val fixture = Fixture(this)
            fixture.manager.start()
            fixture.manager
                .holdBinding()
                .apply { fixture.facade.deliverServiceConnected() }
                .close()
            advanceTimeBy(LINGER_MS + 1)
            val holder = fixture.manager.holdBinding()

            fixture.facade.deliverServiceDied()
            fixture.facade.deliverBinderDeath()
            advanceUntilIdle()

            assertEquals(1, fixture.facade.bindCalls)
            assertSame(ShizukuState.NotRunning, fixture.manager.state.value)
            assertNull(fixture.manager.telemetryService.value)
            holder.close()
        }

    @Test
    fun aCancelledSettleTimeoutWaitingForTheLockCannotBypassDeathCallbackCleanup() =
        runTest {
            val fixture = Fixture(this)
            fixture.manager.start()
            fixture.manager
                .holdBinding()
                .apply { fixture.facade.deliverServiceConnected() }
                .close()
            advanceTimeBy(LINGER_MS + 1)
            val holder = fixture.manager.holdBinding()

            fixture.whileTheLockIsHeldElsewhere(
                underLock = {
                    fixture.facade.deliverServiceDied {
                        fixture.manager.holdBinding().close()
                        assertEquals("the timeout must not permit a bind before cleanup", 1, fixture.facade.bindCalls)
                    }
                },
            ) { advanceTimeBy(SETTLE_TIMEOUT_MS + 1) }
            runCurrent()

            assertEquals("only the replacement settle may bind", 2, fixture.facade.bindCalls)
            fixture.facade.deliverServiceConnected()
            assertEquals("ok", fixture.manager.withTelemetryService(1_000L) { "ok" })
            holder.close()
        }

    @Test
    fun aPostedDeathSettleAlreadyWaitingForTheLockIsFencedAfterTransportLoss() =
        runTest {
            val fixture = Fixture(this)
            fixture.manager.start()
            fixture.manager
                .holdBinding()
                .apply { fixture.facade.deliverServiceConnected() }
                .close()
            advanceTimeBy(LINGER_MS + 1)
            val holder = fixture.manager.holdBinding()
            fixture.facade.deliverServiceDied()

            fixture.whileTheLockIsHeldElsewhere(
                underLock = { fixture.facade.deliverBinderDeath() },
            ) { runCurrent() }
            advanceUntilIdle()

            assertEquals(1, fixture.facade.bindCalls)
            assertSame(ShizukuState.NotRunning, fixture.manager.state.value)
            holder.close()
        }

    @Test
    fun noSecondBindIsIssuedWhileTheUnboundServiceIsStillDying() =
        runTest {
            val fixture = Fixture(this)
            fixture.manager.start()
            fixture.manager
                .holdBinding()
                .apply { fixture.facade.deliverServiceConnected() }
                .close()
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
    fun aDeferredBindStillHappensWhenTheOldDeathNeverArrives() =
        runTest {
            val fixture = Fixture(this)
            fixture.manager.start()
            fixture.manager
                .holdBinding()
                .apply { fixture.facade.deliverServiceConnected() }
                .close()
            advanceTimeBy(LINGER_MS + 1)

            val holder = fixture.manager.holdBinding()
            runCurrent()
            assertEquals(1, fixture.facade.bindCalls)
            advanceTimeBy(SETTLE_TIMEOUT_MS + 1)

            assertEquals(2, fixture.facade.bindCalls)
            holder.close()
        }

    // SQ-118 M1: the fallback bind shares the old process's connection stub, so that process's late death
    // reaches the new binding. It must not tear the live new binding down (and so issue a duplicate bind).
    @Test
    fun theOldServicesDeathAfterTheFallbackBindDoesNotDropTheNewBinding() =
        runTest {
            val fixture = Fixture(this)
            fixture.manager.start()
            fixture.manager
                .holdBinding()
                .apply { fixture.facade.deliverServiceConnected() }
                .close()
            advanceTimeBy(LINGER_MS + 1)
            val holder = fixture.manager.holdBinding()
            advanceTimeBy(SETTLE_TIMEOUT_MS + 1)
            assertEquals(2, fixture.facade.bindCalls)
            fixture.facade.deliverServiceConnected(LiveBinder()) // the new process

            fixture.facade.deliverServiceDied() // the old process's late death, on the shared stub

            assertNotNull("the live new binding is kept", fixture.manager.telemetryService.value)
            assertEquals("ok", fixture.manager.withTelemetryService(1_000L) { "ok" })
            assertEquals("no duplicate bindUserService for the same args", 2, fixture.facade.bindCalls)
            holder.close()
        }

    @Test
    fun aGenuineDeathOfTheFallbackBoundServiceIsStillADisconnect() =
        runTest {
            val fixture = Fixture(this)
            fixture.manager.start()
            fixture.manager
                .holdBinding()
                .apply { fixture.facade.deliverServiceConnected() }
                .close()
            advanceTimeBy(LINGER_MS + 1)
            val holder = fixture.manager.holdBinding()
            advanceTimeBy(SETTLE_TIMEOUT_MS + 1)
            val newService = LiveBinder()
            fixture.facade.deliverServiceConnected(newService)

            newService.alive = false // the new process dies (the old one never reported a death)
            fixture.facade.deliverServiceDied()

            assertNull("a dead new service must not be handed out", fixture.manager.telemetryService.value)
            val result = async { fixture.manager.withTelemetryService(1_000L) { "rebound" } }
            runCurrent()
            assertEquals("the next user rebinds", 3, fixture.facade.bindCalls)
            fixture.facade.deliverServiceConnected(LiveBinder())
            assertEquals("rebound", result.await())
            holder.close()
        }

    // F-17: after a slow shutdown the fallback bind shares the old process's stub, whose only died() goes to the old
    // process's death. The new process's later death is then reported by no stub; the manager must not keep it.
    @Test
    fun theFallbackBindingsDeathAfterTheSharedStubFiredStillRecoversToAFreshBinding() =
        runTest {
            val fixture = Fixture(this)
            fixture.manager.start()
            fixture.manager
                .holdBinding()
                .apply { fixture.facade.deliverServiceConnected(LiveBinder()) }
                .close()
            advanceTimeBy(LINGER_MS + 1)
            val holder = fixture.manager.holdBinding()
            advanceTimeBy(SETTLE_TIMEOUT_MS + 1)
            assertEquals(2, fixture.facade.bindCalls)
            val newService = LiveBinder()
            fixture.facade.deliverServiceConnected(newService)
            fixture.facade.deliverServiceDied() // the old process's late death spends the shared stub
            assertSame(
                newService,
                fixture.manager.telemetryService.value
                    ?.asBinder(),
            )

            newService.kill() // the new process dies: the spent stub reports nothing

            assertNull("a dead service must not stay in use", fixture.manager.telemetryService.value)
            val result = async { fixture.manager.withTelemetryService(1_000L) { "rebound" } }
            runCurrent()
            assertEquals("the next user binds a fresh service", 3, fixture.facade.bindCalls)
            fixture.facade.deliverServiceConnected(LiveBinder())
            assertEquals("rebound", result.await())
            holder.close()
        }

    @Test
    fun aDeadBinderFailingACallIsDroppedEvenBeforeItsDeathNotice() =
        runTest {
            val fixture = Fixture(this)
            fixture.manager.start()
            fixture.manager
                .holdBinding()
                .apply { fixture.facade.deliverServiceConnected(LiveBinder()) }
                .close()
            advanceTimeBy(LINGER_MS + 1)
            val holder = fixture.manager.holdBinding()
            advanceTimeBy(SETTLE_TIMEOUT_MS + 1)
            val newService = LiveBinder()
            fixture.facade.deliverServiceConnected(newService)
            fixture.facade.deliverServiceDied()

            newService.alive = false // the process is gone; the call fails before any death notice is delivered
            assertNull(fixture.manager.withTelemetryService(1_000L) { throw DeadObjectException() })

            assertNull("the dead binder is invalidated", fixture.manager.telemetryService.value)
            val result = async { fixture.manager.withTelemetryService(1_000L) { "rebound" } }
            runCurrent()
            assertEquals(3, fixture.facade.bindCalls)
            fixture.facade.deliverServiceConnected(LiveBinder())
            assertEquals("rebound", result.await())
            holder.close()
        }

    @Test
    fun aDeadBinderWhoseStubStillReportsWaitsForTheStubsPostedCleanup() =
        runTest {
            val fixture = Fixture(this)
            fixture.manager.start()
            val holder = fixture.manager.holdBinding()
            val service = LiveBinder()
            fixture.facade.deliverServiceConnected(service)

            service.alive = false
            assertNull(fixture.manager.withTelemetryService(1_000L) { throw DeadObjectException() })

            assertEquals("no bind may share the live stub before its died() cleans up (SQ-114)", 1, fixture.facade.bindCalls)
            fixture.facade.deliverServiceDied()
            assertNull(fixture.manager.telemetryService.value)
            val result = async { fixture.manager.withTelemetryService(1_000L) { "rebound" } }
            runCurrent()
            assertEquals(2, fixture.facade.bindCalls)
            fixture.facade.deliverServiceConnected(LiveBinder())
            assertEquals("rebound", result.await())
            holder.close()
        }

    @Test
    fun realShizukuSpentStubAfterAFallbackBindStillLetsTheManagerRecover() =
        runTest {
            val main = CallbackMainDispatcher(StandardTestDispatcher(testScheduler))
            Dispatchers.setMain(main)
            LibraryConnectionFacade().use { facade ->
                val manager =
                    ShizukuManagerImpl(
                        context = TestContext(),
                        facade = facade,
                        scope = this,
                        applicationInfoProvider = ShizukuApplicationInfoProvider { ShizukuApplicationInfo(42, true) },
                        ioDispatcher = StandardTestDispatcher(testScheduler),
                    )
                manager.start()
                val first = manager.holdBinding()
                val sharedWrapper = facade.boundWrappers.single()
                val oldBinder = LiveBinder()
                facade.deliverConnected(sharedWrapper, oldBinder)
                first.close()
                advanceTimeBy(LINGER_MS + 1)
                assertEquals(1, facade.unbindCalls)

                // The old process outlives the settle timeout, so the fallback bind reuses its cached wrapper.
                val holder = manager.holdBinding()
                advanceTimeBy(SETTLE_TIMEOUT_MS + 1)
                assertEquals(2, facade.boundWrappers.size)
                assertSame(sharedWrapper, facade.boundWrappers.last())
                val newBinder = LiveBinder()
                facade.deliverConnected(sharedWrapper, newBinder)

                // The old process exits: the library's death link spends the wrapper's once-only died().
                oldBinder.kill()
                main.runCallback { facade.deliverDeathCallback(sharedWrapper) }
                assertSame("the live new binding is kept", newBinder, manager.telemetryService.value?.asBinder())

                newBinder.kill() // died() returns at once on the spent wrapper

                assertNull("a dead service must not stay in use", manager.telemetryService.value)
                val result = async { manager.withTelemetryService(1_000L) { "fresh" } }
                runCurrent()
                assertEquals(3, facade.boundWrappers.size)
                val freshWrapper = facade.boundWrappers.last()
                assertNotSame("the evicted wrapper is not reused", sharedWrapper, freshWrapper)
                facade.deliverConnected(freshWrapper, LiveBinder())
                assertEquals("fresh", result.await())
                holder.close()
                manager.stop()
                advanceUntilIdle()
            }
        }

    // SQ-118 L1: stop() unbinds for good, even with a holder still open and a later death or settle timeout.
    @Test
    fun stopNeverRebindsAfterALaterSettleTimeoutOrDeath() =
        runTest {
            val fixture = Fixture(this)
            fixture.manager.start()
            val holder = fixture.manager.holdBinding()
            fixture.facade.deliverServiceConnected()

            fixture.manager.stop()
            advanceTimeBy(SETTLE_TIMEOUT_MS + 1)
            fixture.facade.deliverServiceDied()
            advanceUntilIdle()

            assertEquals(1, fixture.facade.unbindCalls)
            assertEquals("stop() must leave the service unbound", 1, fixture.facade.bindCalls)
            assertNull(fixture.manager.withTelemetryService(1_000L) { Unit })
            assertEquals(1, fixture.facade.bindCalls)
            holder.close()
        }

    // SQ-118 L2: a linger job already past its delay and waiting for the lock must not act once cancelled.
    @Test
    fun aCancelledLingerWaitingForTheLockDoesNotUnbindEarly() =
        runTest {
            val fixture = Fixture(this)
            fixture.manager.start()
            fixture.manager
                .holdBinding()
                .apply { fixture.facade.deliverServiceConnected() }
                .close() // linger 1 due at 3 s

            // While linger 1 waits for the lock, a quick acquire/release cancels it and starts linger 2 (due at 6 s).
            fixture.whileTheLockIsHeldElsewhere(
                underLock = { fixture.manager.holdBinding().close() },
            ) { advanceTimeBy(LINGER_MS + 1) }

            assertEquals("the cancelled linger must not unbind inside the new window", 0, fixture.facade.unbindCalls)
            advanceTimeBy(LINGER_MS)
            assertEquals(1, fixture.facade.unbindCalls)
        }

    // SQ-118 L3: the service and its generation are read together, so a rebind in between cannot pass a stale binder.
    @Test
    fun aRebindBetweenServiceAndGenerationReadsIsNotMissed() =
        runTest {
            val fixture = Fixture(this)
            fixture.manager.start()
            val oldService = LiveBinder()
            val newService = LiveBinder()
            var received: android.os.IBinder? = null
            val result =
                async {
                    fixture.manager.withTelemetryService(1_000L) {
                        received = it.asBinder()
                        "done"
                    }
                }
            runCurrent()
            fixture.facade.deliverServiceConnected(oldService)

            fixture.whileTheLockIsHeldElsewhere(
                underLock = {
                    oldService.alive = false
                    fixture.facade.deliverServiceDied()
                    fixture.manager
                        .holdBinding()
                        .apply { fixture.facade.deliverServiceConnected(newService) }
                        .close()
                },
            ) { runCurrent() }

            val answer = result.await()
            assertTrue(
                "a result may only come from the current service",
                answer == null || received === newService,
            )
        }

    @Test
    fun unbindClearsTheDeadBinderForCallers() =
        runTest {
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
    fun aLateConnectFromTheUnboundServiceIsIgnored() =
        runTest {
            val fixture = Fixture(this)
            fixture.manager.start()
            fixture.manager.holdBinding().close() // unbound before the service ever connected
            advanceTimeBy(LINGER_MS + 1)
            assertEquals(1, fixture.facade.unbindCalls)

            fixture.facade.deliverServiceConnected()

            assertNull(fixture.manager.telemetryService.value)
        }

    @Test
    fun reacquiringWithinTheLingerKeepsTheServiceWithoutKillingIt() =
        runTest {
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
    fun theIdleLingerIsShortAndBounded() =
        runTest {
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
            val manager =
                ShizukuManagerImpl(
                    context = TestContext(),
                    facade = facade,
                    scope = CoroutineScope(main),
                    applicationInfoProvider = ShizukuApplicationInfoProvider { ShizukuApplicationInfo(42, true) },
                )
            manager.start()
            val holder = manager.holdBinding()
            facade.deliverServiceConnected()

            val threads =
                runBlocking(main) {
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
        val manager =
            ShizukuManagerImpl(
                context = TestContext(),
                facade = facade,
                scope = scope,
                applicationInfoProvider = ShizukuApplicationInfoProvider { applicationInfo },
                ioDispatcher = StandardTestDispatcher(scope.testScheduler),
            )

        /**
         * Holds bindingLock on another thread until [onTestThread] blocks on it, then runs [underLock].
         * Take only the lock: pausing refreshState in getUid() would resume a stale Ready/bind update after
         * an injected transport loss and contaminate the cancellation assertion with a separate bind.
         */
        fun whileTheLockIsHeldElsewhere(
            underLock: () -> Unit,
            onTestThread: () -> Unit,
        ) {
            val testThread = Thread.currentThread()
            val lockHeld = CountDownLatch(1)
            var failure: Throwable? = null
            val bindingLock =
                ShizukuManagerImpl::class.java
                    .getDeclaredField("bindingLock")
                    .apply { isAccessible = true }
                    .get(manager)
            val other =
                Thread {
                    try {
                        synchronized(bindingLock) {
                            lockHeld.countDown()
                            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                            while (testThread.state != Thread.State.BLOCKED && System.nanoTime() < deadline) {
                                Thread.onSpinWait()
                            }
                            check(testThread.state == Thread.State.BLOCKED) { "the test thread never waited for the lock" }
                            underLock()
                        }
                    } catch (t: Throwable) {
                        failure = t
                    }
                }
            other.start()
            check(lockHeld.await(10, TimeUnit.SECONDS)) { "the other thread never took the lock" }
            onTestThread()
            other.join(10_000L)
            failure?.let { throw it }
        }
    }

    /**
     * A binder whose liveness the test controls (the android.jar stub reports every binder dead). It keeps its
     * death links like a remote binder: [kill] is the hosting process exiting, which notifies each link once.
     */
    private class LiveBinder : Binder() {
        @Volatile var alive = true
        private val deathLinks = mutableListOf<IBinder.DeathRecipient>()

        override fun isBinderAlive(): Boolean = alive

        override fun linkToDeath(
            recipient: IBinder.DeathRecipient,
            flags: Int,
        ) {
            if (!alive) throw DeadObjectException()
            synchronized(deathLinks) { deathLinks += recipient }
        }

        override fun unlinkToDeath(
            recipient: IBinder.DeathRecipient,
            flags: Int,
        ): Boolean = synchronized(deathLinks) { deathLinks.remove(recipient) }

        fun kill() {
            alive = false
            val notified = synchronized(deathLinks) { deathLinks.toList().also { deathLinks.clear() } }
            notified.forEach { it.binderDied() }
        }
    }

    private class TestContext : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this

        override fun getPackageName(): String = "com.akane.inspiration"
    }

    /** Unlike StandardTestDispatcher alone, this distinguishes Main from Main.immediate in callbacks. */
    private class CallbackMainDispatcher(
        private val queue: CoroutineDispatcher,
    ) : MainCoroutineDispatcher() {
        private val onMain = ThreadLocal.withInitial { false }

        override val immediate =
            object : MainCoroutineDispatcher() {
                override val immediate: MainCoroutineDispatcher get() = this

                override fun isDispatchNeeded(context: CoroutineContext): Boolean = !onMain.get()

                override fun dispatch(
                    context: CoroutineContext,
                    block: Runnable,
                ) {
                    this@CallbackMainDispatcher.dispatch(context, block)
                }
            }

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) {
            queue.dispatch(context, Runnable { runCallback { block.run() } })
        }

        fun runCallback(block: () -> Unit) {
            val wasOnMain = onMain.get()
            onMain.set(true)
            try {
                block()
            } finally {
                onMain.set(wasOnMain)
            }
        }
    }

    /**
     * Runs api-13.1.5's actual bind/unbind, cache and callback bodies. Only the remote Shizuku daemon is
     * replaced. android.jar has no running Handler, so invoke the library's Main-handler bodies directly;
     * the manager's follow-up Main dispatch still runs on the controlled test scheduler.
     */
    private class LibraryConnectionFacade(
        lifecycle: FakeShizukuFacade = FakeShizukuFacade(),
    ) : ShizukuFacade by lifecycle,
        AutoCloseable {
        val boundWrappers = mutableListOf<Any>()
        var unbindCalls = 0
            private set
        private val connectionClass = Class.forName("rikka.shizuku.ShizukuServiceConnection")
        private val serviceField = Shizuku::class.java.getDeclaredField("service").apply { isAccessible = true }
        private val originalService = serviceField.get(null)

        init {
            serviceField.set(
                null,
                Proxy.newProxyInstance(serviceField.type.classLoader, arrayOf(serviceField.type)) { _, method, args ->
                    when (method.name) {
                        "addUserService" -> {
                            boundWrappers += requireNotNull(args?.get(0))
                            0
                        }

                        "removeUserService" -> {
                            // remove=true destroys remotely without evicting the local wrapper.
                            assertNull(args?.get(0))
                            unbindCalls += 1
                            0
                        }

                        else -> {
                            error("unexpected daemon call: ${method.name}")
                        }
                    }
                },
            )
        }

        override fun bindUserService(
            args: Shizuku.UserServiceArgs,
            connection: ServiceConnection,
        ) {
            Shizuku.bindUserService(args, connection)
        }

        override fun unbindUserService(
            args: Shizuku.UserServiceArgs,
            connection: ServiceConnection,
            remove: Boolean,
        ) {
            assertTrue(remove)
            Shizuku.unbindUserService(args, connection, remove)
        }

        fun deliverConnected(
            wrapper: Any,
            binder: IBinder,
        ) {
            connectionClass
                .getDeclaredMethod("connected", IBinder::class.java)
                .apply { isAccessible = true }
                .invoke(wrapper, binder)
            connectionClass
                .getDeclaredMethod("lambda\$connected\$0", IBinder::class.java)
                .apply { isAccessible = true }
                .invoke(wrapper, binder)
        }

        fun deliverDeathCallback(wrapper: Any) {
            connectionClass
                .getDeclaredMethod("died")
                .apply { isAccessible = true }
                .invoke(wrapper)
            connectionClass
                .getDeclaredMethod("lambda\$died\$1")
                .apply { isAccessible = true }
                .invoke(wrapper)
        }

        override fun close() {
            try {
                val remove =
                    Class
                        .forName("rikka.shizuku.ShizukuServiceConnections")
                        .getDeclaredMethod("remove", connectionClass)
                        .apply { isAccessible = true }
                boundWrappers.forEach { remove.invoke(null, it) }
            } finally {
                serviceField.set(null, originalService)
            }
        }
    }

    private class FakeShizukuFacade : ShizukuFacade {
        var binderAlive = true
        var preV11 = false
        var serverVersion = 13
        var permissionResult = PackageManager.PERMISSION_GRANTED
        var showPermissionRationale = false
        var addedBinderReceivedListeners = 0
        var addedBinderDeadListeners = 0
        var addedPermissionListeners = 0
        var removedBinderReceivedListeners = 0
        var removedBinderDeadListeners = 0
        var removedPermissionListeners = 0
        var permissionRequests = 0
        var bindFailure = false
        var unbindFailure = false
        var bindCalls = 0
        var unbindCalls = 0
        var lastUnbindRemove = false
        private var binderReceivedListener: Shizuku.OnBinderReceivedListener? = null
        private var binderDeadListener: Shizuku.OnBinderDeadListener? = null
        private var permissionListener: Shizuku.OnRequestPermissionResultListener? = null

        /**
         * Mirrors rikka.shizuku.ShizukuServiceConnection(s) (api 13.1.5): a bind reuses the stub cached for the
         * service tag, and unbindUserService(remove=true) neither clears nor evicts it. connected() links each
         * delivered binder to the stub's died(). died() runs once per stub (its dead flag): it notifies every
         * connection, then clears them and evicts the stub. A later connected() on it reaches nobody, the next
         * bind gets a fresh stub, and the death of any other binder linked to a spent stub reports nothing.
         */
        private class ConnectionStub {
            val connections = linkedSetOf<ServiceConnection>()
            var dead = false
        }

        private var cachedStub: ConnectionStub? = null

        /** The stub handed to the daemon by the latest bind: the one its service's connected() reaches. */
        private var boundStub: ConnectionStub? = null

        override fun pingBinder(): Boolean = binderAlive

        override fun getUid(): Int = 2000

        override fun getVersion(): Int = serverVersion

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

        override fun bindUserService(
            args: Shizuku.UserServiceArgs,
            connection: ServiceConnection,
        ) {
            bindCalls += 1
            if (bindFailure) error("daemon died during bind")
            val stub = cachedStub ?: ConnectionStub().also { cachedStub = it }
            stub.connections += connection
            boundStub = stub
        }

        override fun unbindUserService(
            args: Shizuku.UserServiceArgs,
            connection: ServiceConnection,
            remove: Boolean,
        ) {
            unbindCalls += 1
            if (unbindFailure) error("daemon died during unbind")
            lastUnbindRemove = remove
        }

        fun deliverPermissionResult(result: Int) {
            permissionListener?.onRequestPermissionResult(1, result)
        }

        fun deliverBinderDeath() {
            binderDeadListener?.onBinderDead()
        }

        fun deliverServiceConnected(binder: IBinder = Binder()) {
            val stub = boundStub ?: return
            try {
                binder.linkToDeath({ died(stub) }, 0)
            } catch (_: RemoteException) {
                // The library ignores a binder that is already dead here.
            }
            stub.connections.toList().forEach { it.onServiceConnected(ComponentName("test", "service"), binder) }
        }

        /** The bound process exited (e.g. after unbind remove=true): the cached stub's died() path. */
        fun deliverServiceDied(beforeCleanup: () -> Unit = {}) {
            val stub = cachedStub
            if (stub == null) {
                beforeCleanup()
                return
            }
            died(stub, beforeCleanup)
        }

        private fun died(
            stub: ConnectionStub,
            beforeCleanup: () -> Unit = {},
        ) {
            if (stub.dead) return
            stub.dead = true
            stub.connections.toList().forEach { it.onServiceDisconnected(ComponentName("test", "service")) }
            beforeCleanup()
            stub.connections.clear()
            if (cachedStub === stub) cachedStub = null
        }
    }
}
