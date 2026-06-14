# Changelog

All notable changes to Calibrate SoC are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

Nothing yet.

---

## [0.1.14-alpha] — 2026-06-14

Six-dimension audit (bugs, security, perf, robustness, quality, features) with adversarial verification: ~30 fixes + 4 features. 599 unit tests green (was 460).

### Security
- **Root-script injection hardened (defence-in-depth).** `TunableMetadata.validateCustomSysfsPath` now rejects the full shell-metacharacter set (`; \` $ | & ( ) < > { } [ ] * ? ! ~` + control/NUL), and `AynScriptGenerator` single-quotes every sysfs **path** it emits (previously only the value was quoted). `RemoteContentValidator.validatePreset` now validates `extraSysfs` keys (via the path validator) and values — closing the OTA vector. `PServerWriter` now shell-quotes the Settings key + value and rejects non-`[a-zA-Z0-9_.]` keys. `AynScriptDeployer` quotes the deployed `$target` path. `UpdateChecker` validates the release asset URL at the trust boundary (fail-fast).
- Shared the validation regexes (`ValidationRegexes`) across `BackupManager` + `RemoteContentValidator` so the security rules can't silently diverge.

### Fixed
- `PServerWriter.read()` returned null unconditionally → AYN Settings.System tunables never boot-reverted. Now reads the real value via `Settings.System.getString`.
- HUD `cycleNextProfile` modulo-by-zero crash on rapid taps; sticky `flashActionMessage` during rapid taps (token-based clear); swallowed `updateViewLayout`/session-stop exceptions now logged; DragHandler Choreographer callback removed in `onDestroy` (leak on OOM-kill).
- Divide-by-zero guards in `GpuSceneBenchmark`/`GpuTriangleStorm`; `bench_mem.c` size_t overflow + bound.
- Orphaned `.tmp` cleanup in `TunableSnapshotStore`/`TuneHistoryStore`; `BootRevertReceiver` now logs the revert summary.
- **Honesty:** benchmark `ABORTED_BATTERY` actually checked battery *temperature* → renamed `ABORTED_BATTERY_TEMP` + honest label. (Real charge-% abort is a follow-up.)

### Performance
- `MonitorService`/`SysfsProber` cache the thermal-zone list and re-read only temps each tick (was a full `/sys/class/thermal` enumeration at 1–4 Hz). `GameFpsSampler` caches its layer-resolution dumpsys (TTL + display-change) and hoists its whitespace regex. Independent monitor samplers now read in parallel. `@Immutable` on `HudUiState`.

### Refactor / Quality
- Extracted `OverlayService.stepBigCoreMhz` (136-line god-method) into `executeStepWrite` + `buildChmodSandwich` + `cpuFreqMaxPath` (behavior-preserving). Shared utils: `SysfsIo.readSysfsString`, `Units` (khz/hz/milliC conversions), `FileNameUtils.toSafeFilename`.

### Added — Features
- **Thermal Event Timeline** — annotates session recordings with throttle events vs FPS dips (honest heuristic, labelled).
- **Per-App Performance Dashboard** — saved sessions grouped by app: avg FPS / peak temp / avg watts (missing metrics shown as "—", single-session apps flagged).
- **Comparative A/B Benchmark** — run the same benchmark under two profiles; gated honestly on whether each profile actually applied (no-root → "may not have applied" badge, not a fake result).
- **Baseline Degradation Report** — compares current clock ceilings vs factory baseline; honestly returns "insufficient data" + teaches what would enable wear detection, never guesses.

### Deferred to a tested follow-up
- TLS certificate pinning (OTA/update fetches), symlink-resolve in path validation, real battery-state-of-charge benchmark abort — all need on-device verification across the 3 handhelds.

---

## [0.1.13-alpha] — 2026-06-14

Hotfix for the OTA community presets shipped in 0.1.12.

