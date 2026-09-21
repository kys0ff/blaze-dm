package org.blaze.tray

import org.blaze.tray.internal.awt.AwtTrayService
import org.blaze.tray.internal.sni.SniTrayService
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
