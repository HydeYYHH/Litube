# Preserve the shared test runtime and verification entry points.
-keep class kotlin.** { *; }
-keep class com.hhst.youtubelite.player.sponsor.SponsorBlockManager$Companion { public *; }
-keep class com.hhst.youtubelite.player.sponsor.SponsorBlockManager$Segment { *; }
-keep class com.hhst.youtubelite.player.sponsor.SponsorSkipController { *; }
-keep class com.hhst.youtubelite.extension.SettingsBackup { public *; }
-keepclassmembers class com.hhst.youtubelite.extension.SettingsBackupData { public <methods>; }
-keepclassmembers class com.hhst.youtubelite.extension.DownloadBackupData { public <methods>; }
