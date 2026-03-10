package com.example.rwkvnotes

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.rwkvnotes.note.AppScreen
import com.example.rwkvnotes.note.NoteViewModel
import dagger.hilt.android.AndroidEntryPoint
import io.noties.markwon.Markwon

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val viewModel = hiltViewModel<NoteViewModel>()
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                val clipboardManager = LocalClipboardManager.current
                val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    if (uri != null) viewModel.runFromUri(uri)
                }
                val modelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    if (uri != null) viewModel.importModelFromUri(uri)
                }
                val copyToClipboard: (String, String) -> Unit = { text, label ->
                    if (text.isBlank()) {
                        viewModel.showError("$label 为空，无法复制")
                    } else {
                        clipboardManager.setText(AnnotatedString(text))
                        viewModel.showNotice("$label 已复制")
                    }
                }
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        CenterAlignedTopAppBar(
                            title = {
                                Text(
                                    when (state.currentScreen) {
                                        AppScreen.HOME -> "工作台"
                                        AppScreen.STREAM -> "模型推断中"
                                        AppScreen.RESULT -> "结构化结果"
                                        AppScreen.HISTORY -> "历史记录"
                                        AppScreen.MODEL -> "模型管理"
                                        AppScreen.SETTINGS -> "系统参数"
                                    },
                                )
                            },
                        )
                    },
                    bottomBar = {
                        if (state.currentScreen in listOf(AppScreen.HOME, AppScreen.HISTORY, AppScreen.MODEL, AppScreen.SETTINGS)) {
                            BottomNavBar(
                                current = state.currentScreen,
                                onNavigate = { viewModel.navigate(it) },
                            )
                        }
                    },
                ) { innerPadding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val bannerMessage = state.errorMessage ?: state.noticeMessage
                        if (bannerMessage != null) {
                            UiMessageBanner(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                message = bannerMessage,
                                isError = state.errorMessage != null,
                                onDismiss = if (state.errorMessage != null) {
                                    { viewModel.clearErrorMessage() }
                                } else {
                                    { viewModel.clearNoticeMessage() }
                                },
                            )
                        }
                        when (state.currentScreen) {
                            AppScreen.HOME -> HomeScreen(
                                modifier = Modifier.weight(1f),
                                state = state,
                                onInputChanged = { viewModel.onInputChanged(it) },
                                onRun = { viewModel.runRestructure() },
                                onPickFile = { filePicker.launch(arrayOf("*/*")) },
                            )
                            AppScreen.STREAM -> StreamScreen(
                                modifier = Modifier.weight(1f),
                                state = state,
                                onStop = { viewModel.stopInference() },
                                onCopy = { copyToClipboard(state.streamingText, "流式文本") },
                            )
                            AppScreen.RESULT -> ResultScreen(
                                modifier = Modifier.weight(1f),
                                markdown = state.lastMarkdown,
                                tags = state.lastTags,
                                onClose = { viewModel.navigate(AppScreen.HOME) },
                                onCopy = { copyToClipboard(state.lastMarkdown, "Markdown") },
                            )
                            AppScreen.HISTORY -> HistoryScreen(
                                modifier = Modifier.weight(1f),
                                state = state,
                                onQueryChanged = { viewModel.onHistoryQueryChanged(it) },
                                onApplyFilters = { viewModel.applyHistoryFilters() },
                                onClearFilters = { viewModel.clearHistoryFilters() },
                                onSelectTag = { viewModel.selectHistoryTag(it) },
                                onPreviousPage = { viewModel.loadPreviousHistoryPage() },
                                onNextPage = { viewModel.loadNextHistoryPage() },
                                onCopyMarkdown = { copyToClipboard(it, "历史 Markdown") },
                            )
                            AppScreen.MODEL -> ModelScreen(
                                modifier = Modifier.weight(1f),
                                state = state,
                                onWarmup = { viewModel.warmupModel() },
                                onRefreshModels = { viewModel.refreshModels() },
                                onSwitchModel = { viewModel.switchModel(it) },
                                onImport = { modelPicker.launch(arrayOf("*/*")) },
                                onDownload = { viewModel.startModelDownload() },
                            )
                            AppScreen.SETTINGS -> SettingsScreen(
                                modifier = Modifier.weight(1f),
                                state = state,
                                onMaxTokensChanged = { viewModel.onSettingsMaxTokensChanged(it) },
                                onTemperatureChanged = { viewModel.onSettingsTemperatureChanged(it) },
                                onTopPChanged = { viewModel.onSettingsTopPChanged(it) },
                                onSave = { viewModel.saveInferenceSettings() },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UiMessageBanner(
    modifier: Modifier = Modifier,
    message: String,
    isError: Boolean,
    onDismiss: () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isError) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.tertiaryContainer
            },
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = message,
                modifier = Modifier.weight(1f),
                color = if (isError) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.onTertiaryContainer
                },
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        }
    }
}

