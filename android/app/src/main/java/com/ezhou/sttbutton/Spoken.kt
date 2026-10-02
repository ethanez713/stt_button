package com.ezhou.sttbutton

/** Mirrors `stt_button.spoken.spoken_punctuation`; both read the `spoken` vectors. */
object Spoken {
    private const val PAIR_WINDOW_WORDS = 30
    private val PAREN_WORDS = listOf("paren", "parenthesis", "parentheses")
    private val TOKEN = Regex("^([^\\p{L}\\p{N}_']*)(.*?)([^\\p{L}\\p{N}_']*)$", RegexOption.DOT_MATCHES_ALL)
    private val WHITESPACE = Regex("\\s+")

    private val TWO_WORD: Map<Pair<String, String>, Pair<String, String?>> = buildMap {
        put("open" to "quote", "oq" to null)
        put("close" to "quote", "cq" to null)
        put("end" to "quote", "cq" to null)
        put("quote" to "unquote", "quq" to null)
        put("question" to "mark", "mark" to "?")
        put("exclamation" to "point", "mark" to "!")
        put("exclamation" to "mark", "mark" to "!")
        put("new" to "line", "nl" to "\n")
        put("new" to "paragraph", "nl" to "\n\n")
        for (p in PAREN_WORDS) {
            put("open" to p, "op" to null)
            put("close" to p, "cp" to null)
        }
    }
    private val ONE_WORD: Map<String, String> = buildMap {
        put("quote", "bq")
        put("unquote", "cq")
        for (p in PAREN_WORDS) put(p, "bp")
    }
    // "quote unquote or …" names the command rather than quoting the next word.
    private val CONJUNCTIONS = setOf("or", "and", "but", "nor")
    private val CAPITALIZE_AFTER = setOf("?", "!", "\n\n")
    private val COMMAND_KINDS = setOf("oq", "cq", "bq", "quq", "op", "cp", "bp", "mark", "nl")

    /** A word is ("w", word, lead+core, trail); a command is (kind, payload, words as spoken, trail). */
    private class Element(val kind: String, val payload: String?, val spoken: String, val trail: String)

    private class Item(val kind: String, val a: String, val b: String = "")

    /** Mirrors `stt_button.spoken.keep_spoken_ending`. */
    fun keepSpokenEnding(chunk: String, cleaned: String): String {
        val spoken = convert(chunk).trimEnd()
        val mark = spoken.lastOrNull()
        return if (spoken != chunk.trimEnd() && (mark == '?' || mark == '!') && cleaned.trimEnd().endsWith(".")) cleaned.trimEnd().dropLast(1) + mark
        else cleaned
    }

    fun convert(text: String): String {
        val elements = parse(text)
        if (elements.none { it.kind in COMMAND_KINDS }) return text
        val items = pairUp(elements)
        return if (items.all { it.kind == "word" }) text else render(items)
    }

    private fun parse(text: String): List<Element> {
        val tokens = text.split(WHITESPACE).filter { it.isNotEmpty() }.map { token ->
            val g = checkNotNull(TOKEN.matchEntire(token)).groupValues
            listOf(token, g[1], g[2], g[3])
        }
        val cores = tokens.map { it[2].lowercase() }
        val elements = mutableListOf<Element>()
        var i = 0
        while (i < tokens.size) {
            val (word, lead, core, trail) = tokens[i]
            val pair = if (i + 1 < tokens.size && trail.isEmpty()) cores[i] to cores[i + 1] else null
            val two = pair?.let { TWO_WORD[it] }
            if (two != null) {
                elements += Element(two.first, two.second, "$word ${tokens[i + 1][0]}", tokens[i + 1][3])
                i += 2
            } else if (cores[i] in ONE_WORD) {
                elements += Element(ONE_WORD.getValue(cores[i]), null, word, trail)
                i += 1
            } else {
                elements += Element("w", word, lead + core, trail)
                i += 1
            }
        }
        return elements
    }

    private fun pairUp(elements: List<Element>): List<Item> {
        val paired = mutableSetOf<Int>()

        fun closerAfter(i: Int, kind: String): Int? {
            var words = 0
            for (j in i + 1 until elements.size) {
                if (elements[j].kind == "w") {
                    words++
                    if (words > PAIR_WINDOW_WORDS) return null
                } else if (elements[j].kind == kind && j !in paired) {
                    return j
                }
            }
            return null
        }

        val out = mutableListOf<Item>()
        var i = 0
        while (i < elements.size) {
            val e = elements[i]
            when (e.kind) {
                "w" -> out += Item("word", e.payload!!)
                "oq", "bq" -> {
                    val j = closerAfter(i, "cq")
                    if (j == null) out += Item("word", e.spoken)
                    else {
                        paired += j
                        out += Item("open", "\"")
                    }
                }
                "op", "bp" -> {
                    val j = closerAfter(i, "cp")
                    if (j == null) out += Item("word", e.spoken)
                    else {
                        paired += j
                        out += Item("open", "(")
                    }
                }
                "cq" -> out += if (i in paired) Item("close", "\"", e.trail) else Item("word", e.spoken)
                "cp" -> out += if (i in paired) Item("close", ")", e.trail) else Item("word", e.spoken)
                "quq" -> {
                    val following = elements.getOrNull(i + 1)
                    if (following != null && following.kind == "w" && following.spoken.lowercase() !in CONJUNCTIONS) {
                        out += Item("open", "\"")
                        out += Item("word", following.spoken)
                        out += Item("close", "\"", following.trail)
                        i++
                    } else {
                        out += Item("word", e.spoken)
                    }
                }
                "mark" -> out += Item("mark", e.payload!!)
                else -> out += Item("newline", e.payload!!)
            }
            i++
        }
        return out
    }

    private fun render(items: List<Item>): String {
        val out = mutableListOf<String>()
        var glue = false
        var capitalize = false

        fun stripEnd(chars: String) {
            if (out.isNotEmpty()) out[out.lastIndex] = out.last().trimEnd { it in chars }
        }

        for (item in items) {
            when (item.kind) {
                "word" -> {
                    var text = item.a
                    if (capitalize && text.isNotEmpty() && text[0].isLowerCase()) text = text[0].uppercase() + text.substring(1)
                    if (out.isNotEmpty() && !glue) out += " "
                    out += text
                    glue = false
                    capitalize = false
                }
                "open" -> {
                    if (out.isNotEmpty() && !glue) out += " "
                    out += item.a
                    glue = true
                }
                "close" -> {
                    stripEnd(",")
                    out += item.a + item.b
                    glue = false
                    capitalize = false
                }
                "mark" -> {
                    stripEnd(",.;:")
                    out += item.a
                    glue = false
                    capitalize = item.a in CAPITALIZE_AFTER
                }
                else -> {
                    stripEnd(",;:")
                    out += item.a
                    glue = true
                    capitalize = item.a in CAPITALIZE_AFTER
                }
            }
        }
        return out.joinToString("")
    }
}
