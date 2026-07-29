# Developer process

This guide is the working agreement for changing `blue-language-java`. It
covers local setup, code navigation, implementation conventions, tests,
conformance fixtures, and the release-evidence path. The repository implements
both the Blue Language 1.0 document layer and the generic Blue Contracts and
Processor 1.0 kernel; application-specific BEX and Coordination behavior
belongs in their own repositories.

## 1. Prepare the workspace

### Required tools

The checked-in Gradle wrapper is the build entry point. It compiles Java
8-compatible bytecode and runs tests on a Java 8 toolchain. The JVM that runs
Gradle is recorded in generated release evidence rather than fixed by
repository policy. Gradle can provision the Java 8 test toolchain through the
configured Foojay resolver when it is not already installed.

Before editing, check:

```bash
java -version
./gradlew --version
git status --short
```

Do not upgrade the wrapper, Java target, dependency versions, registries, or
release identities as part of an unrelated change. Treat an already-dirty
working tree as user-owned work: identify the files relevant to the task and
preserve everything else.

All builds in one checkout share `build/`. Do not run a filtered `test` task in
parallel with another report-producing build. Gradle test tasks
replace their result directories, so concurrent runs can leave a complete
implementation with incomplete report evidence.

The same rule applies to sibling composite builds that use
`includeBuild("../blue-language-java")`: they execute this checkout's tasks
and write this checkout's `build/` directory. Keep those builds idle while
collecting release evidence. If concurrent composite execution is unavoidable,
give every invocation the same `SOURCE_DATE_EPOCH`, while recognizing that a
shared output directory is still not a supported concurrency boundary.

### Repository-independent provider integration

`blue-language-java` has no dependency on a repository product, catalog
artifact, or repository manifest. Applications provide content through the
generic `NodeProvider` contract and may compose providers with
`SequentialNodeProvider`.

Provider tests must exercise the contract directly:

- content returned for a BlueId is verified against that requested identity;
- `NOT_FOUND`, `UNAVAILABLE`, and invalid evidence remain distinct outcomes;
- source-content providers are bound to the active release and preprocessing
  environment before their content is admitted; and
- provider caches preserve those verification and outcome semantics.

Do not add a concrete repository adapter or artifact coordinate to the
Language build. Integration with an application's storage or catalog belongs
in that application.

## 2. Find the correct layer

Start at the public boundary involved in the behavior, then follow the data
into the smallest owning package.

| Location | Responsibility |
| --- | --- |
| `src/main/java/blue/language/Blue.java` | Main facade, configuration, lifecycle, language operations, snapshots, and processor registration |
| `model/` | Mutable Blue node model, schema model, parsing, and serialization boundaries |
| `preprocess/` | Blue directives, aliases, and default preprocessing |
| `provider/` | Verified content-addressed lookup, ingestion, and cyclic-set proof |
| `merge/` | Resolution, inheritance, list controls, and canonical/minimized reconstruction |
| `snapshot/` | Immutable `FrozenNode`, `ResolvedSnapshot`, reference evidence, and structural reuse |
| `utils/` | BlueId calculation, pointer operations, matching, limits, and shared language constants |
| `dictionary/` and `mapping/` | Dictionary-aware export and Java object conversion |
| `conformance/` | Type conformance and generalization |
| `processor/` | Generic Contracts kernel, gas, phases, external evidence, handlers, checkpoints, and hosted-runtime boundaries |
| `registry/` | Runtime-facing registry loaders and stable registry identities |

The processor package has several deliberately separate boundaries:

- `RuntimeWorkSession` owns hosted-runtime work ledgers and their lifecycle;
- `SemanticOutputBoundary` admits exact hosted output and semantic gas;
- `ExternalChannelFunctionContext` owns immutable same-scope dependencies;
- `SelectedExecutableBody` opens only verified references reachable from the
  selected body;
- `ExecutableBodySourceDescriptor` records the exact contribution and pointer
  from which a body came; and
- `ExactNodeGraphFragments` models physical acquisition using ordinary exact
  Blue content, without changing the two semantic `PROCESS` inputs.

Keep Language, Contracts-kernel, BEX, and Coordination responsibilities
separate. This repository must not acquire application-specific parsing,
authorization, expression evaluation, registry policy, or persistence.

Resources are part of the implementation:

