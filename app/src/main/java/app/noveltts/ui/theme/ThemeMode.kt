package app.noveltts.ui.theme

/**
 * 主题模式：白天固定浅色、夜间固定深色、自动跟随系统深色开关。
 *
 * 存储值就是枚举名（LIGHT / DARK / SYSTEM），旧版本没有该键时回落到 [DEFAULT]。
 * 这个类型不依赖 Compose，便于在单元测试里直接验证解析结果。
 */
enum class ThemeMode(val label: String) {
    LIGHT("白天"),
    DARK("夜间"),
    SYSTEM("跟随系统"),
    ;

    /** 结合系统当前的深色状态，解析出是否使用深色配色。 */
    fun isDark(systemDark: Boolean): Boolean = when (this) {
        LIGHT -> false
        DARK -> true
        SYSTEM -> systemDark
    }

    companion object {
        /** 新安装与旧版本升级后的默认模式：跟随系统。 */
        val DEFAULT: ThemeMode = SYSTEM

        /** 解析 SharedPreferences 里的字符串；未知值或不合法输入回落到 [DEFAULT]。 */
        fun fromStorage(value: String?): ThemeMode =
            entries.firstOrNull { it.name.equals(value?.trim(), ignoreCase = true) } ?: DEFAULT
    }
}
