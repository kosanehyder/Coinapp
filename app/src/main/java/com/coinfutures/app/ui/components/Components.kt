package com.coinfutures.app.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.coinfutures.app.ui.l10n.LocalStrings
import com.coinfutures.app.ui.theme.AppTheme
import com.coinfutures.app.ui.theme.Numeric
import com.coinfutures.engine.ErrorCode
import com.coinfutures.engine.OverallStatus

data class StatusVisual(val color: Color, val container: Color, val icon: ImageVector, val label: String)

@Composable
fun statusVisual(status: OverallStatus): StatusVisual {
    val t = AppTheme.trade
    val s = LocalStrings.current
    return when (status) {
        OverallStatus.LONG_SETUP -> StatusVisual(t.long, t.longContainer, Icons.AutoMirrored.Filled.TrendingUp, s.status(status))
        OverallStatus.SHORT_SETUP -> StatusVisual(t.short, t.shortContainer, Icons.AutoMirrored.Filled.TrendingDown, s.status(status))
        OverallStatus.WAIT -> StatusVisual(t.wait, t.waitContainer, Icons.Filled.PauseCircle, s.status(status))
        OverallStatus.NO_TRADE -> StatusVisual(t.noTrade, t.noTradeContainer, Icons.Filled.Block, s.status(status))
        OverallStatus.DATA_UNAVAILABLE -> StatusVisual(t.unavailable, t.unavailableContainer, Icons.Filled.CloudOff, s.status(status))
    }
}

/** Icon + text + color: status never relies on color alone (FR05). */
@Composable
fun StatusBadge(status: OverallStatus, modifier: Modifier = Modifier, large: Boolean = false) {
    val v = statusVisual(status)
    Surface(color = v.container, contentColor = v.color, shape = RoundedCornerShape(50), modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = if (large) 14.dp else 10.dp, vertical = if (large) 8.dp else 4.dp),
        ) {
            Icon(v.icon, contentDescription = null, modifier = Modifier.size(if (large) 20.dp else 16.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                v.label.uppercase(),
                style = if (large) MaterialTheme.typography.labelLarge else MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    trailing: @Composable (RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth().animateContentSize(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                }
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).semantics { heading() })
                trailing?.invoke(this)
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
fun LabeledValue(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
    emphasize: Boolean = false,
    caption: String? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 6.dp).semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (caption != null) Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }
        Spacer(Modifier.width(12.dp))
        Text(
            value,
            style = (if (emphasize) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge).merge(Numeric),
            color = valueColor,
            fontWeight = if (emphasize) FontWeight.SemiBold else FontWeight.Medium,
            textAlign = TextAlign.End,
        )
    }
}

@Composable
fun Bullet(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant, marker: Color = MaterialTheme.colorScheme.primary) {
    Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 8.dp).size(6.dp).clip(CircleShape).background(marker))
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
    }
}

@Composable
fun Pill(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant, container: Color = MaterialTheme.colorScheme.surfaceContainerHighest, icon: ImageVector? = null) {
    Surface(color = container, contentColor = color, shape = RoundedCornerShape(50), modifier = modifier) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
            }
            Text(text, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(64.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(30.dp))
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 420.dp))
        if (action != null) {
            Spacer(Modifier.height(16.dp))
            action()
        }
    }
}

@Composable
fun ErrorState(code: ErrorCode, onRetry: (() -> Unit)?, modifier: Modifier = Modifier, extra: (@Composable () -> Unit)? = null) {
    val s = LocalStrings.current
    EmptyState(
        icon = Icons.Filled.ErrorOutline,
        title = code.name.replace('_', ' '),
        body = s.error(code),
        modifier = modifier,
        action = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (onRetry != null) Button(onClick = onRetry) { Text(s.retry) }
                extra?.invoke()
            }
        },
    )
}

/** Skeleton placeholder with a subtle pulse while content loads. */
@Composable
fun SkeletonBlock(height: Dp, modifier: Modifier = Modifier) {
    val tr = rememberInfiniteTransition(label = "skeleton")
    val a by tr.animateFloat(0.35f, 0.8f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse), label = "alpha")
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .alpha(a)
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
    )
}

@Composable
fun LoadingColumn(label: String) {
    Column(Modifier.fillMaxWidth().padding(16.dp).semantics { contentDescription = label }, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SkeletonBlock(96.dp)
        SkeletonBlock(160.dp)
        SkeletonBlock(240.dp)
        SkeletonBlock(120.dp)
    }
}

@Composable
fun SecondaryAction(text: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    OutlinedButton(onClick = onClick, modifier = modifier.height(48.dp), enabled = enabled) {
        Icon(icon, null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, maxLines = 1)
    }
}
