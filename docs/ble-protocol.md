# BLE protocol: FakeGlucoSense CGM wire format (local revision v2)

This document describes the bytes on the air. The design decision is in
[ADR-0002](adr/0002-use-bluetooth-sig-cgm-service.md) and the transport split is in
[ADR-0011](adr/0011-transports-tcp-ndjson-and-ble.md); [ADR-0014](adr/0014-cgm-service-completeness.md)
and [ADR-0015](adr/0015-esp32-firmware.md) record later decisions. The byte-level Kotlin codecs
live in `core/src/main/kotlin/com/fakeglucosense/core/ble/` and are used by the companion
(central) and `sensor-app` (peripheral). The ESP32 has a separate C++ encoder in
`esp32/src/cgm_codec.cpp`. Kotlin codec tests pin byte vectors; **no automated ESP32 host-vector
test is currently committed or run in CI**.

This is a **subset** of the Bluetooth SIG *Continuous Glucose Monitoring Service* (CGMS)
**v1.0.2**. “v2” refers to this project's implementation revision, not a SIG specification
version or a conformance claim. The implemented and missing parts are described below (§9).

## 1. GATT layout

| Element | UUID | Properties | Served by `sensor-app` | Served by `esp32/` | Used by the companion |
|---|---|---|---|---|---|
| CGM Service | `0x181F` | primary service | yes, advertised | yes, advertised | scan filter and discovery |
| CGM Measurement | `0x2AA7` | notify | yes, E2E-CRC on every value | yes, E2E-CRC | subscribed through its CCCD |
| CCCD | `0x2902` | read, write | on all notify/indicate characteristics | handled by NimBLE | written to enable |
| CGM Feature | `0x2AA8` | read | yes, static value | yes, static value | not read yet (interoperability gap; §9) |
| CGM Status | `0x2AA9` | read | yes, live snapshot | yes, computed on read | read once at setup; stopped status briefly reported |
| CGM Session Start Time | `0x2AAA` | read (phone), read/write (ESP32) | read only | read **and write** (time sync handler) | read once at setup |
| Record Access Control Point | `0x2A52` | write, indicate | yes | yes | backfill request on connect |
| CGM Specific Ops Control Point | `0x2AAC` | write, indicate | yes | yes | not used in v2 (session runs on the sensor) |
| CGM Session Run Time | `0x2AAB` | — | no | no | no |

The phone sensor serves Session Start Time as read-only. The ESP32 has no RTC: it accepts a
Session Start Time write to re-anchor its synthetic epoch, but the stock companion **does not
write it**, and this time-sync path has not been verified against a real BLE session. At the
ESP32's default epoch its records do not fall in the companion's current-day chart (§8).

## 2. CGM Measurement record (`0x2AA7`)

A notification carries one or more records back to back. Each record, in little-endian order:

```
offset  size  field
0       1     Size      total record length in bytes, including this octet
1       1     Flags     bit 0 = Trend present; bits 5/6/7 = Warning / Cal+Temp / Status octets
2       2     CGM Glucose Concentration (SFLOAT, mg/dL)
4       2     Time Offset (UINT16, minutes since the session start)
6       0-3   Sensor Status Annunciation: [Status] [Cal/Temp] [Warning], in that order,
              one octet each, present only when the matching Flags bit is set
..      2     CGM Trend (SFLOAT, mg/dL per minute), if Flags bit 0
..      2     E2E-CRC (UINT16), implied by Size: exactly two bytes more than the declared fields
```

Sizes: 6-byte minimum, 13-byte maximum (all three annunciation octets + trend + CRC).
Examples of records without a CRC, pinned by `CgmMeasurementCodecTest` (the C++ encoder is
not tested by CI):

- `06 00 78 00 05 00`: plain record, glucose 120 mg/dL, offset 5 min.
- `08 01 78 00 05 00 0A F0`: hand-built **decoder** vector with trend +1.0 mg/dL/min
  (`0xF00A`). The encoders choose the equally valid `01 00` (`0x0001`) for exactly 1.0.
