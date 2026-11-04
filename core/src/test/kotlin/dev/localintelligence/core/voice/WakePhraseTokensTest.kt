package dev.localintelligence.core.voice

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class WakePhraseTokensTest {

    @Test
    fun fixturesMatchSentencePiece() {
        val piecesFile = sequenceOf(
            File("../android/src/main/assets/kws/kws-pieces.tsv"),
            File("android/src/main/assets/kws/kws-pieces.tsv"),
        ).first { it.isFile }
        val tokenizer = WakePhraseTokens.fromTsv(piecesFile.readText())

        val fixtures = listOf(
            "HELLO WORLD" to "▁HE LL O ▁WORLD",
            "HEY SIRI" to "▁HE Y ▁S I RI",
            "MERRY CHRISTMAS" to "▁ME R RY ▁ CH R IST MA S",
            "HEY ASSISTANT" to "▁HE Y ▁AS S IST ANT",
            "HEY NOVA" to "▁HE Y ▁NO V A",
            "HEY JARVIS" to "▁HE Y ▁JA R VI S",
            "HEY MILA" to "▁HE Y ▁MI LA",
            "HEY SAM" to "▁HE Y ▁SA M",
            "HEY DARIO" to "▁HE Y ▁DA RI O",
            "HEY LOCAL" to "▁HE Y ▁LO C AL",
            "HEY KIKI" to "▁HE Y ▁K I K I",
            "HEY ZOE" to "▁HE Y ▁ Z O E",
            "HEY ANNE MARIE" to "▁HE Y ▁AN NE ▁MA RI E",
            "HEY ROBIN" to "▁HE Y ▁RO B IN",
            "HEY QUINTEN" to "▁HE Y ▁QU IN T EN",
            "HEY LUNA" to "▁HE Y ▁ LU N A",
            "HEY MAX" to "▁HE Y ▁MA X",
            "HEY BOB" to "▁HE Y ▁BO B",
            "HEY XENA" to "▁HE Y ▁ X EN A",
            "HEY FRIEND" to "▁HE Y ▁FRIEND",
            "OK COMPUTER" to "▁O K ▁COMP U TER",
            "HEY YOU" to "▁HE Y ▁YOU",
            "HEY INTELLIGENCE" to "▁HE Y ▁IN TE LL IG ENCE",
            "HEY Q" to "▁HE Y ▁ Q",
            "HEY ELIZABETH" to "▁HE Y ▁E LI Z A B E TH",
            "HEY JOHANNES" to "▁HE Y ▁JO HA N NE S",
            "HEY SANNE" to "▁HE Y ▁S AN NE",
            "HEY FLEUR" to "▁HE Y ▁F LE UR",
            "HEY THIJS" to "▁HE Y ▁TH I J S",
            "HEY ZEPHYR" to "▁HE Y ▁ Z E PH Y R",
        )
        assertEquals(30, fixtures.size)
        for ((phrase, expected) in fixtures) {
            assertEquals("Mismatch for '$phrase'", expected, tokenizer.encode(phrase))
        }

        assertEquals(
            "▁HE Y ▁ Z O E :1.5 #0.25 @HEY_ZOE",
            tokenizer.keywordLine("Hey, Zoë!"),
        )
    }
}
