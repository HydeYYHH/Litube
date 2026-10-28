package com.hhst.youtubelite.shorts

import android.os.Parcel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.comments.data.FixtureCommentsRepository
import com.hhst.youtubelite.comments.domain.CommentAction
import com.hhst.youtubelite.comments.domain.CommentSort
import com.hhst.youtubelite.comments.presentation.CommentsViewModel
import com.hhst.youtubelite.shorts.presentation.RecentCommentsViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentCommentsStateAndroidTest {
    @Test fun fiftyPanelsKeepOnlyFourRestorableStates() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val state = SavedStateHandle()
            val owner = RecentCommentsViewModel(state)
            val store = ViewModelStore().apply { put("panels", owner) }
            val restoredStore = ViewModelStore()
            try {
                owner.updateSession(1)
                repeat(50) { index ->
                    owner.getOrCreate("short-$index") { handle ->
                        CommentsViewModel(FixtureCommentsRepository(), handle, "short-$index")
                    }.onAction(CommentAction.SelectSort(CommentSort.Newest))
                }
                assertEquals((46..49).map { "short-$it" }.toSet(), owner.models.keys)
                val saved = owner.savePanels()
                assertEquals((46..49).map { "short-$it" }, saved.getStringArrayList("ids"))
                assertEquals(5, saved.keySet().size)
                val registered = state.savedStateProvider().saveState()
                assertTrue(registered.keySet().size <= 3)
                val parcel = Parcel.obtain()
                try {
                    parcel.writeBundle(saved)
                    assertTrue("Panel state must remain bounded", parcel.dataSize() < 128 * 1024)
                } finally { parcel.recycle() }
                val restoredState = SavedStateHandle(mapOf(
                    "commentPanels" to saved, "commentSessionGeneration" to 1L,
                ))
                val restored = RecentCommentsViewModel(restoredState)
                restoredStore.put("panels", restored)
                assertTrue(restored.models.isEmpty())
                val model = restored.getOrCreate("short-47") { handle ->
                    CommentsViewModel(FixtureCommentsRepository(), handle, "short-47")
                }
                assertEquals(CommentSort.Newest, model.uiState.value.sort)
                assertEquals(1, restored.models.size)
                restored.updateSession(2)
                val reset = restored.getOrCreate("short-48") { handle ->
                    CommentsViewModel(FixtureCommentsRepository(), handle, "short-48")
                }
                assertEquals(CommentSort.Top, reset.uiState.value.sort)
            } finally { store.clear(); restoredStore.clear() }
        }
    }
}
