package org.blaze.presentation.application.components

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
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
import org.jetbrains.jewel.window.TitleBarScope
import org.jetbrains.jewel.window.utils.clientRegion

@OptIn(ExperimentalJewelApi::class)
@Composable
fun TitleBarScope.BlazeSearchField(
    query: TextFieldValue,
    onQueryChange: (TextFieldValue) -> Unit,
) {
    val strings = blazeStrings
    TextField(
        value = query,
        onValueChange = onQueryChange,
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
            if (query.text.isNotEmpty()) {
                IconButton(
                    onClick = {
                        onQueryChange(TextFieldValue(""))
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
        modifier = Modifier
            .align(Alignment.CenterHorizontally)
            .width(420.dp)
            .height(32.dp)
            .onPreviewKeyEvent { event ->
                if (
                    event.type == KeyEventType.KeyDown &&
                    event.key == Key.Escape &&
                    query.text.isNotEmpty()
                ) {
                    onQueryChange(TextFieldValue(""))
                    true
                } else {
                    false
                }
            }
            .clientRegion("search"),
    )
}