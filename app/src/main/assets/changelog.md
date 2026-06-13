## [0.1.8-alpha] — 2026-06-14

Two new features for seeing how your device performs over time.

### Added
- **Benchmark trends** — a new "Trends" tab in Benchmark charts your scores over time (Overall / CPU / GPU / Memory) so you can see whether a tune actually improved things. Pick a run type and watch your own history; scores stay same-device-only (never cross-device).
- **Gaming session recorder** — record a play session and review the timeline afterward: FPS, temperatures, clocks and power over time. Start/stop from a Record button on the floating HUD (real in-game FPS) or from the Dashboard. Keeps your 10 most recent sessions; open any one to see exactly when things got hot or dropped frames.

## [0.1.7-alpha] — 2026-06-14

Two new features for longer, cooler play sessions.

### Added
- **Battery time estimate** — the Dashboard now shows roughly how much play time you have left at your current power draw (e.g. "~3h 10m remaining · 8.4 W"). It's an honest estimate that updates live and tells you when it can't be measured.
- **Temperature alerts** — set a temperature in Settings and get a notification when your device crosses it, so you can take a break or cool down. Optionally auto-switch to a cooler profile when it trips. (Alerts run while monitoring is active — keep the floating HUD on while gaming to be warned in-game.)

## [0.1.6-alpha] — 2026-06-14

A security-hardening release. No new features — these fixes make the app safer for everyone.

### Security
- The in-app updater now verifies every downloaded update is signed by the same key as the app you're running before it can install — a tampered or man-in-the-middle'd APK is refused. Updates are also restricted to HTTPS GitHub URLs only and saved to private storage.
- Generated root tuning scripts now fully escape your profile/governor names, so a profile name can never inject a command into the script that runs as root.
- Backups that are newer than the app supports, or that contain unsafe values, are now rejected on import instead of being loaded anyway.
- Tightened a few internal components (boot receivers, debug logging) so other apps can't poke them.

### Fixed
- Tapping "Download" twice could corrupt the update file — the updater now ignores a second tap while a download is in progress.

### Changed
- Live monitoring now does all its sysfs reads off the main thread, so the in-game HUD steals fewer frames from your game.

## [0.1.5-alpha] — 2026-06-11

### Added
- In-app updater — check GitHub for a newer version, read the patch notes here, and download + install the update without leaving the app (the system installer handles the final confirm).

## [0.1.4-alpha] — 2026-06-11

A big quality-of-life, polish, and "keep up with updates" release.

### Added
- Share any benchmark or stability result as text
- Tap key values to copy them to the clipboard
- Search Tune History; sort benchmark history (Newest / Highest)
- Undo when you delete a stability run or tune-history entry
- Active-tune indicator on the Dashboard (shows your current profile or "Stock")
- Backup & restore — export everything to a file and import it back
- What's New screen, a post-update banner, and a Check-for-updates button
- Accent colour picker (blue / purple / emerald / amber / rose) — dark theme always
- Units preference (MHz/GHz, °C/°F)

### Changed
- UI refinement across the app: shared stat tiles, consistent spacing, section icons, chart placeholders
- A more professional GitHub page (badges, changelog, contributing guide, more screenshots)

## [0.1.3-alpha] — 2026-06-08

Stability test made real: CPU and GPU pegged together at peak (device actually heats to ~95°C, fans ramp) instead of light GPU-only load. Honest sustained÷peak metric replaces misleading min/max FPS variance.

### Added
- CPU + GPU simultaneous pegging in Stability test (devices now reach realistic ~95°C under load)
- GPU MHz telemetry added to thermal and clock monitoring
- Four result charts on benchmark completion: FPS, temperature, CPU+GPU clocks, power consumption
- Real sustained ÷ peak performance metric (replaces variance-based estimate)

### Changed
- Stability test verdict criteria now gate on measurable clock throttle + temperature rise
- Thermal headroom reporting more conservative when thermal management is active

### Fixed
- Run-hang issue caused by coroutine starvation during intensive GPU benchmarks
- Negative clock-drop verdict bug (malformed throttle analysis on some devices)

---

## [0.1.2-alpha] — 2026-06-08

Benchmark deep-dive: GPU frame-time percentiles, category sub-scores, throttle analysis. Redesigned result UI with new Dashboard "At a glance" card and comprehensive Hardware explainers.

### Added
- GPU frame-time percentiles (1% low, p99, frame-time consistency)
- CPU, GPU, and Memory category sub-scores
- Throttle analysis: sustained vs. peak MHz, time-to-throttle, thermal headroom, average watts, energy (mWh)
- Dashboard "At a glance" card summarizing current device state
- Per-section explainers in Hardware tab (SoC, memory, storage, battery, display, radios)
- Improved HUD legibility under different lighting conditions

### Fixed
- Full benchmark run mislabeled as "Aborted — time limit" on completion

---

## [0.1.1-alpha] — 2026-06-08

Silent-failure fixes, empty states, GPU sliders + profile saving, localization, HUD accessibility, and GitHub project scaffolding.

### Added
- HUD failure messaging: when a write fails, a reason is now shown
- Empty states for Benchmark results list and saved Tune profiles
- GPU cluster sliders in Tune tab (matched to CPU clusters)
- "Save as profile" button in Tune (save current state as a custom preset)
- Localized timestamps throughout the app
- HUD accessibility labels for all numeric fields

### Changed
- Hardware speed test now surfaces errors clearly
- Improved HUD opacity and padding for readability

### Fixed
- HUD stepper write failures now show the underlying cause instead of silent fail

---

## [0.1.0-alpha] — 2026-06-03

Initial public release: universal performance monitor, in-game floating HUD, benchmark and stability test suite, CPU/GPU clock presets, comprehensive hardware inspector, first-launch setup wizard.

### Added
- Live Dashboard: per-core CPU MHz sparklines, GPU load and frequency, all kernel thermal zones, RAM, battery, fan RPM
- Floating HUD Overlay (RTSS-style): compact or verbose mode, real in-game FPS, per-core load + MHz, all thermals, live battery watts; draggable, ± clock steppers and saved-profile cycling
- Benchmark Suite: Quick, Standard, and Full runs with side-by-side comparison and throttle curves
- Stability Test: sustained GPU loop, FPS curve, thermal curve, peak temperature, stability % metric
- Tune (Clock Presets): algorithmic presets from actual OPP table, community-tuned presets, custom profile save, per-app auto-switching, display refresh-rate switcher
- Hardware Inspector: SoC family, GPU, core topology, memory type, storage class, battery, display, radios, one-shot speed tests
- Tune History: persistent log of all preset/script writes
- First-Launch Setup Wizard: permissions checklist, optional Advanced Unlock guide
- Device Adapters: AYN Thor, AYN Odin 3, Retroid Pocket 6
- Open Source: Apache-2.0 license, no telemetry, no accounts, no ads
