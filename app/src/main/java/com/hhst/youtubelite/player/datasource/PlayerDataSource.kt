@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hhst.youtubelite.player.datasource

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import com.hhst.youtubelite.core.Constants
import java.io.File
import okhttp3.OkHttpClient

/**
 * DataSource factory menu for the player.
 *
 * - YouTube VOD streams go through [YoutubeHttpDataSource] (POST pulse, rn/range
 *   params, cookies) on the app's shared [OkHttpClient] — connection pooling and
 *   HTTP/2 multiplexing speed up chunked loads and seeks — wrapped in a 512 MB
 *   LRU [SimpleCache] with 2 MB write chunks.
 * - Live streams bypass the cache and use plain HTTP (30 s/45 s timeouts).
 */
class PlayerDataSource private constructor(
    /** Cookie/POST-aware YouTube DASH factory (range+rn params), cached. */
    val ytDash: DataSource.Factory,
    /** Cookie/POST-aware YouTube progressive factory (rn param), cached. */
    val ytProgressive: DataSource.Factory,
    /** Cookie/POST-aware YouTube progressive factory, UNCACHED: live streams
     *  must not write into (or replay from) the VOD LRU under the same cache key. */
    val ytLiveProgressive: DataSource.Factory,
    /** Plain HTTP factory for live manifests/chunks; never cached. */
    val live: DataSource.Factory,
) {
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

        fun create(context: Context, http: OkHttpClient): PlayerDataSource {
            val ytDashUpstream = youtubeFactory(http, range = true, rn = true)
            val ytProgressiveUpstream = youtubeFactory(http, range = false, rn = true)
            val liveUpstream = DefaultHttpDataSource.Factory()
                .setUserAgent(Constants.userAgent())
                .setConnectTimeoutMs(30_000)
                .setReadTimeoutMs(45_000)

            val ytDash = maybeCache(context, ytDashUpstream)
            val ytProgressive = maybeCache(context, ytProgressiveUpstream)
            return PlayerDataSource(
                ytDash = ytDash,
                ytProgressive = ytProgressive,
                ytLiveProgressive = youtubeFactory(http, range = false, rn = true),
                live = liveUpstream,
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

        private fun maybeCache(context: Context, upstream: DataSource.Factory): DataSource.Factory =
            CacheDataSource.Factory()
                .setCache(getCache(context))
                .setUpstreamDataSourceFactory(upstream)
                .setCacheWriteDataSinkFactory(
                    CacheDataSink.Factory()
                        .setCache(getCache(context))
                        .setFragmentSize(CACHE_SINK_FRAGMENT_BYTES),
                )
                .setCacheReadDataSourceFactory(FileDataSource.Factory())
                .setCacheKeyFactory(VIDEO_PLAYBACK_CACHE_KEY)
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
