package com.navigator.automation.ui.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.navigator.automation.engine.Sequence
import com.navigator.automation.engine.SequenceRepository
import com.navigator.automation.engine.Step
import com.navigator.automation.service.OverlayService
import org.json.JSONArray
import org.json.JSONObject

// ── Time unit helpers ─────────────────────────────────────────────────────────

private fun msToDisplay(ms: Long): Pair<String, String> = when {
    ms == 0L -> "0" to "ms"
    ms % 60_000L == 0L -> (ms / 60_000L).toString() to "min"
    ms % 1_000L == 0L  -> (ms / 1_000L).toString()  to "s"
    else -> ms.toString() to "ms"
}

private fun displayToMs(value: String, unit: String): Long {
    val v = value.toLongOrNull() ?: 0L
    return when (unit) { "s" -> v * 1_000L; "min" -> v * 60_000L; else -> v }
}

private fun secsToDisplay(s: Float): Pair<String, String> = when {
    s >= 60f && s % 60f == 0f -> (s / 60f).toInt().toString() to "min"
    else -> s.toString() to "s"
}

private fun displayToSecs(value: String, unit: String): Float {
    val v = value.toFloatOrNull() ?: 1f
    return if (unit == "min") v * 60f else v
}

// ── Editor screen ──────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SequenceEditorScreen(
    sequenceName: String?,
    onBack: () -> Unit,
    onSaved: () -> Unit
) {
    val context = LocalContext.current
    val existing = remember(sequenceName) {
        sequenceName?.let { SequenceRepository.load(context, it) }
    }

    var name      by remember { mutableStateOf(existing?.name ?: "") }
    var steps     by remember { mutableStateOf(existing?.steps?.toMutableList() ?: mutableListOf()) }
    var loopCount by remember { mutableStateOf(existing?.loopCount?.toString() ?: "1") }
    var loopDelay by remember { mutableStateOf(existing?.loopDelaySeconds?.toString() ?: "1.0") }
    var showAdd   by remember { mutableStateOf(false) }
    var nameError by remember { mutableStateOf(false) }
    var editingStepIndex by remember { mutableStateOf<Int?>(null) }

    val editedPoints by OverlayService.editedPoints.collectAsState()
    LaunchedEffect(editedPoints) {
        val updates = editedPoints ?: return@LaunchedEffect
        steps = steps.toMutableList().also { list ->
            updates.forEach { (idx, xy) ->
                if (idx < list.size) {
                    list[idx] = when (val s = list[idx]) {
                        is Step.TapCoords -> s.copy(x = xy.first, y = xy.second)
                        is Step.LongPress -> s.copy(x = xy.first, y = xy.second)
                        else -> s
                    }
                }
            }
        }
        OverlayService.clearEditedPoints()
    }

    fun currentSequence() = Sequence(
        name = name.trim(),
        steps = steps.toList(),
        loopCount = loopCount.toIntOrNull() ?: 1,
        loopDelaySeconds = loopDelay.toFloatOrNull() ?: 1f
    )

    val hasTapPoints = steps.any { it is Step.TapCoords || it is Step.LongPress }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (sequenceName == null) "New Sequence" else "Edit Sequence") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }
                },
                actions = {
                    IconButton(onClick = {
                        val json = currentSequence().toJson().toString(2)
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, json)
                            putExtra(Intent.EXTRA_SUBJECT, "${name.trim()}.json")
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        context.startActivity(Intent.createChooser(shareIntent, "Export sequence"))
                    }) { Icon(Icons.Default.Share, "Export") }

                    IconButton(onClick = {
                        if (name.isBlank()) { nameError = true; return@IconButton }
                        SequenceRepository.save(context, currentSequence())
                        if (sequenceName != null && sequenceName != name.trim()) {
                            SequenceRepository.delete(context, sequenceName)
                        }
                        onSaved()
                    }) { Icon(Icons.Default.Check, "Save") }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; nameError = false },
                    label = { Text("Sequence name") },
                    isError = nameError,
                    supportingText = if (nameError) {{ Text("Name required") }} else null,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = loopCount,
                        onValueChange = { loopCount = it },
                        label = { Text("Loops (0=∞)") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    OutlinedTextField(
                        value = loopDelay,
                        onValueChange = { loopDelay = it },
                        label = { Text("Loop delay (s)") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                    )
                }
            }

            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Steps", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))

                    if (hasTapPoints) {
                        FilledTonalButton(
                            onClick = {
                                if (name.isBlank()) { nameError = true; return@FilledTonalButton }
                                SequenceRepository.save(context, currentSequence())
                                val pointsArray = JSONArray()
                                steps.forEachIndexed { idx, step ->
                                    when (step) {
                                        is Step.TapCoords -> pointsArray.put(
                                            JSONObject().apply { put("stepIndex", idx); put("x", step.x.toDouble()); put("y", step.y.toDouble()) }
                                        )
                                        is Step.LongPress -> pointsArray.put(
                                            JSONObject().apply { put("stepIndex", idx); put("x", step.x.toDouble()); put("y", step.y.toDouble()) }
                                        )
                                        else -> {}
                                    }
                                }
                                context.startService(
                                    Intent(context, OverlayService::class.java).apply {
                                        action = OverlayService.ACTION_EDIT_POINTS
                                        putExtra(OverlayService.EXTRA_SEQUENCE_NAME, name.trim())
                                        putExtra(OverlayService.EXTRA_POINTS_JSON, pointsArray.toString())
                                    }
                                )
                            }
                        ) {
                            Icon(Icons.Default.TouchApp, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Edit points", fontSize = 13.sp)
                        }
                    }

                    FilledTonalButton(onClick = { showAdd = true }) {
                        Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Add step", fontSize = 13.sp)
                    }
                }
            }

            if (steps.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("No steps yet — tap Add Step",
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                    }
                }
            } else {
                itemsIndexed(steps) { idx, step ->
                    StepRow(
                        step = step, index = idx,
                        onEdit     = { editingStepIndex = idx },
                        onDelete   = { steps = steps.toMutableList().also { it.removeAt(idx) } },
                        onMoveUp   = {
                            if (idx > 0) steps = steps.toMutableList().also {
                                val tmp = it[idx]; it[idx] = it[idx-1]; it[idx-1] = tmp
                            }
                        },
                        onMoveDown = {
                            if (idx < steps.size - 1) steps = steps.toMutableList().also {
                                val tmp = it[idx]; it[idx] = it[idx+1]; it[idx+1] = tmp
                            }
                        }
                    )
                }
            }
        }
    }

    if (showAdd) {
        StepDialog(
            initialStep = null,
            availableSequences = SequenceRepository.list(context).filter { it != name },
            onDismiss = { showAdd = false },
            onConfirm = { step -> steps = steps.toMutableList().also { it.add(step) }; showAdd = false }
        )
    }

    editingStepIndex?.let { idx ->
        StepDialog(
            initialStep = steps[idx],
            availableSequences = SequenceRepository.list(context).filter { it != name },
            onDismiss = { editingStepIndex = null },
            onConfirm = { updated -> steps = steps.toMutableList().also { it[idx] = updated }; editingStepIndex = null }
        )
    }
}

