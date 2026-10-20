package com.hhst.youtubelite.share

import android.content.Intent

/**
 * The mobile web shares a single URL through `navigator.share`. Local fixture ids are
 * list identities, so they are mapped to the public Blender videos they stand in for;
 * strings that are already valid YouTube ids are used unchanged.
 */
object VideoShare {
    private val fixtureIds = mapOf(
        "bunny" to "aqz-KE-bpKQ",
        "dream" to "TLkA0RELQ1g",
        "sintel" to "eRsGyueVLvQ",
        "harbour" to "TLkA0RELQ1g",
        "studio" to "aqz-KE-bpKQ",
        "meadow" to "eRsGyueVLvQ",
        "workshop" to "aqz-KE-bpKQ",
        "orbit" to "eRsGyueVLvQ",
        "river" to "TLkA0RELQ1g",
        "live" to "TLkA0RELQ1g",
        "short-1" to "aqz-KE-bpKQ",
        "short-2" to "TLkA0RELQ1g",
        "short-3" to "TLkA0RELQ1g",
        "short-4" to "aqz-KE-bpKQ",
        "short-5" to "TLkA0RELQ1g",
        "short-6" to "TLkA0RELQ1g",
        "short-7" to "aqz-KE-bpKQ",
        "short-8" to "TLkA0RELQ1g",
    )

    private val videoIdPattern = Regex("[A-Za-z0-9_-]{11}")

    /** Returns a public watch URL, or null rather than fabricating a link for an unknown local id. */
    fun urlFor(videoId: String): String? {
        val canonical = fixtureIds[videoId]
            ?: videoId.takeIf { videoIdPattern.matches(it) }
            ?: return null
        return "https://youtu.be/$canonical"
    }

    fun sendIntent(url: String): Intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, url)
    }
}
