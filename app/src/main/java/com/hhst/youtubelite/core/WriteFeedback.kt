package com.hhst.youtubelite.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Per-host progress for writes whose menu has already closed; each job removes only its own token. */
class WriteFeedback {
    private val active = MutableStateFlow<Set<Any>>(emptySet())
    val pending = active.asStateFlow()

    /** Retire the old session's progress immediately, even if its transport has not unwound. */
    fun reset() { active.value = emptySet() }

    fun launch(scope: CoroutineScope, block: suspend CoroutineScope.() -> Unit): Job {
        val token = Any()
        active.update { it + token }
        // Register cleanup before starting, including when the scope is already cancelled.
        val job = scope.launch(start = CoroutineStart.LAZY, block = block)
        job.invokeOnCompletion { active.update { it - token } }
        job.start()
        return job
    }
}
