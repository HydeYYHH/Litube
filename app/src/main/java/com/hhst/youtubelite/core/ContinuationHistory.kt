package com.hhst.youtubelite.core

/** Successful page cursors in one browse cycle. Failed reads remain retryable. */
internal class ContinuationHistory {
    private val consumed = mutableSetOf<String>()

    fun clear() = consumed.clear()

    /** Keep the returned page's rows, but stop before requesting a consumed cursor again. */
    fun nextAfter(requested: String, returned: String?): String? {
        consumed += requested
        return returned?.takeUnless(consumed::contains)
    }
}
