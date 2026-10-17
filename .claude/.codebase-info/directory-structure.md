# Directory Structure
*Last Updated: 2026-10-17*

## Current
```
TEMPLATE.md                 product spec (Turkish)
CLAUDE.md, PROGRESS.md      project instructions, baseline + decision log
gradle/libs.versions.toml   version catalog
app/
  src/main/kotlin/com/akane/inspiration/
    MainActivity.kt
    ui/home/                HomeScreen.kt, HomeViewModel.kt (placeholder)
    ui/theme/               Color, Type, Shape, Spacing, Theme
  src/main/res/values/      strings.xml, themes.xml
  src/test/kotlin/…         HomeViewModelTest.kt
  src/screenshotTest/…      HomeScreenScreenshots.kt (light/dark/large-font/tablet/empty)
  src/screenshotTestDebug/reference/  committed PNG refs
.claude/                    kit scripts, live-rules, commands, this map
```

## Planned (TEMPLATE.md §1)
`core/shizuku-ipc`, `core/telemetry-engine`, `core/diagnostics`, `core/database`, `feature/dashboard`, `feature/xray`, `feature/sleeplab`, `feature/health`, `feature/toolkit`.
