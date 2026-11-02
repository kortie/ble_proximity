// 데모·디버그 앱 (원본 §3.2 ui/). 라이브러리 사용 예시이기도 하다.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

kotlin {
    jvmToolchain(libs.versions.jvmTarget.get().toInt())
}

android {
    namespace = "dev.proximity.sample"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig {
        applicationId = "dev.proximity.sample"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = providers.gradleProperty("proximity.version").get()
    }
    // 정책 값의 단일 출처인 config/를 앱 자산으로 넣는다 (원본 §6.2: 코드에 기본값을 두지 않는다).
    sourceSets["main"].assets.srcDir(rootProject.file("config"))
}

dependencies {
    implementation(project(":proximity-platform"))
    implementation(project(":proximity-crypto"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.zxing.core)
}
