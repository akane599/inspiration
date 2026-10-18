---
name: device-probe
description: Run Inspiration's debug ProbeReceiver probes (PROBE, PROBE_CAPTURE, PROBE_DIFF, PROBE_INSIGHTS, PROBE_REQUEST_PERMISSION) on an emulator and save the InspirationProbe JSON lines as device evidence under build/device-evidence/. Use this whenever a phase or ticket needs a device run, emulator evidence, "probe on API 35/36/37", a check that Shizuku/UserService reads work on a real system, or after a Room schema change that needs pm clear before re-probing — even if the request just says "run it on the emulator".
---

# Device probe

Device evidence in this project means the JSON lines the debug `ProbeReceiver` logs under the `InspirationProbe` tag. The script runs the boot / install / Shizuku / broadcast / logcat sequence repeatably and leaves a file you can cite in PROGRESS.md.

## Run

```bash
bash .claude/skills/device-probe/scripts/device-probe.sh --avd batstats-api36 --probe PROBE_CAPTURE [--clear --grant] [--no-install] [--timeout 120] [--extras "--es reference_kind since_unplugged"]
```

- `--avd`: the probe AVDs are `pixel-api35`, `batstats-api36`, `screenoff-api37`. If a device is already attached (or `ANDROID_SERIAL` is set), it is reused and `--avd` is ignored.
- `--probe`: the action suffix after `com.akane.inspiration.debug.`. Check `app/src/debug/kotlin/com/akane/inspiration/debug/ProbeReceiver.kt` for the actions that exist right now; new phases add probes there.
- `--clear`: `pm clear` after install. Use it when the Room schema changed in place (identityHash mismatch would otherwise crash the app on open). It drops the Shizuku grant, so add `--grant`.
- `--grant`: accept Shizuku's permission dialog automatically (see below).
- `--extras`: passed verbatim to `am broadcast` (e.g. PROBE_DIFF takes snapshot ids).

Output: `build/device-evidence/api<SDK>-<PROBE>.jsonl`, one JSON object per line, ending with `{"probe":"done"}` (or `{"probe":"timeout"}` when the probe's own deadline hit). `build/` is gitignored, so quote the relevant lines into PROGRESS.md or the story log instead of committing the file.

Exit codes: `0` finished, `2` setup failed (boot, installDebug, Shizuku missing/not started), `3` no terminal line before `--timeout` (or probe-side timeout). Treat the exit code as the oracle, not a grep of the output.

## Process rules

- Only the orchestrator boots emulators, and only one emulator/adb run happens at a time. Gradle (`installDebug`) counts toward the two-concurrent-Gradle budget.
- Run API levels sequentially: stop the emulator (`adb emu kill`) before switching `--avd`, otherwise the script reuses whichever device is attached.

## Shizuku permission

The script starts the Shizuku server via its adb starter if it is not running. `pm clear` can drop the Shizuku grant (it did on API 36, not on API 37), and `pm grant … API_V23` does not restore it (Shizuku v11+ keeps its own list), so pass `--grant` together with `--clear`: it fires PROBE_REQUEST_PERMISSION, finds Shizuku's "Allow all the time" button (`android:id/button1`) with uiautomator, taps it, then force-stops the app, because a running process keeps reporting the stale `PermissionRequired` state. If the script still reports a state other than `Ready`, inspect the screen (`adb exec-out screencap -p`) rather than retrying blindly.

## Choosing the interval

Without extras, PROBE_DIFF / PROBE_INSIGHTS pair the latest snapshot with the one just before it, which is often an automatic LIGHT snapshot milliseconds earlier (unplug/screen receivers). Pass `--extras "--es reference_kind since_unplugged"` (also `since_screen_off`, `screen_on_session`, `last_24h`) for a meaningful interval. To simulate discharge: `adb shell dumpsys battery set level 80`, `dumpsys battery unplug`, screen off with `input keyevent 223`, lower the level, capture, then `dumpsys battery reset`. Unplugging at 100% makes Android reset batterystats, so the diff reports `battery_stats_invalid`; unplug below full to evaluate the rules.

## Limits

Emulator batteries are synthetic: charge counters, discharge and per-UID mAh are not representative, and screen-off sleep is not real deep sleep. Evidence from this skill proves the read paths, parsing and wiring work on that API level; it does not validate battery numbers or thresholds. Say so when citing it.
