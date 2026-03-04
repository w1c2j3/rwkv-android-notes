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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
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
                val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    if (uri != null) viewModel.runFromUri(uri)
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
                                onNavigate = viewModel::navigate,
                            )
                        }
                    },
                ) { innerPadding ->
                    when (state.currentScreen) {
                        AppScreen.HOME -> HomeScreen(
                            modifier = Modifier.padding(innerPadding),
                            state = state,
                            onInputChanged = viewModel::onInputChanged,
                            onRun = viewModel::runRestructure,
                            onPickFile = { filePicker.launch(arrayOf("*/*")) },
                        )
                        AppScreen.STREAM -> StreamScreen(
                            modifier = Modifier.padding(innerPadding),
                            state = state,
                            onStop = viewModel::stopInference,
                        )
                        AppScreen.RESULT -> ResultScreen(
                            modifier = Modifier.padding(innerPadding),
                            markdown = state.lastMarkdown,
                            tags = state.lastTags,
                            onClose = { viewModel.navigate(AppScreen.HOME) },
                        )
                        AppScreen.HISTORY -> HistoryScreen(
                            modifier = Modifier.padding(innerPadding),
                            state = state,
                        )
                        AppScreen.MODEL -> ModelScreen(
                            modifier = Modifier.padding(innerPadding),
                            state = state,
                            onWarmup = viewModel::warmupModel,
                            onRefreshModels = viewModel::refreshModels,
                            onSwitchModel = viewModel::switchModel,
                        )
                        AppScreen.SETTINGS -> SettingsScreen(
                            modifier = Modifier.padding(innerPadding),
                        )
                    }
                }
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
            Icon(Icons.Default.History, contentDescription = "history", tint = if (current == AppScreen.HISTORY) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
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
        if (state.history.isNotEmpty()) {
            item {
                Card(shape = RoundedCornerShape(20.dp)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("最近处理", style = MaterialTheme.typography.titleSmall)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(state.history.first().markdown.take(120))
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
            Button(onClick = {}, modifier = Modifier.weight(1f), enabled = state.streamingText.isNotBlank()) { Text("复制") }
        }
    }
}

@Composable
private fun ResultScreen(
    modifier: Modifier,
    markdown: String,
    tags: List<String>,
    onClose: () -> Unit,
) {
    Column(modifier = modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("完成结果", style = MaterialTheme.typography.titleSmall)
            Button(onClick = onClose) { Text("关闭") }
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
) {
    LazyColumn(modifier = modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(state.history) { item ->
            Card(shape = RoundedCornerShape(16.dp)) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(item.markdown.take(140))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        item.tags.forEach { AssistChip(onClick = {}, label = { Text(it) }) }
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
) {
    Column(modifier = modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(shape = RoundedCornerShape(16.dp)) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Active Path: ${state.activeModelPath}", style = MaterialTheme.typography.bodySmall)
                Text("mmap readable: ${state.mmapReadable}")
                Text("warmup success: ${state.lastWarmupSuccess}")
                if (state.modelActionMessage != null) {
                    Text(state.modelActionMessage, style = MaterialTheme.typography.labelSmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onWarmup) { Text("预热模型") }
                    Button(onClick = onRefreshModels) { Text("刷新列表") }
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
private fun SettingsScreen(modifier: Modifier) {
    Column(modifier = modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Inference", style = MaterialTheme.typography.titleSmall)
                Text("Max tokens / Temperature / TopP 在配置页维护")
                Text("Prompt 与 glossary 采用 TOML 配置")
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

