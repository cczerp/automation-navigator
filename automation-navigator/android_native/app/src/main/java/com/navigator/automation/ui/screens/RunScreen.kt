package com.navigator.automation.ui.screens

import androidx.compose.foundation.layout.*
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
import com.navigator.automation.engine.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RunScreen(
    sequenceName: String,
    onBack: () -> Unit
) {
    val context  = LocalContext.current
    val sequence = remember(sequenceName) { SequenceRepository.load(context, sequenceName) }

    // Engine lives for the lifetime of this composable
    val engineScope = remember { CoroutineScope(Dispatchers.Default + SupervisorJob()) }
    val engine = remember(sequence) {
        sequence?.let { SequenceEngine(context, it, engineScope) }
    }

    val status by (engine?.status ?: return).collectAsState()

    // Auto-start
    LaunchedEffect(engine) { engine?.start() }

    // Cleanup when leaving
    DisposableEffect(engine) { onDispose { engine?.stop() } }

    val stateColor = when (status.state) {
        RunState.RUNNING -> MaterialTheme.colorScheme.primary
        RunState.PAUSED  -> MaterialTheme.colorScheme.secondary
        RunState.DONE    -> MaterialTheme.colorScheme.tertiary
        RunState.ERROR   -> MaterialTheme.colorScheme.error
        RunState.IDLE    -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(sequenceName) },
                navigationIcon = {
                    IconButton(onClick = { engine?.stop(); onBack() }) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {

            Spacer(Modifier.height(16.dp))

            // Big state indicator
            Text(
                status.state.name,
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = stateColor
            )

            // Progress
            if (status.totalSteps > 0) {
                val progress = (status.currentStep + 1).toFloat() / status.totalSteps
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                    color = stateColor
                )
                Text(
                    "Step ${status.currentStep + 1} of ${status.totalSteps}" +
                        if (status.loopIteration > 1) "  ·  Loop ${status.loopIteration}" else "",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }

            // Current step label
            if (status.message.isNotEmpty()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        status.message,
                        modifier = Modifier.padding(16.dp),
                        fontSize = 15.sp
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            // Controls
            when (status.state) {
                RunState.RUNNING -> Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    OutlinedButton(onClick = { engine?.pause() }) {
                        Icon(Icons.Default.Pause, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Pause")
                    }
                    Button(
                        onClick = { engine?.stop(); onBack() },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Icon(Icons.Default.Stop, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Stop")
                    }
                }
                RunState.PAUSED -> Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Button(onClick = { engine?.resume() }) {
                        Icon(Icons.Default.PlayArrow, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Resume")
                    }
                    OutlinedButton(onClick = { engine?.stop(); onBack() }) { Text("Stop") }
                }
                RunState.DONE, RunState.ERROR, RunState.IDLE ->
                    Button(onClick = onBack) {
                        Icon(Icons.Default.Check, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Done")
                    }
            }
        }
    }
}
