@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hhst.youtubelite.player.datasource

import android.content.Context
import org.schabi.newpipe.extractor.services.youtube.streams.RequestPlan
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSpec
import android.os.SystemClock
import java.io.IOException
import java.security.MessageDigest
import com.hhst.youtubelite.extractor.YoutubeMediaRequests
import com.hhst.youtubelite.extractor.Format
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import com.hhst.youtubelite.core.Constants
import java.io.File
import okhttp3.OkHttpClient
import androidx.media3.common.C
import androidx.media3.exoplayer.hls.HlsDataSourceFactory
import androidx.media3.exoplayer.hls.DefaultHlsDataSourceFactory
import com.hhst.youtubelite.extractor.hexString

/** Shared plan execution with independent Media3 byte cache and live connections. */
class PlayerDataSource private constructor(
    /** YouTube DASH factory, cached. */
    val ytDash: DataSource.Factory,
    /** YouTube progressive factory, cached. */
    val ytProgressive: DataSource.Factory,
    /** YouTube progressive factory, UNCACHED: live streams
     *  must not write into (or replay from) the VOD LRU under the same cache key. */
    val ytLiveProgressive: DataSource.Factory,
    /** Planned HTTP factory for live manifests/chunks; never cached. */
    val live: DataSource.Factory,
    private val manifestFactory: ((RequestPlan) -> DataSource.Factory)? = null,
    private val formatFactory: ((List<Format>, Boolean, Boolean) -> DataSource.Factory)? = null,
    private val hlsFactory: ((RequestPlan) -> HlsDataSourceFactory)? = null,
) {
    fun manifest(plan: RequestPlan?): DataSource.Factory =
        if (plan != null) manifestFactory?.invoke(plan) ?: live else live
    fun formats(formats: List<Format>, dash: Boolean = true, cached: Boolean = true): DataSource.Factory =
        formatFactory?.invoke(formats, dash, cached) ?: if (dash) ytDash else if (cached) ytProgressive else ytLiveProgressive
    fun vodHls(plan: RequestPlan?): HlsDataSourceFactory =
        if (plan != null) hlsFactory?.invoke(plan) ?: DefaultHlsDataSourceFactory(live)
        else DefaultHlsDataSourceFactory(live)
    companion object {
        private const val CACHE_DIR = "player"
        private const val CACHE_BYTES = 512L * 1024 * 1024
        private const val CACHE_SINK_FRAGMENT_BYTES = 2L * 1024 * 1024
        /** Progressive media read-ahead chunk size. */
        const val PROGRESSIVE_LOAD_INTERVAL_BYTES = 128 * 1024

        @Volatile
        private var cache: SimpleCache? = null

        @Synchronized
        fun getCache(context: Context): SimpleCache {
            return cache ?: SimpleCache(
                File(context.noBackupFilesDir, CACHE_DIR),
                LeastRecentlyUsedCacheEvictor(CACHE_BYTES),
                StandaloneDatabaseProvider(context),
            ).also { cache = it }
        }

        /** Builds the cache off the main thread before playback needs it. */
        fun precache(context: Context) {
            getCache(context)
        }

        fun create(context: Context, http: OkHttpClient, plans: YoutubeMediaRequests? = null): PlayerDataSource {
            val ytDashUpstream = youtubeFactory(http, range = true, rn = true).setMediaRequests(plans)
            val ytProgressiveUpstream = youtubeFactory(http, range = false, rn = true).setMediaRequests(plans)
            val liveUpstream = youtubeFactory(http, range = false, rn = false).setMediaRequests(plans)
                .setConnectTimeoutMs(30_000)
                .setReadTimeoutMs(45_000)

            val ytDash = maybeCache(context, ytDashUpstream)
            val ytProgressive = maybeCache(context, ytProgressiveUpstream)
            val manifests = VodManifestCache()
            return PlayerDataSource(
                ytDash = ytDash,
                ytProgressive = ytProgressive,
                ytLiveProgressive = youtubeFactory(http, range = false, rn = true).setMediaRequests(plans),
                live = liveUpstream,
                manifestFactory = { plan -> youtubeFactory(http, range = false, rn = false).setMediaRequests(plans, plan) },
                formatFactory = { formats, dash, cached ->
                    val byUrl = formats.associateBy { it.url }
                    val upstream = youtubeFactory(http, range = dash, rn = false).setMediaRequests(plans)
                        .setPlanResolver { url -> byUrl[url]?.requestPlan }
                    if (!cached) upstream else maybeCache(context, upstream, CacheKeyFactory { spec ->
                        byUrl[spec.uri.toString()]?.let(YoutubePlaybackCacheKey::of)
                            ?: VIDEO_PLAYBACK_CACHE_KEY.buildCacheKey(spec)
                    })
                },
                hlsFactory = { plan ->
                    val upstream = youtubeFactory(http, range = false, rn = false).setMediaRequests(plans, plan)
                    val current = { plans?.isCurrent(plan) != false }
                    val playlist = manifests.factory(plan.session.key, plan.expiresAtMillis, current, upstream)
                    // Exact signed URL and session identity prove only this segment.
                    // Hash cache keys so media credentials never enter the disk index.
                    val media = maybeCache(context, upstream, CacheKeyFactory { spec ->
                        "hls:v1:" + MessageDigest.getInstance("SHA-256")
                            .digest("${plan.session.key}:${spec.uri}".toByteArray()).hexString()
                    })
                    HlsDataSourceFactory { type ->
                        if (type == C.DATA_TYPE_MANIFEST) playlist.createDataSource()
                        else if (type == C.DATA_TYPE_MEDIA || type == C.DATA_TYPE_MEDIA_INITIALIZATION) {
                            SessionCheckedDataSource(media.createDataSource(), plan, current)
                        } else upstream.createDataSource()
                    }
                },
            )
        }

        private fun youtubeFactory(
            http: OkHttpClient,
            range: Boolean,
            rn: Boolean,
        ): YoutubeHttpDataSource.Factory =
            YoutubeHttpDataSource.Factory(http, Constants.userAgent())
                .setConnectTimeoutMs(30_000)
                .setReadTimeoutMs(30_000)
                .setRangeParameterEnabled(range)
                .setRnParameterEnabled(rn)

        private fun maybeCache(context: Context, upstream: DataSource.Factory, keys: CacheKeyFactory = VIDEO_PLAYBACK_CACHE_KEY): DataSource.Factory =
            CacheDataSource.Factory()
                .setCache(getCache(context))
                .setUpstreamDataSourceFactory(upstream)
                .setCacheWriteDataSinkFactory(
                    CacheDataSink.Factory()
                        .setCache(getCache(context))
                        .setFragmentSize(CACHE_SINK_FRAGMENT_BYTES),
                )
                .setCacheReadDataSourceFactory(FileDataSource.Factory())
                .setCacheKeyFactory(keys)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        /**
         * googlevideo rotates `expire`/`sig`/`pot`/`rn` on every resolve.
         * Key on stream identity (id + itag + audio xtags + content length)
         * so a signature refresh reuses cached bytes, while a different
         * language or `clen` cannot replay the previous object. The version
         * prefix isolates pre-v2 `yt:id:itag` entries.
         */
        private val VIDEO_PLAYBACK_CACHE_KEY = CacheKeyFactory { spec ->
            val uri = spec.uri
            if (!uri.path.orEmpty().contains("videoplayback")) {
                spec.key ?: uri.toString()
            } else {
                YoutubePlaybackCacheKey.ofQuery(uri.encodedQuery.orEmpty())
                    ?: run {
                        val b = uri.buildUpon().clearQuery()
                        uri.queryParameterNames.filter { it != "rn" }.sorted().forEach { name ->
                            uri.getQueryParameters(name).forEach { value ->
                                b.appendQueryParameter(name, value)
                            }
                        }
                        b.build().toString()
                    }
            }
        }
    }
}

/** Cached bytes still obey the session fence, including during an open read. */
private class SessionCheckedDataSource(
    private val inner: DataSource,
    private val plan: RequestPlan,
    private val current: () -> Boolean,
) : DataSource by inner {
    private var nextCheck = 0L
    override fun open(dataSpec: DataSpec): Long {
        if (plan.expiresAtMillis <= System.currentTimeMillis()) throw IOException("MEDIA_URL_EXPIRED")
        nextCheck = 0L
        checkSession()
        return inner.open(dataSpec)
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        checkSession()
        return inner.read(buffer, offset, length)
    }
    private fun checkSession() {
        val now = SystemClock.elapsedRealtime()
        if (now >= nextCheck) {
            if (!current()) throw IOException("MEDIA_SESSION_CHANGED")
            nextCheck = now + 100
        }
    }
}
