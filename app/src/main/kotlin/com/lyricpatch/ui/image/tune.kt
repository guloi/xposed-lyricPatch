package com.lyricpatch.ui.image

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

val TuneIcon: ImageVector = ImageVector.Builder(
    name = "Tune",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    path(fill = SolidColor(Color.Black)) {
        moveTo(3f, 17f); horizontalLineToRelative(6f); verticalLineToRelative(2f)
        horizontalLineTo(3f); close()
        moveTo(3f, 5f); verticalLineToRelative(2f); horizontalLineToRelative(10f)
        verticalLineTo(5f); horizontalLineTo(3f); close()
        moveTo(13f, 21f); verticalLineToRelative(-2f); horizontalLineToRelative(8f)
        verticalLineToRelative(-2f); horizontalLineToRelative(-8f); verticalLineToRelative(-2f)
        horizontalLineToRelative(-2f); verticalLineToRelative(6f); horizontalLineToRelative(2f); close()
        moveTo(7f, 9f); verticalLineToRelative(2f); horizontalLineTo(3f)
        verticalLineToRelative(2f); horizontalLineToRelative(4f); verticalLineToRelative(2f)
        horizontalLineToRelative(2f); verticalLineTo(9f); horizontalLineTo(7f); close()
        moveTo(21f, 13f); verticalLineToRelative(-2f); horizontalLineTo(11f)
        verticalLineToRelative(2f); horizontalLineToRelative(10f); close()
        moveTo(15f, 9f); horizontalLineToRelative(2f); verticalLineTo(7f)
        horizontalLineToRelative(4f); verticalLineTo(5f); horizontalLineToRelative(-4f)
        verticalLineTo(3f); horizontalLineToRelative(-2f); verticalLineToRelative(6f); close()
    }
}.build()