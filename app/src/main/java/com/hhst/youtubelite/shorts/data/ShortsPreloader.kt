package com.hhst.youtubelite.shorts.data

interface ShortsPreloader {
    fun select(videoId: String)
    fun preload(videoId: String?)
    fun cancel()
}
