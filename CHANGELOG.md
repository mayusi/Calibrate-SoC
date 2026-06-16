# Changelog

All notable changes to Calibrate SoC are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

Nothing yet.

---

## [0.1.32-alpha] — 2026-06-16

Adversarial-audit hardening pass over the new AYANEO + fan-curve surface, plus a
battery/security/cleanup round. Telemetry is now a single shared 1 Hz stream, the
dangerous-path block list is expanded and component-matched, and several orphaned
code paths were removed.

### Performance
- **Single shared telemetry stream (biggest battery win).** Every default-interval
  subscriber (Dashboard, AutoTDP, Tunes, Advanced, the HUD assembler, throttle/boost
  services, session recorder) now observes ONE process-shared hot flow instead of
  each one spawning its own 1 Hz sysfs-polling loop. With several screens open this
  collapses 4–5 duplicate loops (per-core freq, GPU, meminfo, every thermal zone,
  battery, cooling devices) into one; the loop stops 5 s after the last subscriber
  leaves, so a backgrounded app polls nothing. The 4 Hz benchmark variant stays a
  cold, short-lived flow.
- **Battery % read off the composition thread.** The Dashboard "At a glance" card
  used to make a synchronous `registerReceiver(ACTION_BATTERY_CHANGED)` binder call
  on the UI thread every telemetry tick. It now reads battery % in the ViewModel via
  a sticky `BroadcastReceiver` (updates only when the level actually changes).

### Security
- **Expanded the dangerous-node block list** for custom sysfs paths: `core_pattern`
  (a root-escalation primitive), `kptr_restrict`, `perf_event_paranoid`,
  `dmesg_restrict`, `modules_disabled`, and the `/sys/class/power_supply/*` charge/
  voltage family (`constant_charge_current*`, `voltage_max`, `charge_control*`,
  `input_current_limit` — a write here could damage the battery). Matching is now
  whole-component everywhere, so the real nodes block while neighbours like
  `/proc/kmem_stats` and `/proc/sys/kernel/panic_on_oops` are no longer false-blocked.
- **PServer writer validator no longer weaker than the door.** `PServerWriter`'s
  local sysfs-path check now rejects NUL bytes (the doc always claimed this) and
  applies the same dangerous-node block list as `TunableMetadata`, so a path that
  bypassed the door validator can't reach the root shell.
- **Backup/share import rejects shell-meta in `extraSysfs` values.** For an unknown
  sysfs path the metadata validator returned no value check, so a crafted profile
  could carry `0; reboot`. Import now rejects shell metacharacters + control chars in
  `extraSysfs` values, mirroring the OTA validator.

### Cleanup
- Removed the unreachable `NoOpGameBoostLauncher` stub (the real `GameBoostLauncherImpl`
  is bound) and the orphaned `SettingsKeyWriter.writeAndVerifyKernelNode` honesty path
  (never wired into a production write) plus its dead tests.

---

## [0.1.31-alpha] — 2026-06-16

AYANEO zero-setup live tuning + Odin custom fan curves.

