package com.hhst.youtubelite.extractor

import java.io.IOException

class InvalidMediaResponseException(
    val statusCode: Int,
    val contentType: String,
    val method: String,
    val client: StreamClient,
) : IOException("MEDIA_ERROR_BODY")
