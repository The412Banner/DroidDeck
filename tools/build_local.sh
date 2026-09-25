#!/usr/bin/env bash
set -euo pipefail

repo_root=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
if tree_status=$(git -C "${repo_root}" status --porcelain 2>/dev/null); then
    if [[ -n "${tree_status}" ]]; then
        export DROIDDECK_BUILD_TREE_STATE=dirty
    else
        export DROIDDECK_BUILD_TREE_STATE=clean
    fi
else
    export DROIDDECK_BUILD_TREE_STATE=unknown
fi
sdk_dir=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-"${HOME}/Library/Android/sdk"}}
java_dir=${JAVA_HOME:-"/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"}
image_name=${DROIDDECK_BUILD_IMAGE:-droiddeck-local-cross:24.04-v2}

if [[ ! -x "${sdk_dir}/platform-tools/adb" ]]; then
    echo "Android SDK not found at ${sdk_dir}; set ANDROID_HOME or ANDROID_SDK_ROOT." >&2
    exit 1
fi
if [[ ! -x "${java_dir}/bin/java" ]]; then
    echo "Java 17 not found at ${java_dir}; set JAVA_HOME." >&2
    exit 1
fi
if ! command -v docker >/dev/null 2>&1; then
    echo "Docker is required to cross-compile the glibc ARM64 preload libraries." >&2
    exit 1
fi

if ! docker image inspect "${image_name}" >/dev/null 2>&1; then
    docker build --platform linux/amd64 -t "${image_name}" \
        -f "${repo_root}/tools/local-cross.Dockerfile" "${repo_root}"
fi

docker run --rm --platform linux/amd64 \
    --user "$(id -u):$(id -g)" \
    -v "${repo_root}:/src" -w /src "${image_name}" bash -lc '
        set -euo pipefail
        d=app/src/main/assets/linuxfs
        mkdir -p "$d"
        aarch64-linux-gnu-g++ -shared -fPIC -O2 -Wall -Wno-attributes -Wno-nonnull-compare \
            -pthread -std=c++17 -static-libstdc++ -static-libgcc \
            -o "$d/libfakeinput.so" app/src/main/cpp/fakeinput_steam.cpp -ldl
        aarch64-linux-gnu-strip --strip-unneeded "$d/libfakeinput.so"
        aarch64-linux-gnu-gcc -shared -fPIC -O2 -Wall -pthread \
            -o "$d/libblsession.so" tools/linuxfs/preload/*.c -ldl
        aarch64-linux-gnu-strip --strip-unneeded "$d/libblsession.so"
        for script in tools/linuxfs/overlay/usr/local/bin/bannerlator-*; do
            install -Dm644 "$script" "$d/usr/local/bin/$(basename "$script")"
        done
        install -Dm644 tools/linuxfs/desktop/droiddeck-desktop "$d/usr/local/bin/droiddeck-desktop"
        install -Dm644 tools/linuxfs/desktop/autostart "$d/etc/xdg/labwc/autostart"
        install -Dm644 tools/linuxfs/desktop/rc.xml "$d/etc/xdg/labwc/rc.xml"
        install -Dm644 tools/linuxfs/desktop/panel.conf "$d/etc/xdg/lxqt/panel.conf"
        install -Dm644 tools/linuxfs/desktop/firefox-droiddeck.js \
            "$d/usr/lib/firefox/defaults/pref/droiddeck.js"

        need=$(aarch64-linux-gnu-readelf -d "$d/libfakeinput.so" | sed -n "s/.*NEEDED.*\\[\\(.*\\)\\]/\\1/p")
        for bad in libstdc++.so.6 libgcc_s.so.1; do
            if printf "%s\\n" "$need" | grep -qx "$bad"; then
                echo "libfakeinput.so links $bad; the C++ runtime must stay static" >&2
                exit 1
            fi
        done
        syms() { aarch64-linux-gnu-readelf -Ws "$1" | awk '\''$4 == "FUNC" && $5 == "GLOBAL" {sub(/@.*/, "", $8); print $8}'\''; }
        fake=$(syms "$d/libfakeinput.so")
        for sym in open openat ioctl read close poll ppoll select stat fstat access scandir; do
            printf "%s\\n" "$fake" | grep -qx "$sym" || {
                echo "libfakeinput.so does not export $sym" >&2
                exit 1
            }
        done
        session=$(syms "$d/libblsession.so")
        for sym in socket bind getsockname setsockopt statfs statvfs; do
            printf "%s\\n" "$session" | grep -qx "$sym" || {
                echo "libblsession.so does not export $sym" >&2
                exit 1
            }
        done
        test -f "$d/usr/local/bin/bannerlator-session"
        test -f "$d/usr/local/bin/bannerlator-proton-extra"
    '

