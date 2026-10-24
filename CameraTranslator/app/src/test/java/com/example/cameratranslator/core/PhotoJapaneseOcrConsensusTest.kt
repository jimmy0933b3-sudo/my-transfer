package com.example.cameratranslator.core

import org.junit.Assert.*
import org.junit.Test

class PhotoJapaneseOcrConsensusTest {
    private fun block(text: String, confidence: Float? = null,
        x: Int = 0, y: Int = 0, w: Int = 240, h: Int = 60
    ) = PhotoJapaneseOcrConsensus.Block(text, x, y, x + w, y + h, confidence)

    @Test fun matchingReadersKeepPaddle() {
        val p = block("集めたらチェックを入れよう", .38f)
        val decision = PhotoJapaneseOcrConsensus.evaluate(
            p, listOf(block("集めたらチェックを入れよう"))
        )
        assertEquals(PhotoJapaneseOcrConsensus.Action.KEEP, decision.action)
        assertEquals(1f, decision.similarity!!, .001f)
    }

    @Test fun weakPaddleUsesIndependentValidJapanese() {
        val decision = PhotoJapaneseOcrConsensus.evaluate(
            block("食べたらチェックを入れよう", .30f),
            listOf(block("集めたらシートに記入しよう"))
        )
        assertEquals(PhotoJapaneseOcrConsensus.Action.REPLACE, decision.action)
        assertEquals("集めたらシートに記入しよう", decision.alternate!!.text)
    }

    @Test fun strongDisagreementFlagsManualReviewRatherThanGuessing() {
        val decision = PhotoJapaneseOcrConsensus.evaluate(
            block("食べたらチェックを入れよう", .92f),
            listOf(block("集めたらシートに記入しよう"))
        )
        assertEquals(PhotoJapaneseOcrConsensus.Action.REVIEW, decision.action)
    }

    @Test fun unrelatedOrOversizedNearbyTextDoesNotOverwrite() {
        val p = block("集めたらチェックを入れよう", .3f)
        assertEquals(PhotoJapaneseOcrConsensus.Action.KEEP,
            PhotoJapaneseOcrConsensus.evaluate(p, listOf(
                block("違う文字がいっぱい", x = 400)
            )).action)
        assertEquals(PhotoJapaneseOcrConsensus.Action.KEEP,
            PhotoJapaneseOcrConsensus.evaluate(p, listOf(
                block("あいうえおかきくけこさしすせそたちつてとなにぬねのほら")
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
