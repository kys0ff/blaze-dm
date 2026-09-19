package org.blaze.presentation.screens.filepicker

import org.blaze.i18n.EnStrings
import org.blaze.presentation.screens.filepicker.filesystem.FileSystemRepository
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class FilePickerTest {

    private val repository = FileSystemRepository()
    private val screenModel = FilePickerScreenModel(repository)
    private val strings = EnStrings

    @Test
    fun testFolderNameValidationEmpty() {
        val result = screenModel.validateFolderName("", Path.of("."), strings)
        assertNull(result)
    }

    @Test
    fun testFolderNameValidationDotAndDotDot() {
        val dotResult = screenModel.validateFolderName(".", Path.of("."), strings)
        assertEquals(strings.filePicker.errorInvalidName, dotResult)

        val dotDotResult = screenModel.validateFolderName("..", Path.of("."), strings)
        assertEquals(strings.filePicker.errorInvalidName, dotDotResult)
    }

    @Test
    fun testFolderNameValidationIllegalCharacters() {
        val slashResult = screenModel.validateFolderName("abc/123", Path.of("."), strings)
        assertNotNull(slashResult)
    }
}
