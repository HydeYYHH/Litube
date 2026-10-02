# Download UI visual QA — 2026-09-16

Accepts the same five scenes defined in `VISUAL_REFS.md` (YouTube Help Android, fetched 2026-09-16). These overlays use **project tokens**, not a new scene list.

## Token map (do not approximate)

| Token | Value | Scene |
| --- | --- | --- |
| Light background | `#FFFFFF` | sheet + manager light |
| Dark background | `#0F0F0F` | scenes 01–05 dark chrome |
| Page inset | 16 dp | manager / settings / sheet |
| Icon | 24 dp | Download / pause / resume |
| Min touch | 48 dp | primary actions |
| Sheet top corners | 28 dp | confirm sheet |
| Thumbnails | 16:9 | manager rows + sheet |
| Title | max 2 lines | rows + sheet |
| Weight | title > author > quality/size > phase | rows |
| Lists | flat, hairline dividers, capsule chips | 03, 04 |
| Cards | none | — |

Compose sources: `DownloadTokens.kt`, `DownloadComponents.kt`, `DownloadSheets.kt`, `DownloadScreens.kt`.

## Scene acceptance

| id | Reference | App surface | Status |
| --- | --- | --- | --- |
| 01 | `01-watch-page-download-entry.svg` | `DownloadWatchEntry` + `SingleVideoConfirmSheet` | Implemented. Watch-row **Download** capsule is the script injection target; the sheet is the confirm UI that entry opens. |
| 02 | `02-completed-state.svg` | `DownloadedBadge` + row complete phase | Implemented. Badge requires a published **video or audio** asset — subtitle/cover-only never shows **Downloaded**. Official black-on-light (Help 11977233) is the default; blue-label regional variant is accepted, not a second layout. |
| 03 | `03-download-manager.svg` | `DownloadActivity` list + All / In progress / Completed | Implemented. Light+dark evidence SVGs below. |
| 04 | `04-quality-settings.svg` | Settings: Wi-Fi only, Download quality, Delete downloads | Implemented. Copy matches Help “Background & downloads”. |
| 05 | `05-resume-after-network.svg` | Row phase **Waiting for network** + Resume | Implemented. Copy: progress resumes automatically on reconnect; Resume is a legal UIDT tap. |

## Automated evidence (this tree)

- `visual-qa/sheet-dark.svg` / `sheet-light.svg` — single-video confirm sheet (scene 01 destination)
- `visual-qa/manager-dark.svg` / `manager-light.svg` — DownloadActivity (scene 03)
- Unit tests pin tokens, phases, size kinds, badge rules, snapshot reject, no auto-submit: `DownloadPresentationTest`, `DownloadConfirmViewModelTest`

Paparazzi/Roborazzi are **not** in the project; adding a screenshot framework was declined in favor of token-locked SVGs + JVM mapping tests.

## Manual verification remaining

TalkBack on a device, 200% font (Download button must not overlap), landscape sheet on a phone. Layout uses sticky 48 dp Download, `navigationBarsPadding`, and a landscape thumbnail+info row so primary actions stay on-screen.
