plugins {
    id("com.android.library")
}

// Maven Central 2.21.2 sources.jar + 동일 AAR Manifest 원본, raw scan hook만 지역 패치.
group = "org.altbeacon"
version = "2.21.2"

android {
    namespace = "org.altbeacon.beacon"
    compileSdk = 36
    defaultConfig { minSdk = 14 }
    buildFeatures { buildConfig = false }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.2.0")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-process:2.6.2")
}
