package com.hhst.youtubelite.subscriptions

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.hhst.youtubelite.subscriptions.data.FixtureSubscriptionsRepository
import com.hhst.youtubelite.subscriptions.domain.SubscriptionsChannels
import com.hhst.youtubelite.subscriptions.domain.SubscriptionsContent
import com.hhst.youtubelite.subscriptions.domain.SubscriptionsOverlay
import com.hhst.youtubelite.subscriptions.domain.SubscriptionsPage
import com.hhst.youtubelite.subscriptions.domain.SubscriptionsUiState
import com.hhst.youtubelite.subscriptions.presentation.SubscriptionsScreen
import com.hhst.youtubelite.ui.design.AppTheme
import java.io.File
import java.util.Locale
import org.junit.Rule
import org.junit.Test

class SubscriptionsPresentationAndroidTest {
    @get:Rule val compose = createComposeRule()

    @Test fun refreshedUpdatesBecomeVisibleAtTheStartOfTheChannelStrip() {
        val original = (0 until 8).map { index ->
            com.hhst.youtubelite.subscriptions.domain.SubscribedChannel("channel-$index", "Channel $index", "bunny", "")
        }
        val channels = mutableStateOf(original)
        compose.setContent {
            AppTheme {
                Box(Modifier.requiredSize(390.dp, 800.dp)) {
                    SubscriptionsScreen(SubscriptionsUiState(signedIn = true,
                        channels = SubscriptionsChannels.Ready(channels.value),
                        content = SubscriptionsContent.Feed(SubscriptionsPage(emptyList(), emptyList()))), {})
                }
            }
        }
        compose.onNodeWithTag("subs-strip-channel-0").assertIsDisplayed()
        compose.runOnIdle {
            channels.value = listOf(original.last().copy(hasNewContent = true)) + original.dropLast(1)
        }
        compose.onNodeWithTag("subs-strip-channel-7").assertIsDisplayed()
        compose.onNodeWithTag("subs-new-channel-7", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun channelNavigationFitsDifferentScriptsAndLargeText() {
        val language = mutableStateOf("en")
        val fontScale = mutableStateOf(1f)
        val directory = mutableStateOf(false)
        val channels = FixtureSubscriptionsRepository.channels().mapIndexed { index, channel ->
            channel.copy(name = listOf("Blender Foundation", "مؤسسة الأفلام", "日本の映画制作")[index])
        }
        compose.setContent {
            val base = LocalContext.current
            val config = Configuration(LocalConfiguration.current).apply {
                setLocale(Locale.forLanguageTag(language.value))
                this.fontScale = fontScale.value
            }
            val context = base.createConfigurationContext(config)
            val direction = if (config.layoutDirection == android.view.View.LAYOUT_DIRECTION_RTL)
                LayoutDirection.Rtl else LayoutDirection.Ltr
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides config,
                LocalLayoutDirection provides direction, LocalDensity provides Density(density, fontScale.value)) {
                AppTheme {
                    key(language.value, fontScale.value, directory.value) {
                        Box(Modifier.requiredSize(390.dp, 800.dp)) {
                            SubscriptionsScreen(SubscriptionsUiState(signedIn = true,
                                channels = SubscriptionsChannels.Ready(channels),
                                content = SubscriptionsContent.Feed(SubscriptionsPage(
                                    FixtureSubscriptionsRepository.sampleVideos(), channels)),
                                overlay = if (directory.value) SubscriptionsOverlay.Channels else SubscriptionsOverlay.None), {})
                        }
                    }
                }
            }
        }
        for (tag in listOf("en", "de", "ar", "ja", "hi")) {
            for (scale in listOf(1f, 2f)) {
                for (showDirectory in listOf(false, true)) {
                    compose.runOnIdle {
                        language.value = tag
                        fontScale.value = scale
                        directory.value = showDirectory
                    }
                    compose.waitForIdle()
                    compose.onNodeWithTag(if (showDirectory) "channels-row-blender" else "subscriptions-manage")
                        .assertIsDisplayed()
                    val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
                    val output = File(context.getExternalFilesDir(null), "subscriptions-ui").apply { mkdirs() }
                    val name = "${if (showDirectory) "directory" else "feed"}-$tag-${scale.toInt()}.png"
                    File(output, name).outputStream().use {
                        compose.onNodeWithTag("subscriptions-screen").captureToImage().asAndroidBitmap()
                            .compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                }
            }
        }
    }
}
