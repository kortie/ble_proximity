#!/usr/bin/env bash
# 라이브러리 파일 배포본을 만든다 (D14: Maven 저장소 없이 파일로 배포, ADR-001).
# 결과: dist/proximity-<version>/ 에 AAR·JAR, DEPENDENCIES.md, SHA256SUMS
set -euo pipefail
cd "$(dirname "$0")/.."

version=$(grep -E '^proximity\.version=' gradle.properties | cut -d= -f2)
out="dist/proximity-$version"
toml=gradle/libs.versions.toml
# [versions] 섹션에서만 읽는다 ([plugins]에도 같은 이름이 있다).
ver() {
  local v
  v=$(awk -v k="$1" '/^\[/{s=($0=="[versions]")} s && $1==k && $2=="=" {gsub(/"/,"",$3); print $3}' "$toml")
  [ -n "$v" ] && [ "$(wc -l <<<"$v")" -eq 1 ] || { echo "버전을 찾지 못함: $1" >&2; exit 1; }
  echo "$v"
}

./gradlew --console=plain -q \
  :proximity-core:assembleRelease :proximity-testing:assembleRelease \
  :proximity-platform:assembleRelease :proximity-crypto:jar

rm -rf "$out"
mkdir -p "$out"
cp proximity-core/build/outputs/aar/proximity-core-release.aar "$out/proximity-core-$version.aar"
cp proximity-platform/build/outputs/aar/proximity-platform-release.aar "$out/proximity-platform-$version.aar"
cp proximity-testing/build/outputs/aar/proximity-testing-release.aar "$out/proximity-testing-$version.aar"
cp proximity-crypto/build/libs/proximity-crypto.jar "$out/proximity-crypto-$version.jar"

cat > "$out/DEPENDENCIES.md" <<EOF
# BLE Proximity $version — 받는 쪽 설정

라이브러리 파일로 배포하므로 아래 의존을 앱이 직접 추가한다 (minSdk $(ver minSdk) 이상).

\`\`\`kotlin
dependencies {
    implementation(files("libs/proximity-core-$version.aar"))
    implementation(files("libs/proximity-platform-$version.aar"))
    implementation(files("libs/proximity-crypto-$version.jar"))
    testImplementation(files("libs/proximity-testing-$version.aar"))   // Fake·계약 테스트 키트 (게임 개발용)

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$(ver coroutines)")
    implementation("com.squareup.wire:wire-runtime:$(ver wire)")
    implementation("org.bouncycastle:bcprov-jdk18on:$(ver bouncycastle)")
}
\`\`\`

빌드 정보: Kotlin $(ver kotlin), AGP $(ver agp), compileSdk $(ver compileSdk)
EOF

(cd "$out" && sha256sum ./*.aar ./*.jar > SHA256SUMS)

for f in proximity-core proximity-platform proximity-testing; do test -s "$out/$f-$version.aar"; done
test -s "$out/proximity-crypto-$version.jar"
test -s "$out/DEPENDENCIES.md"
echo "PACKAGE: OK ($out)"
ls -1 "$out"
