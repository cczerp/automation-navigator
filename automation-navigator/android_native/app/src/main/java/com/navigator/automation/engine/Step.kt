package com.navigator.automation.engine

import org.json.JSONObject

sealed class Step {
    data class TapText(val text: String) : Step()
    data class TapCoords(val x: Float, val y: Float) : Step()
    data class LongPress(val x: Float, val y: Float, val durationMs: Long = 500L) : Step()
    data class WaitSeconds(val seconds: Float) : Step()
    data class TypeText(val text: String) : Step()
    data class Swipe(val direction: String) : Step()   // up | down | left | right
    data class PressKey(val key: String) : Step()
    data class LaunchApp(val target: String) : Step()  // package name or app label
    data class WatchCorners(val timeoutSeconds: Int = 25) : Step()
    data class CheckBranch(val triggerText: String, val thenSequence: String) : Step()
    object PressBack : Step()
    object PressHome : Step()
    object DismissAd : Step()

    fun label(): String = when (this) {
        is TapText       -> "Tap: $text"
        is TapCoords     -> "Tap (%.0f, %.0f)".format(x, y)
        is LongPress     -> "Long press (%.0f, %.0f) ${durationMs}ms".format(x, y)
        is WaitSeconds   -> "Wait: ${seconds}s"
        is TypeText      -> "Type: $text"
        is Swipe         -> "Swipe $direction"
        is PressKey      -> "Keys: $key"
        is LaunchApp     -> "Launch: $target"
        is WatchCorners  -> "Watch corners (${timeoutSeconds}s)"
        is CheckBranch   -> "If '$triggerText' → $thenSequence"
        PressBack        -> "Press Back"
        PressHome        -> "Press Home"
        DismissAd        -> "Dismiss Ad"
    }

    fun toJson(): JSONObject = JSONObject().apply {
        when (val s = this@Step) {
            is TapText      -> { put("type", "click");         put("target", s.text) }
            is TapCoords    -> { put("type", "tap_coords");    put("x", s.x); put("y", s.y) }
            is LongPress    -> { put("type", "long_press");    put("x", s.x); put("y", s.y); put("duration_ms", s.durationMs) }
            is WaitSeconds  -> { put("type", "wait");          put("seconds", s.seconds) }
            is TypeText     -> { put("type", "type");          put("target", s.text) }
            is Swipe        -> { put("type", "swipe");         put("direction", s.direction) }
            is PressKey     -> { put("type", "key");           put("target", s.key) }
            is LaunchApp    -> { put("type", "launch");        put("target", s.target) }
            is WatchCorners -> { put("type", "watch_corners"); put("timeout", s.timeoutSeconds) }
            is CheckBranch  -> {
                put("type", "check_branch")
                put("target", s.triggerText)
                put("then_sequence", s.thenSequence)
            }
            PressBack       -> put("type", "press_back")
            PressHome       -> put("type", "press_home")
            DismissAd       -> put("type", "dismiss_ad")
        }
    }

    companion object {
        fun fromJson(json: JSONObject): Step? = when (json.optString("type")) {
            "click"         -> TapText(json.optString("target"))
            "tap_coords"    -> TapCoords(json.optDouble("x").toFloat(), json.optDouble("y").toFloat())
            "long_press"    -> LongPress(
                                   json.optDouble("x").toFloat(),
                                   json.optDouble("y").toFloat(),
                                   json.optLong("duration_ms", 500L)
                               )
            "wait"          -> WaitSeconds(json.optDouble("seconds", 1.0).toFloat())
            "type"          -> TypeText(json.optString("target"))
            "swipe"         -> Swipe(json.optString("direction", "up"))
            "key"           -> PressKey(json.optString("target"))
            "launch"        -> LaunchApp(json.optString("target"))
            "watch_corners" -> WatchCorners(json.optInt("timeout", 25))
            "check_branch"  -> CheckBranch(
                                   json.optString("target"),
                                   json.optString("then_sequence")
                               )
            "press_back"    -> PressBack
            "press_home"    -> PressHome
            "dismiss_ad"    -> DismissAd
            else            -> null
        }
    }
}
