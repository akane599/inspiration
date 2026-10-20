package com.akane.inspiration.diagnostics.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import com.akane.inspiration.diagnostics.model.SensorKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowSensor
import java.lang.reflect.InvocationTargetException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidSensorCatalogTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun addSensor(
        type: Int,
        handle: Int,
    ) {
        val sensor = ShadowSensor.newInstance(type)
        // Robolectric does not assign handles; set the field Sensor.getHandle() returns.
        Sensor::class.java
            .getDeclaredField("mHandle")
            .apply { isAccessible = true }
            .setInt(sensor, handle)
        shadowOf(context.getSystemService(SensorManager::class.java)).addSensor(sensor)
    }

    @Test
    fun gpsPseudoHandleIsGpsEvenWithNoSensorsRegistered() {
        val catalog = AndroidSensorCatalog(context)
        assertEquals(-10000, GPS_SENSOR_HANDLE)
        assertEquals(SensorKind.GPS, catalog.kindOf(-10000))
    }

    @Test
    fun unknownHandleIsOther() {
        addSensor(Sensor.TYPE_ACCELEROMETER, handle = 3)
        assertEquals(SensorKind.OTHER, AndroidSensorCatalog(context).kindOf(424242))
    }

    @Test
    fun motionSensorsMapByTypeAndOthersAreOther() {
        addSensor(Sensor.TYPE_ACCELEROMETER, handle = 3)
        addSensor(Sensor.TYPE_ACCELEROMETER_UNCALIBRATED, handle = 4)
        addSensor(Sensor.TYPE_GYROSCOPE, handle = 5)
        addSensor(Sensor.TYPE_GYROSCOPE_UNCALIBRATED, handle = 6)
        addSensor(Sensor.TYPE_LIGHT, handle = 7)
        val catalog = AndroidSensorCatalog(context)
        assertEquals(SensorKind.ACCELEROMETER, catalog.kindOf(3))
        assertEquals(SensorKind.ACCELEROMETER, catalog.kindOf(4))
        assertEquals(SensorKind.GYROSCOPE, catalog.kindOf(5))
        assertEquals(SensorKind.GYROSCOPE, catalog.kindOf(6))
        assertEquals(SensorKind.OTHER, catalog.kindOf(7))
    }

    @Test
    fun sensorServiceFailureFallsBackToOther() {
        val broken =
            object : android.content.ContextWrapper(context) {
                override fun getSystemServiceName(serviceClass: Class<*>): String = throw IllegalStateException("no sensors")
            }
        val catalog = AndroidSensorCatalog(broken)
        assertEquals(SensorKind.OTHER, catalog.kindOf(3))
        assertEquals(SensorKind.GPS, catalog.kindOf(-10000))
    }

    @Test
    fun sensorServiceFailureIsLoggedOnce() {
        val broken =
            object : android.content.ContextWrapper(context) {
                override fun getSystemServiceName(serviceClass: Class<*>): String = throw IllegalStateException("no sensors")
            }
        val catalog = AndroidSensorCatalog(broken)
        assertEquals(SensorKind.OTHER, catalog.kindOf(3))
        assertEquals(SensorKind.OTHER, catalog.kindOf(4))
        assertEquals(SensorKind.OTHER, catalog.kindOf(5))
        assertEquals(1, ShadowLog.getLogsForTag("AndroidSensorCatalog").size)
    }

    @Test
    @Config(shadows = [ThrowingHandleShadowSensor::class])
    fun failingGetHandleLeavesMotionSensorsOtherAndIsLoggedOnce() {
        addSensor(Sensor.TYPE_ACCELEROMETER, handle = 3)
        addSensor(Sensor.TYPE_GYROSCOPE, handle = 5)
        val catalog = AndroidSensorCatalog(context)

        assertEquals(SensorKind.OTHER, catalog.kindOf(3))
        assertEquals(SensorKind.OTHER, catalog.kindOf(5))
        assertEquals(SensorKind.OTHER, catalog.kindOf(3))
        assertEquals(SensorKind.GPS, catalog.kindOf(GPS_SENSOR_HANDLE))

        val logs = ShadowLog.getLogsForTag("AndroidSensorCatalog")
        assertEquals(1, logs.size)
        assertEquals(Log.WARN, logs.single().type)
        // Reflection wraps whatever the hidden member throws, so this is the ReflectiveOperationException branch.
        val thrown = logs.single().throwable
        assertTrue(thrown is InvocationTargetException)
        assertTrue(thrown.cause is SecurityException)
    }

    @Test
    fun hiddenApiSignatureNamesExactlyTheReflectedMember() {
        // The app exempts only this signature (N-26); it must match the member read by reflection.
        val getHandle = Sensor::class.java.getMethod("getHandle")
        assertEquals(Int::class.javaPrimitiveType, getHandle.returnType)
        assertEquals(0, getHandle.parameterCount)
        val signature = "L${getHandle.declaringClass.name.replace('.', '/')};->${getHandle.name}()I"
        assertEquals(signature, AndroidSensorCatalog.GET_HANDLE_SIGNATURE)
    }
}

/** A platform whose hidden-API enforcement refuses `Sensor.getHandle()` at call time. */
@Implements(Sensor::class)
class ThrowingHandleShadowSensor : ShadowSensor() {
    @Implementation
    protected fun getHandle(): Int = throw SecurityException("getHandle() is hidden")
}
