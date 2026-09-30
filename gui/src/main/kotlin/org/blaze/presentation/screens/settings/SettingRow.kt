package org.blaze.presentation.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.semantics.semantics
import org.blaze.i18n.BlazeStrings
import org.blaze.i18n.blazeStrings
import org.blaze.presentation.theme.BlazeColors

/**
 * The live Settings search state, shared between the sidebar (which prunes categories) and the content
 * pane (which hides non-matching rows) through [LocalSettingsFilter]. It is deliberately tiny and
 * value-based: an empty query means "no filtering", everything renders.
 */
data class SettingsFilter(val query: String) {
    val isActive: Boolean get() = query.isNotBlank()

    fun matches(option: SettingsOption, strings: BlazeStrings): Boolean =
        SettingsRegistry.matchesQuery(option, query, strings)

    companion object {
        val None = SettingsFilter("")
    }
}

/** The current filter. Sections never read it directly — only through [SettingRow] / [SettingsGroup]. */
val LocalSettingsFilter = staticCompositionLocalOf { SettingsFilter.None }

/**
 * The id of the row Search Everywhere asked to jump to, or null. The owning screen sets it, the matching
 * [SettingRow] paints a flash while it is non-null, and the screen clears it once the flash elapses.
 */
val LocalSettingsFlashId = compositionLocalOf<String?> { null }

/**
 * Wraps one registered Settings control. Its whole job is to keep the *index* and the *UI* the same
 * object: it looks [id] up in the [SettingsRegistry] — throwing when the id is missing, so a row can
 * never exist on screen but be invisible to search — publishes a stable `setting_<id>` semantics tag the
 * guard tests and the flash both key on, and hides the row while a search query excludes it.
 *
 * The control's own label stays where the section wrote it; it is the very same string the registry's
 * [SettingsOption.title] accessor reads, so the searchable text and the visible text cannot drift.
 */
@Composable
internal fun SettingRow(
    id: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val option = SettingsRegistry.require(id)
    val strings = blazeStrings
    val filter = LocalSettingsFilter.current
    if (filter.isActive && !filter.matches(option, strings)) return

    val flashId = LocalSettingsFlashId.current
    val tint = when {
        flashId == id -> BlazeColors.hover
        filter.isActive -> SettingsMatchTint
        else -> Color.Transparent
    }
    Column(
        modifier = modifier
            .background(tint)
            .semantics { testTag = "setting_$id" },
    ) {
        content()
    }
}

/**
 * A titled section that renders its [groupKey]'s header and, when a search query is active, only when at
 * least one of the group's registered options matches — so empty group headers vanish as you type, the
 * way the IDE's Settings prunes its panels.
 */
@Composable
internal fun SettingsGroup(
    groupKey: String,
    strings: BlazeStrings,
    content: @Composable ColumnScope.() -> Unit,
) {
    val filter = LocalSettingsFilter.current
    if (filter.isActive && SettingsRegistry.optionsInGroup(groupKey).none { filter.matches(it, strings) }) {
        return
    }
    SettingsSection(SettingsRegistry.groupTitle(groupKey, strings), content = content)
}

/** A faint accent wash (~12%) marking the rows a search query kept alive. */
private val SettingsMatchTint = Color(0x1F548AF7)
