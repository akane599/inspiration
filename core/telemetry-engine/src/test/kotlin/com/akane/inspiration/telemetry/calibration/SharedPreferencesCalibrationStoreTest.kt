package com.akane.inspiration.telemetry.calibration

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SharedPreferencesCalibrationStoreTest {
    private val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("calibration-test", Context.MODE_PRIVATE)

    private val locks =
        CalibrationLocks(
            sysfsMilliamps = true,
            batteryManagerMilliamps = null,
            dischargePositive = false,
            perCellCurrent = null,
            sysfsMicrovolts = true,
        )
    private val guesses = CalibrationLocks(batteryManagerMilliamps = true, dischargePositive = true)

    @Test
    fun `nothing saved loads as nothing`() {
        assertNull(SharedPreferencesCalibrationStore(prefs, buildFingerprint = "build-1").load())
    }

    @Test
    fun `a new process on the same build gets the saved locks and guesses back, unsettled ones still unsettled`() {
        SharedPreferencesCalibrationStore(prefs, buildFingerprint = "build-1").save(CalibrationStart(locks, guesses))

        assertEquals(CalibrationStart(locks, guesses), SharedPreferencesCalibrationStore(prefs, buildFingerprint = "build-1").load())
    }

    @Test
    fun `after an OS update the old build's locks come back as guesses only, ahead of its own guesses`() {
        SharedPreferencesCalibrationStore(prefs, buildFingerprint = "build-1").save(CalibrationStart(locks, guesses))

        assertEquals(
            CalibrationStart(
                guesses =
                    CalibrationLocks(
                        sysfsMilliamps = true,
                        batteryManagerMilliamps = true,
                        dischargePositive = false,
                        perCellCurrent = null,
                        sysfsMicrovolts = true,
                    ),
            ),
            SharedPreferencesCalibrationStore(prefs, buildFingerprint = "build-2").load(),
        )
    }

    @Test
    fun `a save on a new build replaces every lock of the old one`() {
        SharedPreferencesCalibrationStore(prefs, buildFingerprint = "build-1").save(CalibrationStart(locks, guesses))
        val relearned = CalibrationStart(locks = CalibrationLocks(sysfsMilliamps = false), guesses = locks.orElse(guesses))

        SharedPreferencesCalibrationStore(prefs, buildFingerprint = "build-2").save(relearned)

        assertEquals(relearned, SharedPreferencesCalibrationStore(prefs, buildFingerprint = "build-2").load())
    }
}
