---
description: Robolectric Compose UI tests idle the main looper at teardown
globs: ["**/src/test/**/*UiTest.kt", "**/src/test/**/VisibilityEffectTest.kt"]
prompt: ["/robolectric/i"]
---
- In a Robolectric test that hosts Compose (an `ActivityController` or a `ComposeView`), have tearDown destroy the host and then idle the main looper: `shadowOf(Looper.getMainLooper()).idleFor(100, MILLISECONDS)`.
- Why: `AndroidUiDispatcher.Main` is shared across the whole process and outlives each test. When the looper reset wipes a dispatch posted at teardown, the dispatcher's "scheduled" flags stay set, and nothing dispatched to Main runs again in any later test class in that JVM. Taps still pass, but scroll and animation coroutines silently never run. Failures then look order-dependent and flaky (SQ-66, 2026-10-21: `ScrubGesturesUiTest` stalled `ObscuredTouchGuardUiTest`'s drag test).
- A test whose assertion needs a dispatched coroutine (scrolling, animation, `LaunchedEffect`) first asserts that the dispatcher runs. Copy `assertMainDispatcherRuns()` from `ObscuredTouchGuardUiTest`, so a polluting class fails with a clear message instead of looking like a regression.
