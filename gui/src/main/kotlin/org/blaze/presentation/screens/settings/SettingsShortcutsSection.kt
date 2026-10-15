package org.blaze.presentation.screens.settings

import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.blaze.data.KeymapPersistence
import org.blaze.data.StoredKeyBinding
import org.blaze.i18n.BlazeStrings
import org.blaze.presentation.components.BlazeKeymap
import org.blaze.presentation.screens.settings.state.SettingsState
import org.island.theme.ui.IslandSemanticColors
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text

/**
 * Shortcuts category: every bindable [BlazeKeymap.Action] as a row printing its current chord, with a
 * recorder that captures a new one, plus per-row Unbind / Reset and a header-wide "Restore all defaults".
 *
 * Edits go through [SettingsEvent] into the buffered app-settings draft, so a rebind reaches the running
 * keymap (and every printed accelerator) only on Apply/OK and is discarded on Cancel — exactly like the
 * rest of the shell options. Conflict and "needs a modifier" errors are derived from the draft in
 * [SettingsState]; while either is present the row shows the message and Apply/OK stay blocked, so the
 * value is kept but the collision is surfaced rather than silently overwritten.
 *
 * Each row is registered with a literal id of the form `shortcuts.<action>` (not a loop over the bindable
 * actions): the Settings-search guard test reads those literal ids out of this source to prove the index
 * and the UI are the same object, so the ids have to appear spelled out here.
 */
@OptIn(ExperimentalJewelApi::class)
@Composable
internal fun ShortcutsSection(
    state: SettingsState,
    strings: BlazeStrings,
    onEvent: (SettingsEvent) -> Unit
) {
    val s = strings.settings.shortcuts
    val infoStyle = JewelTheme.defaultTextStyle.copy(fontSize = 12.sp)

    // Only one row records at a time; the id of the action currently armed, or null.
    var recordingAction by remember { mutableStateOf<String?>(null) }

    SettingsGroup("shortcuts", strings) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = s.description, style = infoStyle, color = JewelTheme.globalColors.text.info)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                OutlinedButton(
                    onClick = {
                        recordingAction = null
                        onEvent(SettingsEvent.RestoreAllKeyBindings)
                    },
                    modifier = Modifier.semantics { testTag = "settings_restore_all_shortcuts" },
                ) {
                    Text(s.restoreAllAction)
                }
            }
        }

        SettingRow("shortcuts.NewDownload") {
            ShortcutRow(BlazeKeymap.Action.NewDownload, s.newDownload, strings, state, recordingAction, onEvent) { recordingAction = it }
        }
        SettingRow("shortcuts.SearchEverywhere") {
            ShortcutRow(BlazeKeymap.Action.SearchEverywhere, s.searchEverywhere, strings, state, recordingAction, onEvent) { recordingAction = it }
        }
        SettingRow("shortcuts.FocusListSearch") {
            ShortcutRow(BlazeKeymap.Action.FocusListSearch, s.focusListSearch, strings, state, recordingAction, onEvent) { recordingAction = it }
        }
        SettingRow("shortcuts.ToggleDetails") {
            ShortcutRow(BlazeKeymap.Action.ToggleDetails, s.toggleDetails, strings, state, recordingAction, onEvent) { recordingAction = it }
        }
        SettingRow("shortcuts.SelectAll") {
            ShortcutRow(BlazeKeymap.Action.SelectAll, s.selectAll, strings, state, recordingAction, onEvent) { recordingAction = it }
        }
        SettingRow("shortcuts.TogglePause") {
            ShortcutRow(BlazeKeymap.Action.TogglePause, s.togglePause, strings, state, recordingAction, onEvent) { recordingAction = it }
        }
        SettingRow("shortcuts.Remove") {
            ShortcutRow(BlazeKeymap.Action.Remove, s.remove, strings, state, recordingAction, onEvent) { recordingAction = it }
        }
        SettingRow("shortcuts.OpenSelected") {
            ShortcutRow(BlazeKeymap.Action.OpenSelected, s.openSelected, strings, state, recordingAction, onEvent) { recordingAction = it }
        }
        SettingRow("shortcuts.OpenSettings") {
            ShortcutRow(BlazeKeymap.Action.OpenSettings, s.openSettings, strings, state, recordingAction, onEvent) { recordingAction = it }
        }
    }
}