### Fixed
- **OTA community presets were rejected by our own validator.** The seeded presets
  (RP6 / Thor / Odin 3) have display names containing legitimate punctuation —
  `—` (em-dash), `/`, `(`, `)`, `&` — which the import-time injection guard
  (`RemoteContentValidator` / `BackupManager.validateProfile`) treated as shell
  metacharacters and rejected, so the on-device log showed
  `Rejected 6 remote preset(s): ['name contains disallowed characters', …]`.
  Root cause: the guard applied the strict shell-metachar regex to **display-only**
  fields (preset/adapter name, description, notes) that never reach a shell
  un-escaped — the script generator already `shellSingleQuote`/`commentSafe`-escapes
  everything it emits. Fix: display fields now reject only control characters +
  line breaks (`DISPLAY_UNSAFE`), while **executable** fields (governor names,
  sysfs targets/values, daemon names) keep the strict shell-metachar guard. This
  is the correct security model — defence at the emit layer for display text,
  strict validation for values that become a script. Tests updated accordingly
  (display-field metachars now pass; control chars and governor injection still
  rejected).

---

## [0.1.12-alpha] — 2026-06-14

Audit-driven quality pass on presets, benchmark, and Advanced Tuning. 460 tests green.

### Fixed
- **Preset governor bug** — Battery Saver used the `powersave` governor, which pins
  the CPU to its lowest OPP permanently → emulator stutter + audio crackle. Removed
  `powersave` from all gaming presets (enforced at the GovernorMap level + tested);
  battery presets now use `conservative` (defaults low, ramps on load).

### Changed — presets
- Replaced the 4 abstract built-ins with a **use-case taxonomy**: Cool & Quiet,
  Light Emulation (N64/PSP/Dreamcast), PS2 / GameCube — Sustained, Switch / Heavy,
  **Anti-Throttle — Sustained Max** (new; caps below the throttle point for steadier
  sustained perf — the TheOldTaylor underclock philosophy), Stock (undo). Each
  description states workload, hardware effect, thermal/battery tradeoff.
- **Smarter generator**: per-cluster-tier caps (prime/gold/little), sustained mode
  leaves ≥1 OPP step headroom, OPP-knee heuristic, CPU min always at OPP floor.
- Seeded `content/presets.json` (manifest v2) with verified RP6/Thor/Odin3 presets
  (TheOldTaylor numbers attributed) — delivered via OTA, no app release.

### Changed — benchmark
- **Bottleneck diagnosis** (`BenchBottleneck`): every result gets a plain-language
  verdict (GPU-bound / CPU-limited / thermal-throttle / memory-bound) + which knob
  helps, from existing measured data. The "makes it researchable" feature.
- 3D scene integrated into Standard runs; per-loop FPS bar chart; thermal-over-time
  chart; auto "vs your last run" delta; storage read in Standard; scene visual
  identity (skybox + ground + per-instance color + 2nd mesh + fill light); fixed the
  backwards draw-call-ceiling explainer. Rides in kernelsJson (no schema bump).

### Changed — Advanced Tuning (usable on stock no-root)
- **Script-builder reframe**: on no-root devices, every knob stages into a pending set
  and Generate Script packages them into the existing "Run as Root" `.sh` pipeline
  (via the already-built `extraSysfs` emit) — turning a 0%-usable screen into a fully
  configurable one. Was: 0 of ~50 controls worked on stock RP6.
- **Tier detection fix**: after the one-time unlock (chmod), covered nodes go LIVE via
  a new `UnlockedFileWriter` (plain file write, no su) — `whyWriteDenied` now returns
  null for unlock-covered nodes when `sysfsDirectlyWritable`.
- **Extended unlock script** to chmod more nodes (DDR devfreq, I/O queue, Adreno
  extras, CPU governor-tunable dirs, input boost). procfs/cgroup/thermal stay
  script-only or root-only, honestly labelled — never a dead greyed slider.

---

## [0.1.11-alpha] — 2026-06-14

The big "all-in-one" round: deep kernel clocking + a benchmark hub. Built across
two foundation tracks (tunable core + GPU engine) and three breadth tracks
(advanced-tuning UI, monitoring/script-export, benchmark hub). 411 unit tests green.

