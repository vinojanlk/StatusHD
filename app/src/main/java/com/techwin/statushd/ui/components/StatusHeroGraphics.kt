package com.techwin.statushd.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex

@Composable
fun VideoHeroGraphic() {
    Box(modifier = Modifier.size(110.dp), contentAlignment = Alignment.Center) {
        // Stacked segment cards behind
        Box(
            modifier = Modifier
                .offset(x = 12.dp, y = (-12).dp)
                .size(70.dp, 90.dp)
                .rotate(8f)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White.copy(alpha = 0.3f))
        )
        Box(
            modifier = Modifier
                .offset(x = (-8).dp, y = 8.dp)
                .size(70.dp, 90.dp)
                .rotate(-5f)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White.copy(alpha = 0.5f))
        )

        // Primary video frame
        Box(
            modifier = Modifier
                .size(76.dp, 100.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color.White)
                .zIndex(1f),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(40.dp)
            )
        }

        // Chips
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.align(Alignment.BottomStart).offset(x = (-10).dp, y = 10.dp).zIndex(2f)
        ) {
            Text("1080p", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
        }

        Surface(
            color = MaterialTheme.colorScheme.secondary,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.align(Alignment.TopEnd).offset(x = 10.dp, y = (-10).dp).zIndex(2f)
        ) {
            Text("30s", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
        }
    }
}

@Composable
fun PhotoHeroGraphic() {
    Box(modifier = Modifier.size(110.dp), contentAlignment = Alignment.Center) {
        // Layered photo cards
        Box(
            modifier = Modifier
                .offset(x = 15.dp, y = 10.dp)
                .size(80.dp, 80.dp)
                .rotate(12f)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White.copy(alpha = 0.4f))
        )

        // Primary frame with crop-free lines
        Box(
            modifier = Modifier
                .size(86.dp, 86.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color.White)
                .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(14.dp))
                .zIndex(1f),
            contentAlignment = Alignment.Center
        ) {
            // Crop free frame lines effect
            Box(modifier = Modifier.fillMaxSize().padding(8.dp).border(1.dp, Color.LightGray.copy(alpha = 0.5f)))
            Icon(
                imageVector = Icons.Default.Image,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(36.dp)
            )
        }

        // Chips
        Surface(
            color = MaterialTheme.colorScheme.tertiary,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.align(Alignment.BottomEnd).offset(x = 5.dp, y = 5.dp).zIndex(2f)
        ) {
            Text("90", color = MaterialTheme.colorScheme.onTertiary, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
        }

        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.align(Alignment.TopStart).offset(x = (-5).dp, y = (-5).dp).zIndex(2f)
        ) {
            Text("JPEG", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
        }
    }
}

@Composable
fun SavedHeroGraphic() {
    Box(modifier = Modifier.size(110.dp), contentAlignment = Alignment.Center) {
        // Folder
        Icon(
            imageVector = Icons.Default.Folder,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.9f),
            modifier = Modifier.size(90.dp).offset(y = (-5).dp)
        )

        // Small photo tile
        Box(
            modifier = Modifier
                .offset(x = (-20).dp, y = 10.dp)
                .size(36.dp)
                .rotate(-10f)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.secondary)
                .border(2.dp, Color.White, RoundedCornerShape(6.dp))
                .zIndex(1f),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Image, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
        }

        // Small video tile
        Box(
            modifier = Modifier
                .offset(x = 20.dp, y = 15.dp)
                .size(36.dp)
                .rotate(15f)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.primary)
                .border(2.dp, Color.White, RoundedCornerShape(6.dp))
                .zIndex(1f),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
        }

        // Downward save arrow
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.align(Alignment.BottomCenter).offset(y = 10.dp).size(32.dp).zIndex(2f)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Default.ArrowDownward, contentDescription = null, tint = MaterialTheme.colorScheme.onTertiary, modifier = Modifier.size(18.dp))
            }
        }
    }
}
