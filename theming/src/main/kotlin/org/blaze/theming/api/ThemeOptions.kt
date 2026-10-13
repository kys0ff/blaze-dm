package org.blaze.theming.api

import kotlinx.serialization.Serializable

/**
 * The kind of value a [ThemeOption] holds, flattened onto the descriptor itself
 * ([choices] / [minimum] / [maximum] are only meaningful for the matching kind).
 *
 * Mirrors the link-resolver option model (`ResolverOption`) so both extension kinds render
 * from plain, serializable data without running plugin code — with one extra kind, [Color],
 * because a theme's whole vocabulary is colors and the editor should speak it.
 */
@Serializable
sealed interface ThemeOptionType {

    /** A free-form single-line string. */
    @Serializable data object Text : ThemeOptionType

    /** A checked / unchecked preference. Persisted as `"true"` / `"false"`. */
    @Serializable data object Boolean : ThemeOptionType

    /** A whole number clamped to [[minimum], [maximum]]. Persisted as its decimal text. */
    @Serializable data class Integer(val minimum: Long, val maximum: Long) : ThemeOptionType

    /** One value out of [choices]. Persisted as the chosen choice verbatim. */
    @Serializable data class Select(val choices: List<String>) : ThemeOptionType

    /**
     * A packed ARGB color, edited as `#AARRGGBB` hex and persisted as that text. The app
     * applies validated color options itself (see
     * [org.blaze.theming.core.ThemeRegistry.paletteFor]) so overriding e.g. the accent needs
     * no plugin code at render time at all.
     */
    @Serializable data class Color(val alphaAllowed: kotlin.Boolean = true) : ThemeOptionType
}

/**
 * One user-editable setting a [ConfigurableBlazeThemeProvider] declares, in the shape the
 * Settings screen renders directly — no plugin code runs to build the form.
 *
 * [label] and [description] are plain strings owned by the plugin: extensions are
 * third-party jars without access to the app's localization catalog, so they ship their own
 * wording.
 */
@Serializable
data class ThemeOption(
    /** Stable key the theme reads its value from; unique within one provider. */
    val key: String,
    /** Short label rendered next to the control. */
    val label: String,
    /** Optional one-line explanation shown under the control. */
    val description: String? = null,
    val type: ThemeOptionType = ThemeOptionType.Text,
    /** Value used when the user has never touched this option; also the reset target. */
    val defaultValue: String = "",
    // `kotlin.Boolean` qualified: the nested [ThemeOptionType.Boolean] object shadows the plain
    // name inside this file's scope, so an unqualified `Boolean` here would not compile.
    /**
     * For [ThemeOptionType.Color] descriptors: which palette slot the *app* overrides with
     * the user's value (see [ThemeColorTarget]). Null for non-color options — the GUI then
     * only forwards the raw value through [ThemeConfig] to the plugin.
     */
    val colorTarget: ThemeColorTarget? = null,
    /**
     * Marks a numeric option as the ambient glow's intensity; the app overrides
     * [IslandsPalette.ambientIntensityPercent] with the user's (clamped) answer.
     */
    val ambientIntensityKey: Boolean = false,
    /**
     * The value this option falls back to in **light** ("day") mode when the user has never touched
     * it. [defaultValue] is the dark-mode fallback. A theme that paints both appearances declares a
     * distinct light default here so a color/ambient token authored for the dark palette is never
     * stretched over light chrome; a mode-agnostic option leaves this null and reuses [defaultValue].
     */
    val lightDefaultValue: String? = null,
)

/** Convenience constructors so descriptors read without naming the carrier type. */
fun themeTextOption(key: String, label: String, description: String? = null, default: String = "") =
    ThemeOption(key, label, description, ThemeOptionType.Text, default)

fun themeBooleanOption(
    key: String,
    label: String,
    description: String? = null,
    default: Boolean = false,
) = ThemeOption(key, label, description, ThemeOptionType.Boolean, default.toString())

fun themeIntegerOption(
    key: String,
    label: String,
    description: String? = null,
    range: LongRange,
    default: Long,
) = ThemeOption(
    key, label, description,
    ThemeOptionType.Integer(range.first, range.last), default.toString()
)

fun themeSelectOption(
    key: String,
    label: String,
    description: String? = null,
    choices: List<String>,
    default: String,
) = ThemeOption(key, label, description, ThemeOptionType.Select(choices), default)

/**
 * A color option. [defaultArgb] is the packed `0xAARRGGBB` value shown before the user
 * touches the control; [applyTo] names which palette field the *app* overrides with the
 * user's value (see [ThemeColorTarget]) — this is what lets a plain descriptor drive live
 * restyling without any plugin code in the render path.
 */
fun themeColorOption(
    key: String,
    label: String,
    applyTo: ThemeColorTarget,
    description: String? = null,
    defaultArgb: Long = 0xFF000000L,
    /**
     * The value used (and shown as the reset target) in light mode. Defaults to [defaultArgb] for a
     * mode-agnostic color; a theme that renders a lighter variant of a token passes it here so the
     * day palette keeps its own color instead of inheriting the dark one.
     */
    defaultLightArgb: Long? = null,
) = ThemeOption(
    key = key,
    label = label,
    description = description,
    type = ThemeOptionType.Color(),
    defaultValue = ThemeConfig.encodeArgb(defaultArgb),
    colorTarget = applyTo,
    lightDefaultValue = defaultLightArgb?.let { ThemeConfig.encodeArgb(it) },
)

