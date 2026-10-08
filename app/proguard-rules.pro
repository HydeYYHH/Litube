-keep class org.koin.** { *; }
-dontwarn org.koin.**

-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Gson models persist queue, progress, captions, extraction, and inject reports.
# -keep class (not only keepclassmembers): R8 otherwise abstracts classes that
# are only constructed via Gson.fromJson, which crashes WebView inject callbacks.
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

-keep class com.hhst.youtubelite.player.QueueItem { <init>(...); <fields>; }
-keep class com.hhst.youtubelite.player.QueueState { <init>(...); <fields>; }
-keep class com.hhst.youtubelite.player.engine.ResumePoint { <init>(...); <fields>; }
-keep class com.hhst.youtubelite.player.surface.SubtitleStyle { <init>(...); <fields>; }
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

