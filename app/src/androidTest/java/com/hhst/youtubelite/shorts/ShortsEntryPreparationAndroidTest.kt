package com.hhst.youtubelite.shorts

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.shorts.data.*
import com.hhst.youtubelite.shorts.domain.*
import com.hhst.youtubelite.shorts.presentation.ShortsViewModel
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ShortsEntryPreparationAndroidTest {
    private val page=ShortsPage(listOf(ShortItem("entry000001","","","","","","","")),"next")
    @Test fun foregroundClaimsTheSameReadWhenBrowsingStops() = runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val entered=CompletableDeferred<Unit>();val finish=CompletableDeferred<Unit>();val calls=AtomicInteger()
        val cache=ShortsEntryPreparation({calls.incrementAndGet();entered.complete(Unit);finish.await();page},{1},{true},scope=scope)
        try {
            val browser=async(Dispatchers.IO){cache.prepare()};entered.await()
            val foreground=async(Dispatchers.IO){cache.read()}
            delay(100);browser.cancel();cache.cancelUnclaimed();finish.complete(Unit)
            assertEquals(page,foreground.await());assertEquals(1,calls.get())
            assertEquals(page,cache.read());assertEquals("A consumed page must not replay on the next entry",2,calls.get())
        } finally {scope.cancel()}
    }
    @Test fun expiryIdentityAndPrivacyDiscardPreparedPages() = runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO);var now=0L;var identity=1L;var enabled=true
        val calls=AtomicInteger()
        val cache=ShortsEntryPreparation({calls.incrementAndGet();page},{identity},{enabled},{now},scope)
        try {
            cache.prepare();now=60_001;cache.read();assertEquals(2,calls.get())
            cache.prepare();identity++;cache.read();assertEquals(4,calls.get())
            cache.prepare();enabled=false;cache.read();assertEquals(6,calls.get())
            assertNull(cache.prepare());assertEquals(6,calls.get())
        } finally {scope.cancel()}
    }
    @Test fun unclaimedAndFailedPreparationDoNotPoisonColdEntry() = runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO);val calls=AtomicInteger();var fail=true
        val cache=ShortsEntryPreparation({calls.incrementAndGet();if(fail)error("fixture");page},{1},{true},scope=scope)
        try {
            assertTrue(runCatching{cache.prepare()}.isFailure);fail=false
            assertEquals(page,cache.read());assertEquals(2,calls.get())
            cache.prepare();cache.cancelUnclaimed();cache.read();assertEquals(4,calls.get())
        } finally {scope.cancel()}
    }
    @Test fun initialViewModelKeepsPreparationButRetryCancelsIt() {
        val calls=AtomicInteger();val store=ViewModelStore()
        val preloader=object:ShortsPreloader {
            override fun select(videoId:String){}
            override fun preload(videoId:String?){}
            override fun cancel(){calls.incrementAndGet()}
        }
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val model=ShortsViewModel(object:ShortsRepository {override suspend fun shorts()=page},
                SavedStateHandle(),ShortsScenario.Content,null,preloader=preloader)
            store.put("entry",model)
            assertEquals(0,calls.get());model.onAction(ShortsAction.Retry);assertEquals(1,calls.get())
            store.clear();assertEquals(2,calls.get())
        }
    }
}
