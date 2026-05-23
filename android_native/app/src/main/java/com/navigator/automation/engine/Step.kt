package com.navigator.automation.engine

import org.json.JSONObject

sealed class Step {
    data class TapText(val text: String) : Step()
    data class TapCoords(val x: Float, val y: Float, val delayMs: Long = 0L, val repeatCount: Int = 1) : Step()
    data class LongPress(val x: Float, val y: Float, val durationMs: Long = 500L, val delayMs: Long = 0L, val repeatCount: Int = 1) : Step()
    data class WaitSeconds(val seconds: Float) : Step()
    data class TypeText(val text: String) : Step()
    data class Swipe(val direction: String, val delayMs: Long = 0L) : Step()
    data class SwipeCoords(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val durationMs: Long = 300L, val delayMs: Long = 0L) : Step()
    data class PressKey(val key: String) : Step()
    data class LaunchApp(val target: String) : Step()
    data class WatchCorners(val timeoutSeconds: Int = 25) : Step()
    data class TapWhen(val text: String, val timeoutSeconds: Int = 30) : Step()
    data class CheckBranch(val triggerText: String, val thenSequence: String) : Step()
    object PressBack : Step()
    object PressHome : Step()
    object DismissAd : Step()

    fun label(): String = when (this) {
        is TapText      -> "Tap: $text"
        is TapCoords    -> buildString {
            append("Tap (%.0f, %.0f)".format(x, y))
            if (repeatCount > 1) append(" ×$repeatCount")
            if (delayMs > 0) append(" +${delayMs}ms")
        }
        is LongPress    -> buildString {
            append("Long press (%.0f, %.0f) ${durationMs}ms".format(x, y))
            if (repeatCount > 1) append(" ×$repeatCount")
            if (delayMs > 0) append(" +${delayMs}ms")
        }
        is WaitSeconds  -> "Wait: ${seconds}s"
        is TypeText     -> "Type: $text"
        is Swipe        -> buildString {
            append("Swipe $direction")
            if (delayMs > 0) append(" +${delayMs}ms")
        }
        is SwipeCoords  -> buildString {
            append("Swipe (%.0f,%.0f)→(%.0f,%.0f) ${durationMs}ms".format(x1, y1, x2, y2))
            if (delayMs > 0) append(" +${delayMs}ms")
        }
        is PressKey     -> "Keys: $key"
        is LaunchApp    -> "Launch: $target"
        is WatchCorners -> "Watch corners (${timeoutSeconds}s)"
        is TapWhen      -> "Wait & tap: \"$text\" (up to ${timeoutSeconds}s)"
        is CheckBranch  -> "If '$triggerText' → $thenSequence"
        PressBack       -> "Press Back"
        PressHome       -> "Press Home"
        DismissAd       -> "Dismiss Ad"
    }

    fun toJson(): JSONObject = JSONObject().apply {
        when (val s = this@Step) {
            is TapText     -> { put("type", "click"); put("target", s.text) }
            is TapCoords   -> {
                put("type", "tap_coords"); put("x", s.x); put("y", s.y)
                if (s.delayMs > 0) put("delay_ms", s.delayMs)
                if (s.repeatCount != 1) put("repeat", s.repeatCount)
            }
            is LongPress   -> {
                put("type", "long_press"); put("x", s.x); put("y", s.y); put("duration_ms", s.durationMs)
                if (s.delayMs > 0) put("delay_ms", s.delayMs)
                if (s.repeatCount != 1) put("repeat", s.repeatCount)
            }
            is WaitSeconds -> { put("type", "wait"); put("seconds", s.seconds) }
            is TypeText    -> { put("type", "type"); put("target", s.text) }
            is Swipe       -> {
                put("type", "swipe"); put("direction", s.direction)
                if (s.delayMs > 0) put("delay_ms", s.delayMs)
            }
            is SwipeCoords -> {
                put("type", "swipe_coords")
                put("x1", s.x1); put("y1", s.y1); put("x2", s.x2); put("y2", s.y2)
                put("duration_ms", s.durationMs)
                if (s.delayMs > 0) put("delay_ms", s.delayMs)
            }
            is PressKey    -> { put("type", "key"); put("target", s.key) }
            is LaunchApp   -> { put("type", "launch"); put("target", s.target) }
            is WatchCorners -> { put("type", "watch_corners"); put("timeout", s.timeoutSeconds) }
            is TapWhen     -> { put("type", "tap_when"); put("target", s.text); put("timeout", s.timeoutSeconds) }
            is CheckBranch -> { put("type", "check_branch"); put("target", s.triggerText); put("then_sequence", s.thenSequence) }
            PressBack      -> put("type", "press_back")
            PressHome      -> put("type", "press_home")
            DismissAd      -> put("type", "dismiss_ad")
        }
    }

    companion object {
        fun fromJson(json: JSONObject): Step? = when (json.optString("type")) {
            "click"        -> TapText(json.optString("target"))
            "tap_coords"   -> TapCoords(
                                  json.optDouble("x").toFloat(),
                                  json.optDouble("y").toFloat(),
                                  json.optLong("delay_ms", 0L),
                                  json.optInt("repeat", 1)
                              )
            "long_press"   -> LongPress(
                                  json.optDouble("x").toFloat(),
                                  json.optDouble("y").toFloat(),
                                  json.optLong("duration_ms", 500L),
                                  json.optLong("delay_ms", 0L),
                                  json.optInt("repeat", 1)
                              )
            "wait"         -> WaitSeconds(json.optDouble("seconds", 1.0).toFloat())
            "type"         -> TypeText(json.optString("target"))
            "swipe"        -> Swipe(json.optString("direction", "up"), json.optLong("delay_ms", 0L))
            "swipe_coords" -> SwipeCoords(
                                  json.optDouble("x1").toFloat(),
                                  json.optDouble("y1").toFloat(),
                                  json.optDouble("x2").toFloat(),
                                  json.optDouble("y2").toFloat(),
                                  json.optLong("duration_ms", 300L),
                                  json.optLong("delay_ms", 0L)
                              )
            "key"          -> PressKey(json.optString("target"))
            "launch"       -> LaunchApp(json.optString("target"))
            "watch_corners" -> WatchCorners(json.optInt("timeout", 25))
            "tap_when"      -> TapWhen(json.optString("target"), json.optInt("timeout", 30))
            "check_branch"  -> CheckBranch(json.optString("target"), json.optString("then_sequence"))
            "press_back"   -> PressBack
            "press_home"   -> PressHome
            "dismiss_ad"   -> DismissAd
            else           -> null
        }
    }
}
