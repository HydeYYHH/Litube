package com.hhst.youtubelite.extractor

import com.hhst.youtubelite.diagnostics.AppLog
import com.liskovsoft.youtubeapi.videoinfo.V2.HttpClient
import com.liskovsoft.youtubeapi.videoinfo.V2.HttpReply
import com.liskovsoft.youtubeapi.videoinfo.V2.HttpRequest
import com.liskovsoft.youtubeapi.videoinfo.V2.PlaybackPreference
import com.liskovsoft.youtubeapi.videoinfo.V2.PlaybackSession
import com.liskovsoft.youtubeapi.videoinfo.V2.PlayerChallenge
import com.liskovsoft.youtubeapi.videoinfo.V2.PlayerChallengeRequest
import com.liskovsoft.youtubeapi.videoinfo.V2.PlayerClient
import com.liskovsoft.youtubeapi.videoinfo.V2.PlayerLoadAttempt
import com.liskovsoft.youtubeapi.videoinfo.V2.PlayerFormat
import com.liskovsoft.youtubeapi.videoinfo.V2.PlayerScript
import com.liskovsoft.youtubeapi.videoinfo.V2.PlayerSolver
import com.liskovsoft.youtubeapi.videoinfo.V2.PlayerScriptProvider
import com.liskovsoft.youtubeapi.videoinfo.V2.PlayerToken
import com.liskovsoft.youtubeapi.videoinfo.V2.PlayerTokenProvider
import com.liskovsoft.youtubeapi.videoinfo.V2.PlayerTokenRequest
import com.liskovsoft.youtubeapi.videoinfo.V2.ReadResult
import com.liskovsoft.youtubeapi.videoinfo.V2.RequestContext
import com.liskovsoft.youtubeapi.videoinfo.V2.RequestError
import com.liskovsoft.youtubeapi.videoinfo.V2.RequestFailure
import com.liskovsoft.youtubeapi.videoinfo.V2.TokenPurpose
import com.liskovsoft.youtubeapi.videoinfo.V2.WebSession
import com.liskovsoft.youtubeapi.videoinfo.V2.loadWatch
import com.liskovsoft.youtubeapi.videoinfo.V2.transportError
import java.io.IOException

/**
 * Adapters publish the active task through a thread-local so the synchronous
 * core path stays cancellable and diagnostics stay bound.
 */
