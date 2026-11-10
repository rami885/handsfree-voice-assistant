package com.handsfree.intent

import java.util.Locale

enum class LocalCommand {
    ANSWER, DECLINE, PLAY, PAUSE, NEXT, VOLUME_UP, VOLUME_DOWN, MUTE, FLASHLIGHT_ON, FLASHLIGHT_OFF,
    DND_ON, DND_OFF, BATTERY, READ_SLOWER, SENDER_NAMES_ONLY, STOP_NOTIFICATIONS, SKIP_NOTIFICATION,
    REPEAT, CANCEL, STOP, READ_NOTES, DISMISS_TIMER, SNOOZE_ALARM,
    SET_TIMER, VOLUME, REWIND, PLAY_SPEED, TAKE_NOTE, CHANGE_MESSAGE,
}
data class LocalMatch(val command: LocalCommand, val parameters: Map<String, String> = emptyMap())

class LocalClassifier {
    private val fixed = mapOf(
        "answer" to LocalCommand.ANSWER, "decline" to LocalCommand.DECLINE,
        "play" to LocalCommand.PLAY, "pause" to LocalCommand.PAUSE, "next" to LocalCommand.NEXT,
        "volume up" to LocalCommand.VOLUME_UP, "volume down" to LocalCommand.VOLUME_DOWN,
        "mute" to LocalCommand.MUTE, "flashlight on" to LocalCommand.FLASHLIGHT_ON,
        "flashlight off" to LocalCommand.FLASHLIGHT_OFF, "do not disturb on" to LocalCommand.DND_ON,
        "do not disturb off" to LocalCommand.DND_OFF, "whats my battery level" to LocalCommand.BATTERY,
        "read that slower" to LocalCommand.READ_SLOWER, "read sender names only" to LocalCommand.SENDER_NAMES_ONLY,
        "stop reading notifications" to LocalCommand.STOP_NOTIFICATIONS, "skip this notification" to LocalCommand.SKIP_NOTIFICATION,
        "say that again" to LocalCommand.REPEAT, "cancel" to LocalCommand.CANCEL, "stop" to LocalCommand.STOP,
        "read my notes" to LocalCommand.READ_NOTES, "dismiss the timer" to LocalCommand.DISMISS_TIMER,
        "snooze the alarm" to LocalCommand.SNOOZE_ALARM,
    )
    private val note = Regex("^\\s*take\\s+a\\s+note\\s*: ?([\\s\\S]+)$", RegexOption.IGNORE_CASE)
    private val message = Regex("^\\s*change\\s+the\\s+message\\s+to[ \\t]([\\s\\S]+)$", RegexOption.IGNORE_CASE)

    fun classify(text: String): LocalMatch? {
        note.matchEntire(text)?.let { return payload(LocalCommand.TAKE_NOTE, it.groupValues[1]) }
        message.matchEntire(text)?.let { return payload(LocalCommand.CHANGE_MESSAGE, it.groupValues[1]) }
        // Normalize minus glyphs before punctuation can erase their meaning.
        val signed = text.map { if (it.category == CharCategory.DASH_PUNCTUATION || Character.getName(it.code)?.contains("MINUS") == true) '-' else it }.joinToString("")
        val command = normalize(signed)
        fixed[command]?.let { return LocalMatch(it) }
        // Inspect only the numeric slot, retaining signs until it has been validated.
        val numericText = punctuationTokens(signed, preserveNumberSigns = true).joinToString(" ")
        val argument = listOf(
            "set a timer for (.+) (?:minutes|seconds)", "volume (.+)",
            "rewind (.+) seconds", "play at (.+) speed",
        ).firstNotNullOfOrNull { Regex(it).matchEntire(numericText)?.groupValues?.get(1) } ?: return null
        if (Regex("-\\s*\\d|(?<!\\d)\\.\\d").containsMatchIn(argument)) return null
        val tens = setOf("twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")
        if (Regex("-\\s*[a-z]+", RegexOption.IGNORE_CASE).findAll(argument).any {
                argument.substring(0, it.range.first).takeLastWhile(Char::isLetter).lowercase(Locale.ROOT) !in tens
            }) return null
        Regex("set a timer for ([0-9]+) (minutes|seconds)").matchEntire(command)?.let {
            if (it.groupValues[1].toLongOrNull()?.let { number -> number > 0 } != true) return null
            return LocalMatch(LocalCommand.SET_TIMER, mapOf("duration" to it.groupValues[1], "unit" to it.groupValues[2]))
        }
        listOf(
            Triple("volume ([0-9]+)", LocalCommand.VOLUME, "level"),
            Triple("rewind ([0-9]+) seconds", LocalCommand.REWIND, "seconds"),
            Triple("play at ([0-9]+(?:\\.[0-9]+)?) speed", LocalCommand.PLAY_SPEED, "speed"),
        ).forEach { (pattern, action, parameter) ->
            Regex(pattern).matchEntire(command)?.let {
                val value = it.groupValues[1]
                val number = value.toBigDecimalOrNull() ?: return null
                if (number.signum() < 0 || (action != LocalCommand.VOLUME && number.signum() == 0)) return null
                return LocalMatch(action, mapOf(parameter to number.stripTrailingZeros().toPlainString()))
            }
        }
        return null
    }

    private fun payload(command: LocalCommand, content: String): LocalMatch? =
        if (content.isBlank()) null else LocalMatch(command, mapOf("text" to content))

    private fun punctuationTokens(text: String, preserveNumberSigns: Boolean = false): List<String> {
        val lower = text.lowercase(Locale.ROOT)
        return buildString {
            lower.forEachIndexed { i, c ->
                when {
                    c.isLetterOrDigit() || c.isWhitespace() -> append(c)
                    preserveNumberSigns && (c == '-' || (c == '.' && lower.getOrNull(i + 1)?.isDigit() == true)) -> append(c)
                    c == '.' && i > 0 && i + 1 < lower.length && lower[i - 1].isDigit() && lower[i + 1].isDigit() -> append(c)
                    c == '\'' || c == '’' -> Unit
                    else -> append(' ')
                }
            }
        }.trim().split(Regex("\\s+"))
    }

    internal fun normalize(text: String): String {
        val clean = punctuationTokens(text)
        val result = mutableListOf<String>()
        var i = 0
        while (i < clean.size) {
            var end = i
            while (end < clean.size && clean[end] in SpokenNumbers.words) end++
            var converted = false
            for (candidate in end downTo i + 1) {
                val number = SpokenNumbers.parse(clean.subList(i, candidate)) ?: continue
                result += number
                i = candidate
                converted = true
                break
            }
            if (!converted) result += clean[i++]
        }
        return result.joinToString(" ")
    }
}
