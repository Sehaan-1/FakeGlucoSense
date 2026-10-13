# ADR-0008: Dependency injection: manual container, no Hilt

- **Status:** Accepted
- **Date:** 2026-10-13

## Context

The app has about a dozen collaborators: database, DAO, DataStore, repository, sources, alert
notifier, and ViewModels. The services and workers need the same objects. Hilt would work, but it
adds kapt/KSP processing, generated code, and a larger build. The Android build is the piece we
can't verify locally, so we want to keep it as small as possible.

## Decision

We will use a **hand-written `AppContainer`** owned by the `Application` class. It exposes
`lazy` singletons for the database, repository, sources, and notifier. ViewModels take their
dependencies through factory lambdas. Services and workers reach the container through
`(applicationContext as FakeGlucoSenseApp).container`.

## Consequences

- No annotation processing. Fewer moving parts, and faster builds.
- Dependencies are explicit and easy to trace. Tests can build a container with fakes.
- We give up Hilt's scoping and automatic graph checks. The app is small enough that we don't
  need them.

## Alternatives considered

- **Hilt.** Mature and well-documented, but it adds generated code and setup for a graph this
  size.
- **Koin.** Runtime resolution means errors show up at runtime, not compile time.
- **Service locator without an owner.** Hidden globals make tests and lifecycle reasoning harder.

## References

- Android Developers, "Dependency injection in Android" (manual DI is the documented baseline).
