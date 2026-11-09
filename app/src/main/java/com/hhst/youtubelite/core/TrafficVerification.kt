package com.hhst.youtubelite.core

import com.liskovsoft.youtubeapi.videoinfo.V2.RequestError
import com.liskovsoft.youtubeapi.videoinfo.V2.RequestFailure

/**
 * A `/sorry` traffic challenge the server served instead of content. Solving
 * [challengeUrl] in a browser may lift the block, so completion grants exactly
 * one retry of the original read.
 */
data class VerificationRequired(val challengeUrl: String)

/** True only for the explicit challenge classification — plain 403/429 are rate or auth errors. */
fun RequestError.isTrafficChallenge(): Boolean =
    kind == RequestFailure.VERIFICATION && UrlPolicy.isTrafficChallenge(detail)

fun trafficChallenge(error: com.hhst.youtubelite.data.media.MediaException): VerificationRequired? =
    if (error.failure.isTrafficChallenge()) error.failure.detail?.let(::VerificationRequired) else null
