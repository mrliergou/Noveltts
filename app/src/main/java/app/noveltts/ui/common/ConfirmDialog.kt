package app.noveltts.ui.common

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import app.noveltts.ui.theme.Ink

/** 二次确认弹窗。[dangerous] 不设默认值，强制调用方声明该操作是否会丢数据。 */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String = "删除",
    dangerous: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title, fontWeight = FontWeight.SemiBold, color = Ink) },
        text = { Text(text = message, style = MaterialTheme.typography.bodyMedium, color = Ink) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = confirmLabel,
                    fontWeight = FontWeight.SemiBold,
                    color = if (dangerous) colors.error else colors.primary,
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
