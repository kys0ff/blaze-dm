package org.blaze.presentation.screens.downloads.components

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import org.blaze.i18n.blazeStrings
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/**
 * Compact IntelliJ-style filter/search field shown in the Downloads tool window toolbar.
 * Typing filters the list live; [Escape] clears the query.
 */
@OptIn(ExperimentalJewelApi::class)
@Composable
fun DownloadsSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = blazeStrings
    var fieldValue by remember { mutableStateOf(TextFieldValue(query)) }

    // Keep the local field in sync when the query is changed elsewhere (e.g. cleared externally).
    LaunchedEffect(query) {
        if (fieldValue.text != query) fieldValue = TextFieldValue(query)
    }

    TextField(
        value = fieldValue,
        onValueChange = {
            fieldValue = it
            onQueryChange(it.text)
        },
        placeholder = {
            Text(strings.downloads.searchPlaceholder)
        },
        leadingIcon = {
            Icon(
                key = AllIconsKeys.Actions.Find,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
        },
        trailingIcon = {
            if (fieldValue.text.isNotEmpty()) {
                IconButton(
                    onClick = {
                        fieldValue = TextFieldValue("")
                        onQueryChange("")
                    },
                    modifier = Modifier.size(20.dp),
                ) {
                    Icon(
                        key = AllIconsKeys.Actions.Close,
                        contentDescription = strings.downloads.clearSearch,
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
        },
        modifier = modifier
            .width(220.dp)
            .height(26.dp)
            .padding(horizontal = 6.dp)
            .onPreviewKeyEvent { event ->
                if (
                    event.type == KeyEventType.KeyDown &&
                    event.key == Key.Escape &&
                    fieldValue.text.isNotEmpty()
                ) {
                    fieldValue = TextFieldValue("")
                    onQueryChange("")
                    true
                } else {
                    false
                }
            },
    )
}
