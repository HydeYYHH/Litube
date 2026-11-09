@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.hhst.youtubelite.shorts

import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.playlist.*
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.core.TestJson
import com.hhst.youtubelite.diagnostics.AppLog
import com.hhst.youtubelite.diagnostics.DiagnosticEvent
import com.hhst.youtubelite.extractor.*
import com.hhst.youtubelite.extractor.Cache
import com.hhst.youtubelite.extension.ExtensionManager
import com.hhst.youtubelite.player.datasource.*
import com.hhst.youtubelite.player.engine.*
import com.hhst.youtubelite.player.sponsor.SponsorBlockManager
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.koin.core.context.GlobalContext

/** Same real stream and production engine; injected failure is optional. */
class HlsRecoveryExperimentAndroidTest {
    @get:Rule val activity=ActivityScenarioRule(ExtractionTestActivity::class.java)
    @Test fun compareRecovery() = runBlocking(Dispatchers.IO) {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("network")=="1")
        val app=instrumentation.targetContext
        val output=File(app.filesDir,"hls-recovery-experiment.json");output.delete()
        val id=args.getString("videoId")?:"bQwjr3kb_Ec"
        val height=args.getString("height")?.toInt()?:854
        val failDash=args.getString("failDash")=="1"
        val masterDelay=args.getString("masterDelayMs")?.toLong()?:0L
        val modes=(args.getString("modes")?:"none,master,master,none").split(',')
        val lead=args.getString("hedgeDelayMs")?.toLong()?:500L
        val e=GlobalContext.get().get<Extractor>().extract(id,demand=PlaybackDemand(height,null,false,setOf("avc")))
        val stream=e.stream.await();val metadata=e.metadata.await()
        assertNotNull(stream.hlsRequestPlan);assertNotNull(stream.hlsUrl)
        val video=stream.formats.filter{it.videoOnly && it.height==height && it.codec.orEmpty().startsWith("avc") && it.hasDashRanges}.maxByOrNull{it.bitrate}
        val audio=stream.formats.filter{it.audioOnly && it.hasDashRanges}.maxByOrNull{it.bitrate}
        assertNotNull(video);assertNotNull(audio)
        val input=Stream().apply{copyFrom(stream);formats=listOf(video!!,audio!!);dashUrl=null}
        val cache=PlayerDataSource.getCache(app)
        val rows=mutableListOf<Map<String,Any?>>()
        for((index,mode) in modes.withIndex()) {
            cache.keys.filter{it.startsWith("hls:v1:") || it==YoutubePlaybackCacheKey.of(video!!) || it==YoutubePlaybackCacheKey.of(audio!!)}.forEach(cache::removeResource)
            val origin=SystemClock.elapsedRealtime()
            val network=java.util.Collections.synchronizedList(mutableListOf<Map<String,Any?>>())
            fun record(event:String,request:Request?,fields:Map<String,Any?> = emptyMap()) {
                network+=mapOf("ms" to SystemClock.elapsedRealtime()-origin,"event" to event,
                    "host" to request?.url?.host?.hashCode()?.toString(),
                    "itag" to request?.url?.queryParameter("itag"),
                    "kind" to request?.url?.encodedPath?.let{if(it=="/")"warm_origin" else if(it.contains("manifest"))"playlist" else "media"})+fields
            }
            val defaultHttp=OkHttpClient()
            val builder=OkHttpClient.Builder()
            if(args.getString("wire")=="1")builder.sslSocketFactory(MediaTlsProbeAndroidTest.WireFactory(defaultHttp.sslSocketFactory){host,name,fields->
                network+=mapOf("ms" to SystemClock.elapsedRealtime()-origin,"event" to name,"host" to host.hashCode().toString())+fields
            },defaultHttp.x509TrustManager!!)
            val client=builder.eventListenerFactory{call->object:EventListener(){
                val request=call.request();var tlsAt=0L
                override fun dnsEnd(call:Call,domainName:String,addresses:List<java.net.InetAddress>){record("dns",request,mapOf("ipv4" to addresses.count{it is java.net.Inet4Address},"ipv6" to addresses.count{it is java.net.Inet6Address}))}
                override fun connectStart(call:Call,address:InetSocketAddress,proxy:Proxy){record("connect_start",request,mapOf("ipv6" to (address.address is java.net.Inet6Address),"proxy" to proxy.type().name))}
                override fun secureConnectStart(call:Call){tlsAt=SystemClock.elapsedRealtime();record("tls_start",request)}
                override fun secureConnectEnd(call:Call,handshake:Handshake?){record("tls_end",request,mapOf("tlsMs" to SystemClock.elapsedRealtime()-tlsAt,"version" to handshake?.tlsVersion?.javaName))}
                override fun connectionAcquired(call:Call,connection:Connection){record("connection",request,mapOf("connection" to System.identityHashCode(connection),"protocol" to connection.protocol().toString()))}
                override fun connectionReleased(call:Call,connection:Connection){record("released",request,mapOf("connection" to System.identityHashCode(connection)))}
                override fun responseHeadersEnd(call:Call,response:Response){record("headers",request,mapOf("status" to response.code))}
                override fun connectFailed(call:Call,address:InetSocketAddress,proxy:Proxy,protocol:Protocol?,failure:IOException){record("connect_failed",request,mapOf("causes" to generateSequence<Throwable>(failure){it.cause}.take(8).map{it.javaClass.name}.toList()))}
                override fun callFailed(call:Call,failure:IOException){record("failed",request,mapOf("causes" to generateSequence<Throwable>(failure){it.cause}.take(8).map{it.javaClass.name}.toList()))}
                override fun canceled(call:Call){record("canceled",request)}
            }}.addInterceptor{chain->
                if(chain.request().url.host.contains("sponsor")) return@addInterceptor Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(204).message("fixture disabled side request").body("".toResponseBody()).build()
                if(failDash && chain.request().url.encodedPath=="/videoplayback") {
                    Thread.sleep(1200)
                    throw SSLHandshakeException("controlled handshake EOF").apply{initCause(EOFException())}
                }
                if(masterDelay>0 && chain.request().url.toString()==input.hlsUrl)Thread.sleep(masterDelay)
                chain.proceed(chain.request())
            }.build()
            val sources=PlayerDataSource.create(app,client,YoutubeMediaRequests({true},ExtractionDiagnostics()))
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
            val resolver=MediaSourceResolver(sources,{1_500_000L},PlaybackStartup.videoSupport(app))
            val memory=MemoryJsonCache().apply{put("player:quality","${height}p",60000)}
            val fixedCache=object:Cache {
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
            val recorder=AppLog::class.java.getDeclaredField("recorder").apply{isAccessible=true}.get(AppLog)!!
            val recent=recorder.javaClass.getDeclaredMethod("recent").apply{isAccessible=true}
            val captured=linkedMapOf<Long,Map<String,Any?>>()
            fun sample(){
                @Suppress("UNCHECKED_CAST") val events=recent.invoke(recorder) as List<DiagnosticEvent>
                events.filter{it.elapsedMs>=origin}.forEach{r->captured.putIfAbsent(r.sequence,mapOf("ms" to r.elapsedMs-origin,"event" to r.event,"video" to r.context?.videoId,"fields" to r.fields))}
            }
            val warmDone=AtomicLong(0);var warmFailure:String?=null
            var warmJob:Job?=null
            var began=0L;var frameAt:Long?=null;var playingAt:Long?=null
            try {
                activity.scenario.onActivity{host->
                    engine=PlaybackEngine(app,Extractor(fixedCache,scope),resolver,memory,GlobalContext.get().get<ExtensionManager>(),SponsorBlockManager(client,GlobalContext.get().get(),scope))
                    player=engine.javaClass.getDeclaredField("player").apply{isAccessible=true}.get(engine) as ExoPlayer
                    val selector=engine.javaClass.getDeclaredField("trackSelector").apply{isAccessible=true}.get(engine) as DefaultTrackSelector
                    selector.parameters=selector.parameters.buildUpon().setPreferredVideoMimeType("video/avc").build()
                    engine.setVideoSurface(host.surface);engine.setVideoVisible(true)
                    began=SystemClock.elapsedRealtime();engine.play("https://www.youtube.com/watch?v=$id&t=0")
                }
                if(mode!="none") warmJob=scope.launch {
                    delay(lead)
                    try { runInterruptible { warm(sources,input,mode,height,client) };warmDone.set(SystemClock.elapsedRealtime()) }
                    catch(failure:Exception){warmFailure=failure.javaClass.simpleName}
                }
                while(SystemClock.elapsedRealtime()-began<30_000) {
                    sample();val state=engine.snapshot.value
                    if(state.firstFrameRendered && frameAt==null)frameAt=SystemClock.elapsedRealtime()
                    if(state.isPlaying && playingAt==null)playingAt=SystemClock.elapsedRealtime()
                    if(state.error!=null || frameAt!=null && playingAt!=null)break
                    delay(20)
                }
                val state=engine.snapshot.value
                sample()
                var codec:String?=null
                instrumentation.runOnMainSync{codec=player.videoFormat?.codecs}
                rows+=mapOf("arm" to index,"mode" to mode,"failDash" to failDash,"height" to state.videoHeight,"codec" to codec,
                    "frameMs" to frameAt?.minus(began),"playingMs" to playingAt?.minus(began),"error" to state.error,
                    "warmDoneMs" to warmDone.get().takeIf{it>0}?.minus(began),"warmFailure" to warmFailure,
                    "network" to synchronized(network){network.toList()},"events" to captured.values.toList())
                output.writeText(TestJson.any(mapOf("videoId" to id,"height" to height,"hedgeDelayMs" to lead,"masterDelayMs" to masterDelay,"rows" to rows)))
                assertNull(state.error);assertNotNull(frameAt);assertNotNull(playingAt)
                assertEquals(height,state.videoHeight)
            } finally {
                warmJob?.cancelAndJoin()
                instrumentation.runOnMainSync{engine.stop();player.release()}
                scope.cancel();client.connectionPool.evictAll();client.dispatcher.executorService.shutdown()
            }
        }
    }

