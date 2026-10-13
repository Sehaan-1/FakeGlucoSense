# ADR-0010: Alerts: hysteresis, snooze, and notification channels

- **Status:** Accepted
- **Date:** 2026-10-13

## Context

Naive threshold alerts flap. A reading that bounces between 69 and 71 mg/dL around a 70 mg/dL
threshold would fire every 5 minutes. Users also need different urgency levels: urgent low must
override a normal low, and rapid-change alerts are a separate category. Notifications on
Android 8+ belong to channels that users can tune, and Android 13+ needs `POST_NOTIFICATIONS`.

## Decision

1. **Alert types:** `URGENT_LOW` (< 55 by default), `LOW` (< 70), `HIGH` (> 180), `URGENT_HIGH`
   (> 250), `RAPID_DROP` (trend `DoubleDown`), and `RAPID_RISE` (trend `DoubleUp`). All
   thresholds are user-configurable within safe bounds.
2. **Hysteresis:** an alert clears only after the value moves back past the threshold by a
   margin (default 5 mg/dL, or 5 mg/dL/min for rate alerts).
3. **Snooze:** after an alert fires, the same type doesn't re-fire for 30 minutes unless the
   severity rises (for example, `LOW` escalates to `URGENT_LOW`).
4. **Evaluation is pure.** `core/analysis/AlertEvaluator` takes the previous state and a new
   reading, and returns decisions. The app maps those decisions to notifications. This makes the
   logic unit-testable.
5. **Channels:** `glucose.urgent` (high importance, alarm-like sound), `glucose.alerts` (high),
   `glucose.status` (low importance, used by the foreground service's ongoing notification),
   and `glucose.system` (stale-signal and service messages).

## Consequences

- Alerts are stable and predictable, which matters for a demo that might run for hours.
- Users can silence the non-urgent channels without losing urgent low alerts.
- Notifications are a convenience, not a safety system. The docs say so and the UI reinforces it.
- Do-Not-Disturb bypass needs special permissions. We don't request them.

## Alternatives considered

- **Stateless thresholds.** Flapping, and the user gets spammed.
- **AlarmManager-driven escalation timers.** Overkill: the evaluator runs on every reading.
- **One notification channel.** Users can't separate urgent from informational.

## References

- Android Developers, notification channels and the `POST_NOTIFICATIONS` permission.
- Trend bands: ADR-0005.
