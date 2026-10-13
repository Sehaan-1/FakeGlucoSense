# ADR-0004: Simulator model: parametric physiology, seeded, with scenario presets

- **Status:** Accepted
- **Date:** 2026-10-13

## Context

The simulator has to produce readings that look plausible: slow drift, meal spikes,
insulin-driven drops, nocturnal lows, and sensor noise and lag. Options were (a) replaying a
recorded dataset, (b) a fully random walk, and (c) a simple parametric physiological model.

Recorded real-patient datasets carry privacy and licensing concerns. A random walk doesn't
look right: it never shows meals or corrections. The tests also need determinism, so a fixed
seed must reproduce the same series.

## Decision

We will use a **parametric model** with a seeded `kotlin.random.Random`:

- **Mean reversion** toward a baseline. The baseline drifts slowly (dawn phenomenon in the
  morning).
- **Meal impulses**: each meal adds a carbohydrate "pool" that decays exponentially and raises
  glucose while it's being absorbed.
- **Insulin impulses**: each correction bolus adds an insulin pool that lowers glucose as it
  acts.
- **Exercise**: an optional scenario-level term that increases glucose uptake.
- **Sensor lag**: the reported value is a lagged, smoothed version of true glucose, and
  Gaussian noise is added on top.
- **Clamping** to the physiological range 40–400 mg/dL.

Scenario presets (`STEADY`, `MEAL_DAY`, `NOCTURNAL_LOW`, `HIGH_SPIKE`, `ACTIVE_DAY`) set the
parameters. The output is always mg/dL, sampled at a configurable interval (default 5 min).

The model lives in `core/sim` and is a pure function of its state, time, and RNG, so the
simulator and the sensor app run the same code.

## Consequences

- Scenarios let the demo show every alert and every trend arrow on purpose.
- Determinism makes the simulator's output testable and reproducible for bug reports.
- The model is a teaching approximation, not a validated physiological model. The README and
  docs say so.

## Alternatives considered

- **Replay a public dataset.** Licensing and privacy concerns. Also not controllable for demos.
- **Random walk.** Looks wrong. Doesn't exercise alerts or meals.
- **A full ODE physiology model (e.g., UVA/Padova).** Accurate but a large dependency surface.
  Overkill for the brief, and it's out of scope for a demo.

## References

- `docs/research/prior-art.md` (landscape). The model is ours. No code was copied from elsewhere.
