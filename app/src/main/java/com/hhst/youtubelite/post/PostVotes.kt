package com.hhst.youtubelite.post

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.hhst.youtubelite.account.ChatWebSignInActivity
import com.hhst.youtubelite.data.media.ContentRepositories
import com.hhst.youtubelite.post.presentation.PostCardActionsViewModel
import com.hhst.youtubelite.post.presentation.PostCardInteractions
import com.hhst.youtubelite.ui.observeAccountChanges
import com.hhst.youtubelite.ui.renderNotice
import kotlinx.coroutines.launch

/** Register alongside the host's other launchers, before a deferred Home is started. */
class PostCardActionHost(private val activity: ComponentActivity) {
    private val authorization = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { }
    private val model by lazy {
        ViewModelProvider(activity, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = PostCardActionsViewModel(
                org.koin.java.KoinJavaComponent.getKoin().get<ContentRepositories>().posts) as T
        })[PostCardActionsViewModel::class.java]
    }

    fun bind() {
        activity.observeAccountChanges { model.reset() }
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { model.actionNotices.collect { activity.renderNotice(it) } }
                launch { model.authorizationRequests.collect {
                    authorization.launch(Intent(activity, ChatWebSignInActivity::class.java)
                        .putExtra(ChatWebSignInActivity.EXTRA_POST_AUTHORIZATION, true))
                } }
            }
        }
    }

    fun rate(identity: com.hhst.youtubelite.post.domain.PostIdentity, liked: Boolean) = model.onAction(identity,
        if (liked) com.hhst.youtubelite.post.domain.PostDetailAction.Like else com.hhst.youtubelite.post.domain.PostDetailAction.Dislike)

    @Composable fun interactions(): PostCardInteractions {
        val state by model.uiState.collectAsStateWithLifecycle()
        return PostCardInteractions(state, model::onAction) { urls, index ->
            com.hhst.youtubelite.gallery.GalleryActivity.open(activity, urls, index)
        }
    }
}
