package com.hhst.youtubelite.post.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.hhst.youtubelite.post.domain.PostPollData
import com.hhst.youtubelite.ui.design.AppText
import com.hhst.youtubelite.ui.design.Artwork
import com.hhst.youtubelite.ui.design.DesignTokens

@Composable
fun PostPollChoices(poll: PostPollData, pending: Boolean, modifier: Modifier = Modifier,
    pendingOption: Int? = null, onVote: (Int) -> Unit) {
    Column(modifier.fillMaxWidth().testTag("post-poll"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        poll.options.forEachIndexed { index, option ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .background(if (option.selected) DesignTokens.Ghost else DesignTokens.Background)
                .border(1.dp, DesignTokens.Ghost, RoundedCornerShape(12.dp))
                .clickable(enabled = !pending, role = Role.RadioButton) { onVote(index) }
                .semantics { selected = option.selected }.testTag("post-poll-option-$index").padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                option.image?.let { Artwork(it, Modifier.size(64.dp).clip(RoundedCornerShape(8.dp))) }
                AppText(option.text, Modifier.weight(1f), size = 15, line = 21)
                // Keep the same trailing slot before, during and after the vote.
                Box(Modifier.width(48.dp).height(24.dp), contentAlignment = Alignment.CenterEnd) {
                    if (pendingOption == index) com.hhst.youtubelite.ui.design.ActionProgress()
                    else if (poll.voted) option.percentage?.let { AppText(it, size = 14, line = 20) }
                }
            }
        }
        if (poll.total.isNotBlank()) AppText(poll.total, size = 12, line = 18, color = DesignTokens.Secondary)
    }
}
