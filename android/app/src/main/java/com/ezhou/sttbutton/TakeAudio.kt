package com.ezhou.sttbutton

/** A file in the takes directory: a recording (`<id>.pcm`) or the empty marker (`<id>.corrected`) of a take the user edited. */
data class TakeAudioFile(val name: String, val bytes: Long, val modifiedAtMs: Long)

object TakeAudio {
    const val MAX_BYTES = 300L * 1024 * 1024
    const val CORRECTED = "corrected"
    private const val PCM = "pcm"

    /**
     * Names to delete. Recordings over [maxBytes] go oldest first, those without a corrected marker
     * before those with one; [inProgress] never goes. A marker leaves with its recording, or when none is left.
     */
    fun toDelete(files: List<TakeAudioFile>, inProgress: String? = null, maxBytes: Long = MAX_BYTES): List<String> {
        val recordings = files.filter { it.name.endsWith(".$PCM") }
        val corrected = files.filter { it.name.endsWith(".$CORRECTED") }.map { it.name.removeSuffix(".$CORRECTED") }.toSet()
        val order = compareBy<TakeAudioFile>({ it.name.removeSuffix(".$PCM") in corrected }, { it.modifiedAtMs }, { it.name })
        var total = recordings.sumOf { it.bytes }
        val deleting = mutableListOf<String>()
        for (file in recordings.sortedWith(order)) {
            if (total <= maxBytes) break
            if (file.name == inProgress) continue
            deleting += file.name
            total -= file.bytes
        }
        val kept = recordings.map { it.name.removeSuffix(".$PCM") }.toSet() - deleting.map { it.removeSuffix(".$PCM") }.toSet()
        deleting += corrected.filter { it !in kept }.sorted().map { "$it.$CORRECTED" }
        return deleting
    }
}
