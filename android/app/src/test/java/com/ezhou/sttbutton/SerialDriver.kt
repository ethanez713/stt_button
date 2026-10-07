package com.ezhou.sttbutton

/** Runs an engine's jobs inline, in the order the real driver would, for tests. */
class SerialDriver(private val engine: TakeEngine) {
    /** Jobs run until the engine has nothing to ask for. */
    fun drain(): Int {
        var jobs = 0
        while (true) {
            val job = engine.next() ?: return jobs
            engine.complete(job, job.run())
            jobs++
        }
    }
}

/** Holds back the pause-time cleans until a test lets them run. */
class GatedPolicy(private val inner: CleanPolicy) : CleanPolicy {
    var pauseOpen = false
    override val name get() = inner.name
    override fun next(view: PolicyView, moment: Moment) = if (moment == Moment.PAUSE && !pauseOpen) null else inner.next(view, moment)
}