// ── Step row ──────────────────────────────────────────────────────────────────

@Composable
private fun StepRow(
    step: Step, index: Int,
    onEdit: () -> Unit, onDelete: () -> Unit, onMoveUp: () -> Unit, onMoveDown: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "${index + 1}",
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.small)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.width(10.dp))
            Text(step.label(), modifier = Modifier.weight(1f), fontSize = 14.sp)
            IconButton(onClick = onMoveUp,   modifier = Modifier.size(32.dp)) { Icon(Icons.Default.KeyboardArrowUp,   null, modifier = Modifier.size(18.dp)) }
            IconButton(onClick = onMoveDown, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.KeyboardArrowDown, null, modifier = Modifier.size(18.dp)) }
            IconButton(onClick = onEdit,     modifier = Modifier.size(32.dp)) { Icon(Icons.Default.Edit, null,        modifier = Modifier.size(18.dp)) }
            IconButton(onClick = onDelete,   modifier = Modifier.size(32.dp)) { Icon(Icons.Default.Close, null,       modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error) }
        }
    }
}

// ── Time unit picker ──────────────────────────────────────────────────────────

@Composable
private fun TimeField(
    value: String,
    onValueChange: (String) -> Unit,
    unit: String,
    onUnitChange: (String) -> Unit,
    label: String,
    units: List<String> = listOf("ms", "s", "min"),
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = value, onValueChange = onValueChange,
            label = { Text(label) },
            modifier = Modifier.weight(1f),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
        )
        units.forEach { u ->
            FilterChip(
                selected = unit == u,
                onClick = { onUnitChange(u) },
                label = { Text(u, fontSize = 11.sp) }
            )
        }
    }
}

