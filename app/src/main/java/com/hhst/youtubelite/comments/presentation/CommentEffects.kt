package com.hhst.youtubelite.comments.presentation

import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.hhst.youtubelite.account.SignInFlow
import com.hhst.youtubelite.comments.domain.CommentNotice
import kotlinx.coroutines.launch

fun ComponentActivity.bindCommentNotices(model: CommentsViewModel) {
    lifecycleScope.launch {
        repeatOnLifecycle(Lifecycle.State.STARTED) {
            model.actionNotices.collect { notice ->
                when (notice) {
                    CommentNotice.SignIn -> SignInFlow.open(this@bindCommentNotices)
                    is CommentNotice.Message -> Toast.makeText(this@bindCommentNotices, notice.text, Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
