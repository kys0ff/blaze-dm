package org.blaze.presentation.application

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.runBlocking
import org.blaze.engine.api.DownloadEngine
import org.blaze.i18n.LocalBlazeStrings
import org.blaze.i18n.LocaleManager
import org.blaze.i18n.getStrings
import org.blaze.presentation.application.components.BlazeWindow
import org.blaze.presentation.theme.BlazeTheme
import org.koin.compose.koinInject

@Composable
fun ApplicationScope.BlazeApplication() {
    val localeManager = koinInject<LocaleManager>()
    val currentLocale by localeManager.currentLocale.collectAsState()
    val strings = remember(currentLocale) { getStrings(currentLocale) }

    var isDark by remember { mutableStateOf(true) }

    val windowState = rememberWindowState(
        size = DpSize(1100.dp, 720.dp),
    )

    val engine = koinInject<DownloadEngine>()

    CompositionLocalProvider(LocalBlazeStrings provides strings) {
        BlazeTheme(isDark = isDark) {
            BlazeWindow(
                windowState = windowState,
                onCloseRequest = {
                    runBlocking {
                        engine.shutdown()
                    }
                    exitApplication()
                },
                isDark = isDark,
                onToggleDark = {
                    isDark = !isDark
                },
            )
        }
    }
}
