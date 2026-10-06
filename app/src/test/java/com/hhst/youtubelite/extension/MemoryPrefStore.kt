package com.hhst.youtubelite.extension

/** In-memory [PrefStore] for tests. */
class MemoryPrefStore : PrefStore {
    private val bools = mutableMapOf<String, Boolean>()
    private val longs = mutableMapOf<String, Long>()

    override fun contains(key: String): Boolean =
        bools.containsKey(key) || longs.containsKey(key)

    override fun getBool(key: String, default: Boolean): Boolean =
        bools[key] ?: default

    override fun putBool(key: String, value: Boolean) {
        bools[key] = value
        longs.remove(key)
    }

    override fun getLong(key: String, default: Long): Long =
        longs[key] ?: default

    override fun putLong(key: String, value: Long) {
        longs[key] = value
        bools.remove(key)
    }
}
