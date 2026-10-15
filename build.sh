#!/bin/bash
# Builds the signed release APK into dist/altox-heater-<versionName>.apk.
# Needs JDK 17, Android SDK (platforms;android-36, build-tools;36.1.0) and Gradle 8.14: either on
# PATH / in ANDROID_HOME, or in ANDROID_BUILD (default ~/android-build: jdk/, sdk/, gradle/).
# Signing key: ANDROID_KEYSTORE (default release.jks here), its password ANDROID_KEYSTORE_PASSWORD
# in secrets.env. Both are not in git; to make your own key:
#   keytool -genkeypair -keystore release.jks -storetype PKCS12 -alias altox-heater \
#     -keyalg RSA -keysize 4096 -validity 36500 -dname "CN=Altox Heater"
# Usage: ./build.sh
set -euo pipefail
cd "$(dirname "$0")"
B=${ANDROID_BUILD:-$HOME/android-build}
if [[ -d "$B" ]]; then
  export JAVA_HOME=$B/jdk ANDROID_HOME=$B/sdk PATH=$B/jdk/bin:$B/gradle/bin:$PATH
fi
[[ -f secrets.env ]] && { set -a; source secrets.env; set +a; }
export ANDROID_KEYSTORE=${ANDROID_KEYSTORE:-$PWD/release.jks}
[[ -f "$ANDROID_KEYSTORE" && -n "${ANDROID_KEYSTORE_PASSWORD:-}" ]] \
  || { echo "no signing key: $ANDROID_KEYSTORE and ANDROID_KEYSTORE_PASSWORD in secrets.env" >&2; exit 1; }

VERSION=$(sed -n 's/^ *versionName = "\(.*\)"/\1/p' app/build.gradle.kts)
gradle -q --console=plain assembleRelease
mkdir -p dist
APK=dist/altox-heater-$VERSION.apk
cp app/build/outputs/apk/release/app-release.apk "$APK"
echo "$APK: $(du -h "$APK" | cut -f1)"
