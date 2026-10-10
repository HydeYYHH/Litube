package com.hhst.youtubelite.extractor

import com.hhst.youtubelite.core.Constants
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException

/** Generic legacy transport. Explicit media extraction uses its own immutable host context. */
class HttpDownloader(
    private val client: OkHttpClient,
) : Downloader() {
    override fun execute(request: Request): Response {
        val builder = okhttp3.Request.Builder().url(request.url())
            .method(request.httpMethod(), request.dataToSend()?.toRequestBody())
        request.headers().forEach { (name, values) -> values.forEach { builder.addHeader(name, it) } }
        if (builder.build().header("User-Agent") == null) builder.header("User-Agent", Constants.userAgent())
        client.newCall(builder.build()).execute().use { response ->
            if (response.code == 429) throw ReCaptchaException("HTTP 429", request.url())
            return Response(response.code, response.message, response.headers.toMultimap(),
                response.body?.string().orEmpty(), response.request.url.toString())
        }
    }
}
