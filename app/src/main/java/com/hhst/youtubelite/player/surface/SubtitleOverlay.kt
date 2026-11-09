package com.hhst.youtubelite.player.surface

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Base cue look (material bodyMedium); [SubtitleStyle.textScale] multiplies it. */
private const val BASE_FONT_SP = 14f
private const val BASE_LINE_HEIGHT_SP = 20f

private fun SubtitleStyle.Background.alpha(): Float = when (this) {
    SubtitleStyle.Background.NONE -> 0f
    SubtitleStyle.Background.SEMI -> 0.6f
    SubtitleStyle.Background.OPAQUE -> 1f
}

/**
 * Placed by the caller via [SubtitleStyle.verticalBias] / [SubtitleStyle.horizontal].
 * The caller width-constrains the column so long lines wrap instead of running
 * off-screen; each cue line hugs its own background.
 */
@Composable
fun SubtitleOverlay(
    cues: List<String>,
    style: SubtitleStyle = SubtitleStyle(),
    modifier: Modifier = Modifier,
) {
    val fontSp = BASE_FONT_SP * style.textScale
    val bgAlpha = style.background.alpha()
    val textColor = Color(style.colorArgb)
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp * style.textScale),
    ) {
        cues.forEach { line ->
            Text(
                text = line,
                color = textColor,
                fontSize = fontSp.sp,
                // Scale wrapped/newline text too; the inherited theme line height stays fixed.
                lineHeight = (BASE_LINE_HEIGHT_SP * style.textScale).sp,
                fontWeight = if (style.bold) FontWeight.Bold else FontWeight.Normal,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .then(
                        if (bgAlpha > 0f) {
                            Modifier.background(Color.Black.copy(alpha = bgAlpha), RoundedCornerShape(4.dp))
                        } else {
                            Modifier
                        },
                    )
                    .padding(
                        horizontal = 8.dp * style.textScale,
                        vertical = 2.dp * style.textScale,
                    ),
            )
        }
    }
}
