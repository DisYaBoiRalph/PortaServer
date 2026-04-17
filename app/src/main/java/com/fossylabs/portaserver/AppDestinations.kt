package com.fossylabs.portaserver

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.ui.graphics.vector.ImageVector

enum class AppDestinations(
    val label: String,
    val icon: ImageVector,
    val route: String,
) {
    LLM("LLM", Icons.Rounded.Psychology, "llm"),
    SQL("SQL", Icons.Rounded.Storage, "sql"),
    SETTINGS("Settings", Icons.Rounded.Settings, "settings"),
}
