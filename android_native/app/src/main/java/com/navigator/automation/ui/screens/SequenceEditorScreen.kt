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

    // When the overlay point editor saves positions, update our steps
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
                    // Export via share sheet
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

                    // Save
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
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
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

                    // Edit tap positions on-screen (draggable overlay circles)
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
                        step = step,
                        index = idx,
                        onDelete  = { steps = steps.toMutableList().also { it.removeAt(idx) } },
                        onMoveUp  = {
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
        AddStepDialog(
            availableSequences = SequenceRepository.list(context).filter { it != name },
            onDismiss = { showAdd = false },
            onAdd = { step ->
                steps = steps.toMutableList().also { it.add(step) }
                showAdd = false
            }
        )
    }
}

@Composable
private fun StepRow(step: Step, index: Int, onDelete: () -> Unit, onMoveUp: () -> Unit, onMoveDown: () -> Unit) {
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
            IconButton(onClick = onDelete,   modifier = Modifier.size(32.dp)) { Icon(Icons.Default.Close, null,       modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error) }
        }
    }
}

// ── Add Step Dialog ───────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddStepDialog(
    availableSequences: List<String>,
    onDismiss: () -> Unit,
    onAdd: (Step) -> Unit
) {
    val context = LocalContext.current
    var selected by remember { mutableStateOf(0) }
    val types = listOf(
        "Tap text", "Tap coords", "Long press",
        "Wait", "Type text",
        "Swipe (direction)", "Swipe (coords)",
        "Press key", "Launch app", "Watch corners", "Check branch",
        "Press Back", "Press Home", "Dismiss Ad"
    )

    // Shared fields
    var textArg      by remember { mutableStateOf("") }
    var floatArg     by remember { mutableStateOf("1.0") }
    var intArg       by remember { mutableStateOf("25") }
    var durationArg  by remember { mutableStateOf("500") }
    var xArg         by remember { mutableStateOf("") }
    var yArg         by remember { mutableStateOf("") }
    var dirArg       by remember { mutableStateOf("up") }
    var branchSeqArg by remember { mutableStateOf(availableSequences.firstOrNull() ?: "") }
    var delayArg     by remember { mutableStateOf("0") }
    var repeatArg    by remember { mutableStateOf("1") }
    var swipeDurArg  by remember { mutableStateOf("300") }

    // SwipeCoords fields
    var x1Arg by remember { mutableStateOf("") }
    var y1Arg by remember { mutableStateOf("") }
    var x2Arg by remember { mutableStateOf("") }
    var y2Arg by remember { mutableStateOf("") }

    // Position recorder state
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
        title = { Text("Add Step") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // Type picker
                var expanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                    OutlinedTextField(
                        value = types[selected],
                        onValueChange = {},
                        readOnly = true,
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
                    0 -> OutlinedTextField(value = textArg, onValueChange = { textArg = it },
                            label = { Text("Text to tap") }, modifier = Modifier.fillMaxWidth(), singleLine = true)

                    // 1: Tap coords
                    1 -> {
                        FilledTonalButton(
                            onClick = { isRecording = true; startRecorder() },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(if (isRecording) "Tap anywhere on screen…" else "Record tap position") }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(value = xArg, onValueChange = { xArg = it }, label = { Text("X") },
                                modifier = Modifier.weight(1f), singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                            OutlinedTextField(value = yArg, onValueChange = { yArg = it }, label = { Text("Y") },
                                modifier = Modifier.weight(1f), singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(value = delayArg, onValueChange = { delayArg = it },
                                label = { Text("Delay before (ms)") }, modifier = Modifier.weight(1f), singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                            OutlinedTextField(value = repeatArg, onValueChange = { repeatArg = it },
                                label = { Text("Repeat") }, modifier = Modifier.weight(1f), singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        }
                    }

                    // 2: Long press
                    2 -> {
                        FilledTonalButton(
                            onClick = { isRecording = true; startRecorder() },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(if (isRecording) "Tap anywhere on screen…" else "Record press position") }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(value = xArg, onValueChange = { xArg = it }, label = { Text("X") },
                                modifier = Modifier.weight(1f), singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                            OutlinedTextField(value = yArg, onValueChange = { yArg = it }, label = { Text("Y") },
                                modifier = Modifier.weight(1f), singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                        }
                        OutlinedTextField(value = durationArg, onValueChange = { durationArg = it },
                            label = { Text("Hold duration (ms)") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(value = delayArg, onValueChange = { delayArg = it },
                                label = { Text("Delay before (ms)") }, modifier = Modifier.weight(1f), singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                            OutlinedTextField(value = repeatArg, onValueChange = { repeatArg = it },
                                label = { Text("Repeat") }, modifier = Modifier.weight(1f), singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        }
                    }

                    // 3: Wait
                    3 -> OutlinedTextField(value = floatArg, onValueChange = { floatArg = it },
                            label = { Text("Seconds") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))

                    // 4: Type text
                    4 -> OutlinedTextField(value = textArg, onValueChange = { textArg = it },
                            label = { Text("Text to type") }, modifier = Modifier.fillMaxWidth(), singleLine = true)

                    // 5: Swipe (direction)
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
                        OutlinedTextField(value = delayArg, onValueChange = { delayArg = it },
                            label = { Text("Delay before (ms)") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    }

                    // 6: Swipe coords
                    6 -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(
                                onClick = { isRecordingStart = true; startRecorder() },
                                modifier = Modifier.weight(1f)
                            ) { Text(if (isRecordingStart) "Tap start…" else "Record start") }
                            FilledTonalButton(
                                onClick = { isRecordingEnd = true; startRecorder() },
                                modifier = Modifier.weight(1f)
                            ) { Text(if (isRecordingEnd) "Tap end…" else "Record end") }
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
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(value = swipeDurArg, onValueChange = { swipeDurArg = it },
                                label = { Text("Duration (ms)") }, modifier = Modifier.weight(1f), singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                            OutlinedTextField(value = delayArg, onValueChange = { delayArg = it },
                                label = { Text("Delay before (ms)") }, modifier = Modifier.weight(1f), singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        }
                    }

                    // 7: Press key
                    7 -> OutlinedTextField(value = textArg, onValueChange = { textArg = it },
                            label = { Text("Key (back / home / recents)") },
                            modifier = Modifier.fillMaxWidth(), singleLine = true)

                    // 8: Launch app
                    8 -> OutlinedTextField(value = textArg, onValueChange = { textArg = it },
                            label = { Text("Package or app name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)

                    // 9: Watch corners
                    9 -> OutlinedTextField(value = intArg, onValueChange = { intArg = it },
                            label = { Text("Timeout (seconds)") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))

                    // 10: Check branch
                    10 -> {
                        OutlinedTextField(value = textArg, onValueChange = { textArg = it },
                            label = { Text("If screen shows text…") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        if (availableSequences.isEmpty()) {
                            Text("No other sequences — save this one first.",
                                color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                        } else {
                            Spacer(Modifier.height(4.dp))
                            var branchExp by remember { mutableStateOf(false) }
                            ExposedDropdownMenuBox(expanded = branchExp, onExpandedChange = { branchExp = it }) {
                                OutlinedTextField(value = branchSeqArg, onValueChange = {}, readOnly = true,
                                    label = { Text("Run sequence") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(branchExp) },
                                    modifier = Modifier.menuAnchor().fillMaxWidth())
                                ExposedDropdownMenu(expanded = branchExp, onDismissRequest = { branchExp = false }) {
                                    availableSequences.forEach { s ->
                                        DropdownMenuItem(text = { Text(s) }, onClick = { branchSeqArg = s; branchExp = false })
                                    }
                                }
                            }
                        }
                    }

                    // 11, 12, 13 — no args
                    else -> Text("No configuration needed for this step type.",
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val step: Step? = when (selected) {
                    0  -> if (textArg.isNotBlank()) Step.TapText(textArg) else null
                    1  -> {
                        val x = xArg.toFloatOrNull(); val y = yArg.toFloatOrNull()
                        if (x != null && y != null) Step.TapCoords(
                            x, y,
                            delayArg.toLongOrNull() ?: 0L,
                            repeatArg.toIntOrNull()?.coerceAtLeast(1) ?: 1
                        ) else null
                    }
                    2  -> {
                        val x = xArg.toFloatOrNull(); val y = yArg.toFloatOrNull()
                        if (x != null && y != null) Step.LongPress(
                            x, y,
                            durationArg.toLongOrNull() ?: 500L,
                            delayArg.toLongOrNull() ?: 0L,
                            repeatArg.toIntOrNull()?.coerceAtLeast(1) ?: 1
                        ) else null
                    }
                    3  -> Step.WaitSeconds(floatArg.toFloatOrNull() ?: 1f)
                    4  -> if (textArg.isNotBlank()) Step.TypeText(textArg) else null
                    5  -> Step.Swipe(dirArg, delayArg.toLongOrNull() ?: 0L)
                    6  -> {
                        val x1 = x1Arg.toFloatOrNull(); val y1 = y1Arg.toFloatOrNull()
                        val x2 = x2Arg.toFloatOrNull(); val y2 = y2Arg.toFloatOrNull()
                        if (x1 != null && y1 != null && x2 != null && y2 != null) Step.SwipeCoords(
                            x1, y1, x2, y2,
                            swipeDurArg.toLongOrNull() ?: 300L,
                            delayArg.toLongOrNull() ?: 0L
                        ) else null
                    }
                    7  -> if (textArg.isNotBlank()) Step.PressKey(textArg) else null
                    8  -> if (textArg.isNotBlank()) Step.LaunchApp(textArg) else null
                    9  -> Step.WatchCorners(intArg.toIntOrNull() ?: 25)
                    10 -> if (textArg.isNotBlank() && branchSeqArg.isNotBlank())
                              Step.CheckBranch(textArg, branchSeqArg) else null
                    11 -> Step.PressBack
                    12 -> Step.PressHome
                    13 -> Step.DismissAd
                    else -> null
                }
                step?.let { onAdd(it) }
            }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
