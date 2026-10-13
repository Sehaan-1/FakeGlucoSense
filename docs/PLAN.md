# FakeGlucoSense — Implementation Plan and Status

**Status at the repository state reviewed on 2026-10-13.** ✅ implemented in the tree · 🟡
partially usable / verification still needed · ⬜ not implemented. These markers describe
**code and documented gaps**, not proof of an emulator or physical-device test. The baseline
commit `e01278c` passed the [JVM, Android, and firmware build jobs](https://github.com/Areej-ui-sehaan/FakeGlucoSense-/actions/runs/38375610486);
that workflow did not run lint or a BLE end-to-end test. Later edits need their own CI run.

## 0. Goals and non-goals

**Goals**

- Simulated readings on a 5-minute *simulated* cadence, with configurable speed-up.
- A companion Android app that receives, stores, graphs, and alerts on those readings.
- A BLE path using a second Android phone or an ESP32 as a synthetic sensor with the Bluetooth
  SIG CGM Service (`0x181F`). The ESP32's clock/display gap is tracked in Phase 5.
- Demonstrate Flow, WorkManager, foreground services, notifications, Room, and Compose.

**Non-goals**

- Integration with real CGMs, clinical accuracy, or any treatment logic.
- Cloud sync, accounts, multi-user support, or iOS.
- Calibration and sensor alert-level control-point operations (currently answered “op code not
  supported”; see Future work).

## 1. Requirements (from the brief)

| # | Requirement | Current implementation / caveat |
|---|---|---|
| R1 | Synthetic samples every 5 minutes | `core/sim`, `simulator`, `sensor-app`, `esp32/`; speed multiplier changes wall-clock pacing |
| R2 | Companion receives/displays readings | `app/data/source`, `app/data/ble`, `app/ui/dashboard` |
| R3 | Live glucose graph | `app/ui/chart`; only readings near the phone's wall clock appear |
| R4 | Trend arrows | `core` (`model.Trend`, `analysis.TrendCalculator`) |
| R5 | High/low threshold alerts | `core` (`analysis.AlertEvaluator`), `app` (`notify.AppNotifications`) |
| R6 | 3 / 6 / 12 / 24-hour views | Dashboard range selector and downsampling |
| R7 | Kotlin, Compose, Coroutines/Flow, Room, WorkManager, FGS, notifications | `core`, `simulator`, `app`, `sensor-app`; firmware is C++ |
| R8 | BLE from a phone or ESP32 | Both peripherals serve CGMS; ESP32-to-companion *current-day chart* blocked by clock mismatch |
| R9 | Modular code, ≤1000 lines per source file | Review convention in AGENTS.md §3.1 (not a CI check) |

## 2. Phases

### Phase 0 — Planning and research ✅
- README, AGENTS.md, protocol, architecture, prior art, and accepted ADR-0001 through ADR-0015.
- Bluetooth SIG CGMS v1.0.2, Glucose Service, Nightscout direction names, Android background
  restrictions, toolchain. ADRs are historical decisions; use this plan for current status.

### Phase 1 — Core domain and codecs ✅
- `core/model`: `GlucoseReading`, `Trend`, `AlertThresholds`, `AlertType`, `TimeRange`.
- `core/ble`: SFLOAT, CGM Measurement, Session Start Time, UUIDs. Later additions are CRC,
  feature, status, annunciation, RACP and CGM ops codecs (Phase 4; ADR-0014).
- `core/analysis`: 15-minute `TrendCalculator`, `GlucoseStats`, `AlertEvaluator`, `Downsampler`.
- JVM tests cover codec round trips, CRC, trend boundaries, and alert hysteresis/snooze.
- **Evidence:** `:core:test` runs in the `jvm` CI job; see the baseline run above.

### Phase 2 — JVM simulator ✅
- `core/sim`: `GlucosePhysiology` and seeded `SimulatedSensor`, with `ScenarioPreset` profiles,
  `MealPlanner`, sensor lag, noise, and five scenarios.
- `simulator`: CLI (`--port`, `--bind`, `--scenario`, `--seed`, `--speed`,
  `--interval-minutes`, `--backfill-hours`, `--zone`); `ReadingHub` and `NdjsonTcpServer` fan out
  newline-delimited JSON and replay history to new clients.
- Tests cover determinism, pacing, JSON round trips, fan-out/backfill, and CLI parsing.
- **Evidence:** `:simulator:test` runs in the `jvm` CI job. A manual `nc` smoke test is a
  separate verification step, not a CI job.

### Phase 3 — Android companion ✅ (implementation; emulator smoke still needed)
- Room v1 table with timestamp primary key, `INSERT OR IGNORE`; DataStore holds source, device,
  thresholds, units, and monitoring intent.
- `GlucoseSource` → `TcpSimulatorSource` or `BleCgmSource`/`BleCgmClient` with scan,
  serialized GATT setup, RACP backfill when available, CRC verification, and retry/backoff.
- `GlucoseRepository` persists/deduplicates; `GlucoseMonitorService` owns the foreground live
  link and dispatches new-reading alerts. WorkManager runs the 15-minute stale check and daily
  30-day retention purge.
- Compose dashboard has current value, trend, range chips, chart, window summary, and the
  **Start/Stop monitoring button**. Settings has source selection, BLE picker, thresholds, units.
- **Evidence:** `:app:assembleDebug` and `:app:testDebugUnitTest` run in the `android` CI job.
  Emulator + TCP end-to-end use has not been established by that job. No ViewModel/repository
  fake-source tests yet.

### Phase 4 — BLE sensor targets 🟡 (code present; device/clock gaps)
- `sensor-app` runs the Kotlin model on another phone, advertises CGMS, prepopulates roughly
  24 hours of history, and runs as a `connectedDevice` foreground service (ADR-0013). It needs
  a phone that supports BLE peripheral advertising; 60× demo timestamps can run ahead of now.
- Local wire revision v2 (ADR-0014): status annunciation, CRC, Feature and Status reads, RACP
  report/count/abort, session/cadence ops, and negotiated-MTU batching **on the phone**. The
  companion pulls history over RACP and reads Status; it does **not** read Feature or call ops.
- `esp32/` has PlatformIO/NimBLE firmware with the same characteristic UUIDs, a C++ model and
  codec, serial controls, and a 300-record ring (ADR-0015). Its history starts empty on boot.
  It uses a fixed 2026-01-01 epoch, so the stock companion's current-time chart and stale check
  do not work correctly for its readings. It also lacks an automated C++ host-vector test,
  session-expiry enforcement, and per-central RACP targeting; at a small MTU a full report can
  exceed the 128-frame queue. See [`esp32/README.md`](../esp32/README.md) and
  [`ble-protocol.md`](ble-protocol.md).
- **Evidence:** CI builds both Android apps, runs their local unit tests, and **builds** firmware.
  This is not proof of a two-phone or ESP32 BLE session; no device test is recorded here.

### Phase 5 — Hardening and polish ⬜
- Make ESP32 readings line up with the companion's wall-clock chart and stale check (either
  reliable collector-initiated Session Start Time sync or another explicit time strategy).
  Test fresh boot, stored history, reconnection, and a new session on hardware.
