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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import com.hhst.youtubelite.core.YoutubeThumbnail
import com.hhst.youtubelite.core.artworkSources
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
            maxOf(maxWidth, maxHeight).toPx().toInt().coerceIn(128, 1600)
        }
        val sources = remember(url, fallbackUrl, maxDim) {
            (artworkSources(url, maxDim) + artworkSources(fallbackUrl, maxDim)).distinct()
        }
        fun acceptable(source: String, image: android.graphics.Bitmap): Boolean =
            source == url || source == fallbackUrl || maxOf(image.width, image.height) > 120
        var bitmap by remember(url, fallbackUrl, maxDim, cacheVersion) {
            mutableStateOf(sources.firstNotNullOfOrNull { source ->
                YoutubeThumbnail.cached(source, maxDim)?.takeIf { acceptable(source, it) }
            })
        }
        LaunchedEffect(url, fallbackUrl, maxDim, cacheVersion) {
            if (url.isNullOrBlank()) {
                bitmap = null
                return@LaunchedEffect
            }
            for (source in sources) {
                val image = YoutubeThumbnail.await(source, maxDim)
                if (image != null && acceptable(source, image)) {
                    bitmap = image
                    break
                }
            }
        }
        Crossfade(bitmap, modifier = Modifier.fillMaxSize(),
            animationSpec = tween(motionDuration(140, LocalMotionScale.current)), label = "Artwork") { bmp ->
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
