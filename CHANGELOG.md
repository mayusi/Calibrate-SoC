# Changelog

All notable changes to Calibrate SoC are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

Nothing yet.

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
