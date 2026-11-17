package app.noveltts.ui.common

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.noveltts.ui.theme.Muted

/** 单行搜索框。项目未引入 material-icons，图标由 [AppIconGlyph] 自绘；清除按钮仅在有内容时出现。 */
@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(text = placeholder, style = MaterialTheme.typography.bodySmall, color = Muted) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        leadingIcon = { AppIconGlyph(AppIcon.SEARCH, size = 20.dp, tint = Muted) },
        trailingIcon = {
            if (value.isNotEmpty()) {
                IconButton(onClick = { onValueChange("") }) {
                    AppIconGlyph(AppIcon.CLOSE, size = 20.dp, contentDescription = "清空搜索")
                }
            }
        },
        modifier = modifier.fillMaxWidth(),
    )
}
