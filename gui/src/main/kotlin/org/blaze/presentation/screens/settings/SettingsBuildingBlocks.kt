package org.blaze.presentation.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.blaze.presentation.components.AppMetrics
import org.island.theme.ui.IslandSemanticColors
import org.island.theme.ui.IslandTypography
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Outline
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import org.jetbrains.jewel.ui.theme.simpleListItemStyle
import org.jetbrains.jewel.ui.typography

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
                // The one exemption the chrome's regular-weight rule grants inside Settings: a group
                // header. It goes through [IslandTypography.emphasizedHeader] so the weight is a single
                // audited value instead of a per-call-site copy.
                style = IslandTypography.emphasizedHeader,
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

/**
 * Left-hand category entry with rounded IDE-style selection and an optional error marker.
 *
 * [query] is the sidebar's live search text: every occurrence of it in [label] is painted in the
 * accent so a filtered row still explains why it survived. The selected row skips the tint — it already
 * has a background, accent text on an accent-tinted selection is unreadable, and the chrome's text is
 * regular weight everywhere, so color is the only highlight available.
 */
@Composable
internal fun SettingsNavItem(
    label: String,
    isSelected: Boolean,
    hasError: Boolean,
    query: String = "",
    matchCount: Int = 0,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val colors = JewelTheme.simpleListItemStyle.colors

    val background = when {
        isSelected -> colors.backgroundSelectedActive
        hovered -> IslandSemanticColors.hover
        else -> Color.Transparent
    }
    val contentColor = if (isSelected) colors.contentSelectedActive else Color.Unspecified
    val highlight = IslandSemanticColors.accent

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(AppMetrics.SettingsNavRowHeight)
                .clip(RoundedCornerShape(6.dp))
                .background(background)
                .hoverable(interaction)
                .clickable(interactionSource = interaction, indication = null, onClick = onClick)
                .padding(start = 8.dp, end = 10.dp)
                .semantics { testTag = "settings_nav_$label" },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = if (isSelected) AnnotatedString(label) else highlightedLabel(label, query, highlight),
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            // A count badge appears only while a search is active, so a pruned list still shows how many
            // options each surviving page holds.
            if (matchCount > 0) {
                Text(
                    text = matchCount.toString(),
                    style = JewelTheme.typography.small,
                    color = JewelTheme.globalColors.text.info,
                )
            }
            if (hasError) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(IslandSemanticColors.error)
                )
            }
        }
    }
}

/**
 * [label] with every case-insensitive occurrence of [query] wrapped in a [highlight] [SpanStyle].
 * Returns the plain label when there is nothing to mark, so the rows cost nothing while the
 * sidebar's search box is empty. The color is passed in rather than read here because this is a plain
 * (non-composable) string builder.
 */
private fun highlightedLabel(label: String, query: String, highlight: Color): AnnotatedString {
    if (query.isBlank()) return AnnotatedString(label)

    val matches = mutableListOf<IntRange>()
    var cursor = 0
    while (cursor <= label.length - query.length) {
        val index = label.indexOf(query, startIndex = cursor, ignoreCase = true)
        if (index < 0) break
        matches += index until index + query.length
        cursor = index + query.length
    }
    if (matches.isEmpty()) return AnnotatedString(label)

    return buildAnnotatedString {
        var written = 0
        matches.forEach { range ->
            append(label.substring(written, range.first))
            withStyle(SpanStyle(color = highlight)) {
                append(label.substring(range.first, range.last + 1))
            }
            written = range.last + 1
        }
        append(label.substring(written))
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
                color = IslandSemanticColors.error,
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
