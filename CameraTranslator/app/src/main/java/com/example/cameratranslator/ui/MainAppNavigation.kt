package com.example.cameratranslator.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import com.example.cameratranslator.core.MainViewModel

@Composable
fun MainAppNavigation(viewModel: MainViewModel) {
    var screen by remember { mutableStateOf("Camera") }
    BackHandler(enabled = screen != "Camera") { screen = "Camera" }
    when (screen) {
        "Camera" -> MainScreen(
            viewModel = viewModel,
            onNavigateToPicker = { screen = "Picker" },
            onNavigateToSettings = { screen = "Settings" }
        )
        "Picker" -> ImagePickerScreen(viewModel = viewModel, onReturnToCamera = {
            screen = "Camera"
        })
        "Settings" -> SettingsScreen(viewModel = viewModel, onBack = { screen = "Camera" })
    }
}
