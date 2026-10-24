package com.hhst.youtubelite.post

/**
 * Production route for post like/dislike taps. Anonymous post pages expose no vote endpoints
 * at all — the web itself answers these buttons with a sign-in route (checklist B9 probe,
 * PostVoteProbe evidence). A signed-in vote endpoint has never been observed, so for signed-in
 * users the honest answer is a limitation notice rather than a fake success.
 */
fun openPostVoteRoute(activity: android.app.Activity) {
    if (!com.hhst.youtubelite.data.media.MediaSession.signedIn()) {
        com.hhst.youtubelite.account.SignInFlow.open(activity)
    } else {
        android.widget.Toast.makeText(activity, "Voting on posts isn't available yet.", android.widget.Toast.LENGTH_LONG).show()
    }
}
