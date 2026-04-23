package com.fossylabs.portaserver.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fossylabs.portaserver.server.ServerState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SqlScreen(
    viewModel: SqlViewModel = viewModel(),
    modifier: Modifier = Modifier,
) {
    val serverState by viewModel.serverState.collectAsStateWithLifecycle()
    val databases by viewModel.databases.collectAsStateWithLifecycle()
    val selectedDb by viewModel.selectedDb.collectAsStateWithLifecycle()
    val tables by viewModel.tables.collectAsStateWithLifecycle()
    val queryResult by viewModel.queryResult.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var showCreateDialog by remember { mutableStateOf(false) }
    var queryText by remember { mutableStateOf("") }
    var pendingServerStartForNotificationPermission by remember { mutableStateOf(false) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (pendingServerStartForNotificationPermission) {
            pendingServerStartForNotificationPermission = false
            viewModel.startServer()
            if (!granted) {
                Toast.makeText(
                    context,
                    "Hosting started, but notifications are disabled.",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    if (showCreateDialog) {
        CreateDatabaseDialog(
            onConfirm = { name ->
                viewModel.createDatabase(name)
                showCreateDialog = false
            },
            onDismiss = { showCreateDialog = false },
        )
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("SQL Server") }) },
        modifier = modifier,
    ) { innerPadding ->
        LazyColumn(
            contentPadding = innerPadding,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        ) {

            // ── Server control ──────────────────────────────────────────────
            item {
                Spacer(Modifier.height(4.dp))
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            text = if (serverState == ServerState.RUNNING)
                                "Running on :${settings.sqlPort}" else "Server stopped",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    if (needsNotificationPermission(context)) {
                                        pendingServerStartForNotificationPermission = true
                                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    } else {
                                        viewModel.startServer()
                                    }
                                },
                                enabled = serverState == ServerState.STOPPED,
                                modifier = Modifier.weight(1f),
                            ) { Text("Start") }
                            OutlinedButton(
                                onClick = viewModel::stopServer,
                                enabled = serverState == ServerState.RUNNING,
                                modifier = Modifier.weight(1f),
                            ) { Text("Stop") }
                        }
                    }
                }
            }

            // ── Databases ───────────────────────────────────────────────────
            item {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "Databases",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    TextButton(onClick = { showCreateDialog = true }) {
                        Icon(Icons.Rounded.Add, null)
                        Spacer(Modifier.width(4.dp))
                        Text("New")
                    }
                }
                HorizontalDivider()
            }

            if (databases.isEmpty()) {
                item {
                    Text(
                        text = "No databases yet. Create one to get started.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            }

            items(databases) { dbName ->
                ListItem(
                    headlineContent = { Text(dbName, fontFamily = FontFamily.Monospace) },
                    supportingContent = {
                        if (dbName == selectedDb && tables.isNotEmpty()) {
                            Text("Tables: ${tables.joinToString(", ")}")
                        }
                    },
                    colors = if (dbName == selectedDb)
                        ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                    else ListItemDefaults.colors(),
                    modifier = Modifier.clickable { viewModel.selectDatabase(dbName) },
                )
            }

            // ── Query panel ─────────────────────────────────────────────────
            if (selectedDb != null) {
                item {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Query: $selectedDb",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = queryText,
                        onValueChange = { queryText = it },
                        label = { Text("SQL") },
                        placeholder = { Text("SELECT * FROM my_table") },
                        minLines = 3,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { viewModel.executeQuery(queryText) }),
                        textStyle = TextStyle(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { viewModel.executeQuery(queryText) },
                        enabled = queryText.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Execute") }
                }

                queryResult?.let { result ->
                    item {
                        Spacer(Modifier.height(8.dp))
                        QueryResultCard(result)
                    }
                }
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

private fun needsNotificationPermission(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
    return ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.POST_NOTIFICATIONS,
    ) != PackageManager.PERMISSION_GRANTED
}

@Composable
private fun CreateDatabaseDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Database") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.filter { c -> c.isLetterOrDigit() || c == '_' } },
                label = { Text("Database name") },
                singleLine = true,
            )
        },
        confirmButton = {
            Button(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun QueryResultCard(result: com.fossylabs.portaserver.sql.QueryResult) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            when {
                result.error != null -> {
                    Text(
                        text = result.error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                result.rowsAffected != null -> {
                    Text("Rows affected: ${result.rowsAffected}")
                }
                result.rows != null -> {
                    Text(
                        text = "${result.rows.size} row(s)",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    Spacer(Modifier.height(4.dp))
                    result.rows.take(50).forEach { row ->
                        Text(
                            text = row.entries.joinToString(" | ") { "${it.key}=${it.value}" },
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                    if (result.rows.size > 50) {
                        Text(
                            text = "… and ${result.rows.size - 50} more rows",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }
        }
    }
}
