package app.noveltts.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

// ---- 浅色调色板 -------------------------------------------------------------

private val InkLight = Color(0xFF202D33)
private val MutedLight = Color(0xFF647377)
private val TealLight = Color(0xFF007770)
private val TealDeepLight = Color(0xFF005B57)
private val AccentLight = Color(0xFFBE5C2A)
private val BackgroundLight = Color(0xFFF4F7F5)
private val PaleGreenLight = Color(0xFFEBF6F1)
private val OutlineLight = Color(0xFFDDE7E2)
private val ErrorLight = Color(0xFFB3261E)

// ---- 深色调色板 -------------------------------------------------------------
// 背景压到近黑，表面略亮一档以保留卡片层次；主色提亮保证填充按钮上的深色文字可读。

private val InkNight = Color(0xFFE6EFEC)
private val MutedNight = Color(0xFF9FB1AE)
private val TealNight = Color(0xFF46B8AC)
private val TealDeepNight = Color(0xFF5BD3C6)
private val AccentNight = Color(0xFFF0A87C)
private val BackgroundNight = Color(0xFF0F1516)
private val SurfaceNight = Color(0xFF151D1E)
private val PaleGreenNight = Color(0xFF1D2829)
private val OutlineNight = Color(0xFF2B3839)
private val ErrorNight = Color(0xFFFFB4A9)

val LightColors = lightColorScheme(
    primary = TealDeepLight,
    onPrimary = Color.White,
    secondary = TealLight,
    onSecondary = Color.White,
    tertiary = AccentLight,
    onTertiary = Color.White,
    background = BackgroundLight,
    onBackground = InkLight,
    surface = Color.White,
    onSurface = InkLight,
    surfaceVariant = PaleGreenLight,
    onSurfaceVariant = MutedLight,
    outline = OutlineLight,
    error = ErrorLight,
    onError = Color.White,
)

val DarkColors = darkColorScheme(
    primary = TealDeepNight,
    onPrimary = Color(0xFF04211E),
    secondary = TealNight,
    onSecondary = Color(0xFF04211E),
    tertiary = AccentNight,
    onTertiary = Color(0xFF2A1206),
    background = BackgroundNight,
    onBackground = InkNight,
    surface = SurfaceNight,
    onSurface = InkNight,
    surfaceVariant = PaleGreenNight,
    onSurfaceVariant = MutedNight,
    outline = OutlineNight,
    error = ErrorNight,
    onError = Color(0xFF3A0906),
)

// ---- 语义色 -----------------------------------------------------------------
// 这些名字在 UI 里用了 213 处；改为跟随 MaterialTheme 的 composable getter 后，
// 调用点保持 `color = Ink` 不变，深浅主题切换时自动取到对应值。
// 只能在 @Composable 作用域读取（普通函数里请直接用 MaterialTheme.colorScheme）。

/** 主文字色。 */
val Ink: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onSurface

/** 次要文字/图标色。 */
val Muted: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onSurfaceVariant

/** 品牌青绿（图表、信息级日志）。 */
val Teal: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.secondary

/** 深青绿（主按钮底色）。 */
val TealDark: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.primary

/** 强调色（告警、错误提示文字）。 */
val Accent: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.tertiary

/** 页面底色。 */
val AppBackground: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.background
