package com.ezhou.sttbutton

import java.io.File

/** The model files `push_models.sh` copies into the app's files dir. */
object ModelFiles {
    const val UNIFIED = "models/unified"
    const val S1_MINI = "models/s1-mini/s1-mini-q4_k_m.gguf"
    const val PREVIEW = "models/fc-stream480"

    private val TRANSDUCER = listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt")

    val required: List<String> =
        TRANSDUCER.map { "$UNIFIED/$it" } + S1_MINI + TRANSDUCER.map { "$PREVIEW/$it" }

    fun missing(filesDir: File): List<String> = required.filterNot { File(filesDir, it).isFile }
}
