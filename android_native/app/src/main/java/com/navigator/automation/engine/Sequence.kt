package com.navigator.automation.engine

import org.json.JSONArray
import org.json.JSONObject

data class Sequence(
    val name: String,
    val steps: List<Step>,
    val loopCount: Int = 1,
    val loopDelaySeconds: Float = 1f,
    val stepDelayMs: Long = 500L   // pause between every step
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("loop_count", loopCount)
        put("loop_delay_seconds", loopDelaySeconds)
        put("step_delay_ms", stepDelayMs)
        val arr = JSONArray()
        steps.forEach { arr.put(it.toJson()) }
        put("steps", arr)
    }

    companion object {
        fun fromJson(json: JSONObject): Sequence {
            val stepsArr = json.optJSONArray("steps") ?: JSONArray()
            val steps = (0 until stepsArr.length())
                .mapNotNull { Step.fromJson(stepsArr.getJSONObject(it)) }
            return Sequence(
                name = json.optString("name", "Unnamed"),
                steps = steps,
                loopCount = json.optInt("loop_count", 1),
                loopDelaySeconds = json.optDouble("loop_delay_seconds", 1.0).toFloat(),
                stepDelayMs = json.optLong("step_delay_ms", 500L)
            )
        }
    }
}
