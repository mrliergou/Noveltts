package app.noveltts.ui.log

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.noveltts.engine.Category
import app.noveltts.engine.DiagnosticsLog
import app.noveltts.engine.Level
import app.noveltts.engine.LogEntry
import app.noveltts.ui.common.AppIcon
import app.noveltts.ui.common.AppIconGlyph
import app.noveltts.ui.common.Badge
import app.noveltts.ui.common.EmptyState
import app.noveltts.ui.common.FilterChipRow
import app.noveltts.ui.theme.Accent
import app.noveltts.ui.theme.Ink
import app.noveltts.ui.theme.Muted
import app.noveltts.ui.theme.Teal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 日志级别的中文名，页面与事件条共用。 */
internal fun Level.label(): String = when (this) {
    Level.DEBUG -> "调试"
    Level.INFO -> "信息"
    Level.WARN -> "警告"
    Level.ERROR -> "错误"
}

/** 日志类别的中文名，页面与事件条共用。 */
internal fun Category.label(): String = when (this) {
    Category.ENGINE -> "引擎"
    Category.PACK -> "音色包"
    Category.PIPELINE -> "流水线"
    Category.SPEECH -> "合成"
    Category.UI -> "界面"
    Category.STORAGE -> "存储"
}

/** 级别色标：与 Ink / Muted / Accent 主题一致，错误用配色表的 error，深浅主题都可读。 */
@Composable
internal fun Level.color(): Color = when (this) {
    Level.DEBUG -> Muted
    Level.INFO -> Teal
    Level.WARN -> Accent
    Level.ERROR -> MaterialTheme.colorScheme.error
}

/** 级别图标：与级别文字同时出现，只做辅助识别。 */
internal fun Level.icon(): AppIcon = when (this) {
    Level.DEBUG -> AppIcon.INFO
    Level.INFO -> AppIcon.INFO
    Level.WARN -> AppIcon.WARNING
    Level.ERROR -> AppIcon.ERROR
}

private enum class LevelFilter(val title: String) {
    ALL("全部"),
    WARN("警告"),
    ERROR("错误"),
}

/** 把日志列表序列化成纯文本，供剪贴板与文件导出共用。 */
private fun buildLogText(entries: List<LogEntry>, timeFormat: SimpleDateFormat): String {
    val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
    return buildString {
        appendLine("NovelTTS 诊断日志")
        appendLine("导出时间：$stamp")
        appendLine("共 ${entries.size} 条 · 只含文本长度与前缀哈希，不含朗读原文与许可证")
        appendLine()
        entries.forEach { entry ->
            append(timeFormat.format(Date(entry.timeMillis)))
            append(" [").append(entry.level.label()).append("] [").append(entry.category.label()).append("] ")
            append(entry.message)
            if (!entry.detail.isNullOrBlank()) append(" — ").append(entry.detail)
            appendLine()
        }
    }
}

/**
 * 全屏诊断日志页：级别筛选、类别筛选、清空、复制、导出为文本。
 *
 * 入口建议：从抽屉或设置页进入，例如
 * ```kotlin
 * var showLogs by remember { mutableStateOf(false) }
 * if (showLogs) LogPage(onBack = { showLogs = false })
 * ```
 * 页面自己绘制标题栏，不依赖外层 Scaffold，可整屏替换当前内容。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LogPage(onBack: () -> Unit = {}) {
    val entries by DiagnosticsLog.entriesFlow.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    var levelFilter by remember { mutableStateOf(LevelFilter.ALL) }
    var categoryFilter by remember { mutableStateOf<Category?>(null) }
    var categoryMenuOpen by remember { mutableStateOf(false) }
    var expandedKeys by remember { mutableStateOf(emptySet<String>()) }
    var statusLine by remember { mutableStateOf<String?>(null) }
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val scope = rememberCoroutineScope()
    var pendingExport by remember { mutableStateOf<String?>(null) }
    var pendingCount by remember { mutableStateOf(0) }
    var exporting by remember { mutableStateOf(false) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val text = pendingExport
        val count = pendingCount
        pendingExport = null
        if (uri != null && text != null) scope.launch {
            exporting = true
            try {
                val result = withContext(Dispatchers.IO) { runCatching {
                    val output = context.contentResolver.openOutputStream(uri, "wt") ?: error("无法打开保存位置")
                    output.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                } }
                statusLine = result.fold({ "已保存 $count 条诊断日志" }, { "导出失败：${it.message}" })
            } finally { exporting = false }
        }
    }

    val filtered = remember(entries, levelFilter, categoryFilter) {
        entries.filter { entry ->
            val levelOk = when (levelFilter) {
                LevelFilter.ALL -> true
                LevelFilter.WARN -> entry.level == Level.WARN || entry.level == Level.ERROR
                LevelFilter.ERROR -> entry.level == Level.ERROR
            }
            val categoryOk = categoryFilter == null || entry.category == categoryFilter
            levelOk && categoryOk
        }
    }

    val copyToClipboard = {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (clipboard == null) {
            statusLine = "当前设备不支持剪贴板"
        } else {
            clipboard.setPrimaryClip(
                ClipData.newPlainText("NovelTTS 诊断日志", buildLogText(filtered, timeFormat)),
            )
            Toast.makeText(context, "已复制 ${filtered.size} 条日志", Toast.LENGTH_SHORT).show()
            statusLine = "已复制 ${filtered.size} 条日志到剪贴板"
        }
    }

    val exportToFile = {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.getDefault()).format(Date())
        pendingExport = buildLogText(filtered, timeFormat)
        pendingCount = filtered.size
        exportLauncher.launch("noveltts-diagnostics-$stamp.txt")
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack, contentPadding = PaddingValues(horizontal = 0.dp)) {
                    AppIconGlyph(
                        icon = AppIcon.ARROW_BACK,
                        size = 18.dp,
                        tint = Accent,
                        modifier = Modifier.padding(end = 4.dp),
                    )
                    Text(text = "返回", color = Accent)
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "诊断日志",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = Ink,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${filtered.size}/${entries.size} 条",
                    style = MaterialTheme.typography.labelSmall,
                    color = Muted,
                )
            }

            FilterChipRow(
                options = listOf(
                    LevelFilter.ALL to "全部",
                    LevelFilter.WARN to "警告",
                    LevelFilter.ERROR to "错误",
                ),
                selected = levelFilter,
                onSelect = { levelFilter = it },
            )

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box {
                    OutlinedButton(
                        onClick = { categoryMenuOpen = true },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        AppIconGlyph(
                            icon = AppIcon.FILTER,
                            size = 18.dp,
                            modifier = Modifier.padding(end = 6.dp),
                        )
                        Text(text = "类别：${categoryFilter?.label() ?: "全部"}")
                    }
                    DropdownMenu(
                        expanded = categoryMenuOpen,
                        onDismissRequest = { categoryMenuOpen = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("全部类别") },
                            onClick = {
                                categoryFilter = null
                                categoryMenuOpen = false
                            },
                        )
                        Category.values().forEach { category ->
                            DropdownMenuItem(
                                text = { Text(category.label()) },
                                onClick = {
                                    categoryFilter = category
                                    categoryMenuOpen = false
                                },
                            )
                        }
                    }
                }
                OutlinedButton(
                    onClick = {
                        DiagnosticsLog.clear()
                        statusLine = if (DiagnosticsLog.isStoreAttached()) "已清空内存日志与磁盘文件" else null
                    },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    AppIconGlyph(
                        icon = AppIcon.DELETE,
                        size = 18.dp,
                        modifier = Modifier.padding(end = 6.dp),
                    )
                    Text("清空")
                }
                OutlinedButton(
                    onClick = copyToClipboard,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    AppIconGlyph(
                        icon = AppIcon.COPY,
                        size = 18.dp,
                        modifier = Modifier.padding(end = 6.dp),
                    )
                    Text("复制")
                }
                OutlinedButton(
                    onClick = { exportToFile() },
                    enabled = !exporting && pendingExport == null,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    AppIconGlyph(
                        icon = AppIcon.DOWNLOAD,
                        size = 18.dp,
                        modifier = Modifier.padding(end = 6.dp),
                    )
                    Text(if (exporting) "正在保存…" else "导出…")
                }
            }

            if (statusLine != null) {
                Text(
                    text = statusLine.orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    color = Teal,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = "日志只记录文本长度与前缀哈希，不含朗读原文、音色包绝对路径或许可证。",
                style = MaterialTheme.typography.labelSmall,
                color = Muted,
            )
            if (DiagnosticsLog.isStoreAttached()) {
                Text(
                    text = "已保存到文件 · 内存保留最近 " + entries.size + " 条",
                    style = MaterialTheme.typography.labelSmall,
                    color = Muted,
                )
            }

            if (filtered.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    EmptyState(
                        message = if (entries.isEmpty()) {
                            "暂无日志。合成、导入语音包或引擎出错时会自动记录。"
                        } else {
                            "当前筛选下没有日志。"
                        },
                        actionLabel = if (entries.isNotEmpty()) "清除筛选" else null,
                        onAction = if (entries.isNotEmpty()) {
                            {
                                levelFilter = LevelFilter.ALL
                                categoryFilter = null
                            }
                        } else {
                            null
                        },
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    itemsIndexed(filtered) { index, entry ->
                        val key = "${entry.timeMillis}|$index"
                        LogRow(
                            entry = entry,
                            timeFormat = timeFormat,
                            expanded = key in expandedKeys,
                            onToggle = {
                                expandedKeys = if (key in expandedKeys) {
                                    expandedKeys - key
                                } else {
                                    expandedKeys + key
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LogRow(
    entry: LogEntry,
    timeFormat: SimpleDateFormat,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val hasDetail = !entry.detail.isNullOrBlank()
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (hasDetail) Modifier.clickable(onClick = onToggle) else Modifier),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            AppIconGlyph(
                icon = entry.level.icon(),
                modifier = Modifier.padding(top = 2.dp),
                tint = entry.level.color(),
                size = 16.dp,
            )
            Text(
                text = timeFormat.format(Date(entry.timeMillis)),
                style = MaterialTheme.typography.labelSmall,
                color = Muted,
                fontFamily = FontFamily.Monospace,
            )
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = entry.level.label(),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = entry.level.color(),
                    )
                    Badge(text = entry.category.label())
                }
                Text(
                    text = entry.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = Ink,
                )
                AnimatedVisibility(visible = expanded && hasDetail) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    ) {
                        Text(
                            text = entry.detail.orEmpty(),
                            style = MaterialTheme.typography.labelSmall,
                            color = Muted,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        )
                    }
                }
            }
            if (hasDetail) {
                Text(
                    text = if (expanded) "收起" else "详情",
                    style = MaterialTheme.typography.labelSmall,
                    color = Accent,
                )
            }
        }
    }
}
