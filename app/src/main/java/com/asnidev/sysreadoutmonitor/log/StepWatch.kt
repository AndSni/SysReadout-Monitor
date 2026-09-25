package com.asnidev.sysreadoutmonitor.log

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import java.time.LocalDate

/**
 * Steps from the hardware step counter, which counts since boot. "Today" is
 * measured from the last count SR Monitor saw before midnight.
 */
class StepWatch(private val context: Context) : SensorEventListener {

    private val sm = context.getSystemService(SensorManager::class.java)
    private val sensor = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
    private val store = context.getSharedPreferences("steps", Context.MODE_PRIVATE)
    @Volatile private var count: Float? = null
    private var running = false

    fun update(want: Boolean) {
        val on = want && sensor != null && Access.ACTIVITY.runtimeGranted(context)
        if (on == running) return
        running = on
        if (on) sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL) else sm.unregisterListener(this)
    }

    fun line(): String {
        if (sensor == null) return "no step counter"
        val now = count ?: return "waiting for the step counter"
        val today = LocalDate.now().toEpochDay()
        var base = store.getFloat("base", 0f)
        if (store.getLong("day", -1) != today) {
            // A new day starts from the last count seen before it (0 after a reboot).
            base = store.getFloat("last", 0f).takeIf { it <= now } ?: 0f
            store.edit().putLong("day", today).putFloat("base", base).apply()
        }
        if (now < base) { // rebooted today: the counter started over
            base = 0f
            store.edit().putFloat("base", 0f).apply()
        }
        store.edit().putFloat("last", now).apply()
        return "${(now - base).toInt()} today  ${now.toInt()} since boot"
    }

    override fun onSensorChanged(event: SensorEvent) {
        count = event.values[0]
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
