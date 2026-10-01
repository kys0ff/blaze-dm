package org.blaze.presentation.application.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberDialogState
import org.blaze.i18n.blazeStrings
import org.blaze.presentation.components.BlazeLayout
import org.blaze.presentation.components.preventDialogMinimize
import org.blaze.security.ConsentRequest
import org.blaze.security.ConsentSubjectKind
import org.blaze.service.security.ConsentDecision
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import java.awt.Component
import java.awt.Container
import javax.swing.JButton
import javax.swing.JRootPane

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
 * The window is not closable: the request can only be answered. The native close button is disabled
 * (best-effort, when Swing paints the decorations) and the close *request* is a no-op, so the user
 * is never left having "dismissed" a consent without reading it. The host raises the main frame
 * before composing this dialog; as a `DialogWindow` it inherits that frame as its owner, so it
 * stays modal to Blaze and never enters the taskbar.
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

    val density = LocalDensity.current
    var height by remember { mutableStateOf(260.dp) }
    val state = rememberDialogState(
        position = WindowPosition(Alignment.Center),
        width = BlazeLayout.ConsentDialogWidth,
        height = height,
    )
    // rememberDialogState treats its args as initial values only, so push updates in manually.
    LaunchedEffect(height) { state.size = DpSize(BlazeLayout.ConsentDialogWidth, height) }

    DialogWindow(
        onCloseRequest = { },
        state = state,
        title = title,
        resizable = false,
    ) {
        // Not closable and not minimizable: the request can only be answered, and a dialog
        // iconification must never fold into the app's hide-to-tray behavior.
        preventDialogMinimize()

        LaunchedEffect(window) {
            window.isResizable = false
            disableTitlePaneButtons(window.rootPane)
        }

        // The window height tracks the content's natural size, capped so a long consent message still
        // fits the screen. The scrollable viewport is the outer Column; the inner Column reports its own
        // laid-out height (unbounded *inside* the scroll, hence the true content height) so we can size
        // the window to it. The previous `wrapContentHeight(unbounded = true)` sat directly above
        // `verticalScroll`, so it measured the scroll container against an infinity max height, which
        // Compose forbids - that crashed the whole composition with an IllegalStateException.
        Column(
            modifier = Modifier
                .width(BlazeLayout.ConsentDialogWidth)
                .height(height)
                .background(JewelTheme.globalColors.panelBackground)
                .verticalScroll(rememberScrollState()),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .onSizeChanged { height = (with(density) { it.height.toDp() } + 20.dp).coerceAtMost(480.dp) }
                    .padding(
                        horizontal = BlazeLayout.DialogPaddingHorizontal,
                        vertical = BlazeLayout.DialogPaddingVertical,
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

/**
 * Best-effort visual graying of the caption's buttons when the L&F paints the decorations itself:
 * Metal's `BasicTitlePane` does not expose them as public API (and sits inside the layered pane's
 * glass layer, not among the root pane's direct children), so the tree is walked through the public
 * `components` arrays and every button under a title-pane node is disabled. What actually *prevents*
 * a close is the dialog's no-op `onCloseRequest`, which a window-manager close also routes through;
 * this only keeps that button from looking live on a run that cannot honor it.
 */
private fun disableTitlePaneButtons(rootPane: JRootPane) {
    fun disableButtons(component: Component) {
        when (component) {
            is JButton -> component.isEnabled = false
            is Container -> component.components.forEach(::disableButtons)
        }
    }
    fun walk(component: Component) {
        if (component.javaClass.name.endsWith("BasicTitlePane")) {
            disableButtons(component)
        } else if (component is Container) {
            component.components.forEach(::walk)
        }
    }
    rootPane.components.forEach(::walk)
}
