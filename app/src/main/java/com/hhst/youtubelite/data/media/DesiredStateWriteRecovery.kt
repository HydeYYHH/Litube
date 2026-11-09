package com.hhst.youtubelite.data.media

import com.hhst.youtubelite.watch.domain.SignInRequiredException
import com.hhst.youtubelite.watch.domain.UnconfirmedActionException
import com.liskovsoft.youtubeapi.videoinfo.V2.ActionReply
import com.liskovsoft.youtubeapi.videoinfo.V2.HttpClient
import com.liskovsoft.youtubeapi.videoinfo.V2.HttpReply
import com.liskovsoft.youtubeapi.videoinfo.V2.HttpRequest
import com.liskovsoft.youtubeapi.videoinfo.V2.ReadResult
import com.liskovsoft.youtubeapi.videoinfo.V2.RequestContext
import com.liskovsoft.youtubeapi.videoinfo.V2.RequestError
import com.liskovsoft.youtubeapi.videoinfo.V2.RequestFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

internal data class DesiredStateAttempt<T>(val reply: ActionReply, val value: T)
internal data class DesiredStateRead<T>(val state: String?, val value: T)
internal enum class DesiredStateRecoveryMode { RetryStateSet, ConfirmOnly }

/** Recovery for explicit desired states; adapters own fresh capability/state interpretation. */
internal class DesiredStateWriteRecovery(private val client: MediaClient) {
    private class Target {
        val mutexes = mutableMapOf<Long, Mutex>()
        var revision = 0L
        var references = 0
    }
    private data class Owner(val key: String, val target: Target, val revision: Long, val job: Job?)
    private val ownersLock = Any()
    private val targets = mutableMapOf<String, Target>()
    private var revision = 0L

    /** [observe] returns fresh values; only [onObserved] may publish them for a later attempt. */
    suspend fun <T> setState(
        key: String,
        stateName: String,
        expected: String,
        mode: DesiredStateRecoveryMode = DesiredStateRecoveryMode.RetryStateSet,
        retryUnknownState: Boolean = true,
        checkSource: () -> Unit = {},
        write: (RequestContext, HttpClient) -> DesiredStateAttempt<T>,
        observe: (RequestContext, HttpClient) -> ReadResult<DesiredStateRead<T>>,
        onObserved: (T) -> Unit = {},
        onConfirmed: (T) -> Unit = {},
    ): T {
        val job = currentCoroutineContext()[Job]
        val owner = synchronized(ownersLock) {
            // Validate locally before an obsolete caller can retire a newer intent.
            job?.ensureActive()
            checkSource()
            val target = targets.getOrPut(key, ::Target)
            target.references++
            target.revision = ++revision
            Owner(key, target, target.revision, job)
        }
        try {
            val session = client.write { context, _ ->
                ensureOwner(owner)
                // A retired business source is cancellation, even if its replacement is signed out.
                checkSource()
                if (!context.signedIn) throw SignInRequiredException()
                context
            }
            // A new identity retires old results without waiting for its network calls.
            val mutex = synchronized(ownersLock) { owner.target.mutexes.getOrPut(session.generation) { Mutex() } }
            return mutex.withLock {
                ensureOwner(owner)
                runStateSet(owner, session, stateName, expected, mode, retryUnknownState, write, observe, onObserved, onConfirmed)
            }
        } catch (error: MediaException) {
            if (error.failure.kind == RequestFailure.CANCELLED || error.failure.code == "session_changed")
                throw CancellationException("Media action session changed or cancelled")
            throw error
        } finally {
            synchronized(ownersLock) {
                owner.target.references--
                if (owner.target.references == 0 && targets[owner.key] === owner.target) targets.remove(owner.key)
            }
        }
    }

