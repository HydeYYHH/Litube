package com.hhst.youtubelite.core

import org.junit.Test

class StorageMinifiedAndroidTest {
    @Test fun cacheRecordsSurviveOptimization() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        StorageVerification.verify(context)
    }
}
