# Architecture

FakeGlucoSense has a JVM simulator, an Android companion, an Android phone sensor, and ESP32
firmware. The first three consume the pure-Kotlin `core` module; the firmware is a **separate C++
port** of the model and BLE wire format, not a Gradle dependency. Accepted ADRs record the
original choices; this page describes the code as it stands. See [`PLAN.md`](PLAN.md) for
remaining validation and [`ble-protocol.md`](ble-protocol.md) for wire details.

## 1. Modules and dependencies

```text
core (pure Kotlin/JVM: model, analysis, sim, BLE codecs, NDJSON)
├── simulator (JVM TCP/NDJSON server)
├── sensor-app (Android BLE peripheral)
└── app (Android companion: TCP client + BLE central, Room, Compose, workers)

esp32/ (independent PlatformIO/C++ physiology + BLE codec, CGM peripheral)
```

| Module | Platform | Responsibility | ADRs |
|---|---|---|---|
| `core` | JVM only | Reading/threshold models, scenarios, trends, alerts, SFLOAT/CGM/CRC/RACP/ops codecs, NDJSON | 0002–0005, 0010, 0014 |
| `simulator` | JVM CLI | Drives the core model and broadcasts NDJSON; per-client replay buffer | 0004, 0011 |
| `app` | Android | TCP or BLE ingest, Room persistence, monitoring service, chart/settings, notifications, workers | 0006–0011, 0014 |
| `sensor-app` | Android | Core model served via CGM GATT peripheral, with seeded 24-hour session history | 0013, 0014 |
| `esp32/` | ESP32/NimBLE | C++ model/codec serving the same GATT UUIDs, accumulating history from boot | 0015 |

`core` imports no Android APIs; `simulator` does not depend on Android. `app` and `sensor-app`
do not depend on each other. Both BLE peripherals use the [CGMS v1.0.2 subset](ble-protocol.md)
implemented here, but their clock and queue behavior differ.

## 2. Reading and alert flow

```text
simulator ── NDJSON/TCP ──> TcpSimulatorSource ─┐
sensor-app / esp32 ── BLE ──> BleCgmSource ────┼─> GlucoseMonitorService
                                              │     ├─> GlucoseRepository ─> Room
                                              │     │                         └─> DashboardViewModel ─> Compose UI
                                              │     └─> AlertEngine ─> AppNotifications
                                              └─> link events ─> LiveStatus ─> dashboard
```

1. `GlucoseSource` streams `SourceEvent` link states and `GlucoseReading`s. TCP parses one JSON
   line per reading. The BLE client scans for `0x181F`, serializes GATT operations, requests
   MTU 185, reads Session Start Time and optionally Status, subscribes to Measurement, and asks
   for stored records if RACP is available. The shared codec validates E2E-CRC **when present**
   and drops bad values. The companion does not read CGM Feature or use the ops control point.
2. `GlucoseMonitorService` collects the selected source and owns reconnection (1 s exponential
   backoff capped at 30 s), link/notification updates, repository writes, and alert dispatch.
   Sources do not write the database directly.
3. `GlucoseRepository` inserts readings via Room (`epochMillis` primary key,
   `INSERT OR IGNORE`), derives a trend from prior readings when none was supplied, and returns
   only new rows. Initial backfill and reconnect replays are deduplicated by timestamp. Alerts
   run on **newly inserted** readings, including historical backfill on first connection;
   backfilled samples are not excluded just because they are old.
4. The dashboard ViewModel combines Room flows, DataStore settings, and in-process `LiveStatus`
   into UI state. It downsamples chart points, computes window stats, and displays the latest
   stored value; the UI does not touch BLE, sockets, or Room directly.

