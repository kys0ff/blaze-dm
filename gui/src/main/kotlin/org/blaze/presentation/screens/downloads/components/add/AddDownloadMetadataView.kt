package org.blaze.presentation.screens.downloads.components.add

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.blaze.i18n.blazeStrings
import org.blaze.presentation.util.formatSize
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Checkbox
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text

@Composable
fun AddDownloadMetadataView(
    state: AddDownloadState
) {
    val strings = blazeStrings
    val dStrings = strings.downloads.dialogs
    val metadata = state.metadata ?: return

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = dStrings.metadataResolved,
            style = JewelTheme.defaultTextStyle.copy(fontWeight = FontWeight.Medium)
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(JewelTheme.globalColors.panelBackground.copy(alpha = 0.5f))
                .border(1.dp, JewelTheme.globalColors.borders.normal, RoundedCornerShape(4.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(strings.common.name, color = JewelTheme.globalColors.text.info, modifier = Modifier.width(60.dp))
                Text(metadata.name ?: strings.common.unknown, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(dStrings.size, color = JewelTheme.globalColors.text.info, modifier = Modifier.width(60.dp))
                Text(formatSize(metadata.totalSize, strings))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(dStrings.url, color = JewelTheme.globalColors.text.info, modifier = Modifier.width(60.dp))
                Text(state.source, maxLines = 1, overflow = TextOverflow.Ellipsis, color = JewelTheme.globalColors.text.info)
            }
        }

        if (metadata.files != null) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(dStrings.files, fontWeight = FontWeight.Medium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = dStrings.selectAll,
                            style = JewelTheme.defaultTextStyle.copy(fontSize = 12.sp),
                            color = JewelTheme.globalColors.text.info,
                            modifier = Modifier.clickable {
                                state.selectedFileIndices = metadata.files!!.map { it.index }.toSet()
                            }
                        )
                        Text(
                            text = dStrings.deselectAll,
                            style = JewelTheme.defaultTextStyle.copy(fontSize = 12.sp),
                            color = JewelTheme.globalColors.text.info,
                            modifier = Modifier.clickable {
                                state.selectedFileIndices = emptySet()
                            }
                        )
                    }
                }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp)
                        .background(JewelTheme.globalColors.panelBackground.copy(alpha = 0.3f))
                        .border(1.dp, JewelTheme.globalColors.borders.normal, RoundedCornerShape(4.dp))
                        .padding(4.dp)
                ) {
                    items(metadata.files!!) { file ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    state.selectedFileIndices = if (state.selectedFileIndices.contains(file.index)) {
                                        state.selectedFileIndices - file.index
                                    } else {
                                        state.selectedFileIndices + file.index
                                    }
                                }
                                .padding(vertical = 4.dp, horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Checkbox(
                                checked = state.selectedFileIndices.contains(file.index),
                                onCheckedChange = {
                                    state.selectedFileIndices = if (it) {
                                        state.selectedFileIndices + file.index
                                    } else {
                                        state.selectedFileIndices - file.index
                                    }
                                }
                            )
                            Text(
                                text = file.name,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = formatSize(file.size, strings),
                                color = JewelTheme.globalColors.text.info,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }
        }

        OutlinedButton(onClick = { state.step = 1 }) {
            Text(dStrings.backToEdit)
        }
    }
}
