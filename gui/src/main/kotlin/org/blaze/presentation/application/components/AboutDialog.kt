package org.blaze.presentation.application.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.blaze.APP_VERSION
import org.blaze.i18n.blazeStrings
import org.blaze.presentation.components.BaseDialog
import org.blaze.presentation.icons.BlazeFlame
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.typography

/**
 * The app's About dialog, opened from the title-bar hamburger menu. It states the identity, the
 * version and the one-line description — nothing more, and no action that duplicates the menu.
 *
 * The version comes from the shared [APP_VERSION] constant, also used by the plugin loader's
 * `minAppVersion` gate.
 */
@Composable
fun AboutDialog(onDismiss: () -> Unit) {
    val menu = blazeStrings.menu
    BaseDialog(onDismiss = onDismiss, width = 380.dp, title = menu.aboutTitle) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                BlazeFlame,
                contentDescription = null,
                tint = Color.Unspecified,
                modifier = Modifier.size(40.dp),
            )
            // The title lives in the window caption, so the identity row carries just the version.
            Text(
                text = "Blaze $APP_VERSION",
                style = JewelTheme.typography.small,
                color = JewelTheme.globalColors.text.info,
            )
        }
        Text(
            text = menu.aboutBody,
            style = JewelTheme.defaultTextStyle,
            color = JewelTheme.globalColors.text.normal,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            DefaultButton(onClick = onDismiss) {
                Text(blazeStrings.common.ok)
            }
        }
    }
}
