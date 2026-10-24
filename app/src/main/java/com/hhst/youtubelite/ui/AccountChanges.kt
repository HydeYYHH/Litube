package com.hhst.youtubelite.ui

import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.hhst.youtubelite.data.media.MediaSession
import kotlinx.coroutines.launch

fun ComponentActivity.observeAccountChanges(refresh: () -> Unit) {
    var previous = MediaSession.snapshot().generation
    lifecycleScope.launch {
        repeatOnLifecycle(Lifecycle.State.STARTED) {
            MediaSession.changes.collect { current ->
                if (current != previous) {
                    previous = current
                    refresh()
                }
            }
        }
    }
}
