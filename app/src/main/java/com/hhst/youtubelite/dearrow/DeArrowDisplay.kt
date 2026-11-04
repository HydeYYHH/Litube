package com.hhst.youtubelite.dearrow

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.koin.java.KoinJavaComponent.get
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow

/**
 * Display-layer DeArrow decoration for video cards. Originals stay canonical:
 * these helpers return the original value until (and unless) a replacement
 * arrives, so item identity, keys and persistence are untouched.
 */
@Composable
fun rememberDeArrowTitle(videoId: String?, original: String): String {
    if (videoId.isNullOrBlank()) return original
    val service = remember { get<DeArrowService>(DeArrowService::class.java) }
    val enabled by service.titlesEnabled.collectAsState()
    var replaced by remember(videoId, original) { mutableStateOf<String?>(null) }
    LaunchedEffect(videoId, original, enabled) {
        replaced = null
        if (!enabled) return@LaunchedEffect
        val branding = service.branding(videoId) ?: return@LaunchedEffect
        // The toggle may have flipped while the bucket was in flight.
        if (service.titlesEnabled.value) replaced = branding.title
    }
    return replaced?.takeIf { it.isNotBlank() } ?: original
}

@Composable
fun rememberDeArrowArtwork(videoId: String?, original: String): String {
    if (videoId.isNullOrBlank()) return original
    val service = remember { get<DeArrowService>(DeArrowService::class.java) }
    val enabled by service.thumbnailsEnabled.collectAsState()
    var replaced by remember(videoId, original) { mutableStateOf<String?>(null) }
    LaunchedEffect(videoId, original, enabled) {
        replaced = null
        if (!enabled) return@LaunchedEffect
        val branding = service.branding(videoId) ?: return@LaunchedEffect
        if (service.thumbnailsEnabled.value) replaced = deArrowThumbnailUrl(videoId, branding.thumbTime)
    }
    return replaced ?: original
}

@Composable
fun VideoArtwork(videoId: String?, original: String, modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier) {
    val view = androidx.compose.ui.platform.LocalView.current
    val displayed = rememberDeArrowArtwork(videoId, original)
    var bounds by remember(videoId) { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    androidx.compose.runtime.SideEffect {
        bounds?.let { rect ->
            if (videoId != null) com.hhst.youtubelite.navigation.WatchTransition.artwork(view, videoId,
                rect)
        }
    }
    com.hhst.youtubelite.ui.design.Artwork(displayed,
        modifier.then(androidx.compose.ui.Modifier.onGloballyPositioned { coordinates ->
            bounds = coordinates.boundsInWindow()
        }), fallback = original)
}

fun deArrowThumbnailUrl(videoId: String, thumbTime: Double?): String? {
    val time = thumbTime ?: return null
    return "https://dearrow-thumb.ajay.app/api/v1/getThumbnail?videoID=$videoId&time=$time"
}
