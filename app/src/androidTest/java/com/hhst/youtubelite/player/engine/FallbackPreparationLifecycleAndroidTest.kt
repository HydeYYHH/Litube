@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.hhst.youtubelite.player.engine

import android.os.SystemClock
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.core.TestJson
import com.hhst.youtubelite.extractor.*
import com.hhst.youtubelite.extractor.Cache
import com.hhst.youtubelite.extension.ExtensionManager
import com.hhst.youtubelite.extension.PreferenceKeys
import com.hhst.youtubelite.player.datasource.*
import com.hhst.youtubelite.player.sponsor.SponsorBlockManager
import java.io.EOFException
import java.io.File
import java.io.InterruptedIOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.*
import okhttp3.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.koin.core.context.GlobalContext

/** Audio is still opening after an actual video frame; fallback must remain usable. */
class FallbackPreparationLifecycleAndroidTest {
    @get:Rule val activity=ActivityScenarioRule(ExtractionTestActivity::class.java)

    @Test fun keepFallbackUntilAudioVideoPlayback()=runBlocking(Dispatchers.IO) {
        val inst=InstrumentationRegistry.getInstrumentation()
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("network")=="1")
        val app=inst.targetContext
        val id="bQwjr3kb_Ec"
        val extraction=GlobalContext.get().get<Extractor>().extract(id,demand=PlaybackDemand(854,null,false,setOf("avc")))
        val original=extraction.stream.await()
        val metadata=extraction.metadata.await()
        val video=requireNotNull(original.formats.filter { it.videoOnly && it.height==854 && it.codec.orEmpty().startsWith("avc") && it.hasDashRanges }.maxByOrNull { it.bitrate })
        val audio=requireNotNull(StreamSelection.preferPlayable(StreamSelection.audioCandidates(original.formats.filter { it.audioOnly && it.hasDashRanges })).maxByOrNull { it.bitrate })
        val input=Stream().also { it.copyFrom(original);it.formats=listOf(video,audio);it.dashUrl=null }
        val master=requireNotNull(input.hlsUrl)
        val modes=args.getString("modes","candidate").split(',')
        val output=File(app.filesDir,"fallback-preparation-lifecycle.json")
        val rows=mutableListOf<Map<String,Any?>>()
        val prefs=GlobalContext.get().get<ExtensionManager>()
        val oldRemember=prefs.isEnabled(PreferenceKeys.REMEMBER_QUALITY)
        prefs.setEnabled(PreferenceKeys.REMEMBER_QUALITY,true)
        try { for(mode in modes) {
            val cache=PlayerDataSource.getCache(app)
            cache.keys.toList().forEach(cache::removeResource)
            val origin=SystemClock.elapsedRealtime()
            val events=Collections.synchronizedList(mutableListOf<Map<String,Any?>>())
            fun record(event:String,extra:Map<String,Any?> = emptyMap()) { events+=mapOf("ms" to SystemClock.elapsedRealtime()-origin,"event" to event)+extra }
            val firstFrame=AtomicLong()
            val frameLatch=CountDownLatch(1)
            val masterCalls=AtomicInteger()
            val masterHeaders=AtomicInteger()
            val jobCancelledAtFrame=AtomicReference<Boolean>()
            val jobPresentAtFrame=AtomicReference<Boolean>()
            val audioFailures=AtomicInteger()
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
            val http=OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
                .connectTimeout(6,TimeUnit.SECONDS).readTimeout(6,TimeUnit.SECONDS)
                .addInterceptor { chain ->
                    val request=chain.request()
                    if(request.url.toString()==master) { masterCalls.incrementAndGet();record("master_attempt") }
                    if(request.url.encodedPath=="/videoplayback" && request.url.queryParameter("itag")==audio.itag.toString()) {
                        Thread.sleep(3_500)
                        audioFailures.incrementAndGet();record("controlled_audio_eof")
                        throw SSLHandshakeException("controlled audio handshake EOF").apply { initCause(EOFException()) }
                    }
                    // Equal delay on both arms keeps the slow-open callback before video output.
                    if(request.url.encodedPath=="/videoplayback" && request.url.queryParameter("itag")==video.itag.toString() && request.url.queryParameter("range")?.startsWith("0-")==true) Thread.sleep(250)
                    chain.proceed(request)
                }.addNetworkInterceptor { chain ->
                    if(chain.request().url.toString()==master && masterCalls.get()==1) {
                        record("master_connection_ready")
                        try { if(!frameLatch.await(1_500,TimeUnit.MILLISECONDS)) throw InterruptedIOException("FRAME_GATE_TIMEOUT") }
                        catch(cancelled:InterruptedException) { Thread.currentThread().interrupt();throw InterruptedIOException("FRAME_GATE_CANCELLED") }
                    }
                    chain.proceed(chain.request()).also {
                        if(chain.request().url.toString()==master) { masterHeaders.incrementAndGet();record("master_headers",mapOf("status" to it.code)) }
                    }
                }.build()
            val data=PlayerDataSource.create(app,http,YoutubeMediaRequests({true},ExtractionDiagnostics()))
            val resolver=MediaSourceResolver(data,{1_500_000L},PlaybackStartup.videoSupport(app))
            val state=MemoryJsonCache().apply { put("player:quality","854p",60_000) }
            val fixed=object:Cache {
                override fun getStream(videoId:String)=input
                override fun getMetadata(videoId:String)=metadata
                override fun getChapters(videoId:String)=ChapterList()
                override fun putStream(videoId:String,stream:Stream){}
                override fun putMetadata(videoId:String,metadata:Metadata){}
                override fun putChapters(videoId:String,chapters:ChapterList){}
                override fun invalidateStream(videoId:String){}
            }
            lateinit var engine:PlaybackEngine
            lateinit var player:ExoPlayer
            var created=false
            var playedAt:Long?=null
            var failure:String?=null
            val jobField=PlaybackEngine::class.java.getDeclaredField("fallbackPreparation").apply { isAccessible=true }
            try {
                activity.scenario.onActivity { screen ->
                    engine=PlaybackEngine(app,Extractor(fixed,scope),resolver,state,prefs,SponsorBlockManager(http,prefs,scope))
                    player=engine.javaClass.getDeclaredField("player").apply { isAccessible=true }.get(engine) as ExoPlayer
                    created=true;player.volume=0f
                    player.addListener(object:Player.Listener {
                        override fun onRenderedFirstFrame() {
                            if(!firstFrame.compareAndSet(0,SystemClock.elapsedRealtime())) return
                            val job=jobField.get(engine) as? Job
                            if(mode=="baseline") job?.cancel()
                            jobPresentAtFrame.set(job!=null);jobCancelledAtFrame.set(job?.isCancelled)
                            record("video_frame",mapOf("fallback_present" to (job!=null),"fallback_cancelled" to job?.isCancelled))
                            frameLatch.countDown()
                        }
                    })
                    engine.setVideoSurface(screen.surface);engine.setVideoVisible(true)
                    engine.play("https://www.youtube.com/watch?v=$id&t=0")
                }
                val deadline=SystemClock.elapsedRealtime()+25_000
                while(SystemClock.elapsedRealtime()<deadline) {
                    var done=false
                    inst.runOnMainSync {
                        assertNull(engine.snapshot.value.error)
                        done=player.isPlaying && player.currentPosition>=1_000
                    }
                    if(done) { playedAt=SystemClock.elapsedRealtime();break }
                    delay(20)
                }
                assertNotNull("A/V playback did not recover",playedAt)
                assertEquals(true,jobPresentAtFrame.get())
                assertEquals("Video output alone must not cancel the audio fallback",mode=="baseline",jobCancelledAtFrame.get())
                assertTrue(audioFailures.get()>0)
                assertEquals(854,engine.snapshot.value.videoHeight)
                assertEquals("854p",state.values["player:quality"])
            } catch(error:Throwable) { failure=error.javaClass.simpleName+":"+error.message;throw error }
            finally {
                rows+=mapOf("mode" to mode,"frameMs" to firstFrame.get().takeIf { it>0 }?.minus(origin),
                    "playingConfirmedMs" to playedAt?.minus(origin),"jobPresentAtFrame" to jobPresentAtFrame.get(),
                    "jobCancelledAtFrame" to jobCancelledAtFrame.get(),"masterAttempts" to masterCalls.get(),
                    "masterResponses" to masterHeaders.get(),"error" to failure,"events" to synchronized(events) { events.toList() })
                output.writeText(TestJson.any(rows));frameLatch.countDown()
                if(created) inst.runOnMainSync { engine.stop();player.release() }
                scope.cancel();http.dispatcher.cancelAll();http.connectionPool.evictAll();http.dispatcher.executorService.shutdown()
            }
        } } finally { prefs.setEnabled(PreferenceKeys.REMEMBER_QUALITY,oldRemember) }
    }
}
