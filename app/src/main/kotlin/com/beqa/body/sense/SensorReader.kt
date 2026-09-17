package com.beqa.body.sense

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.HandlerThread
import android.os.Handler
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * M8f, a ONE-SHOT sensor poll. Explicitly NOT a stream.
 *
 * Every call registers listeners, waits for the first sample from each (or a short
 * timeout), then unregisters in a `finally` and quits its own HandlerThread. Nothing
 * survives the request, so nothing can drain the battery between requests. If a caller
 * wants a time series it must poll. That's on purpose.
 *
 * Battery/charging lives in [DeviceContextReader.battery] (a sticky broadcast, no
 * listener at all) and is folded in here for convenience.
 */
object SensorReader {

    /** Cheap, permission-free sensors. Step counter is excluded: it needs ACTIVITY_RECOGNITION. */
    private val DEFAULT_TYPES = listOf(
        Sensor.TYPE_LIGHT,
        Sensor.TYPE_PROXIMITY,
        Sensor.TYPE_ACCELEROMETER,
        Sensor.TYPE_GYROSCOPE,
        Sensor.TYPE_MAGNETIC_FIELD,
        Sensor.TYPE_PRESSURE,
        Sensor.TYPE_AMBIENT_TEMPERATURE,
        Sensor.TYPE_RELATIVE_HUMIDITY
    )

    fun list(ctx: Context): JSONObject {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            ?: return JSONObject().put("ok", false).put("error", "no_sensor_service")
        val arr = JSONArray()
        try {
            for (s in sm.getSensorList(Sensor.TYPE_ALL)) {
                arr.put(
                    JSONObject()
                        .put("type", s.type)
                        .put("name", s.name)
                        .put("vendor", s.vendor)
                        .put("power_ma", s.power.toDouble())
                        .put("max_range", s.maximumRange.toDouble())
                        .put("resolution", s.resolution.toDouble())
                        .put("wake_up", s.isWakeUpSensor)
                )
            }
        } catch (t: Throwable) {
            return JSONObject().put("ok", false).put("error", "list_failed: ${t.javaClass.simpleName}")
        }
        return JSONObject().put("ok", true).put("count", arr.length()).put("sensors", arr)
    }

    /**
     * @param timeoutMs how long to wait for first samples. Capped at 5s, because this blocks a
     *        NanoHTTPD worker thread, and a sensor that has not fired in 5s will not fire.
     */
    fun poll(
        ctx: Context,
        timeoutMs: Long = 1500L,
        includeBattery: Boolean = true,
        rateUs: Int = SensorManager.SENSOR_DELAY_UI
    ): JSONObject {
        val out = JSONObject().put("ok", true)
        val budget = timeoutMs.coerceIn(100L, 5000L)
        out.put("timeout_ms", budget)
        out.put("rate_us", rateUs)

        if (includeBattery) out.put("battery", DeviceContextReader.battery(ctx))

        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        if (sm == null) {
            out.put("sensors", JSONArray())
            out.put("warning", "no_sensor_service")
            return out
        }

        val sensors = DEFAULT_TYPES.mapNotNull { t ->
            try { sm.getDefaultSensor(t) } catch (e: Throwable) { null }
        }
        if (sensors.isEmpty()) {
            out.put("sensors", JSONArray())
            return out
        }

        val results = LinkedHashMap<Int, FloatArray>()
        val accuracy = LinkedHashMap<Int, Int>()
        val latch = CountDownLatch(sensors.size)
        val seen = HashSet<Int>()
        val rejected = HashSet<Int>()

        val thread = HandlerThread("body-sensor-poll")
        thread.start()
        val handler = Handler(thread.looper)

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                val type = event?.sensor?.type ?: return
                synchronized(results) {
                    if (seen.add(type)) {
                        results[type] = event.values.copyOf()
                        accuracy[type] = event.accuracy
                        latch.countDown()
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, acc: Int) { /* ignored */ }
        }

        try {
            for (s in sensors) {
                try {
                    // NOT SENSOR_DELAY_FASTEST. Verified on this device 2026-08-20: at
                    // FASTEST (0us) the accelerometer, gyroscope and magnetometer never
                    // register at all -- `dumpsys sensorservice` shows result=BAD_VALUE and no
                    // registration line, because an app targeting API 31+ without
                    // HIGH_SAMPLING_RATE_SENSORS cannot exceed 200Hz and those sensors' minDelay
                    // is far below that. Light/proximity (on-change) and pressure (12.5Hz) were
                    // unaffected, which is exactly why the bug looked like "some sensors are
                    // just slow". SENSOR_DELAY_UI is ~16Hz and registers fine.
                    val ok = sm.registerListener(listener, s, rateUs, handler)
                    if (!ok) synchronized(results) { rejected.add(s.type) }
                } catch (t: Throwable) { /* skip this one */ }
            }
            latch.await(budget, TimeUnit.MILLISECONDS)
        } catch (t: Throwable) {
            out.put("warning", "poll_failed: ${t.javaClass.simpleName}")
        } finally {
            // THE point of this whole design: nothing stays registered past the request.
            try { sm.unregisterListener(listener) } catch (t: Throwable) { /* ignore */ }
            try { thread.quitSafely() } catch (t: Throwable) { /* ignore */ }
        }

        val arr = JSONArray()
        for (s in sensors) {
            val o = JSONObject()
                .put("type", s.type)
                .put("kind", kindName(s.type))
                .put("name", s.name)
                .put("power_ma", s.power.toDouble())
            val vals = synchronized(results) { results[s.type] }
            if (vals == null) {
                o.put("value", JSONObject.NULL)
                if (synchronized(results) { rejected.contains(s.type) }) {
                    o.put("register_rejected", true)
                } else {
                    o.put("timed_out", true)
                }
            } else {
                val ja = JSONArray()
                for (v in vals) ja.put(v.toDouble())
                o.put("values", ja)
                o.put("value", vals[0].toDouble())
                o.put("unit", unitName(s.type))
                synchronized(results) { accuracy[s.type] }?.let { o.put("accuracy", it) }
                describe(s.type, vals)?.let { o.put("meaning", it) }
            }
            arr.put(o)
        }
        out.put("sensors", arr)
        out.put("sampled", synchronized(results) { results.size })
        return out
    }

