# ADR-0007: Background work: foreground service for the live link, WorkManager for periodic checks

- **Status:** Accepted
- **Date:** 2026-10-13

## Context

The app has to keep a BLE or TCP link open and receive a reading every 5 minutes, even with
the screen off. Android restricts background work heavily:

- A foreground service must declare a `foregroundServiceType`. On targetSdk 34+, that's
  mandatory. A service that talks to a Bluetooth device uses `connectedDevice`, and it needs
  `FOREGROUND_SERVICE_CONNECTED_DEVICE` plus one of the qualifying permissions (see
  `docs/research/prior-art.md` §6).
- Notifications need a runtime permission on Android 13+, and without it foreground-service
  notifications are hidden.
- WorkManager periodic work has a **15-minute minimum interval**, and Doze can delay it
  (https://developer.android.com/reference/androidx/work/PeriodicWorkRequest). It's the wrong
  tool for a 5-minute live stream.

## Decision

1. **`GlucoseMonitorService`** is a foreground service with type `connectedDevice`. It owns the
   active `GlucoseSource` job, persists readings through the repository, dispatches alerts, and
   shows a persistent status notification (current value and trend). It's started only from
   foreground UI, which keeps us inside the background-start restrictions.
2. **`StaleSignalWorker`** runs every 15 minutes with WorkManager. If there's no reading for more
   than 15 minutes, it posts a "No sensor data" notification. It doesn't start or restart the
   service, so it can't hit the background-start limits.
3. **`RetentionWorker`** runs daily and deletes readings older than the retention window.
4. Both the TCP transport and the BLE transport declare `connectedDevice`, so the same service
   type covers both. For the TCP path, the manifest also declares `CHANGE_NETWORK_STATE`, which
   satisfies the FGS requirement. It's a normal permission and is granted at install.

## Consequences

- The live stream survives the screen being off, as long as the service runs.
- Users see a persistent notification. That's expected for a foreground service, and it doubles
  as a glanceable status.
- WorkManager gives "eventually" guarantees for housekeeping and gap detection, not real-time
  delivery. The docs say so.
- OEM battery killers can still stop the service. The README documents the workaround.

## Alternatives considered

- **WorkManager for the live stream.** Can't meet the 5-minute cadence (15-minute minimum), and
  it's the wrong semantics.
- **AlarmManager exact alarms.** Needs `SCHEDULE_EXACT_ALARM` and gives no persistent connection.
- **Keep-alive via a bound activity.** Dies when the process is killed or the UI goes away.
- **`dataSync` for TCP.** Deprecated for long-running work on newer Android versions.

## References

- Android Developers, foreground service types (Android 14 requirements), as summarized in
  `docs/research/prior-art.md` §6.
- PeriodicWorkRequest reference (15-minute minimum).
