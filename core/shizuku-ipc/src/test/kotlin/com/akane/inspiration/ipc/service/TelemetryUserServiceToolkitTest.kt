package com.akane.inspiration.ipc.service

import com.akane.inspiration.ipc.model.BgDexoptReason
import com.akane.inspiration.ipc.model.BgDexoptState
import com.akane.inspiration.ipc.model.ToolkitApi
import com.akane.inspiration.ipc.model.ToolkitCode
import com.akane.inspiration.ipc.model.ToolkitResult
import com.akane.inspiration.ipc.model.ToolkitStatus
import com.akane.inspiration.ipc.toolkit.BgDexoptController
import com.akane.inspiration.ipc.toolkit.CMD
import com.akane.inspiration.ipc.toolkit.CommandRunner
import com.akane.inspiration.ipc.toolkit.FakeInspector
import com.akane.inspiration.ipc.toolkit.FakeRunner
import com.akane.inspiration.ipc.toolkit.ThrowingRunner
import com.akane.inspiration.ipc.toolkit.ToolkitExecutor
import com.akane.inspiration.ipc.toolkit.ok
import com.akane.inspiration.ipc.toolkit.userApp
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryUserServiceToolkitTest {
    private val spawn = ThrowingRunner()

    private fun toolkit(runner: CommandRunner = spawn, inspector: FakeInspector = FakeInspector(mutableMapOf("com.example.app" to userApp()))) =
        ToolkitExecutor(runner, inspector, BgDexoptController(runner, { true }, { 0L }, { }), "com.akane.inspiration") { }

    private fun service(appUid: Int?, callingUid: () -> Int, toolkit: ToolkitExecutor) =
        TelemetryUserService(appUid = appUid, fileRoot = File("/"), callingUid = callingUid, toolkit = toolkit)

    private fun everyWrite(service: TelemetryUserService): List<ToolkitResult> = listOf(
        service.setAppOp("com.example.app", 3, 1),
        service.getAppOps("com.example.app"),
        service.setStandbyBucket("com.example.app", 5),
        service.getStandbyBucket("com.example.app"),
        service.applyDozeProfile(1, null),
        service.getDozeState(),
        service.forceStop("com.example.app"),
        service.startBgDexopt(),
        service.cancelBgDexopt(),
        service.restoreDozeRaw(null, null),
    )

    @Test
    fun `a wrong UID is refused on every typed transaction with no process spawned`() {
        val service = service(appUid = APP_UID, callingUid = { APP_UID + 1 }, toolkit = toolkit())

        everyWrite(service).forEach {
            assertEquals(ToolkitResult.refused(ToolkitCode.CALLER), it)
        }
        val status = service.getBgDexoptStatus()
        assertEquals(BgDexoptState.UNKNOWN, status.state)
        assertEquals(BgDexoptReason.CALLER, status.reason)
        assertEquals(-1, service.getApiVersion())
        assertEquals(0, spawn.starts)
    }

    @Test
    fun `an unresolved application UID refuses every typed transaction with no process spawned`() {
        val service = service(appUid = null, callingUid = { APP_UID }, toolkit = toolkit())

        everyWrite(service).forEach {
            assertEquals(ToolkitStatus.REFUSED, it.status)
            assertEquals(ToolkitCode.CALLER, it.code)
        }
        assertEquals(-1, service.getApiVersion())
        assertEquals(0, spawn.starts)
    }

    @Test
    fun `a throwing caller lookup is treated as denied`() {
        val service = service(appUid = APP_UID, callingUid = { throw IllegalStateException() }, toolkit = toolkit())

        everyWrite(service).forEach { assertEquals(ToolkitCode.CALLER, it.code) }
        assertEquals(0, spawn.starts)
    }

    @Test
    fun `the default service has no Context and fails closed`() {
        val service = TelemetryUserService(appUid = APP_UID, fileRoot = File("/"), callingUid = { APP_UID })

        val result = service.setAppOp("com.example.app", 3, 1)

        assertEquals(ToolkitStatus.REFUSED, result.status)
        assertEquals(ToolkitCode.PROTECTION_UNKNOWN, result.code)
    }

    @Test
    fun `the allowed caller reaches the executor and the api version is 2`() {
        val runner = FakeRunner { argv ->
            if (argv.contains("get")) ok("WAKE_LOCK: default") else ok()
        }
        val service = service(APP_UID, { APP_UID }, toolkit(runner))

        assertEquals(2, ToolkitApi.VERSION)
        assertEquals(2, service.getApiVersion())
        service.setAppOp("com.example.app", 3, 1)

        assertTrue(listOf(CMD, "appops", "set", "--user", "0", "com.example.app", "WAKE_LOCK", "ignore") in runner.calls)
        assertEquals(BgDexoptState.IDLE, service.getBgDexoptStatus().state)
    }

    private companion object {
        const val APP_UID = 10_001
    }
}
