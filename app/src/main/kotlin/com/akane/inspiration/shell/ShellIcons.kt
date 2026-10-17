package com.akane.inspiration.shell

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The shell's one icon set: 24 unit outline glyphs with a 2 unit round stroke. No icon library is in the
 * version catalog, so these are drawn here; the tint comes from the navigation item, never from this file.
 */
internal object ShellIcons {
    /** Speedometer arc with a needle. */
    val Cockpit: ImageVector by lazy {
        glyph("Cockpit", "M4.9,18 A8.5,8.5 0 1 1 19.1,18", "M12,13 L15.5,8.5", "M12,13 m-0.5,0 a0.5,0.5 0 1 0 1,0 a0.5,0.5 0 1 0 -1,0")
    }

    /** Magnifier over the data. */
    val XRay: ImageVector by lazy {
        glyph("XRay", "M10.5,4 a6.5,6.5 0 1 0 0.01,0 Z", "M15.5,15.5 L20,20", "M7.5,10.5 H9 L10,8.5 L11.5,12.5 L12.5,10.5 H13.5")
    }

    /** Crescent moon. */
    val SleepLab: ImageVector by lazy {
        glyph("SleepLab", "M20,14.5 A8,8 0 1 1 9.5,4 A6.5,6.5 0 0 0 20,14.5 Z")
    }

    /** Battery with a bolt. */
    val Health: ImageVector by lazy {
        glyph(
            "Health",
            "M6,7 H17 a1,1 0 0 1 1,1 V16 a1,1 0 0 1 -1,1 H6 a1,1 0 0 1 -1,-1 V8 a1,1 0 0 1 1,-1 Z",
            "M20.5,10.5 V13.5",
            "M12.5,9 L10,12.5 H13.5 L11,16",
        )
    }

    /** Three sliders. */
    val Toolkit: ImageVector by lazy {
        glyph(
            "Toolkit",
            "M4,7 H8 M12,7 H20",
            "M10,7 m-2,0 a2,2 0 1 0 4,0 a2,2 0 1 0 -4,0",
            "M4,12 H14 M18,12 H20",
            "M16,12 m-2,0 a2,2 0 1 0 4,0 a2,2 0 1 0 -4,0",
            "M4,17 H6 M10,17 H20",
            "M8,17 m-2,0 a2,2 0 1 0 4,0 a2,2 0 1 0 -4,0",
        )
    }

    private fun glyph(name: String, vararg paths: String): ImageVector =
        ImageVector.Builder(name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .apply {
                paths.forEach { path ->
                    addPath(
                        pathData = addPathNodes(path),
                        stroke = SolidColor(Color.Black),
                        strokeLineWidth = 2f,
                        strokeLineCap = StrokeCap.Round,
                        strokeLineJoin = StrokeJoin.Round,
                    )
                }
            }
            .build()
}
