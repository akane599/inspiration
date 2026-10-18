---
name: android-emulator-qa
description: "Exercise Android UI flows on an emulator with ADB, UI trees, screenshots and log evidence."
---

Run from the project root. Read AGENTS.md and applicable nested instructions. Helpers live in `.codex/android-kit/`. Preserve existing project conventions. Use normal Codex permissions. Do not change provider/authentication settings or launch nested agent CLIs. Never claim a check ran unless you observed its result.

Choose an explicit device serial. Ask before destructive data resets/uninstall operations unless already authorized. Keep sensitive logs and screenshots local.

# Android Emulator QA

Validate Android app flows in an emulator with repeatable adb commands, UI tree inspection, screenshots, and logs.

## Core Workflow

1. Identify the emulator.
   - Run `python3 .codex/android-kit/emulator/scripts/android-qa.py devices`.
   - Pick the concrete serial shown by `adb devices -l`, and store it mentally as `<serial>` for the rest of the workflow.
   - If multiple devices are present, state which serial is being used before running follow-up commands.

2. Build and install the app when source is available.
   - Discover install tasks with `./gradlew tasks --all | rg install` on macOS/Linux or `.\gradlew.bat tasks --all | rg install` on Windows.
   - Prefer the narrowest matching task, such as `./gradlew :app:installDebug --console=plain` or `.\gradlew.bat :app:installDebug --console=plain`.
   - If the project uses another wrapper script, follow the repo's documented command instead of inventing a new build path.

3. Resolve and launch the app.
   - List likely packages with `adb -s <serial> shell pm list packages | rg <app-or-company-name>`.
   - Resolve the launch activity with `python3 .codex/android-kit/emulator/scripts/android-qa.py resolve-activity --serial <serial> --package <package>`.
   - Launch with `adb -s <serial> shell am start -n <package>/<activity>`.

4. Inspect UI before tapping.
   - Dump UI XML with `python3 .codex/android-kit/emulator/scripts/android-qa.py dump-ui --serial <serial> --out android-qa-output/ui.xml`.
   - Summarize it with `python3 .codex/android-kit/emulator/scripts/android-qa.py summarize-ui --input android-qa-output/ui.xml --out android-qa-output/ui-summary.txt`.
   - Read the summary to choose exact target text, content descriptions, or resource IDs.
   - Compute tap coordinates from XML with `python3 .codex/android-kit/emulator/scripts/android-qa.py pick --input android-qa-output/ui.xml --target "Settings"`.
   - Tap only coordinates derived from the UI tree: `adb -s <serial> shell input tap <x> <y>`.

5. Navigate carefully.
   - Use `adb -s <serial> shell input keyevent 4` for Back.
   - Use `adb -s <serial> shell input swipe <x1> <y1> <x2> <y2>` for scrolling.
   - Start swipes away from the display edges, roughly 150-200 px from left or right, to avoid triggering gesture navigation.
   - If a target is missing and the tree contains scrollable nodes, scroll once, dump again, and re-check before concluding it is unavailable.

6. Capture evidence.
   - Screenshot: `python3 .codex/android-kit/emulator/scripts/android-qa.py screenshot --serial <serial> --out android-qa-output/screen.png`.
   - App logs: `python3 .codex/android-kit/emulator/scripts/android-qa.py logcat --serial <serial> --package <package> --out android-qa-output/logcat.txt`.
   - Crash buffer: `adb -s <serial> logcat -b crash -d > android-qa-output/crash.txt`.
   - Include the screenshot path, relevant UI summary lines, and log path in the final QA result.

## Raw adb Fallbacks

Use direct adb when the wrapper does not cover a needed action:

```bash
adb devices
adb -s <serial> shell cmd package resolve-activity --brief <package>
adb -s <serial> shell am start -n <package>/<activity>
adb -s <serial> exec-out screencap -p > android-qa-output/screen.png
adb -s <serial> exec-out uiautomator dump /dev/tty > android-qa-output/ui.xml
adb -s <serial> shell input tap <x> <y>
adb -s <serial> shell input text "hello"
adb -s <serial> shell input keyevent 4
adb -s <serial> logcat -c
adb -s <serial> shell pidof -s <package>
adb -s <serial> logcat --pid <pid>
adb -s <serial> logcat -d > android-qa-output/logcat.txt
```

## Coordinate Picking Rules

Always derive tap coordinates from `uiautomator` XML, not from screenshots. Screenshots are for visual verification and reporting. XML bounds have the form `bounds="[x1,y1][x2,y2]"`; tap the center point.

The exact-match helper checks `text` and `content-desc`:

```bash
python3 .codex/android-kit/emulator/skills/android-emulator-qa/scripts/ui_pick.py android-qa-output/ui.xml "Settings"
```

The summary helper creates a compact overview for selecting targets:

```bash
python3 .codex/android-kit/emulator/skills/android-emulator-qa/scripts/ui_tree_summarize.py android-qa-output/ui.xml android-qa-output/ui-summary.txt
```

## Reporting

Report Android QA results with:

- Device serial and package tested
- Build or install command used, if any
- Navigation steps performed
- Screenshots and log files captured
- Observed result versus expected result
- Any blocker, including missing emulator, missing package, unresolved activity, absent UI node, or missing app process for log capture
