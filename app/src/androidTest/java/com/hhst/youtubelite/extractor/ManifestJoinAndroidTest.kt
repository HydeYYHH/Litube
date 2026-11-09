@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.hhst.youtubelite.extractor

import android.os.SystemClock
import androidx.media3.datasource.*
import com.hhst.youtubelite.player.datasource.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.io.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test

class ManifestJoinAndroidTest {
    @Test fun foregroundReusesAStillOpeningPreparation()=fixture { factory,entered,release,opens,workers ->
        val preparation=workers.submit<String>{read(factory,true)}
        assertTrue(entered.await(1,TimeUnit.SECONDS))
        val foreground=workers.submit<String>{read(factory,false)}
        Thread.sleep(100)
        assertEquals(1,opens.get());release.countDown()
        assertEquals("#EXTM3U\nfixture",preparation.get(2,TimeUnit.SECONDS))
        assertEquals("#EXTM3U\nfixture",foreground.get(2,TimeUnit.SECONDS))
        assertEquals(1,opens.get())
    }
    @Test fun anUnfinishedPreparationCannotBlockTheForegroundIndefinitely()=fixture { factory,entered,release,opens,workers ->
        val preparation=workers.submit<String>{read(factory,true)}
        assertTrue(entered.await(1,TimeUnit.SECONDS))
        val began=SystemClock.elapsedRealtime()
        assertEquals("#EXTM3U\nfixture",read(factory,false))
        assertTrue(SystemClock.elapsedRealtime()-began in 800..1_800)
        assertEquals(2,opens.get());release.countDown();preparation.get(2,TimeUnit.SECONDS)
    }
    @Test fun cancelledWaitDoesNotCancelThePreparation()=fixture { factory,entered,release,opens,workers ->
        val preparation=workers.submit<String>{read(factory,true)}
        assertTrue(entered.await(1,TimeUnit.SECONDS))
        val foreground=workers.submit<String>{read(factory,false)}
        Thread.sleep(100);assertTrue(foreground.cancel(true))
        release.countDown();assertEquals("#EXTM3U\nfixture",preparation.get(2,TimeUnit.SECONDS))
        assertEquals("#EXTM3U\nfixture",read(factory,false));assertEquals(1,opens.get())
    }
    @Test fun failedPreparationReleasesTheForegroundToItsOwnRequest()=fixture(failFirst=true) { factory,entered,release,opens,workers ->
        val preparation=workers.submit<String>{read(factory,true)}
        assertTrue(entered.await(1,TimeUnit.SECONDS))
        val foreground=workers.submit<String>{read(factory,false)}
        Thread.sleep(100);release.countDown()
        assertThrows(ExecutionException::class.java){preparation.get(2,TimeUnit.SECONDS)}
        assertEquals("#EXTM3U\nfixture",foreground.get(2,TimeUnit.SECONDS));assertEquals(2,opens.get())
    }

    @Test fun aSessionChangeWhileJoiningCannotPublishTheOldManifest() {
        val current=AtomicBoolean(true)
        fixture(current={current.get()}) { factory,entered,release,opens,workers ->
            val preparation=workers.submit<String>{read(factory,true)}
            assertTrue(entered.await(1,TimeUnit.SECONDS))
            val foreground=workers.submit<String>{read(factory,false)}
            Thread.sleep(100);current.set(false);release.countDown()
            assertThrows(ExecutionException::class.java){preparation.get(2,TimeUnit.SECONDS)}
            val failure=assertThrows(ExecutionException::class.java){foreground.get(2,TimeUnit.SECONDS)}
            assertEquals("MEDIA_SESSION_CHANGED",failure.cause?.message);assertEquals(1,opens.get())
        }
    }

    @Test fun joiningAndAFreshNetworkAttemptShareTheOpenBudget() {
        val server=MockWebServer().apply{start()}
        val http=OkHttpClient();val workers=Executors.newSingleThreadExecutor()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val factory=VodManifestCache().factory("budget",Long.MAX_VALUE,{true},
            YoutubeHttpDataSource.Factory(http,"fixture-UA").setOpenTimeoutMs(1_500))
        val uri=server.url("/budget.m3u8").toString()
        val preparation=workers.submit<String>{read(factory,true,uri)}
        try {
            assertNotNull(server.takeRequest(1,TimeUnit.SECONDS))
            val began=SystemClock.elapsedRealtime()
            val failure=assertThrows(IOException::class.java){read(factory,false,uri)}
            val elapsed=SystemClock.elapsedRealtime()-began
            assertTrue("Join extended the open budget: ${elapsed}ms",elapsed in 1_200..2_100)
            assertTrue(generateSequence<Throwable>(failure){it.cause}.any{it.message=="MEDIA_OPEN_TIMEOUT"})
            assertEquals(2,server.requestCount)
        } finally {
            preparation.cancel(true);workers.shutdownNow();workers.awaitTermination(3,TimeUnit.SECONDS)
            http.connectionPool.evictAll();http.dispatcher.executorService.shutdown();server.shutdown()
        }
    }

    private fun fixture(failFirst:Boolean=false,current:()->Boolean={true},
                        block:(DataSource.Factory,CountDownLatch,CountDownLatch,AtomicInteger,ExecutorService)->Unit) {
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val opens=AtomicInteger()
        val workers=Executors.newFixedThreadPool(2)
        val factory=VodManifestCache().factory("identity",Long.MAX_VALUE,current,DataSource.Factory {
            object:DataSource {
                private val bytes=ByteArrayDataSource("#EXTM3U\nfixture".toByteArray())
                override fun addTransferListener(listener:TransferListener)=bytes.addTransferListener(listener)
                override fun open(spec:DataSpec):Long {
                    if(opens.incrementAndGet()==1){
                        entered.countDown();release.await(4,TimeUnit.SECONDS)
                        if(failFirst)throw IOException("PREPARATION_FAILED")
                    }
                    return bytes.open(spec)
                }
                override fun read(buffer:ByteArray,offset:Int,length:Int)=bytes.read(buffer,offset,length)
                override fun getUri()=bytes.uri
                override fun close()=bytes.close()
            }
        })
        try{block(factory,entered,release,opens,workers)}finally{release.countDown();workers.shutdownNow()}
    }
    private fun read(factory:DataSource.Factory,preparation:Boolean,uri:String="https://manifest.googlevideo.com/join.m3u8"):String {
        val source=factory.createDataSource()
        try {
            val spec=DataSpec.Builder().setUri(uri).apply{
                if(preparation)setCustomData(PrefetchDeadline(SystemClock.elapsedRealtime()+2_000))
            }.build()
            source.open(spec);val output=ByteArrayOutputStream();val buffer=ByteArray(32)
            while(true){val count=source.read(buffer,0,buffer.size);if(count<0)break;output.write(buffer,0,count)}
            return output.toString("UTF-8")
        }finally{source.close()}
    }
}
