package com.hhst.youtubelite.downloader.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.hhst.youtubelite.core.PipAutoEnter
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import com.hhst.youtubelite.ui.theme.AppTheme
import com.hhst.youtubelite.ui.theme.NeutralControls
import org.koin.androidx.compose.koinViewModel

class DownloadActivity : ComponentActivity() {

    private var pipHandle: PipAutoEnter.Handle? = null

    /** Batch requests from new intents reach the live screen without recreating it. */
    private val batchRequests = Channel<String>(Channel.CONFLATED)
    private val batchEvents = batchRequests.receiveAsFlow()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        (application as com.hhst.youtubelite.App).startMediaRuntime()
        enableEdgeToEdge()
        val batchId = intent.getStringExtra(DownloadUi.EXTRA_BATCH_ID)
        setContent {
            AppTheme { NeutralControls {
                DownloadManagerScreen(
                    viewModel = koinViewModel(),
                    initialBatchId = batchId,
                    newBatchRequests = batchEvents,
                    onClose = { finish() },
                )
            } }
        }
    }

    // Public so tests can deliver the runtime batch intent directly, without
    // depending on windowing-dependent startActivity routing.
    public override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(DownloadUi.EXTRA_BATCH_ID)?.takeIf(String::isNotBlank)?.let { id ->
            batchRequests.trySend(id)
        }
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
        ): Intent = Intent(context, DownloadActivity::class.java)
            .putExtra(DownloadUi.EXTRA_BATCH_ID, batchId)
    }
}
