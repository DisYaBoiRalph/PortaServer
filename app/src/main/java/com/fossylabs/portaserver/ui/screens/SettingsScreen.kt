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
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
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
    val isClearingModelCache by viewModel.isClearingModelCache.collectAsStateWithLifecycle()
    val clearModelCacheMessage by viewModel.clearModelCacheMessage.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var timeoutFieldValue by remember { mutableStateOf(settings.inactivityTimeoutMinutes?.toString() ?: "") }
    var llmPortValue by remember { mutableStateOf(settings.llmPort.toString()) }
    var sqlPortValue by remember { mutableStateOf(settings.sqlPort.toString()) }
    var hfTokenValue by remember { mutableStateOf(settings.hfToken.orEmpty()) }
    var hfTokenVisible by remember { mutableStateOf(false) }
    var nCtxValue by remember { mutableStateOf(settings.nCtx.toString()) }
    var nThreadsValue by remember { mutableStateOf(settings.nThreads.toString()) }
    var temperatureValue by remember { mutableStateOf(settings.temperature.toString()) }
    var topPValue by remember { mutableStateOf(settings.topP.toString()) }
    var maxTokensValue by remember { mutableStateOf(settings.maxTokens.toString()) }

    LaunchedEffect(settings.inactivityTimeoutMinutes) {
        timeoutFieldValue = settings.inactivityTimeoutMinutes?.toString() ?: ""
    }
    // Only resync when the stored value genuinely differs, so the field is populated
    // once DataStore loads without snapping the cursor while the user is typing.
    LaunchedEffect(settings.hfToken) {
        if (settings.hfToken.orEmpty() != hfTokenValue.trim()) {
            hfTokenValue = settings.hfToken.orEmpty()
        }
    }
    LaunchedEffect(settings.nCtx) { nCtxValue = settings.nCtx.toString() }
    LaunchedEffect(settings.nThreads) { nThreadsValue = settings.nThreads.toString() }
    LaunchedEffect(settings.temperature) { temperatureValue = settings.temperature.toString() }
    LaunchedEffect(settings.topP) { topPValue = settings.topP.toString() }
    LaunchedEffect(settings.maxTokens) { maxTokensValue = settings.maxTokens.toString() }
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

            item { SettingsSectionHeader("Inference") }

            item {
                Text(
                    "Context and threads apply the next time a model is loaded. " +
                        "Temperature, top-p, and max tokens are defaults that a request can override.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            item {
                NumberSettingField(
                    value = nCtxValue,
                    onValueChange = { nCtxValue = it },
                    onCommit = { it.toIntOrNull()?.takeIf { v -> v in 128..131072 }?.let(viewModel::setNCtx) },
                    label = "Context length (tokens)",
                )
            }

            item {
                NumberSettingField(
                    value = nThreadsValue,
                    onValueChange = { nThreadsValue = it },
                    onCommit = { it.toIntOrNull()?.takeIf { v -> v in 0..64 }?.let(viewModel::setNThreads) },
                    label = "Threads (0 = auto)",
                )
            }

            item {
                NumberSettingField(
                    value = maxTokensValue,
                    onValueChange = { maxTokensValue = it },
                    onCommit = { it.toIntOrNull()?.takeIf { v -> v in 1..32768 }?.let(viewModel::setMaxTokens) },
                    label = "Default max tokens",
                )
            }

            item {
                NumberSettingField(
                    value = temperatureValue,
                    onValueChange = { temperatureValue = it },
                    onCommit = { it.toFloatOrNull()?.takeIf { v -> v in 0f..2f }?.let(viewModel::setTemperature) },
                    label = "Default temperature",
                    decimal = true,
                )
            }

            item {
                NumberSettingField(
                    value = topPValue,
                    onValueChange = { topPValue = it },
                    onCommit = { it.toFloatOrNull()?.takeIf { v -> v in 0f..1f }?.let(viewModel::setTopP) },
                    label = "Default top-p",
                    decimal = true,
                )
            }

            item { SettingsSectionHeader("HuggingFace") }

            item {
                OutlinedTextField(
                    value = hfTokenValue,
                    onValueChange = { value ->
                        hfTokenValue = value
                        viewModel.setHfToken(value)
                    },
                    label = { Text("Access token") },
                    supportingText = {
                        Text("Needed for gated models such as Llama and Gemma. Create one at huggingface.co/settings/tokens")
                    },
                    visualTransformation = if (hfTokenVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        IconButton(onClick = { hfTokenVisible = !hfTokenVisible }) {
                            Icon(
                                if (hfTokenVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                contentDescription = if (hfTokenVisible) "Hide token" else "Show token",
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
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

            item { SettingsSectionHeader("Storage") }

            item {
                Text(
                    text = "Model cache stores temporary copies used for SAF-backed model loading.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            item {
                FilledTonalButton(
                    onClick = viewModel::clearModelCache,
                    enabled = !isClearingModelCache,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Icon(Icons.Rounded.Delete, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (isClearingModelCache) "Clearing model cache..." else "Clear model cache")
                }
            }

            clearModelCacheMessage?.let { message ->
                item {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

/** Numeric settings field that only commits values passing [onCommit]'s own range check. */
@Composable
private fun NumberSettingField(
    value: String,
    onValueChange: (String) -> Unit,
    onCommit: (String) -> Unit,
    label: String,
    decimal: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = {
            onValueChange(it)
            onCommit(it)
        },
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(
            keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number,
        ),
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    )
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