### Added
- **AYANEO zero-setup vendor tuning.** Discovered (by decompiling AYANEO's own apps)
  that binding the exported `com.ayaneo.gamewindow` AIDL service lets the privileged
  overlay perform real sysfs writes (GPU/CPU clocks, perf mode) with **no root, no
  Shizuku, no script** — AYANEO's PServer-equivalent. A new vendor adapter drives it,
  verified live on the Pocket DS (GPU node moved 680→585→680 MHz).
- **Custom fan curves** for devices that expose a controllable fan (Odin family +
  AYANEO via the vendor service): map temperature → fan level, applied and reverted
  honestly, gated on whether the device's fan is actually controllable.

---

## [0.1.30-alpha] — 2026-06-16

Fix a catastrophic AutoTDP cap-collapse + sticky clocks; vendor-agnostic device access.

### Fixed
- **AutoTDP could floor the CPU to 384 MHz mid-game and stay stuck.** Root causes: a
  GameNative Wine wrapper was unknown to the classifier (blind), a null GPU signal
  read as "idle" → BATTERY_SAVER, and there was no hard cap floor. Then the revert ran
  inside a cancelled coroutine, so it was skipped (reboot-only recovery). Fixed with
  four guardrails: a hard 40 %-of-max cap floor (8th invariant), a `NonCancellable`
  revert on every exit path, "hold" on a null signal instead of acting blind, and a
  wrapper-aware classifier.
- **Vendor-agnostic device access** — the Shizuku-only path is now generalised so
  vendor-settings devices light up live tuning where the surface exists.

---

## [0.1.29-alpha] — 2026-06-16

Smart AutoTDP — a goal-seeking governor — plus four new features. Verified on Odin 3
and Retroid Pocket 6.

### Added
- **Smart AutoTDP governor** — a band-controller that drives toward a goal (5 modes
  incl. AUTO) instead of a fixed cap: it nudges CPU/GPU caps to hold the target while
  honouring hard safety invariants (cap floors, thermal debounce, null-signal hold).
- **Game Boost** — a short, bounded performance burst on game launch (per-app), with
  an honest revert when the game leaves the foreground.
- **Throttle Guard** — predictive thermal forecasting that eases caps before the
  kernel throttles, rather than after.
- **Per-app Tunes** (formerly "Presets") — bind a saved tune to a package; applied on
  foreground-enter, reverted on exit, with an app reaper for stuck processes.

### Notes
- Adversarial review before build caught four bad numbers; multi-device testing caught
  a backlight-as-cooling-device false throttle and app-UID SELinux denials on uclamp /
  GPU devfreq (now read via a PServer-root fallback).

---

## [0.1.26-alpha] — 2026-06-15

AutoTDP now PROVES it's working.

### Changed
- **Live effect data** — AutoTDP surfaces the MEASURED cap delta, power savings, and
  session energy (null until a completed probe backs it, never a fabricated number),
  with a bolder, denser status UI.

---

## [0.1.25-alpha] — 2026-06-15

AutoTDP reliability + HUD direction.

### Fixed
- **AutoTDP actually starts now** — three silent-fail causes fixed.

### Changed
- **New horizontal HUD** — denser horizontal layout; removed the manual clock stepper
  (AutoTDP is the single clock-control surface now).

---

## [0.1.22-alpha] — 2026-06-15

Real live tuning that actually lands, working AutoTDP.

### Fixed
- **AutoTDP was load-blind and over-eager on thermal** — `/proc/stat` was restricted,
  so load read as zero (added a freq-proxy load floor), and a single 95 °C sample
  thermal-killed it (now 105 °C with a 2-sample debounce). Verified live on the Odin 3
  release build.
- Live writes now reliably land via the PServer-LIVE root-shell path (chmod-sandwich +
  readback verification), instead of silently no-op'ing against a read-only node.

---

## [0.1.19-alpha] — 2026-06-15

Fix the SELinux/root prompts, Direction-C UI, new features.

### Fixed
- Reduced spurious SELinux/root prompts on the no-root path; more reliable AutoTDP
  startup.

### Changed
- **Direction-C "Arsenal" UI** restyle across the key screens (categorical accent
  edges, metric tiles, status pills).

---

## [0.1.17-alpha] — 2026-06-14

The AutoTDP suite + a HUD remake — the headline feature wave.

### Added
- **AutoTDP** — an automatic thermal/power governor that caps clocks toward a target
  with honest, MEASURED effect reporting and a full set of safety invariants.
- **No-root live control** path (PServer-LIVE on AYN/Odin) so the HUD ± and AutoTDP can
  write the kernel without Magisk.
- **Real in-game FPS** capture via the SurfaceFlinger/PServer path, fed to the HUD and
  the Smart engine.
- **Cross-device safety** — wrapper-aware classification, null-signal honesty, and
  per-device capability gating across the supported handhelds.

---

## [0.1.16-alpha] — 2026-06-14

Third audit round — focused on the never-audited new feature surface (benchmark hub, external-score logging, comparative A/B, session stats, baseline degradation) + the recent hardening. 19 confirmed-new findings (0 critical, 3 high, 7 medium, 7 low); the verifiers dropped 12 as false-positive or already-fixed. 662 unit tests green (was 629).

### Security
- **Decompression bomb in `PresetShareCodec` (HIGH)** — `inflate()` had no cap on decompressed size, so a crafted share code could expand to GBs → OOM crash on paste. Added `MAX_INFLATED_BYTES` (256 KiB) cap in the inflate loop (throws → clean `ShareDecodeResult.Error`), plus a `MAX_BASE64_LENGTH` (64 KiB) cap before the base64 decode to prevent pre-decompression memory exhaustion.
- **`BackupManager.validateProfile` now validates `extraSysfs` (defence-in-depth)** — the apply path already validated custom sysfs paths/values, but the import/backup trust boundary did not, so a malicious shared/backup profile's `extraSysfs` sat in the saved store until apply-time. Now rejected at import via `TunableMetadata.validateCustomSysfsPath` + value validation, mirroring the OTA validator.

### Fixed
- **Share codes dropped `extraSysfs` (HIGH data-loss)** — `ShareablePreset` was missing the field, so sharing a profile silently lost its Advanced Tuning knobs. Added the field, bumped `CURRENT_FMT_VERSION` to 2, **back-compatible** (v1 codes decode to empty extraSysfs via the serialized default; no existing code breaks).
- **FPS-dip detector used AND instead of OR (HIGH)** — `SessionSummary` flagged a dip only below `minOf(80%-of-avg, 40fps)`, so on a high-refresh device a 120→55fps stutter registered nothing on the Thermal Timeline. Fixed to `(fps < dipThreshold || fps < absoluteFloor)` matching the code's own comment.
- **Dangerous-path blocklist substring false-positives (MEDIUM)** — `reboot`/`drop_caches`/`sysrq-trigger` were matched as substrings, wrongly blocking legit paths like `/sys/.../reboot_mode/reboot_mode`. Now bare-name entries match whole path components (`split('/')`), full-path entries keep prefix/substring match; the real dangerous nodes stay blocked.
- `GpuFrameResult.downsample` now truly preserves first+last (it dropped the last point despite the doc); `ComparativeRun` tie-band uses abs + epsilon floor for near-zero values; the 1%-low percentile count rounds instead of floor-truncating; manually-entered benchmark scores must be finite + `< 1e10` (blocks `Infinity`/`1e308`).

### Quality / perf
- Cached un-`remember`ed `SimpleDateFormat` instances in two screens; removed dead `isEmpty()` check in `AppStatsScreen`; clarified the NUL-byte check in `validateCustomSysfsPath`.

### Deferred (lower-value, batched for later)
- `AdvancedTuningScreen` god-file extraction (2235 LOC), `CpuCoresSection` recomposition keying, date-format helper consolidation, score-name Unicode hygiene — all medium/low, no correctness impact.

---

## [0.1.15-alpha] — 2026-06-14

The three device-tested security/safety items deferred from 0.1.14, implemented and verified. 629 unit tests green.

### Security
- **TLS certificate pinning** on all GitHub network calls (UpdateChecker, RemoteContentRepository, ApkDownloader) via a new `GitHubCertPins`. Pins each host's CURRENT INTERMEDIATE + the ROOT above it, extracted from the LIVE chains (Sectigo E36 + root E46 for github.com/api/codeload; Let's Encrypt R12 + ISRG Root X1 for the githubusercontent CDN) — real values, openssl-verified, not placeholders. **Fail-open by design:** a pin mismatch (e.g. a CA rotation) logs a warning and falls back to standard HTTPS rather than bricking updates; the APK signature-pin remains the hard install gate, which is why fail-open on transport is safe. Closes the OTA-content MITM gap from the last audit.
- **Symlink-resolve in `validateCustomSysfsPath`** — after the string checks, the path is canonicalised and the resolved target re-validated (must stay under /sys or /proc, not hit the dangerous-path list), blocking a symlink that escapes to e.g. /proc/sys/kernel/panic. Non-existent paths (targeting another SoC) and non-Unix-rooted resolutions (the Windows test host) fall back to string validation — no over-rejection of legit nodes.

