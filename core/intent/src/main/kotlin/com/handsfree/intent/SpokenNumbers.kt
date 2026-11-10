package com.handsfree.intent

internal object SpokenNumbers {
    private val units = listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen")
    private val tens = mapOf("twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90)
    val words = units.toSet() + tens.keys + setOf("hundred", "thousand", "and", "point")

    fun parse(tokens: List<String>): String? {
        val point = tokens.indexOf("point")
        if (point >= 0) {
            val whole = integer(tokens.take(point)) ?: return null
            val decimal = tokens.drop(point + 1)
            if (decimal.isEmpty() || decimal.any { it !in units.take(10) }) return null
            return "$whole." + decimal.joinToString("") { units.indexOf(it).toString() }
        }
        return integer(tokens)?.toString()
    }
    private fun integer(tokens: List<String>): Int? {
        val split = tokens.indexOf("thousand")
        if (split < 0) return underThousand(tokens)
        val high = underThousand(tokens.take(split))?.takeIf { it > 0 } ?: return null
        var rest = tokens.drop(split + 1)
        if (rest.firstOrNull() == "and") {
            rest = rest.drop(1)
            if (rest.isEmpty()) return null
        }
        val low = if (rest.isEmpty()) 0 else underThousand(rest) ?: return null
        return high * 1000 + low
    }
    private fun underThousand(tokens: List<String>): Int? {
        if (tokens.size >= 2 && tokens[1] == "hundred") {
            val high = units.indexOf(tokens[0]).takeIf { it in 1..9 } ?: return null
            var rest = tokens.drop(2)
            if (rest.firstOrNull() == "and") {
                rest = rest.drop(1)
                if (rest.isEmpty()) return null
            }
            return high * 100 + (if (rest.isEmpty()) 0 else underHundred(rest) ?: return null)
        }
        return underHundred(tokens)
    }
    private fun underHundred(tokens: List<String>): Int? {
        if (tokens.size == 1) return units.indexOf(tokens[0]).takeIf { it >= 0 } ?: tens[tokens[0]]
        if (tokens.size != 2) return null
        val high = tens[tokens[0]] ?: return null
        val low = units.indexOf(tokens[1]).takeIf { it in 1..9 } ?: return null
        return high + low
    }
}
