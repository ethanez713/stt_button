package com.ezhou.sttbutton

/** The stretch [from, to) of the region handed to the cleaner; [dirty] once the user has edited inside it, [abandoned] when the take resumed over it. */
class Flight(val from: Int, val to: Int, val dirty: Boolean = false, val abandoned: Boolean = false)

/**
 * Where a take's text sits in its document, as offsets: `start <= cleanEnd <= end`. `[start, cleanEnd)`
 * has been cleaned at least once and `[cleanEnd, end)` is the uncleaned tail. Nothing is closed to a later
 * rewrite. Edits the take did not make move the offsets by [Span]'s rule, except that text inserted exactly
 * at `end` extends the region.
 */
class TakeRegion(start: Int = 0, cleanEnd: Int = start, end: Int = cleanEnd) {
    var start = start
        private set
    var cleanEnd = cleanEnd
        private set
    var end = end
        private set
    var flight: Flight? = null
        private set

    /** The take changed the end of its text by [by] characters. */
    fun appended(by: Int) {
        end += by
    }

    /** The first [length] characters of the region are the opener before the take's own words, so they are not the tail. */
    fun skipLead(length: Int) {
        cleanEnd = (start + length).coerceAtMost(end)
    }

    /** Nothing from [at] on has been cleaned. A settle writes the take raw again, and the cleans after it cover that text. */
    fun cleanedUntil(at: Int) {
        cleanEnd = at.coerceIn(start, end)
    }

    fun flightOver(from: Int, to: Int) {
        flight = Flight(from, to)
    }

    fun dropFlight() {
        flight = null
    }

    /** The flight's stretch was replaced by [length] characters of cleaned text; everything up to them is cleaned. */
    fun flightReplaced(length: Int) {
        val f = checkNotNull(flight)
        end += length - (f.to - f.from)
        cleanEnd = f.from + length
        flight = null
    }

    private var closed = false

    /** The take has stopped: what the user types at the end from here on is theirs, not the take's to clean. */
    fun close() {
        closed = true
    }

    /** The take resumed: dictation at the end is the take's again. Text typed while it was closed stays outside. */
    fun reopen() {
        closed = false
    }

    /** The clean in flight will not be written when it returns. An edit that dirtied it still counts. */
    fun abandonFlight() {
        val current = flight ?: return
        flight = Flight(current.from, current.to, current.dirty, abandoned = true)
    }

    fun apply(edit: Edit) {
        val dirtied = flight?.let { touches(it, edit) } == true
        val insertedAtEnd = !closed && edit.deleted == 0 && edit.at == end
        start = movedAsStart(start, edit)
        cleanEnd = movedAsEnd(cleanEnd, edit)
        end = if (insertedAtEnd) end + edit.inserted else movedAsEnd(end, edit)
        cleanEnd = cleanEnd.coerceIn(start, end)
        end = maxOf(end, cleanEnd)
        flight = flight?.let { Flight(movedAsEnd(it.from, edit), movedAsEnd(it.to, edit), it.dirty || dirtied, it.abandoned) }
    }

    private fun touches(flight: Flight, edit: Edit) =
        if (edit.deleted > 0) edit.at < flight.to && edit.at + edit.deleted > flight.from
        else edit.at >= flight.from && edit.at < flight.to

    private fun movedAsStart(offset: Int, edit: Edit) = when {
        offset >= edit.at + edit.deleted -> offset + edit.inserted - edit.deleted
        offset <= edit.at -> offset
        else -> edit.at
    }

    private fun movedAsEnd(offset: Int, edit: Edit) = when {
        offset <= edit.at -> offset
        offset >= edit.at + edit.deleted -> offset + edit.inserted - edit.deleted
        else -> edit.at
    }
}
