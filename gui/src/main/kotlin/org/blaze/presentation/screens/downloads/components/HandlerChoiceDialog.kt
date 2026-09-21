package org.blaze.presentation.screens.downloads.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.blaze.i18n.blazeStrings
import org.blaze.presentation.components.ExtensionIcon
import org.blaze.presentation.components.IdeDialog
import org.blaze.presentation.components.IdeDialogActions
import org.blaze.presentation.components.IdeDialogTitle
import org.blaze.resolver.core.LinkResolverRegistry
import org.blaze.resolver.core.LoadedResolver
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.RadioButtonRow
import org.jetbrains.jewel.ui.component.Text
import org.koin.compose.koinInject

/**
 * JetBrains-style picker shown when several installed handlers can process a page link.
 * The user chooses one and the caller resolves the link with it. A single-match case never
 * reaches this dialog — [AddDownloadDialog] picks the only candidate and shows an inline
 * notice instead.
 */
@OptIn(ExperimentalJewelApi::class)
@Composable
fun HandlerChoiceDialog(
    handlers: List<LoadedResolver>,
    onDismiss: () -> Unit,
    onConfirm: (LoadedResolver) -> Unit
) {
    val strings = blazeStrings
    val dStrings = strings.downloads.dialogs
    val registry = koinInject<LinkResolverRegistry>()

    var selected by remember { mutableStateOf(handlers.firstOrNull()?.resolver?.id) }
    val selectedHandler = handlers.firstOrNull { it.resolver.id == selected }

    IdeDialog(onDismiss = onDismiss) {
        IdeDialogTitle(dStrings.handlerChoiceTitle)
        Text(
            text = dStrings.handlerChoicePrompt,
            style = JewelTheme.defaultTextStyle.copy(fontSize = 12.sp),
            color = JewelTheme.globalColors.text.info
        )

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            handlers.forEach { handler ->
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        ExtensionIcon(handler, registry, size = 16.dp)
                        RadioButtonRow(
                            text = handler.resolver.displayName,
                            selected = handler.resolver.id == selected,
                            onClick = { selected = handler.resolver.id },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Text(
                        text = handler.resolver.description,
                        style = JewelTheme.defaultTextStyle.copy(fontSize = 12.sp),
                        color = JewelTheme.globalColors.text.info,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 27.dp + 16.dp + 6.dp)
                    )
                }
            }
        }

        IdeDialogActions(
            dismissText = strings.common.cancel,
            onDismiss = onDismiss,
            confirmText = dStrings.resolveAction,
            onConfirm = { selectedHandler?.let(onConfirm) },
            confirmEnabled = selectedHandler != null
        )
    }
}
