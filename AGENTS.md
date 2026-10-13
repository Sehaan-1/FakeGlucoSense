# AGENTS.md — FakeGlucoSense

> Instructions for humans **and** coding agents working in this repository.
> Read this file fully before changing code. Architectural decisions live in
> `docs/adr/`; the implementation plan lives in `docs/PLAN.md`.

---

## 1. What this project is

FakeGlucoSense is a **portfolio-grade, fully simulated** continuous glucose monitor (CGM)
system:

1. **A CGM simulator** that emits a glucose reading every 5 simulated minutes (configurable
   speed-up): a JVM TCP/NDJSON server for laptops/emulators, an Android BLE peripheral on a
   second phone, or ESP32 firmware speaking the Bluetooth SIG *Continuous Glucose Monitoring
   Service* (`0x181F`). The ESP32 has a separate C++ port of the Kotlin logic.
2. **A companion Android app** that receives readings, persists them, shows a live glucose
   graph (3 / 6 / 12 / 24 h), trend arrows (`↑↑ ↑ ↗ → ↘ ↓ ↓↓`), high/low alerts, and runs as a
   foreground service with WorkManager-based periodic checks. See the README and
   `esp32/README.md` for current ESP32 clock/display limitations.

> **Safety:** this is an educational simulator. It is **not a medical device**. Its data is
> synthetic. Never use it, or anything derived from it, for treatment decisions. Keep this
> disclaimer in the README and in any UI string that could be mistaken for real data.

---

## 2. Repository map

```
FakeGlucoSense-/
├── AGENTS.md                 ← you are here
├── README.md                 ← user-facing overview + quick start
├── docs/
│   ├── PLAN.md               ← phased implementation plan + verification matrix
│   ├── architecture.md       ← module graph, data flow, threading model
│   ├── ble-protocol.md       ← GATT service/characteristic layout (wire format)
│   ├── research/prior-art.md ← frameworks & specs we researched and why
│   └── adr/                  ← Architecture Decision Records (0001…)
├── core/                     ← PURE Kotlin/JVM library (no Android, no I/O frameworks)
├── simulator/                ← JVM CLI: TCP/NDJSON server driving the core simulator
├── app/                      ← Android companion app (Compose, Room, WorkManager, FGS, BLE central)
├── sensor-app/               ← Android BLE peripheral: "second phone" CGM sensor
├── esp32/                    ← PlatformIO/NimBLE firmware (independent C++ port)
├── gradle/libs.versions.toml ← single source of truth for Gradle dependency versions
└── .github/workflows/        ← CI (JVM and Android builds/tests; firmware build)
```

### Dependency rules (enforced by review; violations are bugs)

```
core ◄──── simulator
core ◄──── app
core ◄──── sensor-app
```

- `core` **must not** import `android.*`, `androidx.*`, or any framework that needs a device.
- `simulator` must not depend on Android modules. `esp32/` is a separate PlatformIO project,
  not a Gradle module or a consumer of `core`; its C++ codec must stay in sync with the wire docs.
- `app` and `sensor-app` must not depend on each other. Shared Android code goes into
  a new `android-common` module **only** when a second real consumer exists (see ADR-0003).
- The UI layer never touches Room, BLE, or sockets directly. It talks to ViewModels and
  repositories; the monitoring service owns the active source (see `docs/architecture.md`).

---

## 3. Non-negotiable engineering rules

### 3.1 File size limit: **≤ 1000 lines per file**

- Every source file must stay at or under **1000 lines**. Split by responsibility (one
  type or one cohesive concern per file) long before you hit the limit.
- Exception: a file that is *genuinely* indivisible may exceed 1000 lines. To use it you must
  (a) explain why in the PR description, and (b) put a `// FILE-SIZE-EXCEPTION: <reason>`
  comment in the first 5 lines. Generated code is excluded from the rule but must be marked
  `// GENERATED`.
- Check before committing:
  ```bash
  find . -path ./.git -prune -o \( -name '*.kt' -o -name '*.kts' -o -name '*.md' \) -print0 \
    | xargs -0 wc -l | sort -rn | head -5
  ```

### 3.2 Modularity

- One public type per file, filename = type name (`GlucoseReading.kt`).
- Prefer small, pure functions and immutable `data class`/`enum class` models.
- Hide implementation details with `internal` (within a module) or `private`.
- Side effects (time, randomness, Bluetooth, sockets, storage) are injected behind interfaces
  so that the logic stays testable. Use `Clock`-style abstractions (`TimeSource`) and seeded
  `kotlin.random.Random` instances. Never call `System.currentTimeMillis()` or the global RNG
  from logic code.

### 3.3 Kotlin / Android style

- Kotlin official code style (`kotlin.code.style=official`). No `!!` in production code.
- Use `Flow` for streams, `suspend` for one-shot work, and `StateFlow` for UI state.
- Coroutine dispatchers are injected (`CoroutineDispatcher` parameters) so tests can use
  `StandardTestDispatcher`. No `GlobalScope`. Scopes are tied to lifecycles
  (`viewModelScope`, service `lifecycleScope`, or an explicit `SupervisorJob` you cancel).
- Compose: stateless composables with hoisted state. Previews live in the same file as the
  composable, or in `ui/preview/` if the file gets large.
