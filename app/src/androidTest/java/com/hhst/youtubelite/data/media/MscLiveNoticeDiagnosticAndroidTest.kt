package com.hhst.youtubelite.data.media

import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.core.DeviceEvidence
import com.liskovsoft.youtubeapi.videoinfo.V2.HttpClient
import com.liskovsoft.youtubeapi.videoinfo.V2.HttpReply
import com.liskovsoft.youtubeapi.videoinfo.V2.HttpRequest
import com.liskovsoft.youtubeapi.videoinfo.V2.PlayerClient
import com.liskovsoft.youtubeapi.videoinfo.V2.RequestContext
import com.liskovsoft.youtubeapi.videoinfo.V2.RequestError
import com.liskovsoft.youtubeapi.videoinfo.V2.RequestFailure
import com.liskovsoft.youtubeapi.videoinfo.V2.ledgerScope
import com.liskovsoft.youtubeapi.videoinfo.V2.loadNotices
import com.liskovsoft.youtubeapi.videoinfo.V2.playerQuery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.koin.core.context.GlobalContext

/** Two profile reads, plus one delegation comparison when applicable. Never records an interaction. */
class MscLiveNoticeDiagnosticAndroidTest {
    @Test fun compareReadOnlyNotificationProfilesAndRecordOnlyAllowlistedErrors() = runBlocking(Dispatchers.IO) {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("network") == "1" && args.getString("mscLiveNotice") == "1")
        val initial = MediaSession.snapshot()
        assumeTrue("The diagnostic requires the existing OAuth account", !initial.accessToken.isNullOrBlank())
        val initialScope = initial.ledgerScope()
        val probe = MscLiveNetworkProbe(GlobalContext.get().get<OkHttpClient>(),
            args.getString("liveProxyHost")?.takeIf(String::isNotBlank),
            args.getString("liveProxyPort")?.toIntOrNull() ?: 7890, allowSignedIn = true) {
            MediaSession.freshContext().also { context ->
                if (context.accessToken.isNullOrBlank() || context.ledgerScope() != initialScope)
                    throw MediaException(RequestError(RequestFailure.AUTH, "live_account_profile_changed"))
            }
        }
        val operations = JSONArray()
        val output = JSONObject().put("readOnly", true).put("experiment", "notice_profile_diagnostic")
            .put("timeUtc", java.time.Instant.now().toString()).put("operations", operations)
        fun save() {
            output.put("network", probe.report()).put("profileChanged", MediaSession.snapshot().ledgerScope() != initialScope)
            DeviceEvidence.writeJson("msc-live-notice-diagnostic.json", output.toString(2))
        }
        try {
            withTimeout(120_000) {
                val variants = listOf("baseline", "web") + if (initial.onBehalfOfUser != null) listOf("withoutDelegation") else emptyList()
                for (variant in variants) {
                    val operation = JSONObject().put("variant", variant)
                    operations.put(operation)
                    try {
                        val page = probe.sample("notice.$variant") {
                            probe.client.read { context, http ->
                                val effective = if (variant == "withoutDelegation") context.copy(onBehalfOfUser = null) else context
                                val observing = object : HttpClient {
                                    override fun execute(request: HttpRequest): HttpReply {
                                        val sent = if (variant == "web") withWebPosture(request, effective) else request
                                        operation.put("request", describeRequest(sent))
                                        return http.execute(sent).also { reply ->
                                            operation.put("httpStatus", reply.status)
                                            if (reply.status !in 200..299) operation.put("serverError", describeError(reply.body))
                                        }
                                    }
                                }
                                loadNotices(effective, http = observing).requireValue()
                            }
                        }
                        operation.put("outcome", "ok").put("items", page.notices.size).put("hasContinuation", page.nextKey != null)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) {
                        operation.put("outcome", "failed").put("failureType", error.javaClass.simpleName)
                        (error as? MediaException)?.failure?.let { failure ->
                            operation.put("failureKind", failure.kind.name).put("failureCode", safeFailureCode(failure.code))
                        }
                    } finally { save() }
                }
            }
        } finally { save() }
    }

    @Test fun unknownErrorTextAccountDataAndFieldValuesCannotEscapeTheDiagnostic() {
        val secret = "SECRET_ACCOUNT_TOKEN"
        val error = describeError("""{"error":{"code":400,"status":"INVALID_ARGUMENT","message":"$secret",
            "errors":[{"reason":"failedPrecondition","domain":"global","message":"$secret"},
                {"reason":"$secret","domain":"$secret"}],
            "details":[{"@type":"type.googleapis.com/google.rpc.BadRequest","fieldViolations":[
                {"field":"notificationsMenuRequestType","description":"$secret"},{"field":"$secret","description":"$secret"}]},
                {"@type":"$secret","metadata":{"account":"$secret"}}]}}""").toString()
        assertFalse(error.contains(secret))
        assertTrue(error.contains("failedPrecondition"))
        assertTrue(error.contains("notificationsMenuRequestType"))
        assertTrue(error.contains("omitted"))
        assertEquals("precondition_failed", describeError("""{"error":{"message":"Precondition check failed."}}""")
            .getString("messageClass"))
    }

    @Test fun theExperimentalProfilePreservesTheReadPayloadAndAccountContext() {
        val request = HttpRequest("POST", "https://www.youtube.com/youtubei/v1/notification/get_notification_menu",
            mapOf("Authorization" to "Bearer test-token", "X-Youtube-Client-Name" to "7"),
            """{"context":{"client":{"clientName":"TVHTML5","clientVersion":"7.0","hl":"en","gl":"GB","visitorData":"test-visitor"},
                "user":{"onBehalfOfUser":"test-channel"}},"notificationsMenuRequestType":"NOTIFICATIONS_MENU_REQUEST_TYPE_INBOX"}""")
        val web = withWebPosture(request, RequestContext(accessToken = "test-token", visitor = "test-visitor", region = "GB"))
        val body = JSONObject(web.body)
        assertEquals("WEB", body.getJSONObject("context").getJSONObject("client").getString("clientName"))
        assertEquals("GB", body.getJSONObject("context").getJSONObject("client").getString("gl"))
        assertEquals("test-visitor", body.getJSONObject("context").getJSONObject("client").getString("visitorData"))
        assertEquals("test-channel", body.getJSONObject("context").getJSONObject("user").getString("onBehalfOfUser"))
        assertEquals("NOTIFICATIONS_MENU_REQUEST_TYPE_INBOX", body.getString("notificationsMenuRequestType"))
        assertEquals("Bearer test-token", web.headers["Authorization"])
        assertEquals("1", web.headers["X-Youtube-Client-Name"])
        assertFalse(describeRequest(web).toString().contains("test-"))
    }

    private fun withWebPosture(request: HttpRequest, context: RequestContext): HttpRequest {
        require(request.method == "POST" && request.url.substringBefore('?') ==
            "https://www.youtube.com/youtubei/v1/notification/get_notification_menu")
        val web = playerQuery(PlayerClient.WEB, "context", context.visitor)
        val client = JSONObject(web.body).getJSONObject("context").getJSONObject("client")
            .put("hl", context.language).put("gl", context.region)
        context.visitor?.let { client.put("visitorData", it) }
        val root = JSONObject(request.body)
        root.getJSONObject("context").put("client", client)
        return request.copy(headers = request.headers + web.headers, body = root.toString())
    }

    private fun describeRequest(request: HttpRequest): JSONObject {
        val root = JSONObject(request.body)
        val context = root.getJSONObject("context")
        val client = context.getJSONObject("client")
        return JSONObject().put("clientName", client.optString("clientName").takeIf { it in setOf("WEB", "TVHTML5") } ?: "omitted")
            .put("clientVersion", client.optString("clientVersion").takeIf { it.matches(Regex("[0-9.]{1,32}")) } ?: "omitted")
            .put("language", client.optString("hl").takeIf { it.matches(Regex("[a-zA-Z-]{2,12}")) } ?: "omitted")
            .put("region", client.optString("gl").takeIf { it.matches(Regex("[A-Z]{2}")) } ?: "omitted")
            .put("visitorPresent", client.has("visitorData")).put("delegatedChannelPresent", context.optJSONObject("user")?.has("onBehalfOfUser") == true)
            .put("inboxRequest", root.optString("notificationsMenuRequestType") == "NOTIFICATIONS_MENU_REQUEST_TYPE_INBOX")
            .put("continuationPresent", root.has("continuation"))
    }

    private fun describeError(body: String): JSONObject {
        if (body.length > 65_536) return JSONObject().put("jsonError", false).put("reason", "error_envelope_too_large")
        val error = runCatching { JSONObject(body).optJSONObject("error") }.getOrNull()
            ?: return JSONObject().put("jsonError", false)
        val output = JSONObject().put("jsonError", true).put("code", error.optInt("code").takeIf { it in 100..599 } ?: JSONObject.NULL)
            .put("status", error.optString("status").takeIf { it in statuses } ?: "omitted")
            .put("messageClass", when (error.optString("message").trim()) {
                "Precondition check failed." -> "precondition_failed"
                "Request contains an invalid argument." -> "invalid_argument"
                "The request is missing a valid API key." -> "missing_api_key"
                "API key not valid. Please pass a valid API key." -> "invalid_api_key"
                "Login Required.", "Login Required" -> "login_required"
                "Request had insufficient authentication scopes." -> "insufficient_auth_scopes"
                "The caller does not have permission" -> "permission_denied"
                else -> "omitted"
            })
        val errors = error.optJSONArray("errors") ?: JSONArray()
        output.put("errors", JSONArray((0 until errors.length()).take(8).map { index ->
            val item = errors.optJSONObject(index)
            JSONObject().put("reason", item?.optString("reason")?.takeIf { it in reasons } ?: "omitted")
                .put("domain", item?.optString("domain")?.takeIf { it in setOf("global", "youtube.notification", "youtubei", "usageLimits") } ?: "omitted")
        }))
        val details = error.optJSONArray("details") ?: JSONArray()
        output.put("details", JSONArray((0 until details.length()).take(8).map { index ->
            val item = details.optJSONObject(index)
            val type = item?.optString("@type").orEmpty()
            val violations = item?.optJSONArray("fieldViolations") ?: JSONArray()
            JSONObject().put("type", type.takeIf { it in detailTypes } ?: "omitted")
                .put("reason", item?.optString("reason")?.takeIf { it in reasons } ?: "omitted")
                .put("fields", JSONArray((0 until violations.length()).take(8).map { field ->
                    violations.optJSONObject(field)?.optString("field")?.takeIf { it in fields } ?: "omitted"
                }))
        }))
        return output
    }

    private fun safeFailureCode(code: String) = code.takeIf {
        it.matches(Regex("http_[1-5][0-9]{2}")) || it in setOf("sign_in_required", "live_account_profile_changed", "missing_response",
            "invalid_json", "cancelled", "transport_dns", "transport_connect", "transport_tls", "transport_timeout",
            "transport_unreachable", "transport_protocol", "transport_socket", "transport_io", "transport_failed")
    } ?: "omitted"

    private companion object {
        val statuses = setOf("INVALID_ARGUMENT", "FAILED_PRECONDITION", "UNAUTHENTICATED", "PERMISSION_DENIED", "NOT_FOUND", "INTERNAL", "UNAVAILABLE", "RESOURCE_EXHAUSTED")
        val reasons = setOf("badRequest", "invalidParameter", "failedPrecondition", "authError", "forbidden", "youtubeSignupRequired",
            "accountDelegationForbidden", "unsupportedClient", "INVALID_ARGUMENT", "FAILED_PRECONDITION", "UNAUTHENTICATED", "PERMISSION_DENIED")
        val detailTypes = setOf("type.googleapis.com/google.rpc.BadRequest", "type.googleapis.com/google.rpc.ErrorInfo", "type.googleapis.com/google.rpc.PreconditionFailure")
        val fields = setOf("context", "context.client", "context.client.clientName", "context.client.clientVersion", "context.client.hl", "context.client.gl",
            "context.user", "context.user.onBehalfOfUser", "notificationsMenuRequestType", "continuation")
    }
}
