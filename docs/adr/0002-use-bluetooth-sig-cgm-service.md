# ADR-0002: Use the Bluetooth SIG CGM Service as the wire protocol

- **Status:** Accepted (the v2 completeness pass is [ADR-0014](0014-cgm-service-completeness.md); the ESP32 target is [ADR-0015](0015-esp32-firmware.md))
- **Date:** 2026-10-13

## Context

The brief asks for a fake sensor that can run on a second phone or an ESP32 and talk to the
companion app over **real BLE**. We could invent a custom GATT service with our own UUID and
byte layout, or implement the standard Continuous Glucose Monitoring Service (CGMS).

The CGMS v1.0.2 spec defines a measurement characteristic (`0x2AA7`) that carries one or more
records: Size, Flags, Glucose Concentration (SFLOAT, mg/dL), Time Offset (UINT16), and optional
Trend (SFLOAT, mg/dL/min). It also defines Feature (`0x2AA8`), Session Start Time (`0x2AAA`),
and the CCCD for notifications. Trend is present only when bit 0 of Flags is set (see
`docs/research/prior-art.md` §1).

## Decision

We will implement the **CGM Service (`0x181F`)** with these characteristics:

- Measurement `0x2AA7`: notify (required).
- Feature `0x2AA8`: read.
- Session Start Time `0x2AAA`: read, write.
- Client Characteristic Configuration descriptor `0x2902` on the measurement.

We will set only the Trend-present bit in Flags. We will not set the Sensor Status Annunciation
bits in v1, because the spec's flag table is not reliably extracted and we would rather not
guess. The complete layout is in `docs/ble-protocol.md`.

## Consequences

- Any CGM-aware BLE central can read the simulator. We get interoperability for free, and it's
  a stronger portfolio story than a proprietary profile.
- We must handle the variable-length, multi-record layout and the SFLOAT encoding correctly.
  Both are covered by core unit tests.
- Some features are deferred: Specific Ops Control Point, RACP backfill, E2E-CRC, and status bits.
  They're listed as future work.

## Alternatives considered

- **Custom 128-bit UUID and a bespoke JSON-over-GATT protocol.** Simpler to write, but it
  reinvents an existing standard and works with no existing tooling.
- **Glucose Service (`0x1808`).** Designed for blood glucose meters with discrete records and a
  Base Time, not for a continuously streaming sensor (see prior-art §2).
- **Nordic UART Service carrying NDJSON.** Easy on Android, but it isn't a CGM profile and gives
  no interop story.

## References

- Bluetooth SIG, *Continuous Glucose Monitoring Service* v1.0.2:
  https://www.bluetooth.com/wp-content/uploads/Files/Specification/HTML/CGMS_v1.0.2/out/en/index-en.html
- `docs/research/prior-art.md` §1–2
