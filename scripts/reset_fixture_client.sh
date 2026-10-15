#!/bin/sh
set -eu

# AVD-04: only this project's isolated client; never wipes an AVD or payment app.
if [ "$#" -ne 1 ]; then
    echo 'Usage: scripts/reset_fixture_client.sh <emulator-serial>' >&2
    exit 2
fi
case "$1" in
    emulator-*) ;;
    *) echo 'Only an emulator target is allowed.' >&2; exit 2 ;;
esac
sdk_root="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$sdk_root" ]; then
    echo 'Set ANDROID_HOME or ANDROID_SDK_ROOT.' >&2
    exit 2
fi
adb_path="$sdk_root/platform-tools/adb"
package_id=dev.epay.observer.fixtureclient.debug
"$adb_path" -s "$1" shell am force-stop "$package_id"
"$adb_path" -s "$1" shell pm clear "$package_id"
"$adb_path" -s "$1" shell am start -W -n "$package_id/dev.epay.observer.MainActivity"
