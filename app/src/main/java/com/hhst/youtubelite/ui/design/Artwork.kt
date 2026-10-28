package com.hhst.youtubelite.ui.design

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.hhst.youtubelite.R

@Composable
fun Artwork(asset: String, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop, fallback: String? = null) {
    if (asset.startsWith("https://") || asset.startsWith("//")) {
        com.hhst.youtubelite.ui.YoutubeThumb(if (asset.startsWith("//")) "https:$asset" else asset,
            modifier, contentScale = contentScale, placeholderColor = DesignTokens.Surface,
            fallbackUrl = fallback?.let { if (it.startsWith("//")) "https:$it" else it })
        return
    }
    if (asset.isBlank()) {
        Box(modifier.background(DesignTokens.Surface))
        return
    }
    Image(
        painterResource(when (asset) {
            "dream" -> R.drawable.sample_dream
            "sintel" -> R.drawable.sample_sintel
            else -> R.drawable.sample_bunny
        }),
        contentDescription = null,
        modifier = modifier,
        contentScale = contentScale,
    )
}
