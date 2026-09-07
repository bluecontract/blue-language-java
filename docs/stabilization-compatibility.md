# Stabilization compatibility checkpoint

The selected semantic direction preserves authored `{}` as a present object and `[]` as a present list. Source object nulls disappear; Source list nulls become positional `$empty` placeholders. A fieldless builder is not an authored empty object. Strict direct identity requires pure references in type positions. Source canonical identity uses definition resolution and authenticated type evidence. Preparing a definition does not certify a completed instance.

The six canonical Appendix A YAML examples used `description: >`, adding a final newline absent from the registry files. `CoreRegistryAppendixTest` reproduced all six mismatches through the public Source parser. The examples and specification mirror now use `>-`; descriptions and registry identities were not changed to accommodate the editorial error. All six parsed examples now match their authenticated registry BlueIds.

Selected registries:

- Language: `sha256:5c7a48fd3437182a2b6c43255c96e58c81e9872b4a3c150906b831812925a321`, six production entries.
- Contracts: `sha256:1442c90ed0b2601b7293cd3c21938a86907d217336b69e4674adabbf3253e9a4`, 25 production and three fixture entries.
- Fixture-only keys remain FixtureEvent, ScriptedExternalChannel and ScriptedHandler.
- Dictionary: `5WQ4tVb4gUUdZa7EfaiUa2XKQwgAurvfYY3ALPauxcAF`; List: `85ip88snCGrgUNdi1rUFqqAxcxwVGKV2g4LjsKoyKmXK`.
- EmbeddedCollectionEventChannel is newly present. ProcessEmbedded changes from Marker to Contract; dependent runtime identities rotate with the canonical resources.

`RegistryCompatibilityInventoryTest` writes `blue-contracts-core/build/stabilization/registry-compatibility-inventory.json`: every definition path, parsed content, file SHA-256, independently calculated public Java BlueId, registry package, classification, consumers and dependencies. The 34 entries form a closed registry dependency set, and production entries do not reference fixture-only entries.

`CrossLanguageIdentityFixtureTest` independently executes the public Java Source and direct identity APIs for the shared authored fixture inputs. Its output is compared by the Blue JS stabilization tests; no TypeScript helper computes the Java expected IDs. Cases include empty/null distinctions, schema/required declarations, enum order, large scalars, inline types, effective inherited constraints, inherited custom scalars and positional replacement. The corresponding Java commands are:

```sh
./gradlew :blue-language-core:test --tests blue.language.runtime.CoreRegistryAppendixTest --tests blue.language.runtime.CrossLanguageIdentityFixtureTest
./gradlew :blue-contracts-core:test --tests blue.language.processor.RegistryCompatibilityInventoryTest
```

This is a foundational compatibility checkpoint. It does not certify generated catalog, downstream runtime, MyOS acceptance or release readiness. Those gates remain in the ordered stabilization queue.
