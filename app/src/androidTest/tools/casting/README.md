# Software receiver interoperability tests

`media_sample_server.py` is a byte-range-capable HTTP media origin. It is not a
DLNA renderer. Use an independent receiver to verify actual decoding and controls.

## DLNA with Kodi

1. Start Kodi and enable **Settings > Services > UPnP/DLNA > Enable UPnP support**
   and **Allow remote control via UPnP**. Library sharing alone does not enable
   the renderer. [Kodi documentation](https://kodi.wiki/view/Settings/Services/UPnP_DLNA).
2. Serve a seekable MP4 of at least 25 seconds:

   ```text
   python media_sample_server.py --file sample.mp4 --bind PC_IP --port 48081
   ```

3. Obtain the renderer's description URL from its SSDP `LOCATION` advertisement.
   Install matching debug application and Android test APKs, then run:

   ```text
   adb shell am instrument -w -e network 1 -e dlnaDescriptionUrl DESCRIPTION_URL -e dlnaMediaUrl http://PC_IP:48081/sample.mp4 -e class com.hhst.youtubelite.cast.DlnaSoftwareRendererAndroidTest com.hhst.litube.debug.test/com.hhst.youtubelite.ContentTestRunner
   ```

The test uses the production `RealDlnaEngine`, including network selection,
description parsing and SOAP transport. It checks advancing playback, a stable
position while paused, forward seek to 20 seconds, backward seek to 4 seconds,
resume, seek while playing and stop. Optional `-e dlnaHoldMs 10000` holds each observable stage for
visual inspection. Use synthetic media and an explicitly selected receiver: the
test takes over that receiver's current playback and stops it afterward.

An Android emulator's NAT may prevent multicast discovery or inbound media
connections. Supplying the SSDP description URL tests control independently of
that emulator limitation; use a media URL the receiver itself can reach.

To include Litube's Android HTTP media proxy in an emulator test, forward a
desktop port into the emulator and add the proxy arguments:

```text
adb forward tcp:48082 tcp:48082
```

Append `-e dlnaProxyEndpoint http://127.0.0.1:48082 -e dlnaProxyListenPort 48082
-e dlnaMediaBytes FILE_SIZE` to the instrumentation command. `FILE_SIZE` must be
the exact MP4 size in bytes. Kodi runs on the desktop, so its loopback URL reaches
the emulator through adb; the Android proxy fetches the original media URL and
serves the real playback requests. Keep both ports identical because the proxy
validates the HTTP Host header. Remove the forward afterward using
`adb forward --remove tcp:48082`.

## Chromecast software receiver

```text
adb shell am instrument -w -e network 1 -e castReceiverHost PC_IP -e castReceiverPort PORT -e castMediaUrl http://PC_IP:48081/sample.mp4 -e class com.hhst.youtubelite.cast.CastSoftwareReceiverAndroidTest com.hhst.litube.debug.test/com.hhst.youtubelite.ContentTestRunner
```

For DASH use a served MPD plus all referenced segments and add
`-e castMediaType application/dash+xml`. Production DeviceAuth trust verification
is used. The test checks receiver launch, play, pause, seek and resume.

## Quality changes through the production player

Generate four-minute H.264/AAC samples with visible resolution and timecode:

```text
python quality_samples.py --output SAMPLE_DIRECTORY --ffmpeg PATH_TO_FFMPEG
python media_sample_server.py --directory SAMPLE_DIRECTORY --bind 0.0.0.0 --port 48083
adb forward tcp:48082 tcp:48082
```

Install matching application/test APKs. On the standard Android emulator the
origin is `http://10.0.2.2:48083`; use a reachable PC address on a physical phone.
For a desktop receiver and emulator, use the same forwarded proxy port on both
sides. This substitutes only the proxy's advertised endpoint for emulator NAT;
the real player engine, view model, media proxy, controllers and Cast DeviceAuth
remain in the playback path.

```text
adb shell am instrument -w -e network 1 -e qualityRoute dlna -e qualityMediaBase http://10.0.2.2:48083 -e qualityProxyPort 48082 -e dlnaDescriptionUrl DESCRIPTION_URL -e qualityHoldMs 4000 -e class com.hhst.youtubelite.cast.CastQualitySoftwareReceiverAndroidTest#manualQualityAndAutoOnKodi com.hhst.litube.debug.test/com.hhst.youtubelite.ContentTestRunner
adb shell am instrument -w -e network 1 -e qualityRoute chromecast -e qualityMediaBase http://10.0.2.2:48083 -e qualityProxyPort 48082 -e castReceiverHost PC_IP -e castReceiverPort PORT -e qualityHoldMs 4000 -e qualityControls 1 -e class com.hhst.youtubelite.cast.CastQualitySoftwareReceiverAndroidTest#manualQualityAndAutoOnCastReceiver com.hhst.litube.debug.test/com.hhst.youtubelite.ContentTestRunner
```

The test seeks into the video, switches 360p/720p repeatedly, restores automatic
selection, repeats while paused, and resumes. It checks receiver state and
position as well as the selected source and UI label. `qualityControls=1` also
switches between the 440/880 Hz audio samples and measures 2x/1x speed using
receiver-reported positions and the timestamps of those status samples. A speed
assertion failing is a failed acceptance result, even if the phone displays the
requested rate. The receiver is stopped at the end, including on failure.

Use a fresh media origin URL after regenerating samples to avoid stale player
cache entries. A failed Chromecast wait writes the current MPD to the target
app's `files/device-evidence/cast-quality-failed.mpd` for inspection. Remove the
adb forward after testing.
