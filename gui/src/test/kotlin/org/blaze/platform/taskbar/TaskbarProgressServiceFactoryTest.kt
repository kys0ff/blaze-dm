package org.blaze.platform.taskbar

import kotlin.test.Test
import kotlin.test.assertTrue

class TaskbarProgressServiceFactoryTest {

    @Test
    fun `linux prefers the unity launcher backend`() {
        assertTrue(TaskbarProgressServiceFactory.create("Linux") is LinuxTaskbarProgressService)
    }

    @Test
    fun `windows and macos use the awt backend`() {
        assertTrue(TaskbarProgressServiceFactory.create("Windows 11") is AwtTaskbarProgressService)
        assertTrue(TaskbarProgressServiceFactory.create("Mac OS X") is AwtTaskbarProgressService)
    }

    @Test
    fun `unknown platforms fall back to unsupported`() {
        val service = TaskbarProgressServiceFactory.create("FreeBSD")
        assertTrue(service is UnsupportedTaskbarProgressService)
        assertTrue(!service.isSupported)
    }
}