class MscPlayback(
    private val solver: SignatureSolver,
    private val tokens: PoTokenMinter,
    private val identity: () -> RequestContext = { RequestContext() },
    private val onAnonymousVisitor: (String?, Long) -> Unit = { _, _ -> },
    private val sharedWatchReads: com.hhst.youtubelite.data.media.SharedWatchReads? = null,
) {
    private class Task(var context: ExtractionContext, val videoId: String) {
        val attempts = mutableListOf<PlayerLoadAttempt>()
        fun webContext(): ExtractionContext = context.withWebConfiguration().also { context = it }
    }

    private val active = ThreadLocal<Task?>()
    private val transport = HttpClientBridge()

    val session: PlaybackSession = PlaybackSession(
        http = transport,
        solver = SolverAdapter(),
        tokens = TokenAdapter(),
        scripts = ScriptProvider(),
        // Ordinary VOD can start from direct DASH without paying for a web media token and
        // remote HLS playlists. Keep the account-bearing clients in the same fallback queue:
        // content that needs them still resolves with the original account context.
        queues = com.liskovsoft.youtubeapi.videoinfo.V2.PlayerClientQueues(PlayerClient.VISIONOS),
        onAttempt = { attempt ->
            active.get()?.let { task ->
                task.attempts += attempt
                task.context.diagnostics.event("player-response", mapClient(attempt.client).name,
                    "${attempt.status ?: "UNKNOWN"}:${attempt.error?.code ?: "PLAYABLE"}",
                    attempt.elapsedMs, attempt.error?.status ?: 0)
            }
        },
    )

    class Resolved(val metadata: Metadata, val stream: Stream)

    fun resolve(videoId: String, context: ExtractionContext, demand: PlaybackDemand?, fresh: Boolean,
                deferred: Set<StreamClient>): Resolved {
        val task = Task(context, videoId)
        withTask(task) {
            // The demand gates acceptance, never caps selection: local track
            // selection pins quality from the whole returned pool.
            val preference = PlaybackPreference(
                maxHeight = Int.MAX_VALUE,
                audioId = demand?.audioTrack?.takeIf { it.isNotBlank() },
                audioOnly = demand?.audioOnly == true,
                videoCodecs = demand?.videoCodecPrefixes ?: emptySet(),
                allowSabr = false,
            )
            val request = requestContext(context)
            fun failure(error: RequestError) = PlaybackResolutionException(error, PlaybackResolutionDiagnostics(
                if (request.signedIn) "AUTHENTICATED" else "ANONYMOUS",
                task.context.session.configurationSource, task.context.session.networkGeneration, task.attempts.takeLast(12)))
            fun load() = session.load(videoId, request, preference, fresh = fresh,
                includeMuxedFloor = context.catalog || fresh, deferred = deferred.map(::toPlayerClient).toSet(),
                cacheNamespace = playbackCacheNamespace(context))
            var loaded = load()
            context.check()
            if (!fresh && loaded.error?.kind == RequestFailure.CANCELLED && task.attempts.isEmpty()) {
                // MSC can share work across different extraction demands. A cancelled
                // speculative owner must not cancel this still-current foreground task.
                context.diagnostics.event("stream-shared-owner", "MSC", "CANCELLED_RESTART", 0, 0)
                loaded = load()
                context.check()
            }
            if (!loaded.ok) throw failure(
                loaded.error ?: RequestError(RequestFailure.UNSUPPORTED, "no_playable_source"))
            val value = loaded.value!!
            val answer = value.answer ?: throw failure(
                loaded.error ?: RequestError(RequestFailure.UNSUPPORTED, "no_playable_source"))
            if (answer.muxedFormats.none { !it.url.isNullOrBlank() } &&
                answer.adaptiveFormats.none { !it.url.isNullOrBlank() } &&
                answer.hlsUrl.isNullOrBlank() && answer.dashUrl.isNullOrBlank()
            ) {
                // Only a SABR stream remained; the local engine cannot consume it yet.
                throw failure(
                    RequestError(RequestFailure.UNSUPPORTED, "sabr_only", detail = answer.status))
            }
            // Cached loads may skip the script adapter that normally prepares this identity.
            if (value.client !in setOf(PlayerClient.VISIONOS, PlayerClient.ANDROID_VR)) task.webContext()
            val stream = mapStream(value, task.context)
            // A playable fallback (notably muxed HLS) can omit the audio catalog.
            // Catalog reads may probe one other client; startup never waits for it.
            if (context.catalog && !answer.live && stream.namedAudioTracks() < 2) {
                val alternate = if (value.client == PlayerClient.VISIONOS) PlayerClient.WEB_SAFARI else PlayerClient.VISIONOS
                val supplement = session.load(videoId, request, preference, fresh = false,
                    excluded = PlayerClient.entries.filter { it != alternate }.toSet(), includeMuxedFloor = false,
                    cacheNamespace = playbackCacheNamespace(context))
                supplement.value?.takeIf { supplement.ok && it.answer?.live == false }?.let {
                    val extra = mapStream(it, task.context)
                    stream.formats = (stream.formats + extra.formats).distinctBy { format -> format.url }
                }
            }
            return Resolved(mapMetadata(videoId, answer), stream)
        }
    }

    private fun Stream.namedAudioTracks(): Int = formats.filter { it.audioOnly }
        .mapNotNull { it.audioTrackId ?: it.audioLocale ?: it.audioTrackName }.distinct().size

    private fun playbackCacheNamespace(context: ExtractionContext): String =
        "${context.session.key}:${context.session.networkGeneration}"

    fun chapters(videoId: String, context: ExtractionContext): List<Chapter> {
        withTask(Task(context, videoId)) {
            val requestContext = requestContext(context)
            val watched = sharedWatchReads?.read(videoId, requestContext, transport, fresh = false, load = { id, ctx, http -> loadWatch(id, ctx, http = http) })
                ?: loadWatch(videoId, requestContext, http = transport)
            if (!watched.ok) return emptyList()
            return watched.value!!.chapters.map {
                Chapter(it.title.orEmpty(), (it.startMs / 1000).toInt(), it.thumbnail)
            }
        }
    }

    private inline fun <T> withTask(task: Task, block: () -> T): T {
        val previous = active.get()
        active.set(task)
        try {
            return block()
        } finally {
            active.set(previous)
        }
    }

    fun requestContext(context: ExtractionContext): RequestContext {
        context.check()
        val account = identity()
        context.check()
        val sessionSnapshot = context.session
        if (!account.signedIn && account.visitor.isNullOrBlank() && sessionSnapshot.account == WebAccount.ANONYMOUS) {
            onAnonymousVisitor(sessionSnapshot.visitorData, account.generation)
        }
        val cookies = sessionSnapshot.cookies(YoutubeSessionProvider.ORIGIN)
        val web = cookies.takeIf { sessionSnapshot.account != WebAccount.ANONYMOUS && it.isNotEmpty() }
            ?.let { WebSession(it, sessionSnapshot.accountIndex) }
        return account.copy(
            visitor = account.visitor ?: sessionSnapshot.visitorData,
            webSession = if (account.signedIn) null else web,
        )
    }

    private inner class HttpClientBridge : HttpClient {
        override fun execute(request: HttpRequest): HttpReply {
            val task = active.get() ?: throw IOException("playback_bridge_idle")
            val context = task.context
            val headers = request.headers.toMutableMap().apply {
                // WEB tokens are minted under the captured browser identity. Do not send
                // /player with a hardcoded desktop UA and then read media with another UA.
                if (get("User-Agent") == com.liskovsoft.youtubeapi.videoinfo.V2.playerUserAgent(PlayerClient.WEB)) {
                    put("User-Agent", StreamClient.WEB.userAgent(context.session))
                }
            }.mapValues { listOf(it.value) }
            val response = if (request.method == "POST")
                context.post(request.url, headers, request.body.toByteArray())
            else context.get(request.url, headers)
            return HttpReply(response.responseCode, response.responseBody,
                response.responseHeaders.mapValues { it.value.joinToString(", ") })
        }
    }

    internal inner class SolverAdapter : PlayerSolver {
        override fun solve(request: PlayerChallengeRequest): PlayerChallenge {
            val task = active.get() ?: throw IOException("playback_bridge_idle")
            val solved = solver.solve(request.script.url, request.signatures, request.throttles, task.context)
            return PlayerChallenge(solved.signatures, solved.throttles)
        }
    }

    internal inner class TokenAdapter : PlayerTokenProvider {
        override fun token(request: PlayerTokenRequest): PlayerToken? {
            val task = active.get() ?: return null
            val client = mapClient(request.client)
            if (!client.webTokens) return null
            val context = task.webContext()
            val purpose = when (request.purpose) {
                TokenPurpose.PLAYER -> PoTokenMinter.Purpose.PLAYER
                TokenPurpose.MEDIA -> PoTokenMinter.Purpose.GVS
                TokenPurpose.SUBTITLE -> PoTokenMinter.Purpose.SUBS
            }
            // Media tokens use the player request's identity; player and subtitle tokens bind to the video.
            val video = purpose != PoTokenMinter.Purpose.GVS || context.session.videoBoundGvs
            val binding = when {
                video -> PoTokenMinter.Binding.VIDEO
                context.session.account == WebAccount.ANONYMOUS -> PoTokenMinter.Binding.VISITOR
                else -> PoTokenMinter.Binding.DATA_SYNC
            }
            val identifier = when (binding) {
                PoTokenMinter.Binding.VIDEO -> task.videoId
                PoTokenMinter.Binding.VISITOR -> request.context.visitor ?: context.session.visitorData
                PoTokenMinter.Binding.DATA_SYNC -> context.session.dataSyncId
            } ?: return null
            if (identifier.isBlank()) return null
            val minted = try {
                tokens.mint(PoTokenMinter.TokenRequest(purpose, client, MediaProtocol.HTTPS,
                    binding, identifier, context.session.scope()), context)
            } catch (_: IOException) {
                AppLog.event(AppLog.Category.EXTRACTOR, "po_token_unavailable",
                    mapOf("purpose" to purpose.name, "client" to client.name))
                return null
            }
            return PlayerToken(minted.value, minted.expiresAtMillis)
        }
    }

    internal inner class ScriptProvider : PlayerScriptProvider {
        override fun cacheKey(videoId: String, context: RequestContext): String? =
            active.get()?.context?.session?.let { it.playerUrl ?: it.key.takeIf { _ -> it.nativeBootstrap } }

        override fun script(videoId: String, context: RequestContext, fresh: Boolean): ReadResult<PlayerScript> {
            val task = active.get()
                ?: return ReadResult(error = RequestError(RequestFailure.TRANSPORT, "playback_bridge_idle"))
            val web = task.webContext()
            val playerUrl = web.session.playerUrl
                ?: return ReadResult(error = RequestError(RequestFailure.TRANSPORT, "missing_player_script"))
            return try {
                val source = solver.playerScript(playerUrl, web)
                if (source.isBlank()) ReadResult(error = RequestError(RequestFailure.TRANSPORT, "empty_player_script"))
                else ReadResult(PlayerScript(playerUrl, source))
            } catch (failure: IOException) {
                ReadResult(error = transportError(failure))
            }
        }
    }

    companion object {
        fun mapClient(client: PlayerClient): StreamClient = when (client) {
            PlayerClient.VISIONOS -> StreamClient.VISIONOS
            PlayerClient.WEB -> StreamClient.WEB
            PlayerClient.WEB_EMBED -> StreamClient.WEB_EMBED
            PlayerClient.WEB_SAFARI -> StreamClient.WEB_SAFARI
            PlayerClient.TV -> StreamClient.TV
            PlayerClient.TV_TIZEN -> StreamClient.TV_TIZEN
            PlayerClient.ANDROID_VR -> StreamClient.ANDROID_VR
        }

        fun toPlayerClient(client: StreamClient): PlayerClient = when (client) {
            StreamClient.VISIONOS -> PlayerClient.VISIONOS
            StreamClient.WEB -> PlayerClient.WEB
            StreamClient.WEB_EMBED -> PlayerClient.WEB_EMBED
            StreamClient.WEB_SAFARI -> PlayerClient.WEB_SAFARI
            StreamClient.TV -> PlayerClient.TV
            StreamClient.TV_TIZEN -> PlayerClient.TV_TIZEN
            StreamClient.ANDROID_VR -> PlayerClient.ANDROID_VR
        }

        /** Audio-track semantics from the `xtags` chain parameter, falling back to track flags. */
        fun audioTrackType(format: PlayerFormat): String? {
            format.url?.let { url ->
                urlParameter("xtags", url)?.let { xtags ->
                    for (part in xtags.split(':')) {
                        val kv = part.split('=', limit = 2)
                        if (kv.size == 2 && kv[0] == "acont") return kv[1].substringBefore('-')
                    }
                }
            }
            return when {
                format.audioTrack?.isDefault == true -> "original"
                format.audioTrack?.autoDubbed == true -> "dubbed"
                else -> null
            }
        }

        internal fun urlParameter(name: String, url: String): String? =
            url.substringBefore('#').substringAfter('?', "").split('&')
                .firstOrNull { it.substringBefore('=') == name }?.substringAfter('=', "")
    }
}

