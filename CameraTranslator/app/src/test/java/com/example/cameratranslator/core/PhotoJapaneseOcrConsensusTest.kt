package com.example.cameratranslator.core

import org.junit.Assert.*
import org.junit.Test

class PhotoJapaneseOcrConsensusTest {
    private fun block(text: String, confidence: Float? = null,
        x: Int = 0, y: Int = 0, w: Int = 240, h: Int = 60
    ) = PhotoJapaneseOcrConsensus.Block(text, x, y, x + w, y + h, confidence)

    @Test fun matchingReadersKeepPaddle() {
        val decision = PhotoJapaneseOcrConsensus.evaluate(
            block("集めたらチェックを入れよう", .92f),
            listOf(block("集めたらチェックを入れよう"))
        )
        assertEquals(PhotoJapaneseOcrConsensus.Action.KEEP, decision.action)
        assertEquals(1f, decision.similarity!!, .001f)
        assertEquals("readings_agree", decision.reason)
    }

    @Test fun strongPaddleDisagreementDoesNotBlockPhotoTranslation() {
        // The actual toy leaflet had 8 high-confidence Japanese blocks
        // rejected because ML Kit often includes ruby/adjacent text.
        val decision = PhotoJapaneseOcrConsensus.evaluate(
            block("食べたらチェックを入れよう", .91f),
            listOf(block("集めたらシートに記入しよう"))
        )
        assertEquals(PhotoJapaneseOcrConsensus.Action.KEEP, decision.action)
        assertEquals("disagreement_unverified", decision.reason)
    }

    @Test fun moderateConfidenceDisagreementKeepsPaddleAndLogsIt() {
        val decision = PhotoJapaneseOcrConsensus.evaluate(
            block("食べたらチェックを入れよう", .53f),
            listOf(block("集めたらシートに記入しよう"))
        )
        assertEquals(PhotoJapaneseOcrConsensus.Action.KEEP, decision.action)
    }

    @Test fun veryWeakPaddleLargeDisagreementRequiresReview() {
        val decision = PhotoJapaneseOcrConsensus.evaluate(
            block("食べたらチェックを入れよう", .28f),
            listOf(block("集めたらシートに記入しよう"))
        )
        assertEquals(PhotoJapaneseOcrConsensus.Action.REVIEW, decision.action)
    }

    @Test fun veryWeakPaddleMinorTypoCanUseIndependentReader() {
        val decision = PhotoJapaneseOcrConsensus.evaluate(
            block("集めたらチェックを入れよら", .28f),
            listOf(block("集めたらチェックを入れよう"))
        )
        assertEquals(PhotoJapaneseOcrConsensus.Action.REPLACE, decision.action)
    }

    @Test fun plausibleReadingBeatsSlightlyHigherIouWrongLine() {
        val result = PhotoJapaneseOcrConsensus.evaluate(
            block("集めたらチェックを入れよう", .92f),
            listOf(
                block("集めたらシートに記入しよう"),
                block("集めたらチェックを入れよう", x = 6, w = 230)
            )
        )
        assertEquals(PhotoJapaneseOcrConsensus.Action.KEEP, result.action)
        assertEquals("readings_agree", result.reason)
        assertEquals(1f, result.similarity!!, .001f)
    }

    @Test fun conflictingOverlappingAlternatesNeverRewriteWeakPaddleText() {
        val result = PhotoJapaneseOcrConsensus.evaluate(
            block("集めたらチェックを入れよら", .28f),
            listOf(
                block("集めたらシートに記入しよう"),
                block("集めたらチェックを入れよう", x = 6, w = 230)
            )
        )
        assertEquals(PhotoJapaneseOcrConsensus.Action.REVIEW, result.action)
        assertEquals("conflicting_alternates", result.reason)
    }

    @Test fun subLineRubyMustNotBeMatchedWithLargerMainText() {
        val decision = PhotoJapaneseOcrConsensus.evaluate(
            block("ダイキャストビークルは全部で10種類", .91f,
                750, 420, 540, 100),
            listOf(block("キミは全部集められるかな", x = 1000, y = 432,
                w = 230, h = 46))
        )
        assertEquals(PhotoJapaneseOcrConsensus.Action.KEEP, decision.action)
        assertEquals("no_aligned_match", decision.reason)
    }

    @Test fun distantTextDoesNotCountAsAgreement() {
        val p = block("集めたらチェックを入れよう", .3f)
        assertEquals(PhotoJapaneseOcrConsensus.Action.KEEP,
            PhotoJapaneseOcrConsensus.evaluate(p, listOf(
                block("違う文字がいっぱい", x = 400)
            )).action)
    }

    @Test fun latinLogoIsNotAutoCorrectedAsJapanese() {
        assertEquals(PhotoJapaneseOcrConsensus.Action.KEEP,
            PhotoJapaneseOcrConsensus.evaluate(block("TAKARA TOMY", .2f),
                listOf(block("集めたらチェック"))).action)
    }

    @Test fun minorPunctuationDifferencesDoNotTriggerReview() {
        val result = PhotoJapaneseOcrConsensus.evaluate(
            block("集めたら、チェックを入れよう！", .8f),
            listOf(block("集めたらチェックを入れよう"))
        )
        assertEquals(PhotoJapaneseOcrConsensus.Action.KEEP, result.action)
    }
}
