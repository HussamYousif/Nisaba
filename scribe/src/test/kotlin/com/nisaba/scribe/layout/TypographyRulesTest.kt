package com.nisaba.scribe.layout

import com.nisaba.domain.answer.Block
import com.nisaba.domain.answer.Inline
import com.nisaba.domain.shared.PageSize
import com.nisaba.scribe.hand.HandFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TypographyRulesTest {
    @Test fun dashesQuotesEllipsesAndSpacesBecomePlainCharacters() {
        assertEquals("Hello - I'm here...", normalizeTypography("Hello — I’m here…"))
        assertEquals("\"quoted\" and 'single'", normalizeTypography("“quoted” and ‘single’"))
        assertEquals("a b", normalizeTypography("a b"))
        assertEquals("zero", normalizeTypography("ze​ro"))
        assertEquals("- item", normalizeTypography("• item"))
        assertEquals("1 x 2 -> 3 <= 4", normalizeTypography("1 × 2 → 3 ≤ 4"))
    }

    @Test fun plainAsciiAndNorwegianAreUntouched() {
        val s = "Hva er hovedstaden? Oslo, på Østlandet. Æ Ø Å"
        assertEquals(s, normalizeTypography(s))
    }

    @Test fun layoutNeverProducesAQuestionMarkForATypographicDash() {
        val hand = HandFixtures.hand()
        val region = LayoutRegion.below(PageSize.PORTRAIT, 120f, 300f, 44f)
        val (block, _) = layoutBlock(
            Block.Paragraph(listOf(Inline.Text("Hello — I’m here"))), LayoutCursor(0, region.top), region, { null }, hand,
        )
        val chars = block.words.flatMap { w -> w.chars.map { it.ch } }
        assertTrue('?' !in chars, "typographic characters must not turn into '?': $chars")
        assertTrue('-' in chars)
    }
}
