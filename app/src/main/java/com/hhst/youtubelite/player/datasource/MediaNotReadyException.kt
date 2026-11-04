package com.hhst.youtubelite.player.datasource

import java.io.IOException

internal class MediaNotReadyException(val retryAtNanos: Long, cause: IOException) :
    IOException("MEDIA_NOT_READY", cause)
