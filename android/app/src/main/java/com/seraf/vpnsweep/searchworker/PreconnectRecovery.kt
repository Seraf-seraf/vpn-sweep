package com.seraf.vpnsweep.searchworker

internal interface PreconnectWorkerStop {
    fun beginSupervisorStop(attempt: Any, unbound: (Boolean) -> Unit): Boolean
    fun replaceSupervisorAttempt(attempt: Any): Boolean
    fun supervisorDied(proof: SupervisorDeathProof): Boolean
    fun observeRetirement(completed: (CoordinatorRetirementProof?) -> Unit): Boolean
    fun ownsRetirement(proof: CoordinatorRetirementProof): Boolean
}

internal class PreconnectRecoveryProof internal constructor()

internal fun handlePreconnectOpenFailure(
    timedOut: Boolean,
    now: Long,
    deadline: Long,
    reserve: () -> Boolean,
    start: () -> Boolean,
    fail: () -> Unit,
): Unit {
    if (timedOut && now >= 0 && now >= deadline && reserve() && start()) return
    fail()
}

internal fun startPreconnectRecovery(
    generation: Long,
    custody: SearchOwnerLeaseControl,
    drain: SearchWorkerLocalDrain,
    worker: PreconnectWorkerStop,
    schedule: (Long, () -> Unit) -> Boolean,
    newAttempt: ((SupervisorDeathProof) -> Unit, () -> Unit, () -> Unit) -> SupervisorAttemptPort,
    producer: SearchSessionProducer,
    intent: PhaseRecoveryIntent,
): Boolean = producer.beginPreconnectRecovery(
    generation,
    PreconnectRecovery(generation, custody, drain, worker, schedule, newAttempt, intent),
)

internal class PreconnectRecovery(
    private val generation: Long,
    private val custody: Any,
    private val drain: SearchWorkerLocalDrain,
    private val worker: PreconnectWorkerStop,
    private val schedule: (Long, () -> Unit) -> Boolean,
    private val newAttempt: ((SupervisorDeathProof) -> Unit, () -> Unit, () -> Unit) -> SupervisorAttemptPort,
    private val intent: PhaseRecoveryIntent? = null,
) {
    private val lock = Any()
    private var started = false
    private var finished = false
    private var setup = false
    private var oldUnbound = false
    private var workerRetired = false
    private var localDrained = false
    private var attemptStarted = false
    private var token: Any? = null
    private var attempt: SupervisorAttemptPort? = null
    private var completion: ((PreconnectRecoveryProof?) -> Unit)? = null
    private var issued: PreconnectRecoveryProof? = null
    private var consumed = false

    fun belongsTo(generation: Long, custody: Any, intent: PhaseRecoveryIntent): Boolean =
        this.generation == generation && this.custody === custody && this.intent === intent

    fun consume(proof: PreconnectRecoveryProof): Boolean = synchronized(lock) {
        if (!finished || consumed || issued !== proof) false else { consumed = true; true }
    }

    fun start(completed: (PreconnectRecoveryProof?) -> Unit): Boolean {
        synchronized(lock) { if (started) return false; started = true; completion = completed }
        val first = makeAttempt() ?: return failStart()
        if (!worker.beginSupervisorStop(first.identity, ::oldUnbound)) return failStart()
        if (!worker.observeRetirement { proof ->
                if (proof == null || !worker.ownsRetirement(proof)) fail()
                else { synchronized(lock) { workerRetired = true }; enqueue(0, ::advance) }
            }) return failStart()
        if (!drain.seal(generation) { proof ->
                if (proof == null || !proof.belongsTo(drain, generation)) fail()
                else { synchronized(lock) { localDrained = true }; enqueue(0, ::advance) }
            }) return failStart()
        synchronized(lock) { setup = true }
        return enqueue(0, ::advance)
    }

    private fun oldUnbound(success: Boolean): Unit {
        if (!success) return fail()
        synchronized(lock) { oldUnbound = true }
        enqueue(0, ::advance)
    }

    private fun makeAttempt(): SupervisorAttemptPort? {
        val selected = Any()
        synchronized(lock) { if (finished) return null; token = selected; attemptStarted = false }
        val value = try {
            newAttempt(
                { proof -> if (current(selected) && !worker.supervisorDied(proof)) fail() },
                { if (current(selected)) enqueue(RETRY_MILLIS) { retry(selected) } },
                { if (current(selected)) fail() },
            )
        } catch (_: Exception) { return null } catch (_: LinkageError) { return null }
        synchronized(lock) { if (finished || token !== selected) return null; attempt = value }
        return value
    }

    private fun retry(previous: Any): Unit {
        if (!current(previous)) return
        val next = makeAttempt() ?: return fail()
        if (!worker.replaceSupervisorAttempt(next.identity)) return fail()
        advance()
    }

    private fun advance(): Unit {
        var run: SupervisorAttemptPort? = null
        var selected: Any? = null
        var done = false
        synchronized(lock) {
            if (finished || !setup) return
            if (workerRetired && localDrained) done = true
            else if (oldUnbound && !attemptStarted) {
                attemptStarted = true
                run = attempt
                selected = token
            }
        }
        if (done) return finish(true)
        val starting = run ?: return
        when (try { starting.start() } catch (_: Exception) { SupervisorStartResult.UNKNOWN } catch (_: LinkageError) { SupervisorStartResult.UNKNOWN }) {
            SupervisorStartResult.STARTED -> enqueue(CONNECTION_TIMEOUT_MILLIS) { if (current(selected)) starting.connectionTimedOut() }
            SupervisorStartResult.RETRYING -> Unit
            SupervisorStartResult.UNKNOWN -> fail()
        }
    }

    private fun current(candidate: Any?): Boolean = synchronized(lock) { !finished && candidate != null && token === candidate }

    private fun enqueue(delay: Long, action: () -> Unit): Boolean {
        val accepted = try { schedule(delay, action) } catch (_: Exception) { false } catch (_: LinkageError) { false }
        if (!accepted) finish(false)
        return accepted
    }

    private fun failStart(): Boolean { finish(false); return false }
    private fun fail(): Unit { enqueue(0) { finish(false) } }
    private fun finish(success: Boolean): Unit {
        val delivery = synchronized(lock) {
            if (finished) return
            finished = true
            val proof = if (success) PreconnectRecoveryProof().also { issued = it } else null
            completion?.let { callback -> { callback(proof) } }
        }
        if (delivery != null) try { delivery() } catch (_: Exception) { } catch (_: LinkageError) { }
    }

    private companion object {
        const val RETRY_MILLIS = 1_000L
        const val CONNECTION_TIMEOUT_MILLIS = 10_000L
    }
}
