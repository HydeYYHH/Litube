package com.hhst.youtubelite.extractor

import com.hhst.youtubelite.diagnostics.AppLog
import com.liskovsoft.youtubeapi.videoinfo.V2.HttpClient
import com.liskovsoft.youtubeapi.videoinfo.V2.HttpReply
import com.liskovsoft.youtubeapi.videoinfo.V2.HttpRequest
import com.liskovsoft.youtubeapi.videoinfo.V2.PlaybackLoad
import com.liskovsoft.youtubeapi.videoinfo.V2.PlaybackPreference
import com.liskovsoft.youtubeapi.videoinfo.V2.PlaybackSession
import com.liskovsoft.youtubeapi.videoinfo.V2.PlayerChallenge
import com.liskovsoft.youtubeapi.videoinfo.V2.PlayerChallengeRequest
import com.liskovsoft.youtubeapi.videoinfo.V2.PlayerClient
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
import java.io.IOException

/**
 * Bridges the app's challenge and poToken infrastructure to the core playback
 * session. Adapters publish the active task through a thread-local so the
 * synchronous core path stays cancellable and diagnostics stay bound.
 */
class MscPlayback(
    private val solver: SignatureSolver,
    private val tokens: PoTokenMinter,
    private val identity: () -> RequestContext = { RequestContext() },
    private val onAnonymousVisitor: (String?, Long) -> Unit = { _, _ -> },
) {
    private class Task(val context: ExtractionContext, val videoId: String)

    private val active = ThreadLocal<Task?>()
    private val transport = HttpClientBridge()

    val session: PlaybackSession = PlaybackSession(
        http = transport,
        solver = SolverAdapter(),
        tokens = TokenAdapter(),
        scripts = ScriptProvider(),
    )

    /** One mapped result of a core playback load. */
    class Resolved(val metadata: Metadata, val stream: Stream)

    fun resolve(videoId: String, context: ExtractionContext, demand: PlaybackDemand?, fresh: Boolean,
                deferred: Set<StreamClient>): Resolved {
        withTask(Task(context, videoId)) {
            // The demand gates acceptance, never caps selection: local track
            // selection pins quality from the whole returned pool.
            val preference = PlaybackPreference(
                maxHeight = Int.MAX_VALUE,
                audioId = demand?.audioTrack?.takeIf { it.isNotBlank() },
                audioOnly = demand?.audioOnly == true,
                videoCodecs = demand?.videoCodecPrefixes ?: emptySet(),
            )
            val request = requestContext(context)
            val loaded = load(videoId, request, preference, fresh, deferred)
            if (!loaded.ok) throw PlaybackResolutionException(
                loaded.error ?: RequestError(RequestFailure.UNSUPPORTED, "no_playable_source"))
            val value = loaded.value!!
            val answer = value.answer ?: throw PlaybackResolutionException(
                loaded.error ?: RequestError(RequestFailure.UNSUPPORTED, "no_playable_source"))
            if (answer.muxedFormats.none { !it.url.isNullOrBlank() } &&
                answer.adaptiveFormats.none { !it.url.isNullOrBlank() } &&
                answer.hlsUrl.isNullOrBlank() && answer.dashUrl.isNullOrBlank()
            ) {
                // Only a SABR stream remained; the local engine cannot consume it yet.
                throw PlaybackResolutionException(
                    RequestError(RequestFailure.UNSUPPORTED, "sabr_only", detail = answer.status))
            }
            return Resolved(mapMetadata(videoId, answer), mapStream(value, context))
        }
    }

    fun chapters(videoId: String, context: ExtractionContext): List<Chapter> {
        withTask(Task(context, videoId)) {
            val watched = loadWatch(videoId, requestContext(context), http = transport)
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

    private fun load(videoId: String, context: RequestContext, preference: PlaybackPreference,
                     fresh: Boolean, deferred: Set<StreamClient>): com.liskovsoft.youtubeapi.videoinfo.V2.ReadResult<PlaybackLoad> {
        val excluded = deferred.mapNotNull(::toPlayerClient).toSet()
        var loaded = session.load(videoId, context, preference, fresh = fresh, excluded = excluded)
        if (!loaded.ok && excluded.isNotEmpty()) {
            // Deferred clients stay eligible as the terminal pool, never dropped outright.
            loaded = session.load(videoId, context, preference, fresh = fresh, excluded = emptySet())
        }
        return loaded
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

    // -- transport --

    /** Translates core requests onto the task's bounded, browser-aware transport. */
    private inner class HttpClientBridge : HttpClient {
        override fun execute(request: HttpRequest): HttpReply {
            val task = active.get() ?: throw IOException("playback_bridge_idle")
            val context = task.context
            val headers = request.headers.mapValues { listOf(it.value) }
            val response = if (request.method == "POST")
                context.post(request.url, headers, request.body.toByteArray())
            else context.get(request.url, headers)
            return HttpReply(response.responseCode, response.responseBody,
                response.responseHeaders.mapValues { it.value.joinToString(", ") })
        }
    }

    // -- adapters --

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
            val context = task.context
            val client = mapClient(request.client)
            if (!client.webTokens) return null
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
        override fun script(videoId: String, context: RequestContext, fresh: Boolean): ReadResult<PlayerScript> {
            val task = active.get()
                ?: return ReadResult(error = RequestError(RequestFailure.TRANSPORT, "playback_bridge_idle"))
            val playerUrl = task.context.session.playerUrl
                ?: return ReadResult(error = RequestError(RequestFailure.TRANSPORT, "missing_player_script"))
            return try {
                val source = solver.playerScript(playerUrl, task.context)
                if (source.isBlank()) ReadResult(error = RequestError(RequestFailure.TRANSPORT, "empty_player_script"))
                else ReadResult(PlayerScript(playerUrl, source))
            } catch (_: IOException) {
                ReadResult(error = RequestError(RequestFailure.TRANSPORT, "player_script_failed"))
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
            PlayerClient.ANDROID_VR -> StreamClient.ANDROID_VR
        }

        fun toPlayerClient(client: StreamClient): PlayerClient = when (client) {
            StreamClient.VISIONOS -> PlayerClient.VISIONOS
            StreamClient.WEB -> PlayerClient.WEB
            StreamClient.WEB_EMBED -> PlayerClient.WEB_EMBED
            StreamClient.WEB_SAFARI -> PlayerClient.WEB_SAFARI
            StreamClient.TV -> PlayerClient.TV
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
class PlaybackResolutionException(val error: RequestError) :
    IOException("${error.kind.name}:${error.code}")
