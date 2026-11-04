#!/bin/sh
# usage: sh integrity.sh
#
#   Checks this checkout before you build it: a clean tree, what the app can reach, which cryptography it asks the
#   platform for, and its manifest. The expected values are written out below, so any change to what the app can do
#   also changes this file and shows up in the diff you review. It does not replace reading the code: two files.
set -u
export LC_ALL=C

here=$(cd "$(dirname "$0")" && pwd)
failed=0

check() {
    if [ "$2" = "$3" ]; then
        echo "ok    $1"
    else
        echo "FAIL  $1"
        echo "      expected: $3"
        echo "      found:    $2"
        failed=1
    fi
}

lines_in_src() {
    (cd "$here/src" && grep -rcE "$1" dev | grep -v ':0$' | sort | tr '\n' ' ')
}

check "the checkout is clean" "$(git -C "$here" status --porcelain --untracked-files=normal)" ""
check "nothing reaches the network, other apps, logs, reflection or the clipboard" \
    "$(lines_in_src 'Socket|URLConnection|toURL|java\.net|ProcessBuilder|getRuntime|exec\(|loadLibrary|System\.load|Runtime\.load|ClassLoader|forName|java\.lang\.reflect|java\.lang\.invoke|getDeclared|setAccessible|getMethod|FileOutputStream|FileWriter|openFileOutput|getExternal|MediaStore|RandomAccess|PrintWriter|new File\(|getSystemService|getContentResolver|SharedPreferences|startActivity|sendBroadcast|android\.util\.Log|WebView|Clipboard|setTextIsSelectable|Intent')" \
    ""
check "the only host named is Google's for android.jar in build.sh" \
    "$(cd "$here" && grep -rhoE 'https?://[^"]+' src build.sh | sort -u | tr '\n' ' ')" \
    "https://dl.google.com/android/repository "
check "files are reached on seven lines of MainActivity, all under the app's private directory" \
    "$(lines_in_src 'Files\.|getFilesDir|fileList|deleteFile')" \
    "dev/tinyvault/MainActivity.java:7 "
check "the app's theme turns undo history off for every text field" \
    "$(grep -o 'android:theme="[^"]*"' "$here/AndroidManifest.xml") $(grep -c 'android:allowUndo">false' "$here/res/values/themes.xml")" \
    'android:theme="@style/Vault" 1'
check "the cipher is the platform's AES-GCM and the key lives in the Android keystore" \
    "$(grep -rhoE 'getInstance\([^)]+\)' "$here/src" | sort -u | tr '\n' ' ')" \
    'getInstance("AES/GCM/NoPadding") getInstance("AndroidKeyStore") getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore") '
check "the key is AES-256, for one authorised use at a time on an unlocked phone, and survives a new fingerprint" \
    "$(grep -rhoE 'set(KeySize|UserAuthenticationRequired|UserAuthenticationParameters|InvalidatedByBiometricEnrollment|UnlockedDeviceRequired)\([^)]*\)' "$here/src" | tr '\n' ' ')" \
    'setKeySize(256) setUserAuthenticationRequired(true) setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG | KeyProperties.AUTH_DEVICE_CREDENTIAL) setInvalidatedByBiometricEnrollment(false) setUnlockedDeviceRequired(true) '
check "the only randomness is one platform SecureRandom for the password generator, never seeded or picked by name" \
    "$(grep -rhoE 'new SecureRandom\(\)|SecureRandom\.getInstance|getInstanceStrong|setSeed|SHA1PRNG|Math\.random|new Random\(|ThreadLocalRandom|SplittableRandom' "$here/src" | sort | uniq -c | tr -s ' ' | tr '\n' ' ')" \
    ' 1 new SecureRandom() '
check "both windows, the screen and the password dialog, refuse screenshots" "$(lines_in_src 'FLAG_SECURE')" "dev/tinyvault/MainActivity.java:2 "
check "the only permission is the one the authentication prompt needs" \
    "$(grep -oE 'android\.permission\.[A-Z_]+' "$here/AndroidManifest.xml" | tr '\n' ' ')" 'android.permission.USE_BIOMETRIC '
check "the only component is the one screen" \
    "$(grep -oE '<(activity|service|receiver|provider)' "$here/AndroidManifest.xml" | tr '\n' ' ')" "<activity "
check "backups are off and no debug, cleartext or shared user flags are set" \
    "$(grep -oE 'allowBackup="[a-z]+"|debuggable|usesCleartextTraffic|sharedUserId|networkSecurityConfig' "$here/AndroidManifest.xml" | tr '\n' ' ')" \
    'allowBackup="false" '

commit=$(git -C "$here" rev-parse HEAD)
if [ "$failed" != 0 ]; then
    echo "FAILED for commit $commit: find out why before building"
    exit 1
fi
echo "all checks passed for commit $commit, which must be the commit you reviewed"