### Added
- **Real battery state-of-charge benchmark abort** — long benchmark/stability runs now stop with `ABORTED_BATTERY_LOW` when charge drops below 15% and the device is not charging, protecting against a mid-run shutdown. Independent from the existing thermal/temp abort, and only triggers on a genuine percent read (never on a null/unknown reading). Battery % is read via `BatteryManager.BATTERY_PROPERTY_CAPACITY`.

### Notes
- Two agent-introduced regressions were caught at integration and fixed before release: the symlink check rejected valid paths on the Windows unit-test host (now guarded to Unix-rooted resolutions), and the cert-pin test invoked an unmocked `android.util.Log` (now stubbed).

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

[Unreleased]: https://github.com/mayusi/Calibrate-SoC/compare/v0.1.32-alpha...HEAD
[0.1.32-alpha]: https://github.com/mayusi/Calibrate-SoC/releases/tag/v0.1.32-alpha
[0.1.31-alpha]: https://github.com/mayusi/Calibrate-SoC/releases/tag/v0.1.31-alpha
[0.1.30-alpha]: https://github.com/mayusi/Calibrate-SoC/releases/tag/v0.1.30-alpha
[0.1.29-alpha]: https://github.com/mayusi/Calibrate-SoC/releases/tag/v0.1.29-alpha
[0.1.26-alpha]: https://github.com/mayusi/Calibrate-SoC/releases/tag/v0.1.26-alpha
[0.1.25-alpha]: https://github.com/mayusi/Calibrate-SoC/releases/tag/v0.1.25-alpha
[0.1.22-alpha]: https://github.com/mayusi/Calibrate-SoC/releases/tag/v0.1.22-alpha
[0.1.19-alpha]: https://github.com/mayusi/Calibrate-SoC/releases/tag/v0.1.19-alpha
[0.1.17-alpha]: https://github.com/mayusi/Calibrate-SoC/releases/tag/v0.1.17-alpha
[0.1.16-alpha]: https://github.com/mayusi/Calibrate-SoC/releases/tag/v0.1.16-alpha
[0.1.15-alpha]: https://github.com/mayusi/Calibrate-SoC/releases/tag/v0.1.15-alpha
[0.1.14-alpha]: https://github.com/mayusi/Calibrate-SoC/releases/tag/v0.1.14-alpha
[0.1.13-alpha]: https://github.com/mayusi/Calibrate-SoC/releases/tag/v0.1.13-alpha
[0.1.12-alpha]: https://github.com/mayusi/Calibrate-SoC/releases/tag/v0.1.12-alpha
[0.1.11-alpha]: https://github.com/mayusi/Calibrate-SoC/releases/tag/v0.1.11-alpha
[0.1.10-alpha]: https://github.com/mayusi/Calibrate-SoC/releases/tag/v0.1.10-alpha
[0.1.9-alpha]: https://github.com/mayusi/Calibrate-SoC/releases/tag/v0.1.9-alpha
[0.1.8-alpha]: https://github.com/mayusi/Calibrate-SoC/releases/tag/v0.1.8-alpha
[0.1.7-alpha]: https://github.com/mayusi/Calibrate-SoC/releases/tag/v0.1.7-alpha
[0.1.6-alpha]: https://github.com/mayusi/Calibrate-SoC/releases/tag/v0.1.6-alpha
[0.1.5-alpha]: https://github.com/mayusi/Calibrate-SoC/releases/tag/v0.1.5-alpha
[0.1.4-alpha]: https://github.com/mayusi/Calibrate-SoC/releases/tag/v0.1.4-alpha
[0.1.3-alpha]: https://github.com/mayusi/Calibrate-SoC/releases/tag/v0.1.3-alpha
[0.1.2-alpha]: https://github.com/mayusi/Calibrate-SoC/releases/tag/v0.1.2-alpha
[0.1.1-alpha]: https://github.com/mayusi/Calibrate-SoC/releases/tag/v0.1.1-alpha
[0.1.0-alpha]: https://github.com/mayusi/Calibrate-SoC/releases/tag/v0.1.0-alpha
