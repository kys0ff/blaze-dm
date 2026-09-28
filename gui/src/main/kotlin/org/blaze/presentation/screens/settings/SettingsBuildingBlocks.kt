package org.blaze.presentation.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.blaze.presentation.theme.BlazeColors
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Outline
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import org.jetbrains.jewel.ui.theme.simpleListItemStyle

/** Settings content is indented to line up with the label of a checkbox/radio (icon + gap). */
internal val DependentIndent = 27.dp

/** IDE "titled separator": a heading followed by a hairline, then the section's controls. */
@Composable
internal fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = title,
                style = JewelTheme.defaultTextStyle.copy(fontWeight = FontWeight.SemiBold)
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(1.dp)
                    .background(JewelTheme.globalColors.borders.normal)
            )
        }
        content()
    }
}

/** Label + read-only, copyable path field with an optional description. */
@OptIn(ExperimentalJewelApi::class)
@Composable
internal fun ReadOnlyPathField(
    label: String,
    path: String,
    description: String,
    infoStyle: androidx.compose.ui.text.TextStyle
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(text = label)
        TextField(
            value = TextFieldValue(path),
            onValueChange = {},
            enabled = false,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = description,
            style = infoStyle,
            color = JewelTheme.globalColors.text.info
        )
    }
}

/** Left-hand category entry with rounded IDE-style selection and an optional error marker. */
@Composable
internal fun SettingsNavItem(
    label: String,
    isSelected: Boolean,
    hasError: Boolean,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val colors = JewelTheme.simpleListItemStyle.colors

    val background = when {
        isSelected -> colors.backgroundSelectedActive
        hovered -> BlazeColors.hover
        else -> Color.Transparent
    }
    val contentColor = if (isSelected) colors.contentSelectedActive else Color.Unspecified

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(background)
                .hoverable(interaction)
                .clickable(interactionSource = interaction, indication = null, onClick = onClick)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = label,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (hasError) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(BlazeColors.error)
                )
            }
        }
    }
}

/** Labeled numeric input with inline error and optional description. */
@OptIn(ExperimentalJewelApi::class)
@Composable
internal fun NumberField(
    label: String,
    value: TextFieldValue,
    error: String?,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    enabled: Boolean = true,
    fieldWidth: Dp = 100.dp
) {
    val showError = enabled && error != null

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = label,
            color = if (enabled) Color.Unspecified else JewelTheme.globalColors.text.disabled
        )
        TextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            outline = if (showError) Outline.Error else Outline.None,
            modifier = Modifier.width(fieldWidth)
        )
        if (showError) {
            Text(
                text = error,
                color = BlazeColors.error,
                style = JewelTheme.defaultTextStyle.copy(fontSize = 12.sp)
            )
        }
        if (description != null) {
            Text(
                text = description,
                style = JewelTheme.defaultTextStyle.copy(fontSize = 12.sp),
                color = JewelTheme.globalColors.text.info
            )
        }
    }
}

/** Labeled free-text input (single line) with an optional description, e.g. the HTTP User-Agent. */
@OptIn(ExperimentalJewelApi::class)
@Composable
internal fun LabeledTextField(
    label: String,
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    enabled: Boolean = true
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = label,
            color = if (enabled) Color.Unspecified else JewelTheme.globalColors.text.disabled
        )
        TextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth()
        )
        if (description != null) {
            Text(
                text = description,
                style = JewelTheme.defaultTextStyle.copy(fontSize = 12.sp),
                color = JewelTheme.globalColors.text.info
            )
        }
    }
}
