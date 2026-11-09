package com.hhst.youtubelite.di

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.di.appModule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.koinApplication
import org.koin.test.check.checkModules

/**
 * Runtime guard: every appModule definition must resolve. Type-checking cannot catch a
 * positional `get()` that silently retargets after a constructor param list changes;
 * this resolves the whole graph instead.
 */
@RunWith(AndroidJUnit4::class)
class KoinGraphAndroidTest {
    @Test fun everyAppModuleDefinitionResolves() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        koinApplication {
            androidContext(context)
            modules(appModule)
        }.checkModules()
    }
}
