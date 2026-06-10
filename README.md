<p align="center"><img src="docs/assets/banner.png" alt="Calibrate SoC" width="100%"></p>

<div align="center">

**A universal performance tuner, monitor, and benchmark suite for Android gaming handhelds.**

Real-time SoC monitoring · in-game floating HUD · CPU/GPU benchmark + stability testing · clock presets · hardware inspector.

[![Status](https://img.shields.io/badge/status-pre--alpha-orange.svg?style=flat-square)](#project-status)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg?style=flat-square)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Android%2010%2B-green.svg?style=flat-square)](#requirements)
[![Contributions](https://img.shields.io/badge/contributions-welcome-brightgreen.svg?style=flat-square)](#contributing)
[![Release](https://img.shields.io/github/v/release/mayusi/Calibrate-SoC?include_prereleases&style=flat-square)](https://github.com/mayusi/Calibrate-SoC/releases)
[![Downloads](https://img.shields.io/github/downloads/mayusi/Calibrate-SoC/total?style=flat-square)](https://github.com/mayusi/Calibrate-SoC/releases)
[![Last commit](https://img.shields.io/github/last-commit/mayusi/Calibrate-SoC?style=flat-square)](https://github.com/mayusi/Calibrate-SoC/commits/main)

</div>

---

## Table of Contents

- [What it is](#what-it-is)
- [Why it helps](#why-it-helps)
- [Download](#download)
- [Screenshots](#screenshots)
- [Features](#features)
- [Project status](#project-status)
- [Setup](#setup-first-launch)
- [Privilege tiers](#privilege-tiers)
- [Safety](#safety)
- [Requirements](#requirements)
- [Building](#building)
- [Contributing](#contributing)
- [Changelog](#changelog)
- [License](#license)

---

> ### ⚠️ Project status: pre-alpha
> Calibrate SoC is in active early development. Core features work and are tested daily on real hardware (AYN Odin 3, AYN Thor, Retroid Pocket 6), but the app is **not yet feature-complete or stable for general use**. Expect rough edges, breaking changes, and missing device support. **Contributions, bug reports, and device-compatibility data are very welcome** — see [Contributing](#contributing).

---

## What it is

Calibrate SoC is an all-in-one tuning utility for modern Android gaming handhelds. It reads everything the kernel exposes about your chip, lets you benchmark and stress-test it, generates safe over/underclock presets from your device's own frequency table, and overlays a live performance HUD on top of your games — all from a single app, no PC required.

It's designed to be **honest about what each device can and can't do**: where a capability needs root, a custom kernel, or a vendor toggle, the app tells you plainly instead of silently failing.

**Best on:** AYN Thor · AYN Odin 3 · AYN Odin 2 · Retroid Pocket 6 · Retroid Pocket 5, and high-end Android phones.
**Works on:** any Android 10+ device for monitoring and benchmarking.

No telemetry. No accounts. No ads. Fully open source.

---

## Why it helps

- **Cooler, longer sessions** — underclock your handheld's SoC for less heat and more battery during long emulation sessions, with presets built from your own chip's frequency table.
- **See what's really happening** — a floating in-game HUD shows real FPS, per-core clocks, and temps over any game.
- **Prove your tune worked** — benchmark before/after a change; the honest self-relative score shows if you're at your chip's ceiling or tuned down.
- **Check sustained performance** — a 3DMark-style stability test loops the GPU under load and reports how much it throttles.
- **Know your hardware** — full SoC/memory/storage/battery inspector with one-tap speed tests.

---

## Download

Grab the latest APK from [Releases](https://github.com/mayusi/Calibrate-SoC/releases/latest), sideload it with `adb install -r`, and you're done.

See [CHANGELOG.md](CHANGELOG.md) for what's new in each release.

---

## Screenshots

<!-- Screenshot PNGs are added by the screenshot step. Until then these
     paths render as broken images in a local preview; they resolve once
     docs/assets/{dashboard,tune,benchmark,stability,hardware,hud-ingame}.png land. -->

<table>
  <tr>
    <td align="center" width="33%">
      <img src="docs/assets/dashboard.png" width="250" alt="Live dashboard"><br>
      <sub><b>Dashboard</b> — per-core clocks, thermals, battery watts</sub>
    </td>
    <td align="center" width="33%">
      <img src="docs/assets/tune.png" width="250" alt="Tune"><br>
      <sub><b>Tune</b> — presets from your own OPP table</sub>
    </td>
    <td align="center" width="33%">
      <img src="docs/assets/benchmark.png" width="250" alt="Benchmark"><br>
      <sub><b>Benchmark</b> — honest self-relative scoring</sub>
    </td>
  </tr>
  <tr>
    <td align="center" width="33%">
      <img src="docs/assets/stability.png" width="250" alt="Stability test"><br>
      <sub><b>Stability</b> — sustained GPU throttle curve</sub>
    </td>
    <td align="center" width="33%">
      <img src="docs/assets/hardware.png" width="250" alt="Hardware inspector"><br>
      <sub><b>Hardware</b> — SoC, memory, storage, battery</sub>
    </td>
    <td align="center" width="33%">
      <img src="docs/assets/hud-ingame.png" width="250" alt="In-game HUD overlay"><br>
      <sub><b>HUD in-game</b> — live FPS &amp; clocks over any game</sub>
    </td>
  </tr>
</table>

### Detailed benchmark output

<table>
  <tr>
    <td align="center" width="50%">
      <img src="docs/assets/benchmark-gpu.png" width="300" alt="GPU benchmark detail"><br>
      <sub><b>GPU detail</b> — 1% low FPS, frame-time consistency, per-frame curve</sub>
    </td>
    <td align="center" width="50%">
      <img src="docs/assets/benchmark-power.png" width="300" alt="Power and thermals"><br>
      <sub><b>Power & thermals</b> — sustained clocks, watts, energy, thermal headroom</sub>
    </td>
  </tr>
</table>

---

## Features

### 📊 Live dashboard
- Per-core CPU MHz sparklines — every core, every cluster
- GPU load gauge, clock, and frequency table
- Every thermal zone the kernel exposes
- RAM usage, battery power draw (W), fan RPM where readable

### 🎮 Floating HUD overlay (RTSS-style)
- Compact horizontal strip **or** scrollable verbose panel
- Real in-game FPS (display-rate path — works for Vulkan emulators too)
- Per-core load + MHz bars, all thermals, live battery watts
- Drag anywhere; smooth, frame-coalesced movement
- Live ± clock steppers + cluster chips + saved-profile cycling (where the device's tuning path is unlocked)
- Toggle from a Quick Settings tile or the dashboard

### ⚡ Benchmark
- **Quick** (~20 s) — CPU integer single-thread
- **Standard** (~1 min) — CPU integer + float + AES + multi-thread + RAM bandwidth + GPU + draw-call ceiling
- **Full** (~3 min) — Standard plus a sustained throttle test
- Chip-independent, wall-clock-targeted kernels so the same flavor takes the same time on any device
- Side-by-side comparison of any two runs with overlaid throttle curves
- An honest, self-relative rating ("running at this chip's full ceiling" vs "tuned down to ~68%") — never a fake cross-device score

### 🔥 Stability test
- 3DMark Wild Life Extreme-style sustained GPU loop
- Stability % = lowest loop FPS ÷ highest loop FPS
- Per-loop FPS curve + thermal/throttle curve + peak temperature

### 🎚️ Tune
- Algorithmic presets (Battery Saver / Balanced / Performance / Max) generated from **your** kernel's actual OPP table — never hardcoded
- Community-tuned presets bundled per device
- Save custom profiles, with optional per-app auto-switching
- Generates `.sh` scripts for the no-root "Run script as Root" flow on supported handhelds
- Display refresh-rate switcher (60 / 90 / 120 / 144 Hz where the panel supports it)
- "Verify it worked" readback that's honest when a firmware blocks the check

### 🔎 Hardware inspector
- SoC family + friendly name + GPU + core topology
- Memory type (LPDDR5/5X) and storage class/vendor (UFS — Kioxia, Samsung, Micron…), with device-model fallback when sysfs is restricted
- Display, battery (design capacity, health), radios
- One-shot speed tests: sequential R/W, random 4K IOPS, RAM bandwidth, network

### 📜 Tune history
- Persistent log of every preset / script / vendor-key write, with the pathway used

---

## Project status

Calibrate SoC is **pre-alpha**. Here's the honest state of each area:

| Area | Status |
|------|--------|
| Monitoring + Dashboard | ✅ Working on every tested device |
| Benchmark + Stability | ✅ Working |
| Hardware inspector | ✅ Working (some fields restricted by firmware on certain devices) |
| Floating HUD | ✅ Working |
| Preset generation + script tuning | ✅ Working on AYN/Retroid handhelds |
| Live in-app ± clocks (direct sysfs) | ⚠️ Requires a custom/rooted kernel on most stock handhelds — the app detects and labels this honestly |
| Odin 2 / Retroid Pocket 5 | 🟡 Adapters present, not yet hardware-verified |
| Broad phone support | 🟡 Monitor + benchmark only until more devices are tested |

### Device support

| Device | SoC | Status |
|--------|-----|--------|
| AYN Thor | Snapdragon 8 Gen 2 Leading Version | ✅ Verified |
| AYN Odin 3 | Snapdragon 8 Gen 1 Leading Version | ✅ Verified |
| Retroid Pocket 6 | Snapdragon 8+ Gen 1 | ✅ Verified |
| AYN Odin 2 | Snapdragon 888 | 🟡 Adapter present (untested) |
| Retroid Pocket 5 | Snapdragon 778G+ | 🟡 Adapter present (untested) |
| High-end Android phones | Snapdragon 8 / MediaTek | ✅ Monitor + benchmark |

Have a device not listed? [Report it!](https://github.com/mayusi/Calibrate-SoC/issues/new?template=device-compatibility.yml)

---

## Setup (first launch)

A short wizard runs the first time you open the app. Each step auto-detects when it's granted and advances on its own:

1. **Draw over other apps** — for the floating HUD
2. **Usage access** — lets the HUD know which game is in front
3. **Ignore battery optimization** — keeps the monitor/HUD alive during long sessions

The wizard then offers an **optional Advanced Unlock** guide for live in-app tuning, which walks you through:
- **Force SELinux** (available on AYN and Retroid handhelds) — flip the vendor toggle
- **Run the unlock script** — a one-time `.sh` you run via your device's *Run script as Root*. It grants persistent shell-tier permissions (DUMP for real FPS, usage stats, secure-settings) and, on a capable kernel, opens direct clock writes.

Everything is also re-checkable from the **Settings** tab.

> On stock Snapdragon handhelds (e.g. Retroid Pocket 6), the CPU `scaling_max_freq` nodes are kernel-read-only, so *instant* in-app ± clocks need a custom kernel. Clock tuning still works through the one-tap script path — the app tells you this directly rather than pretending the toggle failed.

---

## Privilege tiers

| Tier | What you get |
|------|--------------|
| **Magisk / KernelSU (root)** | Full CPU/GPU sysfs writes, instant HUD ± steppers, presets applied live |
| **AYN/Retroid firmware + unlock script** | Real game FPS, vendor preset switching, refresh-rate control, full monitoring |
| **Shizuku bound** | Cross-vendor equivalent of the firmware path |
| **Nothing granted** | Read-only monitor + benchmark + hardware scan (still fully useful) |

---

## Safety

- Every tunable is **snapshotted before it's written** and reverted on reboot by default.
- A **Restore stock** action rolls back to the factory baseline at any time.
- The first sysfs write requires a one-time typed confirmation.
- Stress tests have a hard time cap and a thermal kill switch (default 85 °C SoC).
- The preset generator refuses unsafe configs (e.g. `min == max`, or a min clock that would prevent idle).

---

## Requirements

- Android 10 (API 29) or newer
- arm64 (every supported handheld qualifies)

## Building

Requires Android Studio (Koala or newer), Android SDK 35, NDK 26+, Kotlin 2.1.

```bash
git clone https://github.com/mayusi/Calibrate-SoC.git
cd Calibrate-SoC
./gradlew assembleDebug
```

The debug APK lands at `app/build/outputs/apk/debug/app-debug.apk`. Sideload with `adb install -r <apk>`.

Release builds are debug-signed unless a `keystore.properties` is present at the project root (it is gitignored — your signing keys never enter the repo).

---

## Contributing

Calibrate SoC is pre-alpha and **actively welcomes contributions of every kind**. See [CONTRIBUTING.md](CONTRIBUTING.md) for detailed guidelines:

- 🐛 **Bug reports** — open an issue with your device, Android version, and steps to reproduce.
- 📱 **Device compatibility** — got a handheld not listed above? Use the **Report unknown device** button in the Hardware tab to generate a pre-filled report. This is the single most valuable contribution right now.
- 🔧 **Pull requests** — device adapters, bug fixes, UI polish, and new presets are all great. Please keep PRs focused and describe what you tested on.
- 💡 **Ideas** — feature requests and design feedback via issues.

There's no CLA and no bureaucracy. If it makes the app better and more honest, it's welcome.

---

## Changelog

See [CHANGELOG.md](CHANGELOG.md) for a detailed history of each release, including what's new, what changed, and what's fixed.

---

## License

[Apache-2.0](LICENSE) — permissive, with an explicit patent grant. No warranty.

## Credits

**Community tuners & inspiration**
- [**TheOldTaylor**](https://github.com/TheOldTaylor/Odin3-CPU-Underclock) — the Odin 3 community underclock values bundled as "Community Tuned" presets (original discoverers u/twoohfive205 and u/JoaozaoS, credited in that repo).
- [**langerhans / OdinTools**](https://github.com/langerhans/OdinTools) — reference for the vendor Settings-key and per-app-switch patterns (no code copied).
- **SmartPack-Kernel-Manager** — read for reference only; no code copied.

**Open-source libraries** — Jetpack Compose & Material 3, Hilt, Room, DataStore, Kotlin Coroutines, kotlinx.serialization, Okio, Vico Charts, Shizuku, libsu. (All Apache-2.0.)

**Development tooling** — parts of this codebase were written with the help of AI coding assistants under human direction, review, and on-device testing: **Anthropic Claude** (primary) and **DeepSeek** (research/review). These are tools that assisted the work — not authors or maintainers — and the project's direction, testing, and final decisions are human.

Full attribution and license details: [CREDITS.md](CREDITS.md).
