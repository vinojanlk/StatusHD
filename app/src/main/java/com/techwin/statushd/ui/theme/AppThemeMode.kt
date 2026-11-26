package com.techwin.statushd.ui.theme

enum class AppThemeMode(val storageValue: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark");

    companion object {
        fun fromStorageValue(value: String?): AppThemeMode {
            return entries.firstOrNull { it.storageValue == value } ?: SYSTEM
        }
    }
}

fun resolveDarkTheme(
    mode: AppThemeMode,
    systemIsDark: Boolean
): Boolean = when (mode) {
    AppThemeMode.SYSTEM -> systemIsDark
    AppThemeMode.LIGHT -> false
    AppThemeMode.DARK -> true
}

fun nextThemeMode(mode: AppThemeMode): AppThemeMode = when (mode) {
    AppThemeMode.SYSTEM -> AppThemeMode.LIGHT
    AppThemeMode.LIGHT -> AppThemeMode.DARK
    AppThemeMode.DARK -> AppThemeMode.SYSTEM
}
