package org.blaze.presentation.screens.downloads.components

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.blaze.download.api.HlsProtectionDto
import org.blaze.i18n.blazeStrings
import org.island.ui.dialog.IslandDialog
import org.island.ui.dialog.StandardDialogButtons
import org.jetbrains.jewel.ui.component.Text

/**
 * The warning Blaze raises when an HLS inspection finds credible signs of DRM or an *uncertain*
 * protection mechanism, before any download attempt starts.
 *
 * It is deliberately a choice, not a block: the presence of DRM indicators informs the user and lets
 * them decide, and "Continue" proceeds only with the normal, supported download workflow. Nothing here
 * circumvents DRM, defeats access controls, or extracts protected keys — acknowledging the warning
 * changes only what gets reported, never what Blaze is able to do.
 *
 * No external policy link is offered: the stream's provider cannot be reliably identified from the
 * playlist alone, and the strings forbid inventing URLs, so the dialog instead points the user to the
 * provider's own documentation.
 */
@Composable
fun DrmWarningDialog(
    protection: HlsProtectionDto?,
    onDismiss: () -> Unit,
    onContinue: () -> Unit,
) {
    val strings = blazeStrings
    val d = strings.downloads.dialogs.drmWarning

    // A wider dialog: the message is a paragraph the user must actually read before committing, and a
    // truncated one would turn an informed choice into a reflex click.
    IslandDialog(onDismiss = onDismiss, width = 520.dp, title = d.title) {
        Text(text = d.message, maxLines = 8, overflow = TextOverflow.Ellipsis)

        AdvisoryCallout(text = d.detail, tone = AdvisoryTone.WARNING)

        // "Uncertain" is a weaker signal than a confirmed indicator, so it is named as such rather than
        // asserting DRM is present. A confirmed one gets the plain advice line.
        if (protection == HlsProtectionDto.Uncertain) {
            Text(
                text = d.uncertainNote,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Text(text = d.advice, modifier = Modifier.padding(top = 2.dp))

        StandardDialogButtons(
            dismissText = strings.common.cancel,
            onDismiss = onDismiss,
            confirmText = d.continueAction,
            onConfirm = onContinue,
        )
    }
}
