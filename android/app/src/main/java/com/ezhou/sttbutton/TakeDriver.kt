package com.ezhou.sttbutton

import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs a [TakeEngine] on the main thread: asks it for a job, hands [Job.run] to [worker], applies the
 * result and asks again. Woken by slices landing, a tick while the take is open (pauses and the silence
 * stop), a document edit and [stop]; [onDone] gets the result once the engine has nothing left after the stop.
 */
class TakeDriver(
    private val engine: TakeEngine,
    private val worker: (Runnable) -> Unit,
    private val onDone: (LiveResult) -> Unit,
    private val onError: (Throwable) -> Unit,
    private val tickMs: Long = TICK_MS,
    private val main: Handler = Handler(Looper.getMainLooper()),
) {
    private val queued = AtomicBoolean(false)
    private var running = false
    private var ended = false
    private val tick = object : Runnable {
        override fun run() {
            if (ended) return
            pump()
            main.postDelayed(this, tickMs)
        }
    }

    fun start() {
        engine.onWoken = ::wake
        main.postDelayed(tick, tickMs)
        wake()
    }

    /** Any thread. */
    fun wake() {
        if (queued.compareAndSet(false, true)) main.post {
            queued.set(false)
            pump()
        }
    }

    /** The take stops: what is left is recognized and cleaned, and [onDone] follows. */
    fun stop() {
        engine.stop()
        wake()
    }

    /** Nothing more is asked of the engine; a job in flight finishes unheard. */
    fun abandon() {
        ended = true
        main.removeCallbacks(tick)
    }

    private fun pump() {
        if (ended || running) return
        try {
            val job = engine.next()
            if (job == null) {
                if (engine.stopping) finishNow()
                return
            }
            running = true
            worker(Runnable {
                val outcome = runCatching { job.run() }
                main.post { jobDone(job, outcome) }
            })
        } catch (e: Throwable) {
            fail(e)
        }
    }

    private fun jobDone(job: Job, outcome: Result<JobResult>) {
        running = false
        if (ended) return
        try {
            engine.complete(job, outcome.getOrThrow())
            pump()
        } catch (e: Throwable) {
            fail(e)
        }
    }

    private fun finishNow() {
        ended = true
        main.removeCallbacks(tick)
        onDone(engine.result())
    }

    private fun fail(e: Throwable) {
        ended = true
        main.removeCallbacks(tick)
        onError(e)
    }

    private companion object {
        const val TICK_MS = 100L
    }
}
