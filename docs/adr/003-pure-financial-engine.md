# ADR-003: Infrastructure-independent financial engine

Status: **Accepted**.

## Context

The same calculation must produce the same result in a test, API process, or worker. Framework and I/O coupling would complicate both numerical verification and eventual distributed execution.

## Decision

Make `financial-engine` a plain Java library with no runtime dependencies beyond the JDK. Validate immutable inputs at domain boundaries, reject ambiguous series ordering, use decimal price values, and make unavailable indicators explicit. Keep database entities, HTTP models, message envelopes, storage access, clocks, and identifiers for job execution outside the financial algorithms.

## Consequences

Workers can invoke the library without changing its financial behavior. Service adapters will translate their contracts to engine inputs. Numerical policies and edge cases must be documented and tested independently of service integration tests. See [calculation conventions](../architecture/calculations.md).
