package org.blaze.presentation.screens.downloads.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import org.blaze.engine.settings.FileConflictBehavior
import org.blaze.i18n.blazeStrings
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.RadioButtonRow
import org.jetbrains.jewel.ui.component.Text

@OptIn(ExperimentalJewelApi::class)
@Composable
fun FileConflictDialog(
    fileName: String,
    onDismiss: () -> Unit,
    onConfirm: (behavior: FileConflictBehavior) -> Unit
) {
    var selectedBehavior by remember { mutableStateOf(FileConflictBehavior.RENAME) }
    val focusRequester = remember { FocusRequester() }

    val strings = blazeStrings
    val dStrings = strings.downloads.dialogs
    val sStrings = strings.settings

    val shape = RoundedCornerShape(8.dp)

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .width(440.dp)
                .clip(shape)
                .background(JewelTheme.globalColors.panelBackground)
                .border(1.dp, JewelTheme.globalColors.borders.normal, shape)
                .focusRequester(focusRequester)
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                        onDismiss()
                        true
                    } else {
                        false
                    }
                }
                .focusable()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = dStrings.conflictTitle,
                style = JewelTheme.defaultTextStyle.copy(
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
            )

            Text(
                text = dStrings.conflictMessage(fileName),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                RadioButtonRow(
                    text = sStrings.renameOption,
                    selected = selectedBehavior == FileConflictBehavior.RENAME,
                    onClick = { selectedBehavior = FileConflictBehavior.RENAME },
                    modifier = Modifier.fillMaxWidth()
                )

                RadioButtonRow(
                    text = sStrings.overwriteOption,
                    selected = selectedBehavior == FileConflictBehavior.OVERWRITE,
                    onClick = { selectedBehavior = FileConflictBehavior.OVERWRITE },
                    modifier = Modifier.fillMaxWidth()
                )

                RadioButtonRow(
                    text = sStrings.skipOption,
                    selected = selectedBehavior == FileConflictBehavior.SKIP,
                    onClick = { selectedBehavior = FileConflictBehavior.SKIP },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(onClick = onDismiss) {
                    Text(strings.common.cancel)
                }
                Spacer(Modifier.width(8.dp))
                DefaultButton(
                    onClick = {
                        onConfirm(selectedBehavior)
                        onDismiss()
                    }
                ) {
                    Text(strings.common.ok)
                }
            }
        }
    }
}