@Composable
private fun RunningBanner() {
    val transition = rememberInfiniteTransition(label = "ai_running")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "ai_progress",
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(
                brush = Brush.linearGradient(
                    listOf(
                        Color(0xFF8E5EFF),
                        Color(0xFF6A00F4),
                        Color(0xFF8E5EFF),
                    ),
                ),
            )
            .padding(12.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "AI is processing locally",
                color = Color.White,
                style = MaterialTheme.typography.titleSmall,
            )
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth(),
                color = Color.White,
                trackColor = Color.White.copy(alpha = 0.25f),
            )
        }
    }
}

@Composable
private fun BottomNavBar(current: AppScreen, onNavigate: (AppScreen) -> Unit) {
    BottomAppBar {
        IconButton(onClick = { onNavigate(AppScreen.HOME) }) {
            Icon(Icons.Default.Edit, contentDescription = "home", tint = if (current == AppScreen.HOME) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = { onNavigate(AppScreen.HISTORY) }) {
            Text(
                text = "历",
                color = if (current == AppScreen.HISTORY) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        IconButton(onClick = { onNavigate(AppScreen.MODEL) }) {
            Icon(Icons.Default.Build, contentDescription = "model", tint = if (current == AppScreen.MODEL) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = { onNavigate(AppScreen.SETTINGS) }) {
            Icon(Icons.Default.Settings, contentDescription = "settings", tint = if (current == AppScreen.SETTINGS) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun HomeScreen(
    modifier: Modifier,
    state: com.example.rwkvnotes.note.NoteUiState,
    onInputChanged: (String) -> Unit,
    onRun: () -> Unit,
    onPickFile: () -> Unit,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card(shape = RoundedCornerShape(20.dp)) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = state.inputText,
                        onValueChange = onInputChanged,
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 6,
                        placeholder = { Text("粘贴你需要整理的笔记、代码或语录...") },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(onClick = onRun, enabled = !state.isRunning && state.inputText.isNotBlank(), modifier = Modifier.weight(1f)) {
                            Text("执行结构化")
                        }
                        Button(onClick = onPickFile, enabled = !state.isRunning, modifier = Modifier.weight(1f)) {
                            Text("上传文件")
                        }
                    }
                }
            }
        }
        if (state.recentHistory.isNotEmpty()) {
            item {
                Card(shape = RoundedCornerShape(20.dp)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("最近处理", style = MaterialTheme.typography.titleSmall)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(state.recentHistory.first().markdown.take(120))
                    }
                }
            }
        }
    }
}

@Composable
private fun StreamScreen(
    modifier: Modifier,
    state: com.example.rwkvnotes.note.NoteUiState,
    onStop: () -> Unit,
    onCopy: () -> Unit,
) {
    Column(modifier = modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        RunningBanner()
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("TTFT: ${state.metrics.ttftMs}ms", style = MaterialTheme.typography.labelSmall)
            Text("Speed: ${"%.1f".format(state.metrics.tokensPerSecond)} t/s", style = MaterialTheme.typography.labelSmall)
        }
        Card(modifier = Modifier.fillMaxWidth().weight(1f), colors = CardDefaults.cardColors(containerColor = Color(0xFF111827))) {
            Text(
                text = state.streamingText,
                color = Color(0xFFF3F4F6),
                modifier = Modifier.padding(12.dp),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = onStop, modifier = Modifier.weight(1f)) { Text("停止") }
            Button(onClick = onCopy, modifier = Modifier.weight(1f), enabled = state.streamingText.isNotBlank()) { Text("复制") }
        }
    }
}

@Composable
private fun ResultScreen(
    modifier: Modifier,
    markdown: String,
    tags: List<String>,
    onClose: () -> Unit,
    onCopy: () -> Unit,
) {
    Column(modifier = modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("完成结果", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onCopy, enabled = markdown.isNotBlank()) { Text("复制") }
                Button(onClick = onClose) { Text("关闭") }
            }
        }
        MarkdownResultCard(markdown = markdown, modifier = Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            tags.forEach { AssistChip(onClick = {}, label = { Text(it) }) }
        }
    }
}

