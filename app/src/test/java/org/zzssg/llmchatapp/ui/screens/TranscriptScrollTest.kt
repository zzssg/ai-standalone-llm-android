package org.zzssg.llmchatapp.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptScrollTest {

    private val viewport = 2000

    @Test
    fun `an empty transcript counts as at the bottom`() {
        assertTrue(
            isScrolledToBottom(
                lastVisibleIndex = null,
                lastVisibleItemBottom = 0,
                totalItems = 0,
                viewportEnd = viewport,
            )
        )
    }

    @Test
    fun `the last item fully in view is at the bottom`() {
        assertTrue(
            isScrolledToBottom(
                lastVisibleIndex = 4,
                lastVisibleItemBottom = 1800,
                totalItems = 5,
                viewportEnd = viewport,
            )
        )
    }

    @Test
    fun `an earlier item being last visible means scrolled up`() {
        assertFalse(
            isScrolledToBottom(
                lastVisibleIndex = 2,
                lastVisibleItemBottom = 1900,
                totalItems = 5,
                viewportEnd = viewport,
            )
        )
    }

    /**
     * The regression this file exists for.
     *
     * While a reply streams in it grows taller than the screen. Scrolling back
     * through it keeps the final message the last *visible* item, so an
     * index-only check reported "at bottom" and every arriving token yanked the
     * list to the top of that message.
     */
    @Test
    fun `reading back inside a tall streaming message is not at the bottom`() {
        assertFalse(
            "the last item is visible but its end is far below the viewport",
            isScrolledToBottom(
                lastVisibleIndex = 4,
                lastVisibleItemBottom = 9000,
                totalItems = 5,
                viewportEnd = viewport,
            )
        )
    }

    @Test
    fun `a few pixels past the edge still counts as the bottom`() {
        assertTrue(
            "rounding and descenders must not flip the state",
            isScrolledToBottom(
                lastVisibleIndex = 4,
                lastVisibleItemBottom = viewport + BOTTOM_SLACK_PX - 1,
                totalItems = 5,
                viewportEnd = viewport,
            )
        )
    }

    @Test
    fun `beyond the slack it is not the bottom`() {
        assertFalse(
            isScrolledToBottom(
                lastVisibleIndex = 4,
                lastVisibleItemBottom = viewport + BOTTOM_SLACK_PX + 1,
                totalItems = 5,
                viewportEnd = viewport,
            )
        )
    }

    @Test
    fun `a single message taller than the screen behaves the same`() {
        assertFalse(
            isScrolledToBottom(
                lastVisibleIndex = 0,
                lastVisibleItemBottom = 6000,
                totalItems = 1,
                viewportEnd = viewport,
            )
        )
        assertTrue(
            isScrolledToBottom(
                lastVisibleIndex = 0,
                lastVisibleItemBottom = viewport,
                totalItems = 1,
                viewportEnd = viewport,
            )
        )
    }
}
