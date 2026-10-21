#!/usr/bin/env bash
set -euo pipefail
verification_directory="$(cd "$(dirname "$0")" && pwd)"
repository_directory="$(cd "$verification_directory/.." && pwd)"
platform="${1:-all}"
case "$platform" in
    android)
        "$repository_directory/gradlew" -p "$verification_directory" :android-app:assembleDebug
        ;;
    ios)
        "$repository_directory/gradlew" -p "$verification_directory" linkDebugFrameworkIosSimulatorArm64
        xcodegen generate --spec "$verification_directory/ios/project.yml"
        xcodebuild -project "$verification_directory/ios/EditorVerification.xcodeproj" -scheme EditorVerification -configuration Debug -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' -derivedDataPath "$verification_directory/build/ios" CODE_SIGNING_ALLOWED=NO
        ;;
    all)
        "$0" android
        "$0" ios
        ;;
    *)
        printf 'Usage: %s [android|ios|all]\n' "$0" >&2
        exit 2
        ;;
esac
