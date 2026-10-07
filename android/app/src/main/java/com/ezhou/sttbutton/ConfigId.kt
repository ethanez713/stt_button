package com.ezhou.sttbutton

import java.security.MessageDigest

/** Names a take's configuration: the pipeline settings and the size of each model file that produced it. */
object ConfigId {
    private const val LENGTH = 12

    fun of(configBytes: ByteArray, models: List<Pair<String, Long>>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(configBytes)
        for ((name, bytes) in models.sortedBy { it.first }) digest.update("\n$name:$bytes".toByteArray())
        return digest.digest().joinToString("") { "%02x".format(it) }.take(LENGTH)
    }
}
