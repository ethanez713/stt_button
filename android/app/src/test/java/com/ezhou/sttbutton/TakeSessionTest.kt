package com.ezhou.sttbutton

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TakeSessionTest {
    private var now = 0L
    private val capMs = 300_000L

    private fun session() = TakeSession(Take("t", "app", 0), capMs) { now }

    private fun TakeSession.open(swap: SwapState? = null): List<TakeEffect> {
        val effects = mutableListOf<TakeEffect>()
        effects += on(TakeEvent.Tap(micGranted = true))
        effects += on(TakeEvent.RecorderStarted)
        if (swap != null) effects += on(TakeEvent.SwapAsked(swap))
        return effects
    }

    private fun List<TakeEffect>.count(type: Class<out TakeEffect>) = count { type.isInstance(it) }

    private fun List<TakeEffect>.of(type: Class<out TakeEffect>) = filter { type.isInstance(it) }

    private fun liveSwap(): SwapState = SwapState.started(true, 0).also { it.onViewStart(100) }

    @Test
    fun given_a_tap_stop_result_when_run_then_the_effects_come_in_order_and_the_text_is_inserted_once() {
        val s = session()
        val opening = s.open()
        assertEquals(
            listOf(TakeEffect.StartTake, TakeEffect.AskSwap, TakeEffect.ShowBubble),
            opening.filter { it === TakeEffect.StartTake || it === TakeEffect.AskSwap || it === TakeEffect.ShowBubble },
        )
        assertEquals(ButtonMode.RECORDING, s.buttonMode)
        assertTrue(opening.contains(TakeEffect.Schedule(capMs, TakeEvent.Cap)))

        val stopping = s.on(TakeEvent.Tap(true))
        assertEquals(
            listOf(TakeEffect.PanelBusy(true), TakeEffect.StopRecorder, TakeEffect.StopEngine),
            stopping.filter { it is TakeEffect.PanelBusy || it === TakeEffect.StopRecorder || it === TakeEffect.StopEngine },
        )
        assertEquals(ButtonMode.BUSY, s.buttonMode)

        val ending = s.on(TakeEvent.EngineFinished(Delivery.INSERT))
        assertEquals(listOf(TakeEffect.Insert("stop"), TakeEffect.Release), ending.take(2))
        assertEquals(TakeEffect.ParkModels, ending.last())
        assertTrue(s.finished)
        assertEquals(TakeState.INSERTED, s.take.state)
    }

    @Test
    fun given_each_stop_trigger_when_it_arrives_then_the_take_stops_once_and_the_ending_line_names_it() {
        val triggers = listOf(
            TakeEvent.Cap to "cap", TakeEvent.Silence to "silence", TakeEvent.MicFailed to "mic",
            TakeEvent.PackageChanged to "package", TakeEvent.Tap(true) to "stop",
        )
        for ((event, name) in triggers) {
            val s = session()
            s.open()
            val stop = s.on(event)
            assertEquals(name, 1, stop.count(TakeEffect.StopRecorder::class.java))
            assertEquals(name, 1, stop.count(TakeEffect.StopEngine::class.java))
            assertEquals(name, s.stopTrigger)
            assertTrue(name, s.on(TakeEvent.Cap).isEmpty())
            assertEquals(listOf(TakeEffect.Insert(name)), s.on(TakeEvent.EngineFinished(Delivery.INSERT)).of(TakeEffect.Insert::class.java))
        }
    }

    @Test
    fun given_a_keyboard_stop_trigger_when_it_arrives_then_the_bubble_is_hidden_with_it() {
        val s = session()
        s.open(liveSwap())

        val effects = s.on(TakeEvent.ExcludedField)

        assertEquals("keyboard", s.stopTrigger)
        assertTrue(effects.contains(TakeEffect.HideBubble))
    }

    @Test
    fun given_the_field_going_away_when_the_take_is_open_then_it_stops_with_the_field_trigger_and_a_second_event_does_nothing() {
        val s = session()
        s.open()

        val effects = s.on(TakeEvent.FieldGone)

        assertEquals("field", s.stopTrigger)
        assertTrue(effects.contains(TakeEffect.StopRecorder))
        assertTrue(effects.contains(TakeEffect.StopEngine))
        assertEquals(emptyList<TakeEffect>(), s.on(TakeEvent.FieldGone))
    }

    @Test
    fun given_a_mic_failure_when_it_stops_the_take_then_the_button_flashes() {
        val s = session()
        s.open()

        val effects = s.on(TakeEvent.MicFailed)

        assertTrue(effects.contains(TakeEffect.SetButton(ButtonMode.FAILED)))
        assertTrue(effects.contains(TakeEffect.Schedule(TakeSession.FAILED_HOLD_MS, TakeEvent.FlashEnded)))
        assertEquals(ButtonMode.FAILED, s.buttonMode)
        assertEquals(listOf(TakeEffect.SetButton(ButtonMode.BUSY)), s.on(TakeEvent.FlashEnded))
    }

    @Test
    fun given_a_scheduled_event_after_its_state_has_passed_when_it_arrives_then_nothing_happens() {
        val s = session()
        s.open()
        s.on(TakeEvent.Tap(true))
        s.on(TakeEvent.EngineFinished(Delivery.INSERT))

        for (late in listOf(TakeEvent.Cap, TakeEvent.HiddenCheckDue(1), TakeEvent.SwapWaitDue, TakeEvent.KeyboardStopDue, TakeEvent.Silence,
            TakeEvent.EngineFinished(Delivery.INSERT), TakeEvent.Error, TakeEvent.LanguageLeft, TakeEvent.ViewFinished)) {
            assertEquals(late.toString(), emptyList<TakeEffect>(), s.on(late))
        }
    }

    @Test
    fun given_a_second_tap_while_stopping_when_it_arrives_then_nothing_happens() {
        val s = session()
        s.open()
        s.on(TakeEvent.Tap(true))

        assertEquals(emptyList<TakeEffect>(), s.on(TakeEvent.Tap(true)))
    }

    @Test
    fun given_a_tap_without_the_microphone_when_it_arrives_then_the_button_flashes_and_no_take_starts() {
        val s = session()

        val effects = s.on(TakeEvent.Tap(micGranted = false))

        assertFalse(effects.contains(TakeEffect.StartTake))
        assertTrue(effects.contains(TakeEffect.SetButton(ButtonMode.FAILED)))
        assertFalse(s.finished)
        assertEquals(emptyList<TakeEffect>(), s.on(TakeEvent.Tap(true)))
        s.on(TakeEvent.FlashEnded)
        assertTrue(s.finished)
    }

    @Test
    fun given_the_button_hidden_for_less_than_the_grace_when_it_returns_then_the_take_goes_on() {
        val s = session()
        s.open()

        now = 100
        val hidden = s.on(TakeEvent.ButtonShown(false))
        assertEquals(listOf(TakeEffect.HideBubble, TakeEffect.Schedule(700, TakeEvent.HiddenCheckDue(1))), hidden)
        now = 500
        val shown = s.on(TakeEvent.ButtonShown(true))
        assertEquals(listOf(TakeEffect.ShowBubble), shown)
        assertEquals(emptyList<TakeEffect>(), s.on(TakeEvent.HiddenCheckDue(1)))
        assertEquals(ButtonMode.RECORDING, s.buttonMode)
    }

    @Test
    fun given_the_button_hidden_for_longer_than_the_grace_when_checked_then_the_take_stops_with_keyboard() {
        val s = session()
        s.open()

        now = 100
        s.on(TakeEvent.ButtonShown(false))
        now = 600
        assertEquals(listOf(TakeEffect.Schedule(200, TakeEvent.HiddenCheckDue(2))), s.on(TakeEvent.ButtonShown(false)))
        now = 800
        assertEquals(listOf(TakeEffect.Recheck), s.on(TakeEvent.HiddenCheckDue(2)).also { assertEquals("stop", s.stopTrigger) })
        val stopped = s.on(TakeEvent.ButtonShown(false))

        assertEquals("keyboard", s.stopTrigger)
        assertEquals(1, stopped.count(TakeEffect.StopRecorder::class.java))
        assertEquals(TakeState.STOPPING, s.take.state)
    }

    @Test
    fun given_a_swap_that_never_comes_up_when_the_wait_runs_out_then_the_keyboard_is_returned_and_the_take_goes_on_without_it() {
        val s = session()
        val opening = s.open(SwapState.started(true, 0))
        assertTrue(opening.contains(TakeEffect.Schedule(SwapState.PENDING_TIMEOUT_MS, TakeEvent.SwapWaitDue)))

        now = SwapState.PENDING_TIMEOUT_MS
        val timeout = s.on(TakeEvent.SwapWaitDue)

        assertEquals(1, timeout.count { it == TakeEffect.ReturnKeyboard(stranded = false) })
        assertEquals(TakeState.OPEN, s.take.state)
        assertFalse(s.holdsTake)
        now += 100
        s.on(TakeEvent.ButtonShown(false))
        now += 700
        s.on(TakeEvent.ButtonShown(false))
        assertEquals("keyboard", s.stopTrigger)
        assertEquals(emptyList<TakeEffect>(), s.on(TakeEvent.SwapWaitDue))
    }

    @Test
    fun given_a_swap_that_is_live_when_the_wait_event_arrives_late_then_nothing_happens() {
        val s = session()
        s.open(SwapState.started(true, 0))
        now = 300
        s.on(TakeEvent.ViewStarted)
        now = 1_000

        assertEquals(emptyList<TakeEffect>(), s.on(TakeEvent.SwapWaitDue))
        assertTrue(s.holdsTake)
    }

    @Test
    fun given_the_keyboards_view_finishing_mid_take_when_it_stays_closed_then_the_take_stops_and_when_it_reopens_it_does_not() {
        val s = session()
        s.open(SwapState.started(true, 0))
        now = 200
        s.on(TakeEvent.ViewStarted)
        now = 1_000
        val closing = s.on(TakeEvent.ViewFinished)
        assertEquals(listOf(TakeEffect.Schedule(SwapState.KEYBOARD_STOP_MS, TakeEvent.KeyboardStopDue)), closing)
        now = 1_300
        s.on(TakeEvent.ViewStarted)
        now = 1_700
        assertEquals(emptyList<TakeEffect>(), s.on(TakeEvent.KeyboardStopDue))
        assertEquals(TakeState.OPEN, s.take.state)

        now = 2_000
        s.on(TakeEvent.ViewFinished)
        now = 2_699
        assertEquals(emptyList<TakeEffect>(), s.on(TakeEvent.KeyboardStopDue))
        now = 2_700
        val due = s.on(TakeEvent.KeyboardStopDue)

        assertEquals(1, due.count(TakeEffect.StopRecorder::class.java))
        assertEquals("keyboard", s.stopTrigger)
    }

    @Test
    fun given_the_button_absent_while_the_take_holds_the_keyboard_when_checked_then_the_take_does_not_stop() {
        val s = session()
        s.open(liveSwap())

        now = 100
        s.on(TakeEvent.ButtonShown(false))
        now = 5_000

        assertEquals(emptyList<TakeEffect>(), s.on(TakeEvent.ButtonShown(false)))
        assertEquals(TakeState.OPEN, s.take.state)
    }

    @Test
    fun given_an_excluded_field_when_the_take_holds_the_keyboard_then_it_stops_and_otherwise_it_is_ignored() {
        val without = session()
        without.open()
        assertEquals(emptyList<TakeEffect>(), without.on(TakeEvent.ExcludedField))

        val with = session()
        with.open(liveSwap())
        assertEquals(1, with.on(TakeEvent.ExcludedField).count(TakeEffect.StopRecorder::class.java))
    }

    @Test
    fun given_the_language_leaving_english_when_the_take_does_not_hold_the_keyboard_then_it_is_discarded_without_an_insert() {
        val s = session()
        s.open()

        val effects = s.on(TakeEvent.LanguageLeft)

        assertEquals(listOf(TakeEffect.LogDiscard(DiscardReason.LANGUAGE, null)), effects.of(TakeEffect.LogDiscard::class.java))
        assertEquals(1, effects.count(TakeEffect.StopRecorder::class.java))
        assertEquals(0, effects.count(TakeEffect.Insert::class.java))
        assertTrue(s.finished)
        assertEquals(emptyList<TakeEffect>(), s.on(TakeEvent.EngineFinished(Delivery.INSERT)))
    }

    @Test
    fun given_the_language_leaving_english_while_the_take_holds_the_keyboard_then_it_is_ignored() {
        val s = session()
        s.open(liveSwap())

        assertEquals(emptyList<TakeEffect>(), s.on(TakeEvent.LanguageLeft))
    }

    @Test
    fun given_an_error_after_the_stop_when_it_arrives_then_the_take_is_discarded_once_and_the_keyboard_returned_once() {
        val s = session()
        s.open(liveSwap())
        s.on(TakeEvent.Tap(true))

        val first = s.on(TakeEvent.Error)
        val second = s.on(TakeEvent.Error)

        assertEquals(listOf(TakeEffect.LogDiscard(DiscardReason.ERROR, null)), first.of(TakeEffect.LogDiscard::class.java))
        assertEquals(1, first.count { it == TakeEffect.ReturnKeyboard(stranded = false) })
        assertTrue(first.contains(TakeEffect.SetButton(ButtonMode.FAILED)))
        assertEquals(emptyList<TakeEffect>(), second)
    }

    @Test
    fun given_a_result_with_no_words_when_it_arrives_then_the_take_writes_a_discard_with_its_trigger() {
        val s = session()
        s.open()
        s.on(TakeEvent.Silence)

        val effects = s.on(TakeEvent.EngineFinished(Delivery.BLANK))

        assertEquals(listOf(TakeEffect.LogDiscard(DiscardReason.EMPTY, "silence")), effects.of(TakeEffect.LogDiscard::class.java))
        assertEquals(0, effects.count(TakeEffect.Insert::class.java))
    }

    @Test
    fun given_a_result_with_nowhere_to_go_when_it_arrives_then_it_goes_to_the_clipboard() {
        for ((delivery, reason) in listOf(Delivery.NO_FIELD to DiscardReason.NO_FIELD, Delivery.OTHER_PACKAGE to DiscardReason.PACKAGE)) {
            val s = session()
            s.open()
            s.on(TakeEvent.Tap(true))

            val effects = s.on(TakeEvent.EngineFinished(delivery))

            assertEquals(listOf(TakeEffect.CopyUnplaced(reason, "stop")), effects.of(TakeEffect.CopyUnplaced::class.java))
            assertEquals(TakeState.DISCARDED, s.take.state)
        }
    }

    @Test
    fun given_the_recorder_failing_to_start_when_reported_then_the_take_is_discarded_with_an_error() {
        val s = session()
        s.on(TakeEvent.Tap(true))

        val effects = s.on(TakeEvent.RecorderStartFailed)

        assertEquals(listOf(TakeEffect.LogDiscard(DiscardReason.ERROR, null)), effects.of(TakeEffect.LogDiscard::class.java))
        assertTrue(effects.contains(TakeEffect.ParkModels))
    }

    @Test
    fun given_a_view_start_with_no_swap_when_reported_then_the_keyboard_is_returned_as_stranded() {
        val s = session()
        s.open()

        assertEquals(listOf(TakeEffect.ReturnKeyboard(stranded = true)), s.on(TakeEvent.ViewStarted))
    }

    @Test
    fun given_a_returned_keyboard_when_the_ime_line_is_due_then_it_is_written() {
        val s = session()
        s.open(liveSwap())
        s.on(TakeEvent.Tap(true))
        val ending = s.on(TakeEvent.EngineFinished(Delivery.INSERT))

        assertTrue(ending.contains(TakeEffect.Schedule(TakeSession.IME_LINE_DELAY_MS, TakeEvent.ImeLineDue)))
        assertEquals(listOf(TakeEffect.WriteImeLine), s.on(TakeEvent.ImeLineDue))
    }

    private val everyEvent: List<TakeEvent> = listOf(
        TakeEvent.Tap(true), TakeEvent.ButtonShown(false), TakeEvent.ButtonShown(true), TakeEvent.Cap, TakeEvent.Silence,
        TakeEvent.MicFailed, TakeEvent.PackageChanged, TakeEvent.FieldGone, TakeEvent.LanguageLeft, TakeEvent.ExcludedField, TakeEvent.ViewStarted,
        TakeEvent.ViewFinished, TakeEvent.Error, TakeEvent.EngineFinished(Delivery.INSERT), TakeEvent.EngineFinished(Delivery.BLANK),
        TakeEvent.EngineFinished(Delivery.NO_FIELD), TakeEvent.SwapWaitDue, TakeEvent.KeyboardStopDue, TakeEvent.HiddenCheckDue(1),
    )

    @Test
    fun given_any_four_events_after_the_start_when_the_take_has_ended_then_the_recorder_is_stopped_and_the_keyboard_returned() {
        var sequences = 0
        for (swapKind in listOf("off", "pending", "live")) {
            fun fresh(): TakeSession {
                now = 0
                val s = session()
                s.on(TakeEvent.Tap(true))
                s.on(TakeEvent.RecorderStarted)
                when (swapKind) {
                    "pending" -> s.on(TakeEvent.SwapAsked(SwapState.started(true, 0)))
                    "live" -> s.on(TakeEvent.SwapAsked(liveSwap()))
                }
                return s
            }
            fun walk(prefix: List<TakeEvent>, depth: Int) {
                val s = fresh()
                var recorderOn = true
                var returns = 0
                val owed = s.swap.returnOwed
                for ((i, event) in prefix.withIndex()) {
                    now = 800L * (i + 1)
                    for (effect in s.on(event)) {
                        if (effect === TakeEffect.StopRecorder) recorderOn = false
                        if (effect == TakeEffect.ReturnKeyboard(stranded = false)) returns++
                    }
                }
                sequences++
                if (s.ended) {
                    assertFalse("recorder left running after $prefix ($swapKind)", recorderOn)
                    assertTrue("keyboard returned more than once after $prefix ($swapKind)", returns <= 1)
                    if (owed || swapKind != "off") assertTrue("keyboard unreturned after $prefix ($swapKind)", returns == 1 || !s.swap.returnOwed)
                }
                if (depth < 4) for (event in everyEvent) walk(prefix + event, depth + 1)
            }
            walk(emptyList(), 0)
        }
        assertTrue(sequences > 1_000)
    }
}
