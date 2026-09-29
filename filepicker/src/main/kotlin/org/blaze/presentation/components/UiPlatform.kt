package org.blaze.presentation.components

/**
 * The single place the UI learns which desktop it is on.
 *
 * Compose Desktop is JVM-only, so this is a plain `when` on `os.name` (the same detection style the
 * `:platform` module's service factories use) rather than expect/actual. Anything platform-shaped —
 * accelerator labels, dialog footer order, the title bar's window controls — reads
 * [CurrentUiPlatform] instead of sniffing `os.name` again, so there is exactly one switch to audit.
 *
 * It lives in the module every screen module already depends on (`Island`, `BlazeLayout`,
 * `IdeDialog` are here) so a `:gui`-only helper could not be the one place both the dialogs and the
 * chrome consult.
 */
enum class UiPlatform { Windows, macOS, Linux } // TODO: Warning: Enum entry name 'macOS' should start with an uppercase letter

val CurrentUiPlatform: UiPlatform by lazy {
    val os = System.getProperty("os.name").orEmpty().lowercase()
    when {
        os.contains("mac") || os.contains("darwin") -> UiPlatform.macOS
        os.startsWith("windows") -> UiPlatform.Windows
        else -> UiPlatform.Linux
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
 * The default button is always the accent-filled [org.jetbrains.jewel.ui.component.DefaultButton];
 * this decides only where the three sit, and the caller renders in this order.
 */
fun dialogButtonOrder(hasApply: Boolean): List<DialogButton> = when (CurrentUiPlatform) {
    UiPlatform.macOS -> if (hasApply) {
        listOf(DialogButton.Cancel, DialogButton.Apply, DialogButton.Ok)
    } else {
        listOf(DialogButton.Cancel, DialogButton.Ok)
    }

    UiPlatform.Windows, UiPlatform.Linux -> if (hasApply) {
        listOf(DialogButton.Ok, DialogButton.Cancel, DialogButton.Apply)
    } else {
        listOf(DialogButton.Ok, DialogButton.Cancel)
    }
}
