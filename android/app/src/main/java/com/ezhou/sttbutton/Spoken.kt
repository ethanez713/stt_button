package com.ezhou.sttbutton

/** Mirrors `stt_button.spoken.spoken_punctuation`; both read the `spoken` vectors. */
object Spoken {
    private const val PAIR_WINDOW_WORDS = 30
    private val PAREN_WORDS = listOf("paren", "parenthesis", "parentheses")
    private val TOKEN = Regex("^([^\\p{L}\\p{N}_']*)(.*?)([^\\p{L}\\p{N}_']*)$", RegexOption.DOT_MATCHES_ALL)
    private val WHITESPACE = Regex("\\s+")
    private val TOKEN_SPAN = Regex("(?U)\\S+")
    private const val RESTATEMENT_MAX_WORDS = 4
    private val FIRST_PERSON = setOf("i", "i'm", "i'll", "i've", "i'd")
    private val SENTENCE_ENDS = setOf('.', '?', '!')

    private val TWO_WORD: Map<Pair<String, String>, Pair<String, String?>> = buildMap {
        put("open" to "quote", "oq" to null)
        put("close" to "quote", "cq" to null)
        put("closed" to "quote", "cq" to null)
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
            put("closed" to p, "cp" to null)
        }
    }
    private val ONE_WORD: Map<String, String> = buildMap {
        put("quote", "bq")
        put("unquote", "cq")
        for (p in PAREN_WORDS) put(p, "bp")
    }
    private val JOINERS = mapOf("slash" to "sl", "hyphen" to "hy", "dash" to "da")
    private val JOINER_KINDS = JOINERS.values.toSet()
    // "a dash of salt" and "the hyphen" name the mark, and "dash cam" is a thing.
    private val DETERMINERS = setOf("a", "an", "the", "my", "your", "our", "this", "that")
    private val DASHED_NOUNS = setOf("cam", "cams", "board")
    private val SLASH_ECHO = Regex("(?i)(?<=\\w)/\\s*(?=slash\\b)")
    // "quote unquote or …" names the command rather than quoting the next word.
    private val CONJUNCTIONS = setOf("or", "and", "but", "nor")
    private val CAPITALIZE_AFTER = setOf("?", "!", "\n\n")
    private val COMMAND_KINDS = setOf("oq", "cq", "bq", "quq", "op", "cp", "bp", "mark", "nl") + JOINER_KINDS

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

    /** Mirrors `stt_button.spoken.resolve_restatements`. */
    fun resolveRestatements(text: String): String {
        var current = text
        while (true) current = withoutOneRestatement(current) ?: return current
    }

    private fun core(word: String): String = checkNotNull(TOKEN.matchEntire(word)).groupValues[2].lowercase()

    private fun restatedCase(word: String, upper: Boolean): String {
        val (_, lead, core, trail) = checkNotNull(TOKEN.matchEntire(word)).groupValues
        val rest = core.drop(1)
        if (core.isEmpty() || (!upper && (core.lowercase() in FIRST_PERSON || rest != rest.lowercase()))) return word
        val first = if (upper) core.take(1).uppercase() else core.take(1).lowercase()
        return lead + first + rest + trail
    }

    private fun withoutOneRestatement(text: String): String? {
        val spans = TOKEN_SPAN.findAll(text).map { it.range }.toList()
        val words = spans.map { text.substring(it.first, it.last + 1) }
        val cores = words.map { core(it) }

        fun repeats(before: Int, after: Int, size: Int): Boolean {
            val first = cores.subList(before, before + size)
            val second = cores.subList(minOf(after, cores.size), minOf(after + size, cores.size))
            return second.size == size && first == second && "" !in first && "not" !in first
        }

        for ((at, core) in cores.withIndex()) {
            if (core != "not") continue
            for (size in RESTATEMENT_MAX_WORDS downTo 1) {
                var start = at - size
                val replacement = at + size + 1
                if (start < 0 || replacement >= words.size || !repeats(start, at + 1, size)) continue
                for (earlier in minOf(3, start) downTo 1) {
                    if (repeats(start - earlier, replacement, earlier)) {
                        start -= earlier
                        break
                    }
                }
                var word = words[replacement]
                if (start == 0 || words[start - 1].last() in SENTENCE_ENDS) word = restatedCase(word, upper = true)
                else if (words[replacement - 1].last() in SENTENCE_ENDS) word = restatedCase(word, upper = false)
                return text.substring(0, spans[start].first) + word + text.substring(spans[replacement].last + 1)
            }
        }
        return null
    }

    fun convert(input: String): String {
        val text = resolveRestatements(SLASH_ECHO.replace(input, " "))
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
            } else if (cores[i] in JOINERS && lead.isEmpty() && trail.isEmpty()) {
                elements += Element(JOINERS.getValue(cores[i]), null, word, trail)
                i += 1
            } else {
                elements += Element("w", word, lead + core, trail)
                i += 1
            }
        }
        return elements
    }

    /** A slash, hyphen or dash as a "join", an "open" "/" for a slash that starts a command word, a "dash", or the word as spoken. */
    private fun joined(elements: List<Element>, i: Int): Item {
        val e = elements[i]
        val before = elements.getOrNull(i - 1)
        val after = elements.getOrNull(i + 1)
        if (after == null || after.kind != "w" || (before != null && before.kind in JOINER_KINDS)) return Item("word", e.spoken)
        if (e.kind == "sl") {
            return if (before == null || before.kind != "w" || before.trail.isNotEmpty() || core(before.payload!!) in DETERMINERS) Item("open", "/")
            else Item("join", "/")
        }
        if (before == null || before.kind != "w" || core(before.payload!!) in DETERMINERS) return Item("word", e.spoken)
        if (e.kind == "hy") return if (before.trail.isEmpty()) Item("join", "-") else Item("word", e.spoken)
        return if ((before.trail.isEmpty() || before.trail == ",") && core(after.payload!!) !in DASHED_NOUNS) Item("dash", " -- ")
        else Item("word", e.spoken)
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
                in JOINER_KINDS -> out += joined(elements, i)
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
                "join" -> {
                    out += item.a
                    glue = true
                }
                "dash" -> {
                    stripEnd(",")
                    out += item.a
                    glue = true
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