- Units are explicit in names: `glucoseMgdl`, `intervalMinutes`, `epochMillis`.
  Glucose is stored in **mg/dL** everywhere internally. Convert only at the edge.
- Times are stored as epoch milliseconds (UTC). Display uses the device time zone.

### 3.4 Privacy and health data

- No network calls except to the **user-configured** simulator endpoint (TCP) and BLE.
- No analytics, crash reporting SDKs, or cloud sync.
- Do not add `INTERNET` permissions beyond what the TCP transport needs.
- Don't log glucose values at `INFO` or above in release builds.

---

## 4. Architecture decisions

Every non-trivial design choice needs an ADR in `docs/adr/` before or with the code.
Use the template in `docs/adr/0000-template.md`. Accepted ADRs are immutable: supersede them
with a new ADR instead of editing history. ADRs record decisions *at the time*; for current
behavior and known gaps, use the code and the living docs (`README.md`, `docs/PLAN.md`,
`docs/architecture.md`, `docs/ble-protocol.md`, and `esp32/README.md`).

Current ADRs (see the folder for details):

| ADR  | Topic                                                   |
|------|---------------------------------------------------------|
| 0001 | Record architecture decisions                           |
| 0002 | Use the Bluetooth SIG CGM Service as the wire protocol  |
| 0003 | Shared pure-Kotlin `core` module                        |
| 0004 | Simulator model: parametric physiology, seeded, scenario presets |
| 0005 | Trend arrows: Nightscout/Dexcom-compatible rate bands    |
| 0006 | Android architecture: UDF, Room as single source of truth |
| 0007 | Background work: foreground service vs WorkManager      |
| 0008 | Dependency injection: manual container, no Hilt         |
| 0009 | Charts: custom Compose `Canvas`, no chart library       |
| 0010 | Alerts: hysteresis, snooze, notification channels       |
| 0011 | Transports: TCP NDJSON for dev, BLE for the real path   |
| 0012 | Build tooling: Gradle Kotlin DSL, version catalog, AGP 9.x |
| 0013 | Sensor app: a second Android phone as a BLE CGM peripheral |
| 0014 | CGM Service completeness: status, CRC, RACP, ops CP, MTU |
| 0015 | ESP32/NimBLE firmware as a third simulated-sensor target |

---

## 5. Build, test, and run

### JVM modules (runnable anywhere with a JDK 17+)

```bash
./gradlew :core:test :simulator:test          # unit tests
./gradlew :simulator:run --args="--port 8765" # start the TCP simulator
```

### Android modules (needs Android SDK, or rely on CI)

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest
./gradlew :sensor-app:assembleDebug :sensor-app:testDebugUnitTest
./gradlew lint                              # manual; not yet in CI
```

### ESP32 firmware (needs PlatformIO and an ESP32 board for flashing)

```bash
pio run -d esp32                           # build; CI runs this in esp32/
pio run -d esp32 -t upload                 # flash a configured esp32dev board
```

### Sandbox note (for agents)

Agent sandboxes may not reach Google Maven (`maven.google.com`), `dl.google.com`, or Maven
Central. In that case:

- `core` and `simulator` can still be validated with a Kotlin compiler distribution plus a JDK.
- The workflow (`.github/workflows/ci.yml`) runs JVM tests, Android debug builds/unit tests,
  and a PlatformIO firmware build. It does **not** run lint, an ESP32 host-vector test, an
  emulator, or a physical BLE session. Treat a green run for the **exact revision** as evidence
  only for the jobs it actually ran; state what was not checked in the PR.

### Definition of done for a change

1. Relevant unit tests added or updated and passing.
2. No file over 1000 lines (or a documented exception).
3. Docs updated: `docs/ble-protocol.md` for any wire change, an ADR for any architectural change,
   and `README.md` if user-visible behavior changed.
4. `git diff --stat` reviewed: no build outputs, IDE files, or secrets committed.
5. CI green on the branch.

---

## 6. Git and GitHub workflow

- Work only on the branch assigned to your session (`git branch --show-current`); never
  switch to, create, or push to another branch. Do not copy a branch name from an old ADR/PR.
- Commit messages: Conventional Commits (`feat(core): ...`, `fix(app): ...`, `docs(adr): ...`).
- Keep commits focused. One logical change per commit is ideal.
- Do not commit: `local.properties`, `*.jks`, `*.keystore`, `.gradle/`, `build/`, `.idea/`,
  `captures/`, `*.apk`, or anything with credentials.
- Open PRs with `gh pr create`, linking the relevant ADR(s) and the plan phase.

---

## 7. Working agreements for agents

- **Plan before code.** For a new feature, update `docs/PLAN.md` (or the relevant ADR) first.
- **Research before deciding.** When a choice involves an external standard or framework,
  record the source in `docs/research/prior-art.md` and cite it in the ADR.
- **Prefer standards over invention.** Use Bluetooth SIG profiles and documented Android APIs.
  Custom behavior needs a documented reason.
- **Don't widen scope silently.** If something is out of scope, add it to the "Future work"
  section of `docs/PLAN.md` instead of building it.
- **Keep the sandbox honest.** Say what was compiled, what was tested, and what was only
  reviewed. Never claim an Android build passed locally unless it did.
- **Keep secrets out.** Don't echo tokens or credentials. Use the `gh` CLI's existing auth.