| Location | Content |
| --- | --- |
| `src/main/resources/registry/blue-language-1.0/` | Canonical Language registry |
| `src/main/resources/registry/blue-contracts-1.0/` | Canonical Contracts registry |
| `src/main/resources/specifications/` | Vendored normative specifications |
| `src/main/resources/release/` | Identity-bound release manifest |
| `src/test/resources/blue-language-1.0/fixtures/` | Closed Language fixture package |
| `src/test/resources/blue-contracts-1.0/fixtures/` | Closed Contracts and gas fixture package |

## 3. Define the change before coding

Write down the behavior in one sentence and identify:

1. the public or package boundary that owns it;
2. the invariant that must remain true;
3. the exact success and failure outcomes;
4. whether the change affects identity, gas, provider evidence, lifecycle,
   public API, fixtures, or release artifacts; and
5. the smallest focused test class that can prove it.

For processor work, also identify the deterministic phase. Read-only evidence
acquisition, routing, mutation, output admission, ledger submission, and
commit are not interchangeable. A failure after gas admission may roll back
application effects while retaining the admitted ordered gas trace.

For identity work, distinguish:

- structural BlueId calculation over authored canonical content;
- semantic BlueId calculation after preprocess, resolve, and minimization;
- verified provider evidence for an exact requested BlueId; and
- opaque finalized cyclic-member identity, which requires a cyclic-set proof.
  Proof acquisition uses `CyclicSetProofResult`; preserve its `NOT_FOUND`,
  `UNAVAILABLE`, and `INVALID_EVIDENCE` distinctions instead of collapsing
  them into a nullable proof.

For hosted-runtime gas work, distinguish the parent invocation limit, each
named ledger's live reservation, and an optional invocation-owned
`RuntimeWorkBudget` shared by several ledgers. Check the shared cap before
mutating either a child trace or the parent reservation, and route a local
rejection through the session's canonical `RuntimeGasExhaustion` path.

Document the reason when a change preserves a compatibility descriptor but
tightens its behavior. Never restore a trust bypass to satisfy an old method
name.

## 4. Use comments to preserve intent

Add comments where they help the next developer recover information that the
Java syntax cannot express.

### Public and extension APIs

Use Javadoc on public classes, interfaces, constructors, methods, and constants
when their contract is not already self-evident. Explain:

- what the API represents or owns;
- required inputs and returned guarantees;
- lifecycle and thread-safety rules;
- identity, verification, gas, and mutation effects;
- whether returned collections and nodes are immutable or defensive copies;
- important failure conditions; and
- how the API differs from a nearby, easily confused operation.

Document parameters and return values when their meaning is not obvious from
the signature. Document exceptions that are part of the caller contract. A
compatibility method should say which final method owns its semantics.

### Internal implementation

Use short comments for invariants, non-obvious ordering, phase boundaries,
security or verification decisions, canonicalization rules, and deliberate
failure behavior. A comment should explain *why* a step exists, not narrate
`i++` or repeat a method name.

Good:

```java
// Gas is admitted before effects so rollback cannot erase performed work.
runtimeWorkSession.submit(ledger);
```

Avoid:

```java
// Submit the ledger.
runtimeWorkSession.submit(ledger);
```

Keep comments synchronized with behavior. Remove comments that describe a
superseded preview path. Prefer extracting a clearly named method when several
lines of commentary are needed to explain basic control flow.

## 5. Replace magic values with named constants

String keys, pointer fragments, type identities, counter names, modes, and
stable diagnostic tokens must not be scattered as unexplained literals.

Reuse the existing owner whenever possible:

| Concern | Existing owner |
| --- | --- |
| Blue metadata and list-control keys | `blue.language.utils.Properties` |
| Processor-managed contract keys | `ProcessorContractConstants` |
| Processor JSON-pointer paths | `ProcessorPointerConstants` |
| Contracts runtime type BlueIds | `RuntimeBlueIds` |
| Gas schedule identity and counter lookup | `GasSchedule` and the gas manifest |
| Release and conformance resources | The corresponding conformance report class |

For example:

```java
public final class ProcessorContractConstants {

    public static final String KEY_EMBEDDED = "embedded";
    public static final String KEY_INITIALIZED = "initialized";
    public static final String KEY_TERMINATED = "terminated";
    public static final String KEY_CHECKPOINT = "checkpoint";

    private ProcessorContractConstants() {
    }
}
```

Choose the narrowest useful ownership:

- use a `private static final` constant when only one class owns the value;
- use a package utility class when several collaborators share one vocabulary;
- use a public constant only when callers must author or interpret that exact
  stable value; and
- derive pointers from key constants instead of duplicating both spellings.

Name constants for meaning, not appearance: `KEY_CHECKPOINT`,
`DEFAULT_RUNTIME_NAMESPACE`, or `TYPE_TEXT_BLUE_ID` is better than
`CHECKPOINT_STRING` or `VALUE_1`. Keep one canonical declaration for a stable
value and statically import it only when the call site remains unambiguous.

Ordinary test data such as a person's display name need not become global
production vocabulary. Repeated protocol values and values whose exact
spelling controls behavior should be named in the test fixture or support
class.

## 6. Write tests as Given–When–Then

Every JUnit `@Test` method should:

- have a readable name beginning with `should`;
- prove one behavior or one tightly coupled outcome;
- show `// given`, `// when`, and `// then` sections in that order; and
- keep assertions in the `then` section.

Example:

```java
@Test
void shouldRejectUnverifiedSelectedBodyReference() {
    // given
    SelectedExecutableBody body = selectedBodyWithMissingReference();

    // when
    Throwable failure = captureFailure(
            () -> body.materializeReference(MISSING_BODY_BLUE_ID));

    // then
    assertInstanceOf(RuntimeException.class, failure);
    assertEquals(EXPECTED_FAILURE_MESSAGE, failure.getMessage());
}
```

Setup shared by every test may remain in `@BeforeEach`, but each test's
`given` section should make the behavior-specific inputs clear. Helper methods
should describe domain intent rather than hide the entire scenario.
`FailureCapture.captureFailure` is the shared test helper for executing an
expected failure in `when` and asserting its type and details in `then`.

Split a test when it has unrelated triggers, distinct failure modes, or
multiple independent reasons to fail. It is reasonable for one test to assert
several properties of one result—for example, an atomic rejection can assert
the unchanged Root, no emitted events, and the retained gas trace—because
those assertions together define one behavior.

For parameterized or dynamic tests, use a `should...` factory/method name and
make each generated display name describe the expected behavior. Conformance
fixture runners may preserve fixture IDs as display evidence, but their
ordinary unit tests still follow this convention.

Keep tests deterministic:

- do not depend on test order, wall-clock time, ambient network, or shared
  mutable global state;
- use exact canonical nodes and stable named constants;
- assert provider demand or locality only where it is part of the contract;
- test both inline and pure-reference representations where representation
  parity matters; and
- include rollback, suspension, and gas-exhaustion cases for phase-sensitive
  processor changes.

Run the smallest proving test while iterating:

```bash
./gradlew test --tests \
  'blue.language.processor.RuntimeWorkSessionTest'
```

Then run the complete suite:

```bash
./gradlew test
```

Do not leave a change proved only by a filtered run.

## 7. Change specifications or fixtures only deliberately

The fixture manifests are closed inventories, not a collection of optional
examples. Unknown operations, fields, controls, projections, counters, and
assertions fail closed. There is no skipped conformance outcome.

Before changing a fixture package:

1. Read its `README.md`, `HARNESS.md`, and manifest.
2. Identify the normative specification paragraph and registry entry that
   require the change.
3. Add or update the smallest fixture that proves the rule.
4. Keep fixture IDs, categories, and manifest ordering deterministic.
5. Update any exact expected gas using the manifest-defined counter names and
   weights; never tune expected totals to match an accidental implementation
   path.
6. Recalculate every affected package/specification identity and update all
   bound declarations together.
7. Run the isolated fixture suite and then the complete release conformance
   gate.
8. Review the generated per-fixture evidence and confirm that every
   manifest-listed fixture executed exactly once.

The current final packages contain 128 Language fixtures and 140 Contracts
fixtures (82 behavior and 58 gas), for 268 release results. A change to those
counts or identities is release work and must not be hidden inside an ordinary
refactor.

Useful focused commands:

```bash
./gradlew test --tests '*BlueLanguageConformanceFixtureTest'
./gradlew test --tests '*BlueContractsConformanceFixtureTest'
./gradlew releaseConformanceTest
```

Do not edit vendored specification prose merely to justify current code. A
normative update should arrive with its reviewed source, digest, fixtures,
registries, migration note, and release-manifest update.

## 8. Verify in increasing scope

Use the following sequence. Stop at the first failure and determine whether it
is a code defect, stale expectation, dependency problem, or contaminated build
output.

