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
import android.text.InputType
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
import com.navigator.automation.engine.Sequence
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
    private var closeZoneView: View? = null
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

    // Sequence editor state
    private var seqEditorView: View? = null
    private var seqEditorList: LinearLayout? = null
    private var seqEditorName: String? = null
    private val seqEditorSteps = mutableListOf<Step>()

    // Step editor state
    private var stepEditorView: View? = null
    private var stepEditIndex: Int? = null  // null = adding new
    private var stepFormFields: StepFormFields? = null
    private var onCoordsRecorded: ((Float, Float) -> Unit)? = null

    // Time unit helpers (mirrored from app for use in overlay views)
    private fun msToDisplay(ms: Long): Pair<String, String> = when {
        ms == 0L -> "0" to "ms"
        ms % 60_000L == 0L -> (ms / 60_000L).toString() to "min"
        ms % 1_000L == 0L  -> (ms / 1_000L).toString()  to "s"
        else -> ms.toString() to "ms"
    }
    private fun displayToMs(v: String, u: String): Long {
        val n = v.toLongOrNull() ?: 0L
        return when (u) { "s" -> n * 1_000L; "min" -> n * 60_000L; else -> n }
    }
    private fun secsToDisplay(s: Float): Pair<String, String> = when {
        s >= 60f && s % 60f == 0f -> (s / 60f).toInt().toString() to "min"
        else -> s.toString() to "s"
    }
    private fun displayToSecs(v: String, u: String): Float {
        val n = v.toFloatOrNull() ?: 1f
        return if (u == "min") n * 60f else n
    }

    // ── Colors ────────────────────────────────────────────────────────────────

    private val CLR_TERRA  = Color.parseColor("#BF6B52")  // terracotta
    private val CLR_SAGE   = Color.parseColor("#5E8B72")  // sage green
    private val CLR_CREAM  = Color.parseColor("#F5EDD8")  // cream
    private val CLR_TEXT   = Color.parseColor("#3D2419")  // dark brown text
    private val CLR_LIGHT  = Color.parseColor("#EEE2D3")  // light surface
    private val CLR_DIVIDER= Color.parseColor("#D4C4B4")  // divider

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
        closeZoneView?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        panelView?.let { wm.removeView(it) }
        recorderView?.let { wm.removeView(it) }
        statusBanner?.let { wm.removeView(it) }
        dismissPointEditor()
        dismissSeqEditor()
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
        val dm = resources.displayMetrics
        val closeZoneRadius = dpToPx(44)

        fun isOverCloseZone(rawX: Float, rawY: Float): Boolean {
            val cx = dm.widthPixels / 2f
            val cy = dm.heightPixels - dpToPx(72).toFloat()
            val dx = rawX - cx; val dy = rawY - cy
            return dx * dx + dy * dy <= closeZoneRadius * closeZoneRadius * 4
        }

        fun showCloseZone() {
            if (closeZoneView != null) return
            val zp = WindowManager.LayoutParams(dpToPx(80), dpToPx(80),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = dpToPx(32) }
            val zone = TextView(this@OverlayService).apply {
                text = "✕"; textSize = 22f; setTypeface(null, Typeface.BOLD)
                setTextColor(Color.WHITE); gravity = Gravity.CENTER
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#CC333333")) }
            }
            wm.addView(zone, zp); closeZoneView = zone
        }

        fun hideCloseZone() {
            closeZoneView?.let { try { wm.removeView(it) } catch (_: Exception) {} }
            closeZoneView = null
        }

        btn.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> { dragX = params.x; dragY = params.y; startRawX = ev.rawX; startRawY = ev.rawY; moved = false; false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - startRawX; val dy = ev.rawY - startRawY
                    if (moved || dx * dx + dy * dy > 100) {
                        moved = true
                        params.x = (dragX - dx).toInt(); params.y = (dragY + dy).toInt()
                        wm.updateViewLayout(btn.parent as View, params)
                        showCloseZone()
                        val hovering = isOverCloseZone(ev.rawX, ev.rawY)
                        (closeZoneView as? TextView)?.background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(if (hovering) Color.parseColor("#CCE53935") else Color.parseColor("#CC333333"))
                        }
                    }; true
                }
                MotionEvent.ACTION_UP -> { hideCloseZone(); if (!moved) togglePanel() else if (isOverCloseZone(ev.rawX, ev.rawY)) stopSelf(); false }
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
        val params = WindowManager.LayoutParams(dpToPx(270),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.END; x = 16; y = 300 }

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(CLR_CREAM)
            elevation = 12f
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(CLR_TERRA)
            setPadding(dpToPx(14), dpToPx(10), dpToPx(10), dpToPx(10))
        }
        val title = TextView(this).apply {
            text = "Run a sequence"; setTextColor(Color.WHITE); textSize = 15f
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val closeBtn = TextView(this).apply {
            text = "✕"; setTextColor(Color.WHITE); textSize = 18f
            setPadding(dpToPx(8), 0, dpToPx(4), 0)
            setOnTouchListener { _, ev ->
                if (ev.action == MotionEvent.ACTION_UP) dismissPanel()
                ev.action == MotionEvent.ACTION_DOWN || ev.action == MotionEvent.ACTION_UP
            }
        }
        header.addView(title); header.addView(closeBtn)
        panel.addView(header)

        if (sequences.isEmpty()) {
            panel.addView(TextView(this).apply {
                text = "No sequences saved yet.\nOpen the app to create one."
                setTextColor(CLR_TEXT); textSize = 13f
                setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(16))
            })
        } else {
            val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            sequences.forEach { seqName ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(dpToPx(16), dpToPx(4), dpToPx(8), dpToPx(4))
                }
                val nameBtn = TextView(this).apply {
                    text = "▶  $seqName"; setTextColor(CLR_TEXT); textSize = 14f
                    setPadding(0, dpToPx(10), 0, dpToPx(10))
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    setOnTouchListener { v, ev ->
                        when (ev.action) {
                            MotionEvent.ACTION_DOWN   -> { v.setBackgroundColor(CLR_LIGHT); true }
                            MotionEvent.ACTION_UP     -> { v.setBackgroundColor(Color.TRANSPARENT); runSequence(seqName); dismissPanel(); true }
                            MotionEvent.ACTION_CANCEL -> { v.setBackgroundColor(Color.TRANSPARENT); true }
                            else -> false
                        }
                    }
                }
                // ✎ opens the in-overlay sequence editor
                val editBtn = TextView(this).apply {
                    text = "✎"; setTextColor(CLR_TERRA); textSize = 18f
                    setPadding(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8))
                    setOnTouchListener { _, ev ->
                        if (ev.action == MotionEvent.ACTION_UP) { dismissPanel(); showSeqEditor(seqName) }
                        ev.action == MotionEvent.ACTION_DOWN || ev.action == MotionEvent.ACTION_UP
                    }
                }
                row.addView(nameBtn); row.addView(editBtn)
                list.addView(row)
                list.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
                    setBackgroundColor(CLR_DIVIDER)
                })
            }
            panel.addView(list)
        }

        panel.addView(TextView(this).apply {
            text = "Open Automation Navigator →"; setTextColor(CLR_TERRA); textSize = 12f
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
        })

        wm.addView(panel, params); panelView = panel
    }

    private fun dismissPanel() { panelView?.let { wm.removeView(it) }; panelView = null }

    private fun runSequence(name: String) {
        val seq = SequenceRepository.load(this, name) ?: run {
            Toast.makeText(this, "Could not load sequence: $name", Toast.LENGTH_SHORT).show(); return
        }
        val svc = AutomationAccessibilityService.instance
        if (svc == null) {
            Toast.makeText(this, "Accessibility service not enabled.\nOpen Settings → Accessibility → Automation Navigator.", Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, "▶ Starting: $name", Toast.LENGTH_SHORT).show()
        svc.execute(seq)
        observeEngine(name)
    }

    // ── In-overlay sequence editor ────────────────────────────────────────────

    private fun showSeqEditor(name: String) {
        dismissSeqEditor()
        val seq = SequenceRepository.load(this, name) ?: run {
            Toast.makeText(this, "Could not load: $name", Toast.LENGTH_SHORT).show(); return
        }
        seqEditorName = name
        seqEditorSteps.clear()
        seqEditorSteps.addAll(seq.steps)

        val dm = resources.displayMetrics
        val panelW = (dm.widthPixels * 0.88f).toInt()
        val panelH = (dm.heightPixels * 0.76f).toInt()

        // Full-screen dim background + panel (no FLAG_NOT_FOCUSABLE so keyboard works)
        val wParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(150, 0, 0, 0))
            setOnTouchListener { _, ev ->
                // tapping outside panel closes editor
                if (ev.action == MotionEvent.ACTION_UP) dismissSeqEditor()
                true
            }
        }

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(CLR_CREAM)
                cornerRadius = dpToPx(14).toFloat()
            }
            elevation = 16f
            layoutParams = FrameLayout.LayoutParams(panelW, panelH, Gravity.CENTER).also { it.topMargin = dpToPx(40) }
            setOnTouchListener { _, _ -> true }  // consume touches so they don't reach the dim bg
        }

        // Header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(CLR_TERRA)
            setPadding(dpToPx(14), dpToPx(12), dpToPx(12), dpToPx(12))
        }
        header.addView(TextView(this).apply {
            text = name; setTextColor(Color.WHITE); textSize = 15f; setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(makeTextBtn("Save", Color.WHITE, CLR_SAGE, dpToPx(6)) { saveSeqEditor() })
        header.addView(makeTextBtn("  ✕  ", Color.WHITE, Color.TRANSPARENT, 0) { dismissSeqEditor() })
        panel.addView(header)

        // Step list
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val stepList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(10), dpToPx(8), dpToPx(10), dpToPx(8))
        }
        seqEditorList = stepList
        scroll.addView(stepList)
        panel.addView(scroll)

        // Footer: add step + loops info
        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(CLR_LIGHT)
            setPadding(dpToPx(12), dpToPx(8), dpToPx(12), dpToPx(8))
        }
        footer.addView(TextView(this).apply {
            text = "Loops: ${seq.loopCount}  Delay: ${seq.loopDelaySeconds}s"
            setTextColor(CLR_TEXT); textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { gravity = Gravity.CENTER_VERTICAL }
        })
        footer.addView(makeTextBtn("+ Add Step", Color.WHITE, CLR_TERRA, dpToPx(8)) {
            showStepEditorForm(null, null)
        })
        panel.addView(footer)

        root.addView(panel)
        wm.addView(root, wParams)
        seqEditorView = root
        refreshSeqEditorList()
    }

    private fun refreshSeqEditorList() {
        val list = seqEditorList ?: return
        list.removeAllViews()
        if (seqEditorSteps.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "No steps — tap Add Step"; setTextColor(CLR_TEXT)
                textSize = 13f; gravity = Gravity.CENTER
                setPadding(0, dpToPx(24), 0, dpToPx(24))
            })
            return
        }
        seqEditorSteps.forEachIndexed { idx, step ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dpToPx(8).toFloat() }
                setPadding(dpToPx(8), dpToPx(8), dpToPx(4), dpToPx(8))
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.setMargins(0, 0, 0, dpToPx(5))
                layoutParams = lp
            }
            // Number badge
            val sz = dpToPx(22)
            row.addView(TextView(this).apply {
                text = "${idx + 1}"; setTextColor(Color.WHITE); textSize = 10f
                setTypeface(null, Typeface.BOLD); gravity = Gravity.CENTER
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(CLR_TERRA) }
                layoutParams = LinearLayout.LayoutParams(sz, sz).apply { gravity = Gravity.CENTER_VERTICAL; setMargins(0, 0, dpToPx(6), 0) }
            })
            // Label
            row.addView(TextView(this).apply {
                text = step.label(); textSize = 12f; setTextColor(CLR_TEXT)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { gravity = Gravity.CENTER_VERTICAL }
            })
            // Controls
            row.addView(makeIconBtn("↑", CLR_SAGE) { if (idx > 0) { seqEditorSteps.apply { val t = this[idx]; this[idx] = this[idx-1]; this[idx-1] = t }; refreshSeqEditorList() } })
            row.addView(makeIconBtn("↓", CLR_SAGE) { if (idx < seqEditorSteps.size - 1) { seqEditorSteps.apply { val t = this[idx]; this[idx] = this[idx+1]; this[idx+1] = t }; refreshSeqEditorList() } })
            row.addView(makeIconBtn("✎", CLR_TERRA) { showStepEditorForm(idx, step) })
            row.addView(makeIconBtn("✗", Color.parseColor("#C0392B")) { seqEditorSteps.removeAt(idx); refreshSeqEditorList() })
            list.addView(row)
        }
    }

    private fun saveSeqEditor() {
        val name = seqEditorName ?: return
        val existing = SequenceRepository.load(this, name) ?: return
        SequenceRepository.save(this, existing.copy(steps = seqEditorSteps.toList()))
        dismissSeqEditor()
        Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
    }

    private fun dismissSeqEditor() {
        dismissStepEditorForm()
        seqEditorView?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        seqEditorView = null; seqEditorList = null; seqEditorName = null; seqEditorSteps.clear()
    }

    // ── Step editor form ──────────────────────────────────────────────────────

    private data class StepFormFields(
        var typeIdx: Int,
        val etText: EditText,
        val etX: EditText, val etY: EditText,
        val etX1: EditText, val etY1: EditText,
        val etX2: EditText, val etY2: EditText,
        val etWaitVal: EditText, var waitUnit: String,
        val etToutVal: EditText, var toutUnit: String,
        val etDurVal: EditText, var durUnit: String,
        val etDelayVal: EditText, var delayUnit: String,
        val etRepeat: EditText,
        var direction: String,
        var key: String,
        var branchSeq: String,
        val fieldsContainer: LinearLayout
    )

    private fun showStepEditorForm(editIndex: Int?, existingStep: Step?) {
        dismissStepEditorForm()
        stepEditIndex = editIndex

        val dm = resources.displayMetrics
        val formW = (dm.widthPixels * 0.82f).toInt()

        val wParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            0,  // allow focus for keyboard
            PixelFormat.TRANSLUCENT
        )

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(120, 0, 0, 0))
            setOnTouchListener { _, ev -> if (ev.action == MotionEvent.ACTION_UP) dismissStepEditorForm(); true }
        }

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dpToPx(14).toFloat() }
            elevation = 20f
            layoutParams = FrameLayout.LayoutParams(formW, LinearLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER).also { it.topMargin = dpToPx(60) }
            setOnTouchListener { _, _ -> true }
        }

        // Header
        val hdr = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(CLR_TERRA)
            setPadding(dpToPx(14), dpToPx(12), dpToPx(12), dpToPx(12))
        }
        hdr.addView(TextView(this).apply {
            text = if (editIndex == null) "Add Step" else "Edit Step"
            setTextColor(Color.WHITE); textSize = 15f; setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        hdr.addView(makeTextBtn("  ✕  ", Color.WHITE, Color.TRANSPARENT, 0) { dismissStepEditorForm() })
        panel.addView(hdr)

        val scrollForm = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            // cap height so panel doesn't overflow
            val maxH = (dm.heightPixels * 0.55f).toInt()
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, maxH)
        }
        val formContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(14), dpToPx(12), dpToPx(14), dpToPx(8))
        }
        scrollForm.addView(formContent)
        panel.addView(scrollForm)

        // Type names and initial index
        val typeNames = listOf(
            "Tap text","Tap XY","Long press","Wait","Type text",
            "Swipe ↑↓","Swipe XY","Key","Launch app",
            "Watch ✕","Wait & tap","Branch",
            "← Back","⌂ Home","Dismiss ad"
        )
        val initIdx = when (existingStep) {
            is Step.TapText -> 0; is Step.TapCoords -> 1; is Step.LongPress -> 2
            is Step.WaitSeconds -> 3; is Step.TypeText -> 4; is Step.Swipe -> 5
            is Step.SwipeCoords -> 6; is Step.PressKey -> 7; is Step.LaunchApp -> 8
            is Step.WatchCorners -> 9; is Step.TapWhen -> 10; is Step.CheckBranch -> 11
            is Step.PressBack -> 12; is Step.PressHome -> 13; is Step.DismissAd -> 14
            else -> 0
        }

        // EditTexts (created once, reused)
        fun makeET(hint: String, inputType: Int = InputType.TYPE_CLASS_TEXT): EditText =
            EditText(this).apply {
                this.hint = hint; this.inputType = inputType
                setTextColor(CLR_TEXT); setHintTextColor(Color.LTGRAY)
                textSize = 14f; background = null
                val pad = dpToPx(8)
                setPadding(pad, pad, pad, pad)
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.setMargins(0, dpToPx(4), 0, dpToPx(4))
                layoutParams = lp
            }

        val etText   = makeET("Text")
        val etX      = makeET("X", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        val etY      = makeET("Y", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        val etX1     = makeET("Start X", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        val etY1     = makeET("Start Y", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        val etX2     = makeET("End X", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        val etY2     = makeET("End Y", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        val etWaitVal = makeET("Duration", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        val etToutVal = makeET("Timeout", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        val etDurVal  = makeET("Hold duration", InputType.TYPE_CLASS_NUMBER)
        val etDelayVal = makeET("Delay after", InputType.TYPE_CLASS_NUMBER)
        val etRepeat  = makeET("Repeat", InputType.TYPE_CLASS_NUMBER)

        // Pre-fill from existing step
        val initDelay = msToDisplay(existingStep?.let {
            when (it) {
                is Step.TapText -> it.delayMs; is Step.TapCoords -> it.delayMs; is Step.LongPress -> it.delayMs
                is Step.WaitSeconds -> it.delayMs; is Step.TypeText -> it.delayMs; is Step.Swipe -> it.delayMs
                is Step.SwipeCoords -> it.delayMs; is Step.PressKey -> it.delayMs; is Step.LaunchApp -> it.delayMs
                is Step.WatchCorners -> it.delayMs; is Step.TapWhen -> it.delayMs; is Step.CheckBranch -> it.delayMs
                is Step.PressBack -> it.delayMs; is Step.PressHome -> it.delayMs; is Step.DismissAd -> it.delayMs
            }
        } ?: 0L)

        when (existingStep) {
            is Step.TapText -> etText.setText(existingStep.text)
            is Step.TapCoords -> { etX.setText("%.1f".format(existingStep.x)); etY.setText("%.1f".format(existingStep.y)); etRepeat.setText(existingStep.repeatCount.toString()) }
            is Step.LongPress -> { etX.setText("%.1f".format(existingStep.x)); etY.setText("%.1f".format(existingStep.y)); etRepeat.setText(existingStep.repeatCount.toString()) }
            is Step.TypeText -> etText.setText(existingStep.text)
            is Step.SwipeCoords -> { etX1.setText("%.1f".format(existingStep.x1)); etY1.setText("%.1f".format(existingStep.y1)); etX2.setText("%.1f".format(existingStep.x2)); etY2.setText("%.1f".format(existingStep.y2)) }
            is Step.PressKey -> etText.setText(existingStep.key)
            is Step.LaunchApp -> etText.setText(existingStep.target)
            is Step.TapWhen -> etText.setText(existingStep.text)
            is Step.CheckBranch -> etText.setText(existingStep.triggerText)
            else -> {}
        }
        etDelayVal.setText(initDelay.first)

        val initWaitDisplay = secsToDisplay(if (existingStep is Step.WaitSeconds) existingStep.seconds else 1f)
        etWaitVal.setText(initWaitDisplay.first)

        val initToutSecs = when (existingStep) {
            is Step.WatchCorners -> existingStep.timeoutSeconds.toFloat()
            is Step.TapWhen -> existingStep.timeoutSeconds.toFloat()
            else -> 30f
        }
        val initToutDisplay = secsToDisplay(initToutSecs)
        etToutVal.setText(initToutDisplay.first)

        val initDurDisplay = msToDisplay(when (existingStep) {
            is Step.LongPress -> existingStep.durationMs
            is Step.SwipeCoords -> existingStep.durationMs
            else -> 500L
        })
        etDurVal.setText(initDurDisplay.first)

        // State tracked via this object (updated by unit toggle buttons)
        val fieldsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        formContent.addView(fieldsContainer)

        val fields = StepFormFields(
            typeIdx = initIdx,
            etText = etText, etX = etX, etY = etY,
            etX1 = etX1, etY1 = etY1, etX2 = etX2, etY2 = etY2,
            etWaitVal = etWaitVal, waitUnit = initWaitDisplay.second,
            etToutVal = etToutVal, toutUnit = initToutDisplay.second,
            etDurVal = etDurVal, durUnit = initDurDisplay.second,
            etDelayVal = etDelayVal, delayUnit = initDelay.second,
            etRepeat = etRepeat,
            direction = if (existingStep is Step.Swipe) existingStep.direction else "up",
            key = if (existingStep is Step.PressKey) existingStep.key else "back",
            branchSeq = if (existingStep is Step.CheckBranch) existingStep.thenSequence else SequenceRepository.list(this).firstOrNull { it != seqEditorName } ?: "",
            fieldsContainer = fieldsContainer
        )
        stepFormFields = fields

        // Type picker bar
        val typeBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(CLR_LIGHT)
        }
        val typeBarScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        var typeBtns = listOf<TextView>()
        fun refreshTypeBtns() {
            typeBtns.forEachIndexed { i, btn ->
                btn.setBackgroundColor(if (i == fields.typeIdx) CLR_TERRA else Color.TRANSPARENT)
                btn.setTextColor(if (i == fields.typeIdx) Color.WHITE else CLR_TEXT)
            }
        }

        val typeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dpToPx(4), dpToPx(4), dpToPx(4), dpToPx(4)) }
        typeBtns = typeNames.mapIndexed { i, name ->
            TextView(this).apply {
                text = name; textSize = 11f; setPadding(dpToPx(8), dpToPx(6), dpToPx(8), dpToPx(6))
                setOnTouchListener { _, ev ->
                    if (ev.action == MotionEvent.ACTION_UP) {
                        fields.typeIdx = i
                        refreshTypeBtns()
                        buildStepFields(fields)
                    }
                    ev.action == MotionEvent.ACTION_DOWN || ev.action == MotionEvent.ACTION_UP
                }
            }.also { typeRow.addView(it) }
        }
        typeBarScroll.addView(typeRow)
        typeBar.addView(typeBarScroll)
        formContent.addView(typeBar, 0)
        refreshTypeBtns()
        buildStepFields(fields)

        // Footer buttons
        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(CLR_LIGHT)
            setPadding(dpToPx(12), dpToPx(10), dpToPx(12), dpToPx(10))
        }
        footer.addView(makeTextBtn("Cancel", CLR_TEXT, Color.TRANSPARENT, 0) { dismissStepEditorForm() })
        footer.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })
        footer.addView(makeTextBtn(if (editIndex == null) "Add" else "Update", Color.WHITE, CLR_TERRA, dpToPx(8)) {
            val step = formFieldsToStep(fields)
            if (step != null) {
                if (stepEditIndex == null) seqEditorSteps.add(step)
                else seqEditorSteps[stepEditIndex!!] = step
                dismissStepEditorForm()
                refreshSeqEditorList()
            } else Toast.makeText(this, "Fill required fields", Toast.LENGTH_SHORT).show()
        })
        panel.addView(footer)

        root.addView(panel)
        wm.addView(root, wParams)
        stepEditorView = root
    }

    private fun buildStepFields(fields: StepFormFields) {
        val c = fields.fieldsContainer
        c.removeAllViews()
        val p = dpToPx(4)

        fun addRow(vararg views: View) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.setMargins(0, p, 0, p); layoutParams = lp
            }
            views.forEach { v ->
                v.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                row.addView(v)
            }
            c.addView(row)
        }

        fun styledEt(et: EditText): View {
            return LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply { setColor(CLR_LIGHT); cornerRadius = dpToPx(6).toFloat() }
                setPadding(dpToPx(4), 0, dpToPx(4), 0)
                addView(et)
            }
        }

        fun unitToggle(currentUnit: String, units: List<String>, onPick: (String) -> Unit): LinearLayout {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dpToPx(4), 0, dpToPx(4))
            }
            var btns = listOf<TextView>()
            fun refresh() { btns.forEach { b -> b.setBackgroundColor(if (b.text == currentUnit) CLR_TERRA else CLR_LIGHT); b.setTextColor(if (b.text == currentUnit) Color.WHITE else CLR_TEXT) } }
            btns = units.map { u ->
                TextView(this).apply {
                    text = u; textSize = 11f; gravity = Gravity.CENTER
                    setPadding(dpToPx(8), dpToPx(5), dpToPx(8), dpToPx(5))
                    setOnTouchListener { _, ev ->
                        if (ev.action == MotionEvent.ACTION_UP) { onPick(u); buildStepFields(fields) }
                        ev.action == MotionEvent.ACTION_DOWN || ev.action == MotionEvent.ACTION_UP
                    }
                }.also { row.addView(it) }
            }
            refresh()
            return row
        }

        fun recordBtn(label: String, onRecord: (Float, Float) -> Unit): TextView =
            TextView(this).apply {
                text = label; textSize = 12f; gravity = Gravity.CENTER
                setTextColor(Color.WHITE); setBackgroundColor(CLR_SAGE)
                setPadding(dpToPx(8), dpToPx(10), dpToPx(8), dpToPx(10))
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.setMargins(0, p, 0, p); layoutParams = lp
                setOnTouchListener { _, ev ->
                    if (ev.action == MotionEvent.ACTION_UP) {
                        onCoordsRecorded = onRecord
                        showPositionRecorderInline()
                    }
                    ev.action == MotionEvent.ACTION_DOWN || ev.action == MotionEvent.ACTION_UP
                }
            }

        fun label(txt: String) = TextView(this).apply {
            text = txt; textSize = 11f; setTextColor(CLR_TEXT)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, p, 0, 0); layoutParams = lp
        }

        fun divider() = View(this).apply {
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
            lp.setMargins(0, dpToPx(6), 0, dpToPx(6)); layoutParams = lp
            setBackgroundColor(CLR_DIVIDER)
        }

        when (fields.typeIdx) {
            0 -> { // Tap text
                c.addView(label("Text to tap")); c.addView(styledEt(fields.etText))
            }
            1 -> { // Tap coords
                c.addView(recordBtn("⦿ Tap to record position") { x, y ->
                    fields.etX.setText("%.1f".format(x)); fields.etY.setText("%.1f".format(y))
                })
                addRow(styledEt(fields.etX), styledEt(fields.etY))
                c.addView(label("Repeat")); c.addView(styledEt(fields.etRepeat))
            }
            2 -> { // Long press
                c.addView(recordBtn("⦿ Tap to record position") { x, y ->
                    fields.etX.setText("%.1f".format(x)); fields.etY.setText("%.1f".format(y))
                })
                addRow(styledEt(fields.etX), styledEt(fields.etY))
                c.addView(label("Hold duration")); addRow(styledEt(fields.etDurVal), unitToggle(fields.durUnit, listOf("ms","s")) { fields.durUnit = it })
                c.addView(label("Repeat")); c.addView(styledEt(fields.etRepeat))
            }
            3 -> { // Wait
                c.addView(label("Duration")); addRow(styledEt(fields.etWaitVal), unitToggle(fields.waitUnit, listOf("s","min")) { fields.waitUnit = it })
            }
            4 -> { // Type text
                c.addView(label("Text to type")); c.addView(styledEt(fields.etText))
            }
            5 -> { // Swipe direction
                c.addView(label("Direction"))
                val dirs = listOf("up","down","left","right")
                val dirRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, p, 0, p) }
                var dirBtns = listOf<TextView>()
                fun refreshDirs() { dirBtns.forEach { b -> b.setBackgroundColor(if (b.text == fields.direction) CLR_TERRA else CLR_LIGHT); b.setTextColor(if (b.text == fields.direction) Color.WHITE else CLR_TEXT) } }
                dirBtns = dirs.map { d ->
                    TextView(this).apply {
                        text = d; textSize = 12f; gravity = Gravity.CENTER
                        setPadding(dpToPx(6), dpToPx(8), dpToPx(6), dpToPx(8))
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        setOnTouchListener { _, ev ->
                            if (ev.action == MotionEvent.ACTION_UP) { fields.direction = d; refreshDirs() }
                            ev.action == MotionEvent.ACTION_DOWN || ev.action == MotionEvent.ACTION_UP
                        }
                    }.also { dirRow.addView(it) }
                }
                refreshDirs()
                c.addView(dirRow)
            }
            6 -> { // Swipe coords
                c.addView(recordBtn("⦿ Record start position") { x, y ->
                    fields.etX1.setText("%.1f".format(x)); fields.etY1.setText("%.1f".format(y))
                })
                addRow(styledEt(fields.etX1), styledEt(fields.etY1))
                c.addView(recordBtn("⦿ Record end position") { x, y ->
                    fields.etX2.setText("%.1f".format(x)); fields.etY2.setText("%.1f".format(y))
                })
                addRow(styledEt(fields.etX2), styledEt(fields.etY2))
                c.addView(label("Swipe duration")); addRow(styledEt(fields.etDurVal), unitToggle(fields.durUnit, listOf("ms","s")) { fields.durUnit = it })
            }
            7 -> { // Press key
                c.addView(label("Key"))
                val keys = listOf("back","home","recents","notifications")
                val keyRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, p, 0, p) }
                var keyBtns = listOf<TextView>()
                fun refreshKeys() { keyBtns.forEach { b -> b.setBackgroundColor(if (b.text == fields.key) CLR_TERRA else CLR_LIGHT); b.setTextColor(if (b.text == fields.key) Color.WHITE else CLR_TEXT) } }
                keyBtns = keys.map { k ->
                    TextView(this).apply {
                        text = k; textSize = 11f; gravity = Gravity.CENTER
                        setPadding(dpToPx(4), dpToPx(8), dpToPx(4), dpToPx(8))
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        setOnTouchListener { _, ev ->
                            if (ev.action == MotionEvent.ACTION_UP) { fields.key = k; refreshKeys() }
                            ev.action == MotionEvent.ACTION_DOWN || ev.action == MotionEvent.ACTION_UP
                        }
                    }.also { keyRow.addView(it) }
                }
                refreshKeys(); c.addView(keyRow)
            }
            8 -> { // Launch app
                c.addView(label("Package name or app name")); c.addView(styledEt(fields.etText))
            }
            9 -> { // Watch corners
                c.addView(label("Timeout")); addRow(styledEt(fields.etToutVal), unitToggle(fields.toutUnit, listOf("s","min")) { fields.toutUnit = it })
            }
            10 -> { // Wait & tap
                c.addView(label("Text to wait for and tap")); c.addView(styledEt(fields.etText))
                c.addView(label("Max wait")); addRow(styledEt(fields.etToutVal), unitToggle(fields.toutUnit, listOf("s","min")) { fields.toutUnit = it })
            }
            11 -> { // Check branch
                c.addView(label("If screen shows…")); c.addView(styledEt(fields.etText))
                val seqs = SequenceRepository.list(this).filter { it != seqEditorName }
                if (seqs.isEmpty()) {
                    c.addView(label("No other sequences saved"))
                } else {
                    c.addView(label("Run sequence"))
                    val seqRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, p, 0, p) }
                    val seqScroll = HorizontalScrollView(this)
                    val seqInner = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                    var seqBtns = listOf<TextView>()
                    fun refreshSeqs() { seqBtns.forEach { b -> b.setBackgroundColor(if (b.text == fields.branchSeq) CLR_TERRA else CLR_LIGHT); b.setTextColor(if (b.text == fields.branchSeq) Color.WHITE else CLR_TEXT) } }
                    seqBtns = seqs.map { s ->
                        TextView(this).apply {
                            text = s; textSize = 11f; gravity = Gravity.CENTER
                            setPadding(dpToPx(8), dpToPx(7), dpToPx(8), dpToPx(7))
                            setOnTouchListener { _, ev ->
                                if (ev.action == MotionEvent.ACTION_UP) { fields.branchSeq = s; refreshSeqs() }
                                ev.action == MotionEvent.ACTION_DOWN || ev.action == MotionEvent.ACTION_UP
                            }
                        }.also { seqInner.addView(it) }
                    }
                    refreshSeqs()
                    seqScroll.addView(seqInner); seqRow.addView(seqScroll); c.addView(seqRow)
                }
            }
            else -> { // 12 Press Back, 13 Press Home, 14 Dismiss Ad
                c.addView(label("No extra configuration needed."))
            }
        }

        // Delay after (shown for all types)
        c.addView(divider())
        c.addView(label("Delay after this step"))
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, p, 0, p); layoutParams = lp
        }
        fields.etDelayVal.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        val delayWrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(CLR_LIGHT); cornerRadius = dpToPx(6).toFloat() }
            setPadding(dpToPx(4), 0, dpToPx(4), 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(fields.etDelayVal)
        }
        row.addView(delayWrap)
        row.addView(unitToggle(fields.delayUnit, listOf("ms","s","min")) { fields.delayUnit = it })
        c.addView(row)
    }

    private fun formFieldsToStep(f: StepFormFields): Step? {
        val delay = displayToMs(f.etDelayVal.text.toString(), f.delayUnit)
        return when (f.typeIdx) {
            0  -> f.etText.text.toString().takeIf { it.isNotBlank() }?.let { Step.TapText(it, delay) }
            1  -> { val x = f.etX.text.toString().toFloatOrNull(); val y = f.etY.text.toString().toFloatOrNull()
                    if (x != null && y != null) Step.TapCoords(x, y, delay, f.etRepeat.text.toString().toIntOrNull()?.coerceAtLeast(1) ?: 1) else null }
            2  -> { val x = f.etX.text.toString().toFloatOrNull(); val y = f.etY.text.toString().toFloatOrNull()
                    val dur = displayToMs(f.etDurVal.text.toString(), f.durUnit)
                    if (x != null && y != null) Step.LongPress(x, y, dur, delay, f.etRepeat.text.toString().toIntOrNull()?.coerceAtLeast(1) ?: 1) else null }
            3  -> Step.WaitSeconds(displayToSecs(f.etWaitVal.text.toString(), f.waitUnit), delay)
            4  -> f.etText.text.toString().takeIf { it.isNotBlank() }?.let { Step.TypeText(it, delay) }
            5  -> Step.Swipe(f.direction, delay)
            6  -> { val x1 = f.etX1.text.toString().toFloatOrNull(); val y1 = f.etY1.text.toString().toFloatOrNull()
                    val x2 = f.etX2.text.toString().toFloatOrNull(); val y2 = f.etY2.text.toString().toFloatOrNull()
                    val dur = displayToMs(f.etDurVal.text.toString(), f.durUnit)
                    if (x1 != null && y1 != null && x2 != null && y2 != null) Step.SwipeCoords(x1, y1, x2, y2, dur, delay) else null }
            7  -> Step.PressKey(f.key, delay)
            8  -> f.etText.text.toString().takeIf { it.isNotBlank() }?.let { Step.LaunchApp(it, delay) }
            9  -> Step.WatchCorners(displayToSecs(f.etToutVal.text.toString(), f.toutUnit).toInt().coerceAtLeast(1), delay)
            10 -> f.etText.text.toString().takeIf { it.isNotBlank() }?.let { Step.TapWhen(it, displayToSecs(f.etToutVal.text.toString(), f.toutUnit).toInt().coerceAtLeast(1), delay) }
            11 -> { val t = f.etText.text.toString(); if (t.isNotBlank() && f.branchSeq.isNotBlank()) Step.CheckBranch(t, f.branchSeq, delay) else null }
            12 -> Step.PressBack(delay)
            13 -> Step.PressHome(delay)
            14 -> Step.DismissAd(delay)
            else -> null
        }
    }

    private fun dismissStepEditorForm() {
        stepEditorView?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        stepEditorView = null; stepFormFields = null; stepEditIndex = null; onCoordsRecorded = null
    }

    // ── Position recorder (inline for overlay editor) ─────────────────────────

    private fun showPositionRecorderInline() {
        recorderView?.let { wm.removeView(it); recorderView = null }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        val root = FrameLayout(this).apply { setBackgroundColor(Color.argb(140, 0, 0, 0)) }
        val banner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setBackgroundColor(CLR_TERRA)
            setPadding(dpToPx(14), dpToPx(12), dpToPx(10), dpToPx(12))
        }
        banner.addView(TextView(this).apply {
            text = "Tap anywhere to record click position"; setTextColor(Color.WHITE); textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        banner.addView(TextView(this).apply {
            text = "Cancel"; setTextColor(Color.parseColor("#FFCDD2")); textSize = 14f
            setPadding(dpToPx(12), 0, dpToPx(4), 0)
            setOnTouchListener { _, ev -> if (ev.action == MotionEvent.ACTION_UP) { dismissRecorder(); onCoordsRecorded = null }; true }
        })
        root.addView(banner, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        root.setOnTouchListener { _, ev ->
            if (ev.action == MotionEvent.ACTION_DOWN) {
                val x = ev.rawX; val y = ev.rawY
                dismissRecorder()
                val cb = onCoordsRecorded
                if (cb != null) {
                    onCoordsRecorded = null
                    cb(x, y)
                } else {
                    _recordedCoords.value = x to y
                }
            }
            true
        }
        wm.addView(root, params); recorderView = root
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
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.BOTTOM; y = dpToPx(60) }
            val banner = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; setBackgroundColor(Color.parseColor("#DD1D1B20"))
                setPadding(dpToPx(14), dpToPx(10), dpToPx(10), dpToPx(10))
            }
            val tv = TextView(this).apply {
                setTextColor(Color.WHITE); textSize = 13f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            statusText = tv
            val stopBtn = TextView(this).apply {
                text = "■ Stop"; setTextColor(Color.parseColor("#FFCDD2")); textSize = 13f
                setTypeface(null, Typeface.BOLD); setPadding(dpToPx(12), 0, dpToPx(4), 0)
                setOnTouchListener { _, ev ->
                    if (ev.action == MotionEvent.ACTION_UP) { AutomationAccessibilityService.instance?.stopCurrent(); dismissStatusBanner() }
                    ev.action == MotionEvent.ACTION_DOWN || ev.action == MotionEvent.ACTION_UP
                }
            }
            banner.addView(tv); banner.addView(stopBtn)
            wm.addView(banner, params); statusBanner = banner
        }
        val stepInfo = if (status.totalSteps > 0) "Step ${status.currentStep + 1}/${status.totalSteps}" else ""
        val stateLabel = if (status.state == RunState.PAUSED) " ⏸ Paused" else ""
        statusText?.text = "⚙ $name  $stepInfo  ${status.message}$stateLabel"
    }

    private fun dismissStatusBanner() {
        statusBanner?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        statusBanner = null; statusText = null
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun buildNotification(): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Overlay", NotificationManager.IMPORTANCE_LOW))
        val tap = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Automation Navigator")
            .setContentText("Tap the floating button to run sequences")
            .setSmallIcon(R.drawable.ic_play_circle)
            .setContentIntent(tap).build()
    }

    // ── Position recorder (from app) ──────────────────────────────────────────

    private fun showPositionRecorder() {
        onCoordsRecorded = null
        showPositionRecorderInline()
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
        if (points.isEmpty()) { Toast.makeText(this, "No tap points in this sequence", Toast.LENGTH_SHORT).show(); return }

        points.forEach { (stepIndex, x, y) ->
            val circleView = buildPointCircle(stepIndex + 1)
            val halfSize = dpToPx(26)
            val circleParams = WindowManager.LayoutParams(dpToPx(52), dpToPx(52),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                this.x = (x - halfSize).toInt().coerceAtLeast(0)
                this.y = (y - halfSize).toInt().coerceAtLeast(0)
            }
            var startX = 0; var startY = 0; var startRawX = 0f; var startRawY = 0f
            circleView.setOnTouchListener { _, ev ->
                when (ev.action) {
                    MotionEvent.ACTION_DOWN -> { startX = circleParams.x; startY = circleParams.y; startRawX = ev.rawX; startRawY = ev.rawY; true }
                    MotionEvent.ACTION_MOVE -> { circleParams.x = (startX + ev.rawX - startRawX).toInt(); circleParams.y = (startY + ev.rawY - startRawY).toInt(); try { wm.updateViewLayout(circleView, circleParams) } catch (_: Exception) {}; true }
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
            text = number.toString(); setTextColor(Color.WHITE); textSize = 15f
            setTypeface(null, Typeface.BOLD); gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(CLR_TERRA); setStroke(dpToPx(2), Color.WHITE) }
            layoutParams = ViewGroup.LayoutParams(size, size)
        }
    }

    private fun showPointEditorBanner() {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM; y = dpToPx(60) }
        val banner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setBackgroundColor(Color.parseColor("#DD1D1B20"))
            setPadding(dpToPx(14), dpToPx(10), dpToPx(10), dpToPx(10))
        }
        banner.addView(TextView(this).apply {
            text = "Drag circles to reposition taps"; setTextColor(Color.WHITE); textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        banner.addView(makeTextBtn("Save", Color.parseColor("#90EE90"), Color.TRANSPARENT, 0) { saveEditedPoints() })
        banner.addView(makeTextBtn("Cancel", Color.parseColor("#FFCDD2"), Color.TRANSPARENT, 0) { dismissPointEditor() })
        wm.addView(banner, params); pointEditorBanner = banner
    }

    private fun saveEditedPoints() {
        val seqName = editingSequenceName ?: return
        val half = dpToPx(26).toFloat()
        val updatesMap = pointEditorViews.associate { entry -> entry.stepIndex to (entry.params.x + half to entry.params.y + half) }
        val seq = SequenceRepository.load(this, seqName)
        if (seq != null) {
            val newSteps = seq.steps.mapIndexed { idx, step ->
                val upd = updatesMap[idx] ?: return@mapIndexed step
                when (step) { is Step.TapCoords -> step.copy(x = upd.first, y = upd.second); is Step.LongPress -> step.copy(x = upd.first, y = upd.second); else -> step }
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
        pointEditorBanner = null; editingSequenceName = null
    }

    // ── View helpers ──────────────────────────────────────────────────────────

    private fun makeTextBtn(label: String, textColor: Int, bgColor: Int, radius: Int, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label; setTextColor(textColor); textSize = 14f; setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER; setPadding(dpToPx(10), dpToPx(6), dpToPx(10), dpToPx(6))
            if (bgColor != Color.TRANSPARENT) {
                background = GradientDrawable().apply { setColor(bgColor); cornerRadius = radius.toFloat() }
            }
            setOnTouchListener { _, ev ->
                if (ev.action == MotionEvent.ACTION_UP) onClick()
                ev.action == MotionEvent.ACTION_DOWN || ev.action == MotionEvent.ACTION_UP
            }
        }

    private fun makeIconBtn(icon: String, color: Int, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = icon; setTextColor(color); textSize = 16f; gravity = Gravity.CENTER
            setPadding(dpToPx(6), dpToPx(4), dpToPx(6), dpToPx(4))
            setOnTouchListener { _, ev ->
                if (ev.action == MotionEvent.ACTION_UP) onClick()
                ev.action == MotionEvent.ACTION_DOWN || ev.action == MotionEvent.ACTION_UP
            }
        }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()
}
