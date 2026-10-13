# ADR-0013: Sensor app: a second Android phone as a BLE CGM peripheral

- **Status:** Accepted
- **Date:** 2026-10-13
- **Deciders:** Project maintainers

## Context

The bonus goal is to run the simulator over real Bluetooth Low Energy, not just TCP. The
companion app (`app`) is the BLE central. It needs a peer that speaks the Bluetooth SIG CGM Service
(ADR-0002). Two options were available: an ESP32 running NimBLE, or a second Android phone running
`sensor-app`. An ESP32 needs hardware that not every contributor has, and its firmware can't be
built in the Gradle CI. A phone can be built and tested in CI, and anyone with two phones can run it.

Android constraints shape the design. Advertising and the GATT server API exist only on the
peripheral side, so the phone must run both. A peripheral that keeps serving in the background
needs a foreground service with `connectedDevice` type (ADR-0007). Its Bluetooth permissions must be declared by API level
(`BLUETOOTH_ADVERTISE` and `BLUETOOTH_CONNECT` on API 31 and above).

## Decision

1. We will build `sensor-app` as an Android application that acts as a BLE **peripheral**. It
   hosts a `BluetoothGattServer` with the CGM Service (`0x181F`) and advertises it.
2. The sensor data comes from `core/sim` (`SimulatedCgmSession`, which wraps `SimulatedSensor`).
   The app is a thin shell: it runs the model, encodes records with the core codecs
   (`CgmMeasurementCodec.batch`, `SessionStartCodec`), and serves them over GATT.
3. A **Speed** control selects real time (1×) or fast demo (60×). Fast-demo timestamps run ahead of
   the phone's clock. The companion shows negative ages as "just now".
4. Sessions carry a 24-hour backfill. The session start is the latest 5-minute boundary minus 24
   hours (`alignedStart`), so the history lines up with the sensor's clock grid. A session ends when its time offset would exceed the 16-bit `Time Offset` field (65 535 minutes). The
   sensor does not restart on its own. The user starts a new session.
5. Outgoing notifications are queued in one outbox shared across subscribed centrals. One
   notification is in flight at a time, and the outbox is capped at 2 000 frames
   (`SensorGattServer.MAX_QUEUED_FRAMES`). When the cap is reached, the oldest frames are dropped.
6. The service is a `connectedDevice` foreground service with `START_NOT_STICKY`. Its notification
   has a Stop action. The Activity is a Compose screen with scenario chips, speed chips, and Start
   and Stop buttons.

## Consequences

- Positive: The full BLE path (advertise, connect, read Session Start Time, subscribe, backfill,
  live notifications) can be tested with two phones and no extra hardware. Both sides share
  the core codecs, so a codec bug can't hide behind two different implementations.
- Positive: `sensor-app` compiles in CI (`./gradlew :sensor-app:assembleDebug`) and its pure session
  logic is unit-tested.
- Negative: A phone is not a real CGM. It can't measure glucose, and it draws more power than
  an ESP32. The "Sensor Status Annunciation" bits are not set, because the spec table isn't
  available to us (see `docs/ble-protocol.md`).
- Neutral: The ESP32 firmware is future work (PLAN.md Phase 5). It uses the same GATT profile, so
  the companion app needs no changes for it.

## Alternatives considered

- **ESP32 firmware first.** Better fit for a real sensor, but it needs hardware the team may not
  have, and CI can't build it. Left as future work.
- **Run the peripheral inside the companion app.** Simpler install, but one phone can't be both a
  central and a peripheral on the same test path, so it would not test a real link.
- **Use the Glucose Service (GLS) instead of CGMS.** Rejected in ADR-0002; GLS models meters, not
  continuous sensors.
- **Build a generic BLE test harness (nRF Connect style).** Useful for debugging, but it doesn't
  prove the app works end to end.

## References

- ADR-0002 (CGM Service as the wire protocol) and ADR-0011 (transports).
- Bluetooth SIG, *Continuous Glucose Monitoring Service* v1.0.2. See `docs/research/prior-art.md` §1.
- Android BLE peripheral role: `BluetoothGattServer`, `BluetoothLeAdvertiser`.
- `docs/ble-protocol.md` for the byte layout.
