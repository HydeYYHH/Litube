package com.hhst.youtubelite.core

import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.TextView
import android.widget.ListView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.AssumptionViolatedException

internal object NativeChoicePicker {
    private fun find(label: String): View {
        if (Build.VERSION.SDK_INT < 29) throw AssumptionViolatedException("Native window inspection requires API29")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = SystemClock.uptimeMillis() + 5000
        do {
            var found: View? = null
            instrumentation.runOnMainSync {
                fun visit(view: View) {
                    if (view is TextView && view.isShown && view.text.toString() == label) found = view
                    if (view is ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index))
                }
                WindowInspector.getGlobalWindowViews().forEach(::visit)
            }
            found?.let { return it }
            SystemClock.sleep(50)
        } while (SystemClock.uptimeMillis() < deadline)
        DeviceEvidence.captureScene("missing-native-option")
        val windows = mutableListOf<String>()
        instrumentation.runOnMainSync {
            fun visit(view: View) {
                if (view is TextView) windows += "${view.javaClass.simpleName}: ${view.text} (${view.isShown})"
                if (view is ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index))
            }
            WindowInspector.getGlobalWindowViews().forEach(::visit)
        }
        throw AssertionError("No visible native option: $label; windows=$windows")
    }

    fun assertVisible(label: String) { find(label) }

    fun choose(label: String) {
        val option = find(label)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val list = option.parent as? ListView ?: throw AssertionError("Native option has no choice list: $label")
            val position = list.getPositionForView(option)
            check(list.performItemClick(option, position, list.adapter.getItemId(position))) {
                "Native option is not selectable: $label"
            }
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    fun back() { DeviceEvidence.shell("input keyevent KEYCODE_BACK") }
}
