package app.noveltts.ui.common

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.noveltts.data.settings.SettingsStore
import app.noveltts.ui.theme.Muted

/** Ask once when a background feature is used; an explicit settings action remains available. */
@Composable
internal fun BackgroundNotificationPermission(active: Boolean = false, showSetting: Boolean = false) {
    if (Build.VERSION.SDK_INT < 33) return
    val context = LocalContext.current
    val settings = remember(context) { SettingsStore(context) }
    var granted by remember { mutableStateOf(context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) granted = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(active, granted) {
        if (active && !granted && settings.claimNotificationPermissionPrompt()) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    if (!showSetting) return
    SectionTitle("后台通知")
    SectionCard {
        if (granted) Text("后台通知已开启", color = Muted)
        else {
            Text("允许通知后，可在通知栏查看后台状态并停止朗读或 HTTP 转发。", color = Muted)
            Button(onClick = { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("允许通知") }
            TextButton(onClick = {
                context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            }) { Text("打开系统通知设置") }
        }
    }
}