### Source and focused checks

```bash
git diff --check
./gradlew compileJava compileTestJava
./gradlew test --tests '<fully-qualified-test-class>'
./gradlew runtimeTraceEvidence
```

`runtimeTraceEvidence` executes the eight ordered-ledger scenarios and records
only values read back from the live `RuntimeWorkSession`.
Provider correctness is established by focused generic `NodeProvider` contract
tests, including exact identity verification, cyclic-set proof, absence,
temporary unavailability, invalid evidence, and cache lifecycle behavior.

### Full project checks

```bash
./gradlew test
./gradlew verifyNoDeprecatedProductionApi
./gradlew verifyNoAmbiguousReverseApi
./gradlew verifyFinalApiBaseline
./gradlew releaseConformanceTest
```

`verifyFinalApiBaseline` compares the candidate with
`api/blue-language-java-1.0.json`, rejects binary incompatibilities and Java
class versions above 52, and lists additive descriptors for review. Do not
rewrite that baseline as an implementation shortcut.

### Release evidence

Run a successful clean build and the project-owned evidence tasks as separate
invocations:

```bash
BLUE_RELEASE_EPOCH="$(git show -s --format=%ct HEAD)"
SOURCE_DATE_EPOCH="$BLUE_RELEASE_EPOCH" ./gradlew clean build
SOURCE_DATE_EPOCH="$BLUE_RELEASE_EPOCH" ./gradlew rcVerify
```

Keep `clean build` separate from `rcVerify`. The first
invocation writes clean-build completion evidence only after `build` succeeds
over the exact source fingerprint and `SOURCE_DATE_EPOCH` recorded by `clean`.
Task exclusions such as `-x test` deliberately suppress that evidence.

Review at least:

```text
build/reports/conformance/release-conformance.json
build/reports/conformance/release-conformance.txt
build/reports/binary-api/final-1.0-baseline-to-candidate.txt
build/reports/runtime-trace/runtime-work-session.json
build/reports/reproducibility/jar-repeatability.json
build/reports/reproducibility/source-archive-repeatability.json
```

The machine-readable report is authoritative. Confirm full test and fixture
counts, zero skipped fixtures, binary compatibility, the maximum observed
ordered runtime trace and its exact prefix semantics, archive repeatability,
locality/hosted-runtime evidence, source fingerprint, and commit-automation
status. Generic provider tests must also remain green for exact identities,
cyclic evidence, absence, unavailability, invalid evidence, and cache
lifecycle. A truthful report may distinguish passing implementation gates
from release readiness when the working tree or reviewed baseline is not
release-clean.

## 9. Review the diff

Before handoff:

1. Read every changed production file from top to bottom.
2. Confirm comments explain current intent and not an abandoned approach.
3. Search for repeated protocol literals that should use an existing or new
   constant.
4. Confirm every changed `@Test` starts with `should` and has visible
   Given–When–Then sections.
5. Check that public additions have useful Javadoc and immutable/defensive-copy
   behavior is explicit.
6. Check failure ordering, especially gas versus suspension, lifecycle, and
   rollback.
7. Confirm generated reports match the exact current source fingerprint.
8. Run `git status --short` and verify no sibling project, build cache, IDE
   file, or unrelated user change entered the diff.

## Contribution checklist

- [ ] The change belongs to Blue Language or the generic Contracts kernel.
- [ ] BEX, Coordination, and unrelated sibling repositories are untouched.
- [ ] Existing dirty changes were preserved.
- [ ] Public and non-obvious internal behavior is documented where it matters.
- [ ] Stable keys, pointers, identities, modes, and counters use named
      constants.
- [ ] Every changed test name starts with `should`.
- [ ] Every changed test uses `// given`, `// when`, and `// then`.
- [ ] Tests are split by behavior and remain deterministic.
- [ ] Focused and full test runs pass.
- [ ] Fixture/specification changes, if any, update all bound identities and
      execute every manifest entry.
- [ ] Binary API additions are intentional and incompatibilities are zero.
- [ ] `releaseConformanceTest` passes with zero skipped fixtures.
- [ ] `clean build` and the subsequent `rcVerify` gate pass, and
      the JSON evidence was reviewed.
- [ ] Documentation and migration notes describe the final behavior.
- [ ] Commit/release automation files remain unchanged unless the task
      explicitly owns them.
