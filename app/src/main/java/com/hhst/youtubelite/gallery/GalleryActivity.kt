package com.hhst.youtubelite.gallery

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.hhst.youtubelite.R
import com.hhst.youtubelite.ui.theme.AppTheme
import com.hhst.youtubelite.ui.theme.settingsAppBarHeight
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GalleryActivity : ComponentActivity() {
    private var pendingSave by mutableStateOf<File?>(null)
    private val saveImage = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val image = pendingSave; pendingSave = null
        val uri = result.data?.data
        if (result.resultCode == RESULT_OK && image != null && uri != null) lifecycleScope.launch {
            val success = withContext(Dispatchers.IO) { runCatching {
                contentResolver.openOutputStream(uri)?.use { output -> image.inputStream().use { it.copyTo(output) } }
                    ?: error("No output stream")
            }.isSuccess }
            Toast.makeText(this@GalleryActivity, if (success) R.string.gallery_saved else R.string.gallery_failed, Toast.LENGTH_SHORT).show()
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge()
        pendingSave = savedInstanceState?.getString("pending_save")?.let(::File)
            ?.takeIf { it.isFile && it.parentFile == File(cacheDir, "gallery") }
        val urls = intent.getStringArrayListExtra("images").orEmpty()
        if (urls.size !in 1..30 || urls.any { !GalleryImages.allowed(it) }) { finish(); return }
        lifecycleScope.launch(Dispatchers.IO) { GalleryImages.clean(this@GalleryActivity) }
        setContent { AppTheme { Gallery(urls, intent.getIntExtra("index", 0).coerceIn(urls.indices)) } }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("pending_save", pendingSave?.path)
        super.onSaveInstanceState(outState)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable private fun Gallery(urls: List<String>, initial: Int) {
        val pager = rememberPagerState(initialPage = initial, pageCount = { urls.size })
        var pageImage by remember { mutableStateOf<Pair<Int, GalleryImages.Image>?>(null) }
        val current = pageImage?.takeIf { it.first == pager.currentPage }?.second
        val scope = rememberCoroutineScope()
        var busy by remember { mutableStateOf(false) }
        var scaledPage by remember { mutableStateOf<Pair<Int, Float>?>(null) }
        val zoomed = scaledPage?.let { it.first == pager.currentPage && it.second > 1f } == true
        Scaffold(topBar = { TopAppBar(title = { Text("${pager.currentPage + 1} / ${urls.size}") },
            expandedHeight = settingsAppBarHeight(),
            navigationIcon = { IconButton(onClick = { finish() }) { Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.navigate_back)) } },
            actions = {
                IconButton(enabled = current != null && !busy && pendingSave == null, onClick = {
                    val image = current ?: return@IconButton
                    pendingSave = image.file
                    runCatching { saveImage.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE); type = image.mime
                        putExtra(Intent.EXTRA_TITLE, "LiTube-${System.currentTimeMillis()}.${image.extension}")
                    }) }.onFailure {
                        pendingSave = null
                        Toast.makeText(this@GalleryActivity, R.string.gallery_failed, Toast.LENGTH_SHORT).show()
                    }
                }) { Icon(painterResource(R.drawable.ic_download), stringResource(R.string.gallery_save)) }
                IconButton(enabled = current != null && !busy && pendingSave == null, onClick = {
                    val image = current ?: return@IconButton
                    busy = true
                    scope.launch {
                        runCatching {
                            val shared = withContext(Dispatchers.IO) {
                                File(image.file.parentFile, "share-${System.currentTimeMillis()}.${image.extension}").also { image.file.copyTo(it) }
                            }
                            val uri = FileProvider.getUriForFile(this@GalleryActivity, "$packageName.download.fileprovider", shared)
                            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                type = image.mime; putExtra(Intent.EXTRA_STREAM, uri)
                                clipData = ClipData.newRawUri("image", uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }, getString(R.string.share)))
                        }.onFailure { Toast.makeText(this@GalleryActivity, R.string.gallery_failed, Toast.LENGTH_SHORT).show() }
                        busy = false
                    }
                }) { Icon(painterResource(R.drawable.ic_share), stringResource(R.string.share)) }
            }) }) { padding ->
            HorizontalPager(pager, userScrollEnabled = !zoomed, modifier = Modifier.fillMaxSize().padding(padding)) { page ->
                var retry by remember { mutableIntStateOf(0) }
                var image by remember { mutableStateOf<GalleryImages.Image?>(null) }
                var failed by remember { mutableStateOf(false) }
                var scale by rememberSaveable { mutableFloatStateOf(1f) }
                var offset by remember { mutableStateOf(Offset.Zero) }
                LaunchedEffect(urls[page], retry) {
                    failed = false
                    image = withContext(Dispatchers.IO) { runCatching { GalleryImages.load(this@GalleryActivity, urls[page]) }.getOrNull() }
                    failed = image == null
                }
                LaunchedEffect(pager.currentPage, image, scale) { if (pager.currentPage == page) { pageImage = image?.let { page to it }; scaledPage = page to scale } }
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    val loaded = image
                    if (loaded != null) {
                        val zoomLabel = stringResource(R.string.gallery_zoom)
                        val resetLabel = stringResource(R.string.gallery_reset_zoom)
                        Image(loaded.bitmap.asImageBitmap(), stringResource(R.string.gallery_image, page + 1),
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                                .semantics {
                                    customActions = listOf(
                                        CustomAccessibilityAction(zoomLabel) { scale = (scale * 1.5f).coerceAtMost(5f); true },
                                        CustomAccessibilityAction(resetLabel) { scale = 1f; offset = Offset.Zero; true },
                                    )
                                }
                                .pointerInput(loaded) { detectTapGestures(onDoubleTap = { scale = if (scale > 1f) 1f else 2.5f; offset = Offset.Zero }) }
                                .pointerInput(loaded) { awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false)
                                    do {
                                        val event = awaitPointerEvent()
                                        if (event.changes.count { it.pressed } >= 2 || scale > 1f) {
                                            scale = (scale * event.calculateZoom()).coerceIn(1f, 5f)
                                            offset = if (scale <= 1f) Offset.Zero else (offset + event.calculatePan()).let {
                                                Offset(it.x.coerceIn(-size.width*(scale-1)/2, size.width*(scale-1)/2), it.y.coerceIn(-size.height*(scale-1)/2, size.height*(scale-1)/2))
                                            }
                                            event.changes.forEach { it.consume() }
                                        }
                                    } while (event.changes.any { it.pressed })
                                } }
                                .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y })
                    } else if (failed) TextButton(onClick = { retry++ }) { Text(stringResource(R.string.gallery_retry)) }
                    else CircularProgressIndicator(Modifier.size(28.dp))
                }
            }
        }
    }
    companion object {
        fun open(context: Context, urls: List<String>, index: Int) {
            context.startActivity(Intent(context, GalleryActivity::class.java).apply {
                putStringArrayListExtra("images", ArrayList(urls)); putExtra("index", index)
            })
        }
    }
}
