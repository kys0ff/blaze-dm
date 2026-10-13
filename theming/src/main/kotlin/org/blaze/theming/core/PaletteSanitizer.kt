package org.blaze.theming.core

import org.blaze.theming.api.*

/**
 * Turns raw, plugin-supplied palette data into values the GUI can render unconditionally.
 * Plugin output is untrusted, so nothing reaches the composition without passing through here.
 */

/** Alpha of the packed value [argb], 0..255. */
private fun alphaOf(argb: Long): Int = ((argb shr 24) and 0xFF).toInt()

/**
 * A packed ARGB is only *renderable* if it fits the 32-bit range and is not fully transparent —
 * an accidental `0x2E6FD8` (no alpha byte) is transparent paint, which reads as a rendering bug
 * rather than a color, and a negative/out-of-range long cannot become a Compose color at all.
 * Such values are dropped so the fallback token shows through instead.
 *
 * Exception: a theme may *intentionally* paint a transparent surface (the Islands title bar does
 * exactly that to let the frame layer show through), so alpha 0 is only rejected for the token
 * slots the app itself never renders transparent — see [allowsTransparent].
 */
internal fun sanitizeArgb(argb: Long?, allowsTransparent: Boolean = false): Long? {
    if (argb == null) return null
    if (argb < 0L || argb > 0xFFFFFFFFL) return null
    if (!allowsTransparent && alphaOf(argb) == 0) return null
    return argb
}

/** Clamp a user/plugin-supplied glow percentage into the renderable range. */
internal fun sanitizeIntensityPercent(percent: Int?): Int? =
    percent?.coerceIn(AMBIENT_INTENSITY_RANGE.first.toInt(), AMBIENT_INTENSITY_RANGE.last.toInt())

/** Replace every unsanitary token of a plugin palette with "unset" so the fallback applies. */
internal fun ThemePalette.sanitized(): ThemePalette = ThemePalette(
    accentArgb = sanitizeArgb(accentArgb),
    successArgb = sanitizeArgb(successArgb),
    warningArgb = sanitizeArgb(warningArgb),
    errorArgb = sanitizeArgb(errorArgb),
    // Hover and the hairlines are translucent washes by design, so a low alpha is legitimate…
    hoverArgb = sanitizeArgb(hoverArgb, allowsTransparent = true),
    panelBackgroundArgb = sanitizeArgb(panelBackgroundArgb),
    borderArgb = sanitizeArgb(borderArgb, allowsTransparent = true),
    infoTextArgb = sanitizeArgb(infoTextArgb),
    islands = islands.sanitized(),
)

/** Per-token sanitisation of the Islands chrome, mirroring [sanitized]. */
internal fun IslandsPalette.sanitized(): IslandsPalette = IslandsPalette(
    // The frame may be transparent on purpose (it sits on the window's own background).
    frameArgb = sanitizeArgb(frameArgb, allowsTransparent = true),
    surfaceArgb = sanitizeArgb(surfaceArgb),
    // Stripe hover is a translucent wash by design (~12% white over the frame).
    stripeSelectedArgb = sanitizeArgb(stripeSelectedArgb),
    stripeHoverArgb = sanitizeArgb(stripeHoverArgb, allowsTransparent = true),
    borderArgb = sanitizeArgb(borderArgb, allowsTransparent = true),
    ambientAccentArgb = sanitizeArgb(ambientAccentArgb),
    ambientIntensityPercent = sanitizeIntensityPercent(ambientIntensityPercent),
)

/**
 * Fold a configurable theme's answered [options] into [palette].
 *
 * Color options are applied by the *app*, straight onto the palette slot they declare as their
 * [ThemeOption.colorTarget] — this is what makes "let the user pick the exact accent" work for
 * plugins without running their code in the render path. Values that fail validation
 * ([ThemeConfig.colorValue] returning null, or an unrenderable ARGB) are ignored so the theme's
 * own palette stands.
 *
 * [config] must hold only the values the user actually persisted — never merged defaults — while
 * [dark] picks which mode's authored default an untouched option falls back to. That way a color a
 * theme ships for the dark palette is not force-applied over the light one: an untouched option
 * resolves to [dark]'s default ([ThemeOption.lightDefaultValue] for light, else
 * [ThemeOption.defaultValue]), and an explicit pick wins in *both* modes.
 */
