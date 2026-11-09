package com.hhst.youtubelite.data.media

import android.os.Bundle
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.account.data.MediaSignInService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.koin.core.context.GlobalContext
import java.io.File

/** Explicit device-code authorization on the dedicated emulator; no Activity or browser is opened. */
class MscLiveSignInAndroidTest {
    @Test fun authorizeTheDedicatedEmulatorWithADeviceCode() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("network") == "1" && args.getString("mscLiveSignIn") == "1")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        val report = JSONObject().put("state", "starting")
        val started = SystemClock.elapsedRealtime()
        val evidence = File(target.filesDir, "device-evidence/msc-live-sign-in.json")
        fun publish() {
            evidence.parentFile!!.mkdirs()
            evidence.writeText(report.toString(2))
            instrumentation.sendStatus(2, Bundle().apply { putString("mscLiveSignIn", report.toString()) })
        }
        val store = PreferencesAuthStore(target)
        MediaSession.initialize(store)
        if (MediaSession.signedIn()) {
            report.put("state", "already_signed_in").put("signedIn", true)
                .put("accountListAvailable", MediaSession.accounts().isNotEmpty())
            publish()
            return@runBlocking
        }
        val service = MediaSignInService(GlobalContext.get().get<OkHttpClient>(), store)
        try {
            val code = service.loadCode()
            // Device_code and all client/token credentials stay inside the real service.
            report.put("state", "awaiting_authorization").put("userCode", code.userCode)
                .put("verificationUrl", code.verificationUrl).put("expiresAtMs", code.expiresAtMs)
            publish()
            val remaining = code.expiresAtMs - System.currentTimeMillis()
            if (remaining <= 0) throw MediaException(com.liskovsoft.youtubeapi.videoinfo.V2.RequestError(
                com.liskovsoft.youtubeapi.videoinfo.V2.RequestFailure.AUTH, "expired_token"))
            val token = withTimeout(remaining) { service.poll(code) }
            val accountsAvailable = service.complete(token).isNotEmpty()
            check(MediaSession.signedIn()) { "live_sign_in_session_not_committed" }
            report.put("state", "authorized").put("signedIn", true)
                .put("accountListAvailable", accountsAvailable)
                .put("persisted", !store.read(MediaSession.KEY_REFRESH_TOKEN).isNullOrBlank())
        } catch (_: TimeoutCancellationException) {
            report.put("state", "expired").put("failureKind", "AUTH").put("failureCode", "expired_token")
            throw AssertionError("live_sign_in_expired")
        } catch (cancelled: CancellationException) {
            report.put("state", "cancelled")
            throw cancelled
        } catch (failure: Exception) {
            val error = (failure as? MediaException)?.failure
            val code = error?.code?.takeIf { it.matches(Regex("[a-z][a-z0-9_]{0,79}")) } ?: "unclassified"
            report.put("state", when (code) {
                "access_denied" -> "declined"
                "expired_token" -> "expired"
                else -> "failed"
            }).put("failureKind", error?.kind?.name ?: "LOCAL").put("failureCode", code)
            // Typed assertions keep protocol/credential exception text out of JUnit output.
            throw AssertionError("live_sign_in_$code")
        } finally {
            report.put("elapsedMs", SystemClock.elapsedRealtime() - started)
            publish()
        }
    }
}
