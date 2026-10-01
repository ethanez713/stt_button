package com.ezhou.sttbutton

import org.json.JSONArray
import org.json.JSONObject

object Fixtures {
    fun resource(name: String): String =
        checkNotNull(javaClass.classLoader.getResourceAsStream(name)) { "missing test resource $name" }
            .bufferedReader().readText()

    val vectors: JSONObject by lazy { JSONObject(resource("pipeline_vectors.json")) }
    val config: PipelineConfig by lazy { PipelineConfig(JSONObject(resource("pipeline.json"))) }

    fun objects(array: JSONArray): List<JSONObject> = List(array.length(), array::getJSONObject)
    fun strings(array: JSONArray): List<String> = List(array.length(), array::getString)

    /** Mirrors `tests/make_vectors.pcm_signal`: Lehmer noise and silence. */
    fun signal(segments: JSONArray): ShortArray {
        val out = ArrayList<Short>()
        var index = 0L
        for (i in 0 until segments.length()) {
            val segment = segments.getJSONArray(i)
            val count = (segment.getDouble(1) * Slicer.SAMPLE_RATE).toInt()
            if (segment.getString(0) == "noise") {
                val amp = segment.getLong(2)
                for (k in 0 until count) out += (((index + k) * 48271) % 2147483647 % (2 * amp + 1) - amp).toShort()
            } else {
                repeat(count) { out += 0 }
            }
            index += count
        }
        return out.toShortArray()
    }

    fun words(text: String): Int = Pipeline.wordCount(text)
}
