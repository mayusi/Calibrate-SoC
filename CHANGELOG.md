# Changelog

All notable changes to Calibrate SoC are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

Nothing yet.

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
