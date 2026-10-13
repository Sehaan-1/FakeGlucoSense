# ADR-0011: Transports: TCP NDJSON for development, BLE for the real path

- **Status:** Accepted
- **Date:** 2026-10-13

## Context

Two transports matter. BLE is the headline feature, but it needs two physical phones and Bluetooth
permissions. Development, demos, and CI need something easier to run. An Android emulator can't
use Bluetooth, but it can reach the host machine at `10.0.2.2`.

## Decision

1. The **simulator** exposes a **TCP server** that sends **newline-delimited JSON (NDJSON)**. One
   object per reading, with keys `t` (epoch ms), `mgdl`, `direction` (the Trend name, for example
   `SingleUp`), and optional `rate` (mg/dL per minute) and `seq`. On connect, the server
   replays the last N hours of history so the chart fills at once.
2. The **BLE transport** speaks the CGM Service (ADR-0002) and is implemented in `app` (central)
   and `sensor-app` (peripheral).
3. Both transports implement the same `GlucoseSource` interface and emit the same domain type,
   `GlucoseReading`. The repository doesn't know which transport it's using.
4. The NDJSON codec is hand-written in `core/wire`. It's a flat object with four fields, so a
   JSON library would be a dependency for nothing.

## Consequences

- `nc localhost 8765` shows the data stream. That makes debugging and demos easy.
- The TCP path works in an emulator and in CI-style tests with no Bluetooth.
- The TCP wire format is ours, not a standard. It's documented, and it is not the interop path
  (BLE is).

## Alternatives considered

- **HTTP polling.** Works, but it adds latency and a request loop for no gain.
- **WebSocket.** Needs a server library. Plain sockets are enough.
- **kotlinx-serialization for NDJSON.** A runtime dependency and a plugin for four fields.
- **BLE only.** Can't be developed or demoed without two phones.

## References

- `docs/ble-protocol.md` for the BLE side.
- Android emulator networking: the host loopback is reachable at `10.0.2.2`.
