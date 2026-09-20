package org.blaze.presentation.components.notifications

import org.jetbrains.jewel.ui.icon.IconKey
import org.jetbrains.jewel.ui.icons.AllIconsKeys

enum class NotificationType(
    val timeoutMillis: Long,
    val iconKey: IconKey,
) {
    INFO(5_000, AllIconsKeys.General.BalloonInformation),
    WARNING(8_000, AllIconsKeys.General.BalloonWarning),
    ERROR(12_000, AllIconsKeys.General.BalloonError),
}