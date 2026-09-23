# SteamDeck — Progress Log

Running engineering log for the SteamDeck app (`com.steamdeck.launcher`). Newest state first, then
the timeline, then lessons and backlog. Companion to the README (what the app *does*) and to
`docs/releases/` (what each version said) — this is *how it got here and where it stands*.

---

## 2026-09-23 — branch `feat/armada-and-lineage` (unmerged): Deck mode for real, added games, own gamescope, own audio sink, lineage cut

- **Deck mode made safe** (device-proven on the FIT): `-steamdeck` only (never `-steamos3`, which restarts the client in a loop); the SteamOS helpers the client calls (`steamos-update`, `steamos-select-branch`, `jupiter-biosupdate`, `jupiter-dock-updater`, `steamos-priv-write`, `steamos-set-timezone`) staged from the apk as no-op stubs under both `/usr/bin` and `/usr/bin/steamos-polkit-helpers` (the client calls the latter path; a missing one there is the "Update Error" dialog); Deck mode defaults the `steamdeck_publicbeta` client channel (on `publicbeta` every Deck-mode start reinstalls the same client and loses the launch URL); a client-branch row in the Steam cog; `session/BatteryComponent.kt` synthesises `/sys/class/power_supply/BAT0` from BatteryManager so the QAM shows charge and time to full. Cloud saves were checked on device and are in sync (FlatOut 1 and 2 saves match `remotecache.vdf`); there was nothing newer to move.
- **Added games**: a Games folder scanned one level deep (`frontend/AddedGames.kt`, best `.exe` per folder, overridable per game in the cog), written into the client's `shortcuts.vdf` by `bannerlator-steam-shortcuts` before each start and mapped to the ARM64 Proton. Device-proven: a 32-bit AIO test from `/sdcard/Games` shows in the library and runs under Proton. Steam shows an "External Controller Translation" notice before a shortcut's first launch (OK once). Launching by `steam://rungameid/<gameid>` works from a session start; the ARM64 client's UI logs a double-precision complaint about the 64-bit id but proceeds.
- **Own gamescope build** (`tools/gamescope/`, workflow `build-gamescope.yml`): the runtime's exact 3.16.29 (Arch Linux ARM's package) rebuilt in an Arch container with Armada's four ARM64 client fixes and two patches ported by hand (realtime Vulkan queues on `GAMESCOPE_FORCE_VULKAN_REALTIME=1`, the gamepad cursor following the X pointer), published as release `gamescope-3.16.29-p1` and staged by the apk over `/usr/local/bin/gamescope`. The hosted runtime image is untouched. Device-proven: `gamescope version 3.16.29+ … at /usr/local/bin/gamescope`, client at 98–121 fps.
- **Lineage cut**: see `docs/lineage-audit.md` (top). Includes the new `tools/aaudio-sink` PulseAudio module, built by `build.yml` into `pulseaudio.tzst` (bundle stamp bumped).
- **Follow-ups the same morning (user reports):** a Games folder on the SD card (`/storage/<card>/Winlator/Games`) showed nothing — the scan only mapped paths inside internal storage, the ROMs folder or the app's own SD library; the chosen Games folder is now bound into the session at `/root/Games` wherever it sits (20 games listed, all in the client's Non-Steam tab on the FIT). Settings > System showed a "Dock Firmware Update / Apply" row: the client runs `jupiter-dock-updater --check` and reads exit 0 with no output as "update available" (state 4 in `systemdockmanager.txt`); the stub answers 7 for `--check` (state 2).
- **Several Games folders** (user ask, same morning): the folder became a list (`SessionPrefs.addedGamesDirs`, the old single key carried in), each bound at `/root/Games/<name>` (a 4-hex hash of the path added only when two chosen folders share a name, so nothing moves when another is added or removed); per-game exe choices keyed by folder path; the cog lists each folder with its count and a Forget, plus Add. Device-proven with the SD card folder and `/sdcard/Games` together: 22 shortcuts.
- **Artwork for added games** (user ask): `frontend/AddedGameArt.kt` — images in the game's folder (cover/poster/boxart/folder-name, header, hero, logo, icon, or an `art` subfolder) first, else a one-time Steam store search by folder name and the capsule/header/hero/logo from Steam's CDN into `files/added-art/<appid>/` (a miss retried after a week), plus an icon pulled from the .exe; the rail shows the portrait; `bannerlator-steam-shortcuts` copies the pieces into `userdata/<acct>/config/grid` under the client's names, never over a file it did not write (manifest `.steamdeck-art.json`), and sets each shortcut's icon. Toggle in the cog. On the FIT: 24 titles looked up, 13 portraits found. The lookup only ran when the cog opened at first; it now runs on resume.
- **Shelf navigation** (user report, reproduced with injected d-pad: down alternated FlatOut ↔ FlatOut 2): the shelf was a `LazyVerticalGrid`, and the pad's focus search only finds composed tiles, so any press towards an off-screen row bounced back among the visible ones. It is a whole grid now (`BoxWithConstraints` + rows in a `verticalScroll` column); the scroll follows the focused tile. User confirmed on the FIT.
- **Client sound through DirectAudio** (user report of choppy menu sound, then "we need direct audio in the client"): the client is Linux-native and speaks PulseAudio, so DirectAudio's Wine driver cannot apply; what can is the relay. `tools/aaudio-sink/module-directaudio-sink.c` is a PulseAudio sink that connects to the relay's socket (lazily: the relay starts after the daemon; a clocked null sink until then), sends the hello, maps the render ring and keeps it topped up to the relay's target with one futex wait per burst - every AAudio call, the adaptive buffer and route handling stay in the relay outside proot. With DirectAudio on the daemon loads it instead of `module-aaudio-sink` (`PulseAudioComponent.setRelaySocket`); the bundle carries both. Measured on the FIT: inside proot the daemon's own AAudio stream got 20 ms bursts with room for three (44.1 kHz and 48 kHz alike; the retuned AAudio sink starts at 60 ms buffer, native rate, half-buffer writes); through the relay the stream has 192-frame bursts and a 576-frame device buffer, sink latency 28 ms. Bundle stamp `2026-09-23-pa13-relay-sink-r3`.
- Builds on the branch: slices 1–3 (Deck mode, lineage, stubs) → `4e02c40e` (added games, fixed `cd7fbf1d`) → `e155a375…` APK (gamescope) → final APK with the audio sink (see the branch's last run). Test folder on the FIT: `/sdcard/Games/`.

## 🔖 0.1.5 RELEASED 2026-09-23 03:17 — known-good point

- **Tag `0.1.5` = `d2f4a84`** (bump commit, versionCode 6), notes `docs/releases/0.1.5.md` on main after it. Private release [0.1.5](https://github.com/The412Banner/SteamDeck/releases/tag/0.1.5) and public [`SteamDeck-0.1.5`](https://github.com/The412Banner/winlator-contents/releases/tag/SteamDeck-0.1.5) on winlator-contents, both Latest, asset `SteamDeck-0.1.5.apk` 21,093,959 bytes, sha256 `85b42954…`, run 35813465422. Staged as `/sdcard/Download/SteamDeck-0.1.5.apk`.
- What went in since 0.1.4 (58 commits): the motion front end, settings as pages with anchored menus, the session drawer, three themes (Paper default) and the new icon, run-as-a-game + libpci fix (117 fps Big Picture on a Fold), client-interface switches, FEX presets, 720p default, TZ, non-Adreno gate, 16 KB app libs, two on-screen sticks + bar-aware layout, folded categories at launch, Kurt's #3 (Back key) / #4 (icon) / #5 (local build helpers), pull-request CI + contributions ledger.
- Untested at release: sticks/bar layout, FEX presets, Deck mode, desktop → Steam hand-off, the `perf:` line. Rollback: `git reset --hard 8e58e8e` (0.1.4) + `SteamDeck-0.1.4.apk`.
- After the cut: the public release on winlator-contents got the private title, and that repo's README a SteamDeck section (latest release, `linuxfs.json`, `desktop.json`) — refresh both at every cut. Verified on the FIT's client binary: there is no `-steampal` flag; the Deck flags are `-steamdeck` / `-steamos3` (Deck mode on the Performance page), `-gamepadui`, `-steamos`.
- **Next, in order:** (1) device pass of 0.1.5 — sticks in a game and the bar layout on the Fold, the `perf:` line and 720p in a session log, a FEX preset, the desktop → Steam hand-off; (2) decide the stick click gesture (double tap or long press); (3) Kurt's draft PR #1 (Decky installer) builds when he marks it ready — it compiles against main, minus two stray `.kotlin` files; (4) rebuild the six PulseAudio prebuilts 16 KB-aligned; (5) optional FIFO/mailbox switch on the compositor's Vulkan present path; (6) the rename away from Valve marks is still open (icon is now the "A").

## 2026-09-22 (late) — motion front end + run-as-a-game, branch `feat/frontend-motion` (unmerged)

- Front end rebuilt around a motion system (`ui/FrontEndScreen.kt`, same state/actions API): the rail's selection is one pill that springs between rows; sub-lists unfold and stagger their children; a page change sinks out and cascades in over a blurred wash of the selection's art; tiles lift/ring/shine on focus and pop a play badge; the launch button sweeps a sheen and squashes on press; the session activity rises over the front end (`res/anim/session_*`). Durations follow the system animator scale.
- The session now runs as a game: manifest `appCategory=game` + `game_mode_config` (Performance mode welcome; the OS FPS cap and downscaling refused), sustained performance mode, the panel's fastest mode at its size, GameManager game state, and an ADPF hint session over the compositor thread fed with each presented frame's interval (`session/PerfMode.kt`, `session/PerfHints.kt`, `nativeCompositorTid`). One `perf:` line in the session log says what took on the device.
- App libraries link 16 KB-aligned. Still 4 KB-only (prebuilt, need a rebuild): libpulse, libpulseaudio, libpulsecommon-13.0, libpulsecore-13.0, libsndfile, libltdl.
- The session's display caps at 720p by default now, client and desktop alike (`SessionPrefs.defaultResolutionCap`); a cap the user chose still wins. The cog's list marks the default per mode.
- Builds: r1 `7ddfb57` (motion only), r2 `546c063` (+ run-as-a-game), r3 `d1ced87` (+ client 720p), r4 `fb2a3d4` (+ desktop 720p) — all CI-green, none device-tested. Staged as `SteamDeck-motion-r1..r4.apk`; r4 has everything.
- r5 `1ba803b` + TZ in the guest; r6 `39272cf` Setup folds, "Linux desktop environment", help link above the grid (r4 seen running on the FIT); r7 `af3f377` settings without a pop-up: the cog and Performance are pages in the pane with anchored menus under each value, frame generation / logs / offline open in place on the rail. **r7 merged fast-forward to `main` (`af3f377`) and main's auto-build staged as `SteamDeck-r52.apk`; no tag, no release.**
- r8 `90ff23a` (branch only, unmerged): the session drawer in the front end's dress with the same rows and menus (Now / Next session / leave), FEXCore presets carried over from Bannerlator (`core/FexPreset`, Steam settings page + drawer, FEX_* into the Steam session's environment; default = FEX defaults as before), file manager and picker locked to landscape.
- Collaborators: `maxjivi05` (push) and `xXJSONDeruloXx` (push, invited 2026-09-22) on the private repo.
- CI for pull requests (2026-09-23 early): `Build APK` runs on `pull_request_target` against main (a conflicting pull request never fires `pull_request`), checks the pull request out at its head with full history, merges main itself, and leaves one comment kept current: mergeable + built (apk on the run), or the conflicting files and hunks, or the compiler's errors from the kept Gradle log. Drafts wait. Fork pull requests may run without approval (repo setting). Proven with a throwaway pull request (#2, closed) both ways. `Contributions ledger` rewrites the README's contributors table on open/merge/close via `.github/scripts/contributions.sh`, committed to main by the Actions bot.

## 🔖 Checkpoint 2026-09-22 22:xx — known-good point for the front end

- **`main` @ `36cde7d`**, APK `SteamDeck-r51.apk` staged (sha `adb5acf5…`, run 35761437514) =
  frontend-r6 + docs. Same versionCode 5 / 0.1.4 inside; **0.1.5 not cut yet**.
- **Proven on the Pocket FIT this evening:** the front end renders and navigates (rail, art,
  square emulator icons, focus outline); RPCS3 lists Tomb Raider (ISO in a subfolder) and God of
  War II HD (its HDD); ▶ God of War II HD boots the game under gamescope at ~40 fps
  (`session-20260922-133044`: `run: rpcs3.AppImage --no-gui …NPUA80491/USRDIR/EBOOT.BIN`, 399
  frames on screen in 10 s).
- **Not yet proven:** desktop→Steam hand-off after the three fixes (r47–r50); ▶ FlatOut from the
  rail (Steam session with rungameid); Running tile; volume keys; automatic SD game storage
  (Install drive drop-down); Steam desktop-UI session; Tomb Raider ISO boot.
- **Open decision:** rename the app away from Valve's marks before it spreads (shortlist offered:
  Linuxlator / Pocketscope / Portascope); rename = label, icon text, `applicationId` (fresh
  install + runtime re-download), `Download/SteamDeck/` log folder, repo, release-tag pattern.
