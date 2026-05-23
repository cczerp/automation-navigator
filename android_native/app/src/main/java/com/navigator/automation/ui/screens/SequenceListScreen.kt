package com.navigator.automation.ui.screens

import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.navigator.automation.engine.Sequence
import com.navigator.automation.engine.SequenceRepository
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SequenceListScreen(
    onBack: () -> Unit,
    onEdit: (String?) -> Unit,
    onRun: (String) -> Unit
) {
    val context = LocalContext.current
    var sequences    by remember { mutableStateOf(emptyList<String>()) }
    var deleteTarget by remember { mutableStateOf<String?>(null) }
    var showImport   by remember { mutableStateOf(false) }

    fun reload() { sequences = SequenceRepository.list(context) }
    LaunchedEffect(Unit) { reload() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("My Sequences") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }
                },
                actions = {
                    IconButton(onClick = { showImport = true }) {
                        Icon(Icons.Default.Download, "Import from clipboard")
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { onEdit(null) },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("New Sequence") }
            )
        }
    ) { padding ->
        if (sequences.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("No sequences — tap + to create one",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(sequences) { name ->
                    SequenceCard(
                        name = name,
                        onEdit   = { onEdit(name) },
                        onRun    = { onRun(name) },
                        onDelete = { deleteTarget = name }
                    )
                }
            }
        }
    }

    deleteTarget?.let { name ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete \"$name\"?") },
            text  = { Text("This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    SequenceRepository.delete(context, name)
                    deleteTarget = null
                    reload()
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } }
        )
    }

    if (showImport) {
        ImportDialog(
            onDismiss = { showImport = false },
            onImported = { reload() }
        )
    }
}

@Composable
private fun ImportDialog(onDismiss: () -> Unit, onImported: () -> Unit) {
    val context = LocalContext.current
    var jsonText by remember {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
        mutableStateOf(clip)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import Sequence") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Paste exported sequence JSON below.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = jsonText,
                    onValueChange = { jsonText = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                    label = { Text("JSON") },
                    minLines = 4
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                try {
                    val seq = Sequence.fromJson(JSONObject(jsonText.trim()))
                    SequenceRepository.save(context, seq)
                    onImported()
                    onDismiss()
                    Toast.makeText(context, "Imported: ${seq.name}", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(context, "Invalid JSON: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }) { Text("Import") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun SequenceCard(name: String, onEdit: () -> Unit, onRun: () -> Unit, onDelete: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(name, modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
            IconButton(onClick = onRun)    { Icon(Icons.Default.PlayArrow, "Run",    tint = MaterialTheme.colorScheme.primary) }
            IconButton(onClick = onEdit)   { Icon(Icons.Default.Edit,      "Edit") }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete,    "Delete", tint = MaterialTheme.colorScheme.error) }
        }
    }
}
