package com.hhst.youtubelite.ui

import android.content.Context
import com.hhst.youtubelite.R

/** Translates legacy action feedback while preserving the original diagnostic text. */
internal fun Context.noticeText(text: String, failure: Boolean = false): String {
    val resource = when (text) {
        "The sign-in code expired. Get a new one." -> R.string.signin_error_expired
        "Sign-in was declined in the browser." -> R.string.signin_error_declined
        "No connection. Check the network and try again." -> R.string.signin_error_connection
        "YouTube refused the sign-in. Try again." -> R.string.signin_error_refused
        "Sign-in failed. Try again." -> R.string.signin_error_failed
        "Check your connection and try again." -> R.string.ui_check_your_connection_and_try_again
        "Comment posted." -> R.string.ui_comment_posted
        "Comment reported." -> R.string.ui_comment_reported
        "Could not publish media on the local network" -> R.string.ui_could_not_publish_media_on_the_local_network
        "Couldn't clear watch history. Try again." -> R.string.ui_couldn_t_clear_watch_history_try_again
        "Couldn't confirm that your message was sent. Your draft has been kept." -> R.string.ui_couldn_t_confirm_that_your_message_was_sent_your_draft_has_been_kept
        "Couldn't create this playlist. Try again." -> R.string.ui_couldn_t_create_this_playlist_try_again
        "Couldn't load the full playlist. Refresh and try again." -> R.string.ui_couldn_t_load_the_full_playlist_refresh_and_try_again
        "Couldn't load the full playlist. Try again." -> R.string.ui_couldn_t_load_the_full_playlist_try_again
        "Couldn't open this link." -> R.string.ui_couldn_t_open_this_link
        "Couldn't refresh channel. Try again." -> R.string.ui_couldn_t_refresh_channel_try_again
        "Couldn't refresh playlist. Try again." -> R.string.ui_couldn_t_refresh_playlist_try_again
        "Couldn't refresh results. Try again." -> R.string.ui_couldn_t_refresh_results_try_again
        "Couldn't remove this entry. Try again." -> R.string.ui_couldn_t_remove_this_entry_try_again
        "Couldn't restore this entry." -> R.string.ui_couldn_t_restore_this_entry
        "Couldn't save this playlist. Try again." -> R.string.ui_couldn_t_save_this_playlist_try_again
        "Couldn't save this video. Try again." -> R.string.ui_couldn_t_save_this_video_try_again
        "Couldn't update recommendations. Try again." -> R.string.ui_couldn_t_update_recommendations_try_again
        "Couldn't update this playlist. Try again." -> R.string.ui_couldn_t_update_this_playlist_try_again
        "Couldn't update this subscription. Try again." -> R.string.ui_couldn_t_update_this_subscription_try_again
        "Couldn't update this video. Try again." -> R.string.ui_couldn_t_update_this_video_try_again
        "Couldn't update watch history. Try again." -> R.string.ui_couldn_t_update_watch_history_try_again
        "Live streams can't be seeked to a timestamp." -> R.string.ui_live_streams_can_t_be_seeked_to_a_timestamp
        "Newest comments aren't offered here. Showing top comments." -> R.string.ui_newest_comments_aren_t_offered_here_showing_top_comments
        "Removed from recommendations" -> R.string.ui_removed_from_recommendations
        "Saved to Watch later" -> R.string.ui_saved_to_watch_later
        "Saved to your library" -> R.string.ui_saved_to_your_library
        "Sign in to continue." -> R.string.ui_sign_in_to_continue
        "The action was cancelled." -> R.string.ui_the_action_was_cancelled
        "The server did not confirm this change. Please refresh before trying again." -> R.string.ui_the_server_did_not_confirm_this_change_please_refresh_before_trying_ag
        "The server did not confirm this removal. Please refresh before trying again." -> R.string.ui_the_server_did_not_confirm_this_removal_please_refresh_before_trying_a
        "The server offered no removal for this entry." -> R.string.ui_the_server_offered_no_removal_for_this_entry
        "The server response could not be read. Please try again." -> R.string.ui_the_server_response_could_not_be_read_please_try_again
        "This action could not be completed." -> R.string.ui_this_action_could_not_be_completed
        "This action is not available." -> R.string.ui_this_action_is_not_available
        "This stream does not allow this message. Check its chat restrictions." -> R.string.ui_this_stream_does_not_allow_this_message_check_its_chat_restrictions
        "Verification finished. Retrying…" -> R.string.verification_completed
        "Watch history cleared." -> R.string.ui_watch_history_cleared
        "Watch history paused." -> R.string.ui_watch_history_paused
        "Watch history resumed." -> R.string.ui_watch_history_resumed
        "YouTube could not complete this action." -> R.string.ui_youtube_could_not_complete_this_action
        "YouTube could not send this message. Your draft has been kept." -> R.string.ui_youtube_could_not_send_this_message_your_draft_has_been_kept
        "YouTube is limiting messages. Wait before sending again." -> R.string.ui_youtube_is_limiting_messages_wait_before_sending_again
        "YouTube is limiting requests. Please try again later." -> R.string.ui_youtube_is_limiting_requests_please_try_again_later
        "YouTube requires verification before you can continue." -> R.string.ui_youtube_requires_verification_before_you_can_continue
        "sign_in_required" -> R.string.ui_sign_in_to_continue
        "unconfirmed" -> R.string.ui_the_server_did_not_confirm_this_change_please_refresh_before_trying_ag
        "rate_limited" -> R.string.ui_youtube_is_limiting_requests_please_try_again_later
        "refused" -> R.string.ui_youtube_could_not_complete_this_action
        "verification" -> R.string.ui_youtube_requires_verification_before_you_can_continue
        "transport" -> R.string.ui_check_your_connection_and_try_again
        "unparsed_response" -> R.string.ui_the_server_response_could_not_be_read_please_try_again
        "invalid_request" -> R.string.ui_this_action_could_not_be_completed
        "cancelled" -> R.string.ui_the_action_was_cancelled
        "unsupported" -> R.string.ui_this_action_is_not_available
        else -> null
    }
    return if (resource != null) getString(resource)
    else if (failure) getString(R.string.ui_something_went_wrong_please_try_again) else text
}