Readings are stored in mg/dL and UTC epoch milliseconds; mmol/L conversion is display-only.
The TCP simulator and phone sensor use wall-clock-aligned session timestamps in their normal
1× modes. The ESP32 uses a fixed 2026-01-01 epoch. The dashboard queries windows relative to
**the companion phone's current time**, and the stale worker compares the latest sample time
with that clock. Consequently, an ESP32 can connect and populate Room while showing no points
on today's chart and a false stale warning. The stock app does not issue Session Start Time
writes; see [the firmware guide](../esp32/README.md#limitations) and Phase 5 of the plan.
Fast-forwarded phone sessions can conversely produce future timestamps.

## 3. Lifecycle and threading

| Work | Owner / execution | Notes |
|---|---|---|
| Live TCP/BLE source | `GlucoseMonitorService` `lifecycleScope` | `connectedDevice` foreground service, `START_STICKY` |
| TCP blocking socket | `TcpSimulatorSource` on `Dispatchers.IO` | Closing socket on cancellation unblocks reads |
| BLE operations/callbacks | `BleCgmSession` callback thread, queued operations bridged to Flow | One Android GATT operation at a time |
| Stale-data backstop | `StaleSignalWorker`, 15-minute periodic WorkManager | Runs only if monitoring is enabled; Doze may delay it |
| Room retention | `RetentionWorker`, daily periodic WorkManager | Deletes rows older than 30 days |
| UI state | ViewModel `viewModelScope`, `StateFlow` | Lifecycle-aware collection in Compose |
| JVM simulation | `SimulationRunner` + `ReadingHub`, TCP server on `Dispatchers.IO` | Bounded per-client queue drops a slow client |
| Phone sensor simulation | `SensorService` `lifecycleScope` on `Dispatchers.Default` | 1-second wall-clock tick; GATT server owns outbox |
| ESP32 simulation | Arduino `loop()` and NimBLE callbacks | No dependency on Android or Kotlin runtime |

Scopes follow service/ViewModel lifecycles; there is no `GlobalScope`. Some dispatchers and
system clocks are still selected directly at platform edges rather than injected everywhere;
see Phase 5 for additional fake-based tests.

### Monitoring state

- Monitoring defaults **off**. The **Glucose** tab starts/stops the service; the Settings tab
  selects a source, address/host, units and thresholds but has no monitoring toggle.
- Start persists `monitoringEnabled = true`; explicit Stop persists `false` before shutting
  down. A sticky service restart with no action respects the flag, and the UI may resume it
  on relaunch if permission is available.
- Threshold edits reach the running alert engine without reconnecting. Source, host or BLE
  device edits restart the link if monitoring is running. BLE with no device selected shows
  an idle link state.
- The service uses the `connectedDevice` type even for TCP; on Android 12+ its start path asks
  for `BLUETOOTH_CONNECT`. Android 13+ asks for `POST_NOTIFICATIONS`; denial hides alerts and
  the service notification rather than automatically stopping the service.

## 4. Persistence and alerts

- Room database `glucose.db`: v1, single `glucose_readings` table, primary key `epochMillis`.
  Schema export is currently off. Add migration and exported schemas before changing it.
- DataStore Preferences: source kind, TCP host/port, BLE address/name, mg/dL or mmol/L display,
  monitoring intent, and thresholds (defaults 55 / 70 / 180 / 250 mg/dL).
- `AlertEvaluator` handles urgent low/low/high/urgent high plus rapid drop/rise, with 5 mg/dL
  hysteresis for glucose thresholds and 30-minute snooze reminders. An urgent alert suppresses
  its corresponding non-urgent alert. **Four channels** exist: urgent alerts, other alerts,
  monitoring status, and stale data — not one channel per alert type. The phone sensor and
  ESP32 use **default** thresholds for BLE Warning bits; user-edited companion thresholds do
  not change sensor-side flags.
- The stale worker notifies when monitoring is on and a stored latest reading is at least
  15 minutes behind the phone clock. It runs periodically, not at the instant a sample becomes
  stale. A fresh database with no readings does not trigger it.
- No cloud sync or analytics. The JVM TCP server is plain/unencrypted and defaults to a
  LAN-reachable bind address; use only on a trusted network.

## 5. Build and verification

Gradle Kotlin DSL + `gradle/libs.versions.toml`: Kotlin **2.3.21**, KSP **2.3.12**, AGP
**9.1.1**, Gradle wrapper **9.3.1**; Android minSdk 26 and compile/targetSdk 36.
`core`/`simulator` are JVM-only. The [CI workflow](../.github/workflows/ci.yml) runs JVM tests,
Android debug builds/unit tests, and a PlatformIO firmware build. It does not run lint, host
C++ vector tests, an emulator or physical BLE tests; compile success is not evidence of a
working on-device link or current-day ESP32 chart.
