package com.hhst.youtubelite.ui.design

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.hhst.youtubelite.R

/** Fixed local artwork used by every fixture surface. No remote image is loaded. */
@Composable
fun Artwork(asset: String, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop) {
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
