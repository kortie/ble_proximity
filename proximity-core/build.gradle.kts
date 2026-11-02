// 순수 로직 (ADR-001). commonMain에는 플랫폼 API·java.* 금지 (I-5'). v1 타깃: android + jvm(테스트용).
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.wire)
}

kotlin {
    jvmToolchain(libs.versions.jvmTarget.get().toInt())
    androidTarget()
    jvm()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            api(libs.wire.runtime)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(project(":proximity-testing"))
        }
        jvmTest.dependencies {
            implementation(project(":proximity-crypto"))
        }
    }
}

wire {
    kotlin {}
}

android {
    namespace = "dev.proximity.core"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig { minSdk = libs.versions.minSdk.get().toInt() }
}
