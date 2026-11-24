package com.techwin.statushd.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun ProcessingStage(
    headline: String,
    subtext: String,
    progress: Float, // 0.0 to 1.0
    stages: List<String>,
    currentStageIndex: Int,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = headline,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground
            )
            if (subtext.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = subtext,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(120.dp)) {
            CircularProgressIndicator(
                progress = progress,
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                strokeWidth = 8.dp
            )
            Text(
                text = "${(progress * 100).toInt()}%",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            stages.forEachIndexed { index, stageText ->
                val isActive = index == currentStageIndex
                val isPast = index < currentStageIndex

                val color = when {
                    isActive -> MaterialTheme.colorScheme.primary
                    isPast -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.outline
                }

                Text(
                    text = stageText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = color
                )
            }
        }
    }
}
