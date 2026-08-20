package org.zzssg.llmchatapp.ui.screens

import androidx.compose.foundation.lazy.LazyListState

/**
 * How close to the end still counts as "the user is watching the latest text".
 *
 * A few pixels of slack absorbs rounding and the last line's descender, so a
 * transcript that is visually at the bottom is not treated as scrolled away.
 */
const val BOTTOM_SLACK_PX = 24

/**
 * Whether the transcript is scrolled to its true end.
 *
 * Taking the *last visible index* as the answer is wrong once a message grows
 * taller than the viewport, which is exactly what happens while a reply streams
 * in: the final item stays visible no matter how far the user scrolls back
 * through it, so "at bottom" never becomes false and every arriving token yanks
 * the list. The bottom edge of that item has to be inside the viewport too.
 *
 * Expressed over plain numbers so it can be tested without a device.
 */
fun isScrolledToBottom(
    lastVisibleIndex: Int?,
    lastVisibleItemBottom: Int,
    totalItems: Int,
    viewportEnd: Int,
    slack: Int = BOTTOM_SLACK_PX,
): Boolean {
    if (totalItems == 0 || lastVisibleIndex == null) return true
    if (lastVisibleIndex != totalItems - 1) return false
    return lastVisibleItemBottom <= viewportEnd + slack
}

/** [isScrolledToBottom] applied to a live list. */
fun LazyListState.isScrolledToBottom(): Boolean {
    val info = layoutInfo
    val last = info.visibleItemsInfo.lastOrNull()
    return isScrolledToBottom(
        lastVisibleIndex = last?.index,
        lastVisibleItemBottom = if (last == null) 0 else last.offset + last.size,
        totalItems = info.totalItemsCount,
        viewportEnd = info.viewportEndOffset,
    )
}

/**
 * Scrolls so the *end* of [index] is visible, rather than its start.
 *
 * `scrollToItem(index)` aligns the item's top with the viewport top. For the
 * message currently being written that means jumping to the beginning of the
 * reply on every token -- the opposite of following it.
 */
suspend fun LazyListState.scrollToItemEnd(index: Int) {
    // The item has to be measured before its height is known, so bring it into
    // view first when it is off-screen.
    if (layoutInfo.visibleItemsInfo.none { it.index == index }) {
        scrollToItem(index)
    }

    val info = layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.index == index } ?: return
    val viewportHeight = info.viewportEndOffset - info.viewportStartOffset
    val overshoot = item.size + info.afterContentPadding - viewportHeight

    scrollToItem(index, overshoot.coerceAtLeast(0))
}
