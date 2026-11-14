package com.example.core.components

import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.model.AppItem
import com.example.domain.model.BlurLevel
import com.example.ui.theme.ShellDarkAccent

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppIconItem(
    app: AppItem,
    iconBitmap: ImageBitmap?,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    modifier: Modifier = Modifier,
    iconSize: Dp = 54.dp,
    showLabel: Boolean = true,
    isShuffling: Boolean = false,
    blurLevel: BlurLevel = BlurLevel.DISABLED,
    enableBuiltInClick: Boolean = true,
    isDraggedSource: Boolean = false,
    isDragPreview: Boolean = false
) {
    val scale by animateFloatAsState(
        targetValue = when {
            isDragPreview -> 1.08f
            isShuffling && app.isShuffled -> 0.88f
            else -> 1f
        },
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 400f),
        label = "icon_scale"
    )
    val contentAlpha by animateFloatAsState(
        targetValue = if (isDraggedSource) 0.25f else 1f,
        animationSpec = spring(stiffness = 500f),
        label = "icon_drag_alpha"
    )

    val interactiveModifier = if (enableBuiltInClick) {
        Modifier.combinedClickable(
            onClick = onClick,
            onLongClick = onLongClick
        )
    } else {
        Modifier
    }
    val tagModifier = if (isDragPreview) {
        Modifier
    } else {
        Modifier.testTag("app_item_${app.componentKey}")
    }

    Column(
        modifier = modifier
            .scale(scale)
            .alpha(contentAlpha)
            .then(interactiveModifier)
            .padding(vertical = 6.dp, horizontal = 4.dp)
            .then(tagModifier),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val blurModifier = if (blurLevel != BlurLevel.DISABLED && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Modifier.blur(blurLevel.dpValue.dp)
        } else {
            Modifier
        }

        Box(
            modifier = Modifier
                .size(iconSize)
                .then(blurModifier),
            contentAlignment = Alignment.Center
        ) {
            if (iconBitmap != null) {
                Image(
                    bitmap = iconBitmap,
                    contentDescription = app.label,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(16.dp))
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = app.label.take(1).uppercase(),
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontFamily = FontFamily.Monospace
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Indicator for shuffled app (Nothing OS Red Dot)
            if (app.isShuffled) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(2.dp)
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(ShellDarkAccent)
                )
            }

            // Indicator for fixed app
            if (app.isFixed) {
                Canvas(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(2.dp)
                        .size(8.dp)
                ) {
                    drawCircle(
                        color = Color.White.copy(alpha = 0.8f),
                        radius = size.width / 2f
                    )
                }
            }
        }

        if (showLabel) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = app.label,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 11.5.sp,
                    letterSpacing = 0.2.sp
                ),
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(68.dp)
            )
        }
    }
}
