package com.hhst.youtubelite.extractor

import org.schabi.newpipe.extractor.services.youtube.streams.ClientOrderPolicy
import org.schabi.newpipe.extractor.services.youtube.streams.ClientProfile
import org.schabi.newpipe.extractor.services.youtube.streams.ExtractionContext

/** Retain useful clients briefly; a network error never blacklists a profile. */
class AdaptiveClientOrder(private val clock: () -> Long = System::currentTimeMillis) : ClientOrderPolicy {
    private data class Preferred(val profile: ClientProfile, val expires: Long)
    private val preferred = LinkedHashMap<String, Preferred>(16, .75f, true)
    private data class Refused(val videoId: String, val identity: String, val profile: ClientProfile)
    private val refused = LinkedHashMap<Refused, Long>()

    /** A real media 403 changes order for this video only; every eligible fallback remains. */
    @Synchronized
    fun mediaForbidden(videoId: String, identity: String, profile: ClientProfile) {
        refused.entries.removeAll { it.value <= clock() }
        refused[Refused(videoId, identity, profile)] = clock() + 30_000
        while (refused.size > 64) refused.remove(refused.keys.first())
    }

    /** Identity excludes extraction generations, so a deliberate refresh keeps its own evidence. */
    fun forVideo(videoId: String, identity: String): ClientOrderPolicy = object : ClientOrderPolicy {
        override fun order(context: ExtractionContext, defaults: List<ClientProfile>): List<ClientProfile> =
            synchronized(this@AdaptiveClientOrder) {
                refused.entries.removeAll { it.value <= clock() }
                val ordered = this@AdaptiveClientOrder.order(context, defaults)
                val (deferred, available) = ordered.partition { Refused(videoId, identity, it) in refused }
                available + deferred
            }

        override fun succeeded(context: ExtractionContext, profile: ClientProfile) =
            this@AdaptiveClientOrder.succeeded(context, profile)
    }

    private fun scope(context: ExtractionContext) =
        "${context.session.scope()}:${context.catalog}:${context.live}:${context.playbackPriority}:${context.demand.cacheKey()}"

    @Synchronized
    override fun order(context: ExtractionContext, defaults: List<ClientProfile>): List<ClientProfile> {
        preferred.entries.removeAll { it.value.expires <= clock() }
        val profile = preferred[scope(context)]?.profile ?: return defaults
        return if (profile in defaults) listOf(profile) + defaults.filter { it != profile } else defaults
    }

    @Synchronized
    override fun succeeded(context: ExtractionContext, profile: ClientProfile) {
        context.check()
        preferred[scope(context)] = Preferred(profile, clock() + 10 * 60_000L)
        while (preferred.size > 32) preferred.remove(preferred.keys.first())
    }
}
