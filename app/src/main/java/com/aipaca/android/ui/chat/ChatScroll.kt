package com.aipaca.android.ui.chat

internal fun isAtChatTail(
    firstVisibleItemIndex: Int,
    firstVisibleItemScrollOffset: Int,
    thresholdPx: Int,
): Boolean = firstVisibleItemIndex == 0 && firstVisibleItemScrollOffset <= thresholdPx