/** Core resolution failure with its protocol-level error, for recovery classification. */
class PlaybackResolutionException(val error: RequestError, val diagnostics: PlaybackResolutionDiagnostics? = null) :
    IOException("${error.kind.name}:${error.code}") {
    /** Evaluated only after the normal client queue failed, including authorized fallbacks. */
    val paymentRequired: Boolean get() {
        val attempts = diagnostics?.attempts.orEmpty()
        if (error.kind != RequestFailure.UNSUPPORTED || attempts.any {
                it.status == "OK" || it.error?.kind == RequestFailure.AUTH || it.error?.status in listOf(401, 403)
            }) return false
        val paid = attempts.filter { it.restriction == com.liskovsoft.youtubeapi.videoinfo.V2.PlayerAccessRestriction.PAYMENT_REQUIRED }
        val login = attempts.filter { it.restriction == com.liskovsoft.youtubeapi.videoinfo.V2.PlayerAccessRestriction.LOGIN_REQUIRED }
        // An authenticated denial can establish payment even when an anonymous
        // client requested age/sign-in verification. A real auth failure cannot.
        return login.none { it.authenticated } &&
            (paid.any { it.authenticated } || (paid.isNotEmpty() && login.isEmpty()))
    }
}

data class PlaybackResolutionDiagnostics(val accountState: String, val configurationSource: String,
    val networkGeneration: Long, val attempts: List<PlayerLoadAttempt>)
