# ADR-0001: Record architecture decisions

- **Status:** Accepted
- **Date:** 2026-10-13

## Context

FakeGlucoSense spans a pure-Kotlin library, a JVM simulator, and two Android apps. It uses
standards (Bluetooth SIG) and platform rules (foreground service types, notification
permissions, WorkManager limits) that are easy to get wrong and hard to rediscover later.
Future contributors, including agents, need to know why the code looks the way it does.

## Decision

We will record each significant decision as a numbered, immutable ADR in `docs/adr/`, using
`0000-template.md`. Each ADR has a status and a date. To change a decision we add a new ADR that
supersedes the old one, and we don't rewrite the old one.

## Consequences

- Some up-front writing cost per decision. Keeping ADRs short (≤ 1 page) keeps it cheap.
- Reviewers can check an implementation against a written contract.

## Alternatives considered

- **Decisions in PR descriptions only.** Lost in merge history and hard to search.
- **A single architecture document.** Goes stale. Small, dated records age better.

## References

- Michael Nygard, "Documenting Architecture Decisions" (2011), the origin of the ADR format.
