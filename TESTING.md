# Calibrate SoC — Hardware Test Matrix

End-to-end checklist to run on real devices before tagging a release. Each row tests one privilege tier on one device class. Pass = all behaviours below match expectations + no crashes in logcat.

## Device matrix

| Device class | Devices to cover | Privilege tiers |
|---|---|---|
| AYN Snapdragon handheld | Odin 2, Odin 3 (preferred), Thor | Magisk/KernelSU, Shizuku, neither |
| Retroid passive | Pocket 4, Pocket 5 | Shizuku, neither |
| AYANEO | Pocket S, Pocket DS | Shizuku, neither |
| MediaTek handheld | Anbernic RG556 | Shizuku, neither |
| Generic Pixel | any rooted + non-rooted Pixel 6+ | all three |
| AVD | Pixel emulator API 30+ | non-root only |

## Per-tier behaviour to verify

### Tier: root (Magisk / KernelSU)

- Dashboard renders per-core MHz changing as you touch slider on Tune.
- Tune → community preset Apply produces sticky write (verify via `adb shell cat /sys/devices/system/cpu/cpufreq/policy*/scaling_max_freq`).
- Reboot reverts everything by default. Confirm post-reboot scaling_max_freq returns to stock.
- Mark a profile Apply on boot. Reboot. Confirm the profile re-applies within 30 seconds of BOOT_COMPLETED.
- Benchmark → Quick produces a CPU score within 5% of the previous run on the same preset (stability).
- Benchmark → Full sustained-throttle for 10 minutes. Verify the throttle curve declines under sustained load and that abort fires if you raise the kill-temp temporarily then lower it below current temp.
- For AYN devices: confirm the daemon-stop write protocol fires by watching `adb shell logcat` for `stop perfd` invocations.

### Tier: Shizuku (no root)

- Dashboard renders with same telemetry as root.
- Tune → CPU sliders Apply returns Rejected with the "Shizuku UserService deferred" message. No crash, no kernel state change.
- Settings → Vendor preset switching (if device is AYN) works via Settings.System keys — verify the AYN system overlay's preset chip updates.
- Benchmark → Quick works (native lib + JNI run as the app UID, no privilege needed).

### Tier: neither

- Dashboard shows whatever sysfs is world-readable (varies by kernel).
- Tune → Apply button is disabled with the "needs root" explainer.
- Benchmark → Quick + Full both work.
- "Report unknown device" share intent fires successfully.

## Per-screen smoke pass

1. **Dashboard** — sparklines update each second. No frame drops scrolling. Privilege chip + handheld chip match Device Info.
2. **Tune** — sliders snap to OPP steps. Community presets show source URL. Unknown SoC presets show extra confirm modal before Apply.
3. **Profiles** — saved profiles list. Apply on boot toggle persists across app restart. Per-app override picker shows installed apps.
4. **Benchmark** — Run history reverse-chronological. Select two rows → Compare card shows delta + overlay chart.
5. **Settings** — Accessibility status flips after granting in system settings (re-enter screen to refresh). Open Device Info button works.
6. **Device Info** — Report unknown device opens system share sheet with prefilled markdown body.

## Crash / safety checks

- Force-stop the app mid-benchmark. Re-open → no orphan progress indicator.
- Pull battery / let device sleep during a Full bench. Watchdog should abort cleanly on resume.
- Apply 10 different presets in a row. Reboot. Confirm revert journal coalesces — only ONE revert per tunable, returning to the original stock value (not the next-to-last).
- Toggle Accessibility off mid-game. Per-app auto-switch should silently no-op rather than apply stale state.

## Tooling

- `adb shell cat /sys/devices/system/cpu/cpufreq/policy*/scaling_max_freq` — ground truth for write verification.
- `adb shell dumpsys thermalservice` — cross-check our temp readings.
- `adb shell logcat -s CalibrateSoC` — only our app's logs (we tag with the class name; consider tightening later).

## Releasing

When the matrix passes for at least Odin 2/3 + a generic Pixel + an AVD, tag `v0.1.0` and upload `app-debug.apk` to GitHub Releases. Production-signed release build needs a `keystore.properties` (gitignored) at project root.
