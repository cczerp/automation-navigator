package com.navigator.automation.ui.screens

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
import com.navigator.automation.engine.SequenceRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SequenceListScreen(
    onBack: () -> Unit,
    onEdit: (String?) -> Unit,      // null = new
    onRun: (String) -> Unit
) {
    val context = LocalContext.current
    var sequences by remember { mutableStateOf(emptyList<String>()) }
    var deleteTarget by remember { mutableStateOf<String?>(null) }

    fun reload() { sequences = SequenceRepository.list(context) }
    LaunchedEffect(Unit) { reload() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("My Sequences") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }
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
                        onEdit = { onEdit(name) },
                        onRun  = { onRun(name) },
                        onDelete = { deleteTarget = name }
                    )
                }
            }
        }
    }

    // Confirm delete
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
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun SequenceCard(name: String, onEdit: () -> Unit, onRun: () -> Unit, onDelete: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(name, modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
            IconButton(onClick = onRun)    { Icon(Icons.Default.PlayArrow, "Run", tint = MaterialTheme.colorScheme.primary) }
            IconButton(onClick = onEdit)   { Icon(Icons.Default.Edit, "Edit") }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Delete", tint = MaterialTheme.colorScheme.error) }
        }
    }
}
