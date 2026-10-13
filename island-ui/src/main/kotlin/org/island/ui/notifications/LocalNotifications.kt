package org.island.ui.notifications

import androidx.compose.runtime.compositionLocalOf

/**
 * The window-wide balloon queue, provided by the top-level window so every surface reports into
 * one visible stack: screen effects, drag-and-drop outcomes and app-level notices all land in the
 * same host instead of per-screen copies that could overlap each other.
 *
 * Falls back to a throwaway instance when no provider exists (isolated component previews and
 * tests) - the notifications still *work* for their sender, they are just not rendered by anyone.
 */
val LocalNotifications = compositionLocalOf<NotificationsState?> { null }
