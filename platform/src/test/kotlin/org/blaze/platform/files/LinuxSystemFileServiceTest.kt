package org.blaze.platform.files

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import java.nio.file.Path

class LinuxSystemFileServiceTest {

    private val file = Path.of("/home/user/Downloads/5MB.zip")

    @Test
    fun `kde reveals with dolphin select rather than kioclient`() {
        // kioclient has no `select` command (it errors with "unknown command"); Dolphin does.
        assertEquals(
            listOf("dolphin", "--select", "file:///home/user/Downloads/5MB.zip"),
            LinuxSystemFileService.selectCommandFor("KDE", file),
        )
        assertEquals(
            listOf("dolphin", "--select", "file:///home/user/Downloads/5MB.zip"),
            LinuxSystemFileService.selectCommandFor("Plasma", file),
        )
    }

    @Test
    fun `gnome, cinnamon and mate use their file managers' select flag`() {
        assertEquals(
            listOf("nautilus", "--select", "/home/user/Downloads/5MB.zip"),
            LinuxSystemFileService.selectCommandFor("GNOME", file),
        )
        assertEquals(
            listOf("nemo", "--select", "/home/user/Downloads/5MB.zip"),
            LinuxSystemFileService.selectCommandFor("X-Cinnamon", file),
        )
        assertEquals(
            listOf("caja", "--select", "/home/user/Downloads/5MB.zip"),
            LinuxSystemFileService.selectCommandFor("MATE", file),
        )
    }

    @Test
    fun `an unrecognised desktop has no select command`() {
        assertNull(LinuxSystemFileService.selectCommandFor("XFCE", file))
        assertNull(LinuxSystemFileService.selectCommandFor("", file))
    }
}