### Added — deep clocking
- **Tunable core extended** to the full kernel-manager taxonomy: CPU core
  hotplug, per-governor tunables, GPU power levels / governor / throttling /
  force-clk / idle-timer, schedtune.boost & uclamp, input boost, DDR/bus devfreq,
  I/O scheduler + readahead, VM sysctls, and a generic custom-sysfs rule. A new
  `TunableMetadata` layer gives every knob a risk tier (SAFE→DANGEROUS), a value
  kind, and validation. All writes go through the existing snapshot + boot-revert
  path, privilege-gated and value-validated.
- **Advanced Tuning screen** (`ui/tune/advanced/`) surfaces these, capability-gated
  (only what the device exposes), privilege-gated (honest greyed reason when no
  root), and risk-gated (blunt confirm dialogs for thermal/throttling knobs).
- **Honest voltage/UV card** — `VoltageControl` states plainly that undervolt is
  not possible on stock locked Snapdragon (needs custom kernel / unlocked BL).
- **Monitoring (no root):** CPU time-in-state histogram, thermal trip-point detail,
  live DDR/bus devfreq — read-only, the honest differentiator.
- **Preset/Profile `extraSysfs` map** carries the new knobs, applied via
  ProfileApplier and emitted (validated + shell-escaped) by AynScriptGenerator for
  the no-root script path; flows through per-app automation and preset-sharing/OTA.

### Added — benchmark hub
- **New heavy 3D GPU benchmark** (`GpuSceneBenchmark`, `BenchFlavor.SCENE_3D`):
  original procedural scene (~871k tris, depth prepass + Blinn-Phong/GGX lit pass +
  post), GLES 3.0 (2.0 fallback), offscreen at fixed 1080p/1440p/2160p tiers,
  glFinish timing, honest avg/median/1%-low FPS + p99 frame ms + consistency% +
  sustained-vs-peak stability%. Rides in `kernelsJson` (no schema change). Baseline
  GPU test untouched.
- **Benchmark hub** (`data/benchmarkhub/`): detects installed benchmark apps
  (3DMark/AnTuTu/Geekbench/GFXBench/CPU-Throttling/PCMark via manifest `<queries>`)
  and launches them by intent; **manual score log** (`data/scorelog/`, Room v7→v8)
  with per-benchmark trends. Legally clean: launch-only + user-entered scores,
  clearly labelled self-reported; no scraping, no embedding, no equivalence claims.

---

## [0.1.10-alpha] — 2026-06-14

"Keep current + share tunes" round.

### Added
- **Preset sharing** — export a saved profile as a short paste-safe code
  (`CSOC1:…`, copy or system-share) and import one by pasting it, with a preview
  before saving. Imported presets are validated (no shell-metachar injection),
  marked unverified, and never auto-apply — they go through the normal
  safety-gated Apply flow.
- **Auto-check for updates** — opt-in (default on) daily GitHub check on launch
  that shows a dismissible "update available" banner with "Later" (snooze 7 days)
  and per-version dismiss. Reuses the existing signature-verifying installer;
  never auto-downloads or auto-installs. Toggle in Settings.

### Changed
- **OTA content channel** — device adapters and community presets can now be
  updated without an app release: the app fetches small JSON files from
  `content/` on the repo's `main` branch over HTTPS (GitHub-host allowlist,
  strict schema validation), caches them to internal storage (offline-safe,
  throttled ~12 h), and merges them over the bundled defaults (remote wins by
  key; bundled is always the fallback). Remote community presets are forced to
  the "unverified" tier and never auto-apply. Code still ships only as signed
  APKs — only DATA goes OTA. Seed files + format docs live in `content/`.

---

## [0.1.9-alpha] — 2026-06-14

A polish & quality pass (no new headline features) driven by the codebase audit.

### Added
- **Cancel running tests** — Benchmark and Stability runs now expose a Stop
  button while in progress. Cancellation propagates through the coroutine so the
  runner's cleanup (CPU thread-pool close, EGL release) runs and no partial
  result is persisted.

### Changed
- **HUD position persists** — the overlay reopens at its last-dragged position
  across HUD close/reopen and reboot, instead of resetting to the default.
- **Honest active-tune chip** — after a reboot, the Dashboard shows "Last
  applied: <profile>" instead of "Active: <profile>" when the tune predates the
  current boot (sysfs reverts on reboot), so the chip never asserts a tune is
  active when it may not be.

