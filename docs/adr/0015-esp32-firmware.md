# ADR-0015: ESP32 firmware as a third simulated-sensor target

- **Status:** Accepted
- **Date:** 2026-10-13
- **Builds on:** ADR-0002, ADR-0011, ADR-0013, ADR-0014

## Context

The brief names the ESP32 as one of the two ways to run the fake sensor (the other is a second
phone, covered by `sensor-app` in ADR-0013). Without firmware, the BLE path could only ever be
tested with two phones. The ESP32 is also the more realistic hardware shape: a headless,
always-powerable board that a collector finds while scanning.

## Decision

A PlatformIO Arduino project in `esp32/` targeting a plain ESP32 devkit, built on
**NimBLE-Arduino 1.4.x** (the de-facto BLE peripheral stack for Arduino-ESP32; the Classic stack
was rejected: only BLE is needed and NimBLE is smaller):

- A C++ port of the wire codec (`cgm_codec.cpp`), kept line-parallel to the Kotlin
  `core/ble/CgmMeasurementCodec`, `SessionStartCodec`, `CgmStatusCodec`, and `CgmCrc16`, and of
  the RACP/ops control-point semantics from `RacpFilter`/`CgmOpsCodec`. Same default feature
  value, same defaults, so the companion cannot tell which peripheral it talks to.
- A C++ port of the simulator physiology shape (`glucose_sim.cpp`): mean reversion, meal
  absorption/bolus pools, night/dawn drift, exercise, sensor lag/noise, the same five scenario
  presets. It deliberately does **not** reproduce the Kotlin RNG; scenario *shape* matches, not
  seed-for-seed values (documented in `esp32/README.md`).
- NimBLE service with all v2 characteristics; paced bounded outbox (4 frames per 30 ms) so a
  full-history RACP report cannot overflow the TX queue; history ring of 300 records.
- Serial CLI (scenario, speed multiplier, start/stop, interval, status).
- **Clock:** no RTC and no network (AGENTS.md §3.4 rules out NTP), so the sensor reports a
  synthetic epoch (2026-01-01 UTC plus sim uptime). Relative timing is exact. Session Start Time
  *writes* are accepted as collector time sync — the standard mechanism — unlike the phone
  sensor, which rejects them.
- A `firmware` CI job builds the project on every push; the codec and simulator are additionally
  host-compiled and verified against the same byte vectors as the Kotlin tests (CRC check value,
  record layouts, deterministic sim).

## Alternatives considered

- **nRF52 / Zephyr NCS** (`bt_cgms` exists there): changes hardware; Zephyr's CGMS service is a
  different profile revision subset (no full RACP bounding). NimBLE-Arduino on ESP32 matches the
  brief and reuses our exact wire semantics.
- Porting the Kotlin sim verbatim (same RNG): rejected as out of proportion; determinism across
  platforms was never promised.

## Consequences

- Real-BLE testing no longer needs two phones; an ESP32 devkit is the cheap always-on sensor.
- Wire-level parity must be preserved across three codebases; the shared test vectors in
  `core/.../ble` tests, the ESP32 host test, and `docs/ble-protocol.md` are the contract.
