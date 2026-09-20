package org.blaze.engine.execution

import kotlinx.coroutines.flow.Flow
import org.blaze.engine.api.DownloadTask

interface DownloadExecutor {
    fun execute(): Flow<DownloadTask>
}