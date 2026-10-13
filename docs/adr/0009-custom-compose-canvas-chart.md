# ADR-0009: Charts: custom Compose `Canvas`, no chart library

- **Status:** Accepted
- **Date:** 2026-10-13

## Context

The dashboard needs a glucose line chart with shaded target and threshold bands, a time axis
for 3/6/12/24-hour windows, and a current-value marker. The data comes as up to 288 points per
24 hours after downsampling.

Chart libraries (for example MPAndroidChart or Vico) would add a dependency and their own
styling and interaction model. We need precise control over the bands and labels.

## Decision

We will draw the chart with Compose **`Canvas`**, in a `ui/chart/` package:

- `GlucoseChartModel` (pure, in core-independent UI code) maps readings to coordinates. It's
  unit-testable.
- `GlucoseChart` is a stateless composable. It draws the target band, threshold lines, the
  polyline, point markers for urgent values, and time labels.
- Readings are downsampled to a pixel budget in core (`analysis/Downsampler`) before drawing.

## Consequences

- No extra dependency. The chart matches the Material 3 theme.
- We own the drawing code and its accessibility. We add `contentDescription` with a text summary.
- Interactive zoom and pan are out of scope for v1.

## Alternatives considered

- **MPAndroidChart.** View-based, so it's awkward inside Compose.
- **Vico.** Good Compose support, but a dependency for one chart, and it wouldn't give us the
  band rendering as cheaply.

## References

- Compose `Canvas` API reference.
