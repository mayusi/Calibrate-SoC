## [0.1.16-alpha] — 2026-06-14

A correctness & safety pass — fixes found by a deep audit of the newer features.

### Security
- **Pasting a malicious preset code can no longer crash the app.** Share codes are now size-capped on both decode steps, so a crafted "zip bomb" code can't balloon into gigabytes of memory.
- **Imported/shared presets now have their advanced kernel paths checked at import**, not just when applied — a bad path is rejected at the door (defense in depth).

### Fixed
- **Sharing a preset now includes your Advanced Tuning knobs.** Previously, custom sysfs tweaks were silently dropped from a shared code, so the person you sent it to got an incomplete tune. (Older codes still import fine.)
- **The in-game throttle/FPS-dip timeline now actually catches dips on high-refresh devices.** A drop from, say, 120→55 fps was being missed; it now flags a dip when frames fall below 80% of your session average *or* below 40 fps.
- A custom tuning path like `…/reboot_mode/…` is no longer wrongly blocked just because it contains the word "reboot" — the dangerous-path list now matches whole path components, while still blocking the real dangerous nodes.
- GPU frame-time charts now keep the true first and last data point; the 1%-low and A/B "tie" calculations were tightened for edge cases; absurd manually-typed benchmark scores (infinity / huge values) are now rejected.

### Under the hood
- Cached a few date formatters and removed some dead code in the newer screens. 662 unit tests pass.

## [0.1.15-alpha] — 2026-06-14

The security follow-ups from the last audit, now done properly.

### Security
- **Update & content downloads are now certificate-pinned** to GitHub's real CAs (verified against the live certificates), so a network attacker with a rogue certificate can't slip in tampered content. If GitHub ever rotates its certificate authority, the app safely falls back to normal HTTPS (and logs it) rather than breaking your updates — defense without the risk of bricking.
- **Tuning paths are now symlink-checked** — a custom or community tuning path that secretly points at a dangerous system file is blocked, while legitimate device paths still work.

### Added
- **Benchmarks now stop if your battery gets genuinely low** (under 15%, and not charging) so a long run can't drain you into a shutdown. This is separate from the existing "too hot" safety stop — and it only triggers on a real battery reading, never a guess.

## [0.1.14-alpha] — 2026-06-14

A big audit-and-harden pass — dozens of fixes plus four new features. Nothing you do day-to-day changes; the app is just safer, lighter, and more useful.

### Security
- **Hardened the root-script generator** so a custom tuning path or a community preset can never sneak a shell command into a script that runs as root — kernel paths are now fully escaped and validated, and remote presets are checked for this too. Also tightened the vendor-settings write path and the boot-script deployer the same way.

### Fixed
- **AYN vendor fan/perf settings now revert on reboot** like everything else (they were silently sticking before).
- **Benchmark "battery" abort is honest now** — it was actually reacting to battery *temperature*, not low charge, but said "low battery". It now says the battery got too hot. (A real low-charge check is coming next.)
- Fixed a rare crash when rapidly cycling profiles from the floating HUD, a sticky HUD status message during fast taps, a couple of silently-swallowed HUD errors, and a possible overlay leak if the HUD service was killed.
- Added missing safety guards against divide-by-zero in the GPU benchmark math and an integer-overflow guard in the native memory test.
- Temp files from saving your tune snapshots/history are always cleaned up now.

### Performance (smoother in-game HUD)
- The live monitor no longer re-scans every thermal sensor from scratch each tick — it caches the sensor list and only reads temperatures, cutting I/O on the gaming hot path.
- The in-game FPS reader caches its expensive lookups instead of re-running them twice a second.
- Independent sensor reads now run in parallel each tick instead of one after another.

### Added — Features
- **Thermal Event Timeline** in your gaming session recordings: see exactly when the device throttled and how it lined up with FPS dips — proof a cooler tune actually helped.
- **Per-App Performance Dashboard**: your saved sessions grouped by game, with average FPS, peak temperature, and average power per app — see which game needs tuning.
- **Comparative A/B Benchmark**: pick two profiles and run the same benchmark under each, then see a side-by-side score/throttle comparison. If a profile can't actually apply on your device, it tells you honestly instead of faking the result.
- **Baseline Degradation check** on the Hardware screen: compares your device now against its factory baseline. (Today it honestly tells you it needs a richer baseline to detect wear — it never guesses.)

## [0.1.13-alpha] — 2026-06-14

### Fixed
- **Community presets now apply** — the verified device presets delivered over the live content channel (Retroid Pocket 6, Thor, Odin 3) were being rejected by the app's own safety check because their names contain normal punctuation like `—`, `/`, and `(…)`. The check now correctly allows punctuation in display names (which are never run as commands) while keeping the strict guard on the values that actually become a script. Your seeded presets show up again.

