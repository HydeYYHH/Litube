package com.hhst.youtubelite.data.media

import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.core.DeviceEvidence
import com.hhst.youtubelite.library.domain.HistoryPage
import com.hhst.youtubelite.library.domain.PlaylistCollectionPage
import com.hhst.youtubelite.library.domain.YouPage
import com.hhst.youtubelite.subscriptions.data.SubscriptionsFilter
import com.hhst.youtubelite.subscriptions.domain.ChannelDirectoryPage
import com.hhst.youtubelite.subscriptions.domain.SubscriptionsPage
import com.liskovsoft.youtubeapi.videoinfo.V2.RequestError
import com.liskovsoft.youtubeapi.videoinfo.V2.RequestFailure
import com.liskovsoft.youtubeapi.videoinfo.V2.ledgerScope
import com.liskovsoft.youtubeapi.videoinfo.V2.loadNotices
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.koin.core.context.GlobalContext

/** Opt-in account reads only: -e network 1 -e mscLiveAccountReads 1. */
class MscLiveAccountReadsAndroidTest {
    private lateinit var probe: MscLiveNetworkProbe
    private val report = JSONObject()
    private val operations = JSONArray()
    private val covered = linkedSetOf<String>()

    @Test fun measureSignedInLibraryNoticeAndSubscriptions() = runBlocking(Dispatchers.IO) {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("network") == "1" && args.getString("mscLiveAccountReads") == "1")
        val initial = MediaSession.snapshot()
        val initialScope = initial.ledgerScope()
        val rounds = (args.getString("liveAccountRounds")?.toIntOrNull() ?: 2).coerceIn(1, 3)
        val proxyHost = args.getString("liveProxyHost")?.takeIf(String::isNotBlank)
        val proxyPort = args.getString("liveProxyPort")?.toIntOrNull() ?: 7890
        report.put("experiment", "signed_in_account_reads").put("readOnly", true).put("rounds", rounds)
            .put("loginMissing", !initial.signedIn).put("profileChanged", false)
            .put("profile", if (initial.accessToken != null) "oauth" else if (initial.signedIn) "unsupported_web" else "anonymous")
        probe = MscLiveNetworkProbe(GlobalContext.get().get<OkHttpClient>(), proxyHost, proxyPort, allowSignedIn = true) {
            MediaSession.freshContext().also { current ->
                if (!current.signedIn || current.accessToken.isNullOrBlank()) {
                    synchronized(report) { report.put("loginLost", true) }
                    throw MediaException(RequestError(RequestFailure.AUTH, "live_account_login_missing"))
                }
                if (current.ledgerScope() != initialScope) {
                    synchronized(report) { report.put("profileChanged", true) }
                    throw MediaException(RequestError(RequestFailure.AUTH, "live_account_profile_changed"))
                }
            }
        }
        if (!initial.signedIn || initial.accessToken.isNullOrBlank()) {
            report.put("outcome", if (!initial.signedIn) "loginMissing" else "profileUnsupported")
            save()
            assumeTrue("Signed-in OAuth session required; see msc-live-account-reads.json", false)
            return@runBlocking
        }
        try {
            repeat(rounds) { round ->
                val prefix = "round_${round + 1}"
                val library = MediaLibraryRepository(probe.client)
                observe("${prefix}_library_you", { library.you() }, ::describeYou)?.let { covered += "you" }
                val history = observe("${prefix}_history_first", { library.history() }, ::describeHistory)
                if (history != null) {
                    covered += "history"
                    history.nextKey?.let { cursor -> observe("${prefix}_history_next", { library.historyNext(cursor) }, ::describeHistory) }
                }
                val playlists = observe("${prefix}_playlists_first", { library.playlists() }, ::describePlaylists)
                if (playlists != null) {
                    covered += "playlists"
                    playlists.nextKey?.let { cursor -> observe("${prefix}_playlists_next", { library.playlistsNext(cursor) }, ::describePlaylists) }
                }
                var continuationFeed: SubscriptionsPage? = null
                var continuationDirectory: ChannelDirectoryPage? = null
                // Independent repositories cannot share an initial browse; the paired lane
                // matches the production feed/directory startup on one repository.
                val variants = if (round % 2 == 0) listOf(false, true) else listOf(true, false)
                for (shared in variants) {
                    val value = observe("${prefix}_subscriptions_${if (shared) "paired" else "independent"}", {
                        val feedRepository = MediaSubscriptionsRepository(probe.client)
                        val directoryRepository = if (shared) feedRepository else MediaSubscriptionsRepository(probe.client)
                        subscriptionPair(feedRepository, directoryRepository)
                    }, ::describePair)
                    if (value != null) {
                        if (value.feed.isSuccess) covered += "subscriptions_feed"
                        if (value.directory.isSuccess) covered += "subscriptions_directory"
                        value.feed.getOrNull()?.let { if (continuationFeed == null || shared) continuationFeed = it }
                        value.directory.getOrNull()?.let { if (continuationDirectory == null || shared) continuationDirectory = it }
                    }
                }
                val continuationRepository = MediaSubscriptionsRepository(probe.client)
                continuationFeed?.nextKey?.let { cursor ->
                    observe("${prefix}_subscriptions_feed_next", {
                        continuationRepository.subscriptions(SubscriptionsFilter.All, cursor)
                    }) { page -> JSONObject().put("items", page.items.size).put("next", page.nextKey != null).put("contentOutcome", content(page.items.size)) }
                }
                continuationDirectory?.nextKey?.let { cursor ->
                    observe("${prefix}_subscriptions_directory_next", { continuationRepository.subscribedChannelsPage(cursor) }) { page ->
                        JSONObject().put("channels", page.channels.size).put("next", page.nextKey != null).put("contentOutcome", content(page.channels.size))
                    }
                }
            }
            // Notifications have no app entry point; this core-capability diagnostic is
            // retained for upstream compatibility investigations.
            val notice = observe("notice_core_cursor_capability", {
                probe.client.read { context, http -> loadNotices(context, http = http).requireValue() }
            }) { page -> JSONObject().put("items", page.notices.size).put("next", page.nextKey != null)
                .put("purpose", "core_capability_not_app_path") }
            notice?.nextKey?.let { cursor ->
                observe("notice_core_next", {
                    probe.client.read { context, http -> loadNotices(context, cursor, http).requireValue() }
                }) { page -> JSONObject().put("items", page.notices.size).put("next", page.nextKey != null)
                    .put("contentOutcome", content(page.notices.size)) }
            }
            report.put("outcome", when {
                report.optBoolean("profileChanged") -> "profileChanged"
                covered.isEmpty() -> "account_reads_unavailable"
                covered.size < 5 -> "partial_account_coverage"
                else -> "completed"
            })
            save()
            assumeTrue("No successful account reads; inspect msc-live-account-reads.json", covered.isNotEmpty())
        } catch (cancelled: CancellationException) {
            report.put("outcome", "cancelled")
            throw cancelled
        } finally {
            val finalContext = MediaSession.snapshot()
            if (finalContext.ledgerScope() != initialScope) report.put("profileChanged", true).put("outcome", "profileChanged")
            if (!finalContext.signedIn) report.put("loginLost", true)
            save()
        }
    }

    private data class SubscriptionPair(val feed: Result<SubscriptionsPage>, val directory: Result<ChannelDirectoryPage>)
    private suspend fun subscriptionPair(feedRepository: MediaSubscriptionsRepository, directoryRepository: MediaSubscriptionsRepository): SubscriptionPair = coroutineScope {
        val feed = async { capture { feedRepository.subscriptions(SubscriptionsFilter.All, null) } }
        val directory = async { capture { directoryRepository.subscribedChannelsPage(null) } }
        SubscriptionPair(feed.await(), directory.await())
    }

    private suspend fun <T> capture(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: Exception) { Result.failure(failure) }

    private fun describePair(pair: SubscriptionPair) = JSONObject()
        .put("feed", pair.feed.fold({ page -> JSONObject().put("outcome", "ok").put("items", page.items.size)
            .put("next", page.nextKey != null).put("contentOutcome", content(page.items.size)) }, ::failure))
        .put("directory", pair.directory.fold({ page -> JSONObject().put("outcome", "ok").put("channels", page.channels.size)
            .put("next", page.nextKey != null).put("contentOutcome", content(page.channels.size)) }, ::failure))
        .put("successfulLogicalReads", listOf(pair.feed.isSuccess, pair.directory.isSuccess).count { it })
    private fun describeYou(page: YouPage) = JSONObject().put("profileReturned", true)
        .put("historyItems", page.history.size).put("playlistItems", page.playlists.size)
        .put("historyFailed", page.historyFailed).put("playlistsFailed", page.playlistsFailed)
    private fun describeHistory(page: HistoryPage) = JSONObject().put("items", page.entries.size)
        .put("next", page.nextKey != null).put("contentOutcome", content(page.entries.size))
    private fun describePlaylists(page: PlaylistCollectionPage) = JSONObject().put("items", page.playlists.size)
        .put("next", page.nextKey != null).put("contentOutcome", content(page.playlists.size))
    private fun content(size: Int) = if (size == 0) "empty" else "items"

    private suspend fun <T> observe(label: String, block: suspend () -> T, describe: (T) -> JSONObject): T? = try {
        val value = probe.sample(label, block)
        operations.put(describe(value).put("label", label).put("outcome", "ok"))
        value
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (error: Exception) {
        operations.put(failure(error).put("label", label))
        null
    } finally {
        save()
    }

    private fun failure(error: Throwable) = JSONObject().put("outcome", "failed").put("failureType", error.javaClass.simpleName).apply {
        (error as? MediaException)?.failure?.let { typed ->
            put("kind", typed.kind.name)
                .put("code", typed.code.takeIf { it.matches(Regex("[a-z][a-z0-9_]{0,79}")) } ?: "redacted")
                .put("status", typed.status ?: JSONObject.NULL)
        }
    }

    private fun save() {
        synchronized(report) {
            report.put("coveredFamilies", JSONArray(covered.toList())).put("operations", operations).put("network", probe.report())
            DeviceEvidence.writeJson("msc-live-account-reads.json", report.toString(2))
        }
    }
}
