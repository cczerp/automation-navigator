package com.navigator.automation.engine

import org.json.JSONObject

sealed class Step {
    data class TapText(val text: String) : Step()
    data class TapButton(val text: String) : Step()   // accessibility ACTION_CLICK on matched node
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
        is TapButton     -> "Tap button: $text"
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
            is TapButton    -> { put("type", "click_button");  put("target", s.text) }
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
            "click_button"  -> TapButton(json.optString("target"))
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