- `07 20 3C 00 0A 00 01`: warning-only record, glucose 60 mg/dL, offset 10 min, Warning octet
  `0x01` (result lower than patient low level).
- `0B E1 5A 00 00 00 01 02 04 05 F0`: all annunciation octets set (status `01`, cal/temp `02`,
  warning `04`), trend `05 F0` (0.5 mg/dL/min, SFLOAT mantissa 5 with exponent −1).

### 2.1 Flags and unsupported records

The Quality bit (bit 1) and the reserved bits (2–4) are never set by the simulator. A record with
any of them set is skipped whole, using its Size octet, so the rest of the notification stays in
sync. A Size shorter than 6 bytes, or one that does not match what the flags declare (with or
without the two CRC bytes), makes the whole notification malformed; the companion drops it.

### 2.2 Sensor Status Annunciation bit assignments

Per the CGMS table, cross-checked against Nordic's CGMS service (`nrfconnect/sdk-nrf`,
`subsys/bluetooth/services/cgms`) and STM32's WBA CGM demo (see `docs/research/prior-art.md`).

**Warning-Octet** (Flags bit 5) — the only octet the simulator sets in measurements:

| Bit | Meaning |
|---|---|
| 0 | Sensor result lower than the patient low alert level (< 70 mg/dL) |
| 1 | Sensor result higher than the patient high alert level (> 180 mg/dL) |
| 2 | Sensor result lower than the hypo level (≤ 55 mg/dL) |
| 3 | Sensor result higher than the hyper level (≥ 250 mg/dL) |
| 4 | Sensor rate of decrease exceeded (↓↓ trend) |
| 5 | Sensor rate of increase exceeded (↑↑ trend) |
| 6 | Sensor result at/below the simulator's 40 mg/dL floor (implementation sets this at 40) |
| 7 | Sensor result at/above the simulator's 400 mg/dL ceiling (implementation sets this at 400) |

**Cal/Temp-Octet** (Flags bit 6): time sync required, calibration not allowed / recommended /
required, sensor temperature high / low, calibration pending. **Status-Octet** (Flags bit 7):
session stopped, battery low, sensor type incorrect, sensor malfunction, device specific alert,
general device fault. Both are zero on a healthy simulated session; CGM Status reports the
Status-Octet's session-stopped bit when a collector stopped the session.

The sensor-side thresholds are the companion's **defaults** (55/70/180/250 mg/dL). Editing
the companion's thresholds does not reconfigure either peripheral. The Kotlin phone sensor
sets device-range bits at its 40/400 mg/dL clamp; the ESP32 C++ `warningFor` currently sets
only bits 0–5. Its rapid-decrease warning also uses `rate <= −3` instead of Kotlin's
`DOUBLE_DOWN` band (`rate < −3`), so exact-boundary values can differ.

### 2.3 E2E-CRC

The CRC is CRC-16-CCITT: polynomial `0x1021`, initial value `0xFFFF`, no reflection, no final XOR
(standard check: `"123456789"` → `0x29B1`). It has no flag bit; presence is implied when a record
is two bytes longer than its declared fields, which is how Nordic's and STM32's collectors detect
it. The value covers the whole characteristic value **with every CRC field excluded** and is
stamped, identically, at the end of every record in the value. The companion recomputes it the
same way and drops the whole notification on a mismatch. `CgmCrc16` (Kotlin) and
`esp32/src/cgm_codec.cpp` implement the same algorithm; only the Kotlin path has an automated
test suite today.

### 2.4 Time offset and session length

Time Offset is UINT16 minutes; absolute time is
`sessionStartMillis + timeOffsetMinutes × 60 000`. **The phone sensor** stops a session if its
offset would exceed 65 535 minutes (~45.5 simulated days). **The ESP32 does not yet enforce
that expiry**: restart it before this limit to avoid wrapped offsets (Phase 5 in `PLAN.md`).

