package com.hhst.youtubelite.data.media

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.core.DeviceEvidence
import com.liskovsoft.youtubeapi.videoinfo.V2.ledgerScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.AssumptionViolatedException
import org.junit.Test

/** Run alone: -e network 1 -e mscLiveSessionContext 1. No content requests or Activities. */
class MscLiveSessionContextAndroidTest {
    @Test fun repeatedFreshContextsAvoidUnchangedCredentialComparisons() = runBlocking<Unit>(Dispatchers.IO) {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("network") == "1" && args.getString("mscLiveSessionContext") == "1")
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val store = PreferencesAuthStore(target)
        val preferences = target.getSharedPreferences("media-auth", Context.MODE_PRIVATE)
        val counting = CountingAuthStore(store, preferences)
        val expectCached = args.getString("liveExpectCachedToken") == "1"
        val report = JSONObject().put("experiment", "repeated_fresh_context").put("readOnly", true)
            .put("signedIn", MediaSession.signedIn()).put("phases", JSONArray())
            .put("expectCachedToken", expectCached)
            .put("decryptCounterScope", "existing_key_comparison_decrypt_attempts_in_PreferencesAuthStore_write")
        val phases = report.getJSONArray("phases")
        val calls = (args.getString("liveSessionCalls")?.toIntOrNull() ?: 20).coerceIn(1, 100)
        try {
            assumeTrue("An OAuth session is required; see msc-live-session-context.json", MediaSession.signedIn())
            // This class runs separately: the wrapper changes no stored values, but
            // rebuilding MediaSession deliberately invalidates other in-flight readers.
            MediaSession.initialize(counting)
            val initial = MediaSession.snapshot()
            val scope = initial.ledgerScope()
            val expires = counting.restoredExpiry
            val refreshDue = expires == null || expires <= System.currentTimeMillis() + 120_000L
            report.put("signedIn", initial.signedIn).put("refreshDueAtStart", refreshDue)
            assumeTrue("A valid OAuth token with two minutes remaining is required", initial.signedIn && !initial.accessToken.isNullOrBlank() && !refreshDue)
            var tokenChanged = false

            fun phase(label: String, count: Int): JSONObject {
                counting.reset()
                val timings = LongArray(count)
                val started = SystemClock.elapsedRealtimeNanos()
                repeat(count) { index ->
                    // Keep this a context/Keystore measurement, excluding real refresh I/O.
                    check(expires!! > System.currentTimeMillis() + 60_000L) { "context_benchmark_refresh_due" }
                    val before = SystemClock.elapsedRealtimeNanos()
                    val fresh = MediaSession.freshContext()
                    timings[index] = SystemClock.elapsedRealtimeNanos() - before
                    check(fresh.signedIn && fresh.ledgerScope() == scope) { "context_benchmark_session_changed" }
                    tokenChanged = tokenChanged || fresh.accessToken != initial.accessToken
                }
                val elapsed = SystemClock.elapsedRealtimeNanos() - started
                timings.sort()
                return JSONObject().put("label", label).put("calls", count)
                    .put("wallMs", elapsed / 1_000_000.0)
                    .put("contextMs", timings.sum() / 1_000_000.0)
                    .put("medianMs", timings[timings.size / 2] / 1_000_000.0)
                    .put("maxMs", timings.last() / 1_000_000.0)
                    .put("authReadAttempts", counting.reads).put("accessPairWriteAttempts", counting.writes)
                    .put("comparisonDecryptAttempts", counting.comparisonDecrypts)
                    .also { phases.put(it) }
            }

            val first = phase("restored_pair_verification", 1)
            if (expectCached) {
                assertEquals(2, first.getInt("accessPairWriteAttempts"))
                assertEquals(2, first.getInt("comparisonDecryptAttempts"))
            }
            repeat(3) { round ->
                val repeated = phase("unchanged_${round + 1}", calls)
                if (expectCached) {
                    assertEquals(0, repeated.getInt("authReadAttempts"))
                    assertEquals(0, repeated.getInt("accessPairWriteAttempts"))
                    assertEquals(0, repeated.getInt("comparisonDecryptAttempts"))
                }
            }
            report.put("outcome", "completed").put("sessionStable", true)
                .put("accessTokenChanged", tokenChanged).put("refreshOccurred", tokenChanged)
                .put("refreshExcludedByValidExpiry", !tokenChanged)
        } catch (skipped: AssumptionViolatedException) {
            report.put("outcome", "skipped")
            throw skipped
        } catch (failure: Exception) {
            report.put("outcome", "failed").put("failureType", failure.javaClass.simpleName)
            throw AssertionError("live_session_context_failed")
        } catch (failure: AssertionError) {
            report.put("outcome", "failed").put("failureType", "AssertionError")
            throw failure
        } finally {
            DeviceEvidence.writeJson("msc-live-session-context.json", report.toString(2))
        }
    }

    private class CountingAuthStore(
        private val delegate: AuthStore,
        private val preferences: SharedPreferences,
    ) : AuthStore {
        var reads = 0
        var writes = 0
        var comparisonDecrypts = 0
        var restoredExpiry: Long? = null
        override fun read(key: String): String? {
            reads++
            return delegate.read(key).also { if (key == MediaSession.KEY_ACCESS_EXPIRES) restoredExpiry = it?.toLongOrNull() }
        }
        override fun write(key: String, value: String?) {
            if (key == MediaSession.KEY_ACCESS_TOKEN || key == MediaSession.KEY_ACCESS_EXPIRES) {
                writes++
                // PreferencesAuthStore.write decrypts an existing value before comparing it;
                // presence checks observe no ciphertext or credentials.
                if (value != null && preferences.contains(key)) comparisonDecrypts++
            }
            delegate.write(key, value)
        }
        fun reset() {
            reads = 0
            writes = 0
            comparisonDecrypts = 0
        }
    }
}
