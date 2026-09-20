package org.blaze.presentation.components.notifications

import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateListOf

@Stable
class NotificationsState(
    private val maxVisible: Int = 4,
) {
    private var nextId = 0L

    val items = mutableStateListOf<Notification>()

    init {
        require(maxVisible > 0)
    }

    fun show(
        type: NotificationType,
        message: String,
        title: String? = null,
    ) {
        if (items.any { it.type == type && it.message == message }) return

        items += Notification(
            id = nextId++,
            type = type,
            message = message,
            title = title,
        )

        while (items.size > maxVisible) {
            items.removeAt(0)
        }
    }

    fun info(message: String, title: String? = null) =
        show(NotificationType.INFO, message, title)

    fun warning(message: String, title: String? = null) =
        show(NotificationType.WARNING, message, title)

    fun error(message: String, title: String? = null) =
        show(NotificationType.ERROR, message, title)

    fun dismiss(id: Long) {
        items.removeAll { it.id == id }
    }
}