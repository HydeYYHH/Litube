package com.hhst.youtubelite.downloader.ui

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.hhst.youtubelite.downloader.core.DownloadCoordinator
import com.hhst.youtubelite.downloader.core.DownloadTarget
import com.hhst.youtubelite.core.PipAutoEnter
import com.hhst.youtubelite.downloader.engine.BackgroundDownloadScheduler
import com.hhst.youtubelite.extractor.Promise
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext

object DownloadActions {
    const val PAUSE = "com.hhst.youtubelite.download.PAUSE"
    const val CANCEL = "com.hhst.youtubelite.download.CANCEL"
    const val RESUME = "com.hhst.youtubelite.download.RESUME"
    const val VIEW = "com.hhst.youtubelite.download.VIEW"
    const val EXTRA_BATCH_ID = "batchId"

    fun handle(action: String?, batchId: String?) {
        if (action.isNullOrBlank() || batchId.isNullOrBlank()) return
        val koin = GlobalContext.getOrNull() ?: return
        val coordinator = koin.get<DownloadCoordinator>()
        Promise.DEFAULT_SCOPE.launch {
            apply(coordinator, action, batchId) {
                koin.get<BackgroundDownloadScheduler>().legalUserInteraction = true
            }
        }
    }

    suspend fun apply(
        coordinator: DownloadCoordinator,
        action: String,
        batchId: String,
        onResume: () -> Unit = {},
    ) {
        val target = DownloadTarget.Batch(batchId)
        when (action) {
            PAUSE -> coordinator.pause(target)
            CANCEL -> coordinator.cancel(target)
            RESUME -> {
                onResume()
                coordinator.resume(target)
            }
        }
    }
}

/** Pause/cancel from the notification; does not count as UIDT user interaction. */
class DownloadActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == DownloadActions.RESUME || action == DownloadActions.VIEW) {
            PipAutoEnter.noteLegacyLaunch()
            context.startActivity(
                DownloadActionActivity.intent(context, action, intent.getStringExtra(DownloadActions.EXTRA_BATCH_ID))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        }
        DownloadActions.handle(action, intent.getStringExtra(DownloadActions.EXTRA_BATCH_ID))
    }
}

/**
 * Visible trampoline so resume registers UIDT from a legal user interaction.
 * View/Resume open [DownloadActivity] with [DownloadActions.EXTRA_BATCH_ID].
 */
class DownloadActionActivity : Activity() {
    private var pipHandle: PipAutoEnter.Handle? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Nested with DownloadActivity: always increment so onDestroy cannot
        // drop the manager's suppress while it is still in front.
        pipHandle = PipAutoEnter.suppress()
        val action = intent.action
        val batchId = intent.getStringExtra(DownloadActions.EXTRA_BATCH_ID)
        if (action == DownloadActions.RESUME) {
            DownloadActions.handle(action, batchId)
        }
        if (action == DownloadActions.VIEW || action == DownloadActions.RESUME) {
            startActivity(
                DownloadActivity.intent(this, batchId = batchId)
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
        }
        finish()
    }

    override fun onDestroy() {
        pipHandle?.restore()
        pipHandle = null
        super.onDestroy()
    }

    companion object {
        fun intent(context: Context, action: String, batchId: String?): Intent =
            Intent(context, DownloadActionActivity::class.java)
                .setAction(action)
                .putExtra(DownloadActions.EXTRA_BATCH_ID, batchId)
    }
}
