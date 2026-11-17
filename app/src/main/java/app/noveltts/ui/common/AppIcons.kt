package app.noveltts.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 项目自绘的单色线性图标集（24dp 网格、1.8dp 笔画）。
 *
 * 设计依据见 `docs/UI_EFFECT_ATLAS.md` 的「图标规划」：整套图标保持同一笔画风格，
 * 不使用 emoji、Unicode 字符或临时手绘 SVG；不引入额外图标依赖。
 * 通用语义（菜单/关闭）由 [MenuCloseIcon] 承担，因为它需要状态动画。
 */
enum class AppIcon {
    TEXT,        // 工作台 / 文本
    FOLDER,      // 语音包
    PERSON,      // 角色规则
    SWAP,        // 替换规则
    GEAR,        // 设置
    TERMINAL,    // 诊断日志
    EXTERNAL,    // 跳转系统界面
    PLAY,        // 开始朗读 / 试听
    STOP,        // 停止朗读
    SEARCH,      // 搜索
    CLOSE,       // 关闭 / 清除
    FILTER,      // 筛选
    MORE,        // 溢出菜单
    DRAG,        // 拖动把手
    EDIT,        // 编辑
    DELETE,      // 删除
    ADD,         // 新建 / 添加
    CHEVRON_DOWN,
    CHEVRON_UP,
    CHEVRON_RIGHT, // 可进入下一级的行尾指示
    ARROW_BACK,    // 返回上一级
    COPY,          // 复制到剪贴板
    CHECK,
    GLOBE,       // 在线来源
    DOWNLOAD,    // 下载 / 导入
    UPLOAD,      // 备份导出
    REFRESH,     // 刷新
    WARNING,
    ERROR,
    INFO,
    SPEED,       // 语速
    VOLUME,      // 音量
    TUNE,        // 音调
    VOICE,       // 音色 / 朗读行为
    MUSIC,       // 背景音乐
    WIFI,        // 合成网络
}

/**
 * 绘制一个 [AppIcon]。
 *
 * 装饰性用法（同一行已有文字说明）不要传 [contentDescription]，让 TalkBack 只读文字；
 * 独立图标按钮必须传描述，且外层触控区域不小于 48dp。
 */
@Composable
fun AppIconGlyph(
    icon: AppIcon,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    size: Dp = 24.dp,
    contentDescription: String? = null,
) {
    val semanticsModifier = if (contentDescription == null) {
        Modifier
    } else {
        Modifier.semantics { this.contentDescription = contentDescription }
    }
    Canvas(modifier = modifier.size(size).then(semanticsModifier)) {
        val factor = this.size.minDimension / 24f
        scale(factor, factor, pivot = Offset.Zero) { drawGlyph(icon, tint) }
    }
}

