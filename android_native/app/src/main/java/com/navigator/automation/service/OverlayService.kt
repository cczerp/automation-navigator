package com.navigator.automation.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.core.app.NotificationCompat
import com.navigator.automation.MainActivity
import com.navigator.automation.R
import com.navigator.automation.engine.EngineStatus
import com.navigator.automation.engine.RunState
import com.navigator.automation.engine.SequenceRepository
import com.navigator.automation.engine.Step
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray

class OverlayService : Service() {

    private lateinit var wm: WindowManager
    private var fabView: View? = null
    private var panelView: View? = null
    private var recorderView: View? = null
    private var statusBanner: View? = null
    private var statusText: TextView? = null

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var engineObserver: Job? = null

    // Point editor state
    private val pointEditorViews = mutableListOf<PointEditorEntry>()
    private var pointEditorBanner: View? = null
    private var editingSequenceName: String? = null

    private data class PointEditorEntry(val stepIndex: Int, val params: WindowManager.LayoutParams, val view: View)

    companion object {
        private const val CHANNEL_ID = "overlay_service"
        private const val NOTIF_ID   = 1

        const val ACTION_RECORD_POSITION = "com.navigator.automation.RECORD_POSITION"
        const val ACTION_EDIT_POINTS     = "com.navigator.automation.EDIT_POINTS"
        const val EXTRA_SEQUENCE_NAME    = "seq_name"
        const val EXTRA_POINTS_JSON      = "points_json"

        var isRunning = false
            private set

        private val _recordedCoords = MutableStateFlow<Pair<Float, Float>?>(null)
        val recordedCoords: StateFlow<Pair<Float, Float>?> = _recordedCoords
        fun clearRecordedCoords() { _recordedCoords.value = null }

        private val _editedPoints = MutableStateFlow<Map<Int, Pair<Float, Float>>?>(null)
        val editedPoints: StateFlow<Map<Int, Pair<Float, Float>>?> = _editedPoints
        fun clearEditedPoints() { _editedPoints.value = null }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        startForeground(NOTIF_ID, buildNotification())
        showFab()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RECORD_POSITION -> showPositionRecorder()
            ACTION_EDIT_POINTS -> {
                val name   = intent.getStringExtra(EXTRA_SEQUENCE_NAME) ?: return START_NOT_STICKY
                val points = intent.getStringExtra(EXTRA_POINTS_JSON) ?: return START_NOT_STICKY
                showPointEditor(name, points)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        serviceScope.cancel()
        fabView?.let  { wm.removeView(it) }
        panelView?.let { wm.removeView(it) }
        recorderView?.let { wm.removeView(it) }
        statusBanner?.let { wm.removeView(it) }
        dismissPointEditor()
    }

    // ── FAB ───────────────────────────────────────────────────────────────────

    private fun showFab() {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.END; x = 16; y = 220 }

        val btn = ImageButton(this).apply {
            setImageResource(R.drawable.ic_play_circle)
            background = null
            alpha = 0.88f
            setPadding(14, 14, 14, 14)
        }

        var dragX = 0; var dragY = 0
        var startRawX = 0f; var startRawY = 0f
        var moved = false

        btn.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    dragX = params.x; dragY = params.y
                    startRawX = ev.rawX; startRawY = ev.rawY
                    moved = false; false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - startRawX
                    val dy = ev.rawY - startRawY
                    if (moved || dx * dx + dy * dy > 100) {
                        moved = true
                        params.x = (dragX - dx).toInt()
                        params.y = (dragY + dy).toInt()
                        wm.updateViewLayout(btn.parent as View, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> { if (!moved) togglePanel(); false }
                else -> false
            }
        }

        val container = FrameLayout(this)
        container.addView(btn)
        wm.addView(container, params)
        fabView = container
    }

    // ── Sequence picker panel ─────────────────────────────────────────────────

