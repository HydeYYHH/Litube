package com.hhst.youtubelite.ui.design

import androidx.annotation.StringRes
import com.hhst.youtubelite.R
import com.hhst.youtubelite.ui.MediaActionKind
import com.hhst.youtubelite.account.domain.AccountMenuAction
import com.hhst.youtubelite.channel.domain.ChannelSortOption
import com.hhst.youtubelite.channel.domain.ChannelShortMenuAction
import com.hhst.youtubelite.channel.domain.ChannelTab
import com.hhst.youtubelite.channel.domain.ChannelPlaylistSort
import com.hhst.youtubelite.comments.domain.CommentMenuAction
import com.hhst.youtubelite.explore.domain.ExploreCategory
import com.hhst.youtubelite.home.domain.HomeCatalog
import com.hhst.youtubelite.home.domain.HomeFeedbackKind
import com.hhst.youtubelite.home.domain.HomeDestination
import com.hhst.youtubelite.home.domain.FeedMenuAction
import com.hhst.youtubelite.library.domain.LibraryRow
import com.hhst.youtubelite.library.domain.HistoryMenuAction
import com.hhst.youtubelite.playlist.domain.PlaylistVideoAction
import com.hhst.youtubelite.post.domain.PostMenuAction
import com.hhst.youtubelite.search.domain.SearchResultType
import com.hhst.youtubelite.search.domain.SearchDestination
import com.hhst.youtubelite.search.domain.SearchUploadDate
import com.hhst.youtubelite.search.domain.SearchVideoMenuAction
import com.hhst.youtubelite.subscriptions.domain.SubscriptionsChip
import com.hhst.youtubelite.ui.components.MainTab
import com.hhst.youtubelite.ui.design.BrowseDestination
import com.hhst.youtubelite.watch.domain.WatchFeedbackKind
import com.hhst.youtubelite.watch.domain.WatchVideoMenuAction

/** Local UI labels; domain identities and server-provided titles remain unchanged. */
@get:StringRes
val MediaActionKind.labelResource: Int
    get() = when (this) {
        MediaActionKind.PlayNext -> R.string.playback_end_next
        MediaActionKind.Queue -> R.string.add_to_queue
        MediaActionKind.Download -> R.string.download
        MediaActionKind.OpenWith -> R.string.open_with
        MediaActionKind.Share -> R.string.share
        MediaActionKind.CopyLink -> R.string.ui_copy_link
    }

@get:StringRes
val AccountMenuAction.labelResource: Int
    get() = when (this) {
        AccountMenuAction.ViewChannel -> R.string.ui_view_channel
        AccountMenuAction.SwitchAccount -> R.string.ui_switch_account
        AccountMenuAction.Settings -> R.string.settings_title
        AccountMenuAction.About -> R.string.settings_about_title
        AccountMenuAction.SignOut -> R.string.ui_sign_out_2
    }

@get:StringRes
val ChannelSortOption.labelResource: Int
    get() = when (this) {
        ChannelSortOption.Latest -> R.string.ui_latest
        ChannelSortOption.Popular -> R.string.ui_popular
        ChannelSortOption.Oldest -> R.string.ui_oldest
    }

@get:StringRes
val ChannelShortMenuAction.labelResource: Int
    get() = when (this) {
        ChannelShortMenuAction.NotInterested -> R.string.ui_not_interested
        ChannelShortMenuAction.SendFeedback -> R.string.ui_send_feedback
    }

@get:StringRes
val ChannelTab.labelResource: Int
    get() = when (this) {
        ChannelTab.Home -> R.string.ui_home
        ChannelTab.Videos -> R.string.ui_videos
        ChannelTab.Shorts -> R.string.ui_shorts
        ChannelTab.Live -> R.string.ui_live
        ChannelTab.Playlists -> R.string.ui_playlists
        ChannelTab.Posts -> R.string.ui_posts
        ChannelTab.Search -> R.string.a11y_search
    }

@get:StringRes
val ChannelPlaylistSort.labelResource: Int
    get() = when (this) {
        ChannelPlaylistSort.DateAdded -> R.string.ui_date_added_newest
        ChannelPlaylistSort.LastVideo -> R.string.ui_last_video_added
    }

@get:StringRes
val CommentMenuAction.labelResource: Int
    get() = when (this) {
        CommentMenuAction.Reply -> R.string.comments_composer_reply
        CommentMenuAction.Report -> R.string.ui_report
        CommentMenuAction.Edit -> R.string.ui_edit
        CommentMenuAction.Delete -> R.string.comments_delete_title
    }

@get:StringRes
val ExploreCategory.labelResource: Int
    get() = when (this) {
        ExploreCategory.Music -> R.string.ui_music
        ExploreCategory.Gaming -> R.string.ui_gaming
        ExploreCategory.News -> R.string.ui_news
        ExploreCategory.Sports -> R.string.ui_sports
    }

@get:StringRes
val HomeCatalog.labelResource: Int
    get() = when (this) {
        HomeCatalog.Popular -> R.string.download_filter_all
        HomeCatalog.MusicCharts -> R.string.home_music_charts
        HomeCatalog.Gaming -> R.string.ui_gaming
        HomeCatalog.Sports -> R.string.ui_sports
        HomeCatalog.News -> R.string.ui_news
    }

@get:StringRes
val HomeFeedbackKind.labelResource: Int
    get() = when (this) {
        HomeFeedbackKind.NotInterested -> R.string.ui_tell_us_why
        HomeFeedbackKind.DontRecommend -> R.string.ui_tell_us_why
        HomeFeedbackKind.SendFeedback -> R.string.ui_send_feedback
        HomeFeedbackKind.Report -> R.string.ui_report_post
    }

@get:StringRes
val HomeDestination.labelResource: Int
    get() = when (this) {
        HomeDestination.Home -> R.string.ui_home
        HomeDestination.Shorts -> R.string.ui_shorts
        HomeDestination.Subscriptions -> R.string.subscriptions_title
        HomeDestination.You -> R.string.ui_you
    }

@get:StringRes
val FeedMenuAction.labelResource: Int
    get() = when (this) {
        FeedMenuAction.Queue -> R.string.add_to_queue
        FeedMenuAction.Download -> R.string.download
        FeedMenuAction.WatchLater -> R.string.ui_save_to_watch_later
        FeedMenuAction.SaveToPlaylist -> R.string.save_to_playlist
        FeedMenuAction.Share -> R.string.share
        FeedMenuAction.NotInterested -> R.string.ui_not_interested
        FeedMenuAction.DontRecommend -> R.string.ui_don_t_recommend_channel
        FeedMenuAction.SendFeedback -> R.string.ui_send_feedback
        FeedMenuAction.ShowFewerShorts -> R.string.ui_show_fewer_shorts
        FeedMenuAction.Report -> R.string.ui_report
        FeedMenuAction.SaveToLibrary -> R.string.ui_save_to_library
    }

@get:StringRes
val LibraryRow.labelResource: Int
    get() = when (this) {
        LibraryRow.History -> R.string.download_history
        LibraryRow.Playlists -> R.string.ui_playlists
        LibraryRow.WatchLater -> R.string.ui_watch_later
        LibraryRow.LikedVideos -> R.string.ui_liked_videos
        LibraryRow.Downloads -> R.string.download_channel_name
        LibraryRow.Queue -> R.string.queue
    }

@get:StringRes
val HistoryMenuAction.labelResource: Int
    get() = when (this) {
        HistoryMenuAction.WatchLater -> R.string.ui_save_to_watch_later
        HistoryMenuAction.SaveToPlaylist -> R.string.save_to_playlist
        HistoryMenuAction.Share -> R.string.share
        HistoryMenuAction.RemoveFromHistory -> R.string.ui_remove_from_watch_history
    }

@get:StringRes
val PlaylistVideoAction.labelResource: Int
    get() = when (this) {
        PlaylistVideoAction.WatchLater -> R.string.ui_save_to_watch_later
        PlaylistVideoAction.Save -> R.string.save_to_playlist
    }

@get:StringRes
val PostMenuAction.labelResource: Int
    get() = when (this) {
        PostMenuAction.Report -> R.string.ui_report
    }

@get:StringRes
val SearchResultType.labelResource: Int
    get() = when (this) {
        SearchResultType.All -> R.string.download_filter_all
        SearchResultType.Shorts -> R.string.ui_shorts
        SearchResultType.Channels -> R.string.info_channels
        SearchResultType.Playlists -> R.string.ui_playlists
    }

@get:StringRes
val SearchDestination.labelResource: Int
    get() = when (this) {
        SearchDestination.Home -> R.string.ui_home
        SearchDestination.Shorts -> R.string.ui_shorts
        SearchDestination.Subscriptions -> R.string.subscriptions_title
        SearchDestination.You -> R.string.ui_you
    }

