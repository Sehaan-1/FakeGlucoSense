# ADR-0003: Shared pure-Kotlin `core` module

- **Status:** Accepted
- **Date:** 2026-10-13

## Context

Three consumers need the same logic: the JVM simulator, the companion app, and the sensor app.
They all need the SFLOAT and CGM Measurement codecs, the trend calculator, the alert evaluator,
and the simulator physiology model. If each one has its own copy, they will drift apart, and
the simulator and the app will disagree about what a trend is.

Running logic unit tests on Android needs an emulator or Robolectric, which is slow. Pure JVM
tests run in milliseconds.

## Decision

We will put all logic that doesn't need Android into a plain **Kotlin/JVM library module**,
`core`. It has no `android.*` or `androidx.*` imports and no Android-specific dependencies. The
simulator, the app, and the sensor app all depend on it.

The module is organized as `model/`, `ble/`, `analysis/`, `sim/`, and `wire/` packages. Each
package has its own files, and no file goes above 1000 lines.

We will use a plain JVM module, not Kotlin Multiplatform. We don't need iOS or JS targets, and
KMP adds configuration overhead. The code is written so it could move to KMP later.

## Consequences

- Most behavior is tested with fast JVM tests in CI and locally.
- The Android modules stay thin: they adapt platform APIs to core types.
- Any change to the wire format or the trend math happens in one place.
- Core can't use Android's `Log`. Logic code returns values, and the edges log them.

## Alternatives considered

- **Duplicate the logic in each app.** Drift is inevitable.
- **An Android library module shared by all apps.** Forces every test to run on the Android
  toolchain, and makes the simulator depend on the Android SDK.
- **Kotlin Multiplatform now.** Premature for two platforms and one target.

## References

- Kotlin docs, "Create a multiplatform library" (context for the future KMP path).
- AGENTS.md §2 (dependency rules).
