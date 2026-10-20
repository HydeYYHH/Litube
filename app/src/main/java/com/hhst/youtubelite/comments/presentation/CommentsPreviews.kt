package com.hhst.youtubelite.comments.presentation

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.hhst.youtubelite.comments.data.FixtureCommentsRepository
import com.hhst.youtubelite.comments.domain.CommentSort
import com.hhst.youtubelite.comments.domain.CommentsContent
import com.hhst.youtubelite.comments.domain.CommentsUiState
import com.hhst.youtubelite.comments.domain.CommentsOverlay

@Preview(name = "Comment actions", widthDp = 390, heightDp = 844, showBackground = true)
@Composable
private fun CommentActionsPreview() {
    CommentsPanel(CommentsUiState(content = CommentsContent.Ready(
        FixtureCommentsRepository.sampleThreads(CommentSort.Top),
    ), overlay = CommentsOverlay.ThreadMenu("c1")), "5.2K", {}, {})
}

@Preview(widthDp = 390, heightDp = 844, showBackground = true)
@Composable
private fun CommentsPreview() {
    CommentsPanel(CommentsUiState(content = CommentsContent.Ready(
        FixtureCommentsRepository.sampleThreads(CommentSort.Top),
    )), "5.2K", {}, {})
}

@Preview(widthDp = 1280, heightDp = 800, showBackground = true)
@Composable
private fun RepliesPreview() {
    CommentsPanel(CommentsUiState(content = CommentsContent.Ready(
        FixtureCommentsRepository.sampleThreads(CommentSort.Top),
    ), expanded = setOf("c1")), "5.2K", {}, {})
}

@Preview(widthDp = 390, heightDp = 844, showBackground = true)
@Composable
private fun EmptyCommentsPreview() {
    CommentsPanel(CommentsUiState(content = CommentsContent.Empty), "0", {}, {})
}