    private fun togglePanel() {
        if (panelView != null) { dismissPanel(); return }

        val sequences = SequenceRepository.list(this)

        val params = WindowManager.LayoutParams(
            dpToPx(260),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.END; x = 16; y = 300 }

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F3EDF7"))
            elevation = 12f
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#6650A4"))
            setPadding(dpToPx(14), dpToPx(10), dpToPx(10), dpToPx(10))
        }
        val title = TextView(this).apply {
            text = "Run a sequence"
            setTextColor(Color.WHITE)
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val closeBtn = TextView(this).apply {
            text = "✕"
            setTextColor(Color.WHITE)
            textSize = 18f
            setPadding(dpToPx(8), 0, dpToPx(4), 0)
            setOnTouchListener { _, ev ->
                if (ev.action == MotionEvent.ACTION_UP) dismissPanel()
                ev.action == MotionEvent.ACTION_DOWN || ev.action == MotionEvent.ACTION_UP
            }
        }
        header.addView(title)
        header.addView(closeBtn)
        panel.addView(header)

        if (sequences.isEmpty()) {
            val empty = TextView(this).apply {
                text = "No sequences saved yet.\nOpen the app to create one."
                setTextColor(Color.parseColor("#49454F"))
                textSize = 13f
                setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(16))
            }
            panel.addView(empty)
        } else {
            val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            sequences.forEach { seqName ->
                val row = TextView(this).apply {
                    text = "▶  $seqName"
                    setTextColor(Color.parseColor("#1D1B20"))
                    textSize = 14f
                    setPadding(dpToPx(16), dpToPx(14), dpToPx(16), dpToPx(14))
                    setOnTouchListener { v, ev ->
                        when (ev.action) {
                            MotionEvent.ACTION_DOWN -> { v.setBackgroundColor(Color.parseColor("#DDD6F3")); true }
                            MotionEvent.ACTION_UP   -> { v.setBackgroundColor(Color.TRANSPARENT); runSequence(seqName); dismissPanel(); true }
                            MotionEvent.ACTION_CANCEL -> { v.setBackgroundColor(Color.TRANSPARENT); true }
                            else -> false
                        }
                    }
                }
                val divider = View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
                    setBackgroundColor(Color.parseColor("#E6E1E5"))
                }
                list.addView(row)
                list.addView(divider)
            }
            panel.addView(list)
        }

        val footer = TextView(this).apply {
            text = "Open Automation Navigator →"
            setTextColor(Color.parseColor("#6650A4"))
            textSize = 12f
            setPadding(dpToPx(16), dpToPx(10), dpToPx(16), dpToPx(12))
            setOnTouchListener { _, ev ->
                if (ev.action == MotionEvent.ACTION_UP) {
                    dismissPanel()
                    startActivity(Intent(this@OverlayService, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    })
                }
                ev.action == MotionEvent.ACTION_DOWN || ev.action == MotionEvent.ACTION_UP
            }
        }
        panel.addView(footer)

        wm.addView(panel, params)
        panelView = panel
    }

    private fun dismissPanel() {
        panelView?.let { wm.removeView(it) }
        panelView = null
    }

    private fun runSequence(name: String) {
        val seq = SequenceRepository.load(this, name) ?: run {
            Toast.makeText(this, "Could not load sequence: $name", Toast.LENGTH_SHORT).show()
            return
        }
        val svc = AutomationAccessibilityService.instance
        if (svc == null) {
            Toast.makeText(this,
                "Accessibility service not enabled.\nOpen Settings → Accessibility → Automation Navigator and turn it on.",
                Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, "▶ Starting: $name", Toast.LENGTH_SHORT).show()
        svc.execute(seq)
        observeEngine(name)
    }

    // ── Running-status banner ─────────────────────────────────────────────────

    private fun observeEngine(name: String) {
        engineObserver?.cancel()
        val engine = AutomationAccessibilityService.currentEngine ?: return
        engineObserver = serviceScope.launch {
            engine.status.collect { status ->
                when (status.state) {
                    RunState.RUNNING, RunState.PAUSED -> showStatusBanner(name, status)
                    RunState.DONE -> {
                        dismissStatusBanner()
                        Toast.makeText(this@OverlayService, "✓ Done: $name", Toast.LENGTH_SHORT).show()
                        engineObserver?.cancel()
                    }
                    RunState.ERROR -> {
                        dismissStatusBanner()
                        Toast.makeText(this@OverlayService, "⚠ Error: ${status.message}", Toast.LENGTH_LONG).show()
                        engineObserver?.cancel()
                    }
                    RunState.IDLE -> { dismissStatusBanner(); engineObserver?.cancel() }
                }
            }
        }
    }

    private fun showStatusBanner(name: String, status: EngineStatus) {
        if (statusBanner == null) {
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.BOTTOM; y = dpToPx(60) }

            val banner = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(Color.parseColor("#DD1D1B20"))
                setPadding(dpToPx(14), dpToPx(10), dpToPx(10), dpToPx(10))
            }

            val tv = TextView(this).apply {
                setTextColor(Color.WHITE)
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            statusText = tv

            val stopBtn = TextView(this).apply {
                text = "■ Stop"
                setTextColor(Color.parseColor("#FFCDD2"))
                textSize = 13f
                setTypeface(null, Typeface.BOLD)
                setPadding(dpToPx(12), 0, dpToPx(4), 0)
                setOnTouchListener { _, ev ->
                    if (ev.action == MotionEvent.ACTION_UP) {
                        AutomationAccessibilityService.instance?.stopCurrent()
                        dismissStatusBanner()
                    }
                    ev.action == MotionEvent.ACTION_DOWN || ev.action == MotionEvent.ACTION_UP
                }
            }

            banner.addView(tv)
            banner.addView(stopBtn)
            wm.addView(banner, params)
            statusBanner = banner
        }

        val stepInfo = if (status.totalSteps > 0) "Step ${status.currentStep + 1}/${status.totalSteps}" else ""
        val stateLabel = if (status.state == RunState.PAUSED) " ⏸ Paused" else ""
        statusText?.text = "⚙ $name  $stepInfo  ${status.message}$stateLabel"
    }

    private fun dismissStatusBanner() {
        statusBanner?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        statusBanner = null
        statusText = null
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun buildNotification(): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Overlay", NotificationManager.IMPORTANCE_LOW)
        )
        val tap = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Automation Navigator")
            .setContentText("Tap the floating button to run sequences")
            .setSmallIcon(R.drawable.ic_play_circle)
            .setContentIntent(tap)
            .build()
    }

    // ── Position recorder overlay ─────────────────────────────────────────────

    private fun showPositionRecorder() {
        recorderView?.let { wm.removeView(it); recorderView = null }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.argb(120, 0, 0, 0))

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#CC6650A4"))
            setPadding(dpToPx(14), dpToPx(12), dpToPx(10), dpToPx(12))
        }
        val label = TextView(this).apply {
            text = "Tap anywhere to record click position"
            setTextColor(Color.WHITE)
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val cancelBtn = TextView(this).apply {
            text = "Cancel"
            setTextColor(Color.parseColor("#FFCDD2"))
            textSize = 14f
            setPadding(dpToPx(12), 0, dpToPx(4), 0)
        }
        cancelBtn.setOnTouchListener { _, ev ->
            if (ev.action == MotionEvent.ACTION_UP) dismissRecorder()
            true
        }
        header.addView(label)
        header.addView(cancelBtn)
        root.addView(header, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP
        ))

        root.setOnTouchListener { _, ev ->
            if (ev.action == MotionEvent.ACTION_DOWN) {
                dismissRecorder()
                _recordedCoords.value = ev.rawX to ev.rawY
            }
            true
        }

        wm.addView(root, params)
        recorderView = root
    }

    private fun dismissRecorder() {
        recorderView?.let { wm.removeView(it) }
        recorderView = null
    }

    // ── Draggable point editor ────────────────────────────────────────────────

    private fun showPointEditor(sequenceName: String, pointsJson: String) {
        dismissPointEditor()
        editingSequenceName = sequenceName

        val points = try {
            val arr = JSONArray(pointsJson)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                Triple(obj.getInt("stepIndex"), obj.getDouble("x").toFloat(), obj.getDouble("y").toFloat())
            }
        } catch (e: Exception) { return }

        if (points.isEmpty()) {
            Toast.makeText(this, "No tap points in this sequence", Toast.LENGTH_SHORT).show()
            return
        }

        points.forEach { (stepIndex, x, y) ->
            val circleView = buildPointCircle(stepIndex + 1)
            val halfSize = dpToPx(26)
            val circleParams = WindowManager.LayoutParams(
                dpToPx(52), dpToPx(52),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                this.x = (x - halfSize).toInt().coerceAtLeast(0)
                this.y = (y - halfSize).toInt().coerceAtLeast(0)
            }

            var startX = 0; var startY = 0
            var startRawX = 0f; var startRawY = 0f

            circleView.setOnTouchListener { _, ev ->
                when (ev.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = circleParams.x; startY = circleParams.y
                        startRawX = ev.rawX; startRawY = ev.rawY
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        circleParams.x = (startX + ev.rawX - startRawX).toInt()
                        circleParams.y = (startY + ev.rawY - startRawY).toInt()
                        try { wm.updateViewLayout(circleView, circleParams) } catch (_: Exception) {}
                        true
                    }
                    else -> false
                }
            }

            wm.addView(circleView, circleParams)
            pointEditorViews.add(PointEditorEntry(stepIndex, circleParams, circleView))
        }

        showPointEditorBanner()
    }

    private fun buildPointCircle(number: Int): TextView {
        val size = dpToPx(52)
        return TextView(this).apply {
            text = number.toString()
            setTextColor(Color.WHITE)
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#DD6650A4"))
                setStroke(dpToPx(2), Color.WHITE)
            }
            layoutParams = ViewGroup.LayoutParams(size, size)
        }
    }

    private fun showPointEditorBanner() {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM; y = dpToPx(60) }

        val banner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#DD1D1B20"))
            setPadding(dpToPx(14), dpToPx(10), dpToPx(10), dpToPx(10))
        }

        val label = TextView(this).apply {
            text = "Drag circles to reposition taps"
            setTextColor(Color.WHITE)
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val saveBtn = TextView(this).apply {
            text = "Save"
            setTextColor(Color.parseColor("#90EE90"))
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setPadding(dpToPx(12), 0, dpToPx(4), 0)
            setOnTouchListener { _, ev ->
                if (ev.action == MotionEvent.ACTION_UP) saveEditedPoints()
                ev.action == MotionEvent.ACTION_DOWN || ev.action == MotionEvent.ACTION_UP
            }
        }

        val cancelBtn = TextView(this).apply {
            text = "Cancel"
            setTextColor(Color.parseColor("#FFCDD2"))
            textSize = 14f
            setPadding(dpToPx(12), 0, dpToPx(4), 0)
            setOnTouchListener { _, ev ->
                if (ev.action == MotionEvent.ACTION_UP) dismissPointEditor()
                ev.action == MotionEvent.ACTION_DOWN || ev.action == MotionEvent.ACTION_UP
            }
        }

        banner.addView(label)
        banner.addView(saveBtn)
        banner.addView(cancelBtn)
        wm.addView(banner, params)
        pointEditorBanner = banner
    }

    private fun saveEditedPoints() {
        val seqName = editingSequenceName ?: return
        val half = dpToPx(26).toFloat()

        val updatesMap = pointEditorViews.associate { entry ->
            entry.stepIndex to (entry.params.x + half to entry.params.y + half)
        }

        val seq = SequenceRepository.load(this, seqName)
        if (seq != null) {
            val newSteps = seq.steps.mapIndexed { idx, step ->
                val upd = updatesMap[idx] ?: return@mapIndexed step
                when (step) {
                    is Step.TapCoords -> step.copy(x = upd.first, y = upd.second)
                    is Step.LongPress -> step.copy(x = upd.first, y = upd.second)
                    else -> step
                }
            }
            SequenceRepository.save(this, seq.copy(steps = newSteps))
        }

        _editedPoints.value = updatesMap
        dismissPointEditor()
        Toast.makeText(this, "Tap positions saved", Toast.LENGTH_SHORT).show()
    }

    private fun dismissPointEditor() {
        pointEditorViews.forEach { entry -> try { wm.removeView(entry.view) } catch (_: Exception) {} }
        pointEditorViews.clear()
        pointEditorBanner?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        pointEditorBanner = null
        editingSequenceName = null
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()
}
