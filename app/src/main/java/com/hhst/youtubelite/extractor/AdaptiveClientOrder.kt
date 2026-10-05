package com.hhst.youtubelite.extractor

import org.schabi.newpipe.extractor.services.youtube.streams.ClientOrderPolicy
import org.schabi.newpipe.extractor.services.youtube.streams.ClientProfile
import org.schabi.newpipe.extractor.services.youtube.streams.ExtractionContext

/** Retain useful clients briefly; a network error never blacklists a profile. */
class AdaptiveClientOrder(private val clock: () -> Long = System::currentTimeMillis) : ClientOrderPolicy {
    private data class Preferred(val profile: ClientProfile, val expires: Long)
    private val preferred = LinkedHashMap<String, Preferred>(16, .75f, true)

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
