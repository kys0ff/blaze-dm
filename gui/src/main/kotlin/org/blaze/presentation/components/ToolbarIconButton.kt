package org.blaze.presentation.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.jetbrains.jewel.ui.component.ActionButton
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icon.IconKey

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ToolbarIconButton(
    key: IconKey,
    tooltip: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    ActionButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        tooltip = { Text(tooltip) }
    ) {
        Icon(key, tooltip)
    }
}
