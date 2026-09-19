package org.blaze.presentation.screens.downloads.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.blaze.i18n.blazeStrings
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Link
import org.jetbrains.jewel.ui.component.Text

@Composable
fun DownloadsEmptyState(
    onAddDownload: () -> Unit,
    modifier: Modifier = Modifier
) {
    val strings = blazeStrings
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = strings.downloads.emptyTitle,
                color = JewelTheme.globalColors.text.info
            )
            Link(text = strings.downloads.emptyAction, onClick = onAddDownload)
        }
    }
}
