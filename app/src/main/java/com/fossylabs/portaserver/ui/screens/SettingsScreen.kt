package com.fossylabs.portaserver.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = viewModel(),
    modifier: Modifier = Modifier,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var timeoutFieldValue by remember { mutableStateOf(settings.inactivityTimeoutMinutes?.toString() ?: "") }
    var llmPortValue by remember { mutableStateOf(settings.llmPort.toString()) }
    var sqlPortValue by remember { mutableStateOf(settings.sqlPort.toString()) }

    LaunchedEffect(settings.inactivityTimeoutMinutes) {
        timeoutFieldValue = settings.inactivityTimeoutMinutes?.toString() ?: ""
    }
    LaunchedEffect(settings.llmPort) { llmPortValue = settings.llmPort.toString() }
    LaunchedEffect(settings.sqlPort) { sqlPortValue = settings.sqlPort.toString() }

    val dirPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(
                it,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
            viewModel.addScanDirectory(it.toString())
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Settings") }) },
        modifier = modifier,
    ) { innerPadding ->
        LazyColumn(
            contentPadding = innerPadding,
            modifier = Modifier.fillMaxSize(),
        ) {
            item { SettingsSectionHeader("Server Behavior") }

            item {
                SettingsRowSwitch(
                    title = "Run in background",
                    subtitle = "Keep server active when app is minimized",
                    checked = settings.backgroundEnabled,
                    onCheckedChange = viewModel::setBackgroundEnabled,
                )
            }

            item {
                SettingsRowSwitch(
                    title = "Inactivity timeout",
                    subtitle = if (settings.inactivityTimeoutMinutes != null)
                        "Stop server after ${settings.inactivityTimeoutMinutes} min of no requests"
                    else
                        "Never stop automatically",
                    checked = settings.inactivityTimeoutMinutes != null,
                    onCheckedChange = { enabled ->
                        if (enabled) viewModel.setInactivityTimeout(60)
                        else viewModel.setInactivityTimeout(null)
                    },
                )
            }

            if (settings.inactivityTimeoutMinutes != null) {
                item {
                    OutlinedTextField(
                        value = timeoutFieldValue,
                        onValueChange = { value ->
                            timeoutFieldValue = value
                            value.toIntOrNull()?.takeIf { it > 0 }
                                ?.let(viewModel::setInactivityTimeout)
                        },
                        label = { Text("Timeout (minutes)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }

            item { SettingsSectionHeader("Network") }

            item {
                OutlinedTextField(
                    value = llmPortValue,
                    onValueChange = { value ->
                        llmPortValue = value
                        value.toIntOrNull()?.takeIf { it in 1..65535 }
                            ?.let(viewModel::setLlmPort)
                    },
                    label = { Text("LLM server port") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            item {
                OutlinedTextField(
                    value = sqlPortValue,
                    onValueChange = { value ->
                        sqlPortValue = value
                        value.toIntOrNull()?.takeIf { it in 1..65535 }
                            ?.let(viewModel::setSqlPort)
                    },
                    label = { Text("SQL server port") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            item { SettingsSectionHeader("Model Directories") }

            items(settings.scanDirectories.toList()) { uriString ->
                ListItem(
                    headlineContent = {
                        Text(Uri.parse(uriString).lastPathSegment ?: uriString)
                    },
                    supportingContent = {
                        Text(
                            uriString,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    trailingContent = {
                        IconButton(onClick = { viewModel.removeScanDirectory(uriString) }) {
                            Icon(Icons.Rounded.Delete, contentDescription = "Remove directory")
                        }
                    },
                )
            }

            item {
                FilledTonalButton(
                    onClick = { dirPickerLauncher.launch(null) },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Add directory")
                }
            }
        }
    }
}

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun SettingsRowSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = {
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        },
    )
}
