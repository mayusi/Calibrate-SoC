# Credits

## Community tuners & inspiration

- **TheOldTaylor** — [Odin3-CPU-Underclock](https://github.com/TheOldTaylor/Odin3-CPU-Underclock). The Odin 3 "Community Tuned" presets ship the exact frequency values from that repo, with attribution. Original discoverers also credited there: u/twoohfive205 and u/JoaozaoS.
- **langerhans / OdinTools** — [github.com/langerhans/OdinTools](https://github.com/langerhans/OdinTools) (MIT). No code is copied; Calibrate SoC follows the same general pattern of an Accessibility service for per-app profile switching and vendor Settings-key flips for AYN preset modes.
- **SmartPack-Kernel-Manager** — [github.com/SmartPack/SmartPack-Kernel-Manager](https://github.com/SmartPack/SmartPack-Kernel-Manager) (GPLv3). Read for reference only — **no code copied**. Calibrate SoC is MIT-licensed and deliberately stays GPL-free.

## Third-party libraries

| Library | License | Use |
|---------|---------|-----|
| Jetpack Compose + Material 3 | Apache-2.0 | UI |
| Hilt | Apache-2.0 | Dependency injection |
| Room | Apache-2.0 | Benchmark history |
| DataStore | Apache-2.0 | Preferences & profile store |
| Kotlin Coroutines + Flow | Apache-2.0 | Async |
| kotlinx.serialization | Apache-2.0 | JSON |
| Okio | Apache-2.0 | Filesystem abstraction (testable sysfs probing) |
| Vico Charts | Apache-2.0 | Compose line charts (dashboard, benchmark, stability) |
| Shizuku API | Apache-2.0 | Privileged shell access without root |
| libsu | Apache-2.0 | Persistent root shell session (topjohnwu) |

## Community

Bug reports and unknown-device adapter contributions are welcome via GitHub issues. Use the **Report unknown device** button in the Hardware tab to generate a pre-filled report.

---

## Development tooling

In the interest of full transparency: large parts of this codebase were written with the help of AI coding assistants, under human direction, review, and on-device testing on real hardware. Every feature is verified on physical devices before it ships. The AI tools used:

- **Anthropic Claude** — primary development assistant (architecture, implementation, debugging, on-device diagnosis).
- **DeepSeek** — secondary assistant used for research and code review.

These tools assisted the work; they are not authors or maintainers, and they are listed here separately from the human community contributors above. The project's direction, testing, and final decisions are human.
