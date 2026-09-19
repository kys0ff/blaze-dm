package org.blaze.presentation.application.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import cafe.adriel.voyager.navigator.Navigator
import org.blaze.presentation.screens.main.MainScreen
import org.jetbrains.jewel.foundation.theme.JewelTheme

@Composable
fun BlazeContent() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(JewelTheme.globalColors.panelBackground),
    ) {
        Navigator(MainScreen())
    }
}