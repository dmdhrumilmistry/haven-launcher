package app.haven.launcher.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.haven.launcher.data.AppItem

/** A folder's icon: its first four apps in a 2x2 grid on a soft rounded tile, like stock Android. */
@Composable
fun FolderIcon(apps: List<AppItem>, size: Dp, modifier: Modifier = Modifier) {
    val gap = size * 0.06f
    val inner = (size - gap * 3) / 2
    // One badge for the whole folder (the total), not tiny ones on each mini icon.
    val badges = LocalBadges.current
    val total = apps.sumOf { badges[it.badgeKey()] ?: 0 }
    Box(modifier.size(size)) {
    Box(
        Modifier
            .size(size)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f), RoundedCornerShape(size * 0.3f))
            .padding(gap),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalBadges provides emptyMap()) {
            Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                apps.take(4).chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                        row.forEach { AppIcon(it, inner) }
                        if (row.size == 1) Spacer(Modifier.size(inner))
                    }
                }
            }
        }
    }
    IconBadge(total, size, Modifier.align(Alignment.TopEnd))
    }
}
