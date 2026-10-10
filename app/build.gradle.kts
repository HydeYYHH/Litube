import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// File-content providers make the fingerprint respond to source changes with configuration caching.
val diagnosticDigest = MessageDigest.getInstance("SHA-256")
listOf(fileTree("src/main"), fileTree("../external/NewPipeExtractor/extractor/src/main"),
    files("build.gradle.kts", "proguard-rules.pro", "../gradle/libs.versions.toml", "../settings.gradle.kts").asFileTree)
    .flatMap { it.files }.sortedBy { it.invariantSeparatorsPath }.forEach { source ->
        diagnosticDigest.update(source.relativeTo(rootDir).invariantSeparatorsPath.toByteArray())
        diagnosticDigest.update(providers.fileContents(layout.projectDirectory.file(source.relativeTo(projectDir).path)).asBytes.get())
    }
val diagnosticBuildId = diagnosticDigest.digest().take(12).joinToString("") { "%02x".format(it) }

android {
    namespace = "com.hhst.youtubelite"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.hhst.litube"
        minSdk = 23
        targetSdk = 36
        versionCode = 302
        versionName = "3.0.2"
        buildConfigField("String", "DIAGNOSTIC_BUILD_ID", "\"$diagnosticBuildId\"")
        buildConfigField("String", "DIAGNOSTIC_DEPENDENCIES", "\"Media3=${libs.versions.media3.get()};OkHttp=${libs.versions.okhttp.get()};Gson=${libs.versions.gson.get()};NewPipe=${libs.newpipe.extractor.get().version}\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    lint {
        abortOnError = false
        warning += "MissingTranslation"
    }

    testOptions {
        animationsDisabled = true
        unitTests {
            isReturnDefaultValues = true
        }
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

val diagnosticMappingDir = layout.buildDirectory.dir("outputs/diagnostic-mappings/$diagnosticBuildId")
tasks.register<Copy>("archiveReleaseDiagnostics") {
    val destination = diagnosticMappingDir.get().asFile
    val identity = diagnosticBuildId
    dependsOn("minifyReleaseWithR8")
    from(layout.buildDirectory.dir("outputs/mapping/release"))
    into(diagnosticMappingDir)
    doLast { destination.resolve("build-id.txt").writeText(identity + "\n") }
}
tasks.matching { it.name == "assembleRelease" }.configureEach { finalizedBy("archiveReleaseDiagnostics") }

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.guava)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.javascriptengine)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)
    implementation(libs.okhttp)
    implementation(libs.gson)
    implementation(libs.mmkv)
    implementation(libs.newpipe.extractor) {
        exclude(group = "org.mozilla", module = "rhino-engine")
    }
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.datasource)
    implementation(libs.media3.session)
    implementation(libs.media3.ui)
    implementation(libs.media3.extractor)
    implementation(libs.media3.muxer)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.nanohttpd)

    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core.ktx)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    coreLibraryDesugaring(libs.desugar.jdk.libs.nio)
}
