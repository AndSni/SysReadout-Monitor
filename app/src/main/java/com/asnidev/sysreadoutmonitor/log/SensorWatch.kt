package com.asnidev.sysreadoutmonitor.log

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/** Latest ambient and orientation readings; listens only to the sensors [update] asks for. */
class SensorWatch(context: Context) : SensorEventListener {

    private val sm = context.getSystemService(SensorManager::class.java)
    private val ambientTypes = listOf(
        Sensor.TYPE_LIGHT,
        Sensor.TYPE_PRESSURE,
        Sensor.TYPE_AMBIENT_TEMPERATURE,
        Sensor.TYPE_RELATIVE_HUMIDITY,
    )
    private val values = HashMap<Int, FloatArray>()
    private val registered = HashSet<Int>()

    val hasAmbient: Boolean get() = ambientTypes.any { sm.getDefaultSensor(it) != null }
    val hasRotation: Boolean get() = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) != null

    fun update(ambient: Boolean, rotation: Boolean) {
        val wanted = buildSet {
            if (ambient) addAll(ambientTypes)
            if (rotation) add(Sensor.TYPE_ROTATION_VECTOR)
        }
        (registered - wanted).forEach { type -> sm.getDefaultSensor(type)?.let { sm.unregisterListener(this, it) } }
        (wanted - registered).forEach { type ->
            // Rows refresh at most once a second, so one reading a second is plenty.
            sm.getDefaultSensor(type)?.let { sm.registerListener(this, it, SAMPLE_US, SAMPLE_US) }
        }
        registered.clear()
        registered.addAll(wanted)
    }

    fun stop() = update(ambient = false, rotation = false)

    fun value(type: Int): Float? = synchronized(values) { values[type]?.get(0) }

    /** Azimuth, pitch, roll in degrees, or null before the first reading. */
    fun orientation(): FloatArray? {
        val v = synchronized(values) { values[Sensor.TYPE_ROTATION_VECTOR] } ?: return null
        val matrix = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(matrix, v)
        val o = SensorManager.getOrientation(matrix, FloatArray(3))
        return floatArrayOf(
            ((Math.toDegrees(o[0].toDouble()) + 360) % 360).toFloat(),
            Math.toDegrees(o[1].toDouble()).toFloat(),
            Math.toDegrees(o[2].toDouble()).toFloat(),
        )
    }

    override fun onSensorChanged(event: SensorEvent) {
        synchronized(values) { values[event.sensor.type] = event.values.copyOf() }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private companion object {
        const val SAMPLE_US = 1_000_000
    }
}
