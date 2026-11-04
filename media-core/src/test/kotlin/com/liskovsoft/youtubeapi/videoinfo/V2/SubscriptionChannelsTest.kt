package com.liskovsoft.youtubeapi.videoinfo.V2

import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class SubscriptionChannelsTest {
    private val channelId = "UCYrxIIlUsH2xrWiQowOFqHw"
    // FEsubscriptions tab endpoint, including URL-escaped protobuf/base64 padding.
    private val capturedParams = "igMaChhVQ1lyeElJbFVzSDJ4cldpUW93T0ZxSHc%3D"

    @Test fun channelFilterParametersMatchTheServerWireFormat() {
        assertEquals(capturedParams, subscriptionChannelParams(channelId))
        assertThrows(IllegalArgumentException::class.java) { subscriptionChannelParams("not-a-channel") }
    }

    private fun response(tabs: String, columns: String? = null) = if (columns == null)
        """{"contents":{"tvBrowseRenderer":{"content":{"tvSecondaryNavRenderer":{"sections":[
            {"tvSecondaryNavSectionRenderer":{"tabs":[$tabs]}}
        ]}}}}}"""
    else """{"contents":{"$columns":{"tabs":[$tabs]}}}"""

    private fun tab(id: String, style: String? = null, params: String? = null,
                    renderer: String = "tabRenderer", title: String = "Same name") = """{
        "$renderer": {"title":"$title","endpoint":{"browseEndpoint":{"browseId":"$id"
            ${params?.let { ",\"params\":\"$it\"" }.orEmpty()}}},
            "thumbnail":{"thumbnails":[{"url":"https://example.com/avatar"}]}
            ${style?.let { ",\"presentationStyle\":{\"style\":\"$it\"}" }.orEmpty()}}
    }"""

    @Test fun oauthDirectoryReadsSubscriptionTabsWithChannelIdentityAndServerUpdateFlags() {
        var calls = 0
        val http = object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                calls++
                assertTrue(request.url.contains("browse"))
                assertEquals("FEsubscriptions", jsonObject(request.body)!!.text("browseId"))
                assertEquals("Bearer token", request.headers["Authorization"])
                return HttpReply(200, response(
                    tab("FEsubscriptions", title = "All") + "," +
                        tab("FEsubscriptions", "NEW_CONTENT", capturedParams) + "," +
                        tab("UCother", renderer = "expandableTabRenderer")))
            }
        }
        val page = loadSubscribedChannelsPage(RequestContext(accessToken = "token"), http = http).value!!
        assertEquals(listOf(channelId, "UCother"), page.channels.map { it.id })
        assertEquals(listOf(true, false), page.channels.map { it.hasNewContent })
        assertEquals(listOf("Same name", "Same name"), page.channels.map { it.title })
        assertEquals(channelId, page.channels.first().channelId)
        assertEquals("https://example.com/avatar", page.channels.first().thumbnail)
        assertNull(page.nextKey)
        assertEquals(1, calls)
    }

    @Test fun duplicateTabsKeepTheirUpdateFlagAndUnrelatedTabsNeverBecomeSubscriptions() {
        val root = jsonObject(response(tab("UCsame") + "," + tab("UCsame", "NEW_CONTENT") + "," +
            tab("FEtrending", "NEW_CONTENT", capturedParams) + "," + tab("UCseen", "DEFAULT"),
            "twoColumnBrowseResultsRenderer"))!!
        assertEquals(listOf("UCsame", "UCseen"), subscriptionChannelTabs(root).map { it.id })
        assertEquals(listOf(true, false), subscriptionChannelTabs(root).map { it.hasNewContent })
    }

    @Test fun malformedOrUnrelatedParamsNeverInventAChannelIdentity() {
        val invalid = listOf("%broken", "not base64", "", "igMa", // Truncated length-delimited data.
            Base64.getUrlEncoder().encodeToString(byteArrayOf(10, 24) + channelId.toByteArray()),
            Base64.getUrlEncoder().encodeToString(byteArrayOf(0x8a.toByte(), 3, 127)))
        invalid.forEach { params ->
            val root = jsonObject(response(tab("FEsubscriptions", "NEW_CONTENT", params)))!!
            assertTrue(subscriptionChannelTabs(root).isEmpty())
        }
    }

    @Test fun unknownProtobufFieldsDoNotHideTheChannelAndBase64MayBeUnescaped() {
        val bytes = Base64.getDecoder().decode(capturedParams.replace("%3D", "="))
        val params = Base64.getUrlEncoder().withoutPadding().encodeToString(byteArrayOf(8, 1) + bytes)
        val root = jsonObject(response(tab("FEsubscriptions", "NEW_CONTENT", params)))!!
        assertEquals(channelId, subscriptionChannelTabs(root).single().id)
    }

    @Test fun continuationsKeepUsingTheDirectoryBrowseWithoutRestartingTheTabsRead() {
        var calls = 0
        val http = object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                calls++
                assertEquals("cursor", jsonObject(request.body)!!.text("continuation"))
                return HttpReply(200, """{"channelRenderer":{"channelId":"UCnext","title":{"simpleText":"Next"}}}""")
            }
        }
        val page = loadSubscribedChannelsPage(RequestContext(accessToken = "token"), "cursor", http).value!!
        assertEquals("UCnext", page.channels.single().id)
        assertEquals(1, calls)
    }

    @Test fun guidePresentationStyleAndRepeatedEntriesKeepUnreadSignals() {
        val http = object : HttpClient {
            override fun execute(request: HttpRequest) = if (request.url.contains("browse")) HttpReply(200, "{}")
            else HttpReply(200, """{"items":[
                {"guideEntryRenderer":{"navigationEndpoint":{"browseEndpoint":{"browseId":"UCnew","params":"channel-home-params"}}}},
                {"guideEntryRenderer":{"navigationEndpoint":{"browseEndpoint":{"browseId":"UCnew"}},
                    "presentationStyle":"GUIDE_ENTRY_PRESENTATION_STYLE_NEW_CONTENT"}},
                {"guideEntryRenderer":{"navigationEndpoint":{"browseEndpoint":{"browseId":"UCseen"}},
                    "presentationStyle":"GUIDE_ENTRY_PRESENTATION_STYLE_DEFAULT"}}
            ]}""")
        }
        val page = loadSubscribedChannelsPage(RequestContext(accessToken = "token"), http = http).value!!
        assertEquals(listOf("UCnew", "UCseen"), page.channels.map { it.id })
        assertEquals(listOf(true, false), page.channels.map { it.hasNewContent })
        assertNull("A channel-home parameter must not become a subscription feed filter", page.channels.first().params)
    }
}
