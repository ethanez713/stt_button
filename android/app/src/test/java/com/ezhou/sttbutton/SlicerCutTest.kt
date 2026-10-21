package com.ezhou.sttbutton

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Same audio and cut offsets as tests/test_slicing.py. */
class SlicerCutTest {
    private val speechThenSilence = JSONArray("""[["silence",0.5],["noise",3,8000],["silence",2]]""")

    private fun audio(segments: JSONArray) = Fixtures.signal(segments)

    private fun gapped(seconds: Double, vararg gaps: Double): ShortArray {
        val pcm = Fixtures.signal(JSONArray("""[["noise",$seconds,8000]]"""))
        for (start in gaps) {
            val at = (start * Slicer.SAMPLE_RATE).toInt()
            for (i in at until at + Slicer.SAMPLE_RATE / 10) pcm[i] = 0.toShort()
        }
        return pcm
    }

    private fun config(searchS: Double? = null) = PipelineConfig(JSONObject(Fixtures.resource("pipeline.json")).apply {
        if (searchS != null) put("slice_search_s", searchS)
    })

    @Test
    fun given_silence_past_the_cut_pause_when_pause_s_has_not_elapsed_then_the_slice_closes_and_paused_waits() {
        val audio = audio(speechThenSilence)
        val slicer = Slicer(30.0, 5.0, 1.6, 2.0, 10.0, cutPauseS = 0.5)

        val early = slicer.feed(audio.copyOfRange(0, (4.0 * Slicer.SAMPLE_RATE).toInt()))
        assertEquals(1, early.size)
        assertEquals(0, early[0][0])
        assertEquals(60000, early[0][1])
        assertFalse(slicer.paused)
        assertTrue(slicer.feed(audio.copyOfRange((4.0 * Slicer.SAMPLE_RATE).toInt(), (5.0 * Slicer.SAMPLE_RATE).toInt())).isEmpty())
        assertFalse(slicer.paused)
        assertTrue(slicer.feed(audio.copyOfRange((5.0 * Slicer.SAMPLE_RATE).toInt(), (5.1 * Slicer.SAMPLE_RATE).toInt())).isEmpty())
        assertTrue(slicer.paused)

        val whole = Slicer(30.0, 5.0, 1.6, 2.0, 10.0, cutPauseS = 0.5)
        val once = whole.feed(audio.copyOfRange(0, (5.1 * Slicer.SAMPLE_RATE).toInt()))
        assertEquals(1, once.size)
        assertEquals(60000, once[0][1])
        assertTrue(whole.paused)
    }

    @Test
    fun given_a_run_on_when_the_field_slicer_cuts_then_the_cut_is_inside_field_slice_max() {
        val cuts = Slicer.fromConfigForField(config()).feed(gapped(9.0, 4.0))

        assertEquals(1, cuts.size)
        assertEquals(0, cuts[0][0])
        assertEquals(64800, cuts[0][1])
        assertTrue(cuts[0][1] <= 8 * Slicer.SAMPLE_RATE)
    }

    /** Constant samples: the dip at 0.5 s is quieter than the one at 1 s, and neither is speech. */
    private fun dipped(): ShortArray {
        val pcm = ShortArray(9 * Slicer.SAMPLE_RATE) { 8000.toShort() }
        val quiet = Slicer.SAMPLE_RATE / 10
        for (i in Slicer.SAMPLE_RATE / 2 until Slicer.SAMPLE_RATE / 2 + quiet) pcm[i] = 5000.toShort()
        for (i in Slicer.SAMPLE_RATE until Slicer.SAMPLE_RATE + quiet) pcm[i] = 6000.toShort()
        return pcm
    }

    @Test
    fun given_a_search_longer_than_the_field_slice_when_the_field_slicer_is_built_then_the_window_stays_inside() {
        val cfg = config(searchS = 100.0)
        val cuts = Slicer.fromConfigForField(cfg).feed(dipped())

        assertEquals(1, cuts.size)
        assertEquals(16800, cuts[0][1])
        assertTrue(cuts[0][1] < (cfg.fieldSliceMaxS * Slicer.SAMPLE_RATE).toInt())
    }

    @Test
    fun given_buffer_config_when_audio_is_sliced_then_cuts_match_a_slicer_without_a_separate_cut_pause() {
        val cfg = config()
        val audio = audio(speechThenSilence)
        val buffered = Slicer.fromConfig(cfg)
        val plain = Slicer(cfg.sliceMaxS, cfg.sliceSearchS, cfg.pauseS, cfg.sliceMinS, cfg.pauseMarginDb, cfg.stopSilenceDbfs)
        val explicit = Slicer(
            cfg.sliceMaxS, cfg.sliceSearchS, cfg.pauseS, cfg.sliceMinS, cfg.pauseMarginDb, cfg.stopSilenceDbfs,
            cutPauseS = cfg.pauseS,
        )

        assertTrue(buffered.feed(audio.copyOfRange(0, (4.0 * Slicer.SAMPLE_RATE).toInt())).isEmpty())
        assertFalse(buffered.paused)
        val rest = audio.copyOfRange((4.0 * Slicer.SAMPLE_RATE).toInt(), (5.1 * Slicer.SAMPLE_RATE).toInt())
        val fromBuffer = buffered.feed(rest)
        plain.feed(audio.copyOfRange(0, (4.0 * Slicer.SAMPLE_RATE).toInt()))
        val fromPlain = plain.feed(rest)
        assertEquals(1, fromBuffer.size)
        assertEquals(68800, fromBuffer[0][1])
        assertEquals(fromPlain[0][0], fromBuffer[0][0])
        assertEquals(fromPlain[0][1], fromBuffer[0][1])
        assertTrue(buffered.paused && plain.paused)
        val once = explicit.feed(audio.copyOfRange(0, (5.1 * Slicer.SAMPLE_RATE).toInt()))
        assertEquals(68800, once[0][1])

        val long = audio(JSONArray("""[["noise",35,8000]]"""))
        val got = Slicer.fromConfig(cfg).feed(long)
        val reference = Slicer(cfg.sliceMaxS, cfg.sliceSearchS, cfg.pauseS, cfg.sliceMinS, cfg.pauseMarginDb).feed(long)
        assertEquals(reference.size, got.size)
        assertEquals(reference[0][0], got[0][0])
        assertEquals(reference[0][1], got[0][1])
        assertTrue(got[0][1] > 8 * Slicer.SAMPLE_RATE)
        assertTrue(got[0][1] <= 30 * Slicer.SAMPLE_RATE)
    }
}
