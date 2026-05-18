package com.navigator.automation.ui.screens

import android.content.Intent
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
import com.navigator.automation.service.AutomationAccessibilityService
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RunScreen(
    sequenceName: String,
    onBack: () -> Unit
) {
    val context  = LocalContext.current
    val sequence = remember(sequenceName) { SequenceRepository.load(context, sequenceName) }

    // Send the sequence to the accessibility service to run — it keeps going even when
    // this screen is off screen (e.g. while the automated app is in the foreground).
    LaunchedEffect(sequence) {
        if (sequence == null) return@LaunchedEffect
        val svc = AutomationAccessibilityService.instance
        if (svc == null) return@LaunchedEffect
        val intent = Intent(context, AutomationAccessibilityService::class.java).apply {
            action = AutomationAccessibilityService.ACTION_RUN
            putExtra(AutomationAccessibilityService.EXTRA_SEQUENCE, sequence.toJson().toString())
        }
        context.startService(intent)
    }

    // Poll the service engine status — the engine lives in the service, not here,
    // so this keeps showing live updates even after we navigate back and return.
    var status by remember { mutableStateOf(EngineStatus(totalSteps = sequence?.steps?.size ?: 0)) }
    LaunchedEffect(Unit) {
        while (true) {
            AutomationAccessibilityService.currentEngine?.status?.value?.let { status = it }
            delay(200)
        }
    }

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
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->

        if (sequence == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("Sequence not found", color = MaterialTheme.colorScheme.error)
            }
            return@Scaffold
        }

        if (AutomationAccessibilityService.instance == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally,
                       verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Default.Warning, null,
                        tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(48.dp))
                    Text("Accessibility Service is not enabled",
                        fontWeight = FontWeight.Medium)
                    Text("Go to Settings → enable it, then come back.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Spacer(Modifier.height(16.dp))

            Text(
                status.state.name,
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = stateColor
            )

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

            when (status.state) {
                RunState.RUNNING -> Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    OutlinedButton(onClick = {
                        context.startService(Intent(context, AutomationAccessibilityService::class.java)
                            .apply { action = AutomationAccessibilityService.ACTION_PAUSE })
                    }) {
                        Icon(Icons.Default.Pause, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Pause")
                    }
                    Button(
                        onClick = {
                            context.startService(Intent(context, AutomationAccessibilityService::class.java)
                                .apply { action = AutomationAccessibilityService.ACTION_STOP })
                            onBack()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Icon(Icons.Default.Stop, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Stop")
                    }
                }
                RunState.PAUSED -> Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Button(onClick = {
                        context.startService(Intent(context, AutomationAccessibilityService::class.java)
                            .apply { action = AutomationAccessibilityService.ACTION_RESUME })
                    }) {
                        Icon(Icons.Default.PlayArrow, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Resume")
                    }
                    OutlinedButton(onClick = {
                        context.startService(Intent(context, AutomationAccessibilityService::class.java)
                            .apply { action = AutomationAccessibilityService.ACTION_STOP })
                        onBack()
                    }) { Text("Stop") }
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
