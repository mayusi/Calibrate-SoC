# Smart AutoTDP + Performance Features — Design (for approval)

**Status:** Plan — awaiting user approval before any code.
**Date:** 2026-06-16 · Device: AYN Odin 3 (SD8 Elite/Gen3-class, Adreno 750) · App: Calibrate SoC

Locked user decisions:
- Smart goal selection = **our curated goal modes + freedom** (auto by default, override anytime).
- Learning = **both, phased** — live real-time engine first, per-game learning later.
- Approach = **research + plan first** (this doc), then build on approval.
- Also: **rename "Presets" → "Tunes"**. And add **more root-powered performance features**.

---

## The core finding (why this is very doable)

Across all research: **the plumbing already exists.** The capability/probe layer (`SysfsProber`) already discovers uclamp, GPU devfreq min/max, GPU die temp, thermal trip points, cooling-device state, `scaling_min_freq`, fan_mode, schedutil tunables, DDR floor. `KernelTunables` already has path-builders for them; the script/preset path already writes `scaling_min_freq` with a cook-guard. The **gaps are only**:
1. A smarter **decision policy** (the engine reacts to fixed thresholds; it doesn't seek a goal).
2. A few probed knobs not yet wired into the live engine's actuator set (min-freq floor, GPU devfreq, uclamp, fan_mode).
3. Curated **features** that package existing knobs into one-tap value.

So Smart AutoTDP is mostly a rewrite of one pure function (`AutoTdpEngine.decide`) + wiring already-probed knobs — not a from-scratch system. Safety, telemetry, proof-of-effect, and the write path are untouched.

---

## Part 1 — Smart AutoTDP: the algorithm

**Chosen design: Goal-Gated Utilization-Band Controller with Thermal Pre-emption.**
This is the pattern proven by Linux devfreq (`simple_ondemand`), Intel RAPL (1 Hz power loop), and the Steam Deck GPU governor.

**How it works (per 1 Hz tick):**
1. **Safety first (unchanged):** thermal kill (105°C/2-sample/3-grace), battery kill, cpu0-never-parked, load-blind honesty. The optimizer only ever proposes states *inside* these.
2. **Classify context:** IDLE / VIDEO / LIGHT_GAME / HEAVY_GAME / UNKNOWN — from GPU busy%, CPU load, foreground package, real FPS when present. Debounced (3 agreeing ticks). Freq-proxy CPU load is a tiebreaker only (it's coarse); GPU% + foreground-pkg are the trusted signals.
3. **Resolve the target band** from the active goal × context. Each goal sets a *utilization band* for the bottleneck resource (e.g. "keep the GPU 70–85% busy" = "just enough headroom, no more").
4. **One-notch adjustment toward the band:** above band → loosen one OPP step; below band → tighten one step; **inside the band → hold** (the dead-band is what kills oscillation). Plus thermal pre-emption: if temp is *rising fast* toward the soft target, tighten before the kernel throttles — so the device cools itself smoothly instead of a sudden FPS cliff.
5. **Honesty:** when load is unreadable → hold (never park on phantom-idle). FPS, when real, only adds a *floor* ("don't tighten past playable") and a smoothness signal — when FPS is null (most apps) the loop runs cleanly on GPU%+CPU+thermal.

**Why it's smart, not just reactive:** the band *is* the goal — it actively hunts the lowest-power operating point that still meets the goal and *holds there*, and it pre-empts heat instead of reacting to it. Stability comes from: dead-band (≥15pts wide) + one-notch-per-tick + asymmetric timing (tighten slow, loosen fast) + a direction cool-down counter (no tighten→stutter→loosen cycles).

### The goal modes ("our own ways" + freedom)

| Mode | Plain meaning | What it optimizes |
|---|---|---|
| **Auto (Detect & Decide)** — default | Reads the situation and picks the right goal every few seconds; shows you what it detected | idle/video → battery; light game → balanced; heavy 3D → balanced or cool-by-thermal-trend |
| **Balanced-Smart** | Best sustainable FPS *and* good temps — holds the knee | GPU 70–85% band, 90°C soft, protect frame smoothness |
| **Max FPS** | Never bottleneck the game; only back off to avoid the kill | GPU 85–95%, 100°C soft, loosen-biased |
| **Cool & Quiet** | Lowest temps + fan noise, accept some FPS | GPU 55–70%, 80°C aggressive pre-empt, fan Smart/Quiet |
| **Battery Saver** | Max battery while staying responsive | GPU 40–60%, hard power ceiling, strongest tighten |

You can run fully-auto or force any mode. The HUD shows the detected context + chosen goal ("Detected: Heavy 3D → Balanced") so you always see the reasoning — and the existing proof-of-effect panel proves it's working (measured power saved, temp held, frame-time variance when real FPS exists).

### New signals + knobs to wire in (all already probed, ROOT-tier, low risk)
- **`scaling_min_freq` floor** on big/prime — the #1 smoothness win (stops down-clock dips that cause stutter). Script path already does it with a cook-guard; just add to the live engine.
- **uclamp.min/max on top-app** — perf-hint the game's own threads instead of blunt core-parking (smoother, reversible, EAS-native).
- **GPU devfreq min/max freq** — finer than the 0–7 pwrlevel; min for frame consistency, max for battery.
- **Real per-core load via root /proc/stat** — already coded; verify it wins the selector on-device (beats freq-proxy).
- **Thermal headroom from trip points + cooling-device state** — replace the guessed 95/105°C with the kernel's *own* throttle thresholds + a live "kernel is throttling now" signal.
- **GPU die temp** (`kgsl-3d0/temp`) — relax GPU before the skin sensor reacts.
- **fan_mode** wired to thermal state (Smart/Sport/Quiet) — the Odin's literal "great temps + quiet" lever. (Odin exposes only the 5 discrete fan presets, NOT a raw PWM curve — we honestly use the presets, not a fake curve.)
- **DDR floor, GPU-bound only** — modest frame-consistency win on bandwidth-bound titles.

### Build order (live first, learning later)
- **Phase 1** — Band controller core (pure rewrite of `decide()` + `GoalProfile`), Balanced-Smart only, exhaustive no-oscillation unit tests. Map old 3 profiles → new goals.
- **Phase 2** — Thermal pre-emption (temp-rise-rate + kernel trip-point headroom).
- **Phase 3** — All goal modes + UI picker + min-freq floor + GPU devfreq + fan_mode wiring.
- **Phase 4** — Frame-time variance signal (the SurfaceFlinger parser already has the vsync timestamps) → smoothness objective + proof.
- **Phase 5** — Auto (context classifier) + HUD "detected context" display.
- **Phase 6 (later)** — Per-game learning: remember the converged operating point per game (`PerAppEfficiencyMap` scaffolding exists).

---

## Part 2 — Rename "Presets" → "Tunes"

People confuse "Presets". Rename the user-facing area + tab to **Tunes** (keep code identifiers; just the strings/labels). Small, safe, do it alongside Phase 1.

---

## Part 3 — New root-powered performance features (ranked)

Research found several finished-but-unwired engines and one-tap opportunities. Top 5 to build:

1. **Game Boost / Max Performance (one-tap brute mode)** — opposite of AutoTDP: pin everything to the ceiling (min-freq floors high, GPU max pwrlevel, performance governors, DDR floor, stop idle clock-gating), time-boxed with a thermal cap + "plug in" hint. Composes writers that already work + are measured on Odin 3 (park = −53% power proven). *Highest perceived value, smallest build.*
2. **Charge limit + bypass charging ("plugged-in gaming")** — cap charge % for longevity + bypass charging so the battery doesn't heat-cycle under load while docked. Genuinely new, root-only, solves the #1 handheld battery-health pain. Gated behind a write-verified node probe (node names vary by firmware — honest "not available" if absent).
3. **Live Predictive Throttle Guard** — `PredictiveThrottleGuard.kt` is a finished, tested engine with NO live consumer; wire it to the existing write path so FPS degrades smoothly under sustained heat instead of a sudden cliff. Biggest win-per-line.
4. **Per-app auto-apply of full tunable bundles** — extend the existing per-app auto-switch so a game launch lands its *complete* tune (Smart AutoTDP goal + Game Boost + display + fan), not just the AutoTDP profile.
5. **Background-app reaper on boost** — free CPU/RAM/scheduler slots by stopping a user-allowlisted set of background apps during gaming; restore on exit. Cheap, high jitter-reduction.

**Honest "no" (won't fake):** true PWM fan curve on Odin 3 (firmware = discrete presets only), real per-OPP undervolt (signed firmware — knee-cap equivalent only), raising thermal trip points (brick risk — block-listed).

---

## What I'd ship as the FIRST release of this work

To get you something real fast without boiling the ocean: **Phase 1 + 2 + 3 of Smart AutoTDP** (the band controller, thermal pre-emption, all goal modes + min-freq/GPU-devfreq/fan wiring) **+ the "Tunes" rename** in one release. Then the new features (Game Boost, charge control, throttle guard) as a following wave. This gets the smart governor — the thing you're most excited about — into your hands first, fully verified on the Odin, then layers features.

Everything stays honesty-first: measured proof only, safety untouched, cpu0 sacred, no synthetic load on your device (unit tests + real gameplay only).

---

## TIGHTENED CONTROL SPEC (post adversarial review — these numbers are LAW)

An adversarial review broke the first-draft numbers. Implement THESE corrected values, not the prose above:

**Loop:** 1 Hz decision; thermal die-temp + `cooling_device/cur_state` sub-sampled at 4 Hz into the 1 Hz tick.
**Smoothing:** busy% EWMA α=0.4; dTemp slope EWMA α=0.5 over ≥3 ticks (never a 1-tick raw delta).

**Dead-band: ≥22pt** (provably > 15pt max OPP-step swing + 6pt 2σ noise → a one-notch correction can't cross the band). Corrected goal bands (GPU busy%) + soft die-temps:

| Mode | GPU band | Soft die-temp | Bias |
|---|---|---|---|
| Max-FPS | 70–95 (25pt) | **95°C** (10°C kill margin) | loosen-biased |
| Balanced-Smart | 63–85 (22pt) | 88°C | hold the knee |
| Cool & Quiet | 48–70 (22pt) | 80°C | aggressive pre-empt, fan Smart/Quiet |
| Battery-Saver | 35–60 (25pt) | 85°C | strongest tighten + hard power ceiling |

Kill stays 105°C / 2-sample / 3-grace, UNTOUCHED.

**Stability:** one notch/tick; **one lever per direction-episode** (don't round-robin actuators by tick — carry a direction on ONE lever until it saturates, then hand off in fixed priority: loosen = GPU-floor→cap→unpark→uclamp; tighten = cap→GPU-floor→park→uclamp). **Cross-actuator cool-down K:** tighten→loosen needs 3 quiet ticks; tighten→tighten K=1; loosen→tighten K=2. Loosen acts on 1 confirming tick, tighten on 2.

**Thermal pre-empt = OR of:** (1) smoothed die ≥ soft_target; (2) EWMA-dTemp ≥ +3°C/s AND within 8°C of soft; (3) any `cooling_device/cur_state > 0` (kernel = throttling NOW → immediate tighten, bypass cool-down). Pre-emptive tighten is exempt from one-lever (safety > smoothness).

**EIGHT INVARIANTS (must hold before emitting any state):**
- **FP-1 (critical):** freq-proxy CPU load may set holdReason but may NEVER originate a state change. The CPU-saturation/unpark fast-path is gated to `hasTrueLoadData` only (this REVERSES the current engine's unpark-on-proxy behavior at AutoTdpEngine.kt:104,241-248). Proxy-only tick → drive on GPU%+pkg+thermal, emit LOAD_BLIND_HOLDING for the CPU dimension.
- **ACT-1:** one lever per direction-episode (above).
- **ACT-2:** {park} XOR {uclamp} on the same cluster per goal — never both.
- **MM-1:** scaling_min_freq floor < bigClusterCap ALWAYS; a tighten that would invert lowers the floor in lockstep.
- **MM-2:** both min and cap ∈ caps.bigClusterOppStepsKhz.
- **CAP-FLOOR (8th — added in 0.1.30, the DEFECT A fix):** the big-cluster cap is NEVER tightened below `CAP_HARD_FLOOR_FRACTION = 0.40` of the top OPP — a HARD invariant, not a heuristic. Enforced in TWO places: (a) `stepCapDown` uses `floorIdx = max(budget-floor, hardFloorIdx)` so a watts-budget floor can only RAISE the floor, and (b) `enforceInvariants` raises any cap that arrived below the hard floor (by any path) to the nearest OPP ≥ floor. The MIN_FREQ_FLOOR lever is mirrored (`stepMinFloorDown` bottoms out at the hard-floor OPP, not stock) so min can't be dragged below it either; MM-1 (min < cap) still has final say. On the AYN Odin 3 little cluster (top 3 532 800 kHz → floor ≈ 1 413 120 → snaps to the **1 708 800 kHz / 1.71 GHz** step); the big/prime cluster scales off its own top OPP. This makes the cur=min=max=384 MHz collapse IMPOSSIBLE BY CONSTRUCTION.
- **H-1:** band-controller reason strings carry config/intent only — NEVER a measured quantity (mW/°C/fps); those live only in the MEASURED tier of AutoTdpEffect. New honesty tier DETECTED (classifier belief) is distinct from DERIVED (config) and MEASURED (probe).
- cpu0 never parked; online ≥ minOnlineCores (preserve).
- **fan_mode:** ≥10s between changes, 5°C hysteresis, monotonic per direction.

**NULL-SIGNAL HOLD rule (GUARDRAIL 3, added in 0.1.30):** a tick that has NEITHER true CPU jiffie load (`hasTrueLoadData==false`) NOR a real GPU read (`gpuLoadPct != null`) is LOAD-BLIND. The band controller routes it to a HOLD with `LOAD_BLIND_HOLDING` ("signals unavailable, holding safe") and NEVER tightens — closing the FP-1 hole for the GPU dimension (a null GPU read was being coerced to 0% busy, a phantom-idle that drove the tighten that floored the cap). The `?: 0` coercion is kept ONLY for the EWMA/HUD display value; the controller is told separately via `gpuSignalValid` whether the signal was actually present. A genuine CPU-saturation LOOSEN stays gated on true load (FP-1), so the asymmetry is honest: blind ⇒ no tighten, real saturation ⇒ relax.

**REVERT-ON-EVERY-EXIT guarantee (GUARDRAIL 2, added in 0.1.30):** stopping AutoTDP — by any path (stop button, thermal-kill, battery-kill, onDestroy, swipe-away/onTaskRemoved, or the daemon `finally`) — reverts ALL writes to the JOURNALED STOCK originals via `AutoTdpRevert.revertNow`, which runs under `NonCancellable + Dispatchers.IO`. DEFECT B: the old revert rode `loopJob`, and every stop path cancelled it, so PServer's inner `withContext(Dispatchers.IO)` threw `CancellationException` before the writes landed — only a reboot (BootRevertReceiver) un-stuck the device. `revertNow` is idempotent (an atomic latch), so the multiple exit paths revert at most once. Thermal/battery kill reverting the cap is correct: it hands control back to the kernel governor at STOCK (the safe state), not an intermediate tuned state.

**Context classifier:** foreground-pkg is the ANCHOR. Upgrade on 2 agreeing ticks; downgrade on 8. Never declass below LIGHT_GAME while foreground-pkg unchanged + was HEAVY_GAME (paused-game guard). FPS used only when isRealFps==true, only as a don't-tighten-below-playable floor, never for classification. **Wrapper-classifier rule (GUARDRAIL 4, added in 0.1.30):** Windows translation-layer wrappers whose foreground package is the wrapper itself (GameNative/IIC `app.gamenative.*`, any `com.winlator*`) are now in `KnownGames.PREFIX_TABLE`, so `defaultHintFor` recognises them and the foreground/paused-game anchor works. While a known game/wrapper is foreground, the classifier FLOORS the context AUTO acts on to at least LIGHT_GAME (→ BALANCED_SMART) — even during the hysteresis confirm window — so AUTO can NEVER route a foreground game to BATTERY_SAVER's aggressive power-cap on a low/absent-GPU tick (DEFECT A trigger #1). Only the returned context is floored; the carried hysteresis state stays honest, and the DETECTED tier still conveys this is a belief, not a measurement.

**Must-have tests (22):** no-oscillation under edge/worst-OPP-swing/two-actuator-chase/cool-down; freq-proxy-never-originates (×3); min<max + park-XOR-uclamp invariants; thermal pre-empt-before-kill + cur_state-immediate + dTemp-noise-rejection + kill-regression; classifier paused-game-guard + asymmetric-hysteresis + anchor-change; FPS-null degradation (×3); honesty no-measured-units + LOAD_BLIND. (Full list in the review; implement all before merging the decide() rewrite.)
