package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Transform
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.core.content.ContextCompat
import com.example.ui.components.MiniPlayerBar
import com.example.ui.screens.BatchScreen
import com.example.ui.screens.ConvertScreen
import com.example.ui.screens.LibraryScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.MyApplicationTheme

enum class AppTab {
    CONVERT,
    BATCH,
    LIBRARY,
    SETTINGS
}

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val themeMode by viewModel.themeMode.collectAsState()
            val strings by viewModel.strings.collectAsState()

            // Notification permission request for Android 13+
            val notificationPermissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission()
            ) { /* granted / denied handled */ }

            LaunchedEffect(Unit) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    if (ContextCompat.checkSelfPermission(
                            this@MainActivity,
                            Manifest.permission.POST_NOTIFICATIONS
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            }

            MyApplicationTheme(themeMode = themeMode) {
                var currentTab by remember { mutableStateOf(AppTab.CONVERT) }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    bottomBar = {
                        Column {
                            // Persistent Mini Player above Navigation Bar
                            MiniPlayerBar(viewModel = viewModel)

                            NavigationBar(
                                containerColor = MaterialTheme.colorScheme.surface,
                                modifier = Modifier
                                    .windowInsetsPadding(WindowInsets.navigationBars)
                                    .testTag("bottom_nav_bar")
                            ) {
                                NavigationBarItem(
                                    selected = currentTab == AppTab.CONVERT,
                                    onClick = { currentTab = AppTab.CONVERT },
                                    icon = { Icon(Icons.Default.Transform, contentDescription = strings.navConvert) },
                                    label = { Text(strings.navConvert, fontWeight = if (currentTab == AppTab.CONVERT) FontWeight.Bold else FontWeight.Normal) },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = MaterialTheme.colorScheme.primary,
                                        selectedTextColor = MaterialTheme.colorScheme.primary,
                                        indicatorColor = MaterialTheme.colorScheme.primaryContainer
                                    ),
                                    modifier = Modifier.testTag("tab_convert")
                                )
                                NavigationBarItem(
                                    selected = currentTab == AppTab.BATCH,
                                    onClick = { currentTab = AppTab.BATCH },
                                    icon = { Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = strings.navBatch) },
                                    label = { Text(strings.navBatch, fontWeight = if (currentTab == AppTab.BATCH) FontWeight.Bold else FontWeight.Normal) },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = MaterialTheme.colorScheme.primary,
                                        selectedTextColor = MaterialTheme.colorScheme.primary,
                                        indicatorColor = MaterialTheme.colorScheme.primaryContainer
                                    ),
                                    modifier = Modifier.testTag("tab_batch")
                                )
                                NavigationBarItem(
                                    selected = currentTab == AppTab.LIBRARY,
                                    onClick = { currentTab = AppTab.LIBRARY },
                                    icon = { Icon(Icons.Default.LibraryMusic, contentDescription = strings.navLibrary) },
                                    label = { Text(strings.navLibrary, fontWeight = if (currentTab == AppTab.LIBRARY) FontWeight.Bold else FontWeight.Normal) },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = MaterialTheme.colorScheme.primary,
                                        selectedTextColor = MaterialTheme.colorScheme.primary,
                                        indicatorColor = MaterialTheme.colorScheme.primaryContainer
                                    ),
                                    modifier = Modifier.testTag("tab_library")
                                )
                                NavigationBarItem(
                                    selected = currentTab == AppTab.SETTINGS,
                                    onClick = { currentTab = AppTab.SETTINGS },
                                    icon = { Icon(Icons.Default.Settings, contentDescription = strings.navSettings) },
                                    label = { Text(strings.navSettings, fontWeight = if (currentTab == AppTab.SETTINGS) FontWeight.Bold else FontWeight.Normal) },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = MaterialTheme.colorScheme.primary,
                                        selectedTextColor = MaterialTheme.colorScheme.primary,
                                        indicatorColor = MaterialTheme.colorScheme.primaryContainer
                                    ),
                                    modifier = Modifier.testTag("tab_settings")
                                )
                            }
                        }
                    }
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        AnimatedContent(
                            targetState = currentTab,
                            transitionSpec = { fadeIn() togetherWith fadeOut() },
                            label = "tab_transition"
                        ) { targetTab ->
                            when (targetTab) {
                                AppTab.CONVERT -> ConvertScreen(
                                    viewModel = viewModel,
                                    onNavigateToLibrary = { currentTab = AppTab.LIBRARY }
                                )
                                AppTab.BATCH -> BatchScreen(viewModel = viewModel)
                                AppTab.LIBRARY -> LibraryScreen(viewModel = viewModel)
                                AppTab.SETTINGS -> SettingsScreen(viewModel = viewModel)
                            }
                        }
                    }
                }
            }
        }
    }
}
