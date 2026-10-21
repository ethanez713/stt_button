package com.ezhou.sttbutton

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ModelsTest {
    private class FakeRecognizer : ClosableRecognizer {
        var closed = false
        override fun recognize(request: RecognizeRequest) = Recognized("heard", if (request.junctionS == null) null else "")
        override fun close() {
            closed = true
        }
    }

    private class FakeCleaner : ClosableCleaner {
        var closed = false
        override fun countTokens(text: String) = Pipeline.wordCount(text)
        override fun clean(request: CleanRequest) = CleanResult(request.target, 1)
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
    fun given_models_when_closed_then_they_are_no_longer_reusable_and_have_no_preview() {
        val models = models()
        assertTrue(models.reusable)
        assertEquals(null, models.awaitPreview())
        models.close()

        assertEquals(false, models.reusable)
    }

    @Test
    fun given_a_recognizer_that_failed_to_load_when_asked_then_the_models_are_not_reusable() {
        val models = Models({ error("no model") }, { cleaner }, { logs += it })
        models.awaitRecognizerLoaded()

        assertEquals(false, models.reusable)
        models.close()
    }

    @Test
    fun given_a_take_that_only_recognizes_when_closed_then_the_cleaner_was_never_loaded() {
        val models = models()
        assertEquals("heard", models.recognizer.recognize(RecognizeRequest(FloatArray(10))).text)
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
    fun given_a_cleaner_not_yet_loaded_when_the_token_counter_is_read_then_it_is_null_and_the_read_does_not_load() {
        val models = models()
        assertNull(models.cleaner().tokenCounter)
        assertNull(models.cleaner().tokenCounter)
        assertEquals(0, cleanerLoads)
        models.cleaner().countTokens("a b")
        assertEquals(2, models.cleaner().tokenCounter!!("a b"))
        models.close()
    }

    @Test
    fun given_no_prepare_when_the_cleaner_is_used_then_it_loads_on_demand_as_a_stop_load() {
        val models = models()
        assertEquals("a b", models.cleaner().clean(CleanRequest("a b"))!!.text)
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

    @Test(timeout = 10_000)
    fun given_a_cleaner_load_when_it_finishes_then_the_callback_sees_the_token_counter() {
        val models = models()
        val ran = CountDownLatch(1)
        var tokens: Int? = null
        var threadName = ""
        models.onCleanerLoaded = {
            threadName = Thread.currentThread().name
            tokens = models.cleaner().tokenCounter?.invoke("a b")
            ran.countDown()
        }
        models.prepareCleaner("text")
        assertTrue(ran.await(5, TimeUnit.SECONDS))
        assertEquals("cleaner-loader", threadName)
        assertEquals(2, tokens)
        models.close()
    }

    @Test(timeout = 10_000)
    fun given_a_cleaner_that_fails_to_load_when_the_load_ends_then_the_callback_runs() {
        val models = Models({ recognizer }, { error("no model") }, { logs += it })
        val ran = CountDownLatch(1)
        var reusable = true
        models.onCleanerLoaded = {
            reusable = models.reusable
            ran.countDown()
        }
        models.prepareCleaner("text")
        assertTrue(ran.await(5, TimeUnit.SECONDS))
        assertEquals(false, reusable)
        models.close()
    }

    @Test(timeout = 10_000)
    fun given_closed_models_when_prepare_runs_then_the_callback_runs_on_the_loader_thread() {
        val models = models()
        models.close()
        val ran = CountDownLatch(1)
        var threadName = ""
        models.onCleanerLoaded = {
            threadName = Thread.currentThread().name
            ran.countDown()
        }
        models.prepareCleaner("text")
        assertTrue(ran.await(5, TimeUnit.SECONDS))
        assertEquals("cleaner-loader", threadName)
    }
}
