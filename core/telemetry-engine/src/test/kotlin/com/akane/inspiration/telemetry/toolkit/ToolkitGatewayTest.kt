package com.akane.inspiration.telemetry.toolkit

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
import java.lang.reflect.Proxy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolkitGatewayTest {
    private val sent = mutableListOf<String>()
    private var apiVersion = ToolkitApi.VERSION
    private var returnNull = false
    private var dexoptState = BgDexoptState.RUNNING

    /** A service stand-in built with a dynamic proxy: the Binder stub cannot load in plain JVM tests. */
    private val service: ITelemetryUserService = Proxy.newProxyInstance(
        ITelemetryUserService::class.java.classLoader,
        arrayOf(ITelemetryUserService::class.java),
    ) { _, method, args ->
        if (method.name == "getApiVersion") return@newProxyInstance apiVersion
        sent += method.name + (args?.joinToString(prefix = "(", postfix = ")") { arg -> "$arg" } ?: "()")
        when {
            method.name == "getBgDexoptStatus" -> BgDexoptStatus(dexoptState, 1L, 0L, 0, BgDexoptReason.NONE)
            returnNull -> null
            else -> ToolkitResult.done(after = "x")
        }
    } as ITelemetryUserService

    private class FakeShizuku(private val service: ITelemetryUserService) : ShizukuManager {
        var available = true
        var holds = 0
        var openHolds = 0
        override val state: StateFlow<ShizukuState> = MutableStateFlow(ShizukuState.Ready(2000, 13))
        override val telemetryService: StateFlow<ITelemetryUserService?> = MutableStateFlow(service)

        override fun requestPermission() = Unit

        override fun start() = Unit

        /** Binding holders alive at the moment each call's service use ended (0 would unbind and destroy it). */
        val holdersWhenCallEnded = mutableListOf<Int>()

        override suspend fun <T> withTelemetryService(timeoutMs: Long, block: suspend (ITelemetryUserService) -> T): T? =
            if (available) block(service).also { holdersWhenCallEnded += openHolds } else null

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
    fun `typed calls send int codes from the enums`() = runTest {
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
    fun `a missing service maps to Unavailable and nothing is sent`() = runTest {
        shizuku.available = false

        val reply = gateway.forceStop("com.example.app")

        assertEquals(GatewayReply.Unavailable(GatewayUnavailable.NO_SERVICE), reply)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `a stale service api version is Unavailable and the write is never sent`() = runTest {
        apiVersion = 0

        assertEquals(
            GatewayReply.Unavailable(GatewayUnavailable.STALE_SERVICE),
            gateway.setAppOp("com.example.app", AppOp.WAKE_LOCK, AppOpMode.IGNORE),
        )
        assertEquals(
            GatewayReply.Unavailable(GatewayUnavailable.STALE_SERVICE),
            gateway.startBgDexopt(),
        )
        apiVersion = -1 // a service that rejected this caller
        assertEquals(GatewayReply.Unavailable(GatewayUnavailable.STALE_SERVICE), gateway.getDozeState())
        assertTrue(sent.isEmpty())
        assertEquals(0, shizuku.holds)
    }

    @Test
    fun `a null parcelable return maps to Unavailable`() = runTest {
        returnNull = true

        assertEquals(GatewayReply.Unavailable(GatewayUnavailable.STALE_SERVICE), gateway.getAppOps("com.example.app"))
    }

    @Test
    fun `a service reply is passed through unchanged`() = runTest {
        val reply = gateway.getDozeState()

        assertEquals(GatewayReply.Reply(ToolkitResult.done(after = "x")), reply)
    }

    @Test
    fun `updated system app results require a warning`() {
        assertTrue(ToolkitResult.done(code = ToolkitCode.WARN_UPDATED_SYSTEM).requiresWarning)
        assertFalse(ToolkitResult.done().requiresWarning)
    }

    @Test
    fun `the binding is held while dexopt is running and released when it stops`() = runTest {
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

    @Test
    fun `a refused start takes no binding hold`() = runTest {
        val refusing = Proxy.newProxyInstance(
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
    fun `status that is not running after a rebind holds nothing and cancel is forwarded`() = runTest {
        dexoptState = BgDexoptState.IDLE

        gateway.bgDexoptStatus()
        gateway.cancelBgDexopt()

        assertEquals(0, shizuku.holds)
        assertEquals(listOf("getBgDexoptStatus()", "cancelBgDexopt()"), sent)
    }
}