### Performance (internal)
- Telemetry samplers no longer compile a regex per sample (`/proc/stat`,
  `/proc/meminfo`, GPU load, per-core freq) — hoisted to constants.
- `PerCoreFreqSampler` caches the CPU-directory enumeration (topology is fixed
  at boot) instead of re-listing + re-sorting every tick.
- Removed redundant `exists()`-before-`read()` syscalls in the freq/GPU samplers.
- `BatterySampler` registers the battery sticky-intent once per sample instead
  of twice (one binder IPC instead of two).
- Removed a main-thread `runBlocking` in `OverlayService.onDestroy`.

### Code quality (internal)
- De-duplicated the telemetry→ThrottleSample mapping (was copied in two runners)
  into a single shared `TelemetrySampleMapper`.
- Hoisted the `0.75` sustained-window ratio (was a magic number in three places)
  to a named `SUSTAINED_WINDOW_RATIO` constant.

---

## [0.1.8-alpha] — 2026-06-14

Two new features for seeing performance over time (feature round, wave 2 of 2).

### Added
- **Benchmark trends** — a third "Trends" tab in the Benchmark screen charts your
  scores over time (Overall / CPU / GPU / Memory) for a chosen run type, with a
  first-vs-latest delta and best-ever. Read-only over your existing benchmark
  history (no schema change); only completed runs are plotted, and the
  same-device-only honesty caption is always shown. Flavors are charted
  separately since scores aren't comparable across Quick/Standard/Full.
- **Gaming session recorder** — record a play session (FPS + CPU/GPU temps +
  clocks + power at 1 Hz) and review the timeline afterward with four charts.
  Start/stop from a Record button on the floating HUD (captures real in-game FPS
  via the SurfaceFlinger/PServer path) or from the Dashboard (temps/clocks/power
  only — noted honestly in the UI). Keeps the 10 most recent sessions, oldest
  auto-pruned; sessions are capped at 3 h as a safety. New `game_sessions` table
  (DB v6 → v7, destructive — bench/stability history reset on upgrade, pre-alpha).

---

## [0.1.7-alpha] — 2026-06-14

Two new features for longer, cooler play sessions (feature round, wave 1 of 2).

### Added
- **Battery time estimate** — the Dashboard shows estimated play time remaining
  at the current power draw, derived from the live discharge wattage and the
  battery's remaining charge (via `BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER`).
  Power is smoothed over a short window; the estimate is clearly labelled as
  approximate and shows an honest "unavailable" state when charge/current can't
  be read. Shows "Charging" instead of a bogus time when plugged in.
- **Temperature alerts** — set a temperature threshold in Settings and get a
  notification when CPU/GPU/battery temperature crosses it, with optional
  auto-switch to a chosen cooler profile. Includes hysteresis (won't re-fire
  until it cools past threshold − 3 °C) and a 60 s rate-limit so it never spams.
  Alerts run while monitoring is active (HUD/Dashboard open) — the in-game HUD
  is the intended companion; this is noted in the Settings copy.

---

## [0.1.6-alpha] — 2026-06-14

A security-hardening release driven by a full audit of the codebase. No new
user-facing features — these are correctness and safety fixes that make the app
safer for everyone.

### Security
- **Update integrity** — the in-app updater now verifies that a downloaded
  update is signed by the **same certificate as the currently-installed build**
  before it will hand the APK to the system installer. A tampered or
  man-in-the-middle'd APK is refused. Downloads are additionally restricted to
  `https://` URLs on GitHub hosts only, and the APK is written to app-private
  internal storage (closing a sdcard swap window).
- **Root-script injection** — every user-controlled value (profile name,
  governor) interpolated into a generated "run as root" script is now
  POSIX-escaped, and comment lines are newline-stripped. A profile named
  `foo' ; rm -rf /data` can no longer inject a command that runs as root.
- **Backup import** — imports now **abort** on a newer-than-supported schema
  version instead of importing anyway, and every imported profile/governor
  string is validated against shell-metacharacter injection before it is saved.
- **Component hardening** — the two `BOOT_COMPLETED` receivers are no longer
  exported (they only need the system broadcast), and verbose root-command
  logging is gated to debug builds so it never lands in release logcat.

