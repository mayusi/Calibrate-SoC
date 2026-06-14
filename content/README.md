# OTA Content Channel

Committing JSON files here pushes content to every user's app on their next launch — **no new APK release required**. Only DATA goes OTA; code still ships as signed APKs.

## Files

| File | Purpose |
|------|---------|
| `manifest.json` | Version index. Bump `version` whenever you change any data file. |
| `adapters.json` | Device-adapter overlay. Remote entries **win by key** over the bundled `adapters.json`. |
| `presets.json` | Community preset overlay. Added to the preset list as "Community (unverified)". |

## How it works

On launch the app fetches `manifest.json` from this path on the `main` branch. If the `version` integer has changed since the last fetch, it re-downloads `adapters.json` and `presets.json` and caches them locally. Fetches are throttled to at most once every 12 hours. The app works fully offline — bundled assets are always the floor.

All URLs are `https://raw.githubusercontent.com/mayusi/Calibrate-SoC/main/content/*` and are validated against a GitHub-host allowlist before any network call.

## Adding a new device adapter

Edit `adapters.json`. Each entry must conform to the `DeviceAdapter` schema (see `app/src/main/java/io/github/mayusi/calibratesoc/data/devicedb/DeviceAdapter.kt`). Rules enforced by the app at parse time:

- `key`: lowercase `[a-z0-9_]+` only
- `displayName`: non-blank, no shell metacharacters
- `notes`: optional, no shell metacharacters
- `perfDaemonsToStopOnWrite`: no whitespace or shell metacharacters

After editing, bump `manifest.json` → `version` by 1 and update `updatedAt`.

### Minimal adapter example

```json
[
  {
    "key": "my_device_model",
    "displayName": "My Device Model",
    "vendorAppPackage": null,
    "fanAdapter": null,
    "perfPresetAdapter": null,
    "thermalLabelOverrides": {},
    "perfDaemonsToStopOnWrite": [],
    "chmodLockCpuFreqWrites": false,
    "communityPresets": [],
    "notes": "Brief notes about quirks."
  }
]
```

A remote adapter entry for an existing key (e.g. `retroid_pocket6`) **overrides** the bundled entry. The bundled set is unchanged; all other bundled entries remain.

## Adding community presets

Edit `presets.json`. Each entry must conform to the `Preset` schema (see `data/presets/Preset.kt`). Rules:

- `id`: non-blank, no shell metacharacters
- `name`: non-blank, no shell metacharacters
- `description`: no shell metacharacters
- `cpuPolicyGovernor` values: valid kernel governor token (no whitespace, no `/`)

The app **always** surfaces these as `GENERIC_UNKNOWN_FAMILY` ("Community (unverified)") regardless of the `verification` field in the JSON. Users must go through the extra "unknown device — I accept the risk" confirm before applying.

After editing, bump `manifest.json` → `version`.

### Minimal preset example

```json
[
  {
    "id": "community_odin3_eco",
    "name": "Odin 3 Eco",
    "description": "Conservative underclock for Odin 3. Reported by community; not formally verified.",
    "verification": "GENERIC_UNKNOWN_FAMILY",
    "sourceUrl": "https://github.com/mayusi/Calibrate-SoC/discussions/1",
    "cpuPolicyMaxKhz": {"0": 1785600, "6": 1958400},
    "cpuPolicyMinKhz": {"0": 384000, "6": 1017600}
  }
]
```

## Security notes

- Remote content is **validated** on the device before use. Shell metacharacters, control characters, and malformed JSON are rejected. A bad entry is logged and skipped; the rest of the file is still applied.
- Remote presets are **never auto-applied**. They go through the same user-tap-Apply + confirm gate as all other presets.
- Remote content is fetched only over HTTPS from `*.githubusercontent.com`.
- The app verifies each URL against an allowlist (`ApkDownloader.isAllowedUrl`) before making any network call.