- Enforce ESP32 16-bit Time Offset expiry; handle a full RACP history at default MTU and
  per-central delivery; add C++ host byte-vector/simulator tests to CI.
- Read CGM Feature before interpreting the profile and test mixed-version/malformed sensors.
- Add Android repository/ViewModel tests with fake sources and injectable clock/dispatchers;
  decide whether historical *new* backfill readings should trigger alerts. Add a denied-
  notification-permission state, accessibility labels on arrows/values, lint in CI, and
  manual emulator/two-phone/ESP32 test notes.
- Add screenshots or a demo script after verifying real UI flows.

### Future work (not scheduled)
- Calibration and alert-level ops, quality fields, Session Run Time (`0x2AAB`).
- Bonding/encrypted links (today's data is synthetic), Wear OS / Glance widget.

## 3. Verification matrix

| Check | Command / path | Automation today |
|---|---|---|
| Core tests | `./gradlew :core:test` | `jvm` CI job |
| Simulator tests | `./gradlew :simulator:test` | `jvm` CI job |
| TCP smoke | `./gradlew :simulator:run` then `nc localhost 8765` | Manual |
| Companion build + tests | `./gradlew :app:assembleDebug :app:testDebugUnitTest` | `android` CI job |
| Phone sensor build + tests | `./gradlew :sensor-app:assembleDebug :sensor-app:testDebugUnitTest` | `android` CI job |
| Firmware compilation | `pio run -d esp32` | `firmware` CI job (working directory `esp32/` there) |
| C++ host codec/sim vector tests | Not yet committed | **Not in CI** |
| Android lint | `./gradlew lint` (requires Android SDK) | **Not in CI** |
| Emulator + TCP / physical BLE | Launch apps and follow README quick start | **Manual, not yet evidenced** |

## 4. Risks and mitigations

| Risk | Impact | Current response / next step |
|---|---|---|
| OEM BLE-stack variability | Flaky connections | Serialized GATT ops and reconnect backoff exist; record tested devices in Phase 5 |
| Android background FGS start restrictions | Service fails to start | Start from foreground UI; WorkManager checks never launch FGS |
| Notifications denied on Android 13+ | No visible alerts | Permission requested; no in-app denied-state banner yet (Phase 5) |
| ESP32 synthetic epoch | Chart empty / false stale alert on companion | Document limitation; implement and test time sync in Phase 5 |
| ESP32 small MTU / long sessions | Partial RACP report / wrapped offset | Bound and test queue delivery and session expiry in Phase 5 |
| External spec bit/op tables | Interoperability errors | Cross-checked Nordic, STM32 and assigned numbers; codec tests; physical validation pending |
| Build toolchain drift | Broken builds | Pinned version catalog and Gradle wrapper; CI on exact revision |

## 5. Status upkeep

Update implementation markers only after inspecting the code, and distinguish a successful
build/unit-test job from an emulator or real-device test. Do not mark Phase 4 complete until
the ESP32 clock issue and the manual BLE acceptance path are resolved or explicitly descoped.
