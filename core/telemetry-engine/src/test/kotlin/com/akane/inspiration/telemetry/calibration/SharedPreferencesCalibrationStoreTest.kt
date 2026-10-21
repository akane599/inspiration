package com.akane.inspiration.telemetry.calibration

import android.content.Context
import com.akane.inspiration.telemetry.status.ChargeState
import com.akane.inspiration.telemetry.status.LightCurrent
import com.akane.inspiration.telemetry.status.chargeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
            batteryManagerDischargePositive = true,
        )
    private val guesses = CalibrationLocks(batteryManagerMilliamps = true, dischargePositive = true, batteryManagerDischargePositive = false)

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
                        batteryManagerDischargePositive = true,
                    ),
            ),
            SharedPreferencesCalibrationStore(prefs, buildFingerprint = "build-2").load(),
        )
    }

    @Test
    fun `opposite source signs persist through restart and build change for live fallback and light charge labels`() {
        for (sysfsPositive in listOf(true, false)) {
            val direction = if (sysfsPositive) 1 else -1
            val drain = CalibrationInput(direction * 300_000L, 3_900_000, -direction * 300_000, 3_900, false, false)
            val evidence = CalibrationEvidence().apply { repeat(3) { add(drain) } }
            SharedPreferencesCalibrationStore(prefs, "build-1").save(evidence.start())

            for (build in listOf("build-1", "build-2")) {
                val start = checkNotNull(SharedPreferencesCalibrationStore(prefs, build).load())
                val selected = if (build == "build-1") start.locks else start.guesses
                assertEquals(sysfsPositive, selected.dischargePositive)
                assertEquals(!sysfsPositive, selected.batteryManagerDischargePositive)
                val restarted = CalibrationEvidence(start)
                val charge = drain.copy(sysfsCurrentRaw = null, bmCurrentNowUa = direction * 250_000, plugged = true, isCharging = true)
                restarted.add(charge)
                assertEquals(250.0, PowerCalibrator().toReading(charge, restarted.calibration())!!.currentMa, 0.0)
                assertEquals(-300.0, PowerCalibrator().toReading(drain, restarted.calibration())!!.currentMa, 0.0)
                val lightCharge = LightCurrent.calibratedMa(direction * 250, start)
                val lightDrain = LightCurrent.calibratedMa(-direction * 300, start)
                assertEquals(250, lightCharge)
                assertEquals(-300, lightDrain)
                assertEquals(ChargeState.Charging, chargeState(true, "charging", lightCharge))
                assertEquals(ChargeState.PluggedNotCharging, chargeState(true, "charging", lightDrain))
                if (build == "build-2") {
                    assertNull(start.locks.batteryManagerDischargePositive)
                    assertNull(restarted.locks().batteryManagerDischargePositive)
                }
            }
        }
    }

    @Test
    fun `legacy shared signs are invalid for both sources on same and changed builds while units survive`() {
        for (legacyPositive in listOf(true, false)) {
            for (build in listOf("build-1", "build-2")) {
                prefs.edit().clear()
                    .putString("buildFingerprint", "build-1")
                    .putBoolean("lock.dischargePositive", legacyPositive)
                    .putBoolean("guess.dischargePositive", !legacyPositive)
                    .putBoolean("lock.sysfsMilliamps", false)
                    .putBoolean("lock.batteryManagerMilliamps", false)
                    .putBoolean("lock.perCellCurrent", false)
                    .putBoolean("lock.sysfsMicrovolts", true)
                    .apply()
                val store = SharedPreferencesCalibrationStore(prefs, build)
                val start = checkNotNull(store.load())
                val unitEvidence = CalibrationLocks(
                    sysfsMilliamps = false, batteryManagerMilliamps = false, perCellCurrent = false, sysfsMicrovolts = true,
                )
                assertEquals(
                    if (build == "build-1") CalibrationStart(locks = unitEvidence) else CalibrationStart(guesses = unitEvidence),
                    start,
                )
                assertNull(LightCurrent.calibratedMa(-300, start))
                val evidence = CalibrationEvidence(start)
                val drain = CalibrationInput(300_000, 3_900_000, -300_000, 3_900, false, false)
                repeat(3) { evidence.add(drain) }
                store.save(evidence.start())

                val relearned = checkNotNull(SharedPreferencesCalibrationStore(prefs, build).load())
                assertEquals(true, relearned.locks.dischargePositive)
                assertEquals(false, relearned.locks.batteryManagerDischargePositive)
                assertEquals(-300, LightCurrent.calibratedMa(-300, relearned))
                assertEquals(true, prefs.getBoolean("lock.sysfsDischargePositive.v2", false))
                assertEquals(false, prefs.getBoolean("lock.batteryManagerDischargePositive.v2", true))
                assertFalse(prefs.contains("lock.dischargePositive"))
                assertFalse(prefs.contains("guess.dischargePositive"))
            }
        }
    }

    @Test
    fun `a save on a new build replaces every lock of the old one`() {
        SharedPreferencesCalibrationStore(prefs, buildFingerprint = "build-1").save(CalibrationStart(locks, guesses))
        val relearned = CalibrationStart(locks = CalibrationLocks(sysfsMilliamps = false), guesses = locks.orElse(guesses))

        SharedPreferencesCalibrationStore(prefs, buildFingerprint = "build-2").save(relearned)

        assertEquals(relearned, SharedPreferencesCalibrationStore(prefs, buildFingerprint = "build-2").load())
    }
}
