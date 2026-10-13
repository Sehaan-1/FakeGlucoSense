# FakeGlucoSense ESP32 firmware

A synthetic CGM **BLE peripheral** for a classic ESP32 dev board, built with PlatformIO and
NimBLE-Arduino (ADR-0015). It advertises the Bluetooth SIG CGM Service (`0x181F`) as `FakeCGM`.
Its GATT UUIDs match the phone sensor, so the companion can **discover and connect** to it.
It does **not** yet provide an out-of-the-box current-day chart in the stock companion because
its clock is synthetic; read [Limitations](#limitations) before using it for a demo.

> **Not a medical device.** All data is synthetic. Never use this device, its readings, or its
> alerts for treatment decisions.

## Implemented in the firmware

- CGM Measurement notifications with optional trend/Warning octet and E2E-CRC; 5-minute
  **simulated-time** samples, with the same five scenario names as the Kotlin model. The C++
  physiology has its own seeded RNG, so values are not identical to the Kotlin output.
- CGM Feature and live CGM Status reads; Session Start Time read and a write handler intended
  for collector time sync (the stock companion only **reads** it).
- RACP report stored records (all, first, last, ≤, ≥, time-offset range), count, and abort;
  delete responds “op code not supported”. It stores up to **300 records**, starting with
  **empty history on every boot** rather than generating 24 hours instantly like `sensor-app`.
- CGM ops-control-point communication interval get/set and start/stop session. Its default
  live cadence is five simulated minutes; it retains full 5-minute history.
- A negotiated-MTU-based *estimate* for grouping RACP records, with a 128-frame outbox paced
  up to four notifications per 30 ms. Live measurements are **single-record** notifications.

The wire-format details and platform differences are in [`docs/ble-protocol.md`](../docs/ble-protocol.md).

## Build and flash

Install [PlatformIO](https://platformio.org/) (`pip install platformio`). The checked-in
`platformio.ini` targets `esp32dev`, Arduino, and NimBLE-Arduino 1.4.x. Use a classic ESP32
board with BLE, suitable for that board setting; other variants need their own configuration.
No glucose sensor or display wiring is required.

From the repository root:

```bash
pio run -d esp32                  # compile
pio run -d esp32 -t upload        # flash; add --upload-port if discovery fails
pio device monitor -b 115200      # serial console
```

From within `esp32/`, the equivalent commands are `pio run` and `pio run -t upload`.
The [firmware CI job](../.github/workflows/ci.yml) runs **`pio run`** on pushes/PRs. It does
not run C++ host codec tests or a hardware BLE test.

## Serial CLI (115200 baud)

```text
help                list commands
status              session, scenario, speed, interval, subscriber count, history size
scenario <1-5>      1 STEADY · 2 MEAL_DAY · 3 NOCTURNAL_LOW · 4 HIGH_SPIKE · 5 ACTIVE_DAY
speed <x>           simulated minutes per wall minute; clamped to 0.1–3600
start / stop        start or stop the CGM session (stop sets the Status bit)
interval <min>      live-record communication interval, 1–255 min (default 5)
```

The firmware starts the `MEAL_DAY` session and advertises on boot. `start` after a `stop`
starts a new session and clears history; changing scenario resets model/history. At 60× a
5-minute simulated interval elapses in about 5 wall seconds.

## Connect from the companion

1. Power the board and check the serial log for `advertising CGM service 0x181F`.
2. In the companion app, use **Settings → Data source → BLE sensor → Scan for sensors** and
   select `FakeCGM`. Grant Bluetooth permissions. On the **Glucose** tab tap **Start monitoring**.
3. The companion reads Session Start Time, subscribes to measurements, and requests stored
   records via RACP. On a fresh boot the report may be empty; wait for live records or accelerate
   the model with `speed 60` to create history. The app can store these records, but their
   synthetic dates mean they do not populate its current-time chart (next section).

## Limitations

- **Clock / dashboard (blocking).** The board has no RTC and makes no network calls. It maps
  its session to **2026-01-01 UTC plus simulated uptime**; the stock companion does **not**
  write Session Start Time. Its 3/6/12/24-hour chart filters by the phone's *current date* and
  its stale check compares the latest sample time to that date. Thus the BLE link can be up
  while the chart is empty and “no data” is reported. Do not treat this setup as a successful
  live-chart/alert demo. Use the JVM simulator or the second-phone sensor for that today.
  A Session Start Time write handler exists in firmware, but an end-to-end collector time-sync
  flow and the effect on stored record offsets have **not** been validated. See Phase 5 in
  [`docs/PLAN.md`](../docs/PLAN.md).
- **Backfill and multiple centrals.** RACP reports are broadcast to every subscriber, not
  routed to the requester. The 128-frame outbox **rejects new frames** when full; at default
  MTU a full 300-record report can be incomplete and return “procedure not completed”. MTU
  tracking is global (not per central); the result indication may arrive before all queued
  notifications. The companion requests MTU 185, but negotiation is not guaranteed.
- **Session lifetime.** The board does not yet stop before its 16-bit Time Offset exceeds
  65,535 simulated minutes (~45.5 days); restart the session before that limit. The phone
  sensor does enforce expiry. Firmware Warning bits also omit the Kotlin phone sensor's
  device-range floor/ceiling flags at 40/400 mg/dL.
- **Verification.** CI compiles firmware, but no automated C++ byte-vector/simulator tests or
  manual BLE hardware checks are recorded in this repository. Shared UUIDs alone do not prove
  full CGMS interoperability or byte-for-byte parity with Kotlin.
