# Downloader visual references (YouTube Android)

Captured 2026-09-16. These are the scene set the visual QA document reuses.

Official primary source (Android platform selector):
https://support.google.com/youtube/answer/11977233?co=GENIE.Platform%3DAndroid&hl=en
Fetched 2026-09-16. Article body (via Google Help fetch) documents all five scenes below.
Media3 muxer API page last updated **2026-08-06 UTC** (codec list, not UI).

Companion official Android articles fetched the same day:
- Offline in select regions: https://support.google.com/youtube/answer/6141269?co=GENIE.Platform%3DAndroid&hl=en
- Premium benefits (Background & downloads): https://support.google.com/youtube/answer/6308116?co=GENIE.Platform%3DAndroid&hl=en

The live Help Center pages are a JS shell and did not embed device screenshots in the fetched HTML. Scene boards in `visual-refs/` are schematic reconstructions of the **official Android UX copy**, not Play Store marketing shots. Device captures should be overlaid onto this same scene list.

Public corroboration with dates (not a replacement for Help Center):
- Android Police “How to download YouTube videos”, updated **2023-06-15**: watch-page Download, quality picker, completed **Downloaded** with black tick. https://www.androidpolice.com/how-to-download-youtube-videos/
- Android Police “YouTube’s massive UI overhaul just hit Android”, published **2025-10-24**: Android player chrome overhaul. https://www.androidpolice.com/youtubes-biggest-redesign-in-years-is-now-rolling-out-to-android/
- Android Authority redesign test, app version **21.23.487** (2026): Download may move into the overflow menu in a limited experiment. The Help Center watch-page Download control remains the baseline. https://www.androidauthority.com/youtube-ui-redesign-test-again-3678953/

## Scene set

| id | file | scene | official behavior (source date) |
| --- | --- | --- | --- |
| 01 | `visual-refs/01-watch-page-download-entry.svg` | Watch-page download entry | Watch page → control row below the player → **Download**. Source: answer/11977233 Android, fetched 2026-09-16. |
| 02 | `visual-refs/02-completed-state.svg` | Completed state | After download, the control reads **Downloaded** and the icon turns **black**. Source: answer/11977233, fetched 2026-09-16. (answer/6141269 says the label turns **blue** in some regions; both official completed treatments are accepted.) |
| 03 | `visual-refs/03-download-manager.svg` | Download manager | Profile → Library → Recents → **Downloads** playlist. Remove via **Downloaded → Delete** or playlist **More → Delete from downloads**. Source: answer/11977233, fetched 2026-09-16. |
| 04 | `visual-refs/04-quality-settings.svg` | Quality settings | Profile → Settings → **Background & downloads → Download quality**. Higher quality uses more data/storage and takes longer. Source: answer/11977233 + 6141269, fetched 2026-09-16. |
| 05 | `visual-refs/05-resume-after-network.svg` | Resume after network | If connectivity is lost mid-download, progress **resumes automatically** on reconnect. Source: answer/11977233, fetched 2026-09-16. |

## Official copy (verbatim from Help Center fetch, 2026-09-16)

Watch-page entry: "Go to the Watch page of the video you’d like to download. Below the video, click Download."

Completed: "Once the video is downloaded, the download icon will turn black below the video."

Resume: "If your device loses internet connectivity while downloading videos, your progress will resume automatically once you reconnect to the internet."

Manager: "Tap your profile picture. In your Library under Recents, select the Downloads playlist from the list of playlists."

Quality: "In Settings, select Background & downloads Download quality."

Delete-all: "Settings → Background & downloads → Delete downloads."
