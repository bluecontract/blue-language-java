# Public SDK reproducer handoff

Run with a JDK supported by Gradle and the repository's configured Java 8 target:

```sh
./gradlew :blue-language-core:test --tests '*DefinitionSdkHandoffTest' --console=plain
```

Source inputs are in `blue-language-core/src/test/resources/campaign/d2/`:

- `gender.yaml`: define/store/reference/use with no sample at definition time.
- `invalid-foo.yaml`: conflicting fixed inherited Integer value.
- `request-pattern.yaml`: required request declaration, required Text child with minLength, and event enum inside contracts metadata.
- `request-payload.yaml`: actual supplied request and event payload used to complete that pattern.

The executable `DefinitionSdkHandoffTest` uses only public SDK APIs. Its provider deliberately stores exact canonical content. IDs are computed from these inputs, never hardcoded to the candidate's current spec-environment digest. The Gender case first resolves a reference without evidence, checks INCOMPLETE and the outstanding exact ID, then provides the same canonical input and succeeds.

Essential definition flow:

```java
Node definition = language.codec().parseSource(yaml, BlueFormat.YAML);
Node prepared = language.resolution().resolveDefinition(definition);
Node canonical = language.identity().canonicalIdentityInput(definition);
String typeId = language.identity().directBlueId(canonical);
provider.addSingleNodes(canonical);
Node authoredInstance = new Node().type(new Node().blueId(typeId)).value("female");
Node completed = language.resolution().resolve(authoredInstance);
```

For the contract-pattern source, canonicalize/store the pattern in the same way. Parse `request-payload.yaml`, set its type to the stored pattern ID, then resolve. Omitting the required request or shortening its text to `x` fails. This verifies Language declaration/value boundaries under `contracts`; it does not execute a Contracts operation or prove an HTTP response.

Cold pure-reference acceptance is separately demonstrated. A direct/source identity and a root-limited resolution may establish the reference without fetching its target. The test then expands a verified invalid target and shows that both ConformanceEngine and completed resolution reject its payload. Adapters must not report the earlier reference acceptance as target conformance.

For a declaration enum or required field, a successful `resolveDefinition`, minimization, canonical identity computation, or specialization is never an instance certificate. C2/Mini should select the intended SDK operation; no transport-side duplicate validator or type-name heuristic is needed.

A2 integration input: `fixture-definition-goal.patch` migrates the two released payload-free normalization vectors to explicit definition preparation. Apply with authoritative fixture/schema/package digest regeneration; do not apply only the YAML edits and publish the old manifest. The final verification report records the exact remaining sealed-fixture/binding failures.
