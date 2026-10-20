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
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.MainCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
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
import java.util.concurrent.ConcurrentLinkedQueue
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
            fixture.start()
            fixture.facade.bindFailure = true
            val holder = fixture.holdBinding()
            assertSame(ShizukuState.NotRunning, fixture.manager.state.value)
            holder.close()
            fixture.facade.bindFailure = false
            fixture.deliverPermissionResult(PackageManager.PERMISSION_GRANTED)
            assertEquals(1, fixture.facade.bindCalls)
            val next = fixture.holdBinding()
            assertEquals(2, fixture.facade.bindCalls)
            next.close()
            advanceUntilIdle()
            assertEquals(1, fixture.facade.unbindCalls)
        }

    @Test
    fun failedServiceAcquisitionReleasesUserBeforeReconnect() =
        runTest {
            val fixture = Fixture(this)
            fixture.start()
            fixture.facade.bindFailure = true
            assertNull(fixture.manager.withTelemetryService(1) { Unit })
            fixture.facade.bindFailure = false
            fixture.deliverPermissionResult(PackageManager.PERMISSION_GRANTED)
            assertEquals(1, fixture.facade.bindCalls)
            advanceUntilIdle()
            assertEquals(0, fixture.facade.unbindCalls)
        }

    @Test
    fun failedIdleUnbindClearsTransportAndAllowsAnotherBind() =
        runTest {
            val fixture = Fixture(this)
            fixture.start()
            val holder = fixture.holdBinding()
            fixture.facade.deliverServiceConnected()
            fixture.facade.unbindFailure = true
            holder.close()
            advanceUntilIdle()
            assertSame(ShizukuState.NotRunning, fixture.manager.state.value)
            assertNull(fixture.manager.telemetryService.value)
            fixture.facade.unbindFailure = false
            fixture.deliverPermissionResult(PackageManager.PERMISSION_GRANTED)
            val next = fixture.holdBinding()
            assertEquals(2, fixture.facade.bindCalls)
            next.close()
            advanceUntilIdle()
        }

    @Test
    fun start_reportsNotInstalledWithoutABinderOrTheManagerApp() =
        runTest {
            val fixture = Fixture(this, managerInstalled = false).apply { facade.binderAlive = false }

            fixture.start()

            assertSame(ShizukuState.NotInstalled, fixture.manager.state.value)
            assertEquals(1, fixture.facade.addedBinderReceivedListeners)
        }

    // N-08: Sui (root, no manager app) hands ShizukuProvider its binder through Sui.init. A live binder is Shizuku.
    @Test
    fun aSuiBinderWithoutTheManagerAppIsReadyAndServesCalls() =
        runTest {
            val fixture =
                Fixture(this, managerInstalled = false).apply {
                    facade.sui = true
                    facade.serverUid = 0 // Sui runs as root
                }

            fixture.start()

            assertEquals(ShizukuState.Ready(0, 13, ShizukuBackend.SUI), fixture.manager.state.value)
            val result = async { fixture.manager.withTelemetryService(1_000L) { "read" } }
            runCurrent()
            assertEquals(1, fixture.facade.bindCalls)
            fixture.facade.deliverServiceConnected()
            assertEquals("read", result.await())
        }

    @Test
    fun suiAsksForPermissionThroughTheShizukuApiWithoutTheManagerApp() =
        runTest {
            val fixture =
                Fixture(this, managerInstalled = false).apply {
                    facade.sui = true
                    facade.serverUid = 0
                    facade.permissionResult = PackageManager.PERMISSION_DENIED
                }
            fixture.start()
            assertEquals(ShizukuState.PermissionRequired(ShizukuBackend.SUI), fixture.manager.state.value)

            fixture.requestPermission()
            assertEquals("Sui shows its own dialog for Shizuku.requestPermission", 1, fixture.facade.permissionRequests)
            fixture.facade.permissionResult = PackageManager.PERMISSION_GRANTED
            fixture.deliverPermissionResult(PackageManager.PERMISSION_GRANTED)

            assertEquals(ShizukuState.Ready(0, 13, ShizukuBackend.SUI), fixture.manager.state.value)
        }

    // SQ-29: every binder-backed state names the server that sent the binder; the binderless ones cannot know it.
    @Test
    fun binderBackedStatesNameTheirBackendAndBinderlessStatesDoNot() =
        runTest {
            ShizukuBackend.entries.forEach { backend ->
                val fixture =
                    Fixture(this).apply {
                        facade.sui = backend == ShizukuBackend.SUI
                        facade.permissionResult = PackageManager.PERMISSION_DENIED
                    }
                fixture.start()
                assertEquals(ShizukuState.PermissionRequired(backend), fixture.manager.state.value)

                fixture.facade.showPermissionRationale = true
                fixture.manager.refresh()
                assertEquals(ShizukuState.PermissionDenied(backend), fixture.manager.state.value)

                fixture.facade.permissionResult = PackageManager.PERMISSION_GRANTED
                fixture.manager.refresh()
                assertEquals(ShizukuState.Ready(2000, 13, backend), fixture.manager.state.value)
                assertEquals(backend, fixture.manager.state.value.backend)

                fixture.facade.binderAlive = false
                fixture.facade.deliverBinderDeath()
                fixture.manager.refresh()
                assertSame(ShizukuState.NotRunning, fixture.manager.state.value)
                assertNull("no binder, so no backend", fixture.manager.state.value.backend)
                fixture.manager.stop()
                advanceUntilIdle()
            }
        }

    @Test
    fun aBinderArrivingAfterNotInstalledIsPickedUp() =
        runTest {
            val fixture = Fixture(this, managerInstalled = false).apply { facade.binderAlive = false }
            fixture.start()
            assertSame(ShizukuState.NotInstalled, fixture.manager.state.value)

            fixture.facade.binderAlive = true
            fixture.deliverBinderReceived()

            assertEquals(ShizukuState.Ready(2000, 13), fixture.manager.state.value)
        }

    // A stopped Sui server is not an uninstall: once this process had a binder, its loss is NotRunning, and a call
    // waits for the server instead of failing at once.
    @Test
    fun aSuiServerThatStopsIsNotRunningAndCallsWaitForItsReturn() =
        runTest {
            val fixture = Fixture(this, managerInstalled = false)
            fixture.start()
            assertEquals(ShizukuState.Ready(2000, 13), fixture.manager.state.value)

            fixture.facade.binderAlive = false
            fixture.facade.deliverBinderDeath()
            fixture.manager.refresh() // MainActivity.onStart while the server is down

            assertSame(ShizukuState.NotRunning, fixture.manager.state.value)
            val call = async { fixture.manager.withTelemetryService(2_000L) { "read" } }
            runCurrent()
            fixture.facade.binderAlive = true
            fixture.deliverBinderReceived() // the server is back
            runCurrent()
            fixture.facade.deliverServiceConnected()
            assertEquals("read", call.await())
        }

    @Test
    fun aBinderDeathAloneMarksTheBinderAsSeen() =
        runTest {
            // The binder came and died before the received listener could read it.
            val fixture = Fixture(this, managerInstalled = false).apply { facade.binderAlive = false }
            fixture.start()
            assertSame(ShizukuState.NotInstalled, fixture.manager.state.value)

            fixture.facade.deliverBinderDeath()
            fixture.manager.refresh()

            assertSame(ShizukuState.NotRunning, fixture.manager.state.value)
        }

    @Test
    fun aLiveBinderWithoutTheManagerAppStillAppliesTheServerVersionChecks() =
        runTest {
            val preV11 = Fixture(this, managerInstalled = false).apply { facade.preV11 = true }
            preV11.start()
            assertSame(ShizukuState.NotRunning, preV11.manager.state.value)

            val oldServer = Fixture(this, managerInstalled = false).apply { facade.serverVersion = 12 }
            oldServer.start()
            assertEquals(ShizukuState.UnsupportedServer(12), oldServer.manager.state.value)

            assertEquals(0, preV11.facade.bindCalls + oldServer.facade.bindCalls)
        }

    @Test
    fun anUnreadableOwnPackageIsNotRunningAndNeverBinds() =
        runTest {
            val fixture =
                Fixture(
                    this,
                    applicationInfoProvider = ShizukuApplicationInfoProvider { error("own package is not readable") },
                )

            fixture.start()
            fixture.manager.holdBinding().close()

            assertSame(ShizukuState.NotRunning, fixture.manager.state.value)
            assertNull(fixture.manager.withTelemetryService(1L) { Unit })
            assertEquals(0, fixture.facade.bindCalls)
        }

    @Test
    fun start_reportsNotRunningWhenBinderIsUnavailable() =
        runTest {
            val fixture = Fixture(this).apply { facade.binderAlive = false }

            fixture.start()

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

            fixture.start()

            assertEquals(ShizukuState.PermissionRequired(), fixture.manager.state.value)
            fixture.requestPermission()
            assertEquals(1, fixture.facade.permissionRequests)
        }

    @Test
    fun deniedPermissionResultUpdatesStateWithoutBinding() =
        runTest {
            val fixture =
                Fixture(this).apply {
                    facade.permissionResult = PackageManager.PERMISSION_DENIED
                }
            fixture.start()

            // A deny that Shizuku stored is reported through the rationale flag, not by the result itself.
            fixture.facade.showPermissionRationale = true
            fixture.deliverPermissionResult(PackageManager.PERMISSION_DENIED)

            assertEquals(ShizukuState.PermissionDenied(), fixture.manager.state.value)
            assertEquals(0, fixture.facade.bindCalls)
        }

    @Test
    fun aOneTimeDenyThenAGrantInTheShizukuAppRecoversWithoutARestart() =
        runTest {
            val fixture =
                Fixture(this).apply {
                    facade.permissionResult = PackageManager.PERMISSION_DENIED
                }
            fixture.start()
            fixture.requestPermission()

            // Shizuku did not store this deny (onetime=true), so it reports no rationale and may ask again.
            fixture.deliverPermissionResult(PackageManager.PERMISSION_DENIED)
            assertEquals(ShizukuState.PermissionRequired(), fixture.manager.state.value)

            fixture.requestPermission()
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
            fixture.start()
            assertEquals(ShizukuState.PermissionDenied(), fixture.manager.state.value)

            // Shizuku answers a stored deny without a dialog, so it is not asked while the deny stands.
            fixture.requestPermission()
            assertEquals(ShizukuState.PermissionDenied(), fixture.manager.state.value)
            assertEquals(0, fixture.facade.permissionRequests)

            // Allowed in the Shizuku app, then Grant tapped back in Inspiration.
            fixture.facade.permissionResult = PackageManager.PERMISSION_GRANTED
            fixture.facade.showPermissionRationale = false
            fixture.requestPermission()

            assertEquals(ShizukuState.Ready(2000, 13), fixture.manager.state.value)
            assertEquals("an access that is already granted needs no dialog", 0, fixture.facade.permissionRequests)
        }

    @Test
    fun refreshDoesNothingBeforeStartOrAfterStop() =
        runTest {
            val fixture = Fixture(this)

            fixture.manager.refresh()
            assertSame(ShizukuState.NotRunning, fixture.manager.state.value)

            fixture.start()
            fixture.manager.stop()
            fixture.facade.permissionResult = PackageManager.PERMISSION_DENIED
            fixture.manager.refresh()

            assertEquals(ShizukuState.Ready(2000, 13), fixture.manager.state.value)
        }

    @Test
    fun refreshWhileReadyLeavesTheBoundServiceAlone() =
        runTest {
            val fixture = Fixture(this)
            fixture.start()
            val holder = fixture.holdBinding()
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
            fixture.start()

            fixture.facade.permissionResult = PackageManager.PERMISSION_GRANTED
            fixture.deliverPermissionResult(PackageManager.PERMISSION_GRANTED)

            assertEquals(ShizukuState.Ready(2000, 13), fixture.manager.state.value)
            assertEquals(0, fixture.facade.bindCalls)
        }

    @Test
    fun withTelemetryServiceUnbindsWhenLastUserCompletes() =
        runTest {
            val fixture = Fixture(this)
            fixture.start()

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
            fixture.start()

            val holder = fixture.holdBinding()
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
            fixture.start()

            assertNull(fixture.manager.withTelemetryService(1L) { Unit })
            advanceTimeBy(LINGER_MS + 1)
            assertEquals(1, fixture.facade.bindCalls)
            assertEquals(1, fixture.facade.unbindCalls)
        }

    @Test
    fun withTelemetryServiceReturnsNullOnBinderDeathDuringBlock() =
        runTest {
            val fixture = Fixture(this)
            fixture.start()

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
            fixture.start()

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
            fixture.start()

            fixture.facade.deliverBinderDeath()

            assertSame(ShizukuState.NotRunning, fixture.manager.state.value)
            assertNull(fixture.manager.telemetryService.value)
        }

    @Test
    fun preV11ShizukuIsNotRunning() =
        runTest {
            val fixture = Fixture(this).apply { facade.preV11 = true }

            fixture.start()

            assertSame(ShizukuState.NotRunning, fixture.manager.state.value)
            assertEquals(0, fixture.facade.bindCalls)
        }

    @Test
    fun aServerOlderThanV13IsUnsupportedAndNeverReady() =
        runTest {
            // A v11/v12 server builds TelemetryUserService through its no-arg constructor, which rejects every call.
            val fixture = Fixture(this).apply { facade.serverVersion = 12 }

            fixture.start()

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
            fixture.start()

            fixture.requestPermission()

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
            fixture.start()
            assertEquals(ShizukuState.PermissionRequired(), fixture.manager.state.value)

            fixture.facade.serverVersion = 12
            fixture.facade.permissionResult = PackageManager.PERMISSION_GRANTED
            fixture.deliverPermissionResult(PackageManager.PERMISSION_GRANTED)

            assertEquals(ShizukuState.UnsupportedServer(12), fixture.manager.state.value)
        }

    @Test
    fun startTwiceRegistersListenersOnce() =
        runTest {
            val fixture = Fixture(this)

            fixture.start()
            fixture.start()

            assertEquals(1, fixture.facade.addedBinderReceivedListeners)
            assertEquals(1, fixture.facade.addedBinderDeadListeners)
            assertEquals(1, fixture.facade.addedPermissionListeners)
        }

    @Test
    fun stopUnregistersListenersAndUnbindsHeldService() =
        runTest {
            val fixture = Fixture(this)
            fixture.start()
            val holder = fixture.holdBinding()

            fixture.manager.stop()
            holder.close()
            runCurrent()

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
            fixture.start()
            val first = fixture.holdBinding()
            fixture.facade.deliverServiceConnected()
            first.close()
            advanceTimeBy(LINGER_MS + 1)
            assertEquals(1, fixture.facade.unbindCalls)

            val second = fixture.holdBinding()
            fixture.facade.deliverServiceDied {
                // The callback ran, but Shizuku still caches the dying wrapper and its registrations. A Shizuku
                // listener's re-read (B-10: queued on IO, not run here) and a holder must both wait for cleanup.
                fixture.facade.deliverPermissionResult(PackageManager.PERMISSION_GRANTED)
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
                        managerApp = ShizukuManagerAppProbe { true },
                        ioDispatcher = StandardTestDispatcher(testScheduler),
                    )
                manager.start()
                val first = manager.holdBinding()
                runCurrent()
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
            fixture.start()
            fixture
                .holdBinding()
                .apply { fixture.facade.deliverServiceConnected() }
                .close()
            advanceTimeBy(LINGER_MS + 1)
            val holder = fixture.holdBinding()

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
            fixture.start()
            fixture
                .holdBinding()
                .apply { fixture.facade.deliverServiceConnected() }
                .close()
            advanceTimeBy(LINGER_MS + 1)
            val holder = fixture.holdBinding()

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
            fixture.start()
            fixture
                .holdBinding()
                .apply { fixture.facade.deliverServiceConnected() }
                .close()
            advanceTimeBy(LINGER_MS + 1)
            val holder = fixture.holdBinding()

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
            fixture.start()
            fixture
                .holdBinding()
                .apply { fixture.facade.deliverServiceConnected() }
                .close()
            advanceTimeBy(LINGER_MS + 1)
            val holder = fixture.holdBinding()
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
            fixture.start()
            fixture
                .holdBinding()
                .apply { fixture.facade.deliverServiceConnected() }
                .close()
            advanceTimeBy(LINGER_MS + 1)

            val holder = fixture.holdBinding()
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
            fixture.start()
            fixture
                .holdBinding()
                .apply { fixture.facade.deliverServiceConnected() }
                .close()
            advanceTimeBy(LINGER_MS + 1)

            val holder = fixture.holdBinding()
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
            fixture.start()
            fixture
                .holdBinding()
                .apply { fixture.facade.deliverServiceConnected() }
                .close()
            advanceTimeBy(LINGER_MS + 1)
            val holder = fixture.holdBinding()
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
            fixture.start()
            fixture
                .holdBinding()
                .apply { fixture.facade.deliverServiceConnected() }
                .close()
            advanceTimeBy(LINGER_MS + 1)
            val holder = fixture.holdBinding()
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
            fixture.start()
            fixture
                .holdBinding()
                .apply { fixture.facade.deliverServiceConnected(LiveBinder()) }
                .close()
            advanceTimeBy(LINGER_MS + 1)
            val holder = fixture.holdBinding()
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
            fixture.start()
            fixture
                .holdBinding()
                .apply { fixture.facade.deliverServiceConnected(LiveBinder()) }
                .close()
            advanceTimeBy(LINGER_MS + 1)
            val holder = fixture.holdBinding()
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
            fixture.start()
            val holder = fixture.holdBinding()
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

    // N-11: an unexpected death leaves the dying stub cached exactly like an unbind does, until Shizuku's died()
    // clears and evicts it after the callback. A bind in between joins that stub and its connect reaches nobody.
    @Test
    fun anUnexpectedServiceDeathDefersBindsUntilShizukusCleanupAndRebindsTheHolder() =
        runTest {
            val fixture = Fixture(this)
            fixture.start()
            val holder = fixture.holdBinding()
            fixture.facade.deliverServiceConnected(LiveBinder())

            fixture.facade.deliverServiceDied {
                // A LiveTelemetry poll on IO wins bindingLock between the callback and Shizuku's cleanup.
                fixture.manager.holdBinding().close()
                assertEquals("no bind may join the dying stub", 1, fixture.facade.bindCalls)
            }
            assertEquals("settling must be posted, not run inside the death callback", 1, fixture.facade.bindCalls)
            runCurrent()
            fixture.facade.deliverServiceConnected(LiveBinder())

            assertEquals("the remaining holder is rebound once", 2, fixture.facade.bindCalls)
            assertNotNull("the rebound service reaches the manager", fixture.manager.telemetryService.value)
            assertEquals("ok", fixture.manager.withTelemetryService(1_000L) { "ok" })
            holder.close()
            advanceUntilIdle()
        }

    @Test
    fun anUnexpectedServiceDeathWithNoUserOrHolderLeftDoesNotRebind() =
        runTest {
            val fixture = Fixture(this)
            fixture.start()
            fixture
                .holdBinding()
                .apply { fixture.facade.deliverServiceConnected(LiveBinder()) }
                .close() // lingering

            fixture.facade.deliverServiceDied()
            advanceUntilIdle()

            assertEquals(1, fixture.facade.bindCalls)
            assertEquals("nothing is left to unbind", 0, fixture.facade.unbindCalls)
            assertNull(fixture.manager.telemetryService.value)
        }

    // N-11, second trigger: the old process's owed death arrives before the fallback-bound service connected.
    @Test
    fun anOwedDeathBeforeTheFallbackBindConnectedRebindsTheHolderOnAFreshStub() =
        runTest {
            val fixture = Fixture(this)
            fixture.start()
            fixture
                .holdBinding()
                .apply { fixture.facade.deliverServiceConnected(LiveBinder()) }
                .close()
            advanceTimeBy(LINGER_MS + 1)
            val holder = fixture.holdBinding()
            advanceTimeBy(SETTLE_TIMEOUT_MS + 1)
            assertEquals("the fallback bind shares the old process's stub", 2, fixture.facade.bindCalls)

            fixture.facade.deliverServiceDied() // the shared stub's only died(): its connect will never come
            runCurrent()
            fixture.facade.deliverServiceConnected(LiveBinder())

            assertEquals("the holder is rebound on a fresh stub", 3, fixture.facade.bindCalls)
            assertNotNull(fixture.manager.telemetryService.value)
            holder.close()
            advanceUntilIdle()
        }

    // The binder-died backstop (F-17) has the same window: the old process's late death spends the shared stub, and
    // the new process dies before Shizuku has cleared and evicted that stub.
    @Test
    fun aSpentStubBindingThatDiesDuringTheStubsCleanupWaitsForItBeforeRebinding() =
        runTest {
            val fixture = Fixture(this)
            fixture.start()
            fixture
                .holdBinding()
                .apply { fixture.facade.deliverServiceConnected(LiveBinder()) }
                .close()
            advanceTimeBy(LINGER_MS + 1)
            val holder = fixture.holdBinding()
            advanceTimeBy(SETTLE_TIMEOUT_MS + 1)
            val newService = LiveBinder()
            fixture.facade.deliverServiceConnected(newService)

            fixture.facade.deliverServiceDied {
                newService.kill() // only the manager's own link reports it: the stub is spent
                assertNull(fixture.manager.telemetryService.value)
                fixture.manager.holdBinding().close()
                assertEquals("no bind may join the stub being cleared", 2, fixture.facade.bindCalls)
            }
            runCurrent()
            fixture.facade.deliverServiceConnected(LiveBinder())

            assertEquals("the holder is rebound on a fresh stub", 3, fixture.facade.bindCalls)
            assertNotNull(fixture.manager.telemetryService.value)
            holder.close()
            advanceUntilIdle()
        }

    @Test
    fun realShizukuUnexpectedDeathRebindsTheHolderOnAFreshWrapper() =
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
                        managerApp = ShizukuManagerAppProbe { true },
                        ioDispatcher = StandardTestDispatcher(testScheduler),
                    )
                manager.start()
                val holder = manager.holdBinding()
                runCurrent()
                val wrapper = facade.boundWrappers.single()
                facade.deliverConnected(wrapper, LiveBinder())

                main.runCallback { facade.deliverDeathCallback(wrapper) } // the service process is killed
                assertEquals("no bind inside the real library callback", 1, facade.boundWrappers.size)
                runCurrent()

                assertEquals(2, facade.boundWrappers.size)
                val fresh = facade.boundWrappers.last()
                assertNotSame("cleanup must finish before choosing a cached wrapper", wrapper, fresh)
                val freshBinder = LiveBinder()
                facade.deliverConnected(fresh, freshBinder)
                assertSame(freshBinder, manager.telemetryService.value?.asBinder())
                holder.close()
                manager.stop()
                advanceUntilIdle()
            }
        }

    @Test
    fun stopDuringAnUnexpectedDeathsFenceDoesNotRebind() =
        runTest {
            val fixture = Fixture(this)
            fixture.start()
            val holder = fixture.holdBinding()
            fixture.facade.deliverServiceConnected(LiveBinder())

            fixture.facade.deliverServiceDied()
            fixture.manager.stop()
            advanceUntilIdle()

            assertEquals("stop() leaves the service unbound", 1, fixture.facade.bindCalls)
            assertNull(fixture.manager.telemetryService.value)
            holder.close()
        }

    @Test
    fun aDaemonDeathDuringAnUnexpectedDeathsFenceClearsItWithoutRebinding() =
        runTest {
            val fixture = Fixture(this)
            fixture.start()
            val holder = fixture.holdBinding()
            fixture.facade.deliverServiceConnected(LiveBinder())

            fixture.facade.deliverServiceDied()
            fixture.facade.binderAlive = false
            fixture.facade.deliverBinderDeath()
            advanceUntilIdle()
            assertEquals("no bind against a dead daemon", 1, fixture.facade.bindCalls)
            assertSame(ShizukuState.NotRunning, fixture.manager.state.value)

            fixture.facade.binderAlive = true
            fixture.deliverBinderReceived() // Shizuku is back
            runCurrent()
            assertEquals("the cleared fence lets the holder bind at once", 2, fixture.facade.bindCalls)
            fixture.facade.deliverServiceConnected(LiveBinder())
            assertNotNull(fixture.manager.telemetryService.value)
            holder.close()
            advanceUntilIdle()
        }

    @Test
    fun aServiceDeathHandledAfterTheDaemonDiedDoesNotBindAgainstTheDeadDaemon() =
        runTest {
            val fixture = Fixture(this)
            fixture.start()
            val holder = fixture.holdBinding()
            fixture.facade.deliverServiceConnected(LiveBinder())

            fixture.facade.binderAlive = false
            fixture.facade.deliverBinderDeath()
            fixture.facade.deliverServiceDied() // the user service's own death notice comes after the daemon's
            advanceUntilIdle()

            assertEquals("the settle must not bind while Shizuku is not running", 1, fixture.facade.bindCalls)
            assertSame(ShizukuState.NotRunning, fixture.manager.state.value)
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
                        managerApp = ShizukuManagerAppProbe { true },
                        ioDispatcher = StandardTestDispatcher(testScheduler),
                    )
                manager.start()
                val first = manager.holdBinding()
                runCurrent()
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
            fixture.start()
            val holder = fixture.holdBinding()
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
            fixture.start()
            fixture
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
            fixture.start()
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
            fixture.start()
            val holder = fixture.holdBinding()
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
            fixture.start()
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
            fixture.start()
            val first = fixture.holdBinding()
            fixture.facade.deliverServiceConnected()

            first.close()
            advanceTimeBy(LINGER_MS / 2)
            val second = fixture.holdBinding()
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
            fixture.start()
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
                    managerApp = ShizukuManagerAppProbe { true },
                )
            manager.start()
            val bound = CountDownLatch(1)
            facade.onBind = { bound.countDown() }
            val holder = manager.holdBinding()
            assertTrue("the bind is issued on IO", bound.await(5, TimeUnit.SECONDS))
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

    // R-08: bindUserService/unbindUserService are two-way binder calls into the Shizuku server. Main callers
    // (Cockpit, Health, MainActivity) must neither run one nor wait for a lock held across one.
    @Test
    fun holdBindingIssuesTheBindOffTheCallersThread() {
        val mainThread = Executors.newSingleThreadExecutor { Thread(it, "main-sim") }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val facade = FakeShizukuFacade()
            val manager =
                ShizukuManagerImpl(
                    context = TestContext(),
                    facade = facade,
                    scope = scope,
                    applicationInfoProvider = ShizukuApplicationInfoProvider { ShizukuApplicationInfo(42, true) },
                    managerApp = ShizukuManagerAppProbe { true },
                )
            manager.start()
            val bound = CountDownLatch(1)
            var bindThread: String? = null
            facade.onBind = {
                bindThread = Thread.currentThread().name
                bound.countDown()
            }

            val holder = mainThread.submit<AutoCloseable> { manager.holdBinding() }.get(5, TimeUnit.SECONDS)

            assertTrue(bound.await(5, TimeUnit.SECONDS))
            assertNotEquals("the bind must not run on the caller's (main) thread", "main-sim", bindThread)
            holder.close()
            manager.stop()
        } finally {
            scope.cancel()
            mainThread.shutdownNow()
        }
    }

    @Test
    fun noLockIsHeldWhileABindIsInFlight() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val inFlight = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            val facade = FakeShizukuFacade()
            val manager =
                ShizukuManagerImpl(
                    context = TestContext(),
                    facade = facade,
                    scope = scope,
                    applicationInfoProvider = ShizukuApplicationInfoProvider { ShizukuApplicationInfo(42, true) },
                    managerApp = ShizukuManagerAppProbe { true },
                )
            manager.start()
            facade.onBind = {
                inFlight.countDown()
                release.await(60, TimeUnit.SECONDS) // a slow Shizuku server, released only after the checks
            }
            val callerDone = CountDownLatch(1)
            val caller =
                Thread {
                    manager.holdBinding()
                    callerDone.countDown()
                }.apply { start() }
            assertTrue(inFlight.await(10, TimeUnit.SECONDS))
            val bindingLock =
                ShizukuManagerImpl::class.java
                    .getDeclaredField("bindingLock")
                    .apply { isAccessible = true }
                    .get(manager)

            // Another Main entry while the server is slow: a second holder, and a bare lock acquisition.
            val otherDone = CountDownLatch(1)
            val other =
                Thread {
                    manager.holdBinding().close()
                    synchronized(bindingLock) {}
                    otherDone.countDown()
                }.apply { start() }
            // Hang-only bounds: both finish at once unless they wait for the held bind.
            val callerBlocked = !callerDone.await(10, TimeUnit.SECONDS)
            val otherBlocked = !otherDone.await(10, TimeUnit.SECONDS)
            release.countDown()
            other.join(10_000L)
            caller.join(10_000L)

            assertTrue("holdBinding must return before the bind transaction does", !callerBlocked)
            assertTrue("no thread may wait for bindingLock while a bind is in flight", !otherBlocked)
            assertEquals(1, facade.bindCalls)
            manager.stop()
        } finally {
            release.countDown()
            scope.cancel()
        }
    }

    @Test
    fun aHolderArrivingDuringAnIdleUnbindTransactionEndsBound() =
        runTest {
            val fixture = Fixture(this)
            fixture.start()
            fixture
                .holdBinding()
                .apply { fixture.facade.deliverServiceConnected(LiveBinder()) }
                .close()
            var arrived: AutoCloseable? = null
            fixture.facade.onUnbind = {
                fixture.facade.onUnbind = {}
                // Main opens Cockpit while the idle unbind's removeUserService is still in flight on IO.
                arrived = fixture.onAnotherThread { fixture.manager.holdBinding() }
            }

            advanceTimeBy(LINGER_MS + 1)

            val holder = arrived
            assertNotNull("holdBinding must not wait for the unbind transaction", holder)
            assertEquals(1, fixture.facade.unbindCalls)
            assertEquals("the bind waits for the unbound process's death (SQ-114)", 1, fixture.facade.bindCalls)
            fixture.facade.deliverServiceDied()
            runCurrent()
            assertEquals(2, fixture.facade.bindCalls)
            fixture.facade.deliverServiceConnected(LiveBinder())
            assertNotNull("the late holder ends bound", fixture.manager.telemetryService.value)
            assertEquals("ok", fixture.manager.withTelemetryService(1_000L) { "ok" })
            holder?.close()
            advanceUntilIdle()
        }

    @Test
    fun aBindStillQueuedWhenItsBindingEndsIsNeverIssued() =
        runTest {
            val fixture = Fixture(this)
            fixture.start()

            val holder = fixture.manager.holdBinding() // the drainer has not run yet
            fixture.manager.stop()
            advanceUntilIdle()

            assertEquals(0, fixture.facade.bindCalls)
            assertEquals("nothing reached Shizuku, so there is nothing to remove", 0, fixture.facade.unbindCalls)
            holder.close()
        }

    @Test
    fun aBindFailingOnIoStillReportsTransportLoss() =
        runTest {
            val fixture = Fixture(this)
            fixture.start()
            fixture.facade.bindFailure = true

            val holder = fixture.manager.holdBinding()
            assertEquals("the caller only decides", ShizukuState.Ready(2000, 13), fixture.manager.state.value)
            runCurrent()

            assertSame(ShizukuState.NotRunning, fixture.manager.state.value)
            holder.close()
        }

    // Every read runs on IO (R-08, B-10), so the app-open refresh, Shizuku's listeners and a Grant tap can overlap.
    @Test
    fun anOlderRefreshFinishingLastCannotOverwriteANewerState() {
        ThreadedFixture().use { fixture ->
            fixture.manager.start()
            fixture.awaitState(ShizukuState.Ready(2000, 13))
            val readGranted = CountDownLatch(1)
            val resume = CountDownLatch(1)
            fixture.facade.onCheckSelfPermission = {
                fixture.facade.onCheckSelfPermission = {}
                readGranted.countDown()
                resume.await(10, TimeUnit.SECONDS)
            }
            val slow = fixture.scope.async { fixture.manager.refresh() }
            assertTrue(readGranted.await(5, TimeUnit.SECONDS))

            // Access is revoked and a newer refresh publishes that while the older one is still between its reads.
            fixture.facade.permissionResult = PackageManager.PERMISSION_DENIED
            runBlocking { fixture.manager.refresh() }
            resume.countDown()
            runBlocking { withTimeout(10_000L) { slow.await() } }

            assertEquals(ShizukuState.PermissionRequired(), fixture.manager.state.value)
        }
    }

    // R-08 follow-up: a non-RuntimeException out of a bind must not leave the binding "bound" with no bind behind it
    // (bindIfRequiredLocked would return early forever) nor strand the queue.
    @Test
    fun aBindThrowingANonRuntimeFailureIsATransportLossAndBindingRecovers() =
        runTest {
            for (thrown in listOf(java.io.IOException("sneaky checked"), NoClassDefFoundError("linkage"))) {
                val fixture = Fixture(this)
                fixture.start()
                fixture.facade.bindThrowable = thrown

                val holder = fixture.holdBinding()
                assertSame("$thrown", ShizukuState.NotRunning, fixture.manager.state.value)

                fixture.facade.bindThrowable = null
                fixture.deliverBinderReceived() // Shizuku is back
                runCurrent()
                assertEquals("$thrown: the holder binds again", 2, fixture.facade.bindCalls)
                fixture.facade.deliverServiceConnected(LiveBinder())
                assertEquals("ok", fixture.manager.withTelemetryService(1_000L) { "ok" })
                holder.close()
                advanceUntilIdle()
            }
        }

    @Test
    fun aVirtualMachineErrorFromABindIsRethrownButLeavesTheManagerUsable() =
        runTest {
            val uncaught = mutableListOf<Throwable>()
            val scope =
                CoroutineScope(
                    SupervisorJob() + StandardTestDispatcher(testScheduler) +
                        CoroutineExceptionHandler { _, t -> uncaught += t },
                )
            val facade = FakeShizukuFacade().apply { bindThrowable = StackOverflowError() }
            val manager =
                ShizukuManagerImpl(
                    context = TestContext(),
                    facade = facade,
                    scope = scope,
                    applicationInfoProvider = ShizukuApplicationInfoProvider { ShizukuApplicationInfo(42, true) },
                    managerApp = ShizukuManagerAppProbe { true },
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                )
            manager.start()

            val holder = manager.holdBinding()
            runCurrent()

            assertTrue("not swallowed", uncaught.single() is StackOverflowError)
            assertSame(ShizukuState.NotRunning, manager.state.value)
            facade.bindThrowable = null
            facade.deliverBinderReceived()
            runCurrent()
            assertEquals("a fresh drainer issues the next bind", 2, facade.bindCalls)
            holder.close()
            manager.stop()
            scope.cancel()
        }

    // R-08 follow-up: an older read is in flight when the server restarts; the new binder's read publishes Ready and a
    // holder binds; then the older read fails.
    @Test
    fun anOlderRefreshFailingAfterANewerReadyDoesNotTakeItDown() {
        ThreadedFixture().use { fixture ->
            fixture.manager.start()
            fixture.awaitState(ShizukuState.Ready(2000, 13))
            val inFlight = CountDownLatch(1)
            val resume = CountDownLatch(1)
            fixture.facade.onCheckSelfPermission = {
                fixture.facade.onCheckSelfPermission = {}
                inFlight.countDown()
                resume.await(60, TimeUnit.SECONDS)
                throw IllegalStateException("the server this read was talking to restarted")
            }
            val slow = fixture.scope.async { fixture.manager.refresh() }
            assertTrue(inFlight.await(10, TimeUnit.SECONDS))

            fixture.facade.serverUid = 2001 // the restarted server, told apart by its uid
            fixture.facade.deliverBinderReceived()
            fixture.awaitState(ShizukuState.Ready(2001, 13))
            val bound = CountDownLatch(1)
            fixture.facade.onBind = { bound.countDown() }
            val holder = fixture.manager.holdBinding()
            assertTrue(bound.await(10, TimeUnit.SECONDS))
            resume.countDown()
            runBlocking { withTimeout(30_000L) { slow.await() } }

            assertEquals(ShizukuState.Ready(2001, 13), fixture.manager.state.value)
            fixture.facade.deliverServiceConnected(LiveBinder())
            assertNotNull("the newer Ready's binding is still in place", fixture.manager.telemetryService.value)
            assertEquals(1, fixture.facade.bindCalls)
            holder.close()
        }
    }

    // B-10: a state read is binder calls into the Shizuku server (pingBinder, checkSelfPermission, the rationale flag,
    // getUid), and so is the dialog request. start() (Application.onCreate), Shizuku's two listeners (which it calls on
    // Main) and requestPermission() (a Grant tap) must all return while the server is wedged.
    @Test
    fun mainEntryPointsReturnWhileTheShizukuServerIsWedged() {
        val mainThread = Executors.newSingleThreadExecutor { Thread(it, "main-sim") }
        val wedge = CountDownLatch(1)
        try {
            ThreadedFixture().use { fixture ->
                fixture.facade.permissionResult = PackageManager.PERMISSION_DENIED
                val pinged = CountDownLatch(1)
                fixture.facade.onServerCall = { call ->
                    if (call == "pingBinder") {
                        pinged.countDown()
                        wedge.await(60, TimeUnit.SECONDS)
                    }
                }
                val returned = CountDownLatch(1)
                mainThread.execute {
                    fixture.manager.start()
                    fixture.facade.deliverBinderReceived()
                    fixture.facade.deliverPermissionResult(PackageManager.PERMISSION_DENIED)
                    fixture.manager.requestPermission()
                    returned.countDown()
                }

                // Hang-only bound: Main returns at once unless it waits for the wedged server.
                assertTrue("Main must not wait for the Shizuku server", returned.await(10, TimeUnit.SECONDS))
                assertTrue("the reads reach the server off Main", pinged.await(10, TimeUnit.SECONDS))
                assertSame("nothing is published before the server answers", ShizukuState.NotRunning, fixture.manager.state.value)

                val dialogAsked = CountDownLatch(1)
                fixture.facade.onServerCall = { call -> if (call == "requestPermission") dialogAsked.countDown() }
                wedge.countDown()
                assertTrue("the Grant tap still asks once the server answers", dialogAsked.await(10, TimeUnit.SECONDS))
                fixture.awaitState(ShizukuState.PermissionRequired())
            }
        } finally {
            wedge.countDown()
            mainThread.shutdownNow()
        }
    }

    @Test
    fun noShizukuServerCallRunsOnTheCallersThread() {
        val mainThread = Executors.newSingleThreadExecutor { Thread(it, "main-sim") }
        val calls = ConcurrentLinkedQueue<Pair<String, String>>()
        val record = { call: String -> calls += call to Thread.currentThread().name }
        val managerAppProbed = CountDownLatch(1)
        val dialogAsked = CountDownLatch(1)

        fun onMain(entry: () -> Unit) {
            mainThread.submit(Runnable { entry() }).get(10, TimeUnit.SECONDS)
        }
        try {
            ThreadedFixture(
                managerApp =
                    ShizukuManagerAppProbe {
                        record("managerApp.isInstalled")
                        managerAppProbed.countDown()
                        true
                    },
                applicationInfoProvider =
                    ShizukuApplicationInfoProvider {
                        record("ownApplicationInfo")
                        ShizukuApplicationInfo(42, true)
                    },
            ).use { fixture ->
                fixture.facade.onServerCall = { call ->
                    record(call)
                    if (call == "requestPermission") dialogAsked.countDown()
                }
                // Cold process: no binder yet, so start()'s read also asks PackageManager for the manager app.
                fixture.facade.binderAlive = false
                fixture.facade.permissionResult = PackageManager.PERMISSION_DENIED
                onMain { fixture.manager.start() }
                assertTrue(managerAppProbed.await(10, TimeUnit.SECONDS))

                fixture.facade.binderAlive = true
                onMain { fixture.facade.deliverBinderReceived() }
                fixture.awaitState(ShizukuState.PermissionRequired())

                onMain { fixture.manager.requestPermission() }
                assertTrue(dialogAsked.await(10, TimeUnit.SECONDS))

                fixture.facade.permissionResult = PackageManager.PERMISSION_GRANTED
                onMain { fixture.facade.deliverPermissionResult(PackageManager.PERMISSION_GRANTED) }
                fixture.awaitState(ShizukuState.Ready(2000, 13))
            }

            val onCaller = calls.filter { (_, thread) -> thread == "main-sim" }
            assertTrue("Shizuku server and PackageManager calls on the caller's thread: $onCaller", onCaller.isEmpty())
            val made = calls.map { (call, _) -> call }.toSet()
            val expected =
                setOf(
                    "pingBinder",
                    "managerApp.isInstalled",
                    "getVersion",
                    "checkSelfPermission",
                    "shouldShowRequestPermissionRationale",
                    "requestPermission",
                    "getUid",
                    "ownApplicationInfo",
                )
            assertTrue("every entry point's calls were observed: $made", made.containsAll(expected))
        } finally {
            mainThread.shutdownNow()
        }
    }

    // B-10: Shizuku's listeners now read on IO, so a read can still be running when stop() returns.
    @Test
    fun aReadStillRunningAcrossStopPublishesNothing() {
        ThreadedFixture().use { fixture ->
            fixture.manager.start()
            fixture.awaitState(ShizukuState.Ready(2000, 13))
            val inFlight = CountDownLatch(1)
            val resume = CountDownLatch(1)
            fixture.facade.onCheckSelfPermission = {
                fixture.facade.onCheckSelfPermission = {}
                inFlight.countDown()
                resume.await(10, TimeUnit.SECONDS)
            }
            fixture.facade.permissionResult = PackageManager.PERMISSION_DENIED
            fixture.facade.deliverPermissionResult(PackageManager.PERMISSION_DENIED)
            assertTrue(inFlight.await(10, TimeUnit.SECONDS))

            fixture.manager.stop()
            resume.countDown()
            fixture.awaitLaunched()

            assertEquals("a stopped manager publishes nothing", ShizukuState.Ready(2000, 13), fixture.manager.state.value)
        }
    }

    @Test
    fun aPermissionRequestThatFailsOnTheBinderIsATransportLoss() =
        runTest {
            val fixture = Fixture(this).apply { facade.permissionResult = PackageManager.PERMISSION_DENIED }
            fixture.start()
            assertEquals(ShizukuState.PermissionRequired(), fixture.manager.state.value)
            fixture.facade.onServerCall = { call ->
                if (call == "requestPermission") throw IllegalStateException("binder haven't been received")
            }

            fixture.requestPermission()

            assertSame(ShizukuState.NotRunning, fixture.manager.state.value)
        }

    // B-10: the dialog request runs on IO, where a server restart can be handled while it is in flight. Its failure
    // then belongs to the dead server, as a drainer transaction's does, and must not take the new one down.
    @Test
    fun aPermissionRequestFailingAfterTheServerRestartedLeavesTheNewServerReady() {
        ThreadedFixture().use { fixture ->
            fixture.facade.permissionResult = PackageManager.PERMISSION_DENIED
            fixture.manager.start()
            fixture.awaitState(ShizukuState.PermissionRequired())
            fixture.facade.onServerCall = { call ->
                if (call == "requestPermission") {
                    fixture.facade.onServerCall = {}
                    // The server dies with the request in flight, and its successor (already granted) is read as Ready.
                    fixture.facade.deliverBinderDeath()
                    fixture.facade.permissionResult = PackageManager.PERMISSION_GRANTED
                    runBlocking { fixture.manager.refresh() }
                    throw IllegalStateException("the server this request was sent to died")
                }
            }

            fixture.manager.requestPermission()
            fixture.awaitLaunched()

            assertEquals(ShizukuState.Ready(2000, 13), fixture.manager.state.value)
        }
    }

    private companion object {
        /** Mirrors the manager's idle linger and unbind-settle timeout (literals, so the test reads standalone). */
        const val LINGER_MS = 3_000L
        const val SETTLE_TIMEOUT_MS = 2_000L
    }

    private class Fixture(
        private val scope: TestScope,
        managerInstalled: Boolean = true,
        applicationInfoProvider: ShizukuApplicationInfoProvider = ShizukuApplicationInfoProvider { ShizukuApplicationInfo(42, true) },
    ) {
        val facade = FakeShizukuFacade()

        /** holdBinding, then lets the IO drainer issue whatever bind it decided (R-08: never on the caller). */
        fun holdBinding(): AutoCloseable = manager.holdBinding().also { scope.runCurrent() }

        /** start, then lets the first state read it started on IO publish (B-10: never on the caller). */
        fun start() {
            manager.start()
            scope.runCurrent()
        }

        /** requestPermission, then lets its re-read and dialog request run on IO (B-10). */
        fun requestPermission() {
            manager.requestPermission()
            scope.runCurrent()
        }

        /** Shizuku's permission-result callback (on Main), then the re-read it started on IO (B-10). */
        fun deliverPermissionResult(result: Int) {
            facade.deliverPermissionResult(result)
            scope.runCurrent()
        }

        /** Shizuku's binder-received callback (on Main), then the re-read it started on IO (B-10). */
        fun deliverBinderReceived() {
            facade.deliverBinderReceived()
            scope.runCurrent()
        }

        /** Runs [block] on another thread and returns its result, or null if it is still blocked after 10 s. */
        fun <T : Any> onAnotherThread(block: () -> T): T? {
            var result: T? = null
            val done = CountDownLatch(1)
            Thread {
                result = block()
                done.countDown()
            }.start()
            return if (done.await(10, TimeUnit.SECONDS)) result else null // a hang-only bound
        }
        val manager =
            ShizukuManagerImpl(
                context = TestContext(),
                facade = facade,
                scope = scope,
                applicationInfoProvider = applicationInfoProvider,
                managerApp = ShizukuManagerAppProbe { managerInstalled },
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
     * A manager whose reads, requests and binds run on real IO threads (its default dispatcher), so they overlap the
     * way they do on a device. Settle posts still go to the test's Main dispatcher.
     */
    private class ThreadedFixture(
        managerApp: ShizukuManagerAppProbe = ShizukuManagerAppProbe { true },
        applicationInfoProvider: ShizukuApplicationInfoProvider = ShizukuApplicationInfoProvider { ShizukuApplicationInfo(42, true) },
    ) : AutoCloseable {
        val facade = FakeShizukuFacade()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager =
            ShizukuManagerImpl(
                context = TestContext(),
                facade = facade,
                scope = scope,
                applicationInfoProvider = applicationInfoProvider,
                managerApp = managerApp,
            )

        fun awaitState(expected: ShizukuState) {
            runBlocking { withTimeout(10_000L) { manager.state.first { it == expected } } }
        }

        /** Waits for every coroutine the manager has launched so far (reads, requests, drains) to end. */
        fun awaitLaunched() {
            runBlocking { withTimeout(10_000L) { scope.coroutineContext.job.children.toList().forEach { it.join() } } }
        }

        override fun close() {
            manager.stop()
            scope.cancel()
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

        /** ShizukuProvider took Sui's binder (Sui.isSui()). */
        var sui = false
        var serverUid = 2000
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

        /** Thrown by the next binds as is: lets a test throw what is not a RuntimeException. */
        @Volatile var bindThrowable: Throwable? = null

        @Volatile var bindCalls = 0

        @Volatile var unbindCalls = 0
        var lastUnbindRemove = false

        /** Runs inside each bind/unbind transaction (after it registered), i.e. while the call is in flight. */
        @Volatile var onBind: () -> Unit = {}

        @Volatile var onUnbind: () -> Unit = {}

        /** Runs after checkSelfPermission has read its answer, before the manager can publish it. */
        @Volatile var onCheckSelfPermission: () -> Unit = {}

        /**
         * Runs first in each call that reaches the Shizuku server (every facade call but the local listener
         * registrations), with the call's name; it may block (a wedged server) or throw (a dead binder).
         */
        @Volatile var onServerCall: (String) -> Unit = {}
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

        override fun pingBinder(): Boolean {
            onServerCall("pingBinder")
            return binderAlive
        }

        override fun isSui(): Boolean {
            onServerCall("isSui")
            return sui
        }

        override fun getUid(): Int {
            onServerCall("getUid")
            return serverUid
        }

        override fun getVersion(): Int {
            onServerCall("getVersion")
            return serverVersion
        }

        override fun isPreV11(): Boolean {
            onServerCall("isPreV11")
            return preV11
        }

        override fun checkSelfPermission(): Int {
            onServerCall("checkSelfPermission")
            return permissionResult.also { onCheckSelfPermission() }
        }

        override fun shouldShowRequestPermissionRationale(): Boolean {
            onServerCall("shouldShowRequestPermissionRationale")
            return showPermissionRationale
        }

        override fun requestPermission(requestCode: Int) {
            onServerCall("requestPermission")
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
            onServerCall("bindUserService")
            bindCalls += 1
            if (bindFailure) error("daemon died during bind")
            bindThrowable?.let { throw it }
            val stub = cachedStub ?: ConnectionStub().also { cachedStub = it }
            stub.connections += connection
            boundStub = stub
            onBind()
        }

        override fun unbindUserService(
            args: Shizuku.UserServiceArgs,
            connection: ServiceConnection,
            remove: Boolean,
        ) {
            onServerCall("unbindUserService")
            unbindCalls += 1
            if (unbindFailure) error("daemon died during unbind")
            lastUnbindRemove = remove
            onUnbind()
        }

        fun deliverPermissionResult(result: Int) {
            permissionListener?.onRequestPermissionResult(1, result)
        }

        fun deliverBinderDeath() {
            binderDeadListener?.onBinderDead()
        }

        fun deliverBinderReceived() {
            binderReceivedListener?.onBinderReceived()
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