private fun DrawScope.drawGlyph(icon: AppIcon, c: Color) {
    when (icon) {
        AppIcon.TEXT -> {
            stroke(c, 4f, 6.5f, 20f, 6.5f)
            stroke(c, 4f, 10.5f, 20f, 10.5f)
            stroke(c, 4f, 14.5f, 15f, 14.5f)
            stroke(c, 4f, 18f, 11f, 18f)
        }

        AppIcon.FOLDER -> path(
            c,
            Path().apply {
                moveTo(3.5f, 18.5f)
                lineTo(3.5f, 5.5f)
                lineTo(9f, 5.5f)
                lineTo(11f, 8f)
                lineTo(20.5f, 8f)
                lineTo(20.5f, 18.5f)
                close()
            },
        )

        AppIcon.PERSON -> {
            drawCircle(c, radius = 3.5f, center = Offset(12f, 8f), style = strokeStyle())
            path(
                c,
                Path().apply {
                    moveTo(4.5f, 19.5f)
                    quadraticTo(12f, 12.2f, 19.5f, 19.5f)
                },
            )
        }

        AppIcon.SWAP -> {
            stroke(c, 4f, 8f, 18f, 8f)
            stroke(c, 14.8f, 5.4f, 18f, 8f)
            stroke(c, 14.8f, 10.6f, 18f, 8f)
            stroke(c, 20f, 16f, 6f, 16f)
            stroke(c, 9.2f, 13.4f, 6f, 16f)
            stroke(c, 9.2f, 18.6f, 6f, 16f)
        }

        AppIcon.GEAR -> {
            drawCircle(c, radius = 4.2f, center = Offset(12f, 12f), style = strokeStyle())
            for (i in 0 until 8) {
                val angle = Math.toRadians(i * 45.0)
                val cos = kotlin.math.cos(angle).toFloat()
                val sin = kotlin.math.sin(angle).toFloat()
                stroke(c, 12f + cos * 6.2f, 12f + sin * 6.2f, 12f + cos * 8.6f, 12f + sin * 8.6f)
            }
        }

        AppIcon.TERMINAL -> {
            roundRect(c, 3.5f, 4.5f, 20.5f, 19.5f, 2.5f)
            path(
                c,
                Path().apply {
                    moveTo(7.5f, 9.5f)
                    lineTo(10.5f, 12f)
                    lineTo(7.5f, 14.5f)
                },
            )
            stroke(c, 12.5f, 15f, 16.5f, 15f)
        }

        AppIcon.EXTERNAL -> {
            path(
                c,
                Path().apply {
                    moveTo(11f, 4.5f)
                    lineTo(4.5f, 4.5f)
                    lineTo(4.5f, 19.5f)
                    lineTo(19.5f, 19.5f)
                    lineTo(19.5f, 13f)
                },
            )
            path(
                c,
                Path().apply {
                    moveTo(14f, 4.5f)
                    lineTo(19.5f, 4.5f)
                    lineTo(19.5f, 10f)
                },
            )
            stroke(c, 19.5f, 4.5f, 12f, 12f)
        }

        AppIcon.PLAY -> fill(
            c,
            Path().apply {
                moveTo(7.5f, 4.8f)
                lineTo(19.5f, 12f)
                lineTo(7.5f, 19.2f)
                close()
            },
        )

        AppIcon.STOP -> drawRoundRect(
            color = c,
            topLeft = Offset(6f, 6f),
            size = Size(12f, 12f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.2f, 2.2f),
        )

        AppIcon.SEARCH -> {
            drawCircle(c, radius = 6f, center = Offset(10.5f, 10.5f), style = strokeStyle())
            stroke(c, 15f, 15f, 20f, 20f)
        }

        AppIcon.CLOSE -> {
            stroke(c, 6f, 6f, 18f, 18f)
            stroke(c, 18f, 6f, 6f, 18f)
        }

        AppIcon.FILTER -> {
            stroke(c, 4f, 6f, 20f, 6f)
            stroke(c, 6.5f, 11f, 17.5f, 11f)
            stroke(c, 9.5f, 16f, 14.5f, 16f)
        }

        AppIcon.MORE -> {
            dot(c, 12f, 6f)
            dot(c, 12f, 12f)
            dot(c, 12f, 18f)
        }

        AppIcon.DRAG -> for (y in listOf(7f, 12f, 17f)) {
            dot(c, 9f, y)
            dot(c, 15f, y)
        }

        AppIcon.EDIT -> path(
            c,
            Path().apply {
                moveTo(4.5f, 19.5f)
                lineTo(5.4f, 15.4f)
                lineTo(15.8f, 5f)
                lineTo(19f, 8.2f)
                lineTo(8.6f, 18.6f)
                close()
            },
        )

        AppIcon.DELETE -> {
            stroke(c, 4.5f, 7f, 19.5f, 7f)
            path(
                c,
                Path().apply {
                    moveTo(9.6f, 7f)
                    lineTo(9.6f, 4.8f)
                    lineTo(14.4f, 4.8f)
                    lineTo(14.4f, 7f)
                },
            )
            path(
                c,
                Path().apply {
                    moveTo(6f, 7f)
                    lineTo(7.1f, 20f)
                    lineTo(16.9f, 20f)
                    lineTo(18f, 7f)
                },
            )
            stroke(c, 10.4f, 10.5f, 10.4f, 16.5f)
            stroke(c, 13.6f, 10.5f, 13.6f, 16.5f)
        }

        AppIcon.ADD -> {
            stroke(c, 12f, 5f, 12f, 19f)
            stroke(c, 5f, 12f, 19f, 12f)
        }

        AppIcon.CHEVRON_DOWN -> path(
            c,
            Path().apply {
                moveTo(6.5f, 9.5f)
                lineTo(12f, 15f)
                lineTo(17.5f, 9.5f)
            },
        )

        AppIcon.CHEVRON_UP -> path(
            c,
            Path().apply {
                moveTo(6.5f, 14.5f)
                lineTo(12f, 9f)
                lineTo(17.5f, 14.5f)
            },
        )

        AppIcon.CHEVRON_RIGHT -> path(
            c,
            Path().apply {
                moveTo(9.5f, 6.5f)
                lineTo(15f, 12f)
                lineTo(9.5f, 17.5f)
            },
        )

        AppIcon.ARROW_BACK -> {
            stroke(c, 19.5f, 12f, 5f, 12f)
            path(
                c,
                Path().apply {
                    moveTo(10.8f, 6.3f)
                    lineTo(5f, 12f)
                    lineTo(10.8f, 17.7f)
                },
            )
        }

        AppIcon.COPY -> {
            // 后页只画左上两条边，前页留出压盖关系。
            path(
                c,
                Path().apply {
                    moveTo(15.6f, 4.6f)
                    lineTo(6.6f, 4.6f)
                    quadraticTo(4.6f, 4.6f, 4.6f, 6.6f)
                    lineTo(4.6f, 15.6f)
                },
            )
            roundRect(c, 8.4f, 8.4f, 19.4f, 19.4f, 2.4f)
        }

        AppIcon.CHECK -> path(
            c,
            Path().apply {
                moveTo(5f, 12.5f)
                lineTo(10f, 17.5f)
                lineTo(19f, 7f)
            },
        )

        AppIcon.GLOBE -> {
            drawCircle(c, radius = 8.5f, center = Offset(12f, 12f), style = strokeStyle())
            drawOval(
                color = c,
                topLeft = Offset(8f, 3.5f),
                size = Size(8f, 17f),
                style = strokeStyle(),
            )
            stroke(c, 3.5f, 12f, 20.5f, 12f)
        }

        AppIcon.DOWNLOAD -> {
            stroke(c, 12f, 4f, 12f, 15f)
            path(
                c,
                Path().apply {
                    moveTo(7.5f, 10.5f)
                    lineTo(12f, 15f)
                    lineTo(16.5f, 10.5f)
                },
            )
            stroke(c, 4.5f, 19.5f, 19.5f, 19.5f)
        }

        AppIcon.UPLOAD -> {
            stroke(c, 12f, 16f, 12f, 5.5f)
            path(
                c,
                Path().apply {
                    moveTo(7.5f, 10f)
                    lineTo(12f, 5.5f)
                    lineTo(16.5f, 10f)
                },
            )
            stroke(c, 4.5f, 19.5f, 19.5f, 19.5f)
        }

        AppIcon.REFRESH -> {
            drawArc(
                color = c,
                startAngle = 60f,
                sweepAngle = 280f,
                useCenter = false,
                topLeft = Offset(4.5f, 4.5f),
                size = Size(15f, 15f),
                style = strokeStyle(),
            )
            path(
                c,
                Path().apply {
                    moveTo(14.5f, 3.6f)
                    lineTo(18.6f, 6f)
                    lineTo(15.4f, 9.2f)
                },
            )
        }

        AppIcon.WARNING -> {
            path(
                c,
                Path().apply {
                    moveTo(12f, 4f)
                    lineTo(21f, 19.5f)
                    lineTo(3f, 19.5f)
                    close()
                },
            )
            stroke(c, 12f, 9.5f, 12f, 14.5f)
            dot(c, 12f, 17f, radius = 1.1f)
        }

        AppIcon.ERROR -> {
            drawCircle(c, radius = 8.5f, center = Offset(12f, 12f), style = strokeStyle())
            stroke(c, 12f, 7.5f, 12f, 13.5f)
            dot(c, 12f, 16.5f, radius = 1.1f)
        }

        AppIcon.INFO -> {
            drawCircle(c, radius = 8.5f, center = Offset(12f, 12f), style = strokeStyle())
            dot(c, 12f, 8f, radius = 1.1f)
            stroke(c, 12f, 11f, 12f, 16.5f)
        }

        AppIcon.SPEED -> {
            drawArc(
                color = c,
                startAngle = 190f,
                sweepAngle = 160f,
                useCenter = false,
                topLeft = Offset(4f, 6f),
                size = Size(16f, 16f),
                style = strokeStyle(),
            )
            stroke(c, 12f, 14f, 16.2f, 9.2f)
            dot(c, 12f, 14f, radius = 1.2f)
        }

        AppIcon.VOLUME -> {
            path(
                c,
                Path().apply {
                    moveTo(4f, 10f)
                    lineTo(7.5f, 10f)
                    lineTo(12f, 6.5f)
                    lineTo(12f, 17.5f)
                    lineTo(7.5f, 14f)
                    lineTo(4f, 14f)
                    close()
                },
            )
            drawArc(
                color = c,
                startAngle = -50f,
                sweepAngle = 100f,
                useCenter = false,
                topLeft = Offset(12.5f, 8f),
                size = Size(6f, 8f),
                style = strokeStyle(),
            )
            drawArc(
                color = c,
                startAngle = -50f,
                sweepAngle = 100f,
                useCenter = false,
                topLeft = Offset(12.5f, 6f),
                size = Size(9f, 12f),
                style = strokeStyle(),
            )
        }

        AppIcon.TUNE -> {
            for (x in listOf(7f, 12f, 17f)) stroke(c, x, 5f, x, 19f)
            knob(c, 7f, 9f)
            knob(c, 12f, 15f)
            knob(c, 17f, 11f)
        }

        AppIcon.VOICE -> {
            drawRoundRect(
                color = c,
                topLeft = Offset(9f, 3.5f),
                size = Size(6f, 9.5f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f, 3f),
                style = strokeStyle(),
            )
            path(
                c,
                Path().apply {
                    moveTo(7.5f, 11f)
                    quadraticTo(12f, 17.8f, 16.5f, 11f)
                },
            )
            stroke(c, 12f, 17f, 12f, 20f)
            stroke(c, 9f, 20f, 15f, 20f)
        }

        AppIcon.MUSIC -> {
            stroke(c, 15f, 5f, 15f, 16f)
            path(
                c,
                Path().apply {
                    moveTo(15f, 5f)
                    quadraticTo(19f, 6f, 19f, 10f)
                },
            )
            drawCircle(c, radius = 2.6f, center = Offset(12.4f, 16.6f))
        }

        AppIcon.WIFI -> {
            drawArc(
                color = c,
                startAngle = 215f,
                sweepAngle = 110f,
                useCenter = false,
                topLeft = Offset(8.5f, 13f),
                size = Size(7f, 7f),
                style = strokeStyle(),
            )
            drawArc(
                color = c,
                startAngle = 215f,
                sweepAngle = 110f,
                useCenter = false,
                topLeft = Offset(5.5f, 10f),
                size = Size(13f, 13f),
                style = strokeStyle(),
            )
            drawArc(
                color = c,
                startAngle = 215f,
                sweepAngle = 110f,
                useCenter = false,
                topLeft = Offset(2.5f, 7f),
                size = Size(19f, 19f),
                style = strokeStyle(),
            )
            dot(c, 12f, 18.6f, radius = 1.2f)
        }
    }
}

private fun DrawScope.strokeStyle(): Stroke = Stroke(width = 1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round)

private fun DrawScope.stroke(c: Color, x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(
    color = c,
    start = Offset(x1, y1),
    end = Offset(x2, y2),
    strokeWidth = 1.8f,
    cap = StrokeCap.Round,
)

private fun DrawScope.path(c: Color, path: Path) = drawPath(
    path = path,
    color = c,
    style = Stroke(width = 1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round),
)

private fun DrawScope.fill(c: Color, path: Path) = drawPath(path = path, color = c)

private fun DrawScope.roundRect(c: Color, l: Float, t: Float, r: Float, b: Float, radius: Float) = drawRoundRect(
    color = c,
    topLeft = Offset(l, t),
    size = Size(r - l, b - t),
    cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius),
    style = strokeStyle(),
)

private fun DrawScope.dot(c: Color, x: Float, y: Float, radius: Float = 1.6f) =
    drawCircle(c, radius = radius, center = Offset(x, y))

private fun DrawScope.knob(c: Color, x: Float, y: Float) {
    drawCircle(Color.Transparent, radius = 2.2f, center = Offset(x, y))
    drawCircle(c, radius = 2.2f, center = Offset(x, y))
}
