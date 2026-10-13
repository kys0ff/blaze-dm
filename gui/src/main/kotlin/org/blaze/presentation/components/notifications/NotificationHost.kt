package org.blaze.presentation.components.notifications

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.blaze.i18n.blazeStrings
import org.blaze.platform.clipboard.SystemClipboard
import org.blaze.presentation.components.ToolbarIconButton
import org.blaze.presentation.theme.BlazeColors
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Link
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.typography
import org.koin.compose.koinInject
import kotlin.time.Duration.Companion.milliseconds

private val balloonShape = RoundedCornerShape(8.dp)

private val balloonMinWidth = 280.dp
private val balloonMaxWidth = 380.dp

private const val exitAnimationMillis = 200L

@Composable
fun NotificationHost(
    state: NotificationsState,
    modifier: Modifier = Modifier,
    bottomInset: Dp = 32.dp,
) {
    Column(
        modifier = modifier.padding(
            start = 16.dp,
            end = 16.dp,
            top = 16.dp,
            bottom = bottomInset,
        ),
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
    val strings = blazeStrings
    val scope = rememberCoroutineScope()
    val clipboard = koinInject<SystemClipboard>()
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()

    var visible by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }

    fun close() {
        if (closing) return

        closing = true
        visible = false

        scope.launch {
            delay(exitAnimationMillis.milliseconds)
            onDismiss()
        }
    }

    LaunchedEffect(Unit) {
        visible = true
    }

    LaunchedEffect(hovered) {
        // Info/warning balloons fade on their own; errors stay until dismissed, like the IDE's
        // sticky error notifications (a 12s timeout would drop the detail before it is read).
        if (!hovered && notification.type != NotificationType.ERROR) {
            delay(notification.type.timeoutMillis.milliseconds)
            close()
        }
    }

    val isError = notification.type == NotificationType.ERROR
    val panelColor = Color(0xFF33353B) // TODO: migrate to theme colors so it can be customizable via theme plugins
    val borderColor = if (isError) {
        BlazeColors.error.copy(alpha = 0.6f)
    } else {
        JewelTheme.globalColors.borders.normal
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInHorizontally { it / 2 },
        exit = fadeOut() + slideOutHorizontally { it / 2 },
    ) {
        Row(
            modifier = Modifier
                .widthIn(
                    min = balloonMinWidth,
                    max = balloonMaxWidth,
                )
                .shadow(4.dp, balloonShape)
                .clip(balloonShape)
                .background(panelColor)
                .then(
                    if (isError) {
                        Modifier.background(
                            BlazeColors.error.copy(alpha = 0.08f),
                        )
                    } else {
                        Modifier
                    },
                )
                .border(if (isError) 1.dp else 0.dp, borderColor, balloonShape)
                .hoverable(interactionSource)
                .padding(
                    start = 12.dp,
                    top = 10.dp,
                    end = 4.dp,
                    bottom = 10.dp,
                ),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier.size(20.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    key = notification.type.iconKey,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                notification.title?.let { title ->
                    SelectionContainer {
                        Text(
                            text = title,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                SelectionContainer {
                    Text(
                        text = notification.message,
                        style = JewelTheme.typography.small,
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                if (isError) {
                    SelectionContainer {
                        Link(
                            text = strings.common.copyDetails,
                            onClick = {
                                clipboard.copy(notification.message)
                            },
                        )
                    }
                }
            }

            Box(
                modifier = Modifier.size(28.dp),
                contentAlignment = Alignment.Center,
            ) {
                ToolbarIconButton(
                    AllIconsKeys.General.Close,
                    strings.common.close,
                    ::close,
                )
            }
        }
    }
}