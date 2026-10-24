package com.hhst.youtubelite.comments.presentation

import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.hhst.youtubelite.account.SignInFlow
import kotlinx.coroutines.launch
import com.hhst.youtubelite.ui.renderNotice

fun ComponentActivity.bindCommentNotices(model: CommentsViewModel) {
    lifecycleScope.launch {
        repeatOnLifecycle(Lifecycle.State.STARTED) {
            model.actionNotices.collect { notice ->
                renderNotice(notice)
            }
        }
    }
}
