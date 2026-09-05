package com.kin.familyhealth

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.kin.familyhealth.ui.theme.KinTheme

/**
 * FOUNDATION-owned Compose host + NavGraph.
 *
 * Routes (see ARCHITECTURE.md "Shared contracts"):
 *   - onboarding       -> AGENT-ONBOARD
 *   - dashboard        -> AGENT-VITALS
 *   - call/{callerId}  -> AGENT-CALL
 *   - settings         -> FOUNDATION owns the settings screen shell; wire up
 *                         SettingsRepository-backed controls as needed.
 *
 * Feature agents: expose a single `@Composable EntryScreen(nav, ...)` per
 * route in your own package and wire it into the NavHost below by replacing
 * the corresponding placeholder composable. Do not restructure the NavHost.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            KinTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    KinNavHost()
                }
            }
        }
    }
}

@Composable
fun KinNavHost(navController: NavHostController = rememberNavController()) {
    NavHost(navController = navController, startDestination = "onboarding") {
        composable("onboarding") {
            // AGENT-ONBOARD fills this
            LoadingPlaceholder("onboarding")
        }
        composable("dashboard") {
            // AGENT-VITALS fills this
            LoadingPlaceholder("dashboard")
        }
        composable("call/{callerId}") {
            // AGENT-CALL fills this
            LoadingPlaceholder("call")
        }
        composable("settings") {
            // FOUNDATION: replace with a real settings screen backed by
            // SettingsRepository if/when needed; placeholder for now.
            LoadingPlaceholder("settings")
        }
    }
}

@Composable
private fun LoadingPlaceholder(route: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("Loading… ($route)")
    }
}
