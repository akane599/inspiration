package com.akane.inspiration.telemetry.toolkit

import android.os.DeadObjectException
import android.os.RemoteException
import com.akane.inspiration.ipc.ITelemetryUserService
import com.akane.inspiration.ipc.ShizukuManager
import com.akane.inspiration.ipc.ShizukuState
import com.akane.inspiration.ipc.model.BgDexoptReason
import com.akane.inspiration.ipc.model.BgDexoptState
import com.akane.inspiration.ipc.model.BgDexoptStatus
import com.akane.inspiration.ipc.model.ToolkitApi
import com.akane.inspiration.ipc.model.ToolkitCode
import com.akane.inspiration.ipc.model.ToolkitResult
import com.akane.inspiration.ipc.toolkit.AppOp
import com.akane.inspiration.ipc.toolkit.AppOpMode
import com.akane.inspiration.ipc.toolkit.DozeProfile
import com.akane.inspiration.ipc.toolkit.StandbyBucket
import com.akane.inspiration.telemetry.store.ToolkitActionRecord
import com.akane.inspiration.telemetry.store.ToolkitActionStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class ToolkitGatewayTest {
    private val sent = mutableListOf<String>()
    private var apiVersion = ToolkitApi.VERSION
    private var returnNull = false
    private var dexoptState = BgDexoptState.RUNNING
    private var dexoptReason = BgDexoptReason.NONE
    private var dexoptExit = 0
    private var dexoptEndedAtMs = 0L

    /** What every typed toolkit call answers (bg-dexopt status aside). */
    private var serviceResult: ToolkitResult = ToolkitResult.done(after = "x")

    // Throwable: a dead binder is a checked RemoteException, which the AIDL methods declare.
    private var serviceThrows: Throwable? = null
    private var apiVersionThrows: Throwable? = null

    /** A service stand-in built with a dynamic proxy: the Binder stub cannot load in plain JVM tests. */
    private val service: ITelemetryUserService =
        Proxy.newProxyInstance(
            ITelemetryUserService::class.java.classLoader,
            arrayOf(ITelemetryUserService::class.java),
        ) { _, method, args ->
            if (method.name == "getApiVersion") {
                apiVersionThrows?.let { throw it }
                return@newProxyInstance apiVersion
            }
            serviceThrows?.let { throw it }
            sent += method.name + (args?.joinToString(prefix = "(", postfix = ")") { arg -> "$arg" } ?: "()")
            when {
                method.name == "getBgDexoptStatus" -> BgDexoptStatus(dexoptState, 1L, dexoptEndedAtMs, dexoptExit, dexoptReason)
                returnNull -> null
                else -> serviceResult
            }
        } as ITelemetryUserService

    private class FakeShizuku(
        private val service: ITelemetryUserService,
    ) : ShizukuManager {
        var available = true
        var holds = 0
        var openHolds = 0
        override val state: StateFlow<ShizukuState> = MutableStateFlow(ShizukuState.Ready(2000, 13))
        override val telemetryService: StateFlow<ITelemetryUserService?> = MutableStateFlow(service)

        override fun requestPermission() = Unit

        override fun start() = Unit

        /** Binding holders alive at the moment each call's service use ended (0 would unbind and destroy it). */
        val holdersWhenCallEnded = mutableListOf<Int>()

        override suspend fun <T> withTelemetryService(
            timeoutMs: Long,
            block: suspend (ITelemetryUserService) -> T,
        ): T? {
            if (!available) return null
            // As ShizukuManagerImpl: a RemoteException from the session (a binder that died on the API check or
            // during the typed call) ends it with null, like a lost answer; the gateway never sees the exception.
            return try {
                when {
                    // The call ran, then its answer was dropped (a rebind meanwhile, or a binder that died mid-call).
                    dropAnswer -> block(service).let { null }

                    else -> block(service).also { holdersWhenCallEnded += openHolds }
                }
            } catch (_: RemoteException) {
                null
            }
        }

        var dropAnswer = false

        override fun holdBinding(): AutoCloseable {
            holds += 1
            openHolds += 1
            var closed = false
            return AutoCloseable {
                if (!closed) {
                    closed = true
                    openHolds -= 1
                }
            }
        }

        override fun stop() = Unit
    }

    private val shizuku = FakeShizuku(service)
    private val gateway = ToolkitGateway(shizuku)

    @Test
    fun `typed calls send int codes from the enums`() =
        runTest {
            gateway.setAppOp("com.example.app", AppOp.WAKE_LOCK, AppOpMode.IGNORE)
            gateway.setStandbyBucket("com.example.app", StandbyBucket.RESTRICTED)
            gateway.applyDozeProfile(DozeProfile.BALANCED, null)
            gateway.restoreDozeRaw("a=1", "b=2")
            gateway.forceStop("com.example.app")

            assertEquals(
                listOf(
                    "setAppOp(com.example.app, 3, 1)",
                    "setStandbyBucket(com.example.app, 5)",
                    "applyDozeProfile(1, null)",
                    "restoreDozeRaw(a=1, b=2)",
                    "forceStop(com.example.app)",
                ),
                sent,
            )
        }

    @Test
    fun `a missing service maps to Unavailable and nothing is sent`() =
        runTest {
            shizuku.available = false

            val reply = gateway.forceStop("com.example.app")

            assertEquals(GatewayReply.Unavailable(GatewayUnavailable.NO_SERVICE, sent = false), reply)
            assertTrue(sent.isEmpty())
        }

    @Test
    fun `a stale service api version is Unavailable and the write is never sent`() =
        runTest {
            apiVersion = 0

            assertEquals(
                GatewayReply.Unavailable(GatewayUnavailable.STALE_SERVICE, sent = false),
                gateway.setAppOp("com.example.app", AppOp.WAKE_LOCK, AppOpMode.IGNORE),
            )
            assertEquals(
                GatewayReply.Unavailable(GatewayUnavailable.STALE_SERVICE, sent = false),
                gateway.startBgDexopt(),
            )
            apiVersion = -1 // a service that rejected this caller
            assertEquals(GatewayReply.Unavailable(GatewayUnavailable.STALE_SERVICE, sent = false), gateway.getDozeState())
            assertTrue(sent.isEmpty())
            assertEquals(0, shizuku.holds)
        }

    @Test
    fun `an api check that throws is reported as never sent`() =
        runTest {
            apiVersionThrows = SecurityException("caller rejected")

            assertEquals(
                GatewayReply.Unavailable(GatewayUnavailable.STALE_SERVICE, sent = false),
                gateway.setStandbyBucket("com.example.app", StandbyBucket.RARE),
            )
            assertTrue(sent.isEmpty())
        }

    @Test
    fun `a write whose answer is lost after dispatch may have been sent`() =
        runTest {
            shizuku.dropAnswer = true

            val reply = gateway.setStandbyBucket("com.example.app", StandbyBucket.RARE)

            assertEquals(GatewayReply.Unavailable(GatewayUnavailable.NO_SERVICE, sent = true), reply)
            assertEquals(listOf("setStandbyBucket(com.example.app, 4)"), sent)
        }

    @Test
    fun `a binder that dies on the api check was never sent the write`() =
        runTest {
            apiVersionThrows = DeadObjectException()

            assertEquals(
                GatewayReply.Unavailable(GatewayUnavailable.NO_SERVICE, sent = false),
                gateway.setStandbyBucket("com.example.app", StandbyBucket.RARE),
            )
            assertTrue(sent.isEmpty())
        }

    @Test
    fun `a binder that dies during the typed call may have committed the write`() =
        runTest {
            serviceThrows = DeadObjectException()

            assertEquals(
                GatewayReply.Unavailable(GatewayUnavailable.NO_SERVICE, sent = true),
                gateway.setStandbyBucket("com.example.app", StandbyBucket.RARE),
            )
        }

    @Test
    fun `a null parcelable return maps to Unavailable`() =
        runTest {
            returnNull = true

            assertEquals(GatewayReply.Unavailable(GatewayUnavailable.STALE_SERVICE), gateway.getAppOps("com.example.app"))
        }

    @Test
    fun `a service that throws is not reported as unreachable`() =
        runTest {
            serviceThrows = SecurityException("caller rejected")

            assertEquals(GatewayReply.Unavailable(GatewayUnavailable.STALE_SERVICE), gateway.getAppOps("com.example.app"))
            serviceThrows = IllegalStateException("service-side failure")
            assertEquals(GatewayReply.Unavailable(GatewayUnavailable.STALE_SERVICE), gateway.getStandbyBucket("com.example.app"))
            // A throw from the transaction itself came after dispatch: a write may have committed before it.
            assertEquals(
                GatewayReply.Unavailable(GatewayUnavailable.STALE_SERVICE, sent = true),
                gateway.setStandbyBucket("com.example.app", StandbyBucket.RARE),
            )
        }

    @Test
    fun `a service-side refusal comes back as the service's reply, not as unavailable`() =
        runTest {
            serviceResult = ToolkitResult.refused(ToolkitCode.NOT_PLUGGED)

            val reply = gateway.getDozeState()

            assertEquals(GatewayReply.Reply(serviceResult), reply)
        }

    @Test
    fun `updated system app results require a warning`() {
        assertTrue(ToolkitResult.done(code = ToolkitCode.WARN_UPDATED_SYSTEM).requiresWarning)
        assertFalse(ToolkitResult.done().requiresWarning)
    }

    @Test
    fun `the binding is held while dexopt is running and released when it stops`() =
        runTest {
            gateway.startBgDexopt()
            assertEquals(1, shizuku.openHolds)
            assertEquals(
                "the hold exists before the start call's service use ends, so the idle unbind cannot kill the service",
                listOf(1),
                shizuku.holdersWhenCallEnded,
            )

            gateway.bgDexoptStatus()
            gateway.bgDexoptStatus()
            assertEquals("one hold however often status is read", 1, shizuku.holds)
            assertEquals(1, shizuku.openHolds)
            assertEquals(listOf(1, 1, 1), shizuku.holdersWhenCallEnded)

            dexoptState = BgDexoptState.FINISHED
            gateway.bgDexoptStatus()
            assertEquals(0, shizuku.openHolds)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `the in-session hold is only a bridge and lapses by itself when nothing renews it`() =
        runTest {
            val bridged = ToolkitGateway(shizuku, holdBridgeScope = backgroundScope)

            bridged.startBgDexopt()
            assertEquals(1, shizuku.openHolds)

            // each RUNNING read renews the bridge, so it outlives the first window while someone keeps asking
            advanceTimeBy(10_000)
            bridged.bgDexoptStatus()
            advanceTimeBy(10_000)
            runCurrent()
            assertEquals(1, shizuku.holds)
            assertEquals(1, shizuku.openHolds)

            // nobody asks again (the screen left and ToolkitActions' own supervised hold took over): it lapses
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(0, shizuku.openHolds)
        }

    @Test
    fun `gateway preserves completion evidence and durable settlement requires verified zero exit`() =
        runTest {
            dexoptState = BgDexoptState.FINISHED
            dexoptEndedAtMs = 2_000L
            for (reason in listOf(BgDexoptReason.NONE, BgDexoptReason.RESULT_NOT_SUCCESSFUL, 999)) {
                for (exit in listOf(0, 7)) {
                    dexoptReason = reason
                    dexoptExit = exit
                    assertEquals(
                        GatewayReply.Reply(BgDexoptStatus(dexoptState, 1L, 2_000L, exit, reason)),
                        gateway.bgDexoptStatus(),
                    )
                    val audit = FakeAudit()
                    val id = audit.insert(ToolkitActionRecord(startedAt = 1L, kind = "dexopt", op = "bg_dexopt", status = ToolkitActionStatus.PENDING))
                    // Recreated actions must settle an earlier process's durable PENDING row the same way.
                    val actions = ToolkitActions(GatewayBackend(gateway), audit, shizuku::holdBinding, supervisorScope = backgroundScope)
                    val status = actions.bgDexoptStatus().first() as com.akane.inspiration.telemetry.toolkit.BgDexoptStatus.Finished
                    val succeeded = reason == BgDexoptReason.NONE && exit == 0
                    assertEquals(exit, status.exitCode)
                    assertEquals(reason == BgDexoptReason.NONE, status.completionVerified)
                    assertEquals(succeeded, status.succeeded)
                    val row = audit.byId(id)
                    assertEquals(if (succeeded) ToolkitActionStatus.DONE else ToolkitActionStatus.FAILED, row.status)
                    assertEquals(exit, row.code)
                    assertEquals(2_000L, row.endedAt)
                    assertEquals("finished", row.afterRaw)
                    actions.reconcile()
                    assertEquals("terminal evidence is durable", row, audit.byId(id))
                    assertEquals(0, shizuku.openHolds)
                }
            }
        }

    @Test
    fun `gateway and durable rows preserve external versus local cancellation`() =
        runTest {
            dexoptState = BgDexoptState.CANCELLED
            dexoptEndedAtMs = 2_000L
            for ((reason, expected) in listOf(BgDexoptReason.CALLER to BgDexoptCancelReason.OTHER, BgDexoptReason.USER to BgDexoptCancelReason.USER)) {
                dexoptReason = reason
                val audit = FakeAudit()
                val id = audit.insert(ToolkitActionRecord(startedAt = 1L, kind = "dexopt", op = "bg_dexopt", status = ToolkitActionStatus.PENDING))
                val actions = ToolkitActions(GatewayBackend(gateway), audit, shizuku::holdBinding, supervisorScope = backgroundScope)

                assertEquals(
                    com.akane.inspiration.telemetry.toolkit.BgDexoptStatus.Cancelled(expected, 2_000L),
                    actions.bgDexoptStatus().first(),
                )
                assertEquals(ToolkitActionStatus.CANCELLED, audit.byId(id).status)
                assertEquals(reason, audit.byId(id).code)
                assertEquals(2_000L, audit.byId(id).endedAt)
                assertEquals(0, shizuku.openHolds)
            }
            assertFalse(sent.any { it.startsWith("cancelBgDexopt") })
        }

    @Test
    fun `a refused start takes no binding hold`() =
        runTest {
            val refusing =
                Proxy.newProxyInstance(
                    ITelemetryUserService::class.java.classLoader,
                    arrayOf(ITelemetryUserService::class.java),
                ) { _, method, _ ->
                    if (method.name == "getApiVersion") ToolkitApi.VERSION else ToolkitResult.refused(ToolkitCode.NOT_PLUGGED)
                } as ITelemetryUserService
            val manager = FakeShizuku(refusing)

            val reply = ToolkitGateway(manager).startBgDexopt()

            assertTrue(reply is GatewayReply.Reply && !reply.value.isDone)
            assertEquals(0, manager.holds)
        }

    @Test
    fun `status that is not running after a rebind holds nothing and cancel is forwarded`() =
        runTest {
            dexoptState = BgDexoptState.IDLE

            gateway.bgDexoptStatus()
            gateway.cancelBgDexopt()

            assertEquals(0, shizuku.holds)
            assertEquals(listOf("getBgDexoptStatus()", "cancelBgDexopt()"), sent)
        }
}
