package org.blaze.presentation.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.jetbrains.jewel.foundation.theme.JewelTheme

internal object IdeColors {
    val isDark: Boolean
        @Composable get() = JewelTheme.globalColors.panelBackground.luminance() < 0.5f

    val accent: Color
        @Composable get() = if (isDark) Color(0xFF548AF7) else Color(0xFF3574F0)

    val success: Color
        @Composable get() = if (isDark) Color(0xFF5FB865) else Color(0xFF208A3C)

    val warning: Color
        @Composable get() = if (isDark) Color(0xFFF2C55C) else Color(0xFFA46704)

    val error: Color
        @Composable get() = JewelTheme.globalColors.text.error

    /** Subtle row hover: a translucent overlay of the text color works in both themes. */
    val hover: Color
        @Composable get() = JewelTheme.globalColors.text.normal.copy(alpha = 0.07f)
}
