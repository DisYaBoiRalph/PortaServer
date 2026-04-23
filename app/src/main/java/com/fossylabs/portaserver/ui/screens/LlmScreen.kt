package com.fossylabs.portaserver.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarOutline
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fossylabs.portaserver.llm.HuggingFaceFileDto
import com.fossylabs.portaserver.llm.ModelInfo
import com.fossylabs.portaserver.llm.ModelRecommender
import com.fossylabs.portaserver.llm.ModelTier
import com.fossylabs.portaserver.server.ServerState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LlmScreen(
    viewModel: LlmViewModel = viewModel(),
    modifier: Modifier = Modifier,
) {
    val serverState by viewModel.serverState.collectAsStateWithLifecycle()
    val loadedModel by viewModel.loadedModel.collectAsStateWithLifecycle()
    val isLoadingModel by viewModel.isLoadingModel.collectAsStateWithLifecycle()
    val deviceSpecs by viewModel.deviceSpecs.collectAsStateWithLifecycle()
    val modelTier by viewModel.modelTier.collectAsStateWithLifecycle()
    val localModels by viewModel.localModels.collectAsStateWithLifecycle()
    val hfModels by viewModel.hfModels.collectAsStateWithLifecycle()
    val isFetchingHf by viewModel.isFetchingHf.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    val selectedHfModel by viewModel.selectedHfModel.collectAsStateWithLifecycle()
    val hfModelFiles by viewModel.hfModelFiles.collectAsStateWithLifecycle()
    val isFetchingFiles by viewModel.isFetchingFiles.collectAsStateWithLifecycle()
    val downloadStates by viewModel.downloadStates.collectAsStateWithLifecycle()
    val localIp by viewModel.localIp.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    // Holds a pending (modelId, fileName) to resume after directory pick
    var pendingDownload by remember { mutableStateOf<Pair<String, String>?>(null) }

    val dirPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            viewModel.setDownloadDirectory(uri.toString())
            pendingDownload?.let { (modelId, fileName) ->
                viewModel.downloadFile(modelId, fileName)
                pendingDownload = null
            }
        } else {
            pendingDownload = null
        }
    }

    // Show snackbar with directory info when a download begins
    LaunchedEffect(downloadStates) {
        val starting = downloadStates.entries.firstOrNull { !it.value.done }
        val currentDownloadDir = settings.downloadDirectory
        if (starting != null && currentDownloadDir != null) {
            val displayPath = safUriToDisplayPath(currentDownloadDir)
            val result = snackbarHostState.showSnackbar(
                message = "Downloading to: $displayPath",
                actionLabel = "+ Dir",
            )
            if (result == SnackbarResult.ActionPerformed) {
                dirPickerLauncher.launch(null)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("LLM Server") },
                actions = {
                    IconButton(onClick = {
                        viewModel.refreshLocalModels()
                        viewModel.refreshDeviceSpecs()
                    }) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Refresh")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = modifier,
    ) { innerPadding ->
        LazyColumn(
            contentPadding = innerPadding,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        ) {

            // ── Device info card ────────────────────────────────────────────
            item {
                Spacer(Modifier.height(4.dp))
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        deviceSpecs?.let { specs ->
                            Text(
                                text = buildString {
                                    append("RAM: ${"%.1f".format(specs.totalRamGb)} GB")
                                    specs.socModel?.let { append("  ·  SoC: $it") }
                                    append("  ·  Cores: ${specs.cpuCores}")
                                },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        modelTier?.let { tier ->
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "Recommended: ${tier.description}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                }
            }

            // ── Server control ──────────────────────────────────────────────
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (serverState == ServerState.RUNNING)
                                    Icons.Rounded.CheckCircle else Icons.Rounded.Refresh,
                                contentDescription = null,
                                tint = if (serverState == ServerState.RUNNING)
                                    MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outline,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = if (serverState == ServerState.RUNNING)
                                    "Running on :${settings.llmPort}" else "Stopped",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        if (serverState == ServerState.RUNNING) {
                            localIp?.let { ip ->
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = "http://$ip:${settings.llmPort}",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }

                        loadedModel?.let {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "Model: ${it.name}",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        }

                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = viewModel::startServer,
                                enabled = serverState == ServerState.STOPPED && loadedModel != null,
                                modifier = Modifier.weight(1f),
                            ) { Text("Start") }
                            OutlinedButton(
                                onClick = viewModel::stopServer,
                                enabled = serverState == ServerState.RUNNING,
                                modifier = Modifier.weight(1f),
                            ) { Text("Stop") }
                        }

                        if (loadedModel == null) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "Load a model below to start the server",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }

                        errorMessage?.let { msg ->
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = msg,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }

            // ── Local models section ────────────────────────────────────────
            item {
                Spacer(Modifier.height(8.dp))
                SectionHeader("Local Models")
                HorizontalDivider()
            }

            // Show any in-progress downloads that don't yet have a local model entry
            val inProgress = downloadStates.filter { (name, state) ->
                !state.done && localModels.none { it.name == name }
            }.toList()

            if (inProgress.isNotEmpty()) {
                items(inProgress) { entry ->
                    val (fileName, state) = entry
                    DownloadingModelCard(fileName, state)
                }
            }

            if (localModels.isEmpty()) {
                item {
                    Text(
                        text = "No .gguf files found. Add directories in Settings.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            }

            items(localModels, key = { it.path }) { model ->
                val isLoaded = loadedModel?.path == model.path
                val dismissState = rememberSwipeToDismissBoxState(
                    confirmValueChange = { value ->
                        if (value == SwipeToDismissBoxValue.EndToStart && !isLoaded) {
                            viewModel.deleteLocalModel(model.path)
                            true
                        } else false
                    }
                )
                SwipeToDismissBox(
                    state = dismissState,
                    enableDismissFromStartToEnd = false,
                    backgroundContent = {
                        if (!isLoaded) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(CardDefaults.shape)
                                    .background(MaterialTheme.colorScheme.error),
                                contentAlignment = Alignment.CenterEnd,
                            ) {
                                Icon(
                                    Icons.Rounded.Delete,
                                    contentDescription = "Delete",
                                    tint = MaterialTheme.colorScheme.onError,
                                    modifier = Modifier.padding(end = 20.dp),
                                )
                            }
                        }
                    },
                ) {
                    ModelCard(
                        model = model,
                        isLoaded = isLoaded,
                        isLoading = isLoadingModel,
                        downloadState = downloadStates[model.name],
                        onLoad = { viewModel.loadModel(model.path) },
                        onUnload = viewModel::unloadModel,
                    )
                }
            }

            // ── HuggingFace discover section ────────────────────────────────
            item {
                Spacer(Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    SectionHeader("Discover on HuggingFace")
                    FilledTonalButton(
                        onClick = viewModel::fetchHuggingFaceModels,
                        enabled = !isFetchingHf,
                    ) {
                        if (isFetchingHf) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text("Fetch")
                    }
                }
                HorizontalDivider()
            }

            items(hfModels) { model ->
                HfModelCard(
                    model = model,
                    tier = modelTier,
                    onClick = { viewModel.selectHfModel(model) },
                )
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }

    // ── Model detail bottom sheet ───────────────────────────────────────────
    if (selectedHfModel != null) {
        ModelDetailSheet(
            model = selectedHfModel!!,
            files = hfModelFiles,
            isFetchingFiles = isFetchingFiles,
            downloadDirectory = settings.downloadDirectory,
            downloadStates = downloadStates,
            loadedModelPath = loadedModel?.path,
            isLoadingModel = isLoadingModel,
            onDismiss = { viewModel.selectHfModel(null) },
            onPickDirectory = { dirPickerLauncher.launch(null) },
            onDownload = { fileName ->
                val modelId = selectedHfModel!!.name
                if (settings.downloadDirectory == null) {
                    pendingDownload = modelId to fileName
                    dirPickerLauncher.launch(null)
                } else {
                    viewModel.downloadFile(modelId, fileName)
                }
            },
            onAddDirectory = { dirPickerLauncher.launch(null) },
            onLoad = { fileUri -> viewModel.loadModel(fileUri) },
            onUnload = viewModel::unloadModel,
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 4.dp),
    )
}

@Composable
private fun ModelCard(
    model: ModelInfo,
    isLoaded: Boolean,
    isLoading: Boolean,
    downloadState: DownloadState? = null,
    onLoad: () -> Unit,
    onUnload: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(model.name, style = MaterialTheme.typography.bodyMedium)
                    if (model.isRecommended) {
                        Spacer(Modifier.height(4.dp))
                        SuggestionChip(
                            onClick = {},
                            label = { Text("Recommended", style = MaterialTheme.typography.labelSmall) },
                            icon = { Icon(Icons.Rounded.Star, null, Modifier.size(14.dp)) },
                        )
                    }
                    if (model.isCorrupted) {
                        Spacer(Modifier.height(4.dp))
                        SuggestionChip(
                            onClick = {},
                            enabled = false,
                            label = { Text("Corrupted", style = MaterialTheme.typography.labelSmall) },
                            icon = { Icon(Icons.Rounded.Warning, null, Modifier.size(14.dp)) },
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                when {
                    model.isCorrupted -> FilledTonalButton(onClick = {}, enabled = false) { Text("Load") }
                    isLoading && !isLoaded -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    isLoaded -> OutlinedButton(onClick = onUnload) { Text("Unload") }
                    else -> FilledTonalButton(onClick = onLoad) { Text("Load") }
                }
            }

            // Show active download progress for this model if present
            downloadState?.let { st ->
                if (!st.done) {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(progress = { st.progress }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(4.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        val dl = st.downloadedBytes
                        val tot = st.totalBytes
                        Text(
                            text = if (tot != null) "${formatFileSize(dl)} / ${formatFileSize(tot)}" else formatFileSize(dl),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        st.speedBytesPerSec?.let { speed ->
                            Text(
                                text = "${formatFileSize(speed)}/s",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadingModelCard(fileName: String, state: DownloadState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(fileName, style = MaterialTheme.typography.bodyMedium)
                    Text("Downloading…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
                Spacer(Modifier.width(8.dp))
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(4.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                val dl = state.downloadedBytes
                val tot = state.totalBytes
                Text(
                    text = if (tot != null) "${formatFileSize(dl)} / ${formatFileSize(tot)}" else formatFileSize(dl),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                state.speedBytesPerSec?.let { speed ->
                    Text(
                        text = "${formatFileSize(speed)}/s",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

@Composable
private fun HfModelCard(model: ModelInfo, tier: ModelTier?, onClick: () -> Unit) {
    val stars = model.downloads?.let { ModelRecommender.popularityStars(it) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(model.name, style = MaterialTheme.typography.bodyMedium)

            // ── Popularity row ──────────────────────────────────────────────
            if (stars != null || model.likes != null) {
                Spacer(Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    if (stars != null) {
                        repeat(stars) {
                            Icon(
                                Icons.Rounded.Star, null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                        repeat(5 - stars) {
                            Icon(
                                Icons.Rounded.StarOutline, null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.outlineVariant,
                            )
                        }
                        Spacer(Modifier.width(4.dp))
                        model.downloads?.let { dl ->
                            Text(
                                text = formatDownloads(dl) + " downloads",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                    model.likes?.let { lk ->
                        if (lk > 0) {
                            Text(
                                text = "  ·  ${formatDownloads(lk)} likes",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                }
            }

            // ── Chip row ────────────────────────────────────────────────────
            val showChips = model.isRecommended || model.pipelineTag != null
            if (showChips) {
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    model.pipelineTag?.let { tag ->
                        SuggestionChip(
                            onClick = {},
                            label = { Text(formatPipelineTag(tag), style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                    if (model.isRecommended) {
                        SuggestionChip(
                            onClick = {},
                            label = { Text("Recommended", style = MaterialTheme.typography.labelSmall) },
                            icon = { Icon(Icons.Rounded.Star, null, Modifier.size(14.dp)) },
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = model.path,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1,
            )
        }
    }
}

private fun formatDownloads(n: Int): String = when {
    n >= 1_000_000 -> "${"%.1f".format(n / 1_000_000f)}M"
    n >= 1_000     -> "${"%.0f".format(n / 1_000f)}k"
    else           -> "$n"
}

private fun formatPipelineTag(tag: String): String =
    tag.split('-', '_').joinToString(" ") { it.replaceFirstChar(Char::uppercaseChar) }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelDetailSheet(
    model: ModelInfo,
    files: List<HuggingFaceFileDto>,
    isFetchingFiles: Boolean,
    downloadDirectory: String?,
    downloadStates: Map<String, DownloadState>,
    loadedModelPath: String?,
    isLoadingModel: Boolean,
    onDismiss: () -> Unit,
    onPickDirectory: () -> Unit,
    onDownload: (fileName: String) -> Unit,
    onAddDirectory: () -> Unit,
    onLoad: (fileUri: String) -> Unit,
    onUnload: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ── Model title ─────────────────────────────────────────────────
            Text(model.name, style = MaterialTheme.typography.titleMedium)

            // ── Popularity ──────────────────────────────────────────────────
            val stars = model.downloads?.let { ModelRecommender.popularityStars(it) }
            if (stars != null || model.likes != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    if (stars != null) {
                        repeat(stars) {
                            Icon(Icons.Rounded.Star, null, Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary)
                        }
                        repeat(5 - stars) {
                            Icon(Icons.Rounded.StarOutline, null, Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.outlineVariant)
                        }
                        Spacer(Modifier.width(4.dp))
                        model.downloads?.let { dl ->
                            Text(
                                text = "${formatDownloads(dl)} downloads",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                    model.likes?.let { lk ->
                        if (lk > 0) {
                            Text(
                                text = "  ·  ${formatDownloads(lk)} likes",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                }
            }

            HorizontalDivider()

            // ── Download directory row ──────────────────────────────────────
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    Icons.Rounded.Folder,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                if (downloadDirectory != null) {
                    Text(
                        text = safUriToDisplayPath(downloadDirectory),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onAddDirectory, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Rounded.Add,
                            contentDescription = "Change download directory",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                } else {
                    Text(
                        text = "No download directory set",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.weight(1f),
                    )
                    FilledTonalButton(onClick = onPickDirectory) {
                        Text("Set directory")
                    }
                }
            }

            HorizontalDivider()

            // ── Files list ──────────────────────────────────────────────────
            Text(
                text = "GGUF Files",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )

            when {
                isFetchingFiles -> {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                }
                files.isEmpty() -> {
                    Text(
                        text = "No GGUF files found for this model.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                else -> {
                    files.forEach { file ->
                        val state = downloadStates[file.rfilename]
                        FileDownloadRow(
                            file = file,
                            downloadState = state,
                            isLoaded = state?.fileUri != null && state.fileUri == loadedModelPath,
                            isLoadingModel = isLoadingModel,
                            onDownload = { onDownload(file.rfilename) },
                            onLoad = { fileUri -> onLoad(fileUri) },
                            onUnload = onUnload,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FileDownloadRow(
    file: HuggingFaceFileDto,
    downloadState: DownloadState?,
    isLoaded: Boolean,
    isLoadingModel: Boolean,
    onDownload: () -> Unit,
    onLoad: (fileUri: String) -> Unit,
    onUnload: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = file.rfilename,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            val displaySize = file.lfs?.size ?: file.size
            displaySize?.let { sz ->
                Text(
                    text = formatFileSize(sz),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.align(Alignment.CenterVertically)
                )
                Spacer(Modifier.width(8.dp))
            }
            when {
                downloadState?.done == true && downloadState.fileUri != null -> {
                    if (isLoaded) {
                        OutlinedButton(onClick = onUnload) { Text("Unload") }
                    } else if (isLoadingModel) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    } else {
                        FilledTonalButton(onClick = { onLoad(downloadState.fileUri) }) { Text("Load") }
                    }
                }
                downloadState?.done == true -> {
                    Icon(
                        Icons.Rounded.CheckCircle,
                        contentDescription = "Downloaded",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp),
                    )
                }
                downloadState != null -> {
                    CircularProgressIndicator(
                        progress = { downloadState.progress },
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                    )
                }
                else -> {
                    IconButton(onClick = onDownload) {
                        Icon(Icons.Rounded.Download, contentDescription = "Download ${file.rfilename}")
                    }
                }
            }
        }
        if (downloadState != null && !downloadState.done) {
            LinearProgressIndicator(
                progress = { downloadState.progress },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                val dl = downloadState.downloadedBytes
                val tot = downloadState.totalBytes
                val sp = downloadState.speedBytesPerSec
                Text(
                    text = if (tot != null) "${formatFileSize(dl)} / ${formatFileSize(tot)}" else formatFileSize(dl),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                sp?.let { speed ->
                    Text(
                        text = "${formatFileSize(speed)}/s",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

/** Extracts a human-readable path from a SAF tree URI.
 *  E.g. "content://...tree/primary%3ADownloads%2FPortaServer" → "Downloads/PortaServer" */
private fun safUriToDisplayPath(uriString: String): String = try {
    val encoded = uriString.substringAfterLast("/tree/")
    val decoded = java.net.URLDecoder.decode(encoded, "UTF-8")
    decoded.substringAfter(":").ifEmpty { decoded }
} catch (_: Exception) {
    uriString.substringAfterLast("/").take(40)
}

private fun formatFileSize(bytes: Long): String = when {
    bytes >= 1_073_741_824L -> "${"%.1f".format(bytes / 1_073_741_824f)} GB"
    bytes >= 1_048_576L     -> "${"%.0f".format(bytes / 1_048_576f)} MB"
    bytes >= 1_024L         -> "${"%.0f".format(bytes / 1_024f)} KB"
    else                    -> "$bytes B"
}

