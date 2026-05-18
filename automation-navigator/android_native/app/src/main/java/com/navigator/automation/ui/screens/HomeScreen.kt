package com.navigator.automation.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.navigator.automation.engine.SequenceRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateSequences: () -> Unit,
    onNavigateSettings: () -> Unit,
    onRunSequence: (String) -> Unit
) {
    val context = LocalContext.current
    var sequences by remember { mutableStateOf(emptyList<String>()) }

    LaunchedEffect(Unit) { sequences = SequenceRepository.list(context) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Automation Navigator", fontWeight = FontWeight.Bold)
                        Text("Your phone, on autopilot", fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateSettings) {
                        Icon(Icons.Default.Settings, "Settings")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Nav tiles
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                NavCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.PlayArrow,
                    label = "My Sequences",
                    onClick = onNavigateSequences
                )
                NavCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.Add,
                    label = "New Sequence",
                    onClick = onNavigateSequences
                )
            }

            Divider()

            if (sequences.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.PlayArrow, null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
                        Spacer(Modifier.height(8.dp))
                        Text("No sequences yet", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                        Spacer(Modifier.height(4.dp))
                        TextButton(onClick = onNavigateSequences) { Text("Create one") }
                    }
                }
            } else {
                Text("Quick Run", fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(sequences) { name ->
                        QuickRunCard(name = name, onClick = { onRunSequence(name) })
                    }
                }
            }
        }
    }
}

@Composable
private fun NavCard(modifier: Modifier, icon: androidx.compose.ui.graphics.vector.ImageVector,
                    label: String, onClick: () -> Unit) {
    Card(modifier = modifier, onClick = onClick) {
        Column(
            modifier = Modifier.padding(20.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(icon, null, modifier = Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
            Text(label, fontWeight = FontWeight.Medium, fontSize = 14.sp)
        }
    }
}

@Composable
private fun QuickRunCard(name: String, onClick: () -> Unit) {
    OutlinedCard(onClick = onClick) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(Icons.Default.PlayArrow, null, tint = MaterialTheme.colorScheme.primary)
            Text(name, fontWeight = FontWeight.Medium, maxLines = 2, fontSize = 13.sp)
        }
    }
}
