# Codex Prompt 03 — Cross-Repository Integration and Release Gate

Run this only after Prompt 01 and Prompt 02 independently pass.

The purpose is to prove that the exact final Contracts 1.0 package is implemented consistently by `blue-language-java` and consumed correctly by the canonical Blue Coordination Java project at `../blue-contract-java`. It does not create a Coordination profile document and it does not implement Mandates.

## Inputs to bind

Record exact identities for:

```text
Blue Language specification/release
Language core registry
Language cyclic finalizer implementation
Language cyclic proof verifier implementation
Blue Contracts specification 1.0
Contracts runtime registry
Contracts gas manifest
Contracts identity-constructor registry
Contracts fixture package
Contracts implementation artifact
Coordination implementation artifact
Repository version used by integration fixtures
```

## Gates

### 1. Package gate

From the package root, run:

```bash
PYTHONDONTWRITEBYTECODE=1 python3 tools/validate_package.py
```

Expected:

```text
PACKAGE_VALID
SEMANTIC_REFERENCE_VALID
validation-output.json: implementationConformanceClaimed: false
```

The release gate is read-only. Do not use `--write-output` here: without the
original source ZIP supplied through `--source`, doing so would replace the
already verified source-archive evidence with `provided: false`. If evidence is
intentionally regenerated, pass the exact original archive with `--source` and
then revalidate every manifest and package byte.

These first two levels prove package structure and the independent executable
reference model. They are not implementation conformance.

### 2. Contracts implementation gate

Require:

```text
all ordinary Contracts fixtures green
all closure fixtures declared by the generated manifest green
all exact cyclic oracles independently verified
all gas traces exact
cold/warm/inline/reference parity
no Mandate-specific code in Language/Contracts
```

### 3. Coordination integration gate

Require:

```text
finite dynamic A/B passes
exact M1/M2/M3/M4 visibility passes
default-policy loop produces exact deterministic rollback
A10/A5 missing-evidence and complete-chain cases pass
merge/split/frozen-edge cases pass
1000-unrelated locality passes
same-entry closure publication is atomic
historical multi-entry readiness remains deterministic
```

### 4. Existing product regression gate

Run the complete current Coordination suite, including:

```text
Counter
NBA
five occurrences
nested same-entry ordering
Wadowice
initialization
historical admission
failure/retry
consumer artifact
Java 17
Java 21
```

Any semantic expectation changed by the final closure-atomic rule must be updated explicitly with a report explaining why. Do not silently relabel failures.

### 5. Release artifact gate

Build from clean commits. Verify extracted source archives and built-JAR consumers. Produce SHA-256 values for every artifact.

### 6. Spec immutability gate

Hash the delivered final spec and fixture package before implementation. Hash them again afterward. They must be byte-identical.

If implementation discovers a contradiction, release is BLOCKED; do not edit the spec in the implementation repository.

Do not run the Coordination `stageRelease` gate against stale prior-candidate
evidence. First introduce or generalize the next candidate's versioned evidence,
artifact hashes, source archive names, and test inventories. Then run the full
Java 17 and Java 21 release lanes and consumer-JAR verification against the
exact staged Contracts artifact rather than a sibling source substitution.

## Final report

Produce one signed/identity-bound report with:

```text
releaseReady: true|false
specIdentity
fixtureIdentity
gasIdentity
contractsIdentityConstructorsIdentity
languageArtifactIdentity
contractsArtifactIdentity
coordinationArtifactIdentity
cyclicFinalizerImplementationIdentity
cyclicProofVerifierIdentity
all test totals
all failed/skipped tests
finite scenario exact trace
loop exact trace
A10/A5 exact result
performance structural counters
known unsupported capabilities
```

Mandate-aware Coordination, provider-backed completeness, MyOS persistence and Playground integration are later independent release phases.
