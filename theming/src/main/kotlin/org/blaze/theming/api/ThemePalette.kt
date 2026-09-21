package org.blaze.theming.api

/**
 * A composable-agnostic color palette.
 *
 * Plugins must not depend on Compose or Jewel, so every color is expressed as a packed
 * ARGB value (e.g. `0xFF3574F0`) rather than `androidx.compose.ui.graphics.Color`. The GUI
 * module converts these to real colors and maps them onto the Jewel theme.
 *
 * All fields are optional. A null field means "don't override this color" — the app keeps
 * its built-in default for it. This lets a theme ship, say, only an accent change while
 * leaving success/warning/error and the surfaces untouched, and it lets the built-in
 * "system" theme be an entirely empty palette.
 */
data class ThemePalette(
    /** Primary accent used for selection, progress, links and highlighted states. */
    val accentArgb: Long? = null,
    /** Positive / success state (e.g. completed downloads, connected status). */
    val successArgb: Long? = null,
    /** Caution state (e.g. paused, seeding). */
    val warningArgb: Long? = null,
    /** Destructive / error state. */
    val errorArgb: Long? = null,
    /** Row hover overlay. */
    val hoverArgb: Long? = null,
    /** Panels, sidebars and dialog surfaces. Overrides Jewel's `panelBackground` when set. */
    val panelBackgroundArgb: Long? = null,
    /** Hairline borders. Overrides Jewel's default border color when set. */
    val borderArgb: Long? = null,
    /** Secondary / informational text. Overrides Jewel's `text.info` when set. */
    val infoTextArgb: Long? = null,
) {
    companion object {
        /** A palette that overrides nothing — every color falls back to the built-in default. */
        val Empty = ThemePalette()
    }
}
