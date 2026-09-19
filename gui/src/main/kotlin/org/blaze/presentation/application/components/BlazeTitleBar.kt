package org.blaze.presentation.application.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.window.DecoratedWindowScope
import org.jetbrains.jewel.window.TitleBar
import org.jetbrains.jewel.window.newFullscreenControls

@OptIn(ExperimentalJewelApi::class)
@Composable
fun DecoratedWindowScope.BlazeTitleBar(
    isDark: Boolean,
    onToggleDark: () -> Unit,
) {
    var query by remember {
        mutableStateOf(TextFieldValue(""))
    }

    TitleBar(
        modifier = Modifier.newFullscreenControls(),
    ) {
        BlazeTitleBarIdentity()

        BlazeSearchField(
            query = query,
            onQueryChange = { query = it },
        )

        BlazeTitleBarActions(
            isDark = isDark,
            onToggleDark = onToggleDark,
        )
    }
}