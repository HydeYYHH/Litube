package com.hhst.youtubelite.player.service

import com.hhst.youtubelite.diagnostics.*

import android.app.Notification
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.os.Build
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaStyleNotificationHelper
import com.hhst.youtubelite.watch.WatchActivity
import com.hhst.youtubelite.R
import com.hhst.youtubelite.core.YoutubeThumbnail
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import androidx.core.graphics.scale

/**
 * Forwards transport through [PlaybackCommandRouter] so notification/headset/AVRCP
 * next/previous use the app queue — the wrapped player holds a single MediaItem,
 * where seekToNext/Previous would be no-ops.
 */
@UnstableApi
class PlaybackService : Service() {

    private val binder = LocalBinder(this)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val artworkScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val artworkRequests = LatestArtworkRequest<String, Bitmap>(
        scope = artworkScope,
        load = { fetchThumbnail(it) },
        publish = { url, art ->
            if (destroyed || hidden) {
                if (!art.isRecycled) art.recycle()
            } else {
                val previous = lastArt
                lastArt = art
                lastArtUrl = url
                val playing = (routingPlayer ?: player)?.isPlaying == true
                notificationManager?.notify(NOTIFICATION_ID, buildNotification(playing))
                if (previous != null && previous !== art && !previous.isRecycled) previous.recycle()
            }
        },
        discard = { if (!it.isRecycled) it.recycle() },
    )

    private var notificationManager: NotificationManager? = null
    private var session: MediaSession? = null
    private var player: Player? = null
    private var routingPlayer: QueueRoutingPlayer? = null
    private var lastTitle: String = ""
    private var lastAuthor: String? = null
    private var lastArt: Bitmap? = null
    private var lastArtUrl: String? = null
    @Volatile
    private var destroyed = false

    /**
     * Set between [hide] and the next [show]: stopSelf() reaches onDestroy only after
     * the main queue drains, so a queued thumbnail post could resurrect the notification.
     * Cleared by show() (unlike [destroyed]) so a re-bind racing old teardown accepts fresh state.
     */
    @Volatile
    private var hidden = false

    // A bound service can hide and show again with the same wrapper before queued commands run.
    private var commandEpoch = 0L

    private var queueHasNext = false
    private var queueHasPrevious = false
    private var lastShowAsPlaying = false
    private val commandListeners = CopyOnWriteArrayList<Player.Listener>()

    override fun onBind(intent: Intent?): IBinder? {
        AppLog.event(AppLog.Category.APP, "playback_service_bound")
        return binder
    }

    // Android can retain the published Binder in native code after onDestroy;
    // a nested class avoids an implicit outer reference that would retain the service.
    class LocalBinder internal constructor(service: PlaybackService) : Binder() {
        private val service = WeakReference(service)

        fun getService(): PlaybackService? = service.get()

        internal fun clear() = service.clear()
    }

