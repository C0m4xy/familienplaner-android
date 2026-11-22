#!/bin/zsh
# Baut build/Familienplaner.apk (signiert mit eigenem Schlüssel aus signing/).
set -euo pipefail
cd "$(dirname "$0")"

export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home}"
export ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
[[ -f local.properties ]] || echo "sdk.dir=$ANDROID_HOME" > local.properties

# Signierschlüssel einmalig anlegen – GUT AUFHEBEN: Updates lassen sich nur mit
# demselben Schlüssel über die installierte App installieren.
if [[ ! -f signing/keystore.properties ]]; then
  mkdir -p signing
  PASS="$(openssl rand -hex 16)"
  "$JAVA_HOME/bin/keytool" -genkeypair -keystore signing/familienplaner.jks -alias familienplaner \
    -keyalg RSA -keysize 3072 -validity 18250 -storepass "$PASS" -keypass "$PASS" \
    -dname "CN=Familienplaner" >/dev/null 2>&1
  cat > signing/keystore.properties <<PROPS
storeFile=signing/familienplaner.jks
storePassword=$PASS
keyAlias=familienplaner
keyPassword=$PASS
PROPS
  chmod 600 signing/keystore.properties signing/familienplaner.jks
  echo "Neuer Signierschlüssel in signing/ angelegt."
fi

./gradlew assembleRelease -q
mkdir -p build
cp app/build/outputs/apk/release/app-release.apk build/Familienplaner.apk
# Aktuelle APK zusätzlich im Ordner Install ablegen (immer nur die neueste Version)
NAME=$(grep -E '^\s*versionName\s*=' app/build.gradle.kts | grep -oE '"[^"]+"' | tr -d '"')
mkdir -p Install
find Install -name "*.apk" -delete
cp build/Familienplaner.apk "Install/Familienplaner-$NAME.apk"
echo "Fertig: $PWD/build/Familienplaner.apk (Kopie: Install/Familienplaner-$NAME.apk)"
