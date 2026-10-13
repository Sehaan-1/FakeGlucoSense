# ADR-0014: Complete the CGM Service: E2E-CRC, status annunciation, RACP, ops CP, MTU

- **Status:** Accepted
- **Date:** 2026-10-13
- **Builds on:** ADR-0002 and ADR-0011

## Context

The v1 wire (ADR-0002) implemented the minimum to stream: Measurement + Session Start Time. The
spec-mandated pieces that were left out are exactly what interoperability tooling and real
collectors exercise: the Sensor Status Annunciation bits, the E2E-CRC, the Record Access Control
Point for stored-record retrieval, the CGM Specific Ops Control Point, the CGM Feature and CGM
Status reads, and MTU negotiation. Making the fake sensor behave like a real one is what makes it
useful for testing against third-party tooling (nRF Connect, PTS-style conformance walks).

Extracting the bit and op tables: the CGMS HTML tables did not survive text extraction well
(`docs/research/prior-art.md` §1). Instead of guessing, we cross-checked two independent,
spec-conformant implementations — **Nordic's CGMS service** (`nrfconnect/sdk-nrf`,
`subsys/bluetooth/services/cgms`, plus `sdk-nrf-bm`) and **STM32's WBA CGM demo** — and the
**assigned-numbers** (`org.bluetooth.characteristic.record_access_control_point`) for the RACP
tables. All agreed; the tables are recorded in `docs/ble-protocol.md` §2.2/§6/§7.

## Decision

Implement the complete v2 profile in shared core codecs and both peripherals:

- **Sensor Status Annunciation.** Three independent octets (Warning / Cal+Temp / Status) flagged
  by Flags bits 5/6/7, in the spec's wire order status, cal/temp, warning; zero octets are omitted
  (as Nordic does). Only Warning is set in measurements, derived from the companion's default
  alert thresholds and trend arrows, so annunciation and app alerts agree.
- **E2E-CRC.** CRC-16-CCITT (0x1021/0xFFFF) over the whole characteristic value with the CRC
  fields excluded, stamped at the end of every record; presence implied by record Size (two extra
  bytes), matching how STM32's collector detects it. The companion verifies and drops failed
  frames. Values are self-contained per notification frame so any frame verifies independently.
- **RACP backfill.** Replaces v1's push-backfill: the companion requests
  *report stored records / all* after enabling indications; delete is unsupported (append-only
  memory, as on Nordic); abort drops queued report frames.
- **Ops CP.** Communication interval get/set (live stream keeps offsets divisible by
  max(interval, 5)); Start/Stop Session (stop sets session-stopped in CGM Status and halts
  measurements; start re-anchors a fresh session). Calibration and alert-level ops answer
  "op code not supported".
- **CGM Feature + CGM Status reads.** Feature advertises exactly what is implemented.
- **MTU negotiation.** Companion requests 185; peripherals batch per subscriber using
  `onMtuChanged`/`onMTUChange` instead of a fixed 20-byte payload.

Compatibility: a sensor without RACP follows the v1 path and connects right after the CCCD write.
A v1 *companion* skips v2 records (bits 5–7 were reserved to it): upgrade both ends together.
Version skew is documented in `docs/ble-protocol.md` §8.

## Consequences

- The byte codec now encodes per-record flags from content and verifies CRCs centrally; both are
  pinned by tests with hand-built spec-shaped vectors, and the C++ port is checked against the
  same vectors by a host test.
- All three implementers (Kotlin central, Kotlin peripheral, C++ peripheral) share one protocol
  document; any wire change must update `docs/ble-protocol.md` and all three.
- RACP keeps transfers one frame in flight per central through the existing outbox; a 25-hour
  report still cannot stall live data.
