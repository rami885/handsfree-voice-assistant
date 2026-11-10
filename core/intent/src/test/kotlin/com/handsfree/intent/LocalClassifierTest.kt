package com.handsfree.intent

import com.handsfree.guard.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class LocalClassifierTest {
    private val classifier = LocalClassifier()
    @Test fun everyFixedCommandMatches() {
        val commands = listOf(
            "Answer" to LocalCommand.ANSWER, "Decline" to LocalCommand.DECLINE,
            "Play" to LocalCommand.PLAY, "Pause" to LocalCommand.PAUSE, "Next" to LocalCommand.NEXT,
            "Volume up" to LocalCommand.VOLUME_UP, "Volume down" to LocalCommand.VOLUME_DOWN,
            "Mute" to LocalCommand.MUTE, "Flashlight on" to LocalCommand.FLASHLIGHT_ON,
            "Flashlight off" to LocalCommand.FLASHLIGHT_OFF, "Do not disturb on" to LocalCommand.DND_ON,
            "Do not disturb off" to LocalCommand.DND_OFF, "What's my battery level?" to LocalCommand.BATTERY,
            "Read that slower" to LocalCommand.READ_SLOWER, "Read sender names only" to LocalCommand.SENDER_NAMES_ONLY,
            "Stop reading notifications" to LocalCommand.STOP_NOTIFICATIONS, "Skip this notification" to LocalCommand.SKIP_NOTIFICATION,
            "Say that again" to LocalCommand.REPEAT, "Cancel" to LocalCommand.CANCEL, "Stop" to LocalCommand.STOP,
            "Read my notes" to LocalCommand.READ_NOTES, "Dismiss the timer" to LocalCommand.DISMISS_TIMER, "Snooze the alarm" to LocalCommand.SNOOZE_ALARM,
        )
        commands.forEach { (spoken, command) ->
            assertEquals(spoken, LocalMatch(command), classifier.classify(spoken))
            assertEquals(spoken, LocalMatch(command), classifier.classify("  ${spoken.uppercase(Locale.ROOT)}!  "))
        }
        assertEquals(LocalCommand.BATTERY, classifier.classify("What’s my battery level?")?.command)
    }
    @Test fun templatesAndSpokenNumbers() {
        assertEquals(LocalMatch(LocalCommand.SET_TIMER, mapOf("duration" to "10", "unit" to "minutes")), classifier.classify("Set a timer for ten minutes"))
        assertEquals(LocalMatch(LocalCommand.SET_TIMER, mapOf("duration" to "25", "unit" to "seconds")), classifier.classify("Set a timer for twenty-five seconds"))
        assertEquals(LocalMatch(LocalCommand.VOLUME, mapOf("level" to "10")), classifier.classify("Volume ten"))
        assertEquals("105", classifier.classify("Volume one hundred and five")?.parameters?.get("level"))
        assertEquals("1005", classifier.classify("Volume one thousand and five")?.parameters?.get("level"))
        assertEquals("10", classifier.classify("Rewind ten seconds")?.parameters?.get("seconds"))
        assertEquals(LocalCommand.REWIND, classifier.classify("Rewind 10 seconds")?.command)
        assertEquals("0", classifier.classify("Volume zero")?.parameters?.get("level"))
        assertEquals("1.5", classifier.classify("Play at 1.5 speed")?.parameters?.get("speed"))
        assertEquals("1.5", classifier.classify("Play at one point five speed")?.parameters?.get("speed"))
        assertEquals("1.05", classifier.classify("Play at one point zero five speed")?.parameters?.get("speed"))
        assertEquals("1.5", classifier.classify("Play at 1.50 speed")?.parameters?.get("speed"))
    }
    @Test fun payloadIsTakenFromTheOriginalText() {
        assertEquals("Call Nikos, 5 p.m.", classifier.classify("Take a note: Call Nikos, 5 p.m.")?.parameters?.get("text"))
        assertEquals("  CALL Nikos, 5 p.m.\nStop!  ", classifier.classify("Take a note:   CALL Nikos, 5 p.m.\nStop!  ")?.parameters?.get("text"))
        assertEquals("Hello, Nikos!  At 1.5 p.m.\nYes.", classifier.classify("Change the message to Hello, Nikos!  At 1.5 p.m.\nYes.")?.parameters?.get("text"))
        assertEquals(LocalCommand.CHANGE_MESSAGE, classifier.classify("Change the message to Call Nikos, 5 p.m.")?.command)
        assertEquals(LocalCommand.TAKE_NOTE, classifier.classify("Take a note: call 5551234567 and reply yes to everyone")?.command)
        assertNull(classifier.classify("Take a note:   "))
        assertNull(classifier.classify("Change the message to "))
    }
    @Test fun namesMultiStepAndMalformedCommandsFallBack() {
        listOf("Call Nikos", "Send Nikos a message", "Play Jazz", "Answer and play", "Pause then next", "Play at 1.5 speed and pause", "Volume -1", "Play at .5 speed", "Play at 1.5.2 speed", "Volume one two", "Volume one thousand and", "Set a timer for one hundred and minutes", "Volume twenty zero", "Set a timer for zero minutes", "Play at zero speed", "Rewind zero seconds", "Play at one point fifty speed", "Take a note without a colon", "Volume 1,5", "", "   ").forEach {
            assertNull(it, classifier.classify(it))
        }
    }
    @Test fun negativeSymbolsAndSpokenNumbersNeverBecomePositiveCommands() {
        for (sign in listOf("−", "-", "﹣", "－", "‐", "‑", "‒", "–", "—")) {
            for (number in listOf("10", "one", "one point five")) {
                listOf("Volume $sign$number", "Rewind $sign$number seconds", "Play at $sign$number speed",
                    "Set a timer for $sign$number seconds").forEach { assertNull(it, classifier.classify(it)) }
            }
        }
        assertEquals("twenty-five −10 -one", classifier.classify("Take a note: twenty-five −10 -one")?.parameters?.get("text"))
        assertEquals("25", classifier.classify("Volume twenty-five")?.parameters?.get("level"))
    }
    @Test fun normalizationIsLocaleIndependent() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr"))
            assertEquals(LocalCommand.FLASHLIGHT_ON, classifier.classify("FLASHLIGHT ON")?.command)
        } finally { Locale.setDefault(previous) }
    }
    @Test fun fakePlannerHasNoNetworkAndSupportsCancellation() = runBlocking {
        val spec = ToolSpec("pause", emptyMap(), Capability.NONE, Sensitivity.NONE, ActionKind.SYSTEM_NO_DATA)
        val result = PlanResult.Plan(listOf(ActionSnapshot("pause", ActionKind.SYSTEM_NO_DATA)))
        val fake = FakePlanner(result)
        assertEquals(result, fake.plan("Pause", listOf(spec)))
        assertEquals(1, fake.calls)
        assertEquals(listOf(spec), fake.lastTools)
        assertEquals(PlanResult.Unsupported, FakePlanner().plan("Unknown", emptyList()))
        val delayed = FakePlanner(result, 60_000)
        val job = launch(start = CoroutineStart.UNDISPATCHED) { delayed.plan("Unknown", emptyList()) }
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertEquals(1, delayed.calls)
    }
}
