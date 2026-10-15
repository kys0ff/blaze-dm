package org.blaze.presentation.application.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberDialogState
import org.blaze.i18n.blazeStrings
import org.blaze.presentation.components.AppMetrics
import org.blaze.security.ConsentRequest
import org.blaze.security.ConsentSubjectKind
import org.blaze.service.security.ConsentDecision
import org.island.theme.ui.IslandLayout
import org.island.ui.dialog.IslandDialogWindow
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text

/**
 * The app-wide security consent dialog: it renders the oldest pending [ConsentRequest] from
 * [org.blaze.security.ConsentService] — a third-party client wanting to drive the local API, or an
 * extension jar wanting to run — and answers it with the three decisions the service understands.
 *
 * The body deliberately spells out what accepting *means* (full control over downloads / in-process
 * code execution) before any button is offered, and the difference between "allow once" and "allow
 * always" is stated so the choice is informed rather than a reflex. Where the approval can be taken
 * back is pointed at, too (Settings → General → Security).
 *
 * The window is composed through the shell's [IslandDialogWindow] so it shares the exact same themed
 * caption / never-minimizable chrome as every other dialog. It is deliberately not closable: the
 * request can only be answered, so the close *request* is a no-op and the caption buttons are
 * grayed ([IslandDialogWindow.disableCaptionButtons]) - the user is never left having "dismissed" a
 * consent without reading it. The host raises the main frame before composing this dialog; as a
 * `DialogWindow` it inherits that frame as its owner, so it stays modal to Blaze and never enters
 * the taskbar.
 */
@Composable
fun ConsentDialogWindow(
    request: ConsentRequest,
    onDecision: (ConsentDecision) -> Unit,
) {
    val strings = blazeStrings.consent
    val isClient = request.kind == ConsentSubjectKind.EXTERNAL_CLIENT
    val title = if (isClient) strings.clientTitle else strings.extensionTitle
    val body = if (isClient) {
        strings.clientBody(request.subjectName, request.subjectDetail)
    } else {
        strings.extensionBody(request.subjectName)
    }
    val meaning = if (isClient) strings.clientMeaning else strings.extensionMeaning
    val infoStyle = JewelTheme.defaultTextStyle.copy(fontSize = 12.sp)
    // Manifest attribution / claims read off the jar without running its code; absent for clients.
    val byline = if (isClient) null else strings.extensionByline(request.author, request.version)

    val density = LocalDensity.current
    var height by remember { mutableStateOf(260.dp) }
    val state = rememberDialogState(
        position = WindowPosition(Alignment.Center),
        width = AppMetrics.ConsentDialogWidth,
        height = height,
    )
    // rememberDialogState treats its args as initial values only, so push updates in manually.
    LaunchedEffect(height) { state.size = DpSize(AppMetrics.ConsentDialogWidth, height) }

    IslandDialogWindow(
        // Not closable and not minimizable: the request can only be answered, and a dialog
        // iconification must never fold into the app's hide-to-tray behavior.
        onDismiss = { },
        state = state,
        title = title,
        disableCaptionButtons = true,
    ) {
        // The window height tracks the content's natural size, capped so a long consent message still
        // fits the screen. The scrollable viewport is the outer Column; the inner Column reports its own
        // laid-out height (unbounded *inside* the scroll, hence the true content height) so we can size
        // the window to it. A `wrapContentHeight(unbounded = true)` directly above `verticalScroll`
        // would measure the scroll container against an infinite max height, which Compose forbids -
        // that crashes the whole composition with an IllegalStateException.
        Column(
            modifier = Modifier
                .width(AppMetrics.ConsentDialogWidth)
                .height(height)
                .background(JewelTheme.globalColors.panelBackground)
                .verticalScroll(rememberScrollState()),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .onSizeChanged { height = (with(density) { it.height.toDp() } + 20.dp).coerceAtMost(480.dp) }
                    .padding(
                        horizontal = IslandLayout.DialogPaddingHorizontal,
                        vertical = IslandLayout.DialogPaddingVertical,
                    ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // The title lives in the window caption, so the body starts with the explanatory copy.
                Text(text = body, style = JewelTheme.defaultTextStyle)
                Text(
                    text = meaning,
                    style = JewelTheme.defaultTextStyle,
                    color = JewelTheme.globalColors.text.normal,
                )
                if (byline != null) {
                    Text(text = byline, style = infoStyle, color = JewelTheme.globalColors.text.info)
                }
                if (request.urlPatterns.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = strings.claimsLabel,
                            style = infoStyle,
                            color = JewelTheme.globalColors.text.info,
                        )
                        Text(
                            text = request.urlPatterns.joinToString(", "),
                            style = infoStyle,
                            color = JewelTheme.globalColors.text.info,
                        )
                    }
                }
                Text(text = strings.choiceHelp, style = infoStyle)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = strings.subjectLabel,
                        style = infoStyle,
                        color = JewelTheme.globalColors.text.info,
                    )
                    Text(
                        text = request.subjectDetail,
                        style = infoStyle,
                        color = JewelTheme.globalColors.text.info,
                    )
                }
                Text(
                    text = strings.settingsHint,
                    style = infoStyle,
                    color = JewelTheme.globalColors.text.info,
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(onClick = { onDecision(ConsentDecision.DENY) }) {
                        Text(strings.deny)
                    }
                    OutlinedButton(onClick = { onDecision(ConsentDecision.ALLOW_ONCE) }) {
                        Text(strings.allowOnce)
                    }
                    DefaultButton(onClick = { onDecision(ConsentDecision.ALLOW_ALWAYS) }) {
                        Text(strings.allowAlways)
                    }
                }
            }
        }
    }
}
