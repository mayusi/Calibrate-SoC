# Roadmap

A short, honest view of where Calibrate SoC is and where it's headed. This is
**pre-alpha** software — plans shift as real-world testing teaches us things.

## Working now ✅

- Live SoC monitor (per-core clocks, GPU, thermals, RAM, battery watts)
- In-game floating HUD with real FPS and live ± clock steppers
- Benchmark suite (Quick / Standard / Full) with honest, self-relative scoring and
  GPU frame-time detail (1% low, p99, consistency)
- Real sustained-peak stability test (CPU + GPU) with throttle/thermal/clock/power curves
- Clock presets generated from your chip's own frequency table + custom profiles
- Hardware inspector with speed tests
- No-root tuning via generated scripts on AYN / Retroid handhelds

## In progress 🔧

- **Quality of life** — share/export results, copy values, history search & filter, undo on delete
- **Keeping up with updates** — in-app "What's new", check-for-updates, version info
- **Data portability** — back up and restore all your data (profiles, history, runs) as a file
- **UI polish** — consistent components, spacing, icons, accent colours
- **Broader device verification** — more handhelds tested on real hardware

## Help wanted 🙌

- **Device compatibility reports** are the single most useful contribution. If your
  handheld isn't fully supported, use the **Report unknown device** button in the
  Hardware tab and open a [device compatibility issue](https://github.com/mayusi/Calibrate-SoC/issues/new?template=device-compatibility.yml).
- Device adapters, bug fixes, and UI improvements — see [CONTRIBUTING.md](CONTRIBUTING.md).

## Not planned (for now)

- Auto-installing updates (the app is sideloaded; we link you to Releases instead)
- A light theme (dark suits the gaming-handheld vibe; accent colours are coming instead)
- Telemetry, accounts, or ads — never

This roadmap is a direction, not a promise. Priorities follow what real devices and
real users actually need.
