package com.ezhou.sttbutton

/** A take recording on disk. [modifiedAtMs] orders it; [bytes] counts toward the cap. */
data class TakeAudioFile(val name: String, val bytes: Long, val modifiedAtMs: Long)

object TakeAudio {
    const val KEEP_NEWEST = 20
    const val MAX_BYTES = 100L * 1024 * 1024

    /** Names to delete, oldest first. The newest [keepNewest] stay, and only while they total at most [maxBytes]. */
    fun toDelete(files: List<TakeAudioFile>, keepNewest: Int = KEEP_NEWEST, maxBytes: Long = MAX_BYTES): List<String> {
        val oldestFirst = files.sortedWith(compareBy({ it.modifiedAtMs }, { it.name }))
        val newest = oldestFirst.takeLast(keepNewest).map { it.name }.toSet()
        var total = files.sumOf { it.bytes }
        val deleting = mutableListOf<String>()
        for (file in oldestFirst) {
            if (file.name in newest && total <= maxBytes) break
            deleting += file.name
            total -= file.bytes
        }
        return deleting
    }
}
