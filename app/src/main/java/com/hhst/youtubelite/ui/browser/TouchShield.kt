package com.hhst.youtubelite.ui.browser

import android.view.MotionEvent
import android.view.ViewConfiguration
import android.webkit.WebView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.testTag
import com.hhst.youtubelite.browser.PageOrigin
import com.hhst.youtubelite.browser.TransientUi
import kotlin.math.hypot

/** Routes modal interaction over the native player. */
@Composable
@OptIn(ExperimentalComposeUiApi::class)
internal fun TouchShield(webView: WebView, ui: TransientUi, currentOrigin: () -> PageOrigin,
    onOverlapChanged: (Boolean) -> Unit, onDismiss: () -> Unit) {
    val dismiss = rememberUpdatedState(onDismiss)
    val latestView = rememberUpdatedState(webView)
    val latestUi = rememberUpdatedState(ui)
    val origin = rememberUpdatedState(currentOrigin)
    val overlap = rememberUpdatedState(onOverlapChanged)
    val gesture = remember { TouchGesture() }
    val slop = remember(webView) { ViewConfiguration.get(webView.context).scaledTouchSlop }
    fun reportOverlap() {
        val scale = webView.width / ui.viewportWidth
        val location = IntArray(2)
        webView.getLocationInWindow(location)
        val left = (gesture.windowPosition.x - location[0]) / scale
        val top = (gesture.windowPosition.y - location[1]) / scale
        val right = left + gesture.width / scale
        val bottom = top + gesture.height / scale
        overlap.value(ui.origin == origin.value() && scale.isFinite() && scale > 0 &&
            ui.regions.any { it.left < right && it.right > left && it.top < bottom && it.bottom > top })
    }
    SideEffect { reportOverlap() }
    Box(Modifier.fillMaxSize()
        .testTag("web-modal")
        .onGloballyPositioned {
            gesture.windowPosition = it.positionInWindow()
            gesture.width = it.size.width
            gesture.height = it.size.height
            reportOverlap()
        }
        .pointerInteropFilter { event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                val location = IntArray(2)
                webView.getLocationInWindow(location)
                gesture.view = webView
                gesture.origin = ui.origin
                gesture.offset = gesture.windowPosition - Offset(location[0].toFloat(), location[1].toFloat())
                gesture.start = Offset(event.x, event.y)
                gesture.moved = false
                val scale = webView.width / ui.viewportWidth
                gesture.forward = ui.origin == origin.value() && scale.isFinite() && scale > 0 &&
                    ui.contains((event.x + gesture.offset.x) / scale, (event.y + gesture.offset.y) / scale)
            }
            val valid = gesture.view === latestView.value && gesture.origin == origin.value() && gesture.origin == latestUi.value.origin
            if (gesture.forward) {
                val copy = MotionEvent.obtain(event)
                copy.offsetLocation(gesture.offset.x, gesture.offset.y)
                if (!valid) copy.action = MotionEvent.ACTION_CANCEL
                gesture.view?.dispatchTouchEvent(copy)
                copy.recycle()
                if (!valid) { gesture.forward = false; gesture.moved = true }
            } else {
                if (event.pointerCount > 1 || hypot(event.x - gesture.start.x, event.y - gesture.start.y) > slop) gesture.moved = true
                if (event.actionMasked == MotionEvent.ACTION_UP && valid && !gesture.moved) dismiss.value()
            }
            true
        })
}

private class TouchGesture {
    var windowPosition = Offset.Zero
    var width = 0
    var height = 0
    var offset = Offset.Zero
    var start = Offset.Zero
    var view: WebView? = null
    var origin: PageOrigin? = null
    var forward = false
    var moved = false
}
