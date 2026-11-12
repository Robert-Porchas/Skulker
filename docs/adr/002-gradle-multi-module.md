# ADR-002: Gradle multi-project build

Status: **Accepted**.

## Context

Skulker will gain service applications without making its calculation library depend on their runtime infrastructure. Today there is one implemented module.

## Decision

Use Gradle with Kotlin DSL under `backend/`, with a committed, version-pinned wrapper and `financial-engine` subproject. Centralize the small set of shared build settings at the root. Add modules and reusable convention plugins only when concrete consumers justify them.

## Consequences

`./gradlew` is the build entry point; a global Gradle installation is unnecessary. Module dependency edges remain explicit. Empty service modules and a separate build-logic framework would add maintenance without validating current functionality, so they are deferred.
