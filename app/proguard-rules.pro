-keep class org.koin.** { *; }
-dontwarn org.koin.**

-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Gson reflection requires model classes, fields, and generic signatures.
-keepattributes Signature
-keepattributes InnerClasses,EnclosingMethod
-keepattributes *Annotation*
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

-keep class com.hhst.youtubelite.player.QueueItem { <init>(...); <fields>; }
-keep class com.hhst.youtubelite.player.QueueState { <init>(...); <fields>; }
-keep class com.hhst.youtubelite.player.engine.ResumePoint { <init>(...); <fields>; }
-keep class com.hhst.youtubelite.player.surface.SubtitleStyle { <init>(...); <fields>; }
-keep class com.hhst.youtubelite.player.sponsor.SponsorBlockParser$Response { <init>(...); <fields>; }
-keep class com.hhst.youtubelite.player.sponsor.SponsorBlockParser$SegmentData { <init>(...); <fields>; }
-keep class com.hhst.youtubelite.extractor.Format { <init>(...); <fields>; }
-keep class com.hhst.youtubelite.extractor.PoIntegrity { <init>(...); <fields>; }
-keep class org.schabi.newpipe.extractor.services.youtube.streams.FormatKey { <init>(...); <fields>; }
-keep class com.hhst.youtubelite.extractor.Metadata { <init>(...); <fields>; }
-keep class com.hhst.youtubelite.extractor.Subtitle { <init>(...); <fields>; }
-keep class com.hhst.youtubelite.extractor.Chapter { <init>(...); <fields>; }
-keep class com.hhst.youtubelite.extractor.Stream { <init>(...); <fields>; }
-keep class com.hhst.youtubelite.extractor.ChapterList { <init>(...); <fields>; }
-keep class com.hhst.youtubelite.extension.InjectReport { <init>(...); <fields>; }
-keep class com.hhst.youtubelite.extension.InjectFailure { <init>(...); <fields>; }
-keep class com.hhst.youtubelite.extension.SettingsBackupData { <init>(...); <fields>; }
-keep class com.hhst.youtubelite.extension.DownloadBackupData { <init>(...); <fields>; }
-keep class com.hhst.youtubelite.diagnostics.DiagnosticEvent { <init>(...); <fields>; }
-keep class com.hhst.youtubelite.diagnostics.DiagnosticContext { <init>(...); <fields>; }
-keep class com.hhst.youtubelite.diagnostics.DiagnosticCause { <init>(...); <fields>; }
-keep class com.hhst.youtubelite.diagnostics.DiagnosticFailure { <init>(...); <fields>; }
-keepclassmembers enum com.hhst.youtubelite.diagnostics.** { *; }
-keep class com.hhst.youtubelite.downloader.core.DownloadConfig { <init>(...); <fields>; }
-keep class com.hhst.youtubelite.downloader.core.EnqueueResult { <init>(...); <fields>; }
-keep class com.hhst.youtubelite.downloader.core.TaskRef { <init>(...); <fields>; }
-keep class com.hhst.youtubelite.browser.Bridge$QueueItemJson { <init>(...); <fields>; }
-keep class com.hhst.youtubelite.core.MmkvJsonCache$Entry { <init>(...); <fields>; }

-keep class com.hhst.youtubelite.downloader.engine.DownloadTransferWorker { *; }
-keep class com.hhst.youtubelite.downloader.engine.DownloadFinalizeWorker { *; }
-keep class com.hhst.youtubelite.downloader.engine.DownloadUidtJobService { *; }
-keep class com.hhst.youtubelite.downloader.engine.KoinDownloadWorkerFactory { *; }

# Optional jsoup / NewPipe paths unused on Android.
-dontwarn java.beans.**
-dontwarn com.google.re2j.**
-dontwarn org.mozilla.javascript.engine.**

