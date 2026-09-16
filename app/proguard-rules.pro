-keep class org.koin.** { *; }
-dontwarn org.koin.**

-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Gson models persist queue, progress, captions, extraction, and inject reports.
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

-keepclassmembers class com.hhst.youtubelite.player.queue.QueueItem { <fields>; }
-keepclassmembers class com.hhst.youtubelite.player.queue.QueueState { <fields>; }
-keepclassmembers class com.hhst.youtubelite.player.engine.ResumePoint { <fields>; }
-keepclassmembers class com.hhst.youtubelite.player.surface.SubtitleStyle { <fields>; }
-keepclassmembers class com.hhst.youtubelite.extractor.Format { <fields>; }
-keepclassmembers class com.hhst.youtubelite.extractor.Metadata { <fields>; }
-keepclassmembers class com.hhst.youtubelite.extractor.Subtitle { <fields>; }
-keepclassmembers class com.hhst.youtubelite.extractor.Chapter { <fields>; }
-keepclassmembers class com.hhst.youtubelite.extractor.Stream { <fields>; }
-keepclassmembers class com.hhst.youtubelite.extension.InjectReport { <fields>; }
-keepclassmembers class com.hhst.youtubelite.extension.InjectFailure { <fields>; }
-keepclassmembers class com.hhst.youtubelite.extension.InjectIconState { <fields>; }

# Optional jsoup / NewPipe paths unused on Android.
-dontwarn java.beans.**
-dontwarn com.google.re2j.**
-dontwarn org.mozilla.javascript.engine.**

