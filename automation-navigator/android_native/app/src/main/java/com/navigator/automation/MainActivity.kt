package com.navigator.automation

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.navigator.automation.ui.screens.*
import com.navigator.automation.ui.theme.AppTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                AppNav()
            }
        }
    }
}

@Composable
private fun AppNav() {
    val navController = rememberNavController()

    NavHost(navController, startDestination = "home") {

        composable("home") {
            HomeScreen(
                onNavigateSequences = { navController.navigate("sequences") },
                onNavigateSettings  = { navController.navigate("settings") },
                onRunSequence       = { name -> navController.navigate("run/${encode(name)}") }
            )
        }

        composable("sequences") {
            SequenceListScreen(
                onBack = { navController.popBackStack() },
                onEdit = { name ->
                    if (name == null) navController.navigate("editor/new")
                    else navController.navigate("editor/${encode(name)}")
                },
                onRun  = { name -> navController.navigate("run/${encode(name)}") }
            )
        }

        composable(
            route = "editor/{name}",
            arguments = listOf(navArgument("name") { type = NavType.StringType })
        ) { back ->
            val raw = back.arguments?.getString("name") ?: "new"
            SequenceEditorScreen(
                sequenceName = if (raw == "new") null else raw,
                onBack   = { navController.popBackStack() },
                onSaved  = { navController.popBackStack() }
            )
        }

        composable(
            route = "run/{name}",
            arguments = listOf(navArgument("name") { type = NavType.StringType })
        ) { back ->
            val name = back.arguments?.getString("name") ?: return@composable
            RunScreen(
                sequenceName = name,
                onBack = { navController.popBackStack() }
            )
        }

        composable("settings") {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
    }
}

/** URL-encode sequence names for safe nav route usage. */
private fun encode(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")
