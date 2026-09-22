package org.blaze.platform.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import org.blaze.platform.api.PlatformIdentity
import org.blaze.platform.clipboard.AwtSystemClipboard
import org.blaze.platform.clipboard.SystemClipboard
import org.blaze.platform.files.SystemFileService
import org.blaze.platform.files.SystemFileServiceFactory

/**
 * Composition-local plumbing for apps that don't run Koin (or want to override a
 * binding locally). [PlatformProviders] installs the platform services once near the
 * root; leaf composables read them through the locals instead of injecting.
 */
val LocalPlatformIdentity = staticCompositionLocalOf<PlatformIdentity?> { null }

val LocalSystemFileService = staticCompositionLocalOf<SystemFileService> {
    error("No SystemFileService provided; wrap the app in PlatformProviders")
}

val LocalSystemClipboard = staticCompositionLocalOf<SystemClipboard> {
    error("No SystemClipboard provided; wrap the app in PlatformProviders")
}

/**
 * Root-level provider for the platform integrations. The default instances are the
 * host-OS backends from the service factories; apps using Koin can pass
 * koin-resolved instances here instead so both access paths see the same objects.
 */
@Composable
fun PlatformProviders(
    identity: PlatformIdentity,
    fileService: SystemFileService = remember(identity) { SystemFileServiceFactory.create() },
    clipboard: SystemClipboard = remember { AwtSystemClipboard() },
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalPlatformIdentity provides identity,
        LocalSystemFileService provides fileService,
        LocalSystemClipboard provides clipboard,
        content = content,
    )
}
