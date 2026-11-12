# ADR-001: Java 21 and the future service framework

Status: **Accepted** for Java 21; **Proposed** for Spring Boot in Milestone 2.

## Context

The engine needs immutable models, deterministic numerical code, and a reproducible JVM build. Later services will need request validation, persistence, and operational endpoints. Those concerns do not belong in the engine.

## Decision

Compile and test the engine with Java 21. Use Java records for appropriate immutable value models. No Spring dependencies are introduced in the initial milestone.

Spring Boot 3.x is the requested service-framework target. Select a compatible, supported release and review its support status at Milestone 2; if the requested major is no longer suitable, document and review the deviation before adopting another major.

## Consequences

Contributors need a Java 21 JDK, and CI must exercise Java 21. Framework upgrades remain independent of financial calculations. This ADR does not assert support for a specific future Spring Boot release.
