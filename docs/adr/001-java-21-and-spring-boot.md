# ADR-001: Java 21 and the future service framework

Status: **Accepted** for Java 21 and Spring Boot 4.1.2 services.

## Context

The engine needs immutable models, deterministic numerical code, and a reproducible JVM build. Later services will need request validation, persistence, and operational endpoints. Those concerns do not belong in the engine.

## Decision

Compile and test the engine with Java 21. Use Java records for appropriate immutable value models. No Spring dependencies are introduced in the initial milestone.

The original service-framework target was Spring Boot 3.x. At Milestone 2, official release
notes confirm [Spring Boot 3.5 ended OSS support](https://spring.io/blog/2026/06/25/spring-boot-3-5-16-available-now/).
Use Spring Boot 4.1.2, which [supports Java 21 and Gradle 9](https://docs.spring.io/spring-boot/system-requirements.html).
This avoids requiring paid support for a new project and preserves the validated Gradle foundation.

Keep the independent engine on JUnit 5. Service tests use Boot-managed JUnit 6 because
[Spring Framework 7's testing extension requires it](https://docs.spring.io/spring-framework/reference/testing/testcontext-framework/support-classes.html).
Use Jackson 3's Boot-configured mapper for service JSON. These framework-driven deviations do not enter the engine.

## Consequences

Contributors need a Java 21 JDK, and CI exercises Java 21. Framework upgrades remain independent of financial calculations. Service dependencies follow the pinned Boot BOM; engine test dependencies remain separately pinned.
