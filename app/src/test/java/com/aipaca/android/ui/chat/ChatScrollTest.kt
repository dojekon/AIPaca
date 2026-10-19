package com.aipaca.android.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatScrollTest {

    @Test
    fun exactTailIsDetected() {
        assertTrue(
            isAtChatTail(
                firstVisibleItemIndex = 0,
                firstVisibleItemScrollOffset = 0,
                thresholdPx = 64,
            ),
        )
    }

    @Test
    fun smallOffsetWithinThresholdIsTreatedAsTail() {
        assertTrue(
            isAtChatTail(
                firstVisibleItemIndex = 0,
                firstVisibleItemScrollOffset = 40,
                thresholdPx = 64,
            ),
        )
    }

    @Test
    fun offsetOnThresholdBoundaryIsTreatedAsTail() {
        assertTrue(
            isAtChatTail(
                firstVisibleItemIndex = 0,
                firstVisibleItemScrollOffset = 64,
                thresholdPx = 64,
            ),
        )
    }

    @Test
    fun offsetAboveThresholdIsNotTail() {
        assertFalse(
            isAtChatTail(
                firstVisibleItemIndex = 0,
                firstVisibleItemScrollOffset = 65,
                thresholdPx = 64,
            ),
        )
    }

    @Test
    fun scrolledIntoHistoryIsNotTail() {
        assertFalse(
            isAtChatTail(
                firstVisibleItemIndex = 3,
                firstVisibleItemScrollOffset = 0,
                thresholdPx = 64,
            ),
        )
    }

    @Test
    fun zeroThresholdRequiresExactTail() {
        assertTrue(
            isAtChatTail(
                firstVisibleItemIndex = 0,
                firstVisibleItemScrollOffset = 0,
                thresholdPx = 0,
            ),
        )
        assertFalse(
            isAtChatTail(
                firstVisibleItemIndex = 0,
                firstVisibleItemScrollOffset = 1,
                thresholdPx = 0,
            ),
        )
    }
}