## 3. SFLOAT (IEEE-11073 16-bit)

Unchanged from v1 — see `SFloat.kt`. Infinities clamp to the 40/400 physiological range; NaN and
other specials make the record unreadable (skipped).

## 4. CGM Feature (`0x2AA8`)

Four octets: a 24-bit feature bitmap in little-endian order, then CGM Type (high nibble) and
CGM Sample Location (low nibble). The actual bytes are **`1E 90 00 95`**:

```
features 0x00901E = patient high/low alerts (bit 1) + hypo (2) + hyper (3) + rate of
                    increase/decrease (4) + E2E-CRC (12) + CGM trend information (15)
octet 3   0x95    = type 9 (interstitial fluid), sample location 5 (subcutaneous)
```

Calibration, bonding, multiple sessions, and quality are reported as unsupported and are not
implemented anywhere.

## 5. CGM Status (`0x2AA9`) and CGM Session Start Time (`0x2AAA`)

Status is seven bytes: Time Offset (UINT16), then the three annunciation octets in the order
Status, Cal/Temp, Warning, then the E2E-CRC over those five bytes. It is the only place the
simulator sets the session-stopped bit. The companion reads Status once during setup; a
stopped result sends a transient link message that can be overwritten when setup completes.
The periodic stale-data worker is a backstop if measurements subsequently stop, not a
continuous CGM Status monitor.

Session Start Time is unchanged from v1: the 9-byte UTC date-time. Decoding honours the zone and
DST fields per spec.

## 6. Record Access Control Point (`0x2A52`)

A central writes a request with response; records are sent as Measurement notifications and
the outcome as an **indication** on the RACP characteristic. The phone queues its indication
after the report frames; the ESP32 sends the indication immediately after queueing, so it
can arrive before some measurements (§8). Op codes and response values follow the Bluetooth
assigned-numbers table for the Record Access Control Point:

- **Report stored records** (`0x01`): operators All (`0x01`), First (`0x05`), Last (`0x06`),
  ≤ (`0x02`), ≥ (`0x03`), and Within range (`0x04`). Bounded operators carry the filter type
  `0x01` (Time Offset) plus one or two UINT16 bounds; range uses one filter byte then both bounds.
  Ends with a Response Code indication (`0x06 … success`) or `no records found` (`0x06`).
- **Report number of stored records** (`0x04`): same operators; answers with the Number of Stored
  Records Response (`0x05`), which is also used for a zero count.
- **Abort operation** (`0x03`, Null operator): drops queued report frames for the requester;
  `abort unsuccessful` (`0x07`) when no report is in flight.
- **Delete stored records** (`0x02`): answered `op code not supported` (`0x02`) — the simulated
  sensor's memory is append-only, as on Nordic's CGMS.

The companion issues *report stored records / all records* right after enabling indications, as
its backfill. Room de-duplicates by timestamp, so overlap with live data is ignored. The phone
sensor generates roughly 24 hours of history immediately; the ESP32 starts with **no history**
on boot and accumulates up to 300 records. An ESP32 report of a full buffer at the default 23-byte
ATT MTU can exceed its 128-frame queue and return *procedure not completed* after partial data;
the companion requests MTU 185, but the negotiated MTU is not guaranteed (§8).

## 7. CGM Specific Ops Control Point (`0x2AAC`)

Op codes per CGMS Table 3.17:

- **Set Communication Interval** (`0x01` + UINT8 minutes) / **Get** (`0x02`, answered by the
  dedicated `0x03` response + UINT8). The sensor accepts 1–255; out-of-range answers `0x05`.
  Live notifications are the stored 5-minute records whose Time Offset is divisible by
  `max(interval, 5)`; the history stays at 5-minute resolution. An interval not divisible by
  five can result in a slower effective live cadence.
- **Start the Session** (`0x1A`): re-anchors a fresh session at the current time (no history);
  idempotent success when already running.
