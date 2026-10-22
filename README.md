Litube
============

Litube is an Android video client with a native Compose interface, native playback and built-in downloads.

Requires Android 6.0 (API 23) or later and an up-to-date Android System WebView. Picture-in-Picture is available on Android 8.0 or later when the device supports it.

## Releases

Download installation packages and read release notes on the [releases page](https://github.com/HydeYYHH/Litube/releases).

This development branch is being connected to MediaServiceCore. Browsing uses the service directly; some account operations still have interface-only entry points. Sign-in uses a separate window with the existing shared session.

## Features

* Ad-free playback and configurable sponsor segment skipping.
* Native controls for quality, playback speed, subtitles and audio tracks.
* Audio-only playback, background playback, picture-in-picture and a mini-player.
* Local queues, playlist playback and autoplay.
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

`:media-core` compiles only the core's `common` sources. Its old Android projects and nested dependencies are not included. The app owns domain models and converts service data in `data/media`; content pages use feature packages with ViewModels and immutable state.

```sh
./gradlew :app:testDebugUnitTest :media-core:test :app:lintDebug
```

Instrumented tests use local content by default. Network read checks require the explicit `network=1` runner argument. Playback, downloads and extraction retain their existing engines.

## Remaining integration

* Account and brand-account selection, history tracking and remaining account writes.
* Continuation and mixed result ordering across all browsing screens.
* Remaining Shorts, voice search and live chat interactions.
* End-to-end validation of signed-in playback, downloaded files and casting on a receiver.

## Published release screenshots

<img title="" src="https://github.com/HydeYYHH/litube/blob/master/fastlane/metadata/android/en-US/images/phoneScreenshots/1.png" alt="" width="200"><img title="" src="https://github.com/HydeYYHH/litube/blob/master/fastlane/metadata/android/en-US/images/phoneScreenshots/2.jpg" alt="" width="200"><img title="" src="https://github.com/HydeYYHH/litube/blob/master/fastlane/metadata/android/en-US/images/phoneScreenshots/3.png" alt="" width="200">


## Contributing

Report issues with the app version, device model, Android version and steps to reproduce. Include a diagnostic archive when available. Contributions and pull requests are welcome.