/**
 * An integer option that edits the Islands ambient glow's peak intensity (percent of the
 * accent blended into the frame, `0`..`100`). The app applies the answer straight onto
 * [IslandsPalette.ambientIntensityPercent], so a theme can hand its signature glow to the user
 * without any plugin code in the render path.
 */
fun themeAmbientIntensityOption(
    key: String,
    label: String,
    description: String? = null,
    defaultPercent: Int = 15,
    /**
     * The glow strength used in light mode when untouched. Defaults to [defaultPercent]; a theme
     * whose day palette wants a subtler bleed passes a smaller number so light chrome keeps its own
     * intensity rather than the dark one.
     */
    defaultLightPercent: Int? = null,
) = ThemeOption(
    key = key,
    label = label,
    description = description,
    type = ThemeOptionType.Integer(AMBIENT_INTENSITY_RANGE.first, AMBIENT_INTENSITY_RANGE.last),
    defaultValue = clampPercent(defaultPercent),
    ambientIntensityKey = true,
    lightDefaultValue = defaultLightPercent?.let { clampPercent(it) },
)

private fun clampPercent(percent: Int): String = percent
    .coerceIn(AMBIENT_INTENSITY_RANGE.first.toInt(), AMBIENT_INTENSITY_RANGE.last.toInt())
    .toString()

/** The inclusive, documented range of the ambient glow's intensity, percent. */
val AMBIENT_INTENSITY_RANGE: LongRange = 0L..100L

/**
 * Which slot of the resolved palette a [ThemeOptionType.Color] option overrides. A stable,
 * closed vocabulary owned by the app: a theme can only redirect a user's color into a token
 * the GUI actually renders, which keeps the extension surface from turning into arbitrary
 * palette injection.
 */
enum class ThemeColorTarget {
    ACCENT,
    SUCCESS,
    WARNING,
    ERROR,
    HOVER,
    PANEL_BACKGROUND,
    BORDER,
    INFO_TEXT,
    ISLANDS_FRAME,
    ISLANDS_SURFACE,
    ISLANDS_STRIPE_SELECTED,
    ISLANDS_STRIPE_HOVER,
    ISLANDS_BORDER,
    ISLANDS_AMBIENT_ACCENT,
}

/**
 * A configurable theme's persisted option values, keyed by [ThemeOption.key]. Missing keys
 * fall back to the descriptor's default when read through [value]/[booleanValue]/...
 *
 * Values are plain strings so the on-disk format in `themes.json` stays trivially extensible:
 * a new option type never changes the file schema.
 */
@Serializable
data class ThemeConfig(
    val values: Map<String, String> = emptyMap()
) {
    fun value(key: String, default: String): String = values[key] ?: default

    fun booleanValue(key: String, default: Boolean): Boolean =
        values[key]?.toBooleanStrictOrNull() ?: default

    /** The stored integer, clamped into [range]; [default] when absent or unparsable. */
    fun intValue(key: String, default: Long, range: LongRange): Long =
        (values[key]?.toLongOrNull() ?: default).coerceIn((range.first)..(range.last))

    /** The stored integer as an [Int], clamped into [range]; for small bounded values. */
    fun intKeyValue(key: String, default: Int, range: LongRange): Int =
        intValue(key, default.toLong(), range).toInt()

    /** The stored choice, or [default] when the value is absent or no longer offered. */
    fun selectValue(key: String, choices: List<String>, default: String): String =
        values[key]?.takeIf { it in choices } ?: default

    /**
     * The stored ARGB color, or null when absent/unparsable. Accepts both the editor's
     * `0x`/`#`-prefixed hex and a bare hex literal; never throws.
     */
    fun colorValue(key: String): Long? = values[key]?.let { parseArgbHex(it) }

    companion object {
        /** The editor/persistence form of a color value: `#AARRGGBB`, upper case. */
        fun encodeArgb(argb: Long): String = "#%08X".format(argb and 0xFFFFFFFFL)

        /** Parses `#AARRGGBB` / `0xAARRGGBB` / `AARRGGBB` (and 6-digit RGB, made opaque). */
        fun parseArgbHex(raw: String): Long? {
            val body = raw.trim().removePrefix("#").removePrefix("0x").removePrefix("0X")
            val value = body.toLongOrNull(16) ?: return null
            return when (body.length) {
                8 -> value
                6 -> 0xFF000000L or value
                else -> null
            }
        }
    }
}

/** Helpers shared by the default members and by the registry's merge step. */
object ThemeConfigDefaults {
    /** A config holding every descriptor's default value — the "never touched" state. */
    fun fromDescriptors(descriptors: List<ThemeOption>): ThemeConfig =
        ThemeConfig(descriptors.associate { it.key to it.defaultValue })
}

/** Applies [ThemeConfig] semantics on top of descriptors; used by the registry. */
fun ThemeConfig.mergedWith(descriptors: List<ThemeOption>): ThemeConfig {
    val defaults = descriptors.associate { it.key to it.defaultValue }
    return ThemeConfig(defaults + values)
}
