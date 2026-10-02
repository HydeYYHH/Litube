package com.hhst.youtubelite.downloader.android

import android.Manifest
import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.hhst.youtubelite.MainActivity
import com.hhst.youtubelite.downloader.pip.PipAutoEnter
import com.hhst.youtubelite.downloader.share.DownloadShareOnce
import com.hhst.youtubelite.downloader.ui.DownloadActionActivity
import com.hhst.youtubelite.downloader.ui.DownloadActions
import com.hhst.youtubelite.downloader.ui.DownloadActivity
import com.hhst.youtubelite.downloader.ui.DownloadShareActivity
import com.hhst.youtubelite.downloader.ui.DownloadSheetActivity
import com.hhst.youtubelite.downloader.ui.DownloadUi
import com.hhst.youtubelite.extension.ExtensionManager
import com.hhst.youtubelite.extension.PreferenceKeys
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

/**
 * API 31+ auto-PiP: MainActivity.setPictureInPictureParams(setAutoEnterEnabled)
 * must stay off while DownloadActivity / sheet / share trampolines hold
 * [PipAutoEnter.suppress], then restore from [PreferenceKeys.ENABLE_PIP].
 *
 * Player eligibility is applied through the real [PipAutoEnter] host (no live
 * YouTube stream). Skipped below API 31 where setAutoEnterEnabled does not exist.
 */
@RunWith(AndroidJUnit4::class)
class DownloadPipAutoEnterAndroidTest {

    private val instrumentation: Instrumentation
        get() = InstrumentationRegistry.getInstrumentation()

    @get:Rule
    val notifications: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    @Before
    fun reset() {
        assumeTrue(
            "PictureInPictureParams.setAutoEnterEnabled is API 31+",
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
        )
        resetOverlayState()
        prefs().setEnabled(PreferenceKeys.ENABLE_PIP, true)
    }

    @After
    fun teardown() {
        runCatching { prefs().setEnabled(PreferenceKeys.ENABLE_PIP, true) }
        resetOverlayState()
    }

    @Test
    fun mainAutoEnter_offForManagerSheetShare_restoresWithEnablePipPref() {
        ActivityScenario.launch(MainActivity::class.java).use { main ->
            instrumentation.waitForIdleSync()
            main.onActivity { host ->
                host.setPlayerPipEligible(true)
                assertFalse("suppress must be clear before auto-enter", PipAutoEnter.isSuppressed())
                assertTrue(PipAutoEnter.shouldAutoEnter(eligible = true, sdk = sdk()))
                assertTrue(
                    "eligible MainActivity must setAutoEnterEnabled " +
                        "pushError=${PipAutoEnter.lastPushError} " +
                        "requested=${PipAutoEnter.lastRequestedAutoEnter} " +
                        "paramsOn=${PipAutoEnter.isAutoEnterEnabled(host)}",
                    autoEnterOn(host),
                )
            }

            openAndFinishOverlay(
                main,
                DownloadActivity::class.java.name,
            ) { host -> DownloadUi.openManager(host) }
            assertRestoredOn(main)

            openAndFinishOverlay(
                main,
                DownloadActivity::class.java.name,
            ) { host ->
                host.startActivity(
                    DownloadActionActivity.intent(host, DownloadActions.VIEW, "batch-pip-api")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            assertRestoredOn(main)

            DownloadShareOnce.reset()
            openAndFinishOverlay(
                main,
                DownloadSheetActivity::class.java.name,
            ) { host ->
                host.startActivity(
                    Intent(host, DownloadShareActivity::class.java)
                        .setAction(Intent.ACTION_SEND)
                        .setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, "https://youtu.be/dQw4w9wgGcQ")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            assertRestoredOn(main)

            prefs().setEnabled(PreferenceKeys.ENABLE_PIP, false)
            instrumentation.waitForIdleSync()
            main.onActivity { host ->
                host.setPlayerPipEligible(false)
                assertFalse(autoEnterOn(host))
            }
            openAndFinishOverlay(
                main,
                DownloadActivity::class.java.name,
                playerEligible = false,
            ) { host -> DownloadUi.openManager(host) }
            waitUntil("ENABLE_PIP off overlay released") { !PipAutoEnter.isSuppressed() }
            main.onActivity { host ->
                assertFalse(PipAutoEnter.isSuppressed())
                assertFalse(
                    PipAutoEnter.shouldAutoEnter(eligible = false, sdk = sdk()),
                )
                assertFalse(
                    "ENABLE_PIP off must keep auto-enter off after overlay",
                    autoEnterOn(host),
                )
            }
        }
    }

    private fun openAndFinishOverlay(
        main: ActivityScenario<MainActivity>,
        overlayClass: String,
        playerEligible: Boolean = true,
        launch: (MainActivity) -> Unit,
    ) {
        val monitor = instrumentation.addMonitor(overlayClass, null, false)
        var overlay: Activity? = null
        try {
            main.onActivity(launch)
            overlay = monitor.waitForActivityWithTimeout(20_000)
            assertNotNull("$overlayClass should open from the host", overlay)
            waitUntil("$overlayClass suppress") { PipAutoEnter.isSuppressed() }
            assertTrue(PipAutoEnter.isSuppressed())
            assertFalse(PipAutoEnter.shouldAutoEnter(eligible = playerEligible, sdk = sdk()))
            main.onActivity { host ->
                assertFalse(
                    "auto-enter must stay off while $overlayClass is in front " +
                        "pushError=${PipAutoEnter.lastPushError} requested=${PipAutoEnter.lastRequestedAutoEnter}",
                    autoEnterOn(host),
                )
            }
        } finally {
            runCatching { overlay?.finish() }
            instrumentation.removeMonitor(monitor)
        }
        waitUntil("$overlayClass finished") { !PipAutoEnter.isSuppressed() }
    }

    /**
     * Prefer the live [PictureInPictureParams] getter. Some API 33 emulator
     * images do not echo [PictureInPictureParams.isAutoEnterEnabled]; then the
     * successful [Activity.setPictureInPictureParams] request is the proof.
     */
    private fun autoEnterOn(activity: Activity): Boolean {
        if (PipAutoEnter.isAutoEnterEnabled(activity)) return true
        return PipAutoEnter.lastRequestedAutoEnter &&
            PipAutoEnter.lastPushError == null &&
            !PipAutoEnter.isSuppressed()
    }

    private fun assertRestoredOn(main: ActivityScenario<MainActivity>) {
        main.onActivity { host ->
            assertFalse(PipAutoEnter.isSuppressed())
            assertTrue(PipAutoEnter.shouldAutoEnter(eligible = true, sdk = sdk()))
            assertTrue(
                "finish restores auto-enter while ENABLE_PIP is on " +
                    "pushError=${PipAutoEnter.lastPushError} requested=${PipAutoEnter.lastRequestedAutoEnter}",
                autoEnterOn(host),
            )
        }
    }

    private fun waitUntil(label: String, timeoutMs: Long = 12_000L, pred: () -> Boolean) {
        val start = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - start < timeoutMs) {
            if (pred()) return
            instrumentation.waitForIdleSync()
            Thread.sleep(50)
        }
        throw AssertionError("timed out waiting for $label")
    }

    private fun prefs(): ExtensionManager = GlobalContext.get().get()

    private fun sdk(): Int = Build.VERSION.SDK_INT
}
