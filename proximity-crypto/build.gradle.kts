// CryptoBox 구현 (ADR-006). BouncyCastle 경량 API를 써서 Android·JVM에서 같은 코드로 동작한다.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(libs.versions.jvmTarget.get().toInt())
}

dependencies {
    api(project(":proximity-core"))
    implementation(libs.bouncycastle.prov)
    testImplementation(kotlin("test"))
}
