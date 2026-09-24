#!/usr/bin/env bash
set -euo pipefail

repo_root=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
sdk_dir=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-"${HOME}/Library/Android/sdk"}}
adb_bin=${ADB:-${sdk_dir}/platform-tools/adb}
apk=${1:-"${repo_root}/app/build/outputs/apk/home/release/app-home-release.apk"}

if [[ ! -x "${adb_bin}" ]]; then
    echo "adb not found at ${adb_bin}; set ADB or ANDROID_HOME." >&2
    exit 1
fi
if [[ ! -f "${apk}" ]]; then
    echo "APK not found at ${apk}; run tools/build_local.sh first." >&2
    exit 1
fi

if [[ -n "${ADB_SERIAL:-}" ]]; then
    serial=${ADB_SERIAL}
else
    devices=$("${adb_bin}" devices | awk '$2 == "device" {print $1}')
    device_count=$(printf '%s\n' "${devices}" | sed '/^$/d' | wc -l | tr -d ' ')
    if [[ "${device_count}" == 0 ]]; then
        echo "No authorized Android device found. Connect one or set ADB_SERIAL." >&2
        exit 2
    fi
    if [[ "${device_count}" != 1 ]]; then
        echo "Multiple Android devices found; set ADB_SERIAL explicitly." >&2
        printf '%s\n' "${devices}" >&2
        exit 2
    fi
    serial=${devices}
fi

"${adb_bin}" -s "${serial}" install -r --no-incremental "${apk}"
installed_path=$("${adb_bin}" -s "${serial}" shell pm path com.droiddeck.launcher | tr -d '\r')
printf 'Installed on %s: %s\n' "${serial}" "${installed_path}"
