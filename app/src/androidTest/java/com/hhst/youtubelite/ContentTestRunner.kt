package com.hhst.youtubelite

import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner
import com.hhst.youtubelite.data.media.ContentSource

class ContentTestRunner : AndroidJUnitRunner() {
    override fun onCreate(arguments: Bundle) {
        ContentSource.fixtures = arguments.getString("network") != "1"
        super.onCreate(arguments)
    }
}
