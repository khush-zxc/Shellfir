package com.example.core.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.ShellDarkAccent
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ShellfriClock(
    shuffledAppCount: Int,
    isShuffleActive: Boolean,
    onClockClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var currentTime by remember { mutableStateOf(Date()) }

    LaunchedEffect(Unit) {
        while (true) {
            currentTime = Date()
            delay(1000L)
        }
    }

    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val timeString = timeFormat.format(currentTime)
    val dateFormat = remember { SimpleDateFormat("EEE, MMM dd", Locale.getDefault()) }
    val dateString = dateFormat.format(currentTime).uppercase()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClockClick)
            .testTag("shellfri_clock_header"),
        horizontalAlignment = Alignment.Start
    ) {
        // Digital clock with dot-matrix styling
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = timeString,
                style = MaterialTheme.typography.displayLarge.copy(
                    fontSize = 54.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp,
                    fontFamily = FontFamily.Monospace
                ),
                color = MaterialTheme.colorScheme.onBackground
            )

            Spacer(modifier = Modifier.width(12.dp))

            // Signature Nothing-style red dot matrix accent
            DotMatrixGlyph(
                color = if (isShuffleActive) ShellDarkAccent else MaterialTheme.colorScheme.outline
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Uppercase date and status tagline
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = dateString,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.2.sp
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text(
                text = " • ",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text(
                text = if (shuffledAppCount > 0) "$shuffledAppCount SHUFFLED" else "NO APPS SHUFFLED",
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace
                ),
                color = if (shuffledAppCount > 0) ShellDarkAccent else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun DotMatrixGlyph(
    color: Color,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier.size(16.dp)
    ) {
        val radius = 2.2.dp.toPx()
        val step = size.width / 2f
        // 2x2 dot matrix with the top-right dot highlighted
        drawCircle(
            color = color.copy(alpha = 0.4f),
            radius = radius,
            center = Offset(step * 0.4f, step * 0.4f)
        )
        drawCircle(
            color = color,
            radius = radius,
            center = Offset(step * 1.6f, step * 0.4f)
        )
        drawCircle(
            color = color.copy(alpha = 0.4f),
            radius = radius,
            center = Offset(step * 0.4f, step * 1.6f)
        )
        drawCircle(
            color = color.copy(alpha = 0.4f),
            radius = radius,
            center = Offset(step * 1.6f, step * 1.6f)
        )
    }
}
