package de.greenganja.growguardian.grow.light

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow

/** Ein Wert des Umgebungslichtsensors. [saturated]: Sensor am Anschlag, echter Wert liegt höher. */
data class PhoneLux(val lux: Double, val maxLux: Double, val saturated: Boolean)

/**
 * Umgebungslichtsensor des Handys (sitzt meist oben neben der Frontkamera).
 *
 * Gleitender Median über die letzten Werte, damit der Wert beim Halten nicht springt.
 */
class PhoneLightSensor(context: Context) {
    private val manager = context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val sensor: Sensor? = manager?.getDefaultSensor(Sensor.TYPE_LIGHT)

    val available: Boolean get() = sensor != null

    fun readings(): Flow<PhoneLux> {
        val m = manager ?: return emptyFlow()
        val s = sensor ?: return emptyFlow()
        return callbackFlow {
            val window = ArrayDeque<Double>()
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    val value = event.values.firstOrNull()?.toDouble() ?: return
                    if (!value.isFinite() || value < 0) return
                    window.addLast(value)
                    while (window.size > WINDOW) window.removeFirst()
                    val median = window.sorted()[window.size / 2]
                    val max = s.maximumRange.toDouble()
                    trySend(PhoneLux(median, max, max > 0 && median >= max * 0.98))
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }
            m.registerListener(listener, s, SensorManager.SENSOR_DELAY_UI)
            awaitClose { m.unregisterListener(listener) }
        }
    }

    private companion object {
        const val WINDOW = 7
    }
}
