package com.mandro

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.mandro.presentation.navigation.BOTTOM_NAV_ITEMS
import com.mandro.presentation.navigation.Screen
import com.mandro.presentation.theme.MandroPalette
import com.mandro.presentation.theme.MandroTheme
import com.mandro.presentation.ui.ble.BleScreen
import com.mandro.presentation.ui.guide.GuideScreen
import com.mandro.presentation.ui.home.HomeScreen
import com.mandro.presentation.ui.splash.SplashScreen
import com.mandro.presentation.ui.user.UserScreen
import com.mandro.presentation.ui.classify.ClassifyScreen
import com.mandro.presentation.ui.collect.CollectScreen
import com.mandro.presentation.ui.firmware.FirmwareScreen
import com.mandro.presentation.ui.training.TrainingProgressScreen
import com.mandro.presentation.ui.waveform.WaveformScreen
import dagger.hilt.android.AndroidEntryPoint

// 바텀 네비가 표시될 화면 목록
private val BOTTOM_NAV_ROUTES = setOf(
    Screen.Waveform.route,
    Screen.Training.route,
    Screen.Classify.route,
    Screen.Settings.route,
)

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MandroTheme {
                val navController = rememberNavController()
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = navBackStackEntry?.destination?.route

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = MandroPalette.Neutral50,
                    bottomBar = {
                        if (currentRoute in BOTTOM_NAV_ROUTES) {
                            NavigationBar(
                                containerColor = MandroPalette.White,
                                tonalElevation = 0.dp,
                            ) {
                                BOTTOM_NAV_ITEMS.forEachIndexed { index, item ->
                                    val selected = currentRoute == item.screen.route
                                    val icon = when (index) {
                                        0 -> Icons.Filled.Timeline
                                        1 -> Icons.Filled.Home
                                        2 -> Icons.Filled.Search
                                        else -> Icons.Filled.Settings
                                    }
                                    NavigationBarItem(
                                        selected = selected,
                                        onClick = {
                                            navController.navigate(item.screen.route) {
                                                popUpTo(navController.graph.findStartDestination().id) {
                                                    saveState = true
                                                }
                                                launchSingleTop = true
                                                restoreState = true
                                            }
                                        },
                                        icon = {
                                            Icon(
                                                imageVector = icon,
                                                contentDescription = item.label,
                                            )
                                        },
                                        label = {
                                            Text(
                                                text = item.label,
                                                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                                            )
                                        },
                                        colors = NavigationBarItemDefaults.colors(
                                            selectedIconColor = MandroPalette.Primary600,
                                            selectedTextColor = MandroPalette.Primary600,
                                            unselectedIconColor = MandroPalette.Neutral500,
                                            unselectedTextColor = MandroPalette.Neutral500,
                                            indicatorColor = MandroPalette.Primary50,
                                        ),
                                    )
                                }
                            }
                        }
                    },
                ) { padding ->
                    NavHost(
                        navController = navController,
                        startDestination = Screen.Splash.route,
                        modifier = Modifier.padding(padding),
                    ) {
                        composable(Screen.Splash.route) {
                            SplashScreen(navController)
                        }
                        composable(Screen.Home.route) {
                            HomeScreen(
                                onAddUser = {
                                    navController.navigate(Screen.UserCreate.route)
                                },
                                onConnectBand = {
                                    navController.navigate(Screen.BleScan.route)
                                },
                            )
                        }
                        composable(Screen.UserCreate.route) {
                            UserScreen(
                                onBack = { navController.popBackStack() },
                                onStart = { navController.navigate(Screen.BleScan.route) },
                            )
                        }
                        composable(Screen.BleScan.route) {
                            BleScreen(
                                onBack = { navController.popBackStack() },
                                onConnected = {
                                    navController.navigate(Screen.Waveform.route)
                                },
                            )
                        }
                        composable(Screen.Waveform.route) {
                            WaveformScreen()
                        }
                        composable(Screen.Training.route) {
                            GuideScreen(
                                onBack = { navController.popBackStack() },
                                onStartRecord = {
                                    navController.navigate(Screen.Collect.route)
                                },
                            )
                        }
                        composable(Screen.Classify.route) {
                            ClassifyScreen(
                                onRelearn = {
                                    navController.navigate(Screen.Training.route)
                                },
                            )
                        }
                        composable(Screen.Settings.route) {
                            // TODO: SettingsScreen 구현 후 교체
                        }
                        composable(Screen.Guide.route) {
                            GuideScreen(
                                onBack = { navController.popBackStack() },
                                onStartRecord = {
                                    navController.navigate(Screen.Collect.route)
                                },
                            )
                        }
                        composable(Screen.Collect.route) {
                            CollectScreen(
                                onDone = {
                                    navController.navigate(Screen.TrainingProgress.route) {
                                        popUpTo(Screen.Training.route)
                                    }
                                },
                            )
                        }
                        composable(Screen.TrainingProgress.route) {
                            TrainingProgressScreen(
                                onDone = {
                                    navController.navigate(Screen.Firmware.route) {
                                        popUpTo(Screen.Training.route)
                                    }
                                },
                            )
                        }
                        composable(Screen.Firmware.route) {
                            FirmwareScreen(
                                onDone = {
                                    navController.navigate(Screen.Classify.route) {
                                        popUpTo(Screen.Training.route)
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}
