package com.navigator.automation.ui.screens

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SequenceEditorScreen(
    sequenceName: String?,   // null = new
    onBack: () -> Unit,
    onSaved: () -> Unit
) {
    val context = LocalContext.current
    val existing = remember(sequenceName) {
        sequenceName?.let { SequenceRepository.load(context, it) }
    }

    var name       by remember { mutableStateOf(existing?.name ?: "") }
    var steps      by remember { mutableStateOf(existing?.steps?.toMutableList() ?: mutableListOf()) }
    var loopCount  by remember { mutableStateOf(existing?.loopCount?.toString() ?: "1") }
    var loopDelay  by remember { mutableStateOf(existing?.loopDelaySeconds?.toString() ?: "1.0") }
    var showAdd    by remember { mutableStateOf(false) }
    var nameError  by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (sequenceName == null) "New Sequence" else "Edit Sequence") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }
                },
                actions = {
                    IconButton(onClick = {
                        if (name.isBlank()) { nameError = true; return@IconButton }
                        SequenceRepository.save(
                            context,
                            Sequence(
                                name = name.trim(),
                                steps = steps.toList(),
                                loopCount = loopCount.toIntOrNull() ?: 1,
                                loopDelaySeconds = loopDelay.toFloatOrNull() ?: 1f
                            )
                        )
                        // Delete old name if renamed
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
            // Name field
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

            // Loop settings
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
                        label = { Text("Delay (s)") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                    )
                }
            }

            // Step header
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Steps", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    FilledTonalButton(onClick = { showAdd = true }) {
                        Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Add step")
                    }
                }
            }

            // Step list
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
                        onDelete = { steps = steps.toMutableList().also { it.removeAt(idx) } },
                        onMoveUp = {
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
    var selected by remember { mutableStateOf(0) }
    val types = listOf("Tap button", "Tap text", "Wait", "Type text", "Swipe", "Press key",
                       "Launch app", "Watch corners", "Check branch",
                       "Press Back", "Press Home", "Dismiss Ad")

    var textArg      by remember { mutableStateOf("") }
    var floatArg     by remember { mutableStateOf("1.0") }
    var intArg       by remember { mutableStateOf("25") }
    var dirArg       by remember { mutableStateOf("up") }
    var branchSeqArg by remember { mutableStateOf(availableSequences.firstOrNull() ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Step") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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

                // Dynamic fields
                when (selected) {
                    0, 1 -> OutlinedTextField(value = textArg, onValueChange = { textArg = it },
                                label = { Text(if (selected == 0) "Button text to click" else "Text to tap") },
                                modifier = Modifier.fillMaxWidth(), singleLine = true)
                    2    -> OutlinedTextField(value = floatArg, onValueChange = { floatArg = it },
                                label = { Text("Seconds") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    3    -> OutlinedTextField(value = textArg, onValueChange = { textArg = it },
                                label = { Text("Text to type") },
                                modifier = Modifier.fillMaxWidth(), singleLine = true)
                    4    -> {
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
                    }
                    5    -> OutlinedTextField(value = textArg, onValueChange = { textArg = it },
                                label = { Text("Key (back / home / recents)") },
                                modifier = Modifier.fillMaxWidth(), singleLine = true)
                    6    -> OutlinedTextField(value = textArg, onValueChange = { textArg = it },
                                label = { Text("Package or app name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    7    -> OutlinedTextField(value = intArg, onValueChange = { intArg = it },
                                label = { Text("Timeout (seconds)") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    8    -> {
                        OutlinedTextField(value = textArg, onValueChange = { textArg = it },
                            label = { Text("If screen shows text…") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        Spacer(Modifier.height(4.dp))
                        if (availableSequences.isEmpty()) {
                            Text("No other sequences to branch to — save this one first.",
                                color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                        } else {
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
                    // 9,10,11 — no args needed
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val step: Step? = when (selected) {
                    0  -> if (textArg.isNotBlank())  Step.TapButton(textArg) else null
                    1  -> if (textArg.isNotBlank())  Step.TapText(textArg)   else null
                    2  -> Step.WaitSeconds(floatArg.toFloatOrNull() ?: 1f)
                    3  -> if (textArg.isNotBlank())  Step.TypeText(textArg)  else null
                    4  -> Step.Swipe(dirArg)
                    5  -> if (textArg.isNotBlank())  Step.PressKey(textArg)  else null
                    6  -> if (textArg.isNotBlank())  Step.LaunchApp(textArg) else null
                    7  -> Step.WatchCorners(intArg.toIntOrNull() ?: 25)
                    8  -> if (textArg.isNotBlank() && branchSeqArg.isNotBlank())
                              Step.CheckBranch(textArg, branchSeqArg) else null
                    9  -> Step.PressBack
                    10 -> Step.PressHome
                    11 -> Step.DismissAd
                    else -> null
                }
                step?.let { onAdd(it) }
            }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
