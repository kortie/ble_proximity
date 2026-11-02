// Fake 구현과 계약 테스트 키트. 게임 개발자도 쓸 수 있게 함께 배포한다 (ADR-001).
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
}

kotlin {
    jvmToolchain(libs.versions.jvmTarget.get().toInt())
    androidTarget()
    jvm()

    sourceSets {
        commonMain.dependencies {
            api(project(":proximity-core"))
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

android {
    namespace = "dev.proximity.testing"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig { minSdk = libs.versions.minSdk.get().toInt() }
}
