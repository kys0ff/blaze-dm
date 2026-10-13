package org.blaze.presentation.screens.downloads.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.blaze.i18n.blazeStrings
import org.blaze.presentation.screens.downloads.components.add.ResolvedPick
import org.blaze.presentation.screens.downloads.components.add.pickResolvedLinks
import org.blaze.presentation.util.formatSize
import org.blaze.resolver.api.ResolvedLink
import org.island.ui.dialog.IslandDialog
import org.island.ui.dialog.StandardDialogButtons
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Checkbox
import org.jetbrains.jewel.ui.component.Text

/**
 * Shown when one handler extracted several downloadable links from a page. The user checks
 * the ones they want: a single tick continues the normal single-download review, several
 * expand into the batch review. "Download all" is the fast path for a mirror list.
 */
@OptIn(ExperimentalJewelApi::class)
@Composable
fun ResolvedLinksChoiceDialog(
    links: List<ResolvedLink>,
    onDismiss: () -> Unit,
    onConfirm: (ResolvedPick) -> Unit
) {
    val strings = blazeStrings
    val dStrings = strings.downloads.dialogs

    // Selected by direct URL (unique per resolved link); everything starts checked so the
    // common "download the whole mirror list" case is a single press away.
    val allKeys = links.map { it.directUrl }.toSet()
    var selectedKeys by remember { mutableStateOf(allKeys) }
    val selected = links.filter { it.directUrl in selectedKeys }
    val infoStyle = JewelTheme.defaultTextStyle.copy(fontSize = 12.sp)

    IslandDialog(onDismiss = onDismiss, title = dStrings.resolvedLinksTitle) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = dStrings.resolvedLinksPrompt,
                style = infoStyle,
                color = JewelTheme.globalColors.text.info
            )

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                links.forEach { link ->
                    val key = link.directUrl
                    val name = link.fileName ?: key.substringAfterLast('/').substringBefore('?').ifBlank { key }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Checkbox(
                            checked = key in selectedKeys,
                            onCheckedChange = { isChecked ->
                                selectedKeys = if (isChecked) selectedKeys + key else selectedKeys - key
                            },
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val size = link.sizeBytes?.let { formatSize(it, strings) }
                            if (size != null) {
                                Text(
                                    text = size,
                                    style = infoStyle,
                                    color = JewelTheme.globalColors.text.info
                                )
                            }
                        }
                    }
                }
            }

            Text(
                text = dStrings.selectedLinkCount(selected.size, links.size),
                style = infoStyle,
                color = JewelTheme.globalColors.text.info,
                modifier = Modifier.fillMaxWidth()
            )
        }

        StandardDialogButtons(
            dismissText = strings.common.cancel,
            onDismiss = onDismiss,
            // "Download all" is offered as the confirm when everything is already checked,
            // otherwise the plain download verb; either way the pick drives single vs batch.
            confirmText = if (selected.size == links.size) dStrings.downloadAllAction else dStrings.pickLinksAction,
            onConfirm = { onConfirm(pickResolvedLinks(selected)) },
            confirmEnabled = selected.isNotEmpty()
        )
    }
}