    private fun describe(type: Int, v: FloatArray): String? = when (type) {
        Sensor.TYPE_LIGHT -> when {
            v[0] < 1f -> "dark (in a pocket or face down)"
            v[0] < 50f -> "dim indoor"
            v[0] < 500f -> "normal indoor"
            v[0] < 5000f -> "bright indoor / overcast outdoors"
            else -> "direct daylight"
        }
        Sensor.TYPE_PROXIMITY -> if (v[0] < 5f) "something is close to the screen" else "clear"
        Sensor.TYPE_ACCELEROMETER -> {
            val x = v[0]; val y = v[1]; val z = v[2]
            when {
                z > 8.5f -> "lying face up"
                z < -8.5f -> "lying face down"
                y > 8.5f -> "upright, portrait"
                y < -8.5f -> "upside down, portrait"
                x > 8.5f -> "landscape, rotated left"
                x < -8.5f -> "landscape, rotated right"
                else -> "tilted or moving"
            }
        }
        else -> null
    }

    private fun kindName(type: Int): String = when (type) {
        Sensor.TYPE_LIGHT -> "light"
        Sensor.TYPE_PROXIMITY -> "proximity"
        Sensor.TYPE_ACCELEROMETER -> "accelerometer"
        Sensor.TYPE_GYROSCOPE -> "gyroscope"
        Sensor.TYPE_MAGNETIC_FIELD -> "magnetometer"
        Sensor.TYPE_PRESSURE -> "pressure"
        Sensor.TYPE_AMBIENT_TEMPERATURE -> "ambient_temperature"
        Sensor.TYPE_RELATIVE_HUMIDITY -> "humidity"
        else -> "type_$type"
    }

    private fun unitName(type: Int): String = when (type) {
        Sensor.TYPE_LIGHT -> "lux"
        Sensor.TYPE_PROXIMITY -> "cm"
        Sensor.TYPE_ACCELEROMETER -> "m/s^2"
        Sensor.TYPE_GYROSCOPE -> "rad/s"
        Sensor.TYPE_MAGNETIC_FIELD -> "uT"
        Sensor.TYPE_PRESSURE -> "hPa"
        Sensor.TYPE_AMBIENT_TEMPERATURE -> "C"
        Sensor.TYPE_RELATIVE_HUMIDITY -> "%"
        else -> ""
    }
}