## [0.1.12-alpha] — 2026-06-14

Big quality pass on the things that matter most: presets that make sense, a benchmark that teaches, and Advanced Tuning you can actually use without root.

### Fixed
- **Battery preset no longer breaks emulators** — the old "Battery Saver" used a governor that pinned the CPU to its slowest speed, which made emulators stutter and crackle audio. Replaced with one that stays low but ramps up when a game needs it.

### Changed — Presets (the core of the app)
- **Use-case presets** instead of abstract percentages: Cool & Quiet (battery/2D retro), Light Emulation (N64/PSP/Dreamcast), PS2 / GameCube — Sustained, Switch / Heavy, **Anti-Throttle — Sustained Max** (the new "underclock for steadier performance" tune), and Stock (undo). Each tells you what it's for, what it does, and the heat/battery tradeoff.
- **Smarter tuning** — presets are derived from your device's real frequency table and cluster layout (little/big/prime), and the sustained ones cap *below* the throttle point so you get steadier framerates over a long session than running flat-out.
- **Verified device presets** (Retroid Pocket 6, Thor, Odin 3) delivered through the live content channel — no app update needed.

### Changed — Benchmark (now teaches, not just a number)
- **Bottleneck diagnosis** — every result now tells you WHY your score is what it is: CPU-bound, GPU-bound, or thermal-throttled, and which tune helps.
- The heavy 3D test now runs as part of a Standard benchmark (not hidden), with a clearer scene, per-loop bar chart, throttle-over-time graph, storage speed, and an automatic "vs your last run" comparison.

### Changed — Advanced Tuning (usable without root now)
- On a no-root device, Advanced Tuning becomes a **script builder**: configure any knob, then Generate Script and run it via your device's "Run as Root" — so it actually does something instead of greying out. Run the one-time unlock and many knobs become live-adjustable. Controls that genuinely need root are clearly labelled, never faked.

## [0.1.11-alpha] — 2026-06-14

The big one — deep clocking + an all-in-one benchmark hub.

### Added — Clocking (the heart of the app, much deeper)
- **Advanced Tuning** — a whole new screen of kernel-level controls (where your device + permissions allow): per-core CPU on/off, CPU governor tunables, full GPU power levels + governor + throttle, scheduler boost (schedtune/uclamp), input boost, memory/DDR bus, I/O scheduler, VM tweaks, and a power-user custom-rule editor. Everything is risk-labelled, validated, reverts on reboot, and is greyed out with an honest reason when your device can't do it.
- **Honest voltage card** — explains plainly that CPU/GPU undervolt isn't possible on stock locked devices (needs a custom kernel), instead of pretending.
- **Deeper monitoring (no root needed)** — CPU time-in-state (which clocks your device actually uses), thermal trip detail (where it throttles), and live DDR/bus frequency.

### Added — Benchmark hub
- **New heavy GPU benchmark** — a real sustained 3D scene (geometry + lighting + multi-pass) at up to 1440p, with honest FPS percentiles, 1% lows, and a stability score.
- **Benchmark hub** — detects other benchmark apps you have installed (3DMark, AnTuTu, Geekbench, GFXBench…) and opens them, and lets you log your scores from them into a personal trends history. (The app opens them and stores what you type — it doesn't run or verify them.)

### Changed
- Presets and profiles can now carry the new advanced knobs, so a shared preset or per-app profile can apply a full deep tune.

### Added
- **Share & import presets** — share any saved profile as a short code (copy or send it), and import one someone sends you. Imported presets are marked unverified and never apply on their own — you review and apply them like any other.
- **Auto-check for updates** — the app can quietly check GitHub about once a day and show a banner when a new version is out (with "Later" and dismiss). Toggle it in Settings. Updates never install on their own.

### Changed
- **Live content updates** — new device support and community presets can now arrive without waiting for a full app update. The app fetches a small content file from GitHub on launch (offline-safe; the built-in data is always the fallback).

## [0.1.9-alpha] — 2026-06-14

Polish & quality pass.

### Added
- **Cancel a running test** — Benchmark and Stability tests now have a Stop button so you're never stuck waiting for a long run to finish.

### Changed
- The floating HUD now **remembers where you put it** — it reopens at your last position instead of resetting.
- The Dashboard's active-tune chip is now honest after a reboot: it says **"Last applied"** (not "Active") when a tune may have reverted on restart, so it never claims something that isn't true.
- Smoother live monitoring — the telemetry samplers do less work each tick (fewer allocations and system calls), so the in-game HUD is a little lighter.

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