export ANDROID_HOME="${sdk_dir}"
export ANDROID_SDK_ROOT="${sdk_dir}"
export JAVA_HOME="${java_dir}"
ndk_version=${DROIDDECK_NDK_VERSION:-}
if [[ -z "${ndk_version}" ]]; then
    ndk_path=$(find "${sdk_dir}/ndk" -mindepth 1 -maxdepth 1 -type d -print | sort -V | tail -1)
    ndk_version=${ndk_path##*/}
fi
if [[ -z "${ndk_version}" || ! -d "${sdk_dir}/ndk/${ndk_version}" ]]; then
    echo "No Android NDK found under ${sdk_dir}/ndk; set DROIDDECK_NDK_VERSION." >&2
    exit 1
fi

for tool in curl tar zstd; do
    if ! command -v "${tool}" >/dev/null 2>&1; then
        echo "${tool} is required to build the bundled audio modules." >&2
        exit 1
    fi
done

staging_dir=$(mktemp -d "${TMPDIR:-/tmp}/droiddeck-build.XXXXXX")
bundle_asset="${repo_root}/app/src/main/assets/pulseaudio.tzst"
bundle_backup="${staging_dir}/pulseaudio.original.tzst"
bundle_replaced=0
cleanup() {
    local exit_code=$?
    trap - EXIT
    if [[ "${bundle_replaced}" == 1 ]]; then
        cp -p "${bundle_backup}" "${bundle_asset}" || exit_code=1
    fi
    rm -rf -- "${staging_dir}" || exit_code=1
    exit "${exit_code}"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

pa_source=${DROIDDECK_PA13_SOURCE_DIR:-"${staging_dir}/pulseaudio-13.0"}
if [[ -z "${DROIDDECK_PA13_SOURCE_DIR:-}" ]]; then
    curl -fsSL -o "${staging_dir}/pulseaudio-13.0.tar.gz" \
        https://github.com/pulseaudio/pulseaudio/archive/refs/tags/v13.0.tar.gz
    mkdir -p "${pa_source}"
    tar -xzf "${staging_dir}/pulseaudio-13.0.tar.gz" \
        -C "${pa_source}" --strip-components=1
fi
if [[ ! -f "${pa_source}/src/pulse/version.h.in" ]]; then
    echo "PulseAudio 13.0 source not found at ${pa_source}; set DROIDDECK_PA13_SOURCE_DIR." >&2
    exit 1
fi

export NDK="${sdk_dir}/ndk/${ndk_version}"
sink_output="${staging_dir}/sink-out"
"${repo_root}/tools/aaudio-sink/build.sh" "${pa_source}" "${sink_output}"
"${repo_root}/tools/proot/build.sh" "${repo_root}/app/src/main/jniLibs/arm64-v8a"

cp -p "${bundle_asset}" "${bundle_backup}"
bundle_dir="${staging_dir}/pulseaudio-bundle"
mkdir -p "${bundle_dir}"
zstd -dc "${bundle_asset}" | tar -xf - -C "${bundle_dir}"
if [[ -e "${bundle_dir}/modules/arm64/module-aaudio-sink.so" \
        || -e "${bundle_dir}/modules/arm64/module-directaudio-sink.so" ]]; then
    echo "The committed audio bundle already contains a built ARM64 sink." >&2
    exit 1
fi
install -m755 "${sink_output}/module-aaudio-sink.so" \
    "${bundle_dir}/modules/arm64/module-aaudio-sink.so"
install -m755 "${sink_output}/module-directaudio-sink.so" \
    "${bundle_dir}/modules/arm64/module-directaudio-sink.so"
tar -cf - -C "${bundle_dir}" . | zstd -19 -c > "${staging_dir}/pulseaudio.tzst"
bundle_replaced=1
mv "${staging_dir}/pulseaudio.tzst" "${bundle_asset}"

cd "${repo_root}"
./gradlew assembleRelease -PndkVersion="${ndk_version}"
cp -p "${bundle_backup}" "${bundle_asset}"
bundle_replaced=0

apk="${repo_root}/app/build/outputs/apk/release/app-release.apk"
audio_check="${staging_dir}/audio-check"
mkdir -p "${audio_check}"
unzip -p "${apk}" assets/pulseaudio.tzst | zstd -dc | tar -xf - -C "${audio_check}"
for audio_file in \
    pactl \
    modules/arm64/module-aaudio-sink.so \
    modules/arm64/module-directaudio-sink.so; do
    if [[ ! -f "${audio_check}/${audio_file}" ]]; then
        echo "APK audio bundle is missing ${audio_file}." >&2
        exit 1
    fi
done

build_tools=$(find "${sdk_dir}/build-tools" -mindepth 1 -maxdepth 1 -type d -print | sort -V | tail -1)
if [[ ! -x "${build_tools}/zipalign" || ! -x "${build_tools}/apksigner" ]]; then
    echo "Android build-tools with zipalign/apksigner are required under ${sdk_dir}/build-tools." >&2
    exit 1
fi

"${build_tools}/zipalign" -p -f 4 "${apk}" "${staging_dir}/app-release.aligned.apk"
"${build_tools}/apksigner" sign \
    --ks keystore/testkey.p12 --ks-type PKCS12 --ks-pass pass:android \
    --ks-key-alias testkey --key-pass pass:android \
    --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
    --out "${apk}" "${staging_dir}/app-release.aligned.apk"

signature_output=$("${build_tools}/apksigner" verify --min-sdk-version 21 --verbose --print-certs "${apk}")
printf '%s\n' "${signature_output}"
for scheme in \
    'Verified using v1 scheme (JAR signing): true' \
    'Verified using v2 scheme (APK Signature Scheme v2): true' \
    'Verified using v3 scheme (APK Signature Scheme v3): true'; do
    grep -qF "${scheme}" <<<"${signature_output}" || {
        echo "APK signature check failed: ${scheme}" >&2
        exit 1
    }
done
grep -qF 'Signer #1 certificate DN: EMAILADDRESS=android@android.com, CN=Android, OU=Android, O=Android' \
    <<<"${signature_output}"

docker run --rm --platform linux/amd64 -v "${repo_root}:/src:ro" -w /src "${image_name}" \
    bash -lc '
        set -euo pipefail
        apk=app/build/outputs/apk/release/app-release.apk
        work=$(mktemp -d)
        unzip -q "$apk" "lib/arm64-v8a/*" -d "$work"
        cd "$work/lib/arm64-v8a"
        system="libc.so libm.so libdl.so liblog.so libandroid.so libz.so libvulkan.so
            libGLESv2.so libEGL.so libnativewindow.so libjnigraphics.so libaaudio.so
            libOpenSLES.so libmediandk.so libcamera2ndk.so libsync.so libneuralnetworks.so"
        fail=0
        for so in *.so; do
            for need in $(readelf -d "$so" | sed -n "s/.*NEEDED.*\\[\\(.*\\)\\]/\\1/p"); do
                [ -f "$need" ] && continue
                case " $(echo $system) " in *" $need "*) continue ;; esac
                echo "missing: $so -> $need"
                fail=1
            done
        done
        [ "$fail" -eq 0 ]
        echo "every NEEDED resolves"
    '

printf 'APK: %s\n' "${apk}"
printf 'SHA-256: '
shasum -a 256 "${apk}" | awk '{print $1}'
