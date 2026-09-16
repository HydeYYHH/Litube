package com.hhst.youtubelite.player.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.hhst.youtubelite.extension.ExtensionManager
import com.hhst.youtubelite.extension.PreferenceKeys
import com.hhst.youtubelite.player.engine.PlaybackNotificationController

/**
 * Binds the engine to [PlaybackService] and forwards playback state to the
 * notification. The service is started foreground on first load; the
 * connection stays for the app lifetime (the service stops itself on hide).
 *
 * When the background-play extension is disabled, no notification is shown.
 * The activity already pauses playback in that case, so a leftover paused
 * notification would be misleading.
 */
@UnstableApi
class ServiceNotificationController(
    private val context: Context,
    private val prefs: ExtensionManager,
) : PlaybackNotificationController {

    @Volatile private var service: PlaybackService? = null
    private var pendingLoad: (() -> Unit)? = null
    /** Nav flags pushed before the service connected; replayed on connect. */
    private var pendingNav: Pair<Boolean, Boolean>? = null
    private var bound = false
    private var lastPlayer: Player? = null
    private var lastTitle: String = ""
    private var lastAuthor: String? = null
    private var lastThumbnailUrl: String? = null

    init {
        prefs.addOnChangedListener { key ->
            if (key == PreferenceKeys.ENABLE_BACKGROUND_PLAY || key == "*") {
                if (!prefs.isEnabled(PreferenceKeys.ENABLE_BACKGROUND_PLAY)) {
                    hideService()
                } else {
                    lastPlayer?.let { onLoaded(it, lastTitle, lastAuthor, lastThumbnailUrl) }
                }
            }
        }
    }

    // Explicit type: the callbacks reference [connection] itself, and an
    // inferred type would make that a cyclic declaration.
    private val connection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val svc = (binder as? PlaybackService.LocalBinder)?.getService() ?: return
            service = svc
            pendingNav?.let { (next, prev) -> svc.updateQueueNavigation(next, prev) }
            pendingNav = null
            pendingLoad?.invoke()
            pendingLoad = null
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }

        override fun onBindingDied(name: ComponentName?) {
            // The service process died: the binding is dead but still counted,
            // so bound must be cleared or every later onLoaded queues in
            // pendingLoad forever with no notification and no MediaSession.
            runCatching { context.unbindService(connection) }
            bound = false
            service = null
        }

        override fun onNullBinding(name: ComponentName?) {
            bound = false
            service = null
        }
    }

    override fun onLoaded(
        player: Player,
        title: String,
        author: String?,
        thumbnailUrl: String?,
    ) {
        if (!prefs.isEnabled(PreferenceKeys.ENABLE_BACKGROUND_PLAY)) return
        lastPlayer = player
        lastTitle = title
        lastAuthor = author
        lastThumbnailUrl = thumbnailUrl
        ensureStartedAndBound()
        val show: () -> Unit = {
            service?.attach(player)
            service?.show(title, author, thumbnailUrl)
        }
        if (service != null) show() else pendingLoad = show
    }

    override fun onPlayingChanged(isPlaying: Boolean) {
        service?.refresh(showAsPlaying = isPlaying)
    }

    override fun onStopped() {
        lastPlayer = null
        lastTitle = ""
        lastAuthor = null
        lastThumbnailUrl = null
        hideService()
    }

    private fun hideService() {
        service?.hide()
        if (bound) {
            runCatching { context.unbindService(connection) }
            bound = false
        } else {
            // startForegroundService may have succeeded while bindService
            // failed: nothing holds the service reference then, so stop the
            // placeholder foreground service here or it outlives playback.
            runCatching { context.stopService(Intent(context, PlaybackService::class.java)) }
        }
        service = null
        pendingLoad = null
        pendingNav = null
    }

    override fun onQueueNavigation(hasNext: Boolean, hasPrevious: Boolean) {
        val svc = service
        if (svc != null) {
            svc.updateQueueNavigation(hasNext, hasPrevious)
        } else {
            pendingNav = hasNext to hasPrevious
        }
    }

    private fun ensureStartedAndBound() {
        if (!bound) {
            PlaybackService.start(context)
            val intent = Intent(context, PlaybackService::class.java)
            // bindService can fail (e.g. backgrounded start on some OEMs); a
            // stuck bound=true would never retry.
            if (context.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
                bound = true
            }
        }
    }
}
