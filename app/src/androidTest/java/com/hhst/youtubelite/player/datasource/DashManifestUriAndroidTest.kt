@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hhst.youtubelite.player.datasource

import android.net.Uri
import androidx.media3.datasource.DataSchemeDataSource
import androidx.media3.datasource.DataSourceInputStream
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import androidx.media3.exoplayer.dash.manifest.DashManifest
import com.hhst.youtubelite.extractor.Format
import org.junit.Assert.*
import org.junit.Test

class DashManifestUriAndroidTest {
    @Test fun codecLevelsShareOneParsedAdaptiveSet() {
        val formats = listOf("avc1.640028", "avc1.4d401f").mapIndexed { index, codec ->
            Format(url = "https://media.invalid/v$index?sig=a%2Bb%3D", itag = 137 - index,
                videoOnly = true, mimeType = "video/mp4; codecs=\"$codec\"", codec = codec,
                initStart = 0, initEnd = 99, indexStart = 100, indexEnd = 796)
        }
        val manifest = parseDashManifest(DashManifestFactory.dataUri(DashManifestFactory.buildVideoPool(formats, 1_000)))
        val group = manifest.getPeriod(0).adaptationSets.single()
        assertEquals(2, group.representations.size)
        assertEquals(formats.map { it.codec }, group.representations.map { it.format.codecs })
        assertTrue(group.representations.all { it.format.sampleMimeType == "video/avc" })
    }

    @Test fun media3RetainsSignedEscapesAndLiteralPlusSigns() {
        val url = "https://rr1.googlevideo.com/videoplayback?itag=135&sig=a%2Bb%2Fc%3D&spc=x+y%2Bz&sparams=expire%2Cid%2Citag"
        val format = Format(url = url, videoOnly = true, mimeType = "video/mp4", codec = "avc1.4d401e",
            initStart = 0, initEnd = 99, indexStart = 100, indexEnd = 796)
        val mpd = DashManifestFactory.build(format, 1_000)
        // Reproduce the previous failure through Android and Media3, not a substitute decoder.
        val oldUri = Uri.parse("data:application/dash+xml," + Uri.encode(mpd))
        assertNotEquals(url, parseDashBaseUrl(oldUri))
        assertEquals(url, parseDashBaseUrl(DashManifestFactory.dataUri(mpd)))
    }
}

internal fun parseDashBaseUrl(uri: Uri): String {
    return parseDashManifest(uri).getPeriod(0).adaptationSets.single().representations.single().baseUrls.single().url
}

private fun parseDashManifest(uri: Uri): DashManifest {
    val spec = DataSpec.Builder().setUri(uri).build()
    return DataSourceInputStream(DataSchemeDataSource(), spec).use {
        DashManifestParser().parse(uri, it)
    }
}
