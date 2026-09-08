#!/usr/bin/env bash
set -e
cd "$(dirname "$0")"
export BT=/opt/android-sdk/build-tools/35.0.0
export PLAT=/opt/android-sdk/platforms/android-34/android.jar
mkdir -p gen/com/wetypefix classes dexout
rm -rf classes/* dexout/* gen/com/wetypefix/R.java build.unaligned.apk wetypefix-*.apk

# 1) resources -> R.java
$BT/aapt package -f -m -M AndroidManifest.xml -S res -I $PLAT -J gen

# 2) compile
javac --release 11 -Xlint:-options -classpath $PLAT:/tmp/xapi.jar \
  -d classes $(find src gen -name "*.java")

# 3) dex
$BT/d8 --release --min-api 24 --lib $PLAT \
  $(find classes -name "*.class" ! -name "R*.class") --output dexout

# 4) package apk
$BT/aapt package -f -M AndroidManifest.xml -S res -I $PLAT -F build.unaligned.apk
(cd dexout && zip -q ../build.unaligned.apk classes.dex)
zip -q build.unaligned.apk assets/xposed_init

# 5) align + sign
$BT/zipalign -f 4 build.unaligned.apk wetypefix-v1.0.0.apk
$BT/apksigner sign --ks debug.jks --ks-pass pass:android --key-pass pass:android \
  --min-sdk-version 24 --out wetypefix-v1.0.0.apk wetypefix-v1.0.0.apk
echo "OK: wetypefix-v1.0.0.apk"
