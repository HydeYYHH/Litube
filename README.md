Litube
============

Litube is an Android video client with a native Compose interface, native playback and built-in downloads.

Requires Android 6.0 (API 23) or later and an up-to-date Android System WebView. Picture-in-Picture is available on Android 8.0 or later when the device supports it.

## Releases

Download installation packages and read release notes on the [releases page](https://github.com/HydeYYHH/Litube/releases).

MediaServiceCore provides browsing, playback and content reads. Sign-in uses device-code OAuth; signed-out browsing uses a persistent device identity. Private mode disables watch reporting.

## Features

* Ad-free playback and configurable sponsor segment skipping.
* Community-submitted titles and thumbnails (DeArrow), off by default.
* Native controls for quality, playback speed, subtitles and audio tracks.
* Audio-only playback, background playback, picture-in-picture and a mini-player.
* Local queues, playlist playback and autoplay.
* Local history and Watch later that work for signed-out use.
* Video, audio and playlist downloads with quality and language selection.
* Search, channel and playlist browsing with native content pages.
* Casting through an open-source sender.
* Settings backup and restore.
* Diagnostic reports that can be saved or shared.

## Building

Use JDK 21 and the Android SDK. Initialize the core checkout before building a fresh clone:

```sh
git submodule update --init external/mediaservicecore
./gradlew :app:assembleDebug
```

`:media-core` compiles the core's standalone `common` sources. The app owns domain models and converts service data in `data/media`; content pages use feature packages with ViewModels and immutable state.

```sh
./gradlew :app:testDebugUnitTest :media-core:test :app:lintDebug
```

Instrumented tests use local content by default. Network read checks require the explicit `network=1` runner argument. Playback, downloads and extraction retain their existing engines.

## Remaining integration

* End-to-end validation of signed-in playback, downloaded files and casting on a receiver.
* On-device verification of the community services (SponsorBlock, DeArrow) against the live endpoints.
* Voice search and some live chat interactions on real devices.

## Published release screenshots

<img title="" src="https://github.com/HydeYYHH/litube/blob/master/fastlane/metadata/android/en-US/images/phoneScreenshots/1.png" alt="" width="200"><img title="" src="https://github.com/HydeYYHH/litube/blob/master/fastlane/metadata/android/en-US/images/phoneScreenshots/2.jpg" alt="" width="200"><img title="" src="https://github.com/HydeYYHH/litube/blob/master/fastlane/metadata/android/en-US/images/phoneScreenshots/3.png" alt="" width="200">


## Contributing

Report issues with the app version, device model, Android version and steps to reproduce. Include a diagnostic archive when available. Contributions and pull requests are welcome.