### Fixed
- Double-tapping **Download** in the updater could start two concurrent
  downloads writing the same file and corrupt the APK — the updater now guards
  against re-entry while a download is in flight.

### Changed
- Live telemetry sampling now runs on `Dispatchers.IO` (`flowOn`), so all the
  per-tick sysfs reads happen off the main thread — the in-game HUD no longer
  does file I/O on the UI thread while a game runs underneath it.

---

## [0.1.5-alpha] — 2026-06-11

### Added
- **In-app updater** — the app now checks GitHub Releases for a newer version, shows the
  release's patch notes right in the app, and can **download and install** the update for you
  (the system installer handles the final confirmation — nothing installs silently). If a
  release has no APK, it falls back to opening the Releases page. No more hunting for the APK
  in a browser.

---

## [0.1.4-alpha] — 2026-06-11

A big quality-of-life, polish, and "keep up with updates" release.

### Added
- **Share & export** — share any benchmark or stability result as text (Intent share sheet).
- **Copy to clipboard** — tap key values (scores, clock summaries, tune-history lines) to copy them.
- **History search & sort** — filter Tune History by preset name; sort benchmark history (Newest / Highest).
- **Undo on delete** — deleting a stability run or tune-history entry shows an Undo snackbar.
- **Active-tune indicator** — the Dashboard shows the currently active profile (or "Stock").
- **Backup & restore** — export everything (profiles, tune history, benchmark runs) to a JSON file you
  can save or move to another device, and import it back.
- **What's New** — an in-app changelog screen, a post-update "what changed" banner, and a
  **Check for updates** button (opens GitHub Releases). Plus "Report an issue" and "View source" links.
- **Appearance** — pick an accent colour (blue / purple / emerald / amber / rose). Dark theme, always.
- **Units** — choose MHz/GHz and °C/°F.

### Changed
- UI refinement across the app: shared stat tiles, consistent spacing, section-header icons,
  chart "warming up" placeholders, and a more polished, cohesive look.
- GitHub project page: badges, table of contents, download link, more screenshots, plus new
  CHANGELOG, CONTRIBUTING, ROADMAP, code of conduct, and an improved PR template.

---

## [0.1.3-alpha] — 2026-06-08

Stability test made real: CPU and GPU pegged together at peak (device actually heats to ~95°C, fans ramp) instead of light GPU-only load. Honest sustained÷peak metric replaces misleading min/max FPS variance. Verdict gated on real clock-drop + temp.

### Added
- CPU + GPU simultaneous pegging in Stability test (devices now reach realistic ~95°C under load)
- GPU MHz telemetry added to thermal and clock monitoring
- Four result charts on benchmark completion: FPS, temperature, CPU+GPU clocks, power consumption
- Real sustained ÷ peak performance metric (replaces variance-based estimate)

### Changed
- Stability test verdict criteria now gate on measurable clock throttle + temperature rise (a held run reads "Rock solid", not false positive throttling)
- Thermal headroom reporting more conservative when thermal management is active

### Fixed
- Run-hang issue caused by coroutine starvation during intensive GPU benchmarks
- Negative clock-drop verdict bug (malformed throttle analysis on some devices)

**⚠️ Breaking database change:** Room schema reset — local benchmark history and tune history cleared on upgrade. (New versioning prevents re-triggering on next launch.)

---

## [0.1.2-alpha] — 2026-06-08

Benchmark deep-dive: GPU frame-time percentiles, category sub-scores, throttle analysis. Redesigned result UI with new Dashboard "At a glance" card and comprehensive Hardware explainers.

### Added
- **Benchmark result breakdown:**
  - GPU frame-time percentiles (1% low, p99, frame-time consistency)
  - CPU, GPU, and Memory category sub-scores
  - Throttle analysis: sustained vs. peak MHz, time-to-throttle, thermal headroom, average watts, energy (mWh)
- Dashboard "At a glance" card summarizing current device state
- Per-section explainers in Hardware tab (SoC, memory, storage, battery, display, radios)
- Improved HUD legibility under different lighting conditions

