package com.hhst.youtubelite.downloader.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hhst.youtubelite.R
import com.hhst.youtubelite.downloader.core.EnqueueResult
import com.hhst.youtubelite.downloader.pip.PipAutoEnter
import com.hhst.youtubelite.ui.theme.AppTheme
import org.koin.androidx.compose.koinViewModel

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
class DownloadSheetActivity : ComponentActivity() {

    private var pipHandle: PipAutoEnter.Handle? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val videoId = intent.getStringExtra(DownloadUi.EXTRA_VIDEO_ID)
        val snapshotId = intent.getStringExtra(DownloadUi.EXTRA_SNAPSHOT_ID)
        val snapshot = snapshotId?.let { DownloadUiSessions.get(this, it) }
        setContent {
            AppTheme {
                val vm = koinViewModel<DownloadViewModel>()
                val snackbar = remember { SnackbarHostState() }
                var result by remember { mutableStateOf<EnqueueResult?>(null) }
                var reject by remember { mutableStateOf<String?>(null) }
                val viewLabel = stringResource(R.string.download_view)
                val addedMessage = stringResource(R.string.download_added)
                val none = stringResource(R.string.download_no_new_tasks)
                val tooMany = stringResource(R.string.download_snapshot_too_many)
                val tooLarge = stringResource(R.string.download_snapshot_too_large)
                LaunchedEffect(result) {
                    val r = result ?: return@LaunchedEffect
                    val snack = DownloadPresentation.snackbarFor(r)
                    val message = if (snack.kind == SnackbarKind.NO_NEW_TASKS) none else addedMessage
                    // An action label makes the M3 default Indefinite, which would
                    // hold this activity open until tapped; Short keeps the View
                    // action available while still auto-dismissing.
                    val outcome = snackbar.showSnackbar(
                        message,
                        actionLabel = viewLabel,
                        duration = SnackbarDuration.Short,
                    )
                    if (outcome == SnackbarResult.ActionPerformed) {
                        DownloadUi.openManager(this@DownloadSheetActivity, r.batchId, snack.taskId)
                    }
                    finish()
                }
                LaunchedEffect(reject) {
                    val msg = reject ?: return@LaunchedEffect
                    snackbar.showSnackbar(
                        when {
                            msg.contains("items") -> tooMany
                            msg.contains("bytes") -> tooLarge
                            else -> msg
                        },
                    )
                    finish()
                }
                Box(Modifier.fillMaxSize()) {
                    when {
                        result != null || reject != null -> Unit
                        !videoId.isNullOrBlank() -> SingleVideoConfirmSheet(
                            videoId = videoId,
                            title = intent.getStringExtra(DownloadUi.EXTRA_TITLE).orEmpty(),
                            author = intent.getStringExtra(DownloadUi.EXTRA_AUTHOR),
                            thumbnailUrl = intent.getStringExtra(DownloadUi.EXTRA_THUMBNAIL),
                            viewModel = vm,
                            onDismiss = { finish() },
                            onSubmitted = { result = it },
                        )
                        snapshot != null -> BatchConfirmSheet(
                            snapshot = snapshot,
                            viewModel = vm,
                            onDismiss = { finish() },
                            onSubmitted = { result = it },
                            onRejected = { reject = it },
                        )
                        else -> finish()
                    }
                    SnackbarHost(
                        snackbar,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(16.dp),
                    )
                }
            }
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
        fun singleIntent(
            context: Context,
            videoId: String,
            title: String,
            author: String?,
            thumbnailUrl: String?,
        ): Intent = Intent(context, DownloadSheetActivity::class.java)
            .putExtra(DownloadUi.EXTRA_VIDEO_ID, videoId)
            .putExtra(DownloadUi.EXTRA_TITLE, title)
            .putExtra(DownloadUi.EXTRA_AUTHOR, author)
            .putExtra(DownloadUi.EXTRA_THUMBNAIL, thumbnailUrl)

        fun batchIntent(context: Context, snapshotId: String): Intent =
            Intent(context, DownloadSheetActivity::class.java)
                .putExtra(DownloadUi.EXTRA_SNAPSHOT_ID, snapshotId)
    }
}
