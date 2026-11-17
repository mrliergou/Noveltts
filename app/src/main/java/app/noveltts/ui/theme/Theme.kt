package app.noveltts.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * 应用主题：按 [themeMode] 选择浅色或深色配色，并同步系统栏底色与图标明暗。
 *
 * [ThemeMode.SYSTEM] 会读取系统深色开关；系统在运行中切换深浅色时 Activity 会重建
 * （Manifest 未声明 configChanges），ViewModel 与 rememberSaveable 状态保留。
 */
@Composable
fun MultiTtsTheme(themeMode: ThemeMode = ThemeMode.DEFAULT, content: @Composable () -> Unit) {
    val dark = themeMode.isDark(isSystemInDarkTheme())
    val colorScheme = if (dark) DarkColors else LightColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            @Suppress("DEPRECATION")
            window.statusBarColor = colorScheme.background.toArgb()
            @Suppress("DEPRECATION")
            window.navigationBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}
