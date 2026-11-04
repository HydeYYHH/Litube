import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins { alias(libs.plugins.kotlin.jvm) }

kotlin {
    compilerOptions {
        languageVersion.set(KotlinVersion.KOTLIN_1_9)
        jvmTarget.set(JvmTarget.JVM_17)
    }
    sourceSets.main { kotlin.srcDir("../external/mediaservicecore/common/src/main/kotlin") }
    sourceSets.test { kotlin.srcDir("../external/mediaservicecore/common/src/test/kotlin") }
}
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

sourceSets.test { resources.srcDir("../external/mediaservicecore/common/src/test/resources") }

dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
}
