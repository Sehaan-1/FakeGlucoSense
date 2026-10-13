# Prior art and frameworks reviewed

This note records what we looked at before designing FakeGlucoSense, and what we took from
each source. Decisions that depend on these sources are recorded in `docs/adr/`.

## 1. Bluetooth SIG — Continuous Glucose Monitoring Service (CGMS) v1.0.2

**Source:** Bluetooth SIG, *Continuous Glucose Monitoring Service* v1.0.2
(https://www.bluetooth.com/wp-content/uploads/Files/Specification/HTML/CGMS_v1.0.2/out/en/index-en.html)

What we took:

- The service is a standard GATT service. Its measurement characteristic is a
  **variable-length structure of one or more records**, each with a Size, a Flags field, a
  Glucose Concentration (SFLOAT, **mg/dL only**), and a Time Offset (UINT16, minutes from
  session start, also used as a sequence number for gap detection).
- An optional **CGM Trend Information** field (SFLOAT, mg/dL/min) is present only when **bit 0
  of Flags** is set. The spec states that the Trend field is present when that bit is 1.
- Byte order is little-endian (LSO first). The spec's transmission-order section says so.
- The spec says the Time Offset "shall be incremented by the measurement interval" and that the
  client must read the CGM Feature characteristic before it interprets the measurement.
- Clients may convert to mmol/L using `mg/dL = 18.02 × mmol/L`. We keep mg/dL internally.

What we took in v2 (ADR-0014): the remaining profile pieces, with the bit and op tables
cross-checked against two independent implementations because the spec's own tables did not
survive text extraction — these are the sources of truth for the tables in
`docs/ble-protocol.md` §2.2, §6, and §7:

- **Nordic's CGMS services** — `nrfconnect/sdk-nrf` `subsys/bluetooth/services/cgms` (and the
  older `sdk-nrf-bm` `ble_cgms` headers): op-code table for the CGM Specific Ops Control Point
  (`0x01`–`0x1C`), feature-bit positions, the measurement field order (status octets after the
  time offset, warning octet last) and their conditional inclusion (octets are sent only when
  non-zero), CGM Status layout, and the append-only database behavior (RACP delete answered
  "op code not supported").
- **STM32's WBA CGM demo** — `stm32-hotspot/STM32WBA-Web-Bluetooth-App-Interfaces`
  (`src/onglets/CGM.jsx`): the Sensor Status Annunciation bit meanings per octet and the
  presence-by-size E2E-CRC detection.
- **Bluetooth assigned numbers** (`org.bluetooth.characteristic.record_access_control_point`):
  the RACP op, operator, and response-code tables; the CGM filter type is Time Offset (`0x01`).

## 2. Bluetooth SIG — Glucose Service (GLS) v1.0.1

**Source:** https://www.bluetooth.com/wp-content/uploads/Files/Specification/HTML/GLS_v1.0.1/out/en/index-en.html

Used as a contrast. GLS is for *blood glucose meters* (discrete readings with a Base Time and
a Sequence Number), not continuous sensors. We chose CGMS because it models a sensor that
streams readings on a fixed cadence, which matches the brief.

## 3. Nightscout — the open-source CGM data platform

Nightscout is the most widely used open-source CGM data platform. Its API exposes glucose
entries with a `direction` string. Its values include `DoubleUp`, `SingleUp`, `FortyFiveUp`,
`Flat`, `FortyFiveDown`, `SingleDown`, `DoubleDown`, `NONE`, `NOT COMPUTABLE`, and
`RATE OUT OF RANGE`.

Sources:

- The Go controllers package lists the direction constants and the `sgv` entry shape
  (https://pkg.go.dev/github.com/adamlounds/nightscout-go/controllers).
- The Android uploader's `TrendArrow` enum maps Dexcom trend names to arrow symbols
  (https://github.com/nightscout/android-uploader, `TrendArrow.java`), as quoted in a
  community discussion.

What we took:

- The **direction vocabulary** (we use the same names, so exports stay interoperable).
- The **arrow symbols** from the Nightscout uploaders' mapping: `⇈ ↑ ↗ → ↘ ↓ ⇊`. The UI uses
  `↑↑` and `↓↓` as text fallbacks because `⇈` and `⇊` are missing from many Android fonts.

What we did not take: Nightscout's server, plugin system, or treatments model. Out of scope.

## 4. Dexcom-style trend bands (rate of change)

A community monitor project reports the Dexcom bands for each arrow. They are based on the
rate of change in mg/dL/min:

| Arrow | Rate |
|-------|------|
| DoubleUp | > 3 |
| SingleUp | 2 – 3 |
| FortyFiveUp | 1 – 2 |
| Flat | < 1 in either direction |
| FortyFiveDown | −1 – −2 |
| SingleDown | −2 – −3 |
| DoubleDown | < −3 |

Source: https://github.com/erikpendragon/nightscout-tdisplay (README, "Trend arrows" table).

We adopt these bands and compute the rate with a least-squares slope over the last 15 minutes.

## 5. Open-source Android CGM apps (landscape)

Community Android apps such as **xDrip+** and **AAPS** (AndroidAPS) connect to real CGMs over
BLE, persist readings, alert on thresholds, and run a foreground service for the sensor link.
They confirm the architecture we chose (BLE link in a foreground service, a local database as
the source of truth, and alerts from a pure logic layer), and they show the failure modes
(reconnect storms, OEM battery killers) that the plan's risk table covers. We did not copy any
code. FakeGlucoSense is an independent, simulator-first design.

## 6. Android platform constraints we rely on

- **Foreground service types are mandatory on targetSdk 34+.** A service that talks to a
  Bluetooth device uses type `connectedDevice`, and it needs `FOREGROUND_SERVICE_CONNECTED_DEVICE`
  plus one of the Bluetooth runtime permissions or another qualifying permission
  (such as the normal `CHANGE_NETWORK_STATE`) before it starts. Sources:
  https://dev.simplu.info/posts/mastering-foreground-services-with-android-14-a-new-frontier-for-developers/
  and https://medium.com/@domen.lanisnik/guide-to-foreground-services-on-android-9d0127dc8f9a
- **Notifications need a runtime permission on Android 13+** (`POST_NOTIFICATIONS`). Without it,
  foreground-service notifications don't show.
  Source: https://developer.zebra.com/blog/persisting-fgs-notifications-newer-android-versions
- **WorkManager periodic work has a 15-minute minimum interval**, and the OS may delay it
  (Doze). That's why it's used for health checks, not for the 5-minute live stream.
  Source: https://developer.android.com/reference/androidx/work/PeriodicWorkRequest
- **Toolchain (researched 2026-10; pins are in the repository):** Android Gradle Plugin 9.1.1
  requires Gradle 9.3.1 and JDK 17, and lists Android API 37 as supported
  (https://developer.android.com/build/releases/agp-9-1-0-release-notes). Upstream Kotlin
  plugin 2.4.20 was evaluated (https://plugins.gradle.org/plugin/org.jetbrains.kotlin.android),
  but this project pins **Kotlin 2.3.21** with **KSP 2.3.12** for Room. The wrapper pins Gradle
  9.3.1; both Android modules use **compileSdk/targetSdk 36**, not 37. For current pins use
  `gradle/libs.versions.toml`, `gradle/wrapper/gradle-wrapper.properties`, and the module build
  files, rather than treating upstream releases as the project's dependencies. The Compose
  Compiler Gradle plugin ships with Kotlin 2.x and is applied as
  `org.jetbrains.kotlin.plugin.compose`
  (https://developer.android.com/develop/ui/compose/setup-compose-dependencies-and-compiler).
  Compose Material 3 1.4.0 is the reviewed release
  (https://developer.android.com/jetpack/androidx/releases/compose-material3).

## 7. Things we decided not to adopt

| Option | Why not |
|--------|---------|
| Hilt | Adds annotation processing and a lot of boilerplate for a small app; manual DI is clearer (ADR-0008) |
| Vico / MPAndroidChart | Extra dependency for one chart; Canvas gives full control over the glucose bands (ADR-0009) |
| Protobuf / kotlinx-serialization on the TCP wire | NDJSON with a hand-rolled codec is readable with `nc` and needs no extra runtime (ADR-0011) |
| Glucose Service (GLS) | Built for meters, not streaming sensors (see §2) |
| Custom 128-bit service UUID | Standard CGMS UUID means any CGM-aware central can read the simulator (ADR-0002) |
