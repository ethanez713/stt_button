package com.ezhou.sttbutton

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class IcuRegexTest {
    private val jvmOnly = listOf("(?U)", "UNICODE_CHARACTER_CLASS")

    @Test
    fun given_main_sources_when_scanned_then_no_regex_flag_android_rejects() {
        val offenders = File("src/main/java").walkTopDown()
            .filter { it.extension == "kt" }
            .flatMap { file -> jvmOnly.filter { it in file.readText() }.map { "${file.name}: $it" } }
            .toList()
        assertTrue(
            "Android's ICU regex throws on these at class init, which fails every take on the phone: $offenders",
            offenders.isEmpty(),
        )
    }
}
