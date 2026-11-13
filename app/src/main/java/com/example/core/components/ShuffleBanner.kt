package com.example.core.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.model.HomeMode
import com.example.domain.model.ShuffleInterval
import com.example.ui.theme.ShellDarkAccent

@Composable
fun ShuffleControlBar(
    homeMode: HomeMode,
    isShuffleEnabled: Boolean,
    shuffleInterval: ShuffleInterval,
    isShuffling: Boolean,
    onModeToggle: () -> Unit,
    onManualShuffle: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val rotation by animateFloatAsState(
        targetValue = if (isShuffling) 360f else 0f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = 300f),
        label = "shuffle_rotate"
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(20.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Mode Switcher pill (NORMAL | FOCUS)
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(onClick = onModeToggle)
                .padding(horizontal = 4.dp, vertical = 4.dp)
                .testTag("mode_switcher"),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ModeBadge(
                label = "NORMAL",
                isSelected = homeMode == HomeMode.NORMAL
            )
            ModeBadge(
                label = "FOCUS",
                isSelected = homeMode == HomeMode.FOCUS
            )
        }

        // Shuffle action button ("BREAK PATTERN")
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(if (isShuffleEnabled) ShellDarkAccent else MaterialTheme.colorScheme.surfaceVariant)
                .clickable(enabled = isShuffleEnabled, onClick = onManualShuffle)
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .testTag("manual_shuffle_button"),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = "Shuffle Now",
                tint = if (isShuffleEnabled) MaterialTheme.colorScheme.onSecondary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(16.dp)
                    .rotate(rotation)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "BREAK PATTERN",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.sp
                ),
                color = if (isShuffleEnabled) MaterialTheme.colorScheme.onSecondary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Settings gear button
        IconButton(
            onClick = onOpenSettings,
            modifier = Modifier
                .size(36.dp)
                .testTag("settings_button")
        ) {
            Icon(
                imageVector = Icons.Default.Tune,
                contentDescription = "Settings",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun ModeBadge(
    label: String,
    isSelected: Boolean
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (isSelected) MaterialTheme.colorScheme.onBackground else androidx.compose.ui.graphics.Color.Transparent
            )
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                fontFamily = FontFamily.Monospace
            ),
            color = if (isSelected) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
