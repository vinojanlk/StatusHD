package com.techwin.statushd.ui.components

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.techwin.statushd.storage.StorageLowWarning
import com.techwin.statushd.storage.StoragePreflightPrompt
import com.techwin.statushd.storage.StorageSettingsNavigator

@Composable
fun StoragePreflightDialog(
    prompt: StoragePreflightPrompt?,
    onContinueUnavailable: () -> Unit,
    onDismissForManageStorage: () -> Unit,
    onCancel: () -> Unit
) {
    if (prompt == null) return
    val context = LocalContext.current

    when (prompt) {
        is StoragePreflightPrompt.InsufficientShared -> {
            val requiredText = Formatter.formatShortFileSize(context, prompt.requiredBytes)
            val availableText = Formatter.formatShortFileSize(context, prompt.availableBytes)
            AlertDialog(
                onDismissRequest = onCancel,
                title = {
                    Text(
                        text = "Not enough storage",
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Text(
                        text = "StatusHD needs about $requiredText of free shared storage. About $availableText is currently available.",
                        modifier = Modifier.verticalScroll(rememberScrollState())
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            onDismissForManageStorage()
                            launchManageStorage(context)
                        }
                    ) {
                        Text("Manage storage")
                    }
                },
                dismissButton = {
                    OutlinedButton(onClick = onCancel) {
                        Text("Cancel")
                    }
                },
                containerColor = MaterialTheme.colorScheme.surface,
                titleContentColor = MaterialTheme.colorScheme.onSurface,
                textContentColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        is StoragePreflightPrompt.InsufficientInternal -> {
            val requiredText = Formatter.formatShortFileSize(context, prompt.requiredBytes)
            val availableText = Formatter.formatShortFileSize(context, prompt.availableBytes)
            AlertDialog(
                onDismissRequest = onCancel,
                title = {
                    Text(
                        text = "Not enough storage",
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Text(
                        text = "StatusHD needs about $requiredText of temporary app storage to prepare this video. About $availableText is currently available.",
                        modifier = Modifier.verticalScroll(rememberScrollState())
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            onDismissForManageStorage()
                            launchManageStorage(context)
                        }
                    ) {
                        Text("Manage storage")
                    }
                },
                dismissButton = {
                    OutlinedButton(onClick = onCancel) {
                        Text("Cancel")
                    }
                },
                containerColor = MaterialTheme.colorScheme.surface,
                titleContentColor = MaterialTheme.colorScheme.onSurface,
                textContentColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        is StoragePreflightPrompt.InsufficientBoth -> {
            val internalReqText = Formatter.formatShortFileSize(context, prompt.internalRequiredBytes)
            val internalAvailText = Formatter.formatShortFileSize(context, prompt.internalAvailableBytes)
            val sharedReqText = Formatter.formatShortFileSize(context, prompt.sharedRequiredBytes)
            val sharedAvailText = Formatter.formatShortFileSize(context, prompt.sharedAvailableBytes)
            AlertDialog(
                onDismissRequest = onCancel,
                title = {
                    Text(
                        text = "Not enough storage",
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Text(
                        text = "StatusHD needs about $internalReqText of temporary app storage (about $internalAvailText available) and about $sharedReqText of shared storage (about $sharedAvailText available).",
                        modifier = Modifier.verticalScroll(rememberScrollState())
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            onDismissForManageStorage()
                            launchManageStorage(context)
                        }
                    ) {
                        Text("Manage storage")
                    }
                },
                dismissButton = {
                    OutlinedButton(onClick = onCancel) {
                        Text("Cancel")
                    }
                },
                containerColor = MaterialTheme.colorScheme.surface,
                titleContentColor = MaterialTheme.colorScheme.onSurface,
                textContentColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        StoragePreflightPrompt.Unavailable -> {
            AlertDialog(
                onDismissRequest = onCancel,
                title = {
                    Text(
                        text = "Storage check unavailable",
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Text(
                        text = "StatusHD could not check available storage.",
                        modifier = Modifier.verticalScroll(rememberScrollState())
                    )
                },
                confirmButton = {
                    Button(onClick = onContinueUnavailable) {
                        Text("Continue")
                    }
                },
                dismissButton = {
                    OutlinedButton(onClick = onCancel) {
                        Text("Cancel")
                    }
                },
                containerColor = MaterialTheme.colorScheme.surface,
                titleContentColor = MaterialTheme.colorScheme.onSurface,
                textContentColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun launchManageStorage(context: android.content.Context) {
    val intent = StorageSettingsNavigator.createManageStorageIntent()
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        try {
            context.startActivity(Intent(Settings.ACTION_SETTINGS))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(
                context,
                "Could not open storage settings.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }
}

@Composable
fun StorageLowWarningBanner(
    warning: StorageLowWarning,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val subtitleText = when {
        warning.internalRequiredBytes > 0L && warning.sharedRequiredBytes > 0L -> {
            val reqText = Formatter.formatShortFileSize(
                context,
                warning.internalRequiredBytes + warning.sharedRequiredBytes
            )
            "Available storage is close to the estimated $reqText needed."
        }
        warning.internalRequiredBytes > 0L -> {
            val reqText = Formatter.formatShortFileSize(context, warning.internalRequiredBytes)
            val availText = warning.internalAvailableBytes?.let { Formatter.formatShortFileSize(context, it) } ?: "low"
            "About $availText available for an estimated $reqText needed."
        }
        else -> {
            val reqText = Formatter.formatShortFileSize(context, warning.sharedRequiredBytes)
            val availText = warning.sharedAvailableBytes?.let { Formatter.formatShortFileSize(context, it) } ?: "low"
            "About $availText available for an estimated $reqText needed."
        }
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start
        ) {
            Icon(
                imageVector = Icons.Default.WarningAmber,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "Storage may be too low",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Text(
                    text = subtitleText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }
    }
}