- **Rollback:** `git checkout 36cde7d` (or 0.1.4 tag `8e58e8e` for the last release), reinstall
  `SteamDeck-r51.apk` / `SteamDeck-0.1.4.apk` from Downloads.
- Loose ends: `ui/MainScreen.kt` keeps ConfirmDialog/CreditsDialog/EmulatorHelpDialog but its
  `MainScreen`/tiles are dead; worktree `~/steamdeck-frontend` still exists (branch merged).

## Current state (2026-09-22, evening)

- **`main` @ the front end merge** (`feat/frontend` fast-forwarded): the launcher main screen -
  Steam ▸ games, Desktop ▸ emulators ▸ games, Running tile, focus outline. ✅ First emulator game
  proven from the app: God of War II HD in RPCS3 under gamescope at ~40 fps on the Pocket FIT.
- Since 0.1.4 on main, unreleased: volume keys to Android; ▶ emulators under gamescope; ROMs chip
  + ? explainer; sysmem copy + ENOSYS hint; automatic game storage (a card = a Steam library);
  the client's games on the desktop with a `steam` shim; desktop→Steam hand-off (three causes
  fixed from FIT logs: Surface detach race, the replaced session's exit ending the new one,
  shared log folder); the front end. Next release = 0.1.5 once the hand-off is seen working.

