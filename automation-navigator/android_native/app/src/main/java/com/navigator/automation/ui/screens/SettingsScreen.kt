package com.navigator.automation.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
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
import com.navigator.automation.service.AutomationAccessibilityService
import com.navigator.automation.service.OverlayService

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    // Re-check on every composition (user may just have returned from system settings)
    val accessibilityEnabled by remember {
        derivedStateOf { AutomationAccessibilityService.instance != null }
    }
    val overlayEnabled = remember {
        derivedStateOf { Settings.canDrawOverlays(context) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {

            Text("Permissions", fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), fontSize = 12.sp)

            // Accessibility service
            PermissionCard(
                title = "Accessibility Service",
                subtitle = "Required — lets the app tap, swipe, and read the screen",
                granted = accessibilityEnabled,
                onEnable = {
                    context.startActivity(
                        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                    )
                }
            )

            // Overlay permission
            PermissionCard(
                title = "Display Over Other Apps",
                subtitle = "Optional — shows floating control button while running",
                granted = overlayEnabled.value,
                onEnable = {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        ).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
                    )
                }
            )

            // Overlay service toggle
            if (overlayEnabled.value) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Floating Button", fontWeight = FontWeight.Medium)
                            Text("Keep visible while running sequences",
                                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        }
                        val running = OverlayService.isRunning
                        Switch(
                            checked = running,
                            onCheckedChange = { on ->
                                val intent = Intent(context, OverlayService::class.java)
                                if (on) context.startService(intent)
                                else   context.stopService(intent)
                            }
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Text("About", fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), fontSize = 12.sp)

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Automation Navigator", fontWeight = FontWeight.Medium)
                    Text("Fully on-device phone automation — no PC required.",
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                }
            }
        }
    }
}

@Composable
private fun PermissionCard(
    title: String,
    subtitle: String,
    granted: Boolean,
    onEnable: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                if (granted) Icons.Default.CheckCircle else Icons.Default.Warning,
                null,
                tint = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                modifier = Modifier.size(28.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Medium)
                Text(subtitle, fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
            if (!granted) {
                FilledTonalButton(onClick = onEnable) { Text("Enable") }
            }
        }
    }
}
