package org.blaze.presentation.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import org.blaze.presentation.screens.downloads.components.TabularFigures
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.typography

/**
 * The chrome's text styles, collected in one place.
 *
 * The Islands spec asks for a regular face everywhere except dialog titles and group headers, and
 * the only way to keep that true is to stop re-styling text at call sites. It matters more than it
 * looks: Jewel's own [typography][org.jetbrains.jewel.ui.Typography] ramps (`small`, `medium`) are
 * *only* size steps — `IntUiTypography` builds every one of them with
 * `createDefaultTextStyle(fontWeight = FontWeight.Normal)` — so each heavier label in this app can
 * only come from an explicit `copy(fontWeight = ...)` somewhere. Those copies are what this object
 * owns now, and [audit] prints the resolved weight of every style so the claim is measured instead
 * of asserted (the screenshot harness runs it on every `:gui:uiScreenshots`).
 */
internal object BlazeTypography {

    /** Table column headers: small and regular — the IDE's header is no heavier than its rows. */
    @get:Composable
    val tableHeader: TextStyle
        get() = JewelTheme.typography.small

    /** A row's numeric readouts (size, speed, eta): small, regular, fixed-width digits. */
    @get:Composable
    val rowCell: TextStyle
        get() = JewelTheme.typography.small.copy(fontFeatureSettings = TabularFigures)

    /** The state badge ("Completed", "Downloading") and the priority chip: one step smaller. */
    @get:Composable
    val badge: TextStyle
        get() = JewelTheme.defaultTextStyle.copy(fontSize = 12.sp)

    /** Status-bar readouts: small, regular, and always in the secondary text color. */
    @get:Composable
    val statusBar: TextStyle
        get() = JewelTheme.typography.small

    /** Filter chips and header-menu entries. */
    @get:Composable
    val chip: TextStyle
        get() = JewelTheme.typography.small

    /** A tool window's title: the size step up, still a regular face. */
    @get:Composable
    val groupHeader: TextStyle
        get() = JewelTheme.typography.medium

    /**
     * The two labels the spec exempts from the regular-weight rule: a dialog's title and a Settings
     * group header. Nothing else may use this — that is what makes the exemption auditable.
     */
    @get:Composable
    val emphasizedHeader: TextStyle
        get() = JewelTheme.defaultTextStyle.copy(fontWeight = FontWeight.SemiBold)

    /** The app name in the title bar: the same face as everything else on the frame. */
    @get:Composable
    val titleBarLabel: TextStyle
        get() = JewelTheme.defaultTextStyle

    /**
     * A text field's hint/placeholder label: the small ramp, so a hint inside the tool-window search
     * field never out-weights the 11sp cells it sits above. Audited like every other chrome style.
     */
    @get:Composable
    val fieldPlaceholder: TextStyle
        get() = JewelTheme.typography.small

    /**
     * Resolves every chrome style (and the Jewel ramps they are built from) into a report of their
     * actual `FontWeight`, which is how the "regular everywhere" rule gets verified rather than
     * trusted. The screenshot harness prints it on every `:gui:uiScreenshots` run.
     *
     * The lines that answer the reported "reads too bold" complaints: the Size column's value is
     * [rowCell], the "Completed" badge is [badge], the status bar's "Ready" is [statusBar], and the
     * search field's hint is [fieldPlaceholder].
     */
    @Composable
    fun auditReport(): String {
        fun line(name: String, style: TextStyle) =
            "  %-18s fontWeight=%-14s fontSize=%s".format(name, style.fontWeight, style.fontSize)
        return buildString {
            appendLine("[fontAudit] Jewel ramps - what 'small'/'medium' really are:")
            appendLine(line("typography.regular", JewelTheme.typography.regular))
            appendLine(line("typography.small", JewelTheme.typography.small))
            appendLine(line("typography.medium", JewelTheme.typography.medium))
            appendLine(line("defaultTextStyle", JewelTheme.defaultTextStyle))
            appendLine("[fontAudit] Blaze chrome styles:")
            appendLine(line("tableHeader", tableHeader))
            appendLine(line("rowCell", rowCell))
            appendLine(line("badge", badge))
            appendLine(line("statusBar", statusBar))
            appendLine(line("chip", chip))
            appendLine(line("titleBarLabel", titleBarLabel))
            appendLine(line("groupHeader", groupHeader))
            appendLine(line("fieldPlaceholder", fieldPlaceholder))
            appendLine(line("emphasizedHeader", emphasizedHeader))
        }
    }
}
