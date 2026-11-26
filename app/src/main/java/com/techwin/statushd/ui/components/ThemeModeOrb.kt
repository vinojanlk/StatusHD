package com.techwin.statushd.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.techwin.statushd.ui.theme.AppThemeMode
import com.techwin.statushd.ui.theme.nextThemeMode

@Composable
fun ThemeModeOrb(
    selectedMode: AppThemeMode,
    onSelectMode: (AppThemeMode) -> Unit,
    modifier: Modifier = Modifier
) {
    var menuExpanded by remember { mutableStateOf(false) }

    val modeLabel = when (selectedMode) {
        AppThemeMode.SYSTEM -> "System default"
        AppThemeMode.LIGHT -> "Light"
        AppThemeMode.DARK -> "Dark"
    }
    val containerColor = when (selectedMode) {
        AppThemeMode.SYSTEM -> MaterialTheme.colorScheme.secondaryContainer
        AppThemeMode.LIGHT, AppThemeMode.DARK -> MaterialTheme.colorScheme.primaryContainer
    }
    val contentColor = when (selectedMode) {
        AppThemeMode.SYSTEM -> MaterialTheme.colorScheme.onSecondaryContainer
        AppThemeMode.LIGHT, AppThemeMode.DARK -> MaterialTheme.colorScheme.onPrimaryContainer
    }
    val borderColor = when (selectedMode) {
        AppThemeMode.SYSTEM -> MaterialTheme.colorScheme.outlineVariant
        AppThemeMode.LIGHT, AppThemeMode.DARK -> MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
    }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .combinedClickable(
                    role = Role.Button,
                    onClickLabel = "Switch appearance mode",
                    onLongClickLabel = "Choose appearance mode",
                    onClick = { onSelectMode(nextThemeMode(selectedMode)) },
                    onLongClick = { menuExpanded = true }
                )
                .semantics(mergeDescendants = true) {
                    contentDescription = "Appearance: $modeLabel"
                    stateDescription = modeLabel
                },
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = CircleShape,
                color = containerColor,
                contentColor = contentColor,
                border = BorderStroke(1.dp, borderColor)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    AnimatedContent(
                        targetState = selectedMode,
                        transitionSpec = {
                            (fadeIn(tween(180)) + scaleIn(initialScale = 0.85f, animationSpec = tween(180)))
                                .togetherWith(fadeOut(tween(140)))
                        },
                        label = "ThemeModeOrbIcon"
                    ) { mode ->
                        AppearanceModeIcon(
                            mode = mode,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
        }

        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            ThemeOrbMenuItem(
                label = "System default",
                mode = AppThemeMode.SYSTEM,
                isSelected = selectedMode == AppThemeMode.SYSTEM,
                onClick = {
                    menuExpanded = false
                    onSelectMode(AppThemeMode.SYSTEM)
                }
            )
            ThemeOrbMenuItem(
                label = "Light",
                mode = AppThemeMode.LIGHT,
                isSelected = selectedMode == AppThemeMode.LIGHT,
                onClick = {
                    menuExpanded = false
                    onSelectMode(AppThemeMode.LIGHT)
                }
            )
            ThemeOrbMenuItem(
                label = "Dark",
                mode = AppThemeMode.DARK,
                isSelected = selectedMode == AppThemeMode.DARK,
                onClick = {
                    menuExpanded = false
                    onSelectMode(AppThemeMode.DARK)
                }
            )
        }
    }
}

@Composable
private fun ThemeOrbMenuItem(
    label: String,
    mode: AppThemeMode,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    DropdownMenuItem(
        text = {
            Text(
                text = label,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        leadingIcon = {
            AppearanceModeIcon(
                mode = mode,
                modifier = Modifier.size(20.dp)
            )
        },
        trailingIcon = {
            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        },
        onClick = onClick
    )
}

@Composable
fun AppearanceModeIcon(
    mode: AppThemeMode,
    modifier: Modifier = Modifier
) {
    when (mode) {
        AppThemeMode.SYSTEM -> {
            Box(
                modifier = modifier,
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.Smartphone,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 2.dp, y = (-2).dp)
                        .size(10.dp)
                )
            }
        }
        AppThemeMode.LIGHT -> {
            Icon(
                imageVector = Icons.Outlined.LightMode,
                contentDescription = null,
                modifier = modifier
            )
        }
        AppThemeMode.DARK -> {
            Icon(
                imageVector = Icons.Outlined.DarkMode,
                contentDescription = null,
                modifier = modifier
            )
        }
    }
}
