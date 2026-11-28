package org.matrix.vector.manager.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.matrix.vector.ui.store.releaseMarkdownToHtml

/** Release notes become HTML with link and image targets inside quoted attributes. */
class ReleaseMarkdownTest {
    private val repo = "https://example.com/owner/repo"

    @Test
    fun quoteInALinkTargetStaysInsideTheAttribute() {
        val html = releaseMarkdownToHtml("[x](https://example.com/a\"style=\"position:fixed)", repo)
        assertFalse(html, html.contains("\"style=\""))
        assertTrue(html, html.contains("&quot;style=&quot;"))
    }

    @Test
    fun quoteInAnImageTargetStaysInsideTheAttribute() {
        val html = releaseMarkdownToHtml("![a](https://example.com/i.png\"onerror=\"x)", repo)
        assertFalse(html, html.contains("\"onerror=\""))
        // The image rule itself emitted it, with the quote inside the src attribute.
        assertTrue(
            html,
            html.contains("<img src=\"https://example.com/i.png&quot;onerror=&quot;x\""),
        )
    }

    @Test
    fun quotesInTextAreEscapedOnce() {
        val html = releaseMarkdownToHtml("say \"hi\" & 'bye'", repo)
        assertEquals(1, Regex("&quot;hi&quot;").findAll(html).count())
        assertFalse(html, html.contains("&amp;quot;"))
    }

    @Test
    fun quotedBareUrlLinksWithoutTheQuote() {
        val html = releaseMarkdownToHtml("see \"https://example.com/x\" here", repo)
        assertTrue(html, html.contains("href=\"https://example.com/x\""))
    }

    @Test
    fun angleBracketedUrlLinksWithoutTheBracket() {
        val html = releaseMarkdownToHtml("see <https://example.com/a> here", repo)
        assertTrue(html, html.contains("href=\"https://example.com/a\""))
        assertFalse(html, html.contains("&gt;\""))
    }
}
