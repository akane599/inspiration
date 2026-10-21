package com.akane.inspiration.toolkit

import androidx.compose.ui.graphics.Color
import com.akane.inspiration.ipc.model.BgDexoptReason
import com.akane.inspiration.ipc.model.BgDexoptState
import com.akane.inspiration.telemetry.store.ToolkitActionStatus
import com.akane.inspiration.telemetry.toolkit.BgDexoptCancelReason
import com.akane.inspiration.telemetry.toolkit.BgDexoptStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import com.akane.inspiration.ipc.model.BgDexoptStatus as ServiceDexopt

@OptIn(ExperimentalCoroutinesApi::class)
class ToolkitDexoptCompletionTest {
    private val scheduler = TestCoroutineScheduler()
    private val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
    }

    @After
    fun tearDown() {
        scope.cancel()
        Dispatchers.resetMain()
    }

    @Test
    fun `completion evidence survives gateway durable settlement and viewmodel presentation`() {
        for (reason in listOf(BgDexoptReason.NONE, BgDexoptReason.RESULT_NOT_SUCCESSFUL, 999)) {
            for (exit in listOf(0, 7)) {
                val deps = FakeToolkitDeps(scope = scope)
                val vm = ToolkitViewModel(deps)
                vm.onIntent(ToolkitIntent.Visible)
                vm.onIntent(ToolkitIntent.StartDexopt)
                val startedAt = deps.device.dexopt.startedAtMs
                deps.device.dexopt = ServiceDexopt(BgDexoptState.FINISHED, startedAt, 2_000L, exit, reason)

                scheduler.advanceTimeBy(5_001L)
                scheduler.runCurrent()

                val status = vm.state.value.dexopt.status as BgDexoptStatus.Finished
                val succeeded = exit == 0 && reason == BgDexoptReason.NONE
                assertEquals(exit, status.exitCode)
                assertEquals(reason == BgDexoptReason.NONE, status.completionVerified)
                assertEquals(succeeded, status.succeeded)
                assertEquals(
                    if (succeeded) Color.Green else Color.Red,
                    finishedDexoptColor(status, success = Color.Green, failure = Color.Red),
                )
                val expected = if (succeeded) ToolkitActionStatus.DONE else ToolkitActionStatus.FAILED
                assertEquals(expected, deps.device.store.rows.single().status)
                assertEquals(expected, vm.state.value.history.single().status)
                assertEquals(exit, deps.device.store.rows.single().code)
                assertEquals(1, deps.device.releases)
                assertEquals(
                    when {
                        succeeded -> R.string.toolkit_dexopt_finished
                        exit != 0 -> R.string.toolkit_dexopt_finished_code
                        else -> R.string.toolkit_dexopt_completion_unverified
                    },
                    finishedDexoptTextRes(status),
                )
                vm.onIntent(ToolkitIntent.Hidden)
            }
        }
    }

    @Test
    fun `external and local cancellation stay cancelled in history and the visible status`() {
        for ((reason, expected) in listOf(
            BgDexoptReason.CALLER to BgDexoptCancelReason.OTHER,
            BgDexoptReason.USER to BgDexoptCancelReason.USER,
        )) {
            val deps = FakeToolkitDeps(scope = scope)
            val vm = ToolkitViewModel(deps)
            vm.onIntent(ToolkitIntent.Visible)
            vm.onIntent(ToolkitIntent.StartDexopt)
            deps.device.dexopt = ServiceDexopt(BgDexoptState.CANCELLED, deps.device.dexopt.startedAtMs, 2_000L, 0, reason)

            scheduler.advanceTimeBy(5_001L)
            scheduler.runCurrent()

            assertEquals(BgDexoptStatus.Cancelled(expected, 2_000L), vm.state.value.dexopt.status)
            assertEquals(ToolkitActionStatus.CANCELLED, vm.state.value.history.single().status)
            assertEquals(reason, deps.device.store.rows.single().code)
            assertEquals(1, deps.device.releases)
            assertFalse(deps.device.writes.contains("cancelBgDexopt"))
            vm.onIntent(ToolkitIntent.Hidden)
        }
    }

    @Test
    fun `a finished value without completion evidence defaults to non-success`() {
        val status = BgDexoptStatus.Finished(0, 2_000L)
        assertFalse(status.succeeded)
        assertEquals(Color.Red, finishedDexoptColor(status, success = Color.Green, failure = Color.Red))
        assertEquals(R.string.toolkit_dexopt_completion_unverified, finishedDexoptTextRes(status))
        assertEquals(
            R.string.toolkit_dexopt_finished_code,
            finishedDexoptTextRes(BgDexoptStatus.Finished(7, 2_000L, completionVerified = true)),
        )
    }

    @Test
    fun `unverified zero exit has honest English and Turkish wording without a fabricated error code`() {
        for ((directory, expected) in listOf(
            "values" to "Successful completion was not confirmed.",
            "values-tr" to "Başarıyla tamamlandığı doğrulanamadı.",
        )) {
            val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(File("src/main/res/$directory/strings.xml")).getElementsByTagName("string")
            val strings = (0 until nodes.length).associate { index ->
                val node = nodes.item(index)
                node.attributes.getNamedItem("name").nodeValue to node.textContent
            }
            assertEquals(expected, strings.getValue("toolkit_dexopt_completion_unverified"))
            assertFalse(strings.getValue("toolkit_dexopt_completion_unverified").contains("%"))
        }
    }
}
