package org.island.foundation

/**
 * The single place an Island app learns which desktop it is on.
 *
 * Compose Desktop is JVM-only, so this is a plain `when` on `os.name` (the same detection style the
 * app's platform-integration service factories use) rather than expect/actual. Anything
 * platform-shaped — accelerator labels, dialog footer order, the title bar's window controls — reads
 * [CurrentIslandPlatform] instead of sniffing `os.name` again, so there is exactly one switch to
 * audit.
 *
 * This module is deliberately Compose-free: pure host-detection and native-chrome plumbing that any
 * layer of the stack (including tests without a UI) can consult.
 */
enum class IslandPlatform { Windows, MacOS, Linux }

val CurrentIslandPlatform: IslandPlatform by lazy {
    val os = System.getProperty("os.name").orEmpty().lowercase()
    when {
        os.contains("mac") || os.contains("darwin") -> IslandPlatform.MacOS
        os.startsWith("windows") -> IslandPlatform.Windows
        else -> IslandPlatform.Linux
    }
}

/** One button in a dialog footer. */
enum class DialogButton { Ok, Cancel, Apply }

/**
 * The order IntelliJ lays a dialog's footer buttons out in, left to right, right-aligned.
 *
 * Windows and Linux read `OK, Cancel, Apply`; macOS reverses the row to `Cancel, Apply, OK` so the
 * default button ends up in the corner the pointer already rests in. A dialog with no Apply (which is
 * most of them — only Settings commits without closing) simply drops that entry and keeps the same
 * direction, giving `OK, Cancel` and `Cancel, OK`.
 *
 * The default button is always the accent-filled Jewel `DefaultButton`; this decides only where the
 * three sit, and the caller renders in this order.
 */
fun dialogButtonOrder(hasApply: Boolean): List<DialogButton> = when (CurrentIslandPlatform) {
    IslandPlatform.MacOS -> if (hasApply) {
        listOf(DialogButton.Cancel, DialogButton.Apply, DialogButton.Ok)
    } else {
        listOf(DialogButton.Cancel, DialogButton.Ok)
    }

    IslandPlatform.Windows, IslandPlatform.Linux -> if (hasApply) {
        listOf(DialogButton.Ok, DialogButton.Cancel, DialogButton.Apply)
    } else {
        listOf(DialogButton.Ok, DialogButton.Cancel)
    }
}
