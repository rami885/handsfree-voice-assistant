package com.handsfree.probes

import org.junit.Assert.*
import org.junit.Test

class FixedSentencesTest {
    @Test fun t13PlaybackDoesNotContainTheMeasuredStopWord() {
        assertEquals(10, ProbeController.sentences.size)
        assertTrue(ProbeController.sentences.all { it.isNotBlank() })
        assertFalse(ProbeController.sentences.any { Regex("\\bstop\\b", RegexOption.IGNORE_CASE).containsMatchIn(it) })
    }
}
