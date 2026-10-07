package com.ezhou.sttbutton

import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs a [TakeEngine] on the main thread: asks it for a job, hands [Job.run] to [worker], applies the
 * result and asks again. Woken by slices landing, a tick while the take is open (pauses and the silence
 * stop; [onTick] runs on it), a document edit and [stop]; [onDone] gets the result once the engine has
 * nothing left after the stop.
 */
class TakeDriver(
    private val engine: TakeEngine,
    private val worker: (Runnable) -> Unit,
    private val onDone: (LiveResult) -> Unit,
    private val onError: (Throwable) -> Unit,
    private val onTick: () -> Unit = {},
    private val tickMs: Long = TICK_MS,
    private val main: Handler = Handler(Looper.getMainLooper()),
) {
    private val queued = AtomicBoolean(false)
    @Volatile private var running = false
    private var ended = false
    private var settled: (() -> Unit)? = null
    private val tick = object : Runnable {
        override fun run() {
            if (ended) return
            onTick()
            pump()
            main.postDelayed(this, tickMs)
        }
    }

    /** Any thread. */
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

    /** Any thread but the main one: returns once no job is in flight and the engine has asked for none since. */
    fun settle() {
        while (true) {
            while (running) Thread.sleep(SETTLE_POLL_MS)
            val ran = java.util.concurrent.CountDownLatch(1)
            main.post {
                pump()
                ran.countDown()
            }
            ran.await()
            if (!running) return
        }
    }

    /** Any thread: the take stops, what is left is recognized and cleaned, and [onDone] follows. */
    fun stop() {
        main.post {
            engine.markStop()
            engine.stop()
            pump()
        }
    }

    /** Nothing more is asked of the engine; [onSettled] runs on the main thread once no job is in flight. */
    fun abandon(onSettled: () -> Unit) {
        main.post {
            ended = true
            main.removeCallbacks(tick)
            if (running) settled = onSettled else onSettled()
        }
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
        if (ended) {
            settled?.invoke()
            settled = null
            return
        }
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
        const val SETTLE_POLL_MS = 2L
    }
}
