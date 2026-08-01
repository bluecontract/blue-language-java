# ADR 0007: Published module boundaries follow semantic ownership

Status: accepted for the Java distribution.

## Context

The original single source set mixed the value model, Language algorithms,
mapping, IPFS transport, Contracts processing, conformance fixtures, and
release tooling. Optional dependencies leaked into minimal consumers.

## Decision

Publish these acyclic components:

```text
blue-language-model
        ^
        |
blue-language-core <--- blue-language-ipfs
        ^
        +--- blue-language-mapping
        ^             ^
        +-------------+--- blue-contracts-core
                              ^
                              |
                       blue-conformance

blue-language-java re-exports the supported runtime modules.
```

The model owns stable values. Language core owns semantics and provider SPI.
Mapping owns Java reflection; IPFS owns HTTP transport. Contracts depends on
Language public APIs, never the reverse. Conformance may depend on both.
Build logic is an included build and is not a runtime artifact.

## Consequences

- Published modules have no split Java packages or dependency cycles.
- The aggregate artifact remains the one-dependency convenience option and
  contains only composition/facade code.
- Fixture harnesses and release CLIs cannot leak into runtime core artifacts.
- Published-artifact smoke tests resolve staged coordinates without composite
  substitution.

See [Modules and dependencies](../architecture/modules-and-dependencies.md).
