# Architecture
*Last Updated: 2026-10-17* · Greenfield: **current** vs **planned** marked explicitly. Source of intent: `TEMPLATE.md` §§1–7.

## Current
`MainActivity` (`app/src/main/kotlin/com/akane/inspiration/MainActivity.kt`) → `HomeScreen` (`ui/home/HomeScreen.kt`) ← `HomeViewModel` (`ui/home/HomeViewModel.kt`, `StateFlow`). Theme tokens in `ui/theme/` (Color, Type, Shape, Spacing, Theme). Placeholder list content only.

## Product goals (planned)
1. **Exact data**: read real power data via Shizuku (UID 2000) instead of `power_profile.xml` estimates.
2. **Insight layer**: deterministic rule engine turns raw stats into plain-language diagnoses + one-tap Shizuku fixes.
3. **Zero observer effect**: no polling while the screen is off; event-driven snapshots (<150 ms) on SCREEN_ON/OFF, POWER_CONNECTED/DISCONNECTED, or app open. Live gauge updates (1.5 s) only while the Cockpit is visible.

## Planned layers
```
:feature:{dashboard,xray,sleeplab,health,toolkit}   Compose UI + MVI ViewModels
          │
:core:diagnostics        rule engine (night vampire, awake ratio >8%, modem >35% of screen, sensor leak), wakelock/alarm dictionary, Battery Story text
:core:telemetry-engine   snapshot diff (Δ = S_now − S_ref), proto parsing, coulomb counter, sysfs reader, deep-sleep/Doze math
:core:database           Room, WAL, 30-day downsampling
:core:shizuku-ipc        ShizukuManager (permission/binding StateFlow), ShizukuBinderWrapper around system services, HiddenApiBypass init, TelemetryUserService (AIDL, UID 2000)
```
System services targeted: `batterystats` (IBatteryStats / BatteryUsageStats), `powerstats` (ODPM rails), `power`, `deviceidle`, `usagestats`, `activity`; plus `dump --proto` over a ParcelFileDescriptor pipe and `/sys/class/power_supply/*`.

## Build phases (TEMPLATE.md §7)
1. Gradle + Shizuku IPC core · 2. telemetry/proto/snapshot diff + Room · 3. diagnostics engine · 4. design system + Canvas charts · 5. five screens + ViewModels.

## Unverified assumptions (check on device)
SELinux access to sysfs/kernel wakelocks as shell on API 36/37; ODPM availability per SoC; hidden-API method signatures across API 31–37.