    private fun warm(sources:PlayerDataSource,stream:Stream,mode:String,height:Int,http:OkHttpClient) {
        val plan=stream.hlsRequestPlan!!;val url=stream.hlsUrl!!
        val deadline=SystemClock.elapsedRealtime()+3000
        if(mode=="origin") {
            val origin=stream.formats.first{it.videoOnly}.url.toHttpUrl().newBuilder().encodedPath("/").query(null).build()
            val client=http.newBuilder().retryOnConnectionFailure(false).build()
            runBlocking(Dispatchers.IO) {
                List(2){async {
                    val request=Request.Builder().url(origin).header("User-Agent",plan.userAgent)
                        .header("Origin","https://www.youtube.com").header("Referer","https://www.youtube.com/").build()
                    val call=client.newCall(request);call.timeout().timeout(2000,java.util.concurrent.TimeUnit.MILLISECONDS)
                    try{call.execute().use{response->
                        val source=response.body!!.source();val buffer=okio.Buffer()
                        while(buffer.size<65_536 && source.read(buffer,8192)>=0){}
                    }}finally{call.cancel()}
                }}.awaitAll()
            }
            return
        }
        fun read(url:String):ByteArray {
            val source=sources.vodHls(plan).createDataSource(C.DATA_TYPE_MANIFEST)
            try {
                source.open(DataSpec.Builder().setUri(url).setCustomData(PrefetchDeadline(deadline)).build())
                val out=ByteArrayOutputStream();val buffer=ByteArray(8192)
                while(true){val n=source.read(buffer,0,buffer.size);if(n<0)break;out.write(buffer,0,n);check(out.size()<=VodManifestCache.MAX_DOCUMENT_BYTES)}
                return out.toByteArray()
            }finally{source.close()}
        }
        val master=read(url)
        if(mode=="master")return
        val multi=HlsPlaylistParser().parse(Uri.parse(url),master.inputStream()) as HlsMultivariantPlaylist
        val variant=multi.variants.firstOrNull{it.format.height==height && it.format.codecs.orEmpty().contains("avc")}
            ?:multi.variants.first{it.format.height==height}
        read(variant.url.toString())
    }
}
