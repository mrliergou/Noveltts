package app.noveltts.ui.common

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme

/** Two-line menu mark that converges into a close symbol while a navigation drawer opens. */
@Composable
fun MenuCloseIcon(
    expanded: Boolean,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurface,
) {
    val progress by animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "menu-close-progress",
    )
    val description = if (expanded) "关闭导航菜单" else "打开导航菜单"
    val state = if (expanded) "已展开" else "已收起"

    Canvas(
        modifier = modifier
            .size(24.dp)
            .semantics {
                contentDescription = description
                stateDescription = state
            },
    ) {
        val iconScale = size.minDimension / 24f
        scale(iconScale, iconScale, pivot = Offset.Zero) {
            val topY = 8f + 4f * progress
            val bottomY = 16f - 4f * progress
            val strokeWidth = 1.8f

            rotate(degrees = 45f * progress, pivot = Offset(12f, topY)) {
                drawLine(
                    color = tint,
                    start = Offset(4f, topY),
                    end = Offset(20f, topY),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round,
                )
            }
            rotate(degrees = -45f * progress, pivot = Offset(12f, bottomY)) {
                drawLine(
                    color = tint,
                    start = Offset(4f, bottomY),
                    end = Offset(20f, bottomY),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}
