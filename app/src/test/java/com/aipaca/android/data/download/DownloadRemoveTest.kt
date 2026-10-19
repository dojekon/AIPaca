package com.aipaca.android.data.download

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadRemoveTest {

    @Test
    fun deletesPartialFileForUnfinishedStates() {
        val states = listOf(
            DownloadManager.STATE_QUEUED,
            DownloadManager.STATE_RUNNING,
            DownloadManager.STATE_PAUSED,
            DownloadManager.STATE_ERROR,
            DownloadManager.STATE_CANCELLED,
        )

        states.forEach { state ->
            assertTrue(state, shouldDeleteFilesOnRemove(state))
        }
    }

    @Test
    fun keepsFileForCompletedState() {
        assertFalse(shouldDeleteFilesOnRemove(DownloadManager.STATE_COMPLETED))
    }
}
