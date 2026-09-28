package org.blaze.presentation.screens.downloads.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.blaze.i18n.blazeStrings
import org.blaze.presentation.icons.BlazeFlame
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Link
import org.jetbrains.jewel.ui.component.Text

/**
 * The first screen a new user meets. Beyond the call to action it teaches the other two ways in:
 * pasting a link straight from the clipboard, and dropping a torrent / magnet / URL onto the window.
 * The Blaze flame anchors it visually without tinting (it is a multi-colour icon).
 */
@Composable
fun DownloadsEmptyState(
    onAddDownload: () -> Unit,
    modifier: Modifier = Modifier,
    onPaste: (() -> Unit)? = null,
) {
    val strings = blazeStrings
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Image(
                imageVector = BlazeFlame,
                contentDescription = null,
                modifier = Modifier.size(96.dp),
            )
            Text(
                text = strings.downloads.emptyTitle,
                style = JewelTheme.defaultTextStyle.copy(fontSize = 16.sp),
                color = JewelTheme.globalColors.text.normal,
            )
            Text(
                text = strings.downloads.emptyDescription,
                style = JewelTheme.defaultTextStyle.copy(fontSize = 13.sp),
                color = JewelTheme.globalColors.text.info,
                textAlign = TextAlign.Center,
            )
            DefaultButton(onClick = onAddDownload) {
                Text(strings.downloads.emptyAction)
            }
            if (onPaste != null) {
                Link(text = strings.downloads.emptyPaste, onClick = onPaste)
            }
            Text(
                text = strings.downloads.emptyDropHint,
                style = JewelTheme.defaultTextStyle.copy(fontSize = 12.sp),
                color = Color.Gray,
                textAlign = TextAlign.Center,
            )
        }
    }
}
