package org.blaze.presentation.screens.downloads.components

data class ConflictData(
    val url: String,
    val savePath: String,
    val name: String?,
    val fileName: String,
    val fullPathStr: String
)