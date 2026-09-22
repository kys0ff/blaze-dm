package org.blaze.presentation.screens.downloads.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.blaze.domain.repository.PortableInfo
import org.blaze.domain.repository.PortableKind
import org.blaze.i18n.blazeStrings
import org.blaze.presentation.components.IdeDialog
import org.blaze.presentation.components.IdeDialogActions
import org.blaze.presentation.components.IdeDialogTitle
import org.blaze.presentation.util.formatSize
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Text

/**
 * Preview + confirmation for resuming a portable download that was moved from another PC (§12).
 *
 * Shows only safe, display-only fields from [info] — the source label, expected size and how much is
 * already present — and lets the user pick the resume destination before queueing the transfer. No
 * credential material is ever displayed; when the resource may require it, a note directs the user to
 * supply it on this machine.
 */
@OptIn(ExperimentalJewelApi::class)
@Composable
fun ImportPortableDialog(
    info: PortableInfo,
    destinationDir: String,
    busy: Boolean,
    onBrowseDestination: () -> Unit,
    onImport: () -> Unit,
    onDismiss: () -> Unit,
) {
    val strings = blazeStrings
    val p = strings.downloads.portable
    val secondary = JewelTheme.globalColors.text.info
    val hintStyle = JewelTheme.defaultTextStyle.copy(fontSize = 12.sp)

    val kindLine = when (info.kind) {
        PortableKind.HTTP -> p.kindHttp(info.suggestedName)
        PortableKind.TORRENT -> p.kindTorrent(info.suggestedName)
    }

    IdeDialog(onDismiss = onDismiss, width = 480.dp) {
        IdeDialogTitle(p.dialogTitle)
        Text(kindLine, maxLines = 1, overflow = TextOverflow.Ellipsis)

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            info.source?.let { Field(p.source, it) }
            Field(p.expectedSize, formatSize(info.totalBytes, strings))
            Field(p.available, formatSize(info.availableBytes, strings))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("${p.saveLocation}: ", style = hintStyle, color = secondary)
                Text(destinationDir, style = hintStyle, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                DefaultButton(onClick = onBrowseDestination) { Text(strings.common.browse) }
            }

            if (!info.resumable) {
                Text(p.staleWarning, style = hintStyle, color = JewelTheme.globalColors.text.error)
            }
            if (info.mayRequireCredentials) {
                Text(p.credentialsNote, style = hintStyle, color = secondary)
            }
        }

        IdeDialogActions(
            dismissText = strings.common.cancel,
            onDismiss = onDismiss,
            confirmText = p.importAction,
            onConfirm = onImport,
            confirmEnabled = info.resumable && !busy
        )
    }
}

@Composable
private fun Field(label: String, value: String) {
    val strings = blazeStrings
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = label,
            style = JewelTheme.defaultTextStyle.copy(fontSize = 12.sp),
            color = JewelTheme.globalColors.text.info,
            modifier = Modifier.weight(0.4f)
        )
        Text(
            text = value,
            style = JewelTheme.defaultTextStyle.copy(fontSize = 12.sp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(0.6f),
        )
    }
}
