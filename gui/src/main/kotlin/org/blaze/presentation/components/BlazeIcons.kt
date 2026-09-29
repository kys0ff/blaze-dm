package org.blaze.presentation.components

import org.jetbrains.jewel.ui.icon.IconKey
import org.jetbrains.jewel.ui.icon.PathIconKey
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/**
 * The handful of glyphs whose Jewel/IntelliJ key does not resolve to the picture the chrome asks for.
 *
 * Jewel picks the *New UI* path of every [IntelliJIconKey][org.jetbrains.jewel.ui.icon.IntelliJIconKey]
 * it is given (`JewelTheme.newUiChecker`), and for a few icons the New UI and Classic drawings differ
 * in kind, not just in style. Each entry below names the key it replaces and why, and every path is
 * verified to exist in `com.jetbrains.intellij.platform:icons` (with its `_dark` sibling, which Jewel
 * applies automatically through `StandalonePainterHintsProvider`'s `Dark` hint).
 */
internal object BlazeIcons {

    /**
     * The IDE's stop glyph: a plain filled square, exactly the drawing the Run tool window uses.
     *
     * Replaces [AllIconsKeys.Actions.Suspend] / `AllIconsKeys.Run.Stop`, whose *New UI* path is
     * `expui/run/stop.svg` — a rounded rect with a dark-red `#5E3838` body under a `#DB5C5C` stroke,
     * i.e. a box around the box. The Classic path below is `actions/suspend.svg`: one 10x10 `fill`
     * rect (`#DB5860` light, `#C75450` dark), no rounding, no stroke layer.
     *
     * [PathIconKey] ignores the New/Classic choice entirely, so this call site always gets the square.
     */
    val Stop: IconKey = PathIconKey("actions/suspend.svg", AllIconsKeys::class.java)

    /**
     * "Full speed" — no throttle applied. [AllIconsKeys.Actions.Lightning] (`actions/lightning.svg`,
     * New UI `expui/actions/lightning.svg`): the IDE's own "no delay / instant" bolt.
     *
     * It replaces the gear this widget used to show, which made the speed selector read as a second
     * Settings entry (the chrome already has one in the title bar and one in the stripe).
     */
    val FullSpeed: IconKey = AllIconsKeys.Actions.Lightning

    /**
     * "Snail mode" — the global speed throttle is engaged. [AllIconsKeys.Actions.StopWatch]
     * (`actions/stopWatch.svg`, New UI `expui/actions/stopWatch.svg`): a timer glyph, which is the
     * IDE's family for "slow / measured" and pairs with [FullSpeed] as the two states of one control.
     */
    val SnailMode: IconKey = AllIconsKeys.Actions.StopWatch
}
