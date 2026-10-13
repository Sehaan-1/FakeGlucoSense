# ADR-0006: Android architecture: unidirectional data flow, Room as the single source of truth

- **Status:** Accepted
- **Date:** 2026-10-13

## Context

Readings arrive from a source that can disconnect at any time (BLE or TCP) and must stay visible
when the UI is closed. The UI must update live. The data has to survive process death. The
Android guidance on app architecture recommends a UI layer, a data layer with a repository, and
a local database as the source of truth.

## Decision

We will use this layering:

```
Compose UI  ──state──►  ViewModel (StateFlow)  ──calls──►  GlucoseRepository
                                                             │ observes Room (Flow)
                                                             │ ingests from GlucoseSource
Sources (TCP / BLE) ──Flow<SourceEvent>──► GlucoseRepository ──► Room (single source of truth)
```

- **Room** holds every reading. The UI and the alert logic observe Room's `Flow`s. Nothing reads
  a source directly.
- The **repository** dedupes by timestamp (`INSERT OR IGNORE` on the primary key). It exposes
  `observeWindow(hours)` and `observeLatest()`.
- **ViewModels** expose immutable `StateFlow<UiState>` and send events back up. The UI is a
  function of that state.
- **Settings** (thresholds, source, device address) live in **DataStore Preferences** and are
  exposed as `Flow`s.

## Consequences

- Restarting the app or the service shows history immediately, from the database.
- The dashboard works the same whether readings came from TCP, BLE, or a backfill.
- Writing to Room is the only path that triggers alerts, so alert logic is deterministic.
- Room needs a schema version and migrations as the schema evolves. v1 is the only version.

## Alternatives considered

- **Push readings straight into the ViewModel.** Loses data on process death and duplicates
  state.
- **LiveData.** Flow is the modern default, and it works well with coroutines.
- **MVI framework libraries.** Extra concepts for a small app. A plain StateFlow reducer is enough.

## References

- Android Developers, "Guide to app architecture" (layering and single source of truth).
- `docs/architecture.md` for the threading model.
