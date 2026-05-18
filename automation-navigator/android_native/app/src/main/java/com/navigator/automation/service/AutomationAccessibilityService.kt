package com.navigator.automation.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.navigator.automation.engine.Sequence
import com.navigator.automation.engine.SequenceEngine
import kotlinx.coroutines.*

class AutomationAccessibilityService : AccessibilityService() {

    companion object {
        var instance: AutomationAccessibilityService? = null
            private set

        // Exposed so RunScreen can observe status without binding to the service
        var currentEngine: SequenceEngine? = null
            private set

        const val ACTION_RUN      = "com.navigator.automation.RUN"
        const val ACTION_STOP     = "com.navigator.automation.STOP"
        const val ACTION_PAUSE    = "com.navigator.automation.PAUSE"
        const val ACTION_RESUME   = "com.navigator.automation.RESUME"
        const val EXTRA_SEQUENCE  = "sequence_json"
    }

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var engine: SequenceEngine? = null
        set(value) { field = value; currentEngine = value }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        serviceScope.cancel()
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() { engine?.stop() }

    // Commands arrive via startService() intents from OverlayService / MainActivity
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RUN -> {
                val json = intent.getStringExtra(EXTRA_SEQUENCE) ?: return START_NOT_STICKY
                val seq  = runCatching {
                    Sequence.fromJson(org.json.JSONObject(json))
                }.getOrNull() ?: return START_NOT_STICKY
                engine?.stop()
                engine = SequenceEngine(this, seq, serviceScope)
                engine!!.start()
            }
            ACTION_STOP   -> engine?.stop()
            ACTION_PAUSE  -> engine?.pause()
            ACTION_RESUME -> engine?.resume()
        }
        return START_NOT_STICKY
    }

    // ── Gesture helpers (called by SequenceEngine) ────────────────────────

    fun tapNode(node: AccessibilityNodeInfo): Boolean {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        return tapCoords(rect.exactCenterX(), rect.exactCenterY())
    }

    /** Click via accessibility ACTION_CLICK — more reliable for standard Android buttons. */
    fun clickNode(node: AccessibilityNodeInfo): Boolean {
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    fun tapCoords(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        var done = false
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription) { done = true }
            override fun onCancelled(g: GestureDescription) { done = true }
        }, null)
        // Busy-wait — called from coroutine on Dispatchers.Default
        val deadline = System.currentTimeMillis() + 1_000
        while (!done && System.currentTimeMillis() < deadline) Thread.sleep(10)
        return done
    }

    fun swipe(direction: String) {
        val display = resources.displayMetrics
        val w = display.widthPixels.toFloat()
        val h = display.heightPixels.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val path = Path()
        when (direction.lowercase()) {
            "up"    -> { path.moveTo(cx, cy + h * 0.2f); path.lineTo(cx, cy - h * 0.2f) }
            "down"  -> { path.moveTo(cx, cy - h * 0.2f); path.lineTo(cx, cy + h * 0.2f) }
            "left"  -> { path.moveTo(cx + w * 0.2f, cy); path.lineTo(cx - w * 0.2f, cy) }
            "right" -> { path.moveTo(cx - w * 0.2f, cy); path.lineTo(cx + w * 0.2f, cy) }
            else    -> return
        }
        val stroke  = GestureDescription.StrokeDescription(path, 0, 300)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    // ── Node search (called by SequenceEngine) ────────────────────────────

    /** Find first clickable node whose text/description/hint contains [text] (case-insensitive). */
    fun findNodeByText(text: String): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        return searchNode(root, text)
    }

    private fun searchNode(node: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        val t = text.lowercase()
        val nodeText = listOf(
            node.text?.toString(),
            node.contentDescription?.toString(),
            node.hintText?.toString()
        ).filterNotNull().any { it.lowercase().contains(t) }

        if (nodeText) {
            // Prefer a clickable ancestor if the matched node itself isn't clickable
            return findClickableAncestor(node) ?: node
        }
        for (i in 0 until node.childCount) {
            val result = searchNode(node.getChild(i) ?: continue, text)
            if (result != null) return result
        }
        return null
    }

    private fun findClickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isClickable) return node
        val parent = node.parent ?: return null
        return findClickableAncestor(parent)
    }

    /** Collect all leaf text nodes — used by WatchCorners / DismissAd to scan for dismiss UI. */
    fun collectAllText(): List<Pair<String, Rect>> {
        val root = rootInActiveWindow ?: return emptyList()
        val results = mutableListOf<Pair<String, Rect>>()
        collectText(root, results)
        return results
    }

    private fun collectText(node: AccessibilityNodeInfo, out: MutableList<Pair<String, Rect>>) {
        val txt = node.text?.toString()?.trim()
        if (!txt.isNullOrEmpty()) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            out.add(txt to rect)
        }
        for (i in 0 until node.childCount) collectText(node.getChild(i) ?: continue, out)
    }

    /** Look for dismiss text (X, ×, Close, Skip, Done …) in screen corners. */
    fun findDismissNode(): AccessibilityNodeInfo? {
        val dismissWords = setOf("x", "×", "close", "skip", "done", "dismiss", "✕", "✖", "got it", "no thanks")
        val root = rootInActiveWindow ?: return null
        return findDismissInTree(root, dismissWords)
    }

    private fun findDismissInTree(
        node: AccessibilityNodeInfo,
        words: Set<String>
    ): AccessibilityNodeInfo? {
        val txt = listOf(node.text?.toString(), node.contentDescription?.toString())
            .filterNotNull()
            .joinToString(" ")
            .lowercase()
            .trim()
        if (words.any { txt == it || txt.contains(it) }) {
            return findClickableAncestor(node) ?: node
        }
        for (i in 0 until node.childCount) {
            val r = findDismissInTree(node.getChild(i) ?: continue, words)
            if (r != null) return r
        }
        return null
    }

    // ── Typing ────────────────────────────────────────────────────────────

    fun typeText(text: String) {
        val focused = findFocusedInput() ?: return
        val args = Bundle().apply { putString(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun findFocusedInput(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        return findEditText(root)
    }

    private fun findEditText(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.className?.contains("EditText") == true && node.isEnabled) return node
        for (i in 0 until node.childCount) {
            val r = findEditText(node.getChild(i) ?: continue)
            if (r != null) return r
        }
        return null
    }

    // ── App launch ────────────────────────────────────────────────────────

    fun launchApp(target: String): Boolean {
        val pm = packageManager
        // Try as package name first
        val launchIntent = pm.getLaunchIntentForPackage(target)
            ?: pm.getInstalledApplications(0)
                .firstOrNull { info ->
                    pm.getApplicationLabel(info).toString().lowercase() == target.lowercase()
                }
                ?.let { pm.getLaunchIntentForPackage(it.packageName) }
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launchIntent)
            return true
        }
        return false
    }
}
