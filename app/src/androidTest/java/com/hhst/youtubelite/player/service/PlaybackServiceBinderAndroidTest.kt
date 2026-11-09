package com.hhst.youtubelite.player.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.SystemClock
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.extractor.ExtractionTestActivity
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@UnstableApi
class PlaybackServiceBinderAndroidTest {
    @Test fun retainedBinderDoesNotKeepDestroyedServiceAlive() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext.applicationContext
        val intent = Intent(context, PlaybackService::class.java)
        val connected = CountDownLatch(2)
        val binders = List(2) { AtomicReference<PlaybackService.LocalBinder>() }
        val watchedService = AtomicReference<WeakReference<PlaybackService>>()
        val connections = binders.map { result ->
            object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    val local = binder as PlaybackService.LocalBinder
                    result.set(local)
                    watchedService.set(WeakReference(requireNotNull(local.getService())))
                    connected.countDown()
                }

                override fun onServiceDisconnected(name: ComponentName?) = Unit
            }
        }
        val bound = BooleanArray(connections.size)

        ActivityScenario.launch(ExtractionTestActivity::class.java).use { scenario ->
            try {
                scenario.onActivity {
                    connections.forEachIndexed { index, connection ->
                        bound[index] = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
                        assertTrue("Playback service must bind", bound[index])
                    }
                }
                assertTrue("Both clients must connect", connected.await(5, TimeUnit.SECONDS))
                val retainedBinder = binders[0].get()
                assertSame("Clients share the published binder", retainedBinder, binders[1].get())

                instrumentation.runOnMainSync {
                    context.unbindService(connections[0])
                    bound[0] = false
                }
                instrumentation.waitForIdleSync()
                assertNotNull("The remaining client must retain access", retainedBinder.getService())

                instrumentation.runOnMainSync {
                    context.unbindService(connections[1])
                    bound[1] = false
                }
                val destroyedDeadline = SystemClock.elapsedRealtime() + 5_000
                while (retainedBinder.getService() != null && SystemClock.elapsedRealtime() < destroyedDeadline) {
                    SystemClock.sleep(50)
                }
                assertNull("A binder retained after onDestroy must be detached", retainedBinder.getService())

                // Keep the binder strongly reachable, like the native root in the leak trace.
                val collectedDeadline = SystemClock.elapsedRealtime() + 5_000
                while (watchedService.get().get() != null && SystemClock.elapsedRealtime() < collectedDeadline) {
                    instrumentation.waitForIdleSync()
                    Runtime.getRuntime().gc()
                    System.runFinalization()
                    SystemClock.sleep(50)
                }
                assertNull("The retained binder must not prevent service collection", watchedService.get().get())
                assertNull(retainedBinder.getService())
            } finally {
                instrumentation.runOnMainSync {
                    connections.forEachIndexed { index, connection ->
                        if (bound[index]) context.unbindService(connection)
                    }
                    context.stopService(intent)
                }
            }
        }
    }
}
