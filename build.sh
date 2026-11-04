#!/bin/sh
# usage: sh build.sh
#
#   Builds out/tinyvault.apk from this checkout, checks what it declares and contains, and only then opens it in
#   Android's installer.
#   On the phone, in Termux: pkg install git openjdk-21 aapt aapt2 d8 apksigner zip unzip curl
#   android.jar comes from Google once, checked against the SHA-1 Google lists and the one pinned here.
#   The signing key keystore/tinyvault.jks is made on first use. Keep it: Android only lets an APK signed with the
#   same key replace the installed tinyvault.
set -eu

PLATFORM=platform-35_r02.zip
PLATFORM_SHA1=0bb560a90a7a2cbd0dd8348224d518b638fe7949
REPOSITORY=https://dl.google.com/android/repository

here=$(cd "$(dirname "$0")" && pwd)
out=$here/out
jar=$here/sdk/android-35/android.jar

if [ ! -f "$jar" ]; then
    mkdir -p "$here/sdk/android-35"
    curl -fsSL -o "$here/sdk/$PLATFORM" "$REPOSITORY/$PLATFORM"
    listed=$(curl -fsSL "$REPOSITORY/repository2-3.xml" | tr -d ' \t\r\n' \
        | sed -n "s|.*<checksumtype=\"sha1\">\([0-9a-f]*\)</checksum><url>$PLATFORM</url>.*|\1|p")
    actual=$(sha1sum "$here/sdk/$PLATFORM" | cut -d' ' -f1)
    if [ "$listed" != "$PLATFORM_SHA1" ] || [ "$actual" != "$PLATFORM_SHA1" ]; then
        echo "build.sh: Google lists SHA-1 '$listed' and the download has '$actual', but '$PLATFORM_SHA1' is pinned" >&2
        exit 1
    fi
    unzip -p "$here/sdk/$PLATFORM" android-35/android.jar > "$jar.part"
    mv "$jar.part" "$jar"
fi

rm -rf "$out"
mkdir -p "$out/classes" "$out/dex"
find "$here/src" -name '*.java' | sed 's/.*/"&"/' > "$out/sources.txt"
javac --release 17 -Xlint:all -Werror -classpath "$jar" -d "$out/classes" @"$out/sources.txt"
(cd "$out/classes" && zip -q -r -X ../classes.jar .)
d8 --release --min-api 35 --lib "$jar" --output "$out/dex" "$out/classes.jar"

version=$(git -C "$here" rev-parse --short=12 HEAD)
[ -z "$(git -C "$here" status --porcelain --untracked-files=normal)" ] || version=$version-dirty
aapt2 compile --dir "$here/res" -o "$out/res.zip"
aapt2 link -o "$out/unsigned.apk" -I "$jar" --manifest "$here/AndroidManifest.xml" \
    --version-code "$(date +%s)" --version-name "$version" "$out/res.zip"
(cd "$out/dex" && zip -q -X ../unsigned.apk classes.dex)
# Android 11+ installs only if resources.arsc is stored uncompressed on a 4-byte boundary. aapt2 pads the entry's local
# header to make it so and zip leaves existing entries alone; Termux has no zipalign, so this reads that header to confirm.
offset=$(unzip -Z -v "$out/unsigned.apk" resources.arsc | awk '/offset of local header/ {print $NF}')
set -- $(dd if="$out/unsigned.apk" bs=1 skip=$((offset + 8)) count=2 2>/dev/null | od -An -tu2) \
    $(dd if="$out/unsigned.apk" bs=1 skip=$((offset + 26)) count=4 2>/dev/null | od -An -tu2)
if [ "$1" != 0 ] || [ $(( (offset + 30 + $2 + $3) % 4 )) != 0 ]; then
    echo "build.sh: resources.arsc is not stored on a 4-byte boundary (method $1, name $2, padding $3, header at $offset)" >&2
    exit 1
fi

keystore=$here/keystore/tinyvault.jks
if [ ! -f "$keystore" ]; then
    mkdir -p "$here/keystore"
    keytool -genkeypair -keystore "$keystore" -alias tinyvault -keyalg RSA -keysize 2048 -validity 10000 -dname CN=tinyvault
fi
apksigner sign --ks "$keystore" --out "$out/tinyvault.apk" "$out/unsigned.apk"

declared=$(aapt2 dump badging "$out/tinyvault.apk" \
    | grep -oE "^(package|uses-permission|launchable-activity): name='[^']+'" | tr '\n' ' ')
contents=$(unzip -Z1 "$out/tinyvault.apk" | grep -v '^META-INF/' | LC_ALL=C sort | tr '\n' ' ')
if [ "$declared" != "package: name='dev.tinyvault' uses-permission: name='android.permission.USE_BIOMETRIC' launchable-activity: name='dev.tinyvault.MainActivity' " ] \
    || [ "$contents" != "AndroidManifest.xml classes.dex res/xml/rules.xml resources.arsc " ]; then
    echo "build.sh: the APK is not what this script expects, so it was not opened: $declared| $contents" >&2
    exit 1
fi
echo "built $out/tinyvault.apk from commit $version"
termux-open "$out/tinyvault.apk"
