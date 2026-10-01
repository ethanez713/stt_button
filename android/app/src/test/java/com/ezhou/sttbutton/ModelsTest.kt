package com.ezhou.sttbutton

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelsTest {
    private class FakeRecognizer : ClosableRecognizer {
        var closed = false
        override fun recognize(samples: FloatArray) = "heard"
        override fun recognizeAfter(samples: FloatArray, junctionS: Double) = "" to "heard"
        override fun close() {
            closed = true
        }
    }

    private class FakeCleaner : ClosableCleaner {
        var closed = false
        override fun countTokens(text: String) = Pipeline.wordCount(text)
        override fun clean(chunk: String) = ChunkResult(chunk, 1)
        override fun cancel() {}
        override fun close() {
            closed = true
        }
    }

    private val recognizer = FakeRecognizer()
    private val cleaner = FakeCleaner()
    private val logs = mutableListOf<String>()
    private var cleanerLoads = 0

    private fun models() = Models({ recognizer }, { cleanerLoads++; cleaner }, { logs += it })

    @Test
    fun given_a_take_that_only_recognizes_when_closed_then_the_cleaner_was_never_loaded() {
        val models = models()
        assertEquals("heard", models.recognizer.recognize(FloatArray(10)))
        models.close()

        assertEquals(0, cleanerLoads)
        assertTrue(recognizer.closed)
        assertTrue(logs.single().startsWith("recognizer loaded"))
    }

    @Test
    fun given_prepare_called_twice_when_the_cleaner_is_used_then_it_loads_once_with_the_first_trigger() {
        val models = models()
        models.prepareCleaner("time")
        models.prepareCleaner("text")
        assertEquals(3, models.cleaner().countTokens("one two three"))
        models.close()

        assertEquals(1, cleanerLoads)
        assertTrue(cleaner.closed)
        assertTrue(logs.any { it.startsWith("cleaner loaded") && it.endsWith("trigger=time") })
    }

    @Test
    fun given_no_prepare_when_the_cleaner_is_used_then_it_loads_on_demand_as_a_stop_load() {
        val models = models()
        assertEquals("a b", models.cleaner().clean("a b")!!.text)
        models.close()

        assertEquals(1, cleanerLoads)
        assertTrue(logs.any { it.endsWith("trigger=stop") })
    }

    @Test
    fun given_closed_models_when_the_cleaner_is_asked_for_then_it_fails_instead_of_hanging() {
        val models = models()
        models.close()

        val failure = runCatching { models.cleaner().countTokens("x") }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals(0, cleanerLoads)
    }
}
