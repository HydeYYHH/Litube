package com.hhst.youtubelite.downloader.io

/** JVM-safe published-URI checks; avoids [android.net.Uri] so unit tests need no Robolectric. */
object DownloadPublishedUris {

    fun isOpenable(raw: String?): Boolean {
        if (raw.isNullOrBlank()) return false
        val value = raw.trim()
        return when {
            value.startsWith("content://") -> {
                val rest = value.removePrefix("content://")
                val authority = rest.substringBefore('/', missingDelimiterValue = "")
                authority.isNotBlank() && rest.contains('/')
            }
            value.startsWith("file://") -> value.length > "file://".length
            else -> false
        }
    }
}
