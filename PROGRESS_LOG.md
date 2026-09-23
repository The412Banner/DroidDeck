# SteamDeck — Progress Log

Running engineering log for the SteamDeck app (`com.steamdeck.launcher`). Newest state first, then
the timeline, then lessons and backlog. Companion to the README (what the app *does*) and to
`docs/releases/` (what each version said) — this is *how it got here and where it stands*.

---

## 2026-09-22 (late) — motion front end + run-as-a-game, branch `feat/frontend-motion` (unmerged)

- Front end rebuilt around a motion system (`ui/FrontEndScreen.kt`, same state/actions API): the rail's selection is one pill that springs between rows; sub-lists unfold and stagger their children; a page change sinks out and cascades in over a blurred wash of the selection's art; tiles lift/ring/shine on focus and pop a play badge; the launch button sweeps a sheen and squashes on press; the session activity rises over the front end (`res/anim/session_*`). Durations follow the system animator scale.
- The session now runs as a game: manifest `appCategory=game` + `game_mode_config` (Performance mode welcome; the OS FPS cap and downscaling refused), sustained performance mode, the panel's fastest mode at its size, GameManager game state, and an ADPF hint session over the compositor thread fed with each presented frame's interval (`session/PerfMode.kt`, `session/PerfHints.kt`, `nativeCompositorTid`). One `perf:` line in the session log says what took on the device.
- App libraries link 16 KB-aligned. Still 4 KB-only (prebuilt, need a rebuild): libpulse, libpulseaudio, libpulsecommon-13.0, libpulsecore-13.0, libsndfile, libltdl.
- The session's display caps at 720p by default now, client and desktop alike (`SessionPrefs.defaultResolutionCap`); a cap the user chose still wins. The cog's list marks the default per mode.
- Builds: r1 `7ddfb57` (motion only), r2 `546c063` (+ run-as-a-game), r3 `d1ced87` (+ client 720p), r4 `fb2a3d4` (+ desktop 720p) — all CI-green, none device-tested. Staged as `SteamDeck-motion-r1..r4.apk`; r4 has everything.
- r5 `1ba803b` + TZ in the guest; r6 `39272cf` Setup folds, "Linux desktop environment", help link above the grid (r4 seen running on the FIT); r7 `af3f377` settings without a pop-up: the cog and Performance are pages in the pane with anchored menus under each value, frame generation / logs / offline open in place on the rail. **r7 merged fast-forward to `main` (`af3f377`) and main's auto-build staged as `SteamDeck-r52.apk`; no tag, no release.**
- r8 `90ff23a` (branch only, unmerged): the session drawer in the front end's dress with the same rows and menus (Now / Next session / leave), FEXCore presets carried over from Bannerlator (`core/FexPreset`, Steam settings page + drawer, FEX_* into the Steam session's environment; default = FEX defaults as before), file manager and picker locked to landscape.
- Collaborators: `maxjivi05` (push) and `xXJSONDeruloXx` (push, invited 2026-09-22) on the private repo.

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
