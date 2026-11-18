package com.example.displayconnect.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.displayconnect.viewmodel.MainViewModel
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.displayconnect.R
import com.example.displayconnect.ui.screens.DataScreen
import com.example.displayconnect.ui.screens.HomeScreen
import com.example.displayconnect.ui.screens.MainScreen
import com.example.displayconnect.ui.screens.SettingsScreen

object Routes {
    const val HOME = "home"
    const val CLASSIC = "classic"
    const val SETTINGS = "settings"
    const val BOARD = "board"
    const val SENSORS = "sensors"
    const val ROUTES = "routes"
    const val EXTRAS = "extras"
    const val ADVANCED = "advanced"
}

@Composable
fun DisplayConnectNavHost(
    onRequestLocationPermission: () -> Unit,
    onRequestBluetoothPermission: () -> Unit,
    modifier: Modifier = Modifier
) {
    val navController = rememberNavController()
    val mainViewModel: MainViewModel = viewModel()

    NavHost(
        navController = navController,
        startDestination = Routes.HOME,
        modifier = modifier
    ) {
        composable(Routes.HOME) {
            HomeScreen(
                viewModel = mainViewModel,
                onNavigateToSettings = { navController.navigate(Routes.CLASSIC) },
                onOpenClassic = { navController.navigate(Routes.CLASSIC) },
                onRequestLocationPermission = onRequestLocationPermission,
                onRequestBluetoothPermission = onRequestBluetoothPermission
            )
        }
        composable(Routes.CLASSIC) {
            DataScreen(
                onNavigateBack = { navController.popBackStack(Routes.HOME, false) },
                onOpenSection = { navController.navigate(it) }
            )
        }
        listOf(Routes.BOARD, Routes.SENSORS, Routes.ROUTES).forEach { section ->
            composable(section) {
                MainScreen(
                    viewModel = mainViewModel,
                    section = section,
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToSettings = { navController.navigate(Routes.EXTRAS) },
                    onRequestLocationPermission = onRequestLocationPermission,
                    onRequestBluetoothPermission = onRequestBluetoothPermission
                )
            }
        }
        listOf(Routes.SETTINGS, Routes.EXTRAS, Routes.ADVANCED).forEach { route ->
            composable(route) {
                SettingsScreen(
                    mainViewModel = mainViewModel,
                    section = when (route) { Routes.EXTRAS -> "board"; Routes.ADVANCED -> "advanced"; else -> "preferences" },
                    onNavigateBack = { navController.popBackStack() }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsTopBar(onNavigateBack: () -> Unit) {
    TopAppBar(
        title = { Text(stringResource(R.string.settings_title)) },
        navigationIcon = {
            IconButton(onClick = onNavigateBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainTopBar(onNavigateToSettings: () -> Unit) {
    TopAppBar(
        title = { Text(stringResource(R.string.app_name)) },
        actions = {
            IconButton(onClick = onNavigateToSettings) {
                Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings))
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataTopBar(title: String, onNavigateBack: () -> Unit) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            IconButton(onClick = onNavigateBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
            }
        }
    )
}