@get:StringRes
val SearchUploadDate.labelResource: Int
    get() = when (this) {
        SearchUploadDate.Anytime -> R.string.ui_anytime
        SearchUploadDate.Today -> R.string.ui_today
        SearchUploadDate.ThisWeek -> R.string.ui_this_week
        SearchUploadDate.ThisMonth -> R.string.ui_this_month
        SearchUploadDate.ThisYear -> R.string.ui_this_year
    }

@get:StringRes
val SearchVideoMenuAction.labelResource: Int
    get() = when (this) {
        SearchVideoMenuAction.Queue -> R.string.add_to_queue
        SearchVideoMenuAction.Download -> R.string.download
        SearchVideoMenuAction.WatchLater -> R.string.ui_save_to_watch_later
        SearchVideoMenuAction.SaveToPlaylist -> R.string.save_to_playlist
        SearchVideoMenuAction.Share -> R.string.share
        SearchVideoMenuAction.NotInterested -> R.string.ui_not_interested
        SearchVideoMenuAction.DontRecommend -> R.string.ui_don_t_recommend_channel
    }

@get:StringRes
val SubscriptionsChip.labelResource: Int
    get() = when (this) {
        SubscriptionsChip.All -> R.string.download_filter_all
        SubscriptionsChip.Today -> R.string.ui_today
        SubscriptionsChip.ContinueWatching -> R.string.ui_continue_watching
        SubscriptionsChip.Unwatched -> R.string.ui_unwatched
        SubscriptionsChip.Live -> R.string.ui_live
        SubscriptionsChip.Posts -> R.string.ui_posts
    }

@get:StringRes
val MainTab.labelResource: Int
    get() = when (this) {
        MainTab.Home -> R.string.ui_home
        MainTab.Shorts -> R.string.ui_shorts
        MainTab.Subscriptions -> R.string.subscriptions_title
        MainTab.You -> R.string.ui_you
    }

@get:StringRes
val BrowseDestination.labelResource: Int
    get() = when (this) {
        BrowseDestination.Home -> R.string.ui_home
        BrowseDestination.Shorts -> R.string.ui_shorts
        BrowseDestination.Subscriptions -> R.string.subscriptions_title
        BrowseDestination.You -> R.string.ui_you
    }

@get:StringRes
val WatchFeedbackKind.labelResource: Int
    get() = when (this) {
        WatchFeedbackKind.Report -> R.string.ui_report_video
        WatchFeedbackKind.NotInterested -> R.string.ui_tell_us_why
        WatchFeedbackKind.DontRecommend -> R.string.ui_tell_us_why
    }

@get:StringRes
val WatchVideoMenuAction.labelResource: Int
    get() = when (this) {
        WatchVideoMenuAction.Queue -> R.string.add_to_queue
        WatchVideoMenuAction.Download -> R.string.download
        WatchVideoMenuAction.WatchLater -> R.string.ui_save_to_watch_later
        WatchVideoMenuAction.SaveToPlaylist -> R.string.save_to_playlist
        WatchVideoMenuAction.Share -> R.string.share
        WatchVideoMenuAction.NotInterested -> R.string.ui_not_interested
        WatchVideoMenuAction.DontRecommend -> R.string.ui_don_t_recommend_channel
    }

val HomeFeedbackKind.reasonResources: List<Int>
    get() = when (this) {
        HomeFeedbackKind.NotInterested -> listOf(R.string.ui_i_m_not_interested_in_this_video, R.string.ui_it_s_repetitive, R.string.ui_i_ve_already_seen_it)
        HomeFeedbackKind.DontRecommend -> listOf(R.string.ui_i_don_t_like_this_channel, R.string.ui_this_channel_is_misleading, R.string.ui_i_ve_already_seen_enough)
        HomeFeedbackKind.SendFeedback -> listOf(R.string.ui_not_interested, R.string.ui_inappropriate, R.string.ui_misleading, R.string.ui_something_else)
        HomeFeedbackKind.Report -> listOf(R.string.ui_spam_or_misleading, R.string.ui_harmful_content, R.string.ui_something_else)
    }

val WatchFeedbackKind.reasonResources: List<Int>
    get() = when (this) {
        WatchFeedbackKind.Report -> listOf(R.string.ui_spam_or_misleading, R.string.ui_harmful_content, R.string.ui_something_else)
        WatchFeedbackKind.NotInterested -> listOf(R.string.ui_i_m_not_interested_in_this_video, R.string.ui_it_s_repetitive, R.string.ui_i_ve_already_seen_it)
        WatchFeedbackKind.DontRecommend -> listOf(R.string.ui_i_don_t_like_this_channel, R.string.ui_this_channel_is_misleading, R.string.ui_i_ve_already_seen_enough)
    }
