# DroidDeck

Run Valve's native ARM64 Steam client on an Adreno Android device. Steam runs in a Linux runtime under proot, with gamescope and an in-app Vulkan compositor. Windows games use Valve's ARM64 Proton and FEX. A desktop with LXQt, Firefox, and emulators is also available.

## Requirements and install

Use Android 9 or newer on a supported Adreno device (730 or newer, or 8xx). Mali, Xclipse, PowerVR, and Adreno 710 are unsupported. No root is required. Allow about 3 GB for the runtime and 1.1 GB more for the desktop and emulators. Install an APK from [Releases](https://github.com/The412Banner/DroidDeck/releases), install the Linux runtime, then press **Play** and sign in. Steam downloads on first launch. Install **Desktop & apps** to use the desktop and emulators.

Choose `DroidDeck-X.Y.Z.apk` for Android Home integration or `DroidDeck-X.Y.Z-Non-Launcher.apk` for devices whose game mode filters out Home apps. Both APKs use the same package ID and signing key; installing one over the other switches editions while retaining DroidDeck data. The Non-Launcher edition does not register as Android Home, so the system Home button opens the device's normal Home screen. Android-app shortcuts remain available only when DroidDeck holds the Android Home role.

On Android 12+, if Steam exits without a log, turn off **Restrict child processes** in Developer options.

## Build

Run `tools/build_local.sh` with Docker, Java 17, the Android SDK/NDK, and `zstd` installed. It builds the ARM64 audio sinks from PulseAudio 13.0 and packages both editions:

- Home-enabled: `app/build/outputs/apk/home/release/app-home-release.apk`
- Non-Launcher: `app/build/outputs/apk/nonLauncher/release/app-nonLauncher-release.apk`

Set `DROIDDECK_PA13_SOURCE_DIR` to an existing PulseAudio 13.0 source directory to skip downloading it. To install the Home-enabled APK on an attached device, run `tools/deploy_local.sh`. Pass the Non-Launcher APK path to install that edition instead.

## Limits

Compatibility and performance vary by device; hardware validation is limited. Desktop compositing uses software rendering. Firefox sandboxing is reduced under proot. See the session logs in `Download/DroidDeck/` when diagnosing problems.

## Credits and licence

GPL-3.0. Runtime, shim, input, and controller work build on WinNative and Bannerlator (maxjivi05). See [LICENSE](LICENSE). Steam and Proton belong to Valve Corporation; this project is not affiliated with Valve.
