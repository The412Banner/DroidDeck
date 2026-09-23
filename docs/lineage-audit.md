# Lineage audit — what in this repo was Winlator's, and how it left the lineage

> **Done, 2026-09-23 (branch `feat/armada-and-lineage`).** Everything §4 asked for is in:
> - the five inherited files are gone, replaced by fresh ones with different shapes:
>   `core/SessionPart.java` (attach/start/stop), `core/HostEnvironment.kt`, `core/HostProcess.kt`
>   (Consumer callbacks), `input/PadState.kt` (named fields, `press`/`isDown`), and plain
>   `java.util.function.Consumer` where a one-method callback was used;
> - the AAudio sink is the app's own: `tools/aaudio-sink/module-aaudio-sink.c`, a blocking-write
>   design against upstream PulseAudio 13 headers, compiled by the APK build into the audio bundle
>   (`build.yml`), the committed bundle carrying no sink at all. Same module arguments, so the
>   daemon's config line did not change. Device-proven: sink RUNNING, two client streams, latency
>   reported from the stream's timestamp;
> - `cpp/winlator/` is `cpp/framegen/`, the log tags are `SteamDeck_*`, the rumble socket is
>   `steamdeck-rumble` and is now actually served (`session/RumbleComponent.kt` drives the
>   vibrator); the two Bannerlator design notes are deleted; README and the credits dialog no
>   longer name Winlator. What remains is the name of the catalog repo (`winlator-contents`), which
>   is a URL, and the release notes already published.
>
> The rest of this document is the audit as it was made, kept for the record.