- **Stop the Session** (`0x1B`): measurements stop and CGM Status reports the session-stopped
  bit until a new session starts; idempotent.
- Calibration and alert-level ops answer **op code not supported** (`0x02`): the fake sensor has
  no calibration and its thresholds live in the companion.

Responses are indications on the same characteristic (`0x1C` Response Code carries
request-op + response-value).

## 8. Sensor behavior and connection sequence (v2)

**Phone peripheral (`sensor-app`).** Advertises `0x181F` with the device name in the scan
response. No push-backfill on subscribe; history is pulled via RACP. For each subscriber,
`SensorGattServer` batches whole records within its negotiated ATT MTU (`onMtuChanged`) and
queues frames in a shared 2 000-frame outbox, one notification/indication in flight at a time.
When full, the oldest queued frames are dropped.

**ESP32 peripheral (`esp32/`).** Advertises `0x181F` as `FakeCGM`. Live readings are encoded
one per notification; RACP reports use an MTU-based batching estimate, with a *global* peer
MTU (`onMTUChange`), not per-central MTUs. The 128-frame outbox rejects new frames when full;
`drainOutbox` sends up to **four notifications per 30 ms** (not one in flight at a time).
Control-point indications are sent separately, immediately after queueing a report, rather
than waiting for all queued notifications to be delivered. Reports go to every subscribed
central, not only the requester. Full-history delivery is not guaranteed at the default MTU;
these differences need hardware validation (Phase 5).

**Central (companion app).** Setup sequence, one queued GATT op at a time as in v1:

1. Scan with a filter on `0x181F`; connect; 20 s timeout.
2. Discover services.
3. Request MTU 185 (a refusal is fine; payloads stay at the default).
4. Read Session Start Time.
5. Read CGM Status if served. A stopped status sends a transient link message during setup;
   the final CONNECTED status can replace it, and Status is not re-read while connected.
6. Enable Measurement notifications (CCCD `0x0001`).
7. If RACP is served: enable its indications (CCCD `0x0002`) and write
   *report stored records / all records*. Stored records arrive as Measurement notifications.
8. Mark CONNECTED after the RACP **write callback**, not after confirming the report finished.
   Live/stored records are mapped and persisted; E2E-CRC is verified **when present**.

A sensor without RACP (older `sensor-app` builds, foreign CGMs) is connected at step 7's
equivalent end (step 6); the repository's timestamp de-duplication makes any push-backfill and
RACP backfill coexist harmlessly.

**Clock caveat.** The phone sensor's session start is based on the phone clock; its 60× demo
mode can produce future timestamps. The ESP32 boots at a synthetic 2026-01-01 epoch and its
history starts empty. The companion does not write Session Start Time, so the current-time
chart excludes the ESP32's records, and the stale check may warn despite an active link.
The board accepts Session Start Time writes, but this end-to-end path is not yet verified.

Mixed-version note: the earlier v1 companion skipped records with Flags bits 5–7 set. An old
companion therefore ignores many v2 records. Upgrade both ends together; plain records are
otherwise compatible. The protocol still needs hardware interop testing.

## 9. Not implemented / known limitations

- Calibration ops, alert-level ops, and reset-device-specific-alert (`0x04–0x19`) answer
  “op code not supported”. CGM Session Run Time (`0x2AAB`), Quality fields (Flags bit 1),
  Multiple Bond / Multiple Sessions feature bits, and RACP deletion are absent/unsupported.
- The companion does **not** read CGM Feature before interpreting records (the CGMS spec calls
  for this), does not issue ops-control commands or time-sync writes, and does not continuously
  monitor CGM Status after the one setup read.
- ESP32 session expiry is not enforced before UINT16 Time Offset wraps; full-buffer RACP at
  default MTU may be partial; RACP reports are broadcast to all subscribers and its result
  indication may precede queued notifications. There is no automated C++ host-vector suite.
- Secure connections and bonding are not implemented. The link is unauthenticated and the data
  is synthetic. These limitations are not evidence of CGMS conformance.
