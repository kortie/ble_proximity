// Android 플랫폼 어댑터 (BLE 광고·스캔·GATT, 가속도 센서). 여기에만 android.bluetooth 호출이 있다 (I-5).
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

kotlin {
    jvmToolchain(libs.versions.jvmTarget.get().toInt())
}

android {
    namespace = "dev.proximity.platform"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig { minSdk = libs.versions.minSdk.get().toInt() }
}

dependencies {
    api(project(":proximity-core"))
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(kotlin("test-junit"))
}