internal fun applyColorOptions(
    palette: ThemePalette,
    options: List<ThemeOption>,
    config: ThemeConfig,
    dark: Boolean,
): ThemePalette {
    var result = palette
    for (option in options) {
        if (option.type !is ThemeOptionType.Color) continue
        val target = option.colorTarget ?: continue
        val argb = config.colorValue(option.key) ?: option.defaultArgbForMode(dark)
        result = sanitizeArgb(argb)?.let { result.applyColorTarget(target, it) } ?: result
    }
    return result
}

/** The ARGB an untouched color option falls back to for [dark], or null when it declares none. */
private fun ThemeOption.defaultArgbForMode(dark: Boolean): Long? {
    val hex = if (dark) defaultValue else lightDefaultValue ?: defaultValue
    return ThemeConfig.parseArgbHex(hex)
}

/** Write [argb] into the palette slot named by [target]. */
private fun ThemePalette.applyColorTarget(target: ThemeColorTarget, argb: Long): ThemePalette {
    val islandsCopy = islands
    return when (target) {
        ThemeColorTarget.ACCENT -> copy(accentArgb = argb)
        ThemeColorTarget.SUCCESS -> copy(successArgb = argb)
        ThemeColorTarget.WARNING -> copy(warningArgb = argb)
        ThemeColorTarget.ERROR -> copy(errorArgb = argb)
        ThemeColorTarget.HOVER -> copy(hoverArgb = argb)
        ThemeColorTarget.PANEL_BACKGROUND -> copy(panelBackgroundArgb = argb)
        ThemeColorTarget.BORDER -> copy(borderArgb = argb)
        ThemeColorTarget.INFO_TEXT -> copy(infoTextArgb = argb)
        ThemeColorTarget.ISLANDS_FRAME -> copy(islands = islandsCopy.copy(frameArgb = argb))
        ThemeColorTarget.ISLANDS_SURFACE -> copy(islands = islandsCopy.copy(surfaceArgb = argb))
        ThemeColorTarget.ISLANDS_STRIPE_SELECTED ->
            copy(islands = islandsCopy.copy(stripeSelectedArgb = argb))
        ThemeColorTarget.ISLANDS_STRIPE_HOVER ->
            copy(islands = islandsCopy.copy(stripeHoverArgb = argb))
        ThemeColorTarget.ISLANDS_BORDER -> copy(islands = islandsCopy.copy(borderArgb = argb))
        ThemeColorTarget.ISLANDS_AMBIENT_ACCENT ->
            copy(islands = islandsCopy.copy(ambientAccentArgb = argb))
    }
}

/**
 * Fold any [themeAmbientIntensityOption][org.blaze.theming.api.themeAmbientIntensityOption]
 * the user answered into the Islands glow strength, clamped into the renderable range. The
 * first such option holding a parsable value wins, so a theme publishes at most one.
 *
 * As with [applyColorOptions], [config] carries only the user's persisted answers: an untouched
 * option falls back to [dark]'s authored default rather than clobbering the opposite mode.
 */
internal fun applyAmbientIntensity(
    palette: ThemePalette,
    options: List<ThemeOption>,
    config: ThemeConfig,
    dark: Boolean,
): ThemePalette {
    val option = options.firstOrNull { it.ambientIntensityKey } ?: return palette
    val stored = config.values[option.key]?.toLongOrNull()
    val defaultHex = if (dark) option.defaultValue else option.lightDefaultValue ?: option.defaultValue
    val percent = sanitizeIntensityPercent(
        (stored ?: defaultHex.toLongOrNull())?.toInt(),
    ) ?: return palette
    return palette.copy(islands = palette.islands.copy(ambientIntensityPercent = percent))
}