### Fixed
- Full benchmark run mislabeled as "Aborted — time limit" on completion

**⚠️ Breaking database change:** Room schema reset — all previous benchmark and tune history cleared on upgrade.

---

## [0.1.1-alpha] — 2026-06-08

Silent-failure fixes, empty states, GPU sliders + profile saving, localization, HUD accessibility, and GitHub project scaffolding.

### Added
- HUD failure messaging: when a write fails (HUD ± stepper, direct clock set), a reason is now shown
- Empty states for Benchmark results list and saved Tune profiles
- GPU cluster sliders in Tune tab (matched to CPU clusters)
- "Save as profile" button in Tune (save current state as a custom preset)
- Localized timestamps throughout the app
- HUD accessibility labels for all numeric fields
- GitHub issue templates: bug_report.md, device-compatibility.yml
- GitHub project banner and initial README screenshots
- License corrected: MIT → Apache-2.0 in all headers

### Changed
- Hardware speed test now surfaces errors clearly (network, storage, RAM speed test failures show reasons)
- Improved HUD opacity and padding for readability

### Fixed
- HUD stepper write failures now show the underlying cause instead of silent fail

---

## [0.1.0-alpha] — 2026-06-03

Initial public release: universal performance monitor, in-game floating HUD, benchmark and stability test suite, CPU/GPU clock presets, comprehensive hardware inspector, first-launch setup wizard.

### Added
- **Live Dashboard:** per-core CPU MHz sparklines, GPU load and frequency, all kernel thermal zones, RAM, battery, fan RPM
- **Floating HUD Overlay (RTSS-style):** compact or verbose mode, real in-game FPS, per-core load + MHz, all thermals, live battery watts; draggable, frame-coalesced movement; ± clock steppers and saved-profile cycling
- **Benchmark Suite:**
  - Quick (~20 s): CPU integer single-thread
  - Standard (~1 min): CPU (int, float, AES, multi-thread) + RAM bandwidth + GPU + draw-call ceiling
  - Full (~3 min): Standard + sustained throttle test
  - Side-by-side comparison of runs with overlaid throttle curves
  - Honest self-relative rating (% of chip's full ceiling, never fake cross-device score)
- **Stability Test:** sustained GPU loop (3DMark Wild Life Extreme style), FPS curve, thermal curve, peak temperature, stability % metric
- **Tune (Clock Presets):**
  - Algorithmic presets (Battery Saver / Balanced / Performance / Max) from actual OPP table (device-specific, never hardcoded)
  - Community-tuned presets for supported handhelds
  - Custom profile save and optional per-app auto-switching
  - `.sh` script generation for "Run script as Root" flow on AYN/Retroid devices
  - Display refresh-rate switcher (60 / 90 / 120 / 144 Hz)
  - Honest write-readback verification
- **Hardware Inspector:**
  - SoC family, friendly name, GPU, core topology
  - Memory type, storage class/vendor, battery, display, radios
  - One-shot speed tests (sequential and random 4K I/O, RAM bandwidth, network)
- **Tune History:** persistent log of all preset/script writes with pathway details
- **First-Launch Setup Wizard:**
  - Permissions checklist (draw-over, usage access, battery optimization)
  - Optional Advanced Unlock guide (SELinux toggle, unlock script walkthrough)
- **Device Adapters:** initial support for AYN Thor, AYN Odin 3, Retroid Pocket 6 (adapters present for Odin 2, Pocket 5, high-end phones)
- **Open Source:** Apache-2.0 license, no telemetry, no accounts, no ads

---

[Unreleased]: https://github.com/mayusi/Calibrate-SoC/compare/v0.1.3-alpha...HEAD
[0.1.3-alpha]: https://github.com/mayusi/Calibrate-SoC/releases/tag/v0.1.3-alpha
[0.1.2-alpha]: https://github.com/mayusi/Calibrate-SoC/releases/tag/v0.1.2-alpha
[0.1.1-alpha]: https://github.com/mayusi/Calibrate-SoC/releases/tag/v0.1.1-alpha
[0.1.0-alpha]: https://github.com/mayusi/Calibrate-SoC/releases/tag/v0.1.0-alpha
