package com.hhst.youtubelite.diagnostics

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class DiagnosticNetworkAndroidTest {
    private fun events(context: DiagnosticContext) = AppLog.recent(context)
        .filter { it.event == "request.failed" || it.event == "request.end" }

    @Test fun anUnreadHttpErrorIsAFailureRatherThanCancellation() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404).setBody("missing"))
            val context = DiagnosticContext()
            val client = DiagnosticNetwork.install(OkHttpClient.Builder()).build()
            client.newCall(Request.Builder().url(server.url("/fixture")).tag(DiagnosticContext::class.java, context).build())
                .execute().close()
            val row = events(context).single()
            assertEquals("request.failed", row.event)
            assertEquals("FAILURE", row.fields["outcome"])
            assertEquals(false, row.fields["cancelled"])
            assertEquals("http_rejected", row.fields["reason"])
            assertTrue(DiagnosticNetwork.pending(context).isEmpty())
        }
    }

    @Test fun explicitlyAcceptedHttpErrorsRemainSuccessful() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404).setBody("expected"))
            val context = DiagnosticContext()
            val client = DiagnosticNetwork.install(OkHttpClient.Builder()).build()
            client.newCall(Request.Builder().url(server.url("/fixture"))
                .tag(DiagnosticContext::class.java, context).tag(DiagnosticNetwork.Policy::class.java,
                    DiagnosticNetwork.Policy(setOf(404))).build()).execute().use { it.body!!.string() }
            val row = events(context).single()
            assertEquals("request.end", row.event)
            assertEquals("SUCCESS", row.fields["outcome"])
            assertTrue(DiagnosticNetwork.pending(context).isEmpty())
        }
    }

    @Test fun closingASuccessfulUnreadBodyDoesNotClaimTheCallWasCancelled() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("unused"))
            val context = DiagnosticContext()
            val client = DiagnosticNetwork.install(OkHttpClient.Builder()).build()
            client.newCall(Request.Builder().url(server.url("/fixture")).tag(DiagnosticContext::class.java, context).build())
                .execute().close()
            val row = events(context).single()
            assertEquals("ABANDONED", row.fields["outcome"])
            assertEquals(false, row.fields["cancelled"])
            assertTrue(DiagnosticNetwork.pending(context).isEmpty())
        }
    }
}
