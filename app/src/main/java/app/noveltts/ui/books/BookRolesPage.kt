package app.noveltts.ui.books

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.noveltts.data.config.BookLibraryJson
import app.noveltts.domain.BookCharacter
import app.noveltts.domain.BookLibrary
import app.noveltts.domain.BookRoleProfile
import app.noveltts.domain.BookVoiceMode
import app.noveltts.ui.MainUiState
import app.noveltts.ui.MainViewModel
import app.noveltts.ui.common.*
import app.noveltts.ui.theme.Muted
import kotlinx.coroutines.launch

/** Each editor captures the original book/version, even when the active book later changes. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BookRolesPage(state: MainUiState, viewModel: MainViewModel) {
    var editor by rememberSaveable { mutableStateOf("") }
    var baseline by rememberSaveable { mutableStateOf("") }
    var recoveryFingerprint by rememberSaveable { mutableStateOf("") }
    var recoveryCount by rememberSaveable { mutableStateOf(0) }
    var recoveryError by rememberSaveable { mutableStateOf<String?>(null) }
    var recoveryChecking by remember { mutableStateOf(false) }
    var showBooks by rememberSaveable { mutableStateOf(false) }
    var showManagement by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val book = state.bookLibrary.activeBook
    var query by rememberSaveable(book?.id) { mutableStateOf("") }
    val editable = state.booksLoadError == null && !state.bookBusy
    val characters = remember(book?.characters, query) {
        book?.characters.orEmpty().filter { person -> person.names.any { it.contains(query.trim(), ignoreCase = true) } }
    }
    fun open(kind: String, source: BookRoleProfile, character: BookCharacter? = null) {
        baseline = BookLibraryJson.encode(BookLibrary(listOf(source.copy(characters = listOfNotNull(character)))))
        editor = kind
    }
    fun checkRecovery() {
        recoveryChecking = true
        scope.launch {
            try {
                viewModel.bookRecoveryPreview().onSuccess {
                    recoveryFingerprint = it.first; recoveryCount = it.second
                    recoveryError = null; baseline = ""; editor = "recover"
                }.onFailure { recoveryError = it.message ?: "没有可用备份" }
            } finally { recoveryChecking = false }
        }
    }
    LazyColumn(
        Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "current-book") {
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("当前朗读书籍", style = MaterialTheme.typography.labelMedium, color = Muted)
                        Text(book?.name ?: "全局角色模式", style = MaterialTheme.typography.titleLarge)
                    }
                    Box {
                        IconButton({ showManagement = true }, enabled = !state.bookBusy && !recoveryChecking) {
                            AppIconGlyph(AppIcon.MORE, contentDescription = "书籍管理与恢复")
                        }
                        DropdownMenu(showManagement, { showManagement = false }) {
                            if (book != null && state.booksLoadError == null) {
                                DropdownMenuItem(text = { Text("修改书名") }, onClick = { showManagement = false; open("rename", book) })
                                DropdownMenuItem(text = { Text("删除本书方案") }, onClick = { showManagement = false; open("deleteBook", book) })
                                HorizontalDivider()
                            }
                            DropdownMenuItem(text = { Text("刷新配置") }, onClick = { showManagement = false; viewModel.refreshAll() })
                            DropdownMenuItem(text = { Text("检查本机书籍备份") }, onClick = { showManagement = false; checkRecovery() })
                        }
                    }
                }
                Text("换书后请手动切换。此选择同时用于工作台、系统 TTS 和 HTTP，下一次新朗读生效。",
                    style = MaterialTheme.typography.bodySmall, color = Muted)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ showBooks = true }, enabled = editable && state.bookLibrary.books.isNotEmpty()) { Text("切换书籍") }
                    TextButton({ open("new", BookRoleProfile(name = "新书籍")) }, enabled = editable) {
                        AppIconGlyph(AppIcon.ADD, size = 18.dp)
                        Spacer(Modifier.width(4.dp)); Text("新建书籍")
                    }
                }
                if (state.bookBusy || recoveryChecking) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.booksLoadError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                    TextButton({ checkRecovery() }, enabled = !state.bookBusy && !recoveryChecking) { Text("从本机备份恢复") }
                }
                (recoveryError ?: state.bookOperationError)?.takeIf { editor.isBlank() }?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
            }
        }
        if (book != null && state.booksLoadError == null) {
            item(key = "pool") {
                SectionCard {
                    val available = book.voicePool.count { id -> state.usableVoices.any { it.systemName == id } }
                    Text("声音池 · ${book.voicePool.size} 个声音", style = MaterialTheme.typography.titleMedium)
                    Text(if (available == 0) "尚无可分配声音，未绑定人物先用默认对话音色。"
                        else "$available 个可用 · 新人物自动固定分配，已绑定人物保持原声。",
                        style = MaterialTheme.typography.bodySmall, color = Muted)
                    OutlinedButton({ open("pool", book) }, enabled = editable) {
                        Text(if (book.voicePool.isEmpty()) "选择声音" else "管理声音池")
                    }
                }
            }
            item(key = "characters") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("人物 · ${book.characters.size}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Button({ open("character", book) }, enabled = editable) { Text("添加人物") }
                    }
                    if (book.characters.isNotEmpty()) SearchField(query, { query = it }, "搜索人物或别名")
                }
            }
            if (book.characters.isEmpty()) item(key = "empty") {
                Text("添加人物姓名和常用称呼，再为他选择声音。人物与声音只保存在本书。", color = Muted)
            } else if (characters.isEmpty()) item(key = "no-match") {
                Text("没有匹配的人物或别名", color = Muted)
                TextButton({ query = "" }) { Text("清空搜索") }
            }
            items(characters, key = { "character:${it.id}" }) { character ->
                var showActions by remember { mutableStateOf(false) }
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(
                            Modifier.weight(1f).heightIn(min = 64.dp)
                                .clickable(enabled = editable, role = Role.Button, onClickLabel = "编辑${character.name}") {
                                    open("character", book, character)
                                }.padding(vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(character.name, style = MaterialTheme.typography.titleMedium)
                            if (character.aliases.isNotEmpty()) Text("别名：${character.aliases.joinToString("、")}",
                                style = MaterialTheme.typography.bodySmall, color = Muted)
                            Text(bindingLabel(character, state), style = MaterialTheme.typography.bodySmall, color = Muted)
                        }
                        Box {
                            IconButton({ showActions = true }, enabled = editable) {
                                AppIconGlyph(AppIcon.MORE, contentDescription = "${character.name}的更多操作")
                            }
                            DropdownMenu(showActions, { showActions = false }) {
                                DropdownMenuItem(text = { Text("编辑姓名与声音") }, onClick = { showActions = false; open("character", book, character) })
                                DropdownMenuItem(text = { Text("合并到其他人物") }, enabled = book.characters.size > 1,
                                    onClick = { showActions = false; open("merge", book, character) })
                                DropdownMenuItem(text = { Text("删除人物") }, onClick = { showActions = false; open("deleteCharacter", book, character) })
                            }
                        }
                    }
                }
            }
        } else if (state.booksLoadError == null) item(key = "global-help") {
            Text("新建或选择一本书，即可为这本书单独保存人物与声音。未选书时继续使用全局角色。", color = Muted)
        }
    }
    if (showBooks) BookPickerDialog(state.bookLibrary, editable, onSelect = {
        showBooks = false
        if (it != state.bookLibrary.activeBookId) viewModel.selectBook(it, state.bookLibrary.revision)
    }, onDismiss = { showBooks = false })
    if (editor.isNotBlank()) key(editor, baseline, recoveryFingerprint) {
        val expected = remember(baseline) {
            if (baseline.isBlank()) BookRoleProfile(name = "恢复") else BookLibraryJson.decode(baseline).books.single()
        }
        BookEditorSheet(editor, expected, recoveryFingerprint, recoveryCount, state, viewModel) { editor = "" }
    }
}

internal fun bindingLabel(character: BookCharacter, state: MainUiState): String {
    if (character.voiceMode == BookVoiceMode.DEFAULT) return "跟随默认对话音色"
    if (character.voiceId.isBlank()) return "等待声音池分配 · 暂用默认对话音色"
    val voice = state.voices.firstOrNull { it.systemName == character.voiceId }
    val label = voice?.displayName ?: "原绑定音色"
    return label + if (voice?.usable == true) " · 固定配音" else " · 暂不可用，恢复后使用原声"
}

@Composable
internal fun BookPickerDialog(library: BookLibrary, enabled: Boolean, onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val books = remember(library.books, query) { library.books.filter { it.name.contains(query.trim(), ignoreCase = true) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("切换朗读书籍") },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SearchField(query, { query = it }, "搜索书名")
                LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = 400.dp).selectableGroup()) {
                    item { BookChoiceRow("全局角色模式", "不使用书籍人物", library.activeBookId.isEmpty(), enabled) { onSelect("") } }
                    items(books, key = { it.id }) { book ->
                        BookChoiceRow(book.name, "${book.characters.size} 个人物 · ${book.voicePool.size} 个池音色",
                            library.activeBookId == book.id, enabled) { onSelect(book.id) }
                    }
                    if (books.isEmpty()) item { Text("没有匹配的书籍", Modifier.padding(12.dp), color = Muted) }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("取消") } },
    )
}

@Composable
internal fun BookChoiceRow(title: String, subtitle: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp)
        .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
        .padding(vertical = 8.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        RadioButton(selected, onClick = null, enabled = enabled)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Muted)
        }
    }
}
