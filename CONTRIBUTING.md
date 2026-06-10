# Contributing to Calibrate SoC

Calibrate SoC is in active development and welcomes contributions of all kinds. This guide explains how to report bugs, contribute device compatibility data, write code, and submit pull requests.

## Reporting a Bug

1. Check [open issues](https://github.com/mayusi/Calibrate-SoC/issues) to see if your bug is already reported.
2. Open a [new bug report](https://github.com/mayusi/Calibrate-SoC/issues/new?template=bug_report.md) and include:
   - A clear description of what happened
   - Steps to reproduce (1, 2, 3…)
   - What you expected instead
   - Your device model, SoC, Android version, and app version
   - Logcat output or screenshots (if helpful)

## Device Compatibility Reports (⭐ Most Wanted)

The single most valuable contribution right now is **device compatibility data**. Here's how:

1. Open the app and go to the **Hardware** tab.
2. Tap the **"Report unknown device"** button at the bottom.
3. A pre-filled report is copied to your clipboard.
4. Open a [device compatibility issue](https://github.com/mayusi/Calibrate-SoC/issues/new?template=device-compatibility.yml) and paste the output.
5. Fill in:
   - What features work on your device (checkboxes)
   - Anything that doesn't work (crashes, missing features, incorrect values)
   - Optional logcat and device properties if you hit a bug

No special hardware or knowledge needed — if you can run the app, you can help map support.

## Building and Testing

### Prerequisites
- Android Studio (Koala or newer)
- Android SDK 35
- Android NDK 26+
- Kotlin 2.1

### Build steps
```bash
git clone https://github.com/mayusi/Calibrate-SoC.git
cd Calibrate-SoC
./gradlew assembleDebug
```

The debug APK lands at `app/build/outputs/apk/debug/app-debug.apk`. Sideload it:
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Running tests
```bash
./gradlew testDebugUnitTest
```

## Code style

- **Kotlin + Jetpack Compose:** match the surrounding code style. No strict formatter enforced, so look at nearby functions and follow that pattern.
- **Keep it honest:** the app never fakes data. If a feature needs root/custom kernel/vendor permission and your code can't get it, return a clear error or unavailable state — don't pretend to have done something you haven't.
- **Comments:** explain *why*, not *what*. If the code is unclear, a comment is helpful.

## Adding a Device Adapter

Device adapters live in `app/src/main/java/io/github/mayusi/calibratesoc/data/devicedb/`.

The adapter registry (`DeviceAdapterRegistry`) is the entry point. Look at existing adapters (e.g. `OdinThreeAdapter`, `RetroidPocket6Adapter`) to see the pattern.

**When to add a new adapter:**
- You have a device where tuning or special monitoring paths differ from the generic path (e.g., vendor-specific sysfs nodes, preset locations, script generation).
- You've tested it on real hardware.

**What an adapter provides:**
- Device detection (property matchers)
- CPU/GPU cluster info (cores, OPP tables)
- Vendor tuning paths (if the device has custom firmware)
- Special handling for speed tests, script generation, or clock readback

Feel free to open a draft PR early if you're writing an adapter — we can help with review.

## Submitting a Pull Request

1. **Keep it focused:** one feature, one fix, or one device adapter per PR.
2. **Reference an issue:** if your PR closes an issue, mention it (`Fixes #123`).
3. **Describe what you changed:** in the PR body, explain what the change does and *why*.
4. **Say what you tested on:** include your device model, Android version, and what you tested (e.g., "Built and ran on AYN Odin 3 / Android 13; verified Dashboard updates and HUD renders correctly").
5. **Link the contributing guide:** your PR template will do this automatically.

### PR template checklist
- [ ] I built this locally and tested it
- [ ] My change doesn't fake data (it's honest when something's unavailable)
- [ ] I didn't break existing features
- [ ] Device model + Android version mentioned above

## No CLA, no bureaucracy

There's no Contributor License Agreement. If your code is good and makes the app better and more honest, it's welcome.

## Questions?

Open an issue or start a discussion. The maintainers are active and responsive.

---

Thanks for contributing! Your bug reports, device data, code, and ideas make Calibrate SoC better. 🙏
