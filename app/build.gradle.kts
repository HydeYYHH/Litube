import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.attribute.BasicFileAttributes

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// File-content providers make the fingerprint respond to source changes with configuration caching.
val diagnosticDigest = MessageDigest.getInstance("SHA-256")
listOf(fileTree("src/main"),
    files("build.gradle.kts", "proguard-rules.pro", "proguard-r8-verification.pro", "../gradle/libs.versions.toml", "../settings.gradle.kts").asFileTree)
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
        minSdk = 24
        targetSdk = 36
        versionCode = 400
        versionName = "4.0.0"
        buildConfigField("String", "DIAGNOSTIC_BUILD_ID", "\"$diagnosticBuildId\"")
        buildConfigField("String", "DIAGNOSTIC_DEPENDENCIES", "\"Media3=${libs.versions.media3.get()};OkHttp=${libs.versions.okhttp.get()};kotlinx-serialization=${libs.versions.kotlinxSerialization.get()};UPnPCast=${libs.versions.upnpcast.get()};MSC=${libs.versions.msc.get()}\"")
        testInstrumentationRunner = "com.hhst.youtubelite.ContentTestRunner"
    }

    // Keep resource qualifiers aligned with AppLocale.OPTIONS and locales_config.xml.
    androidResources {
        localeFilters += setOf(
            "en", "zh", "zh-rTW", "ar", "bg", "bn", "bs", "cs", "da", "de", "el", "es", "fa", "fi",
            "fil", "fr", "gu", "hi", "hr", "hu", "in", "iw", "it", "ja", "kn", "ko", "ml", "mr",
            "ms", "nb", "nl", "pa", "pl", "pt", "ro", "ru", "sk", "sl", "sr", "sv", "ta", "te",
            "th", "tr", "uk", "ur", "vi",
        )
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
        create("minifiedDebug") {
            initWith(getByName("release"))
            proguardFile("proguard-r8-verification.pro")
            signingConfig = signingConfigs.getByName("debug")
            applicationIdSuffix = ".minified"
            versionNameSuffix = "-minified"
            matchingFallbacks += listOf("release")
        }
    }

    testBuildType = providers.gradleProperty("androidTestBuildType").orElse("debug").get()
    if (testBuildType == "minifiedDebug") {
        defaultConfig.testInstrumentationRunner = "com.hhst.youtubelite.player.sponsor.R8VerificationRunner"
        // Release-like verification must not depend on debug-only test activities.
        sourceSets.getByName("androidTest").java.setSrcDirs(listOf("src/r8AndroidTest/java"))
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
        abortOnError = true
        warning += "MissingTranslation"
    }

    testOptions {
        animationsDisabled = true
        unitTests {
            isReturnDefaultValues = true
            all {
                it.maxHeapSize = "256m"
                it.maxParallelForks = 1
            }
        }
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

val diagnosticArchives = layout.buildDirectory.dir("outputs/diagnostic-mappings")
val diagnosticMappingDir = diagnosticArchives.map { it.dir(diagnosticBuildId) }
val pruneDiagnosticArchives = tasks.register("pruneDiagnosticArchives") {
    val rootDirectory = diagnosticArchives
    val currentId = diagnosticBuildId
    val retention = providers.gradleProperty("diagnosticArchiveLimit").map { it.toInt().coerceIn(1, 100) }.orElse(5)
    doLast {
        val root = rootDirectory.get().asFile.canonicalFile
        val archives = root.listFiles().orEmpty().filter { directory ->
            directory.isDirectory && directory.name.matches(Regex("[0-9a-f]{24}")) &&
                !Files.isSymbolicLink(directory.toPath()) && directory.canonicalFile.parentFile == root &&
                listOf(directory.resolve("build-id.txt"), directory.resolve("minifiedDebug/build-id.txt"))
                    .any { it.isFile && it.readText().trim() == directory.name }
        }
        val retained = archives.filter { it.name != currentId }.sortedByDescending { it.lastModified() }
            .take((retention.get() - 1).coerceAtLeast(0)).toSet()
        archives.filter { it.name != currentId && it !in retained }.forEach { directory ->
            check(directory.canonicalFile.parentFile == root)
            Files.walkFileTree(directory.toPath(), object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.delete(file)
                    return FileVisitResult.CONTINUE
                }
                override fun postVisitDirectory(dir: Path, failure: java.io.IOException?): FileVisitResult {
                    if (failure != null) throw failure
                    Files.delete(dir)
                    return FileVisitResult.CONTINUE
                }
            })
            logger.lifecycle("Removed diagnostic archive ${directory.name}")
        }
    }
}
listOf("release", "minifiedDebug").forEach { variant ->
    val suffix = variant.replaceFirstChar { it.uppercaseChar() }
    val archive = tasks.register<Copy>("archive${suffix}Diagnostics") {
        val directory = diagnosticMappingDir.get().asFile
        val destination = if (variant == "release") directory else directory.resolve(variant)
        val identity = diagnosticBuildId
        dependsOn("minify${suffix}WithR8")
        from(layout.buildDirectory.dir("outputs/mapping/$variant"))
        into(destination)
        doLast { destination.resolve("build-id.txt").writeText(identity + "\n") }
        finalizedBy(pruneDiagnosticArchives)
    }
    tasks.matching { it.name == "assemble$suffix" }.configureEach { finalizedBy(archive) }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.msc)
    implementation(libs.guava)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.javascriptengine)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)
    implementation(libs.okhttp)
    // Network reads stay in the bounded, allowlisted thumbnail transport.
    implementation(libs.coil.core)
    implementation(libs.kotlinx.serialization.json)
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
    implementation(libs.upnpcast)

    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.leakcanary.android)
    add("minifiedDebugImplementation", libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core.ktx)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.koin.test)
    androidTestImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    coreLibraryDesugaring(libs.desugar.jdk.libs.nio)
}
