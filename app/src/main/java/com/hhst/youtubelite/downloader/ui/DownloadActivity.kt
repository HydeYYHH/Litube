package com.hhst.youtubelite.downloader.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.hhst.youtubelite.downloader.pip.PipAutoEnter
import com.hhst.youtubelite.ui.theme.AppTheme
import org.koin.androidx.compose.koinViewModel

/** Independent download manager: filters, batch details, settings, history. */
class DownloadActivity : ComponentActivity() {

    private var pipHandle: PipAutoEnter.Handle? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val batchId = intent.getStringExtra(DownloadUi.EXTRA_BATCH_ID)
        val taskId = intent.getStringExtra(DownloadUi.EXTRA_TASK_ID)
        val dest = intent.getStringExtra(DownloadUi.EXTRA_DEST)
        setContent {
            AppTheme {
                DownloadManagerScreen(
                    viewModel = koinViewModel(),
                    initialBatchId = batchId,
                    initialTaskId = taskId,
                    initialDest = dest,
                    onClose = { finish() },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        recreate()
    }

    override fun onStart() {
        super.onStart()
        if (pipHandle == null) pipHandle = PipAutoEnter.suppress()
    }

    override fun onStop() {
        pipHandle?.restore()
        pipHandle = null
        super.onStop()
    }

    companion object {
        fun intent(
            context: Context,
            batchId: String? = null,
            taskId: String? = null,
            dest: String? = null,
        ): Intent = Intent(context, DownloadActivity::class.java)
            .putExtra(DownloadUi.EXTRA_BATCH_ID, batchId)
            .putExtra(DownloadUi.EXTRA_TASK_ID, taskId)
            .putExtra(DownloadUi.EXTRA_DEST, dest)
    }
}