@Composable
private fun HistoryScreen(
    modifier: Modifier,
    state: com.example.rwkvnotes.note.NoteUiState,
    onQueryChanged: (String) -> Unit,
    onApplyFilters: () -> Unit,
    onClearFilters: () -> Unit,
    onSelectTag: (String?) -> Unit,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    onCopyMarkdown: (String) -> Unit,
) {
    LazyColumn(modifier = modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Card(shape = RoundedCornerShape(16.dp)) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = state.historyQueryInput,
                        onValueChange = onQueryChanged,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("搜索历史") },
                        placeholder = { Text("按原文或 Markdown 搜索") },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(onClick = onApplyFilters, modifier = Modifier.weight(1f)) { Text("搜索") }
                        Button(onClick = onClearFilters, modifier = Modifier.weight(1f)) { Text("清空筛选") }
                    }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item {
                            AssistChip(
                                onClick = { onSelectTag(null) },
                                label = { Text(if (state.historySelectedTag == null) "全部" else "全部标签") },
                            )
                        }
                        items(state.historyAvailableTags) { tag ->
                            AssistChip(
                                onClick = { onSelectTag(tag) },
                                label = {
                                    Text(if (state.historySelectedTag == tag) "[$tag]" else tag)
                                },
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("第 ${state.historyPageIndex + 1} 页", style = MaterialTheme.typography.labelSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = onPreviousPage, enabled = state.historyPageIndex > 0) { Text("上一页") }
                            Button(onClick = onNextPage, enabled = state.historyHasNextPage) { Text("下一页") }
                        }
                    }
                    if (state.historyLoading) {
                        Text("历史记录加载中...", style = MaterialTheme.typography.labelSmall)
                    }
                    if (state.historyErrorMessage != null) {
                        Text(
                            state.historyErrorMessage,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (!state.historyLoading && state.history.isEmpty()) {
                        Text("当前筛选条件下没有记录", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        items(state.history) { item ->
            Card(shape = RoundedCornerShape(16.dp)) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(item.markdown.take(140))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = { onCopyMarkdown(item.markdown) }) { Text("复制") }
                        Text("#${item.id}", style = MaterialTheme.typography.labelSmall)
                    }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(item.tags) { tag ->
                            AssistChip(onClick = { onSelectTag(tag) }, label = { Text(tag) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelScreen(
    modifier: Modifier,
    state: com.example.rwkvnotes.note.NoteUiState,
    onWarmup: () -> Unit,
    onRefreshModels: () -> Unit,
    onSwitchModel: (String) -> Unit,
    onImport: () -> Unit,
    onDownload: () -> Unit,
) {
    Column(modifier = modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(shape = RoundedCornerShape(16.dp)) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Active Path: ${state.activeModelPath}", style = MaterialTheme.typography.bodySmall)
                Text("runtime format: ${state.requiredRuntimeExtension}")
                Text("mmap readable: ${state.mmapReadable}")
                Text("warmup success: ${state.lastWarmupSuccess}")
                if (state.runtimeErrorMessage != null) {
                    Text(state.runtimeErrorMessage, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
                if (state.modelActionMessage != null) {
                    Text(state.modelActionMessage, style = MaterialTheme.typography.labelSmall)
                }
                if (state.downloadState != null) {
                    Text("download state: ${state.downloadState}", style = MaterialTheme.typography.labelSmall)
                }
                if (state.downloadProgressText != null) {
                    Text("download: ${state.downloadProgressText}", style = MaterialTheme.typography.labelSmall)
                }
                if (state.downloadSpeedText != null) {
                    Text("speed: ${state.downloadSpeedText}", style = MaterialTheme.typography.labelSmall)
                }
                if (state.downloadEtaText != null) {
                    Text("eta: ${state.downloadEtaText}", style = MaterialTheme.typography.labelSmall)
                }
                if (state.downloadErrorCode != null) {
                    Text("download error: ${state.downloadErrorCode}", style = MaterialTheme.typography.labelSmall)
                }
                if (state.downloadErrorText != null) {
                    Text(state.downloadErrorText, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { Button(onClick = onWarmup) { Text("预热模型") } }
                    item { Button(onClick = onRefreshModels) { Text("刷新列表") } }
                    item { Button(onClick = onImport) { Text("导入模型") } }
                    item { Button(onClick = onDownload) { Text("下载模型") } }
                }
            }
        }
        if (state.models.isEmpty()) {
            Card(shape = RoundedCornerShape(16.dp)) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("还没有本地模型", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "先导入一个 ${state.requiredRuntimeExtension} runtime 模型文件；如果后续补齐 TOML 里的下载 URL 和 SHA256，也可以直接用下载模型。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "导入后应用会自动切换并尝试预热当前模型。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(state.models) { _, model ->
                Card(shape = RoundedCornerShape(12.dp)) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(model.name, style = MaterialTheme.typography.titleSmall)
                        Text(model.path, style = MaterialTheme.typography.bodySmall)
                        Text("size: ${model.sizeBytes}", style = MaterialTheme.typography.labelSmall)
                        if (!model.isActive) {
                            Button(onClick = { onSwitchModel(model.path) }) { Text("切换") }
                        } else {
                            Text("当前模型", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    modifier: Modifier,
    state: com.example.rwkvnotes.note.NoteUiState,
    onMaxTokensChanged: (String) -> Unit,
    onTemperatureChanged: (String) -> Unit,
    onTopPChanged: (String) -> Unit,
    onSave: () -> Unit,
) {
    Column(modifier = modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Inference", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(
                    value = state.settingsMaxTokensInput,
                    onValueChange = onMaxTokensChanged,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Max tokens") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(
                    value = state.settingsTemperatureInput,
                    onValueChange = onTemperatureChanged,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Temperature") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                OutlinedTextField(
                    value = state.settingsTopPInput,
                    onValueChange = onTopPChanged,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Top P") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                Text("Prompt 与 glossary 仍采用 TOML 配置")
                if (state.settingsMessage != null) {
                    Text(
                        state.settingsMessage,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (state.settingsMessage == "settings saved") {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                }
                Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) {
                    Text("保存推理参数")
                }
            }
        }
    }
}

@Composable
private fun MarkdownResultCard(markdown: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val markwon = remember(context) { Markwon.create(context) }
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Markdown", style = MaterialTheme.typography.titleMedium)
            AndroidView(
                modifier = Modifier.fillMaxWidth(),
                factory = { ctx -> android.widget.TextView(ctx) },
                update = { view -> markwon.setMarkdown(view, markdown) },
            )
        }
    }
}

