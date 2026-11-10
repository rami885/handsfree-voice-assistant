package com.handsfree.probes

import org.junit.Assert.*
import org.junit.Test

class FixedSentencesTest {
    private val stopWord = Regex("\\bstop\\b", RegexOption.IGNORE_CASE)

    @Test fun stopWordPatternMatchesWholeWordsIgnoringCase() {
        assertTrue(stopWord.containsMatchIn("You can say stop now."))
        assertTrue(stopWord.containsMatchIn("STOP!"))
        assertFalse(stopWord.containsMatchIn("The music is stopping."))
    }

    @Test fun t13PlaybackDoesNotContainTheMeasuredStopWord() {
        assertEquals(10, ProbeController.sentences.size)
        assertTrue(ProbeController.sentences.all { it.isNotBlank() })
        assertFalse(ProbeController.sentences.any { stopWord.containsMatchIn(it) })
    }
}
