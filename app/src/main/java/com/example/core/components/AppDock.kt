package com.example.core.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.model.AppItem
import com.example.domain.repository.IconRepository
import com.example.domain.repository.rememberAppIcon

@Composable
fun AppDock(
    dockApps: List<AppItem>,
    iconRepository: IconRepository,
    onAppClick: (AppItem) -> Unit,
    onAppLongClick: (AppItem) -> Unit,
    onOpenDrawer: () -> Unit,
    modifier: Modifier = Modifier,
    dockCount: Int = dockApps.size.coerceIn(3, 7)
) {
    val effectiveCapacity = dockCount.coerceIn(3, 7)
    val visibleDockApps = dockApps
        .sortedBy { if (it.dockRank >= 0) it.dockRank else Int.MAX_VALUE }
        .take(effectiveCapacity)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Subtle app drawer pull indicator
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                .clickable(onClick = onOpenDrawer)
                .padding(horizontal = 16.dp, vertical = 5.dp)
                .testTag("open_drawer_button"),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = "Search & All Apps",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "SEARCH APPS",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.sp
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector = Icons.Default.KeyboardArrowUp,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp)
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Pinned Dock apps row
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val renderedCount = visibleDockApps.size.coerceAtLeast(1)
            val adaptiveIconSize = ((maxWidth / renderedCount) - 8.dp).coerceIn(32.dp, 50.dp)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("app_dock_row"),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                visibleDockApps.forEach { app ->
                    key(app.componentKey) {
                        val iconBitmap = rememberAppIcon(iconRepository, app.packageName, app.activityName)
                        AppIconItem(
                            app = app,
                            iconBitmap = iconBitmap,
                            onClick = { onAppClick(app) },
                            onLongClick = { onAppLongClick(app) },
                            iconSize = adaptiveIconSize,
                            showLabel = false
                        )
                    }
                }
            }
        }
    }
}
