package org.island.ui.dialog

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import org.island.foundation.DialogButton
import org.island.foundation.dialogButtonOrder
import org.island.theme.ui.IslandLayout
import org.island.ui.window.applyWindowsTitleBarTheme
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import javax.swing.JButton
import javax.swing.JRootPane

/**
 * The single source of truth for a themed OS dialog window in the Islands shell.
 *
 * Every dialog in the app - the simple message/confirm [IslandDialog] as well as richer hosts like
 * a Settings window or a consent prompt that supply their own body - is composed through this, so
 * the window chrome that must never diverge lives in exactly one place:
 *  - the Compose [DialogWindow] itself (real OS window, owned by the surrounding frame so it is
 *    document-modal and gets no taskbar entry);
 *  - [preventDialogMinimize] - an iconified dialog would fold the owner frame into a group minimize
 *    and hide the whole app;
 *  - [applyWindowsTitleBarTheme] pinned to the panel background, so the native Windows caption reads
 *    as one themed surface with the body instead of an off-theme bar over a Jewel panel;
 *  - the optional [minimumSizePx] and, for non-closable prompts, [disableCaptionButtons] (grays the
 *    OS close/minimize/maximize glyphs when the L&F paints the decorations).
 *
 * The [content] runs in the [DialogWindowScope] so a host can reach [DialogWindowScope.window]
 * (min-size, insets, root pane) while composing its own body; simple dialogs wrap that body in
 * [IslandDialog]'s padded, auto-sizing, Escape-dismissable surface instead.
 *
 * @param onDismiss the close request handler; pass a no-op for a dialog that must only be answered
 *   through its buttons (paired with [disableCaptionButtons]).
 * @param state hoisted dialog state so a host can keep the user's resize/drag across close+reopen.
 * @param resizable whether the OS frame may be dragged-resized (Settings true, message dialogs false).
 * @param minimumSizePx the frame's minimum size in pixels, applied once the peer exists.
 * @param disableCaptionButtons best-effort graying of the caption buttons for a non-closable prompt.
 */
@Composable
fun IslandDialogWindow(
    onDismiss: () -> Unit,
    state: DialogState,
    title: String,
    resizable: Boolean = false,
    minimumSizePx: Pair<Int, Int>? = null,
    disableCaptionButtons: Boolean = false,
    content: @Composable DialogWindowScope.() -> Unit,
) {
    DialogWindow(
        onCloseRequest = onDismiss,
        state = state,
        title = title,
        resizable = resizable,
    ) {
        // A dialog is never minimizable: an iconified dialog would drag the owner frame into the
        // group minimize and hide the whole app to the tray.
        preventDialogMinimize()

        // Paint the native Windows caption with the panel background so the dialog reads as a
        // single themed surface rather than a Jewel panel under an off-theme bar. Silent no-op
        // everywhere but Windows; dark/light and text contrast are derived from the color there.
        applyWindowsTitleBarTheme(JewelTheme.globalColors.panelBackground)

        LaunchedEffect(window, minimumSizePx, disableCaptionButtons) {
            minimumSizePx?.let { window.minimumSize = Dimension(it.first, it.second) }
            if (disableCaptionButtons) {
                window.isResizable = false
                disableTitlePaneButtons(window.rootPane)
            }
        }

        content()
    }
}

@Composable
fun IslandDialog(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 460.dp,
    height: Dp? = null,
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(12.dp),
    requestFocus: Boolean = true,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val density = LocalDensity.current

    var contentHeight by remember { mutableStateOf(0.dp) }

    val state = rememberDialogState(
        position = WindowPosition(Alignment.Center),
        size = DpSize(width, height ?: 230.dp),
    )

    if (requestFocus) {
        LaunchedEffect(Unit) {
            focusRequester.requestFocus()
        }
    }

    IslandDialogWindow(
        onDismiss = onDismiss,
        state = state,
        title = title.orEmpty(),
    ) {
        LaunchedEffect(width, height, contentHeight) {
            if (height != null) {
                state.size = DpSize(width, height)
            } else if (contentHeight > 0.dp) {
                val decorations = (window.insets.top + window.insets.bottom).dp
                state.size = DpSize(width, contentHeight + decorations)
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(JewelTheme.globalColors.panelBackground)
                .focusRequester(focusRequester)
                .onPreviewKeyEvent { event ->
                    if (
                        event.type == KeyEventType.KeyDown &&
                        event.key == Key.Escape
                    ) {
                        onDismiss()
                        true
                    } else {
                        false
                    }
                }
                .focusable(),
        ) {
            Column(
                modifier = modifier
                    .fillMaxWidth()
                    .wrapContentHeight(
                        align = Alignment.Top,
                        unbounded = true,
                    )
                    .onSizeChanged { size ->
                        val newHeight = with(density) {
                            size.height.toDp()
                        }
                        if (newHeight != contentHeight) {
                            contentHeight = newHeight
                        }
                    }
                    .padding(
                        horizontal = IslandLayout.DialogPaddingHorizontal,
                        vertical = IslandLayout.DialogPaddingVertical,
                    ),
                verticalArrangement = verticalArrangement,
                content = content,
            )
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

/**
 * Right-aligned dialog button bar: a secondary (dismiss) action followed by the default
 * (confirm) action. An optional [leading] slot (e.g. a "don't ask again" checkbox) is
 * pinned to the start of the row.
 */
@Composable
fun StandardDialogButtons(
    dismissText: String,
    onDismiss: () -> Unit,
    confirmText: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    confirmEnabled: Boolean = true,
    leading: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically
    ) {
        leading()
        Spacer(Modifier.weight(1f))
        dialogButtonOrder(hasApply = false).forEach { button ->
            when (button) {
                DialogButton.Ok -> DefaultButton(
                    onClick = onConfirm,
                    enabled = confirmEnabled
                ) {
                    Text(confirmText)
                }

                DialogButton.Cancel -> OutlinedButton(onClick = onDismiss) {
                    Text(dismissText)
                }

                DialogButton.Apply -> Unit
            }
        }
    }
}
