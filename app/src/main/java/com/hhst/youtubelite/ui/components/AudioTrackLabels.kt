package com.hhst.youtubelite.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.core.os.ConfigurationCompat
import com.hhst.youtubelite.R
import com.hhst.youtubelite.player.datasource.AudioTrackChoice
import java.util.Locale

@Composable
fun audioTrackLabel(choice: AudioTrackChoice): String {
    val locale = ConfigurationCompat.getLocales(LocalConfiguration.current)[0] ?: Locale.getDefault()
    val language = choice.languageTag?.takeIf { it.isNotBlank() }?.let {
        Locale.forLanguageTag(it.replace('_', '-')).getDisplayName(locale).ifBlank { it }
    } ?: choice.trackName?.takeIf { it.isNotBlank() }
        ?: if (choice.trackType != null) stringResource(R.string.download_audio) else choice.label
    val type = when (choice.trackType?.lowercase(Locale.ROOT)) {
        "original" -> stringResource(R.string.download_audio_original)
        "dubbed" -> stringResource(R.string.download_audio_dubbed)
        "descriptive" -> stringResource(R.string.audio_description)
        "secondary" -> stringResource(R.string.secondary_audio)
        else -> null
    }
    return if (type == null) language else "$language · $type"
}
