/*
 * Adapted from AndroidX Media3 DefaultMediaSourceFactory.
 * Copyright The Android Open Source Project.
 * Licensed under the Apache License, Version 2.0.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package androidx.media3.exoplayer.source;

import android.net.Uri;
import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.datasource.DataSource;
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.text.DefaultSubtitleParserFactory;
import androidx.media3.extractor.text.SubtitleExtractor;

/** Media3 1.10.1 bridge: publish caption tracks without waiting for a sidecar HTTP response. */
@OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class LazySubtitleSourceFactory {
    private LazySubtitleSourceFactory() {}

    public static MediaSource create(DataSource.Factory transport, Format format, Uri uri) {
        DefaultSubtitleParserFactory parsers = new DefaultSubtitleParserFactory();
        if (!parsers.supportsFormat(format)) throw new IllegalArgumentException("Unsupported subtitle format");
        return new ProgressiveMediaSource.Factory(transport,
                () -> new Extractor[]{new SubtitleExtractor(parsers.create(format), null)})
                .enableLazyLoadingWithSingleTrack(SubtitleExtractor.TRACK_ID,
                        format.buildUpon().setSampleMimeType(MimeTypes.APPLICATION_MEDIA3_CUES)
                                .setCodecs(format.sampleMimeType)
                                .setCueReplacementBehavior(parsers.getCueReplacementBehavior(format)).build())
                .setLoadOnlySelectedTracks(true)
                // A denied caption must reach the engine's caption-only recovery immediately.
                .setLoadErrorHandlingPolicy(new DefaultLoadErrorHandlingPolicy(0) {
                    @Override public long getRetryDelayMsFor(LoadErrorInfo error) { return C.TIME_UNSET; }
                })
                .createMediaSource(MediaItem.fromUri(uri));
    }
}
