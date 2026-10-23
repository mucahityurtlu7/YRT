package com.yrt.music.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yrt.music.navigation.MainNavTabs
import com.yrt.music.navigation.Screen

@Composable
fun StreamingNavigationBar(
    selectedScreen: Screen?,
    onSelectScreen: (Screen) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.background)
            .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
            .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 8.dp),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            color = colors.surfaceContainerLow,
            border = BorderStroke(0.5.dp, colors.outlineVariant.copy(alpha = 0.65f)),
            shadowElevation = 6.dp,
        ) {
            Row(
                modifier = Modifier.selectableGroup().padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MainNavTabs.forEach { tab ->
                    val selected = selectedScreen == tab.screen
                    val tint by animateColorAsState(
                        targetValue = if (selected) colors.primary else colors.onSurfaceVariant,
                        label = "navigationTint",
                    )
                    val markerAlpha by animateFloatAsState(
                        targetValue = if (selected) 1f else 0f,
                        label = "navigationMarker",
                    )
                    val icon = if (selected) tab.icon else when (tab.screen) {
                        Screen.Search -> Icons.Outlined.Explore
                        Screen.Library -> Icons.Outlined.LibraryMusic
                        Screen.Downloads -> Icons.Outlined.Download
                        else -> tab.icon
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(16.dp))
                            .selectable(
                                selected = selected,
                                role = Role.Tab,
                                onClick = { onSelectScreen(tab.screen) },
                            )
                            .heightIn(min = 64.dp)
                            .padding(horizontal = 4.dp, vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically),
                    ) {
                        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
                        Text(
                            text = tab.title,
                            color = tint,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Box(
                            Modifier.width(16.dp).height(2.dp)
                                .background(colors.primary.copy(alpha = markerAlpha), CircleShape),
                        )
                    }
                }
            }
        }
    }
}
