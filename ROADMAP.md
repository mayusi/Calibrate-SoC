# Roadmap

A short, honest view of where Calibrate SoC is and where it's headed. This is
**pre-alpha** software — plans shift as real-world testing teaches us things.

## Working now ✅

- **Smart AutoTDP** — a goal-seeking thermal/power governor (5 modes incl. AUTO) that
  nudges CPU/GPU caps toward a target while honouring hard safety invariants (cap
  floors, thermal debounce, null-signal hold). Reports MEASURED effect only — never a
  fabricated savings number.
- **Game Boost** — a short, bounded performance burst on game launch (per-app), with an
  honest revert when the game leaves the foreground.
- **Throttle Guard** — predictive thermal forecasting that eases caps before the kernel
  throttles, instead of after.
- **Per-app Tunes** — bind a saved tune to a package; applied on foreground-enter,
  reverted on exit.
- **Custom fan curves** on devices with a controllable fan (Odin family + AYANEO),
  gated on whether the fan is actually controllable.
- **Live, no-root tuning across tiers**, honestly gated per device:
  - **AYANEO zero-setup** — binds AYANEO's own vendor service for real sysfs writes
    with no root, no Shizuku, and no script.
  - **PServer-LIVE** on AYN / Odin — writes the kernel via the vendor root shell after a
    one-time unlock, with readback verification.
  - **Generated `.sh` scripts** as the universal fallback on AYN / Retroid handhelds.
- Live SoC monitor (per-core clocks, GPU, thermals, RAM, battery watts), now driven by a
  single shared 1 Hz telemetry stream so several open screens don't multiply battery cost.
- In-game floating HUD with real in-game FPS (SurfaceFlinger/PServer path).
- Benchmark suite (Quick / Standard / Full) with honest, self-relative scoring and GPU
  frame-time detail (1% low, p99, consistency); real sustained-peak stability test.
- Clock presets generated from your chip's own frequency table + custom profiles;
  gaming session recorder + per-app performance dashboard.
- Hardware inspector with speed tests.
- Data portability — back up / restore everything to a file; preset sharing via short
  paste-safe codes; OTA device-adapter + community-preset updates (DATA only — code
  still ships only as signed APKs).

Everything here is **per-device** — what's reachable depends on your handheld's firmware
and unlock state, and the app says so honestly rather than faking a capability.

## In progress 🔧

- **Broader device verification** — more handhelds tested on real hardware; expanding the
  AYANEO vendor-service path and fan-curve coverage to more models.
- **Security & honesty hardening** — ongoing adversarial audits (defence-in-depth path
  validation, no-fabricated-effect reporting).
- **UI polish** — denser "Arsenal" layouts, clearer capability gating.

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
