package com.kin.familyhealth

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.kin.familyhealth.di.ServiceLocator
import com.kin.familyhealth.ui.theme.KinTheme
import com.kin.familyhealth.vitals.VitalsViewModel

/**
 * FOUNDATION-owned Compose host + NavGraph, wired by the commander to the real
 * feature EntryScreens. Routes: onboarding, dashboard, call/{callerId}, settings.
 * EntryScreen calls are fully qualified to avoid overload ambiguity (onboarding
 * exposes two EntryScreen overloads in one package).
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
            val context = LocalContext.current
            com.kin.familyhealth.onboarding.EntryScreen(
                onFinished = {
                    navController.navigate("dashboard") {
                        popUpTo("onboarding") { inclusive = true }
                    }
                },
                pairing = ServiceLocator.pairing(context),
                healthConnectPermissions = ServiceLocator.healthPermissions(context),
            )
        }

        composable("dashboard") {
            val context = LocalContext.current
            com.kin.familyhealth.dashboard.EntryScreen(
                onOpenSettings = { navController.navigate("settings") },
                onReachIn = { ServiceLocator.startReachIn(context) },
                factory = VitalsViewModel.Factory(
                    context,
                    ServiceLocator.vitalsSync(context),
                    ServiceLocator.myUid(),
                ),
            )
        }

        composable(
            route = "call/{callerId}",
            arguments = listOf(navArgument("callerId") { type = NavType.StringType }),
        ) { entry ->
            com.kin.familyhealth.call.EntryScreen(
                callerId = entry.arguments?.getString("callerId").orEmpty(),
            )
        }

        composable("settings") {
            com.kin.familyhealth.onboarding.EntryScreen(
                onBack = { navController.popBackStack() },
            )
        }
    }
}
