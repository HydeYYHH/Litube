package com.hhst.youtubelite.core

/**
 * TTL JSON key-value store.
 *
 * Miss, expiry, and decode failure return null so callers can refetch.
 */
interface JsonCache {
    fun <T> get(key: String, type: Class<T>): T?
    fun put(key: String, value: Any, ttlMs: Long)
    fun invalidate(key: String)

    /**
     * One-parse read that also reports the entry's absolute expiry (epoch ms),
     * letting layered caches inherit the remaining lifetime without a second
     * decode. Null expiry means the store cannot report one; the caller then
     * assumes a full write TTL. Default: plain [get], no expiry.
     */
    fun <T> getWithExpiry(key: String, type: Class<T>): Pair<T, Long?>? =
        get(key, type)?.let { it to null }
}