## State at 0.1.4 (2026-09-22)

- **Latest release: 0.1.4** — private: https://github.com/The412Banner/SteamDeck/releases/tag/0.1.4
  · public: https://github.com/The412Banner/winlator-contents/releases/tag/SteamDeck-0.1.4
  `main` @ `8e58e8e`, CI run 35682844106, versionCode 5, APK sha256 `4b53ce6a…` (20,957,823 B).
- **Runtime:** `linuxfs-r9` on winlator-contents (790 MB), the only runtime release left; desktop
  packages from `steamdeck-desktop-r1`. The app rewrites its own scripts (`bannerlator-*`), the
  driver, `bannerlator-netmanager` and the DirectAudio pieces into the installed runtime at every
  launch, so a fix in those reaches an installed runtime without a re-download.
- **Shipped feature set:** the native ARM64 Steam client under gamescope on the in-app Wayland
  compositor; a LXQt-on-labwc desktop with Firefox and a shelf of emulators; Proton tools
  (GE / CachyOS) as downloads; graphics drivers importable per mode; DirectAudio + microphone;
  client/game core masks + four session switches; NetworkManager stand-in (Max's); overlay
  restored; ROMs folder + Storage bound into the session's home; Bannerlator's File Manager;
  a cog per launch mode (resolution, shape, HDR10, drivers, touch, OSC/audio, renderer); per-session
  log folders that survive a crash; leftover-process sweep; frame generation (Win-FG, LSFG).
- **Verification level:** every release is CI-green and staged to the maintainer's device. Proven
  on hardware (Pocket FIT / Fold): sign-in, store, install + launch (FlatOut 144 Hz), frame
  generation, controllers + OSC, sound, desktop + Firefox, folding mid-session, driver import, the
  log folder, the ANR fix, the network page. **Not yet proven:** everything 0.1.4 added (ROMs
  folder inside an emulator, File Manager, resolution cap, HDR10, crash sweep, orphan sweep, the
  drawer's Steam menu), DirectAudio *voice*, the core masks' effect, offline start, the soft
  keyboard, any emulator with a game, Adreno 710.
- **Open field reports (Thor Pro, 8 Gen 2):** trackpad-mode tap does not click (code sends the
  click; cause not found by reading); crash after 1–2 min in Ballionaire / Geometry Wars (no log
  survived — 0.1.4's sweep will leave `crash.log`); a Fold's 10–20 fps client menus (Chromium →
  ANGLE → Zink on an experimental A8xx Turnip); Fold crash loop (xalia ENOSYS storm — switches
  shipped, untested); FlatOut shrinking after Resume (gamescope forcing the swapchain extent).

## Release pipeline (how every version is cut)
1. Work on `main`, pushed → `Build APK` runs `assembleRelease` (AOSP test key, v1+v2+v3 re-signed
   by CI with zipalign + apksigner) → artifact `steamdeck-apk`. Dev builds keep the last release's
   versionCode/versionName; a release bumps both in `app/build.gradle` in its own commit.
2. Verify: `gh run view <id> --json conclusion` (never trust a run listing's first row — a
   `workflow_dispatch` run gets cancelled by concurrency in favour of the push-triggered one),
   download the artifact, sha256 it.
3. Stage: `cp` to `/sdcard/Download/SteamDeck-rN.apk` (dev) or `SteamDeck-X.Y.Z.apk` (release).
   Never `pm install` — the maintainer installs.
4. Private release `X.Y.Z` targeting the **full 40-char sha** (a short sha is refused), notes from
   the draft in the session scratchpad, `--latest`. Public release `SteamDeck-X.Y.Z` on
   winlator-contents with the README-style notes; one public release per version.
5. README ledger, this log, memory.

## Timeline
- **2026-09-19 — 0.1 groundwork.** Lifted out of Bannerlator's gamescope runtime on the user's
  ask: one screen, one button. r2 reached Big Picture sign-in on the Pocket FIT. Package renamed to
  `com.steamdeck.launcher` (r4), foreground service, test key, on-screen controls.
- **2026-09-20 — 0.1.** The Linux Steam client and a desktop; runtime r6→r9 in a day (Proton
  self-selection, proot shipped, refresh rate, video, first run reaches a game). Public explainer
  page for non-Linux users.
- **2026-09-21 — 0.1.1.** Catch-up with Bannerlator: driver selection (two lists, per mode),
  overlay restored + GameHub force-stop, DirectAudio + microphone, core masks, four session
  switches as toggles, per-session log folders with credentials scrubbed, Adreno 710 path via
  Banners-Turnip r3.
- **2026-09-22 — 0.1.2.** The teardown ANR (log collection on the main thread) fixed; two
  compositor log lines (frame-size change, window rename); README ledger.
- **2026-09-22 — 0.1.3.** Max's NetworkManager stand-in ported (the client's network page); shim
  audit vs WinNative = zero functional drift.
- **2026-09-22 (later) — after 0.1.4, on main.** Volume keys; ▶ emulators under gamescope (the
  desktop cannot: labwc on pixman offers no dma-buf); ROMs chip; sysmem warning + ENOSYS hint;
  automatic game storage; the client's games on the desktop (`steam` shim hands off to a
  gamescope session); the hand-off's three faults found in FIT logs and fixed; the front end
  merged (branch `feat/frontend`, r1–r6); God of War II HD booted in RPCS3 from the rail.
- **2026-09-22 — 0.1.4.** ROMs folder + Storage in the session's home; Bannerlator's File Manager
  ported whole; a cog per mode (railed settings window) with resolution cap, shape, HDR10 gated on
  the panel, drivers, touch, OSC/audio/renderer; two-column main menu; crash-safe log folders +
  Session logs toggle; OrphanReaper; drawer Steam menu. Public releases reorganised: one per
  version, old `Steamdeck` release and runtimes r1–r8 deleted.

## Architecture (where things live)
- `MainActivity` / `ui/MainScreen.kt` — the main screen; `ui/ModeSettingsDialog.kt` the cogs;
  `ui/*Dialog.kt` the rest.
- `SessionActivity` — the Surface, input (touch, touchpad, pads, keyboard), the drawer, the HDR
  decision, the compositor start (`wayland/CompositorHost`, `wayland/WaylandCompositor`).
- `session/SessionService` — proot + gamescope/labwc + PulseAudio + DirectAudio relay + network
  link; binds (`/root/Storage`, `/root/ROMs`); env for the guest; teardown; `OrphanReaper`;
  `SessionArtifacts` + `CrashHandler` + `SessionPaths` for the log folders; `SessionPrefs`.
- `files/` — Bannerlator's File Manager (`FileManagerScreen.kt` and its helpers), the picker
  activity and `InAppFilePicker` intent API.
- `gpu/` — Turnip (bionic, for the compositor) and Linux Turnip (glibc, for the runtime) managers.
- `tools/linuxfs/overlay/usr/local/bin/bannerlator-*` — the guest scripts, staged into the
  runtime by `SessionFiles` at every launch (CI packages only `bannerlator-*` names).
- `app/src/main/cpp/waylandcomp/` — the compositor (shared lineage with Bannerlator).

## Lessons learned (don't repeat these)
- Nothing in teardown may do bulk filesystem work on the main thread (0.1.1's ANR).
- A CI closure check on a multi-line variable must flatten it first (`libaaudio.so` refusal).
- A runtime script must be named `bannerlator-*` or CI never packages it.
- `gh release create --target` needs the full sha.
- A proot bind is invisible to a program's "Computer" list; put what users need under home.
- The compositor decides its driver and its HDR gate once per app process — anything that changes
  them applies after the app is fully closed, and the UI must say so.
- Bannerlator's File Manager ports mechanically (`port_fm.py` anchors) once the container hooks
  are cut; do not hand-edit 2,500 lines.
- Deleting a release asset on GitHub can drop a sibling asset — re-list and restore.

## Backlog / next
- Device-prove 0.1.4 on the FIT (list above).
- Thor Pro: trackpad tap; the 1–2 min in-game crash once a `crash.log` arrives.
- Xfce as a second desktop shell (Max's branch runs XFCE 4.20 on labwc) — a catalog package + a
  shell choice in the Desktop cog; comfort, not performance.
- Quick Access Menu for non-Deck pads — no confirmed chord; needs research, not a guess.
- FlatOut shrink: try `vk_wsi_force_swapchain_to_current_extent=false` via `steamdeck-env`.
- Max's stricter `winnative-directaudio` guards; `winnative-epic-launch` (a feature).
- Rename before anything truly public: "SteamDeck" is Valve's mark.
