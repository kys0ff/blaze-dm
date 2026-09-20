package org.blaze.presentation.components.notifications

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.blaze.presentation.components.ToolbarIconButton
import org.blaze.presentation.theme.IdeColors
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Link
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icon.IconKey
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import kotlin.time.Duration.Companion.milliseconds

// ─────────────────────────────────────────────────────────────────────────────
// Model
// ─────────────────────────────────────────────────────────────────────────────

enum class NotificationType(val timeoutMillis: Long) {
    INFO(5_000),
    WARNING(8_000),
    ERROR(12_000);

    val iconKey: IconKey
        get() = when (this) {
            INFO -> AllIconsKeys.General.BalloonInformation
            WARNING -> AllIconsKeys.General.BalloonWarning
            ERROR -> AllIconsKeys.General.BalloonError
        }
}

@Stable
class Notification internal constructor(
    val id: Long,
    val type: NotificationType,
    val message: String,
    val title: String?,
)

/**
 * Holds the balloons currently on screen. Hoist it wherever you want notifications to
 * be reachable from (a screen, or app-wide via Koin / a CompositionLocal).
 */
@Stable
class NotificationsState(private val maxVisible: Int = 4) {
    private var nextId = 0L
    val items = mutableStateListOf<Notification>()

    fun show(type: NotificationType, message: String, title: String? = null) {
        // Same balloon already showing → don't stack duplicates (e.g. repeated tracker errors).
        if (items.any { it.type == type && it.message == message }) return
        items += Notification(nextId++, type, message, title)
        while (items.size > maxVisible) items.removeAt(0)
    }

    fun info(message: String, title: String? = null) = show(NotificationType.INFO, message, title)
    fun warning(message: String, title: String? = null) = show(NotificationType.WARNING, message, title)
    fun error(message: String, title: String? = null) = show(NotificationType.ERROR, message, title)

    fun dismiss(id: Long) {
        items.removeAll { it.id == id }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// UI
// ─────────────────────────────────────────────────────────────────────────────

private val BalloonShape = RoundedCornerShape(8.dp)
private val BalloonMaxWidth = 380.dp
private const val ExitAnimationMillis = 200L

/**
 * Overlay that renders the balloons. Place it as the last child of a `Box(Modifier.fillMaxSize())`
 * with `Modifier.align(Alignment.BottomEnd)`; it only occupies the space of the balloons themselves.
 *
 * @param bottomInset keeps balloons clear of a bottom status bar, like the IDE does.
 */
@Composable
fun NotificationHost(
    state: NotificationsState,
    modifier: Modifier = Modifier,
    bottomInset: Dp = 32.dp,
) {
    Column(
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = bottomInset),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.End,
    ) {
        state.items.forEach { notification ->
            key(notification.id) {
                NotificationBalloon(
                    notification = notification,
                    onDismiss = { state.dismiss(notification.id) },
                )
            }
        }
    }
}

@Composable
private fun NotificationBalloon(
    notification: Notification,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    var visible by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }

    fun close() {
        if (closing) return
        closing = true
        scope.launch {
            visible = false
            delay(ExitAnimationMillis.milliseconds) // let the exit animation play before removing
            onDismiss()
        }
    }

    LaunchedEffect(Unit) { visible = true }

    // Auto-dismiss; hovering cancels the timer, and it restarts once the pointer leaves.
    LaunchedEffect(hovered) {
        if (!hovered) {
            delay(notification.type.timeoutMillis.milliseconds)
            close()
        }
    }

    val isError = notification.type == NotificationType.ERROR
    val panel = JewelTheme.globalColors.panelBackground
    val border = if (isError) IdeColors.error.copy(alpha = 0.6f) else JewelTheme.globalColors.borders.normal

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInHorizontally { it / 2 },
        exit = fadeOut() + slideOutHorizontally { it / 2 },
    ) {
        Row(
            modifier = Modifier
                .widthIn(min = 280.dp, max = BalloonMaxWidth)
                .shadow(8.dp, BalloonShape)
                .clip(BalloonShape)
                .background(panel)
                // Subtle red wash for errors, like IDE error balloons.
                .background(if (isError) IdeColors.error.copy(alpha = 0.08f) else panel.copy(alpha = 0f))
                .border(1.dp, border, BalloonShape)
                .hoverable(interaction)
                .padding(start = 12.dp, top = 10.dp, end = 4.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                key = notification.type.iconKey,
                contentDescription = null,
                modifier = Modifier.padding(top = 2.dp).size(16.dp),
            )

            Column(
                modifier = Modifier.weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                notification.title?.let { title ->
                    Text(text = title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    text = notification.message,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                )
                if (isError) {
                    Link(
                        text = "Copy details", // TODO: move to blazeStrings
                        onClick = { clipboard.setText(AnnotatedString(notification.message)) },
                    )
                }
            }

            ToolbarIconButton(AllIconsKeys.General.Close, "Close", ::close) // TODO: blazeStrings
        }
    }
}