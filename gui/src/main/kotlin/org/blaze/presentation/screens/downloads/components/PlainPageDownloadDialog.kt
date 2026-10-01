package org.blaze.presentation.screens.downloads.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.blaze.i18n.blazeStrings
import org.blaze.presentation.components.BaseDialog
import org.blaze.presentation.components.StandardDialogButtons
import org.jetbrains.jewel.ui.component.Text

/**
 * Warning shown before Blaze fetches a page link that no installed handler can take apart.
 *
 * Blaze is perfectly capable of downloading *this* URL — it just isn't the file the user meant. A
 * hosted download page returns HTML, and until recently that HTML landed in the list with no word
 * about why it wouldn't open. Nothing here blocks the transfer: the user is told what proceeding
 * saves, and if they say yes then the page is exactly what they asked for.
 */
@Composable
fun PlainPageDownloadDialog(
    url: String,
    onDismiss: () -> Unit,
    onProceed: () -> Unit,
) {
    val strings = blazeStrings
    val pStrings = strings.downloads.dialogs.plainPage

    // A touch wider than the other dialogs: the link itself is the subject of the warning, and a
    // truncated "...share/abc" would leave the user guessing which URL they just approved.
    BaseDialog(onDismiss = onDismiss, width = 500.dp, title = pStrings.title) {
        Text(
            text = pStrings.message(url),
            maxLines = 4,
            overflow = TextOverflow.Ellipsis
        )

        AdvisoryCallout(text = pStrings.detail, tone = AdvisoryTone.WARNING)

        StandardDialogButtons(
            dismissText = strings.common.cancel,
            onDismiss = onDismiss,
            confirmText = pStrings.confirm,
            onConfirm = onProceed
        )
    }
}
