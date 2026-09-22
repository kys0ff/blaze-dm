package org.blaze.platform.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PlatformIdentityTest {

    private val identity = PlatformIdentity(
        appId = "org.blaze",
        appName = "Blaze",
        description = "Blaze download manager",
        genericName = "Download Manager",
        categories = "Network;FileTransfer;",
        mainClass = "org.blaze.MainKt",
    )

    @Test
    fun registrationNamesAreDerivedFromTheAppId() {
        assertEquals("org.blaze.desktop", identity.desktopFileName)
        assertEquals("application://org.blaze.desktop", identity.launcherUri)
        assertEquals("/org/blaze/Launcher", identity.launcherObjectPath)
        assertEquals("org.blaze.autostart", identity.launchAgentLabel)
        assertEquals("blaze", identity.executableName)
    }

    @Test
    fun wmClassFollowsTheAwtMainClassDerivation() {
        assertEquals("org-blaze-MainKt", identity.windowManagerClass)
    }

    @Test
    fun executableDefaultsToTheLastAppIdSegment() {
        assertEquals("blaze", identity.executableName)
    }

    @Test
    fun malformedAppIdsAreRejected() {
        assertFailsWith<IllegalArgumentException> {
            identity.copy(appId = "not an id")
        }
        assertFailsWith<IllegalArgumentException> {
            identity.copy(appId = "singlesegment")
        }
    }
}