/**
 * The content of one action's row: its localized name, the chord the draft resolves to (or "Unbound"),
 * the recorder, and Unbind / Reset controls. [onRecord] arms or disarms this row's capture. It renders
 * inside a [SettingRow] whose literal id the search index keys on.
 */
@OptIn(ExperimentalJewelApi::class)
@Composable
private fun ShortcutRow(
    action: BlazeKeymap.Action,
    label: String,
    strings: BlazeStrings,
    state: SettingsState,
    recordingAction: String?,
    onEvent: (SettingsEvent) -> Unit,
    onRecord: (String?) -> Unit,
) {
    val s = strings.settings.shortcuts
    val bindings = state.appSettings.keyBindings
    val unbound = state.appSettings.unboundShortcuts
    val name = action.name
    val display = KeymapPersistence.draftDisplay(action, bindings, unbound)
    val isUnbound = name in unbound
    // Reset only makes sense once the action has drifted from its built-in default (an override or an unbind).
    val isOverridden = name in bindings || isUnbound

    val conflictOther = state.keyBindingConflicts[name]
    val unsafe = name in state.unsafeKeyBindings
    val error = when {
        unsafe -> s.needModifierError
        conflictOther != null -> s.conflictError(shortcutLabelFor(conflictOther, s))
        else -> null
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label)
            if (error != null) {
                Text(
                    text = error,
                    color = IslandSemanticColors.error,
                    style = JewelTheme.defaultTextStyle.copy(fontSize = 12.sp)
                )
            }
        }

        if (recordingAction == name) {
            ChordRecorder(
                hint = s.recordingHint,
                onCaptured = { stored ->
                    onEvent(SettingsEvent.SetKeyBinding(name, stored))
                    onRecord(null)
                },
                onCancel = { onRecord(null) },
            )
        } else {
            Text(
                text = display ?: s.unboundLabel,
                color = if (isUnbound) JewelTheme.globalColors.text.info else Color.Unspecified,
            )
            OutlinedButton(onClick = { onRecord(name) }) {
                Text(s.recordAction)
            }
            if (!isUnbound) {
                OutlinedButton(onClick = { onEvent(SettingsEvent.ClearKeyBinding(name)) }) {
                    Text(s.clearAction)
                }
            }
            if (isOverridden) {
                OutlinedButton(onClick = { onEvent(SettingsEvent.RestoreDefaultKeyBinding(name)) }) {
                    Text(s.resetAction)
                }
            }
        }
    }
}

/**
 * A focused capture surface armed while a row is recording. It swallows every key while it owns focus:
 * Esc cancels, the first real chord is [KeymapPersistence.capture]d and reported through [onCaptured]
 * (modifier-only presses are ignored so the recorder waits for a full chord). The focused node is a
 * descendant of the preview handler, so the capture is reliable regardless of the surrounding layout's
 * focus.
 */
@Composable
private fun ChordRecorder(
    hint: String,
    onCaptured: (StoredKeyBinding) -> Unit,
    onCancel: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier = Modifier
            .semantics { testTag = "shortcut_recorder" }
            .onPreviewKeyEvent { event ->
                when {
                    event.type == KeyEventType.KeyDown && event.key == Key.Escape -> {
                        onCancel(); true
                    }
                    else -> {
                        val captured = KeymapPersistence.capture(event)
                        if (captured != null) onCaptured(captured)
                        // Swallow everything while armed so no chord or typing leaks to the app behind.
                        true
                    }
                }
            },
    ) {
        Box(
            modifier = Modifier
                .focusRequester(focusRequester)
                .focusable(interactionSource = interactionSource),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = hint)
        }
    }

    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
}

/** The localized action name for a raw [BlazeKeymap.Action] name (used to name the other half of a conflict). */
private fun shortcutLabelFor(actionName: String, s: BlazeStrings.Settings.Shortcuts): String = when (actionName) {
    BlazeKeymap.Action.NewDownload.name -> s.newDownload
    BlazeKeymap.Action.SearchEverywhere.name -> s.searchEverywhere
    BlazeKeymap.Action.FocusListSearch.name -> s.focusListSearch
    BlazeKeymap.Action.ToggleDetails.name -> s.toggleDetails
    BlazeKeymap.Action.SelectAll.name -> s.selectAll
    BlazeKeymap.Action.TogglePause.name -> s.togglePause
    BlazeKeymap.Action.Remove.name -> s.remove
    BlazeKeymap.Action.OpenSelected.name -> s.openSelected
    BlazeKeymap.Action.OpenSettings.name -> s.openSettings
    else -> actionName
}
