package com.aipaca.android.data.download

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.aipaca.android.AiPacaApp
import java.io.IOException
import kotlinx.coroutines.CancellationException

class DownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val id = inputData.getLong(KEY_DOWNLOAD_ID, INVALID_ID)
        if (id <= 0L) return Result.failure()
        val manager = (applicationContext as AiPacaApp).container.downloadManager
        return try {
            manager.runQueued(id).fold(
                onSuccess = { Result.success() },
                onFailure = { error -> if (error is IOException) Result.retry() else Result.failure() },
            )
        } catch (e: CancellationException) {
            manager.onWorkStopped(id)
            throw e
        }
    }

    companion object {

        const val KEY_DOWNLOAD_ID = "download_id"
        private const val INVALID_ID = -1L
    }
}
