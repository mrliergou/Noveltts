package app.noveltts.ui.books

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.noveltts.data.config.BookLibraryJson
import app.noveltts.domain.BookCharacter
import app.noveltts.domain.BookRoleProfile
import app.noveltts.domain.BookVoiceMode
import app.noveltts.ui.MainUiState
import app.noveltts.ui.MainViewModel
import app.noveltts.ui.common.*
import app.noveltts.ui.theme.Muted
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BookEditorSheet(kind: String, expected: BookRoleProfile, fingerprint: String, recoveryCount: Int,
    state: MainUiState, viewModel: MainViewModel, onDismiss: () -> Unit) {
    val original = expected.characters.firstOrNull()
    val initialName = if (kind == "new") "" else if (kind == "character") original?.name.orEmpty() else expected.name
    val initialAliases = original?.aliases?.joinToString("\n").orEmpty()
    val initialMode = original?.voiceMode ?: BookVoiceMode.POOL
    val initialPoolVoice = original?.voiceId.orEmpty().takeIf { initialMode == BookVoiceMode.POOL }.orEmpty()
    var name by rememberSaveable { mutableStateOf(initialName) }
    var aliases by rememberSaveable { mutableStateOf(initialAliases) }
    var pool by rememberSaveable { mutableStateOf(expected.voicePool.joinToString("\n")) }
    var poolVoiceId by rememberSaveable { mutableStateOf(initialPoolVoice) }
    var fixedVoiceId by rememberSaveable { mutableStateOf(original?.voiceId.orEmpty()) }
    var modeName by rememberSaveable { mutableStateOf(initialMode.name) }
    val characterId = rememberSaveable { original?.id ?: UUID.randomUUID().toString() }
    var target by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    var selectedExpanded by rememberSaveable { mutableStateOf(false) }
    var discard by rememberSaveable { mutableStateOf(false) }
    var submitted by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var nameEdited by rememberSaveable { mutableStateOf(false) }
    var aliasesEdited by rememberSaveable { mutableStateOf(false) }
    var nameInputTooLong by rememberSaveable { mutableStateOf(false) }
    var aliasesInputTooLong by rememberSaveable { mutableStateOf(false) }
    var poolPreviewId by remember { mutableStateOf<String?>(null) }
    val currentPreviewId by rememberUpdatedState(state.previewVoiceId)
    DisposableEffect(Unit) {
        onDispose { if (poolPreviewId != null && currentPreviewId == poolPreviewId) viewModel.stopPreview() }
    }
    val mode = BookVoiceMode.valueOf(modeName)
    val voiceId = when (mode) { BookVoiceMode.POOL -> poolVoiceId; BookVoiceMode.FIXED -> fixedVoiceId; BookVoiceMode.DEFAULT -> "" }
    val ids = remember(pool) { pool.lines().filter { it.isNotBlank() } }
    val busy = state.bookBusy || submitted.isNotEmpty()
    val dirty = name != initialName || aliases != initialAliases || pool != expected.voicePool.joinToString("\n") ||
        mode != initialMode || poolVoiceId != initialPoolVoice || fixedVoiceId != original?.voiceId.orEmpty()
    val latestBusy by rememberUpdatedState(busy)
    val requestDismiss by rememberUpdatedState { if (!latestBusy) { if (dirty) discard = true else onDismiss() } }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = {
        if (it == SheetValue.Hidden) { requestDismiss(); false } else true
    })
    LaunchedEffect(submitted, state.bookBusy, state.bookOperationId, state.bookOperationError) {
        if (submitted.isNotEmpty() && !state.bookBusy) {
            if (state.bookOperationId == submitted) {
                if (state.bookOperationError == null) onDismiss() else error = state.bookOperationError
            }
            submitted = ""
        }
    }
    val currentCharacters = state.bookLibrary.books.firstOrNull { it.id == expected.id }?.characters.orEmpty()
    val characterErrors = remember(name, aliases, characterId, currentCharacters) {
        bookCharacterErrors(name, aliases, characterId, currentCharacters)
    }
    val nameError = when (kind) {
        "new", "rename" -> if (nameInputTooLong) "书名最多 80 字，超长粘贴未接收" else bookNameError(name, character = false)
        "character" -> if (nameInputTooLong) "人物名最多 60 字，超长粘贴未接收" else characterErrors.first
        else -> null
    }
    val aliasError = (if (aliasesInputTooLong) "输入过长，超长粘贴未接收；请整理为最多 16 个别名" else characterErrors.second)
        .takeIf { kind == "character" }
    val candidates = remember(currentCharacters, original?.id, query) {
        currentCharacters.filter { it.id != original?.id && it.names.any { alias -> alias.contains(query.trim(), ignoreCase = true) } }
    }
    val selectedTarget = currentCharacters.firstOrNull { it.id == target && it.id != original?.id }
    val poolCandidates = remember(state.voices, query) {
        state.voices.filter { voice -> voice.usable && listOf(voice.displayName, voice.packName, voice.engineCode, voice.localeTag)
            .any { it.contains(query.trim(), ignoreCase = true) } }
    }
    val voicesById = remember(state.voices) { state.voices.associateBy { it.systemName } }
    val title = when (kind) {
        "new" -> "新建书籍"
        "rename" -> "修改书名"
        "pool" -> "管理声音池"
        "character" -> if (original == null) "添加人物" else "编辑人物"
        "deleteBook" -> "删除书籍方案"
        "deleteCharacter" -> "删除人物"
        "merge" -> "合并人物"
        else -> "恢复本机书籍备份"
    }
    val destructive = kind in setOf("deleteBook", "deleteCharacter", "merge", "recover")
    val canSave = !busy && nameError == null && aliasError == null &&
        (kind != "merge" || selectedTarget != null) && (kind != "character" || mode != BookVoiceMode.FIXED || voiceId.isNotBlank())
    ModalBottomSheet(onDismissRequest = { requestDismiss() }, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.92f).imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleLarge)
                    if (kind != "new" && kind != "recover") Text(expected.name, style = MaterialTheme.typography.bodySmall, color = Muted)
                }
                IconButton({ requestDismiss() }, enabled = !busy) { AppIconGlyph(AppIcon.CLOSE, contentDescription = "关闭编辑") }
            }
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth()
                    .then(if (kind == "merge") Modifier.selectableGroup() else Modifier),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (kind in setOf("new", "rename", "character")) item(key = "name") {
                    OutlinedTextField(name, {
                        nameInputTooLong = it.length > 256
                        if (!nameInputTooLong) name = it
                        nameEdited = true; error = null
                    },
                        label = { Text(if (kind == "character") "人物姓名" else "书名") },
                        supportingText = { Text(nameError ?: if (kind == "character") "保存到「${expected.name}」" else "最多 80 字") },
                        isError = nameEdited && nameError != null, enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                if (kind == "new") item(key = "new-help") {
                    Text("创建后自动选中这本书。接下来选择声音池、添加人物，即可开始固定配音。", color = Muted)
                }
                if (kind == "pool") {
                    item(key = "pool-summary") {
                        Text("已选 ${ids.size} / ${BookLibraryJson.MAX_POOL} 个声音", style = MaterialTheme.typography.titleMedium)
                        Text("勾选多个声音后统一保存。移除或重排只影响新分配，已有绑定不变。", style = MaterialTheme.typography.bodySmall, color = Muted)
                        if (ids.isEmpty()) Text("声音池为空时，未绑定人物先用默认对话音色。", color = Muted)
                        val unavailable = ids.count { voicesById[it]?.usable != true }
                        if (unavailable > 0) Text("$unavailable 个已选声音暂不可用；在已选列表中可查看或移除。", color = Muted)
                        if (ids.size >= BookLibraryJson.MAX_POOL) Text("声音池已满，请先移除一个声音再添加。", color = Muted)
                        if (ids.isNotEmpty()) TextButton({ selectedExpanded = !selectedExpanded }) {
                            Text(if (selectedExpanded) "收起已选声音" else "查看已选 / 调整顺序")
                        }
                    }
                    if (selectedExpanded) itemsIndexed(ids, key = { _, id -> "selected:$id" }) { index, id ->
                        Column {
                            val voice = voicesById[id]
                            Text("${index + 1}. ${voice?.displayName ?: "原绑定音色"}" + if (voice?.usable == true) "" else " · 不可用")
                            Row {
                                IconButton({
                                    val reordered = ids.toMutableList()
                                    java.util.Collections.swap(reordered, index, index - 1); pool = reordered.joinToString("\n")
                                }, enabled = !busy && index > 0) { AppIconGlyph(AppIcon.CHEVRON_UP, contentDescription = "上移${voice?.displayName ?: "音色"}") }
                                IconButton({
                                    val reordered = ids.toMutableList()
                                    java.util.Collections.swap(reordered, index, index + 1); pool = reordered.joinToString("\n")
                                }, enabled = !busy && index < ids.lastIndex) { AppIconGlyph(AppIcon.CHEVRON_DOWN, contentDescription = "下移${voice?.displayName ?: "音色"}") }
                                TextButton({ pool = ids.filterNot { it == id }.joinToString("\n") }, enabled = !busy) { Text("移除") }
                            }
                        }
                    }
                    item(key = "pool-search") { SearchField(query, { query = it }, "搜索音色、语音包或语言") }
                    if (poolCandidates.isEmpty()) item(key = "pool-empty") {
                        Text(if (state.usableVoices.isEmpty()) "暂无可用音色，请先导入语音包或配置许可证。" else "没有匹配的音色，请调整搜索。", color = Muted)
                    }
                    items(poolCandidates, key = { "available:${it.systemName}" }) { voice ->
                        val selected = voice.systemName in ids
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Row(Modifier.weight(1f).heightIn(min = 64.dp)
                                .toggleable(selected, enabled = !busy && (selected || ids.size < BookLibraryJson.MAX_POOL), role = Role.Checkbox) { checked ->
                                    pool = (if (checked) ids + voice.systemName else ids.filterNot { it == voice.systemName }).joinToString("\n")
                                }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Checkbox(selected, onCheckedChange = null, enabled = !busy && (selected || ids.size < BookLibraryJson.MAX_POOL))
                                Column(Modifier.weight(1f)) {
                                    Text(voice.displayName, style = MaterialTheme.typography.bodyLarge)
                                    Text(voiceSubtitle(voice), style = MaterialTheme.typography.bodySmall, color = Muted)
                                }
                            }
                            TextButton({
                                if (state.previewVoiceId == voice.systemName) viewModel.stopPreview()
                                else { poolPreviewId = voice.systemName; viewModel.previewVoice(voice) }
                            }, enabled = !busy) {
                                Text(if (state.previewVoiceId == voice.systemName) "停止" else "试听")
                            }
                        }
                    }
                }
                if (kind == "character") {
                    item(key = "aliases") {
                        OutlinedTextField(aliases, {
                            aliasesInputTooLong = it.length > 2048
                            if (!aliasesInputTooLong) aliases = it
                            aliasesEdited = true; error = null
                        }, label = { Text("别名 / 常用称呼") },
                            supportingText = { Text(aliasError ?: "每行一个，最多 16 个；重复称呼自动去重") }, isError = aliasesEdited && aliasError != null,
                            enabled = !busy, minLines = 2, maxLines = 4, modifier = Modifier.fillMaxWidth())
                    }
                    item(key = "voice-mode") {
                        Text("配音方式", style = MaterialTheme.typography.titleMedium)
                        Column(Modifier.selectableGroup()) {
                            BookVoiceMode.entries.forEach { option ->
                                val (label, description) = when (option) {
                                    BookVoiceMode.POOL -> "声音池分配" to "首次分配后固定，调整声音池不会换声"
                                    BookVoiceMode.FIXED -> "手动指定" to "为这个人物单独选择一个声音"
                                    BookVoiceMode.DEFAULT -> "跟随默认对话" to "使用全局设置中的默认对话声音"
                                }
                                BookChoiceRow(label, description, mode == option, !busy) { modeName = option.name }
                            }
                        }
                    }
                    item(key = "voice") {
                        if (mode == BookVoiceMode.FIXED) VoiceField("人物音色", fixedVoiceId, state.voices, { fixedVoiceId = it },
                            onPreview = { viewModel.previewVoice(it) }, onStopPreview = viewModel::stopPreview,
                            previewVoiceId = state.previewVoiceId, previewStatus = state.statusMessage, enabled = !busy)
                        else Text(bindingLabel(BookCharacter(characterId, name, voiceId = voiceId, voiceMode = mode), state), color = Muted)
                        if (mode == BookVoiceMode.POOL && poolVoiceId.isNotBlank()) TextButton({ poolVoiceId = "" }, enabled = !busy) {
                            Text("保存时重新分配")
                        }
                        if (mode == BookVoiceMode.POOL && expected.voicePool.none { id -> state.usableVoices.any { it.systemName == id } }) {
                            Text("本书声音池暂无可用声音。可以先保存人物，之后在声音池中添加。", style = MaterialTheme.typography.bodySmall, color = Muted)
                        }
                        if (mode == BookVoiceMode.FIXED && fixedVoiceId.isBlank()) Text("请选择人物音色后保存", style = MaterialTheme.typography.bodySmall, color = Muted)
                    }
                }
                when (kind) {
                    "deleteBook" -> item { Text("删除「${expected.name}」的声音池与全部人物；若为当前书籍，回到全局角色模式。相关定向替换规则保留但不再命中。") }
                    "deleteCharacter" -> item { Text("删除「${original?.name}」及其别名和绑定。相关定向替换规则保留但不再命中。") }
                    "recover" -> item { Text("用本机上一次保存的备份恢复全部书籍（$recoveryCount 本），包括备份中的当前书籍选择。现文件另存保留；对下一次新朗读生效。") }
                    "merge" -> {
                        item(key = "merge-explanation") {
                            Text("将「${original?.name}」合并到下方人物，保留目标人物的声音。来源姓名会变成别名；来源人物的定向替换不会自动转移。")
                            SearchField(query, { query = it }, "搜索要保留的人物")
                        }
                        items(candidates, key = { "target:${it.id}" }) { candidate ->
                            BookChoiceRow(candidate.name, bindingLabel(candidate, state), target == candidate.id, !busy) { target = candidate.id }
                        }
                        if (candidates.isEmpty()) item { Text("没有可合并到的人物，请调整搜索。", color = Muted) }
                    }
                }
            }
            HorizontalDivider()
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (kind == "merge" && selectedTarget != null) Text("保留「${selectedTarget.name}」及其声音", style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton({ requestDismiss() }, enabled = !busy, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("取消") }
                    Button({
                        error = null
                        val operation = UUID.randomUUID().toString()
                        submitted = operation
                        when (kind) {
                            "new" -> viewModel.createBook(operation, expected.copy(name = name, voicePool = ids))
                            "rename", "pool" -> viewModel.updateBook(operation, expected, name, ids)
                            "character" -> viewModel.saveBookCharacter(operation, expected, BookCharacter(characterId, name, aliases.lines(), voiceId, mode))
                            "deleteBook" -> viewModel.deleteBook(operation, expected)
                            "deleteCharacter" -> viewModel.deleteBookCharacter(operation, expected, original!!.id)
                            "merge" -> viewModel.mergeBookCharacters(operation, expected, original!!.id, target)
                            "recover" -> viewModel.recoverBooks(operation, fingerprint)
                        }
                    }, enabled = canSave, modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        colors = if (destructive) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError)
                            else ButtonDefaults.buttonColors()) {
                        Text(if (busy) "正在保存…" else when (kind) {
                            "new" -> "创建并选中"
                            "merge" -> "确认合并"
                            "recover" -> "确认恢复"
                            "deleteBook", "deleteCharacter" -> "确认删除"
                            else -> "保存"
                        })
                    }
                }
            }
        }
    }
    BackHandler { requestDismiss() }
    if (discard) ConfirmDialog("放弃修改？", "尚未保存的编辑内容将丢失。", "放弃", true,
        onConfirm = { discard = false; onDismiss() }, onDismiss = { discard = false })
}
