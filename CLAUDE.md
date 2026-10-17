<!-- android-kit -->
# CLAUDE.md — Inspiration

Shizuku-powered battery analytics, diagnostics and optimization for Android power users: real Binder/kernel telemetry, plain-language insights, zero observer effect. Product spec: `TEMPLATE.md` (Turkish).
Android, developed on Ubuntu from the CLI.

<!-- STACK:BEGIN (filled by /bootstrap) -->
- Origin: new · Integration branch: main
- Language: kotlin · UI: Compose · screenshot tests: compose-preview
- JDK target 17 · Gradle 9.8.0 · AGP 9.4.1 · Kotlin 2.4.20 · compileSdk 37 · targetSdk 36 · minSdk 31
- Modules: :app (spec targets :core:* + :feature:* — Phase 1) · Architecture: single-module MVVM scaffold → clean arch + MVI per spec · DI/DB/Net: none / none (Room planned) / none
- App id: com.akane.inspiration · Launcher: .MainActivity
- Baseline: build ok · 1 unit test, 0 pre-existing failures (PROGRESS.md)
<!-- STACK:END -->

## How work flows here
- **The main session orchestrates; it doesn't implement.** Work becomes Sidequest tickets, executors work in isolated worktrees on their returned Claude or GPT route, and the main session integrates verified results. Bounded inline-safe maintenance follows Sidequest's current rules.
- **Routing** (Sidequest profile `android-kit-hybrid`): Claude Opus/medium orchestrates. Follow current mixed Sidequest routes: Luna bounded work; Terra routine coding/UI/review; Opus debugging, hard coding, experiments and visual judgement; Sonnet research. Explicit category fallbacks may cross Claude/GPT; global fallback stays provider-scoped. Inspect effective routes and disclose overrides or provider blockers.
- **Big or multi-step changes:** plan mode → `/plan-audit` (routed review ticket with the Android checklist) → pin the plan as a Sidequest story → dispatch waves.
- **UI overhaul:** `/ui-overhaul <scope>`.
- **Bugs:** every ticket's checks must pass before integration; weak oracles, unchecked consumers and high-stakes changes require independent review. `/bug-hunt` uses the mixed review-audit route. Both native review plugins use GPT reviewers with Claude finding validation; temporarily disable Sidequest if it blocks them, keep Gateway enabled and restart.
- **End of a session or story:** `/wrap-up` turns corrections and decisions into rules, CLAUDE.md lines and the decision log (you approve each). Personal preferences go to auto memory ("remember that …").
- **Conventions** live in `.claude/live-rules/rules/` and are injected into every session and executor, so they're not repeated here. **Project map:** `.claude/.codebase-info/` (codebase-mapper).
- **Board:** "show the Sidequest board"; side issues mentioned mid-task get filed, not worked.
- **Plugin health / updates:** `/quartermaster:toolshed-doctor`, `/quartermaster:update-toolshed`. After a few weeks of real work: `/quartermaster:resupply`.
- **Compaction:** your last instructions are saved before compaction and re-injected after; re-read them before acting.

## Commands
- Build: `./gradlew :app:assembleDebug --console=plain -q` · Unit: `:app:testDebugUnitTest` · Lint: `:app:lintDebug`
- Screenshots: `:app:updateDebugScreenshotTest` (write refs) · `:app:validateDebugScreenshotTest` (diff)
- Device: `./gradlew :app:installDebug -q && adb shell am start -n com.akane.inspiration/.MainActivity` · logs: `adb logcat -d --pid=$(adb shell pidof -s com.akane.inspiration) | tail -80`
- Emulator: `emulator -avd batstats-api36 -no-window -no-audio &` then `adb wait-for-device shell 'while [[ -z $(getprop sys.boot_completed) ]]; do sleep 1; done'`
