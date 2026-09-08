package com.abplus.botchchat.ui

import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import org.junit.Assert.*
import org.junit.Test

class JapaneseQuoteEmphasisTest {
    private fun render(markdown: String): String {
        val document = Parser.builder().build().parse(normalizeJapaneseQuoteEmphasis(markdown))
        return HtmlRenderer.builder().build().render(document)
    }

    @Test fun emphasizesQuotedTextAdjacentToJapanese() {
        assertEquals("<p>これは <strong>「重要」</strong> です。</p>\n", render("これは**「重要」**です。"))
    }

    @Test fun handlesMultipleQuotesAndOrdinaryEmphasis() {
        assertEquals("<p><strong>「一つ」</strong> と <strong>「二つ」</strong> 、<strong>通常</strong></p>\n",
            render("**「一つ」**と**「二つ」**、**通常**"))
    }

    @Test fun emphasizesConsecutiveQuotesAsOneSpan() {
        assertEquals("<p>これは <strong>「文章1」「文章2」</strong> です。</p>\n",
            render("これは**「文章1」「文章2」**です。"))
        assertEquals("<p><strong>「一」「二」「三」</strong></p>\n",
            render("**「一」「二」「三」**"))
    }

    @Test fun keepsConsecutiveQuotesLiteralInsideCode() {
        val markdown = "`**「文章1」「文章2」**`"
        assertEquals(markdown, normalizeJapaneseQuoteEmphasis(markdown))
    }

    @Test fun leavesInlineAndFencedCodeLiteral() {
        assertEquals("<p><code>**「コード」**</code></p>\n", render("`**「コード」**`"))
        assertEquals("<pre><code>**「コード」**\n</code></pre>\n", render("```\n**「コード」**\n```"))
    }

    @Test fun preservesEscapesAndLongFences() {
        val examples = listOf("\\**「そのまま」**", "````\n```\n**「コード」**\n````",
            "    **「コード」**", "``改行\n**「コード」**``")
        examples.forEach { assertEquals(it, normalizeJapaneseQuoteEmphasis(it)) }
    }

    @Test fun leavesIncompleteStreamingMarkupUntilClosed() {
        assertEquals("<p>これは**「途中</p>\n", render("これは**「途中"))
    }
}
