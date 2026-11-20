package com.elmdash.app.phone

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Lightweight Material-style vector icons without pulling in the massive 40MB material-icons-extended JAR.
 */
object EvIcons {

    private fun icon(name: String, block: ImageVector.Builder.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply(block).build()

    val Dashboard: ImageVector by lazy {
        icon("Dashboard") {
            path(fill = SolidColor(Color.White)) {
                moveTo(3f, 13f); horizontalLineTo(11f); verticalLineTo(3f); horizontalLineTo(3f); close()
                moveTo(3f, 21f); horizontalLineTo(11f); verticalLineTo(15f); horizontalLineTo(3f); close()
                moveTo(13f, 21f); horizontalLineTo(21f); verticalLineTo(11f); horizontalLineTo(13f); close()
                moveTo(13f, 3f); verticalLineTo(9f); horizontalLineTo(21f); verticalLineTo(3f); close()
            }
        }
    }

    val Route: ImageVector by lazy {
        icon("Route") {
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2.2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(6f, 18f); verticalLineTo(9f)
                curveTo(6f, 6.8f, 7.8f, 5f, 10f, 5f)
                curveTo(12.2f, 5f, 14f, 6.8f, 14f, 9f)
                verticalLineTo(15f)
                curveTo(14f, 17.2f, 15.8f, 19f, 18f, 19f)
                verticalLineTo(6f)
            }
            path(fill = SolidColor(Color.White)) {
                moveTo(6f, 18f); arcTo(2.5f, 2.5f, 0f, true, true, 5.99f, 18f); close()
                moveTo(18f, 6f); arcTo(2.5f, 2.5f, 0f, true, true, 17.99f, 6f); close()
            }
        }
    }

    val MonitorHeart: ImageVector by lazy {
        icon("MonitorHeart") {
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2.2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(3f, 12f); horizontalLineTo(7f); lineTo(10f, 5f); lineTo(14f, 19f); lineTo(17f, 12f); horizontalLineTo(21f)
            }
        }
    }

    val Bluetooth: ImageVector by lazy {
        icon("Bluetooth") {
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2.1f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(7f, 7f); lineTo(17f, 17f); lineTo(12f, 21f); verticalLineTo(3f); lineTo(17f, 7f); lineTo(7f, 17f)
            }
        }
    }

    val Fullscreen: ImageVector by lazy {
        icon("Fullscreen") {
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2.2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(4f, 9f); verticalLineTo(4f); horizontalLineTo(9f)
                moveTo(20f, 9f); verticalLineTo(4f); horizontalLineTo(15f)
                moveTo(4f, 15f); verticalLineTo(20f); horizontalLineTo(9f)
                moveTo(20f, 15f); verticalLineTo(20f); horizontalLineTo(15f)
            }
        }
    }

    val Bookmark: ImageVector by lazy {
        icon("Bookmark") {
            path(fill = SolidColor(Color.White)) {
                moveTo(6f, 4f); horizontalLineTo(18f); verticalLineTo(21f); lineTo(12f, 17f); lineTo(6f, 21f); close()
            }
        }
    }

    val Visibility: ImageVector by lazy {
        icon("Visibility") {
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2.0f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(2f, 12f); quadTo(12f, 4f, 22f, 12f); quadTo(12f, 20f, 2f, 12f)
            }
            path(fill = SolidColor(Color.White)) {
                moveTo(12f, 9.5f); arcTo(2.5f, 2.5f, 0f, true, true, 11.99f, 9.5f); close()
            }
        }
    }

    val VisibilityOff: ImageVector by lazy {
        icon("VisibilityOff") {
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2.0f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(3f, 3f); lineTo(21f, 21f)
                moveTo(4f, 12f); quadTo(12f, 6f, 20f, 12f)
            }
        }
    }

    val Pause: ImageVector by lazy {
        icon("Pause") {
            path(fill = SolidColor(Color.White)) {
                moveTo(6f, 5f); horizontalLineTo(10f); verticalLineTo(19f); horizontalLineTo(6f); close()
                moveTo(14f, 5f); horizontalLineTo(18f); verticalLineTo(19f); horizontalLineTo(14f); close()
            }
        }
    }

    val Terminal: ImageVector by lazy {
        icon("Terminal") {
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2.2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(4f, 7f); lineTo(10f, 12f); lineTo(4f, 17f)
                moveTo(12f, 17f); horizontalLineTo(20f)
            }
        }
    }

    val Car: ImageVector by lazy {
        icon("Car") {
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2.0f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(5f, 11f); lineTo(7f, 6f); horizontalLineTo(17f); lineTo(19f, 11f)
                moveTo(3f, 11f); horizontalLineTo(21f); verticalLineTo(17f); horizontalLineTo(3f); close()
                moveTo(6f, 17f); verticalLineTo(19f)
                moveTo(18f, 17f); verticalLineTo(19f)
            }
        }
    }

    val Lan: ImageVector by lazy {
        icon("Lan") {
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2.0f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(9f, 4f); horizontalLineTo(15f); verticalLineTo(8f); horizontalLineTo(9f); close()
                moveTo(4f, 16f); horizontalLineTo(10f); verticalLineTo(20f); horizontalLineTo(4f); close()
                moveTo(14f, 16f); horizontalLineTo(20f); verticalLineTo(20f); horizontalLineTo(14f); close()
                moveTo(12f, 8f); verticalLineTo(12f); moveTo(7f, 12f); horizontalLineTo(17f)
                moveTo(7f, 12f); verticalLineTo(16f); moveTo(17f, 12f); verticalLineTo(16f)
            }
        }
    }

    val Power: ImageVector by lazy {
        icon("Power") {
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2.2f,
                strokeLineCap = StrokeCap.Round
            ) {
                moveTo(12f, 3f); verticalLineTo(11f)
                moveTo(7.5f, 6.5f); arcTo(7.5f, 7.5f, 0f, true, false, 16.5f, 6.5f)
            }
        }
    }
}
