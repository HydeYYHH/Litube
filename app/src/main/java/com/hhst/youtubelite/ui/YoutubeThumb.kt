package com.hhst.youtubelite.ui

import androidx.compose.foundation.Image
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.DisposableEffect
import kotlinx.coroutines.delay
import com.hhst.youtubelite.core.YoutubeThumbnail
import com.hhst.youtubelite.core.artworkSources
import com.hhst.youtubelite.core.artworkDecodeDimension
import com.hhst.youtubelite.ui.design.LocalMotionScale
import com.hhst.youtubelite.ui.design.motionDuration

@Composable
fun YoutubeThumb(
    url: String?,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    contentScale: ContentScale = ContentScale.Crop,
    placeholderColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    fallbackUrl: String? = null,
    placeholder: @Composable BoxScope.() -> Unit = {},
) {
    BoxWithConstraints(modifier.background(placeholderColor), contentAlignment = Alignment.Center) {
        val cacheVersion by YoutubeThumbnail.version.collectAsState()
        // Size-aware decode: small slots (avatars) don't decode cover-sized bitmaps.
        // Unbounded constraints (wrap-content) fall back to the full decode size.
        val maxDim = with(LocalDensity.current) {
            artworkDecodeDimension(maxOf(maxWidth, maxHeight).toPx().toInt().coerceAtLeast(128))
        }
        val sources = remember(url, fallbackUrl, maxDim) {
            (artworkSources(url, maxDim) + artworkSources(fallbackUrl, maxDim)).distinct()
        }
        fun acceptable(source: String, image: android.graphics.Bitmap): Boolean =
            source == url || source == fallbackUrl || maxOf(image.width, image.height) > 120
        // A decode-size change or cache clear must not erase an image the view still owns.
        // Only an explicit source change starts a new visual identity.
        var bitmap by remember(url, fallbackUrl) {
            mutableStateOf(sources.firstNotNullOfOrNull { source ->
                (YoutubeThumbnail.cached(source, maxDim) ?: YoutubeThumbnail.cachedForDisplay(source))
                    ?.takeIf { acceptable(source, it) }
            })
        }
        var resumeVersion by remember { mutableStateOf(0) }
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        DisposableEffect(lifecycle) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) resumeVersion++
            }
            lifecycle.addObserver(observer)
            onDispose { lifecycle.removeObserver(observer) }
        }
        LaunchedEffect(url, fallbackUrl, maxDim, cacheVersion, resumeVersion) {
            if (sources.isEmpty()) {
                bitmap = null
                return@LaunchedEffect
            }
            repeat(3) { attempt ->
                for (source in sources) {
                    val image = YoutubeThumbnail.await(source, maxDim)
                    if (image != null && acceptable(source, image)) {
                        bitmap = image
                        return@LaunchedEffect
                    }
                }
                if (attempt == 2) return@LaunchedEffect
                val retryAfter = sources.mapNotNull(YoutubeThumbnail::retryDelayMillis).minOrNull()
                    ?: return@LaunchedEffect
                delay(retryAfter)
            }
        }
        key(url, fallbackUrl) {
            Crossfade(bitmap, modifier = Modifier.fillMaxSize(),
                animationSpec = tween(motionDuration(90, LocalMotionScale.current)), label = "Artwork") { bmp ->
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = contentDescription,
                        contentScale = contentScale,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    placeholder()
                }
            }
        }
    }
}
