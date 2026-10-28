import com.google.protobuf.gradle.id

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.protobuf") version "0.10.0"
}

android {
    namespace = "com.heytesla.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.heytesla.app"
        minSdk = 36
        targetSdk = 36
        versionCode = 66
        versionName = "0.66.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // sherpa-onnx AAR은 4개 ABI를 담는다. 대상 기기(arm64)만 포함해 APK 증가를 줄인다.
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets["main"].resources.srcDir("src/main/proto/licenses")
    // gitignore된 로컬 모델. tools/prepare_sherpa_ko.py로 준비한다.
    sourceSets["main"].assets.srcDir("../.local-models/sherpa-assets")

    androidResources {
        noCompress += "onnx"
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:4.36.2"
    }
    generateProtoTasks {
        all().configureEach {
            builtins {
                id("java") {
                    option("lite")
                }
            }
        }
    }
}

// 동일 2.21.2 배포 소스의 최소 raw scan 패치 모듈로만 치환한다.
configurations.configureEach {
    resolutionStrategy.dependencySubstitution {
        substitute(module("org.altbeacon:android-beacon-library:2.21.2"))
            .using(project(":altbeacon"))
    }
}

dependencies {
    implementation("org.altbeacon:android-beacon-library:2.21.2")
    implementation("com.google.protobuf:protobuf-javalite:4.36.2")
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation(files("../.local-models/sherpa-onnx-1.13.8.aar"))
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}

// 기기·BLE·RF 없이 실제 생산 consumer를 독립 차량 상대편과 실행한다.
tasks.register<JavaExec>("runVehiclePeerSimulator") {
    group = "verification"
    description = "독립 차량 상대편으로 생산 UWB 경로를 호스트에서 실행"
    val hostRuntime = provider { tasks.named<Test>("testDebugUnitTest").get().classpath }
    dependsOn(hostRuntime)
    classpath = files(hostRuntime)
    mainClass.set("com.heytesla.app.vehicle.uwb.VehiclePeerSimulator")
}
