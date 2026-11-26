package com.techwin.statushd.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun StatusHeroCard(
    badgeText: String,
    title: String,
    description: String,
    gradientColors: List<Color>,
    buttonText: String,
    onButtonClick: () -> Unit,
    buttonIcon: androidx.compose.ui.graphics.vector.ImageVector,
    graphicContent: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(Color(0xFF070A11))
            .background(Brush.linearGradient(gradientColors))
            .padding(24.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(
                    modifier = Modifier.weight(1f).padding(end = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    TonalBadge(
                        text = badgeText,
                        containerColor = Color.White.copy(alpha = 0.15f),
                        contentColor = Color.White
                    )
                    Text(
                        text = title,
                        style = MaterialTheme.typography.displayMedium,
                        color = Color.White
                    )
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.85f)
                    )
                }
                Box(
                    modifier = Modifier.size(100.dp),
                    contentAlignment = Alignment.Center
                ) {
                    graphicContent()
                }
            }

            StatusPrimaryButton(
                text = buttonText,
                icon = buttonIcon,
                onClick = onButtonClick,
                containerColor = Color.White,
                contentColor = Color.Black
            )
        }
    }
}
