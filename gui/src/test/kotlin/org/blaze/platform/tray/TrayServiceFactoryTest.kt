package org.blaze.platform.tray

import org.blaze.platform.tray.sni.SniTrayService
import kotlin.test.Test
import kotlin.test.assertTrue

class TrayServiceFactoryTest {

    @Test
    fun `linux prefers the sni backend`() {
        assertTrue(TrayServiceFactory.create("Linux") is SniTrayService)
    }

    @Test
    fun `windows and macos keep the awt backend`() {
        assertTrue(TrayServiceFactory.create("Windows 11") is AwtTrayService)
        assertTrue(TrayServiceFactory.create("Mac OS X") is AwtTrayService)
    }
}