// ── Step dialog ───────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StepDialog(
    initialStep: Step?,
    availableSequences: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (Step) -> Unit
) {
    val context = LocalContext.current
    val types = listOf(
        "Tap text", "Tap coords", "Long press",
        "Wait", "Type text",
        "Swipe (direction)", "Swipe (coords)",
        "Press key", "Launch app",
        "Watch corners", "Wait & tap", "Check branch",
        "Press Back", "Press Home", "Dismiss Ad"
    )

    val initIdx = when (initialStep) {
        is Step.TapText     -> 0;  is Step.TapCoords   -> 1;  is Step.LongPress   -> 2
        is Step.WaitSeconds -> 3;  is Step.TypeText    -> 4;  is Step.Swipe       -> 5
        is Step.SwipeCoords -> 6;  is Step.PressKey    -> 7;  is Step.LaunchApp   -> 8
        is Step.WatchCorners-> 9;  is Step.TapWhen    -> 10;  is Step.CheckBranch -> 11
        is Step.PressBack   ->12;  is Step.PressHome  -> 13;  is Step.DismissAd   -> 14
        null -> 0; else -> 0
    }

    var selected by remember { mutableStateOf(initIdx) }

    // Text / key / target field
    var textArg by remember { mutableStateOf(when (initialStep) {
        is Step.TapText -> initialStep.text; is Step.TypeText -> initialStep.text
        is Step.PressKey -> initialStep.key; is Step.LaunchApp -> initialStep.target
        is Step.TapWhen -> initialStep.text; is Step.CheckBranch -> initialStep.triggerText
        else -> ""
    }) }

    // Wait duration (seconds)
    val (initWaitVal, initWaitUnit) = remember { secsToDisplay(if (initialStep is Step.WaitSeconds) initialStep.seconds else 1f) }
    var waitVal  by remember { mutableStateOf(initWaitVal) }
    var waitUnit by remember { mutableStateOf(initWaitUnit) }

    // Timeout (seconds) for WatchCorners / TapWhen
    val initTimeout = remember {
        when (initialStep) {
            is Step.WatchCorners -> initialStep.timeoutSeconds.toFloat()
            is Step.TapWhen      -> initialStep.timeoutSeconds.toFloat()
            else -> 30f
        }
    }
    val (initToutVal, initToutUnit) = remember { secsToDisplay(initTimeout) }
    var toutVal  by remember { mutableStateOf(initToutVal) }
    var toutUnit by remember { mutableStateOf(initToutUnit) }

    // Hold duration for LongPress
    val (initDurVal, initDurUnit) = remember { msToDisplay(if (initialStep is Step.LongPress) initialStep.durationMs else 500L) }
    var durVal  by remember { mutableStateOf(initDurVal) }
    var durUnit by remember { mutableStateOf(initDurUnit) }

    // Swipe duration
    val (initSwDurVal, initSwDurUnit) = remember { msToDisplay(if (initialStep is Step.SwipeCoords) initialStep.durationMs else 300L) }
    var swDurVal  by remember { mutableStateOf(initSwDurVal) }
    var swDurUnit by remember { mutableStateOf(initSwDurUnit) }

    // Coords
    var xArg  by remember { mutableStateOf(when (initialStep) { is Step.TapCoords -> "%.1f".format(initialStep.x); is Step.LongPress -> "%.1f".format(initialStep.x); else -> "" }) }
    var yArg  by remember { mutableStateOf(when (initialStep) { is Step.TapCoords -> "%.1f".format(initialStep.y); is Step.LongPress -> "%.1f".format(initialStep.y); else -> "" }) }
    var x1Arg by remember { mutableStateOf(if (initialStep is Step.SwipeCoords) "%.1f".format(initialStep.x1) else "") }
    var y1Arg by remember { mutableStateOf(if (initialStep is Step.SwipeCoords) "%.1f".format(initialStep.y1) else "") }
    var x2Arg by remember { mutableStateOf(if (initialStep is Step.SwipeCoords) "%.1f".format(initialStep.x2) else "") }
    var y2Arg by remember { mutableStateOf(if (initialStep is Step.SwipeCoords) "%.1f".format(initialStep.y2) else "") }

    // Direction / key
    var dirArg by remember { mutableStateOf(if (initialStep is Step.Swipe) initialStep.direction else "up") }

    // Branch sequence
    var branchSeqArg by remember { mutableStateOf(if (initialStep is Step.CheckBranch) initialStep.thenSequence else availableSequences.firstOrNull() ?: "") }

    // Repeat
    var repeatArg by remember { mutableStateOf(when (initialStep) {
        is Step.TapCoords -> initialStep.repeatCount.toString()
        is Step.LongPress -> initialStep.repeatCount.toString()
        else -> "1"
    }) }

    // Delay AFTER (all types)
    val initDelayMs = remember {
        when (initialStep) {
            is Step.TapText -> initialStep.delayMs; is Step.TapCoords -> initialStep.delayMs
            is Step.LongPress -> initialStep.delayMs; is Step.WaitSeconds -> initialStep.delayMs
            is Step.TypeText -> initialStep.delayMs; is Step.Swipe -> initialStep.delayMs
            is Step.SwipeCoords -> initialStep.delayMs; is Step.PressKey -> initialStep.delayMs
            is Step.LaunchApp -> initialStep.delayMs; is Step.WatchCorners -> initialStep.delayMs
            is Step.TapWhen -> initialStep.delayMs; is Step.CheckBranch -> initialStep.delayMs
            is Step.PressBack -> initialStep.delayMs; is Step.PressHome -> initialStep.delayMs
            is Step.DismissAd -> initialStep.delayMs; else -> 0L
        }
    }
    val (initDelayVal, initDelayUnit) = remember { msToDisplay(initDelayMs) }
    var delayVal  by remember { mutableStateOf(initDelayVal) }
    var delayUnit by remember { mutableStateOf(initDelayUnit) }

    // Position recorder
    var isRecording      by remember { mutableStateOf(false) }
    var isRecordingStart by remember { mutableStateOf(false) }
    var isRecordingEnd   by remember { mutableStateOf(false) }
    val recordedCoords by OverlayService.recordedCoords.collectAsState()
    LaunchedEffect(recordedCoords) {
        val coords = recordedCoords ?: return@LaunchedEffect
        when {
            isRecording      -> { xArg = "%.1f".format(coords.first); yArg = "%.1f".format(coords.second); isRecording = false }
            isRecordingStart -> { x1Arg = "%.1f".format(coords.first); y1Arg = "%.1f".format(coords.second); isRecordingStart = false }
            isRecordingEnd   -> { x2Arg = "%.1f".format(coords.first); y2Arg = "%.1f".format(coords.second); isRecordingEnd = false }
        }
        OverlayService.clearRecordedCoords()
    }
    fun startRecorder() {
        OverlayService.clearRecordedCoords()
        context.startService(Intent(context, OverlayService::class.java).apply {
            action = OverlayService.ACTION_RECORD_POSITION
        })
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initialStep == null) "Add Step" else "Edit Step") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // Type picker
                var expanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                    OutlinedTextField(
                        value = types[selected], onValueChange = {}, readOnly = true,
                        label = { Text("Step type") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        types.forEachIndexed { idx, t ->
                            DropdownMenuItem(text = { Text(t) }, onClick = { selected = idx; expanded = false })
                        }
                    }
                }

                when (selected) {
                    // 0: Tap text
                    0 -> {
                        OutlinedTextField(value = textArg, onValueChange = { textArg = it },
                            label = { Text("Text to tap") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        TimeField(delayVal, { delayVal = it }, delayUnit, { delayUnit = it }, "Delay after", modifier = Modifier.fillMaxWidth())
                    }

                    // 1: Tap coords
                    1 -> {
                        FilledTonalButton(onClick = { isRecording = true; startRecorder() }, modifier = Modifier.fillMaxWidth()) {
                            Text(if (isRecording) "Tap anywhere on screen…" else "Record tap position")
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(value = xArg, onValueChange = { xArg = it }, label = { Text("X") },
                                modifier = Modifier.weight(1f), singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                            OutlinedTextField(value = yArg, onValueChange = { yArg = it }, label = { Text("Y") },
                                modifier = Modifier.weight(1f), singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                        }
                        OutlinedTextField(value = repeatArg, onValueChange = { repeatArg = it },
                            label = { Text("Repeat") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        TimeField(delayVal, { delayVal = it }, delayUnit, { delayUnit = it }, "Delay after", modifier = Modifier.fillMaxWidth())
                    }

                    // 2: Long press
                    2 -> {
                        FilledTonalButton(onClick = { isRecording = true; startRecorder() }, modifier = Modifier.fillMaxWidth()) {
                            Text(if (isRecording) "Tap anywhere on screen…" else "Record press position")
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(value = xArg, onValueChange = { xArg = it }, label = { Text("X") },
                                modifier = Modifier.weight(1f), singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                            OutlinedTextField(value = yArg, onValueChange = { yArg = it }, label = { Text("Y") },
                                modifier = Modifier.weight(1f), singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                        }
                        TimeField(durVal, { durVal = it }, durUnit, { durUnit = it }, "Hold duration", units = listOf("ms", "s"), modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = repeatArg, onValueChange = { repeatArg = it },
                            label = { Text("Repeat") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        TimeField(delayVal, { delayVal = it }, delayUnit, { delayUnit = it }, "Delay after", modifier = Modifier.fillMaxWidth())
                    }

                    // 3: Wait
                    3 -> {
                        TimeField(waitVal, { waitVal = it }, waitUnit, { waitUnit = it }, "Duration", units = listOf("s", "min"), modifier = Modifier.fillMaxWidth())
                        TimeField(delayVal, { delayVal = it }, delayUnit, { delayUnit = it }, "Extra delay after", modifier = Modifier.fillMaxWidth())
                    }

                    // 4: Type text
                    4 -> {
                        OutlinedTextField(value = textArg, onValueChange = { textArg = it },
                            label = { Text("Text to type") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        TimeField(delayVal, { delayVal = it }, delayUnit, { delayUnit = it }, "Delay after", modifier = Modifier.fillMaxWidth())
                    }

                    // 5: Swipe direction
                    5 -> {
                        var dirExp by remember { mutableStateOf(false) }
                        val dirs = listOf("up", "down", "left", "right")
                        ExposedDropdownMenuBox(expanded = dirExp, onExpandedChange = { dirExp = it }) {
                            OutlinedTextField(value = dirArg, onValueChange = {}, readOnly = true,
                                label = { Text("Direction") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(dirExp) },
                                modifier = Modifier.menuAnchor().fillMaxWidth())
                            ExposedDropdownMenu(expanded = dirExp, onDismissRequest = { dirExp = false }) {
                                dirs.forEach { d -> DropdownMenuItem(text = { Text(d) }, onClick = { dirArg = d; dirExp = false }) }
                            }
                        }
                        TimeField(delayVal, { delayVal = it }, delayUnit, { delayUnit = it }, "Delay after", modifier = Modifier.fillMaxWidth())
                    }

                    // 6: Swipe coords
                    6 -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(onClick = { isRecordingStart = true; startRecorder() }, modifier = Modifier.weight(1f)) {
                                Text(if (isRecordingStart) "Tap start…" else "Record start")
                            }
                            FilledTonalButton(onClick = { isRecordingEnd = true; startRecorder() }, modifier = Modifier.weight(1f)) {
                                Text(if (isRecordingEnd) "Tap end…" else "Record end")
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(value = x1Arg, onValueChange = { x1Arg = it }, label = { Text("Start X") },
                                modifier = Modifier.weight(1f), singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                            OutlinedTextField(value = y1Arg, onValueChange = { y1Arg = it }, label = { Text("Start Y") },
                                modifier = Modifier.weight(1f), singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(value = x2Arg, onValueChange = { x2Arg = it }, label = { Text("End X") },
                                modifier = Modifier.weight(1f), singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                            OutlinedTextField(value = y2Arg, onValueChange = { y2Arg = it }, label = { Text("End Y") },
                                modifier = Modifier.weight(1f), singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                        }
                        TimeField(swDurVal, { swDurVal = it }, swDurUnit, { swDurUnit = it }, "Swipe duration", units = listOf("ms", "s"), modifier = Modifier.fillMaxWidth())
                        TimeField(delayVal, { delayVal = it }, delayUnit, { delayUnit = it }, "Delay after", modifier = Modifier.fillMaxWidth())
                    }

                    // 7: Press key
                    7 -> {
                        OutlinedTextField(value = textArg, onValueChange = { textArg = it },
                            label = { Text("Key (back / home / recents)") },
                            modifier = Modifier.fillMaxWidth(), singleLine = true)
                        TimeField(delayVal, { delayVal = it }, delayUnit, { delayUnit = it }, "Delay after", modifier = Modifier.fillMaxWidth())
                    }

                    // 8: Launch app
                    8 -> {
                        OutlinedTextField(value = textArg, onValueChange = { textArg = it },
                            label = { Text("Package or app name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        TimeField(delayVal, { delayVal = it }, delayUnit, { delayUnit = it }, "Extra delay after launch", modifier = Modifier.fillMaxWidth())
                    }

                    // 9: Watch corners
                    9 -> {
                        TimeField(toutVal, { toutVal = it }, toutUnit, { toutUnit = it }, "Timeout", units = listOf("s", "min"), modifier = Modifier.fillMaxWidth())
                        TimeField(delayVal, { delayVal = it }, delayUnit, { delayUnit = it }, "Delay after", modifier = Modifier.fillMaxWidth())
                    }

                    // 10: Wait & tap
                    10 -> {
                        OutlinedTextField(value = textArg, onValueChange = { textArg = it },
                            label = { Text("Text to wait for and tap") },
                            modifier = Modifier.fillMaxWidth(), singleLine = true)
                        TimeField(toutVal, { toutVal = it }, toutUnit, { toutUnit = it }, "Max wait", units = listOf("s", "min"), modifier = Modifier.fillMaxWidth())
                        TimeField(delayVal, { delayVal = it }, delayUnit, { delayUnit = it }, "Delay after", modifier = Modifier.fillMaxWidth())
                        Text("Polls until text appears, then taps it — good for timed ad buttons.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    }

                    // 11: Check branch
                    11 -> {
                        OutlinedTextField(value = textArg, onValueChange = { textArg = it },
                            label = { Text("If screen shows text…") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        if (availableSequences.isEmpty()) {
                            Text("No other sequences — save this one first.", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                        } else {
                            var branchExp by remember { mutableStateOf(false) }
                            ExposedDropdownMenuBox(expanded = branchExp, onExpandedChange = { branchExp = it }) {
                                OutlinedTextField(value = branchSeqArg, onValueChange = {}, readOnly = true,
                                    label = { Text("Run sequence") },
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(branchExp) },
                                    modifier = Modifier.menuAnchor().fillMaxWidth())
                                ExposedDropdownMenu(expanded = branchExp, onDismissRequest = { branchExp = false }) {
                                    availableSequences.forEach { s ->
                                        DropdownMenuItem(text = { Text(s) }, onClick = { branchSeqArg = s; branchExp = false })
                                    }
                                }
                            }
                        }
                        TimeField(delayVal, { delayVal = it }, delayUnit, { delayUnit = it }, "Delay after", modifier = Modifier.fillMaxWidth())
                    }

                    // 12, 13, 14 — delay only
                    else -> TimeField(delayVal, { delayVal = it }, delayUnit, { delayUnit = it }, "Delay after", modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val delay = displayToMs(delayVal, delayUnit)
                val step: Step? = when (selected) {
                    0  -> if (textArg.isNotBlank()) Step.TapText(textArg, delay) else null
                    1  -> { val x = xArg.toFloatOrNull(); val y = yArg.toFloatOrNull()
                            if (x != null && y != null) Step.TapCoords(x, y, delay, repeatArg.toIntOrNull()?.coerceAtLeast(1) ?: 1) else null }
                    2  -> { val x = xArg.toFloatOrNull(); val y = yArg.toFloatOrNull()
                            if (x != null && y != null) Step.LongPress(x, y, displayToMs(durVal, durUnit), delay, repeatArg.toIntOrNull()?.coerceAtLeast(1) ?: 1) else null }
                    3  -> Step.WaitSeconds(displayToSecs(waitVal, waitUnit), delay)
                    4  -> if (textArg.isNotBlank()) Step.TypeText(textArg, delay) else null
                    5  -> Step.Swipe(dirArg, delay)
                    6  -> { val x1 = x1Arg.toFloatOrNull(); val y1 = y1Arg.toFloatOrNull()
                            val x2 = x2Arg.toFloatOrNull(); val y2 = y2Arg.toFloatOrNull()
                            if (x1 != null && y1 != null && x2 != null && y2 != null)
                                Step.SwipeCoords(x1, y1, x2, y2, displayToMs(swDurVal, swDurUnit), delay) else null }
                    7  -> if (textArg.isNotBlank()) Step.PressKey(textArg, delay) else null
                    8  -> if (textArg.isNotBlank()) Step.LaunchApp(textArg, delay) else null
                    9  -> Step.WatchCorners(displayToSecs(toutVal, toutUnit).toInt().coerceAtLeast(1), delay)
                    10 -> if (textArg.isNotBlank()) Step.TapWhen(textArg, displayToSecs(toutVal, toutUnit).toInt().coerceAtLeast(1), delay) else null
                    11 -> if (textArg.isNotBlank() && branchSeqArg.isNotBlank()) Step.CheckBranch(textArg, branchSeqArg, delay) else null
                    12 -> Step.PressBack(delay)
                    13 -> Step.PressHome(delay)
                    14 -> Step.DismissAd(delay)
                    else -> null
                }
                step?.let { onConfirm(it) }
            }) { Text(if (initialStep == null) "Add" else "Update") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