    override fun onCreate() {
        super.onCreate()
        destroyed = false
        AppLog.event(AppLog.Category.APP, "playback_service_created")
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.player_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.player_channel_desc)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            notificationManager?.createNotificationChannel(channel)
        }
        startForegroundSafely(placeholderNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AppLog.event(AppLog.Category.APP, "playback_service_command", mapOf("start_id" to startId, "flags" to flags,
            "action" to intent?.action?.takeIf { it in setOf(ACTION_PLAY_PAUSE, ACTION_PREVIOUS, ACTION_NEXT, ACTION_CANCELLED) }))
        if (session == null) startForegroundSafely(placeholderNotification())
        if (intent?.action != null) {
            when (intent.action) {
                ACTION_PLAY_PAUSE -> dispatchCommand(command = PlaybackCommandRouter::playPause)
                ACTION_PREVIOUS -> dispatchCommand(command = PlaybackCommandRouter::previous)
                ACTION_NEXT -> dispatchCommand(command = PlaybackCommandRouter::next)
                // Delete intent fires only while not ongoing: swiping the paused
                // notification away is the user's stop signal.
                ACTION_CANCELLED -> {
                    PlaybackCommandRouter.pause()
                    hide()
                    return START_NOT_STICKY
                }
            }
            // No refresh() here: onIsPlayingChanged → refresh reads the real
            // post-command state; a refresh here would re-post the old one.
        }
        return START_NOT_STICKY
    }

    /** Capture ownership when received; the router checks it again before execution. */
    private fun dispatchCommand(owner: Player? = routingPlayer, command: (() -> Boolean) -> Unit) {
        val epoch = commandEpoch
        command { owner != null && routingPlayer === owner && !hidden && !destroyed && commandEpoch == epoch }
    }

    /**
     * Session player that routes the app-level transport (queue next/previous,
     * play/pause/stop while casting) instead of driving the wrapped local player.
     */
    private inner class QueueRoutingPlayer(wrapped: Player) : ForwardingPlayer(wrapped) {
        private fun dispatch(command: (() -> Boolean) -> Unit) {
            dispatchCommand(this, command)
        }

        override fun play() = dispatch(PlaybackCommandRouter::play)
        override fun pause() = dispatch(PlaybackCommandRouter::pause)
        override fun stop() = dispatch(PlaybackCommandRouter::stop)
        override fun seekToNext() = dispatch(PlaybackCommandRouter::next)
        override fun seekToNextMediaItem() = dispatch(PlaybackCommandRouter::next)
        override fun seekToPrevious() = dispatch(PlaybackCommandRouter::previous)
        override fun seekToPreviousMediaItem() = dispatch(PlaybackCommandRouter::previous)

        // Headset/system media controls derive their buttons from this set;
        // single-item ExoPlayer omits next/previous, so the app queue must add them.
        override fun getAvailableCommands(): Player.Commands =
            PlaybackQueueCommands.apply(super.getAvailableCommands(), queueHasNext, queueHasPrevious)

        override fun addListener(listener: Player.Listener) {
            commandListeners.add(listener)
            super.addListener(listener)
        }

        override fun removeListener(listener: Player.Listener) {
            commandListeners.remove(listener)
            super.removeListener(listener)
        }

        fun dispatchAvailableCommandsChanged() {
            val cmds = availableCommands
            commandListeners.forEach { it.onAvailableCommandsChanged(cmds) }
        }
    }

    /** Idempotent; called from the engine on every playback start before [show]. */
    fun attach(player: Player) {
        if (destroyed) return
        if (this.player === player && session != null) return
        commandEpoch++
        this.player = player
        session?.release()
        commandListeners.clear()
        routingPlayer = QueueRoutingPlayer(player)
        // System media card (QS / volume dialog) taps must open the app —
        // the notification's contentIntent does not cover them.
        val sessionActivity = PendingIntent.getActivity(
            this, 0,
            Intent(this, WatchActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        session = MediaSession.Builder(this, routingPlayer!!)
            .setSessionActivity(sessionActivity)
            .build()
    }

    fun updateQueueNavigation(hasNext: Boolean, hasPrevious: Boolean) {
        if (queueHasNext == hasNext && queueHasPrevious == hasPrevious) return
        queueHasNext = hasNext
        queueHasPrevious = hasPrevious
        routingPlayer?.dispatchAvailableCommandsChanged()
        refresh()
    }

    fun show(title: String, author: String?, thumbnailUrl: String?) {
        if (destroyed) return
        if (session == null) return
        hidden = false
        lastTitle = title
        lastAuthor = author
        // A new video without a thumbnail must not keep the previous one's art.
        if (thumbnailUrl == null && lastArt != null) recycleArt()
        // The wrapped player reflects the cast receiver while delegating.
        val playing = lastShowAsPlaying || (routingPlayer ?: player)?.isPlaying == true
        lastShowAsPlaying = playing
        startForegroundSafely(buildNotification(playing))
        if (thumbnailUrl == null || (thumbnailUrl == lastArtUrl && lastArt != null)) {
            artworkRequests.cancel()
            return
        }
        artworkRequests.submit(thumbnailUrl)
    }

    fun refresh(showAsPlaying: Boolean? = null) {
        // hidden: a queued notification action can still deliver between hide()
        // and onDestroy — re-posting here would resurrect the cancelled one.
        if (destroyed || hidden) return
        if (session == null) return
        if (showAsPlaying != null) lastShowAsPlaying = showAsPlaying
        val p = routingPlayer ?: player ?: return
        val playing = lastShowAsPlaying
        p.mediaMetadata.let { meta ->
            if (meta.title != null) lastTitle = meta.title.toString()
            if (meta.artist != null) lastAuthor = meta.artist.toString()
        }
        notificationManager?.notify(NOTIFICATION_ID, buildNotification(playing))
    }

    fun hide() {
        // Reject late thumbnail posts now (see [hidden]); removeCallbacks in
        // onDestroy may run after a queued post has already fired.
        hidden = true
        commandEpoch++
        artworkRequests.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        notificationManager?.cancel(NOTIFICATION_ID)
        recycleArt()
        stopSelf()
    }

    /**
     * Notification tap returns to the media's own context: Shorts audio reopens
     * the Shorts feed; a Short must never be restored as regular watch playback.
     */
    private fun resumeIntent(): Intent {
        val shorts = runCatching {
            org.koin.java.KoinJavaComponent.getKoin()
                .get<com.hhst.youtubelite.player.PlayerViewModel>()
        }.getOrNull()?.takeIf {
            it.currentMediaKind == com.hhst.youtubelite.player.MediaKind.SHORTS
        }
        return if (shorts != null) {
            com.hhst.youtubelite.shorts.ShortsActivity.intent(this, shorts.uiState.value.videoId)
        } else {
            Intent(this, WatchActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
        }
    }

    private fun buildNotification(isPlaying: Boolean): Notification? {
        val current = session ?: return null
        val contentIntent = PendingIntent.getActivity(
            this, 101,
            resumeIntent(),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(lastTitle)
            .setContentText(lastAuthor)
            .setLargeIcon(lastArt)
            .setContentIntent(contentIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            // Swipe-dismiss while playing would orphan the audio; paused, the
            // notification is the user's dismissible "I'm done" handle
            // (official-app behavior) — dismissing stops playback.
            .setOngoing(isPlaying)
            .setDeleteIntent(
                PendingIntent.getService(
                    this, ACTION_CANCELLED.hashCode(),
                    Intent(this, PlaybackService::class.java).apply { action = ACTION_CANCELLED },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )

        var playPauseIndex = 0
        if (queueHasPrevious) {
            builder.addAction(
                R.drawable.ic_previous,
                getString(R.string.action_previous),
                sessionButtonIntent(Player.COMMAND_SEEK_TO_PREVIOUS),
            )
            playPauseIndex = 1
        }
        builder.addAction(
            if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
            getString(if (isPlaying) R.string.action_pause else R.string.action_play),
            sessionButtonIntent(Player.COMMAND_PLAY_PAUSE),
        )
        if (queueHasNext) {
            builder.addAction(
                R.drawable.ic_next,
                getString(R.string.action_next),
                sessionButtonIntent(Player.COMMAND_SEEK_TO_NEXT),
            )
        }
        val compact = buildList {
            if (queueHasPrevious) add(0)
            add(playPauseIndex)
            if (queueHasNext) add(playPauseIndex + 1)
        }
        val style = MediaStyleNotificationHelper.MediaStyle(current)
            .setShowActionsInCompactView(*compact.toIntArray())
        return builder.setStyle(style).build()
    }

    private fun placeholderNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.player_channel_name))
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun sessionButtonIntent(command: Int): PendingIntent {
        val action = when (command) {
            Player.COMMAND_SEEK_TO_PREVIOUS -> ACTION_PREVIOUS
            Player.COMMAND_SEEK_TO_NEXT -> ACTION_NEXT
            else -> ACTION_PLAY_PAUSE
        }
        val intent = Intent(this, PlaybackService::class.java).apply {
            this.action = action
        }
        return PendingIntent.getService(
            this, action.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun startForegroundSafely(notification: Notification?) {
        if (notification == null) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            // A failed startForeground leaves a started-but-not-foreground
            // service that trips ForegroundServiceDidNotStartInTime: exit
            // now instead of only logging.
            AppLog.event(AppLog.Category.APP, "foreground_service_failed", mapOf("service" to "playback"), e)
            stopSelf()
        }
    }

    private suspend fun fetchThumbnail(url: String?): Bitmap? {
        if (destroyed) return null
        val original = YoutubeThumbnail.await(url) ?: return null
        if (destroyed) return null
        val size = minOf(original.width, original.height)
        if (size <= 0) return null
        val left = (original.width - size) / 2
        val top = (original.height - size) / 2
        val square = if (left == 0 && top == 0 && original.width == size && original.height == size) {
            original
        } else {
            Bitmap.createBitmap(original, left, top, size, size)
        }
        val config = square.config ?: Bitmap.Config.ARGB_8888
        val unique = if (square.width <= NOTIFICATION_ART_PX) {
            if (square === original) square.copy(config, false) ?: return null else square
        } else {
            val scaled = square.scale(NOTIFICATION_ART_PX, NOTIFICATION_ART_PX)
            if (scaled !== square && square !== original) square.recycle()
            if (scaled === original) scaled.copy(config, false) ?: return null else scaled
        }
        return unique
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Playing: genuine background play, keep the session across the
        // recents swipe. Paused/idle: the swipe is a done signal — tear
        // down (official-app behavior).
        if (session != null) {
            val p = routingPlayer ?: player
            if (p?.isPlaying == true) return
            hide()
            return
        }
        super.onTaskRemoved(rootIntent)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        AppLog.event(AppLog.Category.APP, "playback_service_destroyed")
        destroyed = true
        commandEpoch++
        binder.clear()
        mainHandler.removeCallbacksAndMessages(null)
        artworkScope.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        session?.release()
        session = null
        recycleArt()
        notificationManager?.cancel(NOTIFICATION_ID)
        super.onDestroy()
    }

    private fun recycleArt() {
        lastArt?.let { if (!it.isRecycled) it.recycle() }
        lastArt = null
        lastArtUrl = null
    }

    companion object {
        private const val TAG = "PlaybackService"
        private const val CHANNEL_ID = "player_channel"
        private const val NOTIFICATION_ID = 100
        private const val ACTION_PLAY_PAUSE = "com.hhst.youtubelite.action.PLAY_PAUSE"
        private const val ACTION_PREVIOUS = "com.hhst.youtubelite.action.PREVIOUS"
        private const val ACTION_NEXT = "com.hhst.youtubelite.action.NEXT"
        private const val ACTION_CANCELLED = "com.hhst.youtubelite.action.CANCELLED"
        private const val NOTIFICATION_ART_PX = 256

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, PlaybackService::class.java),
            )
        }
    }
}
