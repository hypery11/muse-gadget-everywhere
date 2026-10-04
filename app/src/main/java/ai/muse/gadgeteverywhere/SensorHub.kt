package ai.muse.gadgeteverywhere

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject

class SensorHub(private val context: Context, private val emit: (String, JSONObject) -> Unit) : SensorEventListener {
    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val samples = mutableMapOf<Int, Pair<Long, FloatArray>>()
    private var battery = JSONObject()
    private val handler = Handler(Looper.getMainLooper())
    private val types = mapOf(Sensor.TYPE_LIGHT to "light_lux", Sensor.TYPE_PROXIMITY to "proximity_cm",
                             Sensor.TYPE_ACCELEROMETER to "acceleration_m_s2", Sensor.TYPE_AMBIENT_TEMPERATURE to "ambient_c")
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val current = JSONObject()
            if (scale > 0 && level >= 0) current.put("percent", 100.0 * level / scale)
            current.put("plugged", intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0)
            if (intent.hasExtra(BatteryManager.EXTRA_TEMPERATURE)) current.put("temperature_c", intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0)
            if (battery.length() > 0 && battery.optBoolean("plugged") != current.optBoolean("plugged")) emit("power.changed", current)
            if (current.has("percent") && current.optDouble("percent") <= 15 && battery.optDouble("percent", 100.0) > 15) emit("battery.low", current)
            battery = current
        }
    }
    init {
        // BATTERY_CHANGED is a system-only sticky broadcast, valid on API 24+.
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }
    fun available(): JSONArray = JSONArray(types.filterKeys { manager.getDefaultSensor(it) != null }.values.toList())
    fun sample() {
        for (type in types.keys) manager.getDefaultSensor(type)?.let { manager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        handler.postDelayed({ manager.unregisterListener(this) }, 1000)
    }
    fun snapshot(): JSONObject {
        val result = JSONObject().put("battery", JSONObject(battery.toString())).put("available", available())
        for ((type, sample) in samples) {
            result.put(types.getValue(type), JSONObject().put("values", JSONArray(sample.second.toList()))
                .put("age_ms", SystemClock.elapsedRealtime() - sample.first))
        }
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivity.activeNetwork
        val caps = connectivity.getNetworkCapabilities(network)
        return result.put("network", JSONObject().put("connected", network != null)
            .put("validated", caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true)
            .put("metered", connectivity.isActiveNetworkMetered))
    }
    override fun onSensorChanged(event: SensorEvent) { samples[event.sensor.type] = SystemClock.elapsedRealtime() to event.values.clone() }
    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
    fun close() {
        manager.unregisterListener(this)
        handler.removeCallbacksAndMessages(null)
        context.unregisterReceiver(receiver)
    }
}
