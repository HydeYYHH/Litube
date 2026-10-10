package com.hhst.youtubelite.player.service

import com.hhst.youtubelite.diagnostics.*

import android.app.Notification
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
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
import com.hhst.youtubelite.MainActivity
import com.hhst.youtubelite.R
import com.hhst.youtubelite.core.YoutubeThumbnail
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import androidx.core.graphics.scale

/**
 * Media playback notification / foreground service.
 *
 * The session wraps the active player (local [ExoPlayer], or the cast
 * receiver while delegating) in a forwarding player so next/previous from the
 * notification, headset keys and AVRCP route through [PlaybackCommandRouter]
 * into the app's queue semantics — the wrapped player holds a single
 * MediaItem, where seekToNext/Previous would be no-ops.
 */
@UnstableApi
class PlaybackService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    private var notificationManager: NotificationManager? = null
    private var session: MediaSession? = null
    private var player: Player? = null
    private var routingPlayer: QueueRoutingPlayer? = null
    private var lastTitle: String = ""
    private var lastAuthor: String? = null
    private var lastArt: Bitmap? = null
    private var lastArtUrl: String? = null
    /** URL whose art fetch is queued but not finished; dedupes repeated show()s. */
    private var pendingArtUrl: String? = null
    /** Monotonic token; only the newest art fetch may apply its bitmap. */
    private val artSeq = AtomicLong()
    @Volatile
    private var destroyed = false

    /**
     * Set between [hide] and the next [show]. stopSelf() only reaches
     * onDestroy after the main queue drains, so a thumbnail post enqueued
     * before hide() could otherwise resurrect the notification in between.
     * Unlike [destroyed] this is cleared by show(): the controller unbinds in
     * onStopped, but a re-bind racing the old instance's teardown must still
     * accept fresh state.
     */
    @Volatile
    private var hidden = false

    private var queueHasNext = false
    private var queueHasPrevious = false
    private var lastShowAsPlaying = false
    private val commandListeners = CopyOnWriteArrayList<Player.Listener>()

    override fun onBind(intent: Intent?): IBinder? {
        AppLog.event(AppLog.Category.APP, "playback_service_bound")
        return LocalBinder()
    }

    inner class LocalBinder : Binder() {
        fun getService(): PlaybackService = this@PlaybackService
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
                ACTION_PLAY_PAUSE -> PlaybackCommandRouter.playPause()
                ACTION_PREVIOUS -> PlaybackCommandRouter.previous()
                ACTION_NEXT -> PlaybackCommandRouter.next()
                // Swiping the (paused) notification away ends playback — the
                // delete intent only fires while not ongoing.
                ACTION_CANCELLED -> {
                    PlaybackCommandRouter.pause()
                    hide()
                    return START_NOT_STICKY
                }
            }
            // No refresh() here: the command's state change lands through the
            // engine's onIsPlayingChanged → refresh, which reads the real
            // post-command state (a refresh here would re-post the old one).
        }
        return START_NOT_STICKY
    }

    /**
     * Session player that routes the app-level transport (queue next/previous,
     * play/pause while casting) instead of driving the wrapped local player,
     * whose single MediaItem makes seekToNext/Previous no-ops.
     */
    private inner class QueueRoutingPlayer(wrapped: Player) : ForwardingPlayer(wrapped) {
        override fun play() = PlaybackCommandRouter.play()
        override fun pause() = PlaybackCommandRouter.pause()
        override fun seekToNext() = PlaybackCommandRouter.next()
        override fun seekToNextMediaItem() = PlaybackCommandRouter.next()
        override fun seekToPrevious() = PlaybackCommandRouter.previous()
        override fun seekToPreviousMediaItem() = PlaybackCommandRouter.previous()

        // Headset/system media controls derive their buttons from this set;
        // a single-item ExoPlayer does not advertise next/previous, so the
        // app queue must add those commands when it can navigate.
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

    /**
     * Binds the engine's player into the session. Idempotent; called from the
     * engine on every playback start before [show].
     */
    fun attach(player: Player) {
        if (destroyed) return
        if (this.player === player && session != null) return
        this.player = player
        session?.release()
        commandListeners.clear()
        routingPlayer = QueueRoutingPlayer(player)
        // Tapping the system media card (QS / volume dialog) must open the app,
        // not no-op — the notification's contentIntent does not cover it.
        val sessionActivity = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
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
        // A new video without a thumbnail must not keep the previous one's
        // art on the notification.
        if (thumbnailUrl == null && lastArt != null) recycleArt()
        // The wrapped player reflects the cast receiver while delegating.
        val playing = lastShowAsPlaying || (routingPlayer ?: player)?.isPlaying == true
        lastShowAsPlaying = playing
        startForegroundSafely(buildNotification(playing))
        // Skip when the art is current OR a fetch for this URL is already
        // queued: onLoaded fires per load (and again after the oEmbed title
        // swap), so without the pending check a slow fetch would pile up one
        // executor task per show() for the same URL.
        if ((thumbnailUrl == lastArtUrl && lastArt != null) || thumbnailUrl == pendingArtUrl) return
        try {
            pendingArtUrl = thumbnailUrl
            // Supersession token: only the newest requested fetch may apply
            // its art — a slow fetch for the PREVIOUS video completing late
            // must not revert the notification to the old thumbnail.
            val seq = artSeq.incrementAndGet()
            executor.execute {
                val art = fetchThumbnail(thumbnailUrl)
                if (pendingArtUrl == thumbnailUrl) pendingArtUrl = null
                if (art == null) return@execute
                if (destroyed || seq != artSeq.get()) {
                    if (!art.isRecycled) art.recycle()
                    return@execute
                }
                mainHandler.post {
                    if (destroyed || hidden || seq != artSeq.get()) {
                        if (!art.isRecycled) art.recycle()
                        return@post
                    }
                    val previous = lastArt
                    lastArt = art
                    lastArtUrl = thumbnailUrl
                    val nowPlaying = (routingPlayer ?: player)?.isPlaying == true
                    notificationManager?.notify(NOTIFICATION_ID, buildNotification(nowPlaying))
                    if (previous != null && previous !== art && !previous.isRecycled) {
                        previous.recycle()
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            pendingArtUrl = null
        }
    }

    /** Rebuilds the notification from the current player state. */
    fun refresh(showAsPlaying: Boolean? = null) {
        // hidden: between hide() and onDestroy a queued notification action
        // can still deliver to onStartCommand — re-posting the cancelled
        // notification here would resurrect it for one main-loop turn.
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
        // onDestroy may run after the queued post has already fired.
        hidden = true
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        notificationManager?.cancel(NOTIFICATION_ID)
        recycleArt()
        stopSelf()
    }

    // -- notification --

    private fun buildNotification(isPlaying: Boolean): Notification? {
        val current = session ?: return null
        val contentIntent = PendingIntent.getActivity(
            this, 101,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_play)
            .setContentTitle(lastTitle)
            .setContentText(lastAuthor)
            .setLargeIcon(lastArt)
            .setContentIntent(contentIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            // Swipe-dismiss while playing would orphan the audio with no
            // controls; paused, the notification is the user's "I'm done"
            // handle (official-app behavior): dismissible, and dismissing
            // stops playback via the delete intent.
            .setOngoing(isPlaying)
            .setDeleteIntent(
                PendingIntent.getService(
                    this, ACTION_CANCELLED.hashCode(),
                    Intent(this, PlaybackService::class.java).apply { action = ACTION_CANCELLED },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )

        // Session-driven media buttons: prev/play-pause/next mirror queue nav.
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
            .setSmallIcon(R.drawable.ic_stat_play)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.player_channel_name))
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    /** Button intent targeting this service; [onStartCommand] routes it to the router. */
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
            // service on a collision course with the system's
            // ForegroundServiceDidNotStartInTime check: exit now instead of
            // only logging the failure.
            AppLog.event(AppLog.Category.APP, "foreground_service_failed", mapOf("service" to "playback"), e)
            stopSelf()
        }
    }

    // -- thumbnail --

    private fun fetchThumbnail(url: String?): Bitmap? {
        if (destroyed) return null
        val original = YoutubeThumbnail.fetch(url) ?: return null
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
        // recents swipe. Paused/idle: the swipe is a done signal — tear the
        // notification and service down (official-app behavior).
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
        mainHandler.removeCallbacksAndMessages(null)
        executor.shutdownNow()
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
