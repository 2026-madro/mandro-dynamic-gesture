package com.mandro

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.mandro.presentation.navigation.Screen
import com.mandro.presentation.theme.MandroTheme
import com.mandro.presentation.ui.ble.BleScreen
import com.mandro.presentation.ui.guide.GuideScreen
import com.mandro.presentation.ui.home.HomeScreen
import com.mandro.presentation.ui.splash.SplashScreen
import com.mandro.presentation.ui.user.UserScreen
import com.mandro.presentation.ui.waveform.WaveformScreen
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MandroTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val navController = rememberNavController()
                    NavHost(
                        navController = navController,
                        startDestination = Screen.Splash.route,
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
                            )
                        }
                        composable(Screen.Waveform.route) {
                            WaveformScreen()
                        }
                        composable(Screen.Guide.route) {
                            GuideScreen(
                                onBack = { navController.popBackStack() },
                                onStartRecord = {
                                    navController.navigate(Screen.Collect.route)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}