    private suspend fun <T> runStateSet(
        owner: Owner,
        session: RequestContext,
        stateName: String,
        expected: String,
        mode: DesiredStateRecoveryMode,
        retryUnknownState: Boolean,
        write: (RequestContext, HttpClient) -> DesiredStateAttempt<T>,
        observe: (RequestContext, HttpClient) -> ReadResult<DesiredStateRead<T>>,
        onObserved: (T) -> Unit,
        onConfirmed: (T) -> Unit,
    ): T {
        var lastReply: ActionReply? = null
        var lastWriteError: RequestError? = null
        var observation: ReadResult<DesiredStateRead<T>> = ReadResult()
        var readbacks = 0
        var attempts = 0
        val maximumWrites = if (mode == DesiredStateRecoveryMode.RetryStateSet) 2 else 1
        for (attempt in 1..maximumWrites) {
            currentCoroutineContext().ensureActive()
            ensureOwner(owner)
            attempts = attempt
            val reply = try {
                inSession(owner, session, write).also { lastReply = it.reply; lastWriteError = null }
            } catch (error: MediaException) {
                if (error.failure.kind != RequestFailure.TRANSPORT || error.failure.code !in RETRYABLE_TRANSPORT_CODES) throw error
                lastWriteError = error.failure
                null
            }
            if (reply?.reply?.confirmed == true) return confirm(owner, session, reply.value, onConfirmed)
            // A network failure may follow a committed mutation: read before repeating.
            repeat(2) { read ->
                if (read > 0) delay(SETTLEMENT_DELAY_MS)
                currentCoroutineContext().ensureActive()
                ensureOwner(owner)
                observation = try {
                    withTimeoutOrNull(READBACK_TIMEOUT_MS) { inSession(owner, session, observe) }
                        ?: ReadResult(error = RequestError(RequestFailure.TRANSPORT, "transport_timeout"))
                } catch (error: MediaException) {
                    if (!recoverableReadFailure(error.failure)) throw error
                    ReadResult(error = error.failure)
                }
                readbacks++
                observation.error?.let { error ->
                    if (error.kind == RequestFailure.CANCELLED) throw CancellationException("Media action cancelled")
                    if (!recoverableReadFailure(error)) throw MediaException(error)
                }
                val observed = observation.value
                if (observation.ok && observed != null) {
                    // Publish capabilities only after this bounded read completes; a timed-out worker cannot replace them.
                    synchronized(ownersLock) { ensureOwner(owner); onObserved(observed.value) }
                    if (observed.state == expected) return confirm(owner, session, observed.value, onConfirmed)
                }
            }
            // Unknown is not unchanged; append adapters use ConfirmOnly even after an old state.
            if (observation.value?.state == null && (lastWriteError == null || !retryUnknownState)) break
        }
        currentCoroutineContext().ensureActive()
        ensureOwner(owner)
        throw UnconfirmedActionException(lastReply?.httpStatus ?: lastWriteError?.status,
            lastReply?.metadata.orEmpty() + mapOf(
                "response_shape" to lastReply?.responseShape.orEmpty(),
                "expected_$stateName" to expected,
                "observed_$stateName" to (observation.value?.state ?: "unknown"),
                "readback" to if (observation.ok) "completed" else "failed",
                "write_attempts" to attempts.toString(),
                "readback_attempts" to readbacks.toString(),
                "recovery_mode" to if (mode == DesiredStateRecoveryMode.ConfirmOnly) "confirm_only" else "state_set",
            ) + lastWriteError?.let { mapOf("write_failure" to it.code) }.orEmpty())
    }

    private suspend fun <T> inSession(owner: Owner, session: RequestContext, block: (RequestContext, HttpClient) -> T): T {
        val stageJob = currentCoroutineContext()[Job]
        fun ensureStage() {
            stageJob?.ensureActive()
            ensureOwner(owner)
        }
        return client.writeInSession(session) { context, http ->
            ensureStage()
            val guarded = object : HttpClient {
                override fun execute(request: HttpRequest): HttpReply {
                    ensureStage()
                    return http.execute(request).also { ensureStage() }
                }
            }
            try {
                block(context, guarded).also { ensureStage() }
            } catch (error: Exception) {
                ensureStage()
                throw error
            }
        }
    }

    private suspend fun <T> confirm(owner: Owner, session: RequestContext, value: T, onConfirmed: (T) -> Unit): T {
        currentCoroutineContext().ensureActive()
        client.writeInSession(session) { _, _ ->
            synchronized(ownersLock) { ensureOwner(owner); onConfirmed(value) }
        }
        ensureOwner(owner)
        return value
    }

    private fun ensureOwner(owner: Owner) = synchronized(ownersLock) {
        owner.job?.ensureActive()
        if (targets[owner.key] !== owner.target || owner.target.revision != owner.revision)
            throw CancellationException("Media action superseded")
    }

    private fun recoverableReadFailure(error: RequestError) =
        error.kind == RequestFailure.TRANSPORT || error.kind == RequestFailure.PARSE && error.code != "response_too_large"

    private companion object {
        const val SETTLEMENT_DELAY_MS = 250L
        const val READBACK_TIMEOUT_MS = 2_000L
        val RETRYABLE_TRANSPORT_CODES = setOf("transport_dns", "transport_connect", "transport_tls", "transport_timeout",
            "transport_unreachable", "transport_socket", "transport_io")
    }
}
