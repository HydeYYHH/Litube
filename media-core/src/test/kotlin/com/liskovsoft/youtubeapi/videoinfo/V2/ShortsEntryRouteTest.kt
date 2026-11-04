package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.*
import org.junit.Test

class ShortsEntryRouteTest {
    private val seed = """{"replacementEndpoint":{"reelWatchEndpoint":{"videoId":"entry000001"}},"entries":[],"sequenceContinuation":"next"}"""
    @Test fun seedlessEntryReadsTheDocumentWithoutARejectedApiRequest() {
        val calls=mutableListOf<HttpRequest>()
        val http=object:HttpClient { override fun execute(request:HttpRequest):HttpReply {
            calls+=request
            return HttpReply(200,"<script>var ytInitialData = $seed;</script>")
        } }
        val page=loadHome(HomeSection.SHORTS,http=http).value!!
        assertEquals(listOf("entry000001"),page.items.map{it.id})
        assertEquals("next",page.nextKey)
        assertEquals(1,calls.size);assertEquals("GET",calls.single().method)
    }
    @Test fun unknownDocumentCanFallBackToTheSequenceApi() {
        val calls=mutableListOf<HttpRequest>()
        val http=object:HttpClient { override fun execute(request:HttpRequest):HttpReply {
            calls+=request;return HttpReply(200,if(request.method=="GET")"<html></html>" else seed)
        } }
        assertEquals("entry000001",loadHome(HomeSection.SHORTS,http=http).value!!.items.single().id)
        assertEquals(listOf("GET","POST"),calls.map{it.method})
    }
    @Test fun failedDocumentDoesNotAddAnotherNetworkRequest() {
        var calls=0
        val http=object:HttpClient { override fun execute(request:HttpRequest):HttpReply {
            calls++;return HttpReply(503,"busy")
        } }
        assertFalse(loadHome(HomeSection.SHORTS,http=http).ok);assertEquals(1,calls)
    }
    @Test fun continuationKeepsItsOriginalApiRequest() {
        val http=object:HttpClient { override fun execute(request:HttpRequest):HttpReply {
            assertEquals("POST",request.method);assertTrue(request.body.contains("sequenceParams"))
            return HttpReply(200,seed)
        } }
        assertTrue(loadHome(HomeSection.SHORTS,continuation="next",http=http).ok)
    }
}
