# ADR-0005: Trend arrows: Nightscout/Dexcom-compatible rate bands

- **Status:** Accepted
- **Date:** 2026-10-13

## Context

The brief asks for trend arrows (`↑↑`, `↗`, `→`, …). A CGM can report a trend, or the app can
compute one from recent readings. Nightscout exposes a fixed `direction` vocabulary
(`DoubleUp`, `SingleUp`, `FortyFiveUp`, `Flat`, `FortyFiveDown`, `SingleDown`, `DoubleDown`,
`NONE`, `NOT COMPUTABLE`, `RATE OUT OF RANGE`). Users familiar with CGM apps expect the Dexcom
rate bands.

## Decision

1. `Trend` is an enum with the Nightscout names, plus `NOT_COMPUTABLE` (fewer than two readings
   in the window) and `NONE`.
2. The rate is a **least-squares slope in mg/dL/min** over the readings from the last 15
   minutes. We require at least two points.
3. Classification uses the bands from prior-art §4:
   - `> 3` → DoubleUp
   - `(2, 3]` → SingleUp
   - `[1, 2]` → FortyFiveUp
   - `(−1, 1)` → Flat (strictly between −1 and 1)
   - `[−2, −1]` → FortyFiveDown
   - `[−3, −2)` → SingleDown
   - `< −3` → DoubleDown

   The intervals are exactly those in `Trend.fromRate`. The shared endpoints are assigned as shown.
4. The BLE Trend field carries the same rate in mg/dL/min. The app prefers the computed rate
   when the sensor doesn't send one.

## Consequences

- The simulator and the app compute the same arrow from the same readings (same code in core).
- The rate uses regression, not a two-point difference, so one noisy reading doesn't flip the
  arrow.
- The band boundaries are a convention, not a regulatory standard. The UI doesn't claim
  otherwise.

## Alternatives considered

- **Two-point difference.** Too sensitive to sensor noise.
- **Sensor-supplied trend only.** The simulator has to supply it, and the app would then trust
  input it can't check. We keep the computed value as a fallback.
- **Use the Nightscout strings verbatim in the UI.** Hard to read. We map them to arrows and
  short labels.

## References

- `docs/research/prior-art.md` §3–4.
- Nightscout direction constants: https://pkg.go.dev/github.com/adamlounds/nightscout-go/controllers
