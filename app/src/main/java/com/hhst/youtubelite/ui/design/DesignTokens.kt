package com.hhst.youtubelite.ui.design

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.*
import com.hhst.youtubelite.R
import kotlin.math.ceil

object DesignTokens {
    val Ink = Color(0xFF0F0F0F)
    val Secondary = Color(0xFF606060)
    val Surface = Color(0xFFF2F2F2)
    val Ghost = Color(0xFFE5E5E5)
    val Border = Color.Black.copy(alpha = 0.1f)
    val NavigationDivider = Color.Black.copy(alpha = 0.2f)
    val Scrim = Color.Black.copy(alpha = 0.3f)
    val MenuScrim = Color.Black.copy(alpha = 0.6f)
    val Red = Color(0xFFFF0033)
    val Font = FontFamily(
        Font(R.font.roboto_regular, FontWeight.Normal), Font(R.font.roboto_medium, FontWeight.Medium),
        Font(R.font.roboto_bold, FontWeight.Bold),
        Font(R.font.roboto_bold, FontWeight.Black),
    )
    val TopBarHeight = 48.dp
    val BottomBarHeight = 56.dp
    val GridGap = 16.dp
    fun text(size: Int = 14, line: Int = 20, weight: FontWeight = FontWeight.Normal, color: Color = Ink) = TextStyle(
        color = color, fontFamily = Font, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
    )
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) { content() }
}

/** Draws the message card shadow without platform elevation. */
fun Modifier.messageCardShadow() = drawWithCache {
    // Convert the Gaussian standard deviation to BlurMaskFilter's radius.
    val sigma = 8.dp.toPx()
    val blur = ((sigma - 0.5f) / 0.57735f).coerceAtLeast(0f)
    val padding = ceil(blur * 2).toInt()
    val bitmap = android.graphics.Bitmap.createBitmap(ceil(size.width).toInt() + 2 * padding,
        ceil(size.height).toInt() + 2 * padding, android.graphics.Bitmap.Config.ARGB_8888)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x33000000
        maskFilter = android.graphics.BlurMaskFilter(blur, android.graphics.BlurMaskFilter.Blur.NORMAL)
    }
    val left = padding.toFloat()
    val top = padding + 4.dp.toPx()
    android.graphics.Canvas(bitmap).drawRoundRect(left, top, left + size.width, top + size.height, 12.dp.toPx(), 12.dp.toPx(), paint)
    val shadow = bitmap.asImageBitmap()
    onDrawBehind { drawImage(shadow, topLeft = androidx.compose.ui.geometry.Offset(-left, -left)) }
}

@Composable
fun AppText(
    text: String, modifier: Modifier = Modifier, size: Int = 14, line: Int = 20,
    weight: FontWeight = FontWeight.Normal, color: Color = DesignTokens.Ink,
    maxLines: Int = Int.MAX_VALUE, align: TextAlign? = null,
    exactLineHeight: TextUnit? = null,
) {
    BasicText(text, modifier, style = DesignTokens.text(size, line, weight, color).copy(textAlign = align ?: TextAlign.Start,
        lineHeight = exactLineHeight ?: line.sp),
        maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

fun Modifier.headerShadow() = drawWithCache {
    val blur = ((1.dp.toPx() - 0.5f) / 0.57735f).coerceAtLeast(0.01f)
    val padding = ceil(blur * 2).toInt()
    val bitmap = android.graphics.Bitmap.createBitmap(ceil(size.width).toInt() + 2 * padding,
        ceil(size.height).toInt() + 2 * padding + 4.dp.roundToPx(), android.graphics.Bitmap.Config.ARGB_8888)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x33000000
        maskFilter = android.graphics.BlurMaskFilter(blur, android.graphics.BlurMaskFilter.Blur.NORMAL)
    }
    val origin = padding.toFloat()
    android.graphics.Canvas(bitmap).drawRect(origin + 2.dp.toPx(), origin + 6.dp.toPx(),
        origin + size.width - 2.dp.toPx(), origin + size.height + 2.dp.toPx(), paint)
    val shadow = bitmap.asImageBitmap()
    onDrawBehind { drawImage(shadow, topLeft = androidx.compose.ui.geometry.Offset(-origin, -origin)) }
}

@Composable
fun ShortsLogo(modifier: Modifier = Modifier) {
    Box(modifier.size(24.dp)) {
        AppIcon(AppIcon.ShortsBody, color = DesignTokens.Red)
        AppIcon(AppIcon.ShortsPlay, color = Color.White)
    }
}

@Composable
fun AppIcon(icon: AppIcon, modifier: Modifier = Modifier, color: Color = DesignTokens.Ink) {
    val paths = remember(icon) { icon.paths.map { PathParser().parsePathString(it).toPath().asAndroidPath() } }
    Spacer(modifier.size(24.dp).drawWithCache {
        // Cache at the displayed pixel size so vector edges retain anti-aliasing on every renderer.
        val bitmap = android.graphics.Bitmap.createBitmap(ceil(size.width).toInt().coerceAtLeast(1),
            ceil(size.height).toInt().coerceAtLeast(1), android.graphics.Bitmap.Config.ARGB_8888)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { this.color = color.toArgb() }
        val canvas = android.graphics.Canvas(bitmap).apply { scale(size.width / 24f, size.height / 24f) }
        paths.forEach { canvas.drawPath(it, paint) }
        val raster = bitmap.asImageBitmap()
        onDrawBehind { drawImage(raster) }
    })
}

@Composable
fun AppIconButton(
    icon: AppIcon, description: String, modifier: Modifier = Modifier,
    diameter: Dp = 48.dp, background: Color = Color.Transparent, color: Color = DesignTokens.Ink, onClick: () -> Unit,
) {
    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    Box(modifier.size(diameter).clip(CircleShape)
        .background(if (hovered) DesignTokens.Border else background)
        .hoverable(interactions)
        .clickable(interactionSource = interactions, indication = null, role = Role.Button, onClick = onClick)
        .semantics { contentDescription = description }, contentAlignment = Alignment.Center) { AppIcon(icon, color = color) }
}

@Composable
fun LitubeMark(modifier: Modifier = Modifier) {
    Image(painterResource(R.drawable.ic_litube_mark), contentDescription = "Litube", modifier = modifier)
}

@Composable
fun LitubeBrand(modifier: Modifier = Modifier) {
    CompositionLocalProvider(LocalDensity provides androidx.compose.ui.unit.Density(LocalDensity.current.density, 1f)) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        LitubeMark(Modifier.size(28.dp))
        Spacer(Modifier.width(3.dp))
        AppText("Litube", size = 22, line = 28, weight = FontWeight.Black)
    }
    }
}
