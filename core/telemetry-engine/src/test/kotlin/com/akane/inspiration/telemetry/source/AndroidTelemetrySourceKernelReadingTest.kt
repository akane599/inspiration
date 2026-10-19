package com.akane.inspiration.telemetry.source

import android.os.BatteryManager
import com.akane.inspiration.ipc.ITelemetryUserService
import com.akane.inspiration.ipc.ShizukuManager
import com.akane.inspiration.ipc.ShizukuState
import com.akane.inspiration.ipc.model.KernelBatterySnapshot
import com.akane.inspiration.telemetry.calibration.CalibrationInput
import com.akane.inspiration.telemetry.calibration.PowerCalibrator
import com.akane.inspiration.telemetry.common.Clocks
import com.akane.inspiration.telemetry.live.LiveTelemetry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * `kernelReading()` with a calibration reader that cached sysfs at process start (150 mA) while the
 * device now draws 1.2 A: a kernel read missing a node must never bring the cached value back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidTelemetrySourceKernelReadingTest {
    private val context = RuntimeEnvironment.getApplication()

    private val cachedAtStart =
        CalibrationInput(
            sysfsCurrentRaw = -150_000L,
            sysfsVoltageRaw = 3_900_000L,
            bmCurrentNowUa = -150_000,
            bmVoltageMv = 3_900,
            isCharging = false,
            plugged = false,
        )

    private fun source(kernel: KernelBatterySnapshot): AndroidTelemetrySource {
        shadowOf(context.getSystemService(BatteryManager::class.java))
            .setIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW, -1_200_000)
        return AndroidTelemetrySource(
            context = context,
            shizuku = DirectShizuku(KernelService(kernel)),
            clocks = FixedClocks,
            calibrationInputReader = CalibrationInputReader { cachedAtStart },
        )
    }

    @Test
    fun `a kernel read without current_now takes the live current from the fresh battery manager value`() =
        runBlocking {
            val source = source(kernel(currentNowRaw = null, voltageNowRaw = 4_000_000L))

            val reading =
                LiveTelemetry(source, PowerCalibrator(), FixedClocks, ioDispatcher = Dispatchers.Unconfined)
                    .readings(periodMs = 1L)
                    .first()

            assertEquals("fresh -1200 mA, not the -150 mA cached at process start", -1_200, reading.currentMa)
            assertEquals("calibrated from the fresh current and the kernel voltage", -4.8, reading.watts ?: Double.NaN, 1e-9)
        }

    @Test
    fun `a kernel read without sysfs values never substitutes the ones cached at process start`() =
        runBlocking {
            val input = checkNotNull(source(KernelBatterySnapshot.unavailable("no battery supply")).kernelReading()?.calibrationInput)

            assertNull(input.sysfsCurrentRaw)
            assertNull(input.sysfsVoltageRaw)
            assertEquals(-1_200_000, input.bmCurrentNowUa)
        }

    @Test
    fun `the battery manager current is read raw right after the sysfs read`() =
        runBlocking {
            val batteryManager = shadowOf(context.getSystemService(BatteryManager::class.java))
            batteryManager.setIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW, -1_200_000)
            // The load changes while the service reads sysfs: BatteryManager now reports 1.5 mA, with µA detail.
            val service =
                object : ITelemetryUserService.Default() {
                    override fun readKernelBattery(): KernelBatterySnapshot {
                        batteryManager.setIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW, 1_537)
                        return kernel(currentNowRaw = 1_540L, voltageNowRaw = 4_350_000L)
                    }
                }
            val source =
                AndroidTelemetrySource(
                    context = context,
                    shizuku = DirectShizuku(service),
                    clocks = FixedClocks,
                    calibrationInputReader = CalibrationInputReader { cachedAtStart },
                )

            val input = checkNotNull(source.kernelReading()?.calibrationInput)

            assertEquals("the same moment as sysfs, not cut to whole mA", 1_537, input.bmCurrentNowUa)
            assertEquals(1_540L, input.sysfsCurrentRaw)
        }

    private fun kernel(
        currentNowRaw: Long?,
        voltageNowRaw: Long?,
    ) = KernelBatterySnapshot(
        available = true,
        reason = null,
        currentNowRaw = currentNowRaw,
        voltageNowRaw = voltageNowRaw,
        chargeCounterRaw = null,
        chargeFullRaw = null,
        chargeFullDesignRaw = null,
        cycleCount = null,
        temperatureDeciC = 300,
        status = "Discharging",
        capacityPercent = 60,
        sourcePath = "/sys/class/power_supply/battery",
        readAtElapsedMs = 2L,
    )

    private class KernelService(
        private val snapshot: KernelBatterySnapshot,
    ) : ITelemetryUserService.Default() {
        override fun readKernelBattery(): KernelBatterySnapshot = snapshot
    }

    private class DirectShizuku(
        private val service: ITelemetryUserService,
    ) : ShizukuManager {
        override val state: StateFlow<ShizukuState> = MutableStateFlow(ShizukuState.Ready(2000, 13))
        override val telemetryService: StateFlow<ITelemetryUserService?> = MutableStateFlow(service)

        override fun requestPermission() = Unit

        override fun start() = Unit

        override suspend fun <T> withTelemetryService(
            timeoutMs: Long,
            block: suspend (ITelemetryUserService) -> T,
        ): T? = block(service)

        override fun holdBinding(): AutoCloseable = AutoCloseable {}

        override fun stop() = Unit
    }

    private object FixedClocks : Clocks {
        override fun wallMs(): Long = 1_000L

        override fun elapsedRealtimeMs(): Long = 2_000L

        override fun uptimeMs(): Long = 3_000L

        override fun bootCount(): Int = 4
    }
}
