package com.hhst.youtubelite.core

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class AndroidWebViewMainGate(
    private val handler: Handler = Handler(Looper.getMainLooper()),
    private val timeoutMs: Long = 8_000L,
) : WebViewMainGate {
    override val isOnMain: Boolean
        get() = Looper.myLooper() == Looper.getMainLooper()

    override fun <T> run(block: () -> T): T {
        if (isOnMain) return block()
        val latch = CountDownLatch(1)
        val box = AtomicReference<Result<T>>()
        val posted = handler.post {
            box.set(runCatching(block))
            latch.countDown()
        }
        check(posted) { "PoToken WebView main looper is shutting down" }
        check(latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            "PoToken WebView timed out waiting for the main thread"
        }
        return checkNotNull(box.get()).getOrThrow()
    }

    override fun post(block: () -> Unit) {
        if (isOnMain) {
            block()
            return
        }
        handler.post(block)
    }
}
