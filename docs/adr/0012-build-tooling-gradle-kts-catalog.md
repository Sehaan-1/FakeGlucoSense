# ADR-0012: Build tooling: Gradle Kotlin DSL, version catalog, AGP 9.x

- **Status:** Accepted
- **Date:** 2026-10-13

## Context

The repo has JVM modules and Android modules. Dependency versions must stay consistent across
them. The Android toolchain has moved quickly: AGP 9.x changed Kotlin integration, and the
Compose Compiler now ships as a Kotlin Gradle plugin.

Checked 2026-10 (see `docs/research/prior-art.md` §6):

- AGP **9.1.1** requires Gradle **9.3.1** and JDK 17, and supports API 37.
- Kotlin Gradle plugin **2.4.20** is current upstream. We pin **2.3.21** because the KSP release
  train (KSP **2.3.12**, needed by Room's annotation processor) tracks the 2.3 Kotlin line.
  Revisit when a KSP release matches Kotlin 2.4.
- Compose Compiler is the `org.jetbrains.kotlin.plugin.compose` plugin.
- Compose Material 3 **1.4.0** is the stable release.

## Decision

1. **Gradle Kotlin DSL** (`*.gradle.kts`) everywhere, with a **version catalog** at
   `gradle/libs.versions.toml`.
2. **Gradle 9.3.1**, **AGP 9.1.1**, **Kotlin 2.3.21**, **KSP 2.3.12**. With AGP 9, Kotlin is built into the
   Android plugin, so the separate `kotlin-android` plugin is removed.
3. **JDK 17** for compilation (the AGP minimum), with the Gradle toolchain set in one place.
4. **compileSdk 36, targetSdk 36, minSdk 26.** Notification channels and the foreground-service
   types we use are available from API 26.
5. The **Gradle wrapper** is committed (`gradlew`, `gradle/wrapper/`) so CI and contributors use
   the same Gradle version.
6. **Lint** runs in CI. Warnings are triaged, not silenced globally.

## Consequences

- One place to bump versions. Renovate-style updates are straightforward.
- Build requires JDK 17 or newer and Android Studio (or the Android SDK command-line tools).
- Compose and AndroidX versions are pinned explicitly. We don't use a BOM, to keep the pins easy
  to read and to avoid BOM/version-mismatch surprises.

## Alternatives considered

- **Groovy DSL.** Works, but Kotlin DSL is the current default and is type-checked.
- **Pinning versions per module.** Drifts apart quickly.
- **Compose BOM.** Convenient, but it hides the individual versions. We pin explicitly for
  clarity.
- **AGP 8.x.** Still works, but the stated plan targets the current toolchain. Downgrading is a
  one-line change if needed.

## References

- AGP 9.1.1 release notes: https://developer.android.com/build/releases/agp-9-1-0-release-notes
- Compose compiler setup: https://developer.android.com/develop/ui/compose/setup-compose-dependencies-and-compiler
