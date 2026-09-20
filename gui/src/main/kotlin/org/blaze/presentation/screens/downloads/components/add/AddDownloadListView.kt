package org.blaze.presentation.screens.downloads.components.add

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import org.jetbrains.jewel.ui.component.*

@Composable
fun AddDownloadListView(
    state: AddDownloadState
) {
    val strings = blazeStrings
    val dStrings = strings.downloads.dialogs

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${state.batchItems.count { it.isSelected }} / ${state.batchItems.size} ${dStrings.itemsSelected}",
                style = JewelTheme.defaultTextStyle.copy(fontWeight = FontWeight.Medium)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = dStrings.selectAll,
                    style = JewelTheme.defaultTextStyle.copy(fontSize = 12.sp),
                    color = JewelTheme.globalColors.text.info,
                    modifier = Modifier.clickable {
                        state.batchItems.forEach { it.isSelected = true }
                    }
                )
                Text(
                    text = dStrings.deselectAll,
                    style = JewelTheme.defaultTextStyle.copy(fontSize = 12.sp),
                    color = JewelTheme.globalColors.text.info,
                    modifier = Modifier.clickable {
                        state.batchItems.forEach { it.isSelected = false }
                    }
                )
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 300.dp)
                .background(JewelTheme.globalColors.panelBackground.copy(alpha = 0.3f))
                .border(1.dp, JewelTheme.globalColors.borders.normal, RoundedCornerShape(4.dp))
                .padding(4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            items(state.batchItems) { item ->
                val metadata = item.metadata
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { item.isSelected = !item.isSelected }
                        .padding(vertical = 4.dp, horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Checkbox(
                        checked = item.isSelected,
                        onCheckedChange = { item.isSelected = it }
                    )
                    
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = metadata?.name ?: item.url,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = if (metadata != null) FontWeight.Medium else FontWeight.Normal
                        )
                        if (metadata != null) {
                            Text(
                                text = formatSize(metadata.totalSize, strings),
                                style = JewelTheme.defaultTextStyle.copy(fontSize = 11.sp),
                                color = JewelTheme.globalColors.text.info
                            )
                        } else if (item.isFetching) {
                            Text(
                                text = dStrings.fetchingMetadata,
                                style = JewelTheme.defaultTextStyle.copy(fontSize = 11.sp),
                                color = JewelTheme.globalColors.text.info
                            )
                        } else if (item.error != null) {
                            Text(
                                text = item.error!!,
                                style = JewelTheme.defaultTextStyle.copy(fontSize = 11.sp),
                                color = JewelTheme.globalColors.text.error
                            )
                        }
                    }

                    if (item.isFetching) {
                        IndeterminateHorizontalProgressBar(modifier = Modifier.width(60.dp))
                    }
                }
            }
        }

        OutlinedButton(onClick = { state.step = 1 }) {
            Text(dStrings.backToEdit)
        }
    }
}
