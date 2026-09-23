package org.blaze.platform.autostart

import org.blaze.platform.desktop.LaunchCommand

/**
 * The one gate every platform's [AutoStartService.enable] passes its command through.
 *
 * A login item is the least forgiving registration there is: the desktop runs it blindly at every
 * sign-in, so a command written during development (`<jdk>/bin/java`, no classpath, no main class)
 * produces a failure the user cannot attribute to anything. Refusing to register it is the honest
 * outcome, and the returned message says exactly why - callers surface it as a warning rather than
 * letting it look like a settings bug.
 *
 * @throws IllegalArgumentException when [command] cannot launch the app by itself.
 */
internal fun requireLaunchableLoginItem(command: String): String {
    require(LaunchCommand.isStandaloneExecutable(command)) {
        "Refusing to register an unlaunchable login item: '$command' is a development launch " +
            "(an interpreter or class-path command), not a standalone launcher. " +
            "Run at startup only takes effect for the packaged app."
    }
    return command
}
