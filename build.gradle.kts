plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.wire) apply false
}

// 회귀 테스트 필터: ./gradlew <test tasks> -PregressionThrough=N
// 클래스 이름 접두사 S0_ ~ SN_ 인 테스트만 실행한다 (원본 명세 §8 규약).
val regressionThrough = providers.gradleProperty("regressionThrough").orNull?.toInt()

subprojects {
    tasks.withType<Test>().configureEach {
        // config/default.yaml 등 저장소 파일을 테스트에서 읽기 위해 루트 경로를 넘긴다.
        systemProperty("proximity.repoRoot", rootDir.absolutePath)
        if (regressionThrough != null) {
            filter {
                (0..regressionThrough).forEach {
                    includeTestsMatching("*.S${it}_*")
                    includeTestsMatching("S${it}_*")
                }
                isFailOnNoMatchingTests = false
            }
        }
        testLogging {
            events("failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
}
