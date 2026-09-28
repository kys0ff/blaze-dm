package org.blaze.presentation.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import org.blaze.engine.settings.FileConflictBehavior
import org.blaze.i18n.BlazeStrings
import org.blaze.presentation.screens.settings.state.SettingsState
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.ui.component.CheckboxRow
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.RadioButtonRow
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField

/**
 * Files category: where downloads land and what happens when something is already there.
 * The destination field is read-only by design (the picker owns edits) but selectable,
 * mirroring the IDE's path fields.
 */
@OptIn(ExperimentalJewelApi::class)
@Composable
internal fun FilesSection(
    state: SettingsState,
    strings: BlazeStrings,
    onEvent: (SettingsEvent) -> Unit,
    onBrowseDownloadDir: () -> Unit
) {
    SettingsSection(strings.settings.destinationHeader) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(text = strings.settings.defaultDownloadDirLabel)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Read-only field: looks like the IDE's path fields and the
                // path can be selected and copied.
                TextField(
                    value = TextFieldValue(state.settings.defaultDownloadDir),
                    onValueChange = {},
                    enabled = false,
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(onClick = onBrowseDownloadDir) {
                    Text(strings.common.browse)
                }
            }
        }

        CheckboxRow(
            text = strings.settings.askWhereToSaveLabel,
            checked = state.settings.askWhereToSave,
            onCheckedChange = { onEvent(SettingsEvent.UpdateAskWhereToSave(it)) }
        )
    }

    SettingsSection(strings.settings.fileConflictsHeader) {
        Text(text = strings.settings.fileConflictBehaviorLabel)

        val options = listOf(
            FileConflictBehavior.ASK to strings.settings.askOption,
            FileConflictBehavior.OVERWRITE to strings.settings.overwriteOption,
            FileConflictBehavior.SKIP to strings.settings.skipOption,
            FileConflictBehavior.RENAME to strings.settings.renameOption
        )
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { (behavior, label) ->
                RadioButtonRow(
                    text = label,
                    selected = state.settings.fileConflictBehavior == behavior,
                    onClick = { onEvent(SettingsEvent.UpdateFileConflictBehavior(behavior)) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}
