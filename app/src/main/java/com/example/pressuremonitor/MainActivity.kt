package com.example.pressuremonitor

/*
  App entry point. Sets up:
    - The shared BleViewModel (scoped to this Activity, so it survives
      navigation between screens but not app restarts)
    - Navigation Compose routes: home, graph, sessions, sessions/{id}
    - Runtime Bluetooth permission requesting (must happen at the
      Activity level - the ViewModel just gets told once it's granted)

  Requires the following dependency in app/build.gradle.kts:
      implementation("androidx.navigation:navigation-compose:2.9.7")
*/

import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val bleViewModel: BleViewModel by viewModels()

    // Bumped every time a real connection attempt starts (permissions
    // already granted). Used as a LaunchedEffect key below, so each new
    // attempt gets its own fresh 5s timeout window.
    private var connectAttempt by mutableStateOf(0)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            connectAndStartTimeoutWatcher()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                val snackbarHostState = remember { SnackbarHostState() }
                val coroutineScope = rememberCoroutineScope()

                Scaffold(
                    snackbarHost = { SnackbarHost(snackbarHostState) }
                ) { innerPadding ->

                    // Fires 5s after each connection attempt (connectAttempt
                    // changing restarts this effect, cancelling any previous
                    // pending timer). If still not connected by then, we let
                    // the user know - the underlying scan/retry loop in
                    // BleViewModel keeps running regardless.
                    LaunchedEffect(connectAttempt) {
                        if (connectAttempt > 0) {
                            delay(5000)
                            if (!bleViewModel.isConnected) {
                                snackbarHostState.showSnackbar(
                                    "Could not connect to the sensor. Make sure it's powered on and nearby."
                                )
                            }
                        }
                    }

                    Surface(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        val navController = rememberNavController()

                        NavHost(navController = navController, startDestination = "home") {
                            composable("home") {
                                HomeScreen(
                                    isConnected = bleViewModel.isConnected,
                                    isConnecting = bleViewModel.isConnecting,
                                    onConnectClick = {
                                        if (!bleViewModel.isBluetoothEnabled()) {
                                            coroutineScope.launch {
                                                snackbarHostState.showSnackbar(
                                                    "Bluetooth is off. Please turn it on and press Connect again."
                                                )
                                            }
                                        } else {
                                            requestPermissionsAndConnect()
                                        }
                                    },
                                    onGraphClick = { navController.navigate("graph") },
                                    onSessionsClick = { navController.navigate("sessions") }
                                )
                            }
                            composable("graph") {
                                PressureGraphScreen(
                                    viewModel = bleViewModel,
                                    onBack = { navController.popBackStack() }
                                )
                            }
                            composable("sessions") {
                                LoggedSessionsScreen(
                                    onSessionClick = { id -> navController.navigate("sessions/$id") },
                                    onBack = { navController.popBackStack() }
                                )
                            }
                            composable(
                                route = "sessions/{sessionId}",
                                arguments = listOf(navArgument("sessionId") { type = NavType.LongType })
                            ) { backStackEntry ->
                                val sessionId = backStackEntry.arguments?.getLong("sessionId") ?: 0L
                                SessionDetailScreen(
                                    sessionId = sessionId,
                                    onBack = { navController.popBackStack() }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun requestPermissionsAndConnect() {
        val missing = bleViewModel.requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            connectAndStartTimeoutWatcher()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun connectAndStartTimeoutWatcher() {
        bleViewModel.beginConnection()
        connectAttempt++
    }
}