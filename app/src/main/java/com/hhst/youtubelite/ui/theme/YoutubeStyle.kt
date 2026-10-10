package com.hhst.youtubelite.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp

object YoutubeAppearance {
    var dark by mutableStateOf<Boolean?>(null)
        private set

    fun update(value: Boolean?) { dark = value }
}

object YoutubeStyle {
    val Shapes = Shapes(
        extraSmall = RoundedCornerShape(4.dp), small = RoundedCornerShape(8.dp),
        medium = RoundedCornerShape(12.dp), large = RoundedCornerShape(12.dp),
        extraLarge = RoundedCornerShape(16.dp),
    )
    val SheetShape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
    val MenuShape = RoundedCornerShape(12.dp)
    val Action: Color
        @Composable get() = if (MaterialTheme.colorScheme.background.luminance() < 0.5f)
            Color(0xFF3EA6FF) else Color(0xFF065FD4)
}