Audited 2026-09-23 against Winlator upstream (brunodev85's commits in the Bannerlator history) with
a text-similarity measure, not by trusting the comments. Everything below is verifiable with
`git grep -i winlator` (54 lines in 26 files) and a `difflib` ratio against Bruno's originals.

## 0. The right cut: inherited from Winlator, or written fresh inside its forks

WinNative and Bannerlator are Winlator forks, so "from Bannerlator" does not by itself mean
"Winlator's". What matters is whether a piece came down from Bruno's tree or was written new in
the forks. Measured against Bruno's originals:

**Inherited from Winlator (through the forks):**
- the five small app files in §1 (228 lines);
- the **PulseAudio AAudio sink** in the runtime's audio bundle: Bannerlator's adaptive
  `module-aaudio-sink` is a fork of Bruno's and Tom Yan's (brunodev85/pulseaudio-android,
  LGPL-2.1) with adaptive buffer sizing added. It lives in `pulseaudio.tzst` and the `libpulse*`
  prebuilts, not in app code. This is the one inherited piece that does real work.

**Written fresh in the forks, not Winlator's:**
- the Wayland compositor and its Vulkan present path (Bannerlator);
- DirectAudio, both the Wine driver and the relay (Bannerlator);
- Win-FG (Bannerlator, FSR3-derived) and LSFG Native (WinNative's port of lsfg-vk);
- the gamescope runtime, the session shim and the fake-evdev layer (WinNative); the proot launch
  here measures 1.5 % against Winlator's launcher — proot is the shared idea, none of the code is;
- the file manager, the front end, the settings, the session service (Bannerlator / this app).

## 1. App code that is measurably Winlator's

| File | Bruno's original | Shared text | Lines | What it is |
|---|---|---|---|---|
| `core/Callback.java` | `core/Callback.java` | 55 % | 6 | a one-method callback interface |
| `core/EnvironmentComponent.java` | `xenvironment/EnvironmentComponent.java` | 51 % | 17 | start/stop base for the audio components |
| `input/GamepadState.java` | `inputcontrols/GamepadState.java` | 33 % | 58 | six axis floats, a d-pad array, a button mask |
| `core/EnvVars.java` | `core/EnvVars.java` | 21 % | 31 | KEY=VALUE list helper |
| `core/ProcessHelper.java` | `core/ProcessHelper.java` | 21 % | 116 | spawn a process, drain its output |

Five files, 228 lines, none above 55 %. Everything else with a Winlator name on it measures under
12 % against Bruno's text — i.e. the file name and a few field names survived, the code did not:

| File | Compared with | Shared text |
|---|---|---|
| `audio/PulseAudioComponent.java` | Bruno's PulseAudioComponent | 11 % |
| `core/FileUtils.java` | Bruno's FileUtils | 9 % |
| `core/TarZst.java` | Bruno's TarCompressorUtils | 8 % |
| `input/PadBridge.java` | Bruno's ExternalController | 7 % |
| `files/FileOps.kt` | Bruno's FileUtils | 4 % |
| `core/DeviceReport.kt` | Bruno's GPUInformation | 2 % |
| `input/EvdevKeys.java` | Bruno's XKeycode | 2 % |
| `gpu/TurnipDriver.java` | AdrenotoolsManager (pipetto-crypto's, not Bruno's) | 7 % |

## 2. Things named after Winlator that are not Winlator's code

- **`app/src/main/cpp/winlator/`** — the directory name only. Inside: `lsfg/` (WinNative's port of
  lsfg-vk, credited to Eden / PancakeTAS), `winfg/` (Bannerlator's Win-FG, FSR3-derived, with its own
  LICENSE and FidelityFX notice), `VulkanRendererContext.h` (a Vulkan dispatch table struct from
  Bannerlator's renderer, first written by yaywoohoo in June 2026). Referenced by 6 lines in
  `cpp/CMakeLists.txt` and 8 in `waylandcomp/CMakeLists.txt`, plus 9 comment lines in the compositor.
- **`scanout/ScanoutContext.h`** — Bannerlator's (The412Banner, June 2026). Log tag `Winlator_Scanout`.
- **`VulkanRendererContext.h`** log tags `Winlator_Renderer`, `Winlator_Scanout`.
- **`fakeinput_steam.cpp`** — Max's, from WinNative. Connects to an abstract socket named
  `winlator_vibration` for rumble. **Nothing in this app serves that socket** (the server was
  Winlator's WinHandler); the connect fails silently and rumble is dead code here.
- **`winlator-contents`** — the name of the public catalog repo, in two URLs (`linuxfs.json`,
  `desktop.json`) and the README. A repo name, not code.
- **File manager comments** mention a `/storage/emulated/0/Winlator/…` path as an example.
- **`WAYLAND_RUNTIME.md`, `HDR_RECON.md`** — design notes carried from Bannerlator that describe
  Bannerlator's X11 renderer (`libwinlator.so`), which this app does not have.

## 3. Credits and prose

- `README.md` — "Lineage and licence" paragraph and the credits list name Winlator.
- `ui/MainScreen.kt` — the credits dialog text (the rest of that file is dead code).
- `docs/releases/0.1.0.md` … `0.1.5.md` — a Winlator credit line each (published release notes;
  history, leave as is).
- `PROGRESS_LOG.md` — five mentions, all of the `winlator-contents` repo.

## 4. What it would take

**To stop using Winlator code at all** — rewrite the five files in §1 (228 lines, an afternoon):
a callback interface, a start/stop base class, a pad state holder, an env list, a process spawner.
None of them does anything Winlator-specific; they are the shapes any such app has. Then the one
real piece: a **new AAudio sink module** for PulseAudio in place of the forked one — a few hundred
lines of C against the PulseAudio 13 module API (a sink that pulls from PA's render loop into an
AAudio stream, with the adaptive buffer logic Bannerlator added kept), rebuilt into the audio
bundle by the existing build-pulseaudio workflow. A day or two, and the part most worth testing.

**To stop being named after it** — rename `cpp/winlator/` (to `framegen/`), fix the two CMake
files, the log tags, the abstract-socket name (and remove the dead rumble client while at it), and
the comments; drop the Bannerlator design notes that describe the X11 path this app never had.

**To take it out of the credits** — the README paragraph, the credits dialog, and the credit line
in future release notes. Past notes stay as published.

**What does not change** — the licence. Everything here is GPL-3.0 and stays so: the runtime and the
fake-evdev layer are Max's (WinNative, GPL-3.0), the compositor, frame generation, file manager
and the session are Bannerlator's (GPL-3.0), lsfg-vk and FidelityFX carry their own notices. Leaving
Winlator's lineage does not leave the GPL, and does not touch the runtime image, which is shared
with Bannerlator by design.
