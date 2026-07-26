# Blue Language 1.0 and Contracts Kernel 1.0 migration

This release aligns `blue-language-java` with the Final Implementation
Baseline identified by:

```text
release:
  blue-language-1.0-contracts-1.0-bex-2.0-implementation-baseline
releasePackage:
  sha256:db847cc10e0a8c9dacf529031f49f928ca4b9d62c650270b1bc3dc93c66967a0
languageRegistryPackage:
  sha256:b705171a6ca62c990792bcb78db9d921caf5b0ed06370648b9a81769d69dd71e
languageFixturePackage:
  sha256:277418303ae10aade4029a398f880a8d0f2b321d4943492ac811287c21eb3dbb
contractsRegistryPackage:
  sha256:14d5537efbece502ebf430e09805650dd7ea460415a7aa0a8279c2c11d1d6366
contractsGasPackage:
  sha256:88c7bbe77d531c9e973cae13002c3464a2c14568833adf5d804d13b7b3d26af5
contractsFixturePackage:
  sha256:58a3d8446e0e7c63063204c7bfaa312ace1242a182bc2f9c4875479a81149904
```

The Contracts gas weights and portable limits are loaded from the bound
manifest. The baseline labels the numerical values provisional pending
calibration; the counter names, ownership, formulas, and trace order are the
implementation contract.

## Language API

The four Language operations remain:

```text
expand <-> collapse
resolve <-> minimize
```

Demand-limited operations expose an explicit result instead of using a missing
node or provider exception to represent every outcome. Callers must distinguish
established values, proven semantic absence, incomplete evidence, and invalid
content. Incomplete results are not valid inputs to whole-document
canonicalization, Content BlueId calculation, or complete minimization.

Provider integrations can distinguish exact content, definitive provider-domain
absence, transient unavailability, and invalid evidence. The legacy
`NodeProvider.fetchByBlueId` method remains available for compatible providers;
new providers should expose the richer result so Language operations do not
confuse provider state with semantic absence.

The canonical `Text`, `Integer`, `Double`, `Boolean`, `Dictionary`, and `List`
nodes are loaded from the release registry files and verified against both
their file digests and published BlueIds. BlueId v1 itself is unchanged.

## Contracts result and failure model

The semantic operation remains:

```text
PROCESS(document, event) -> ProcessResult
```

The completed result surface is:

```text
status
document
events
totalGas
diagnostic?
```

`events` contains Root emissions only. The preview name `triggeredEvents` is a
compatibility alias and does not change Root-only output semantics.

Completed status values are closed:

```text
success
no-match
stale
terminated
invalid-processing-document
capability-failure
runtime-fatal
gas-limit-exceeded
portable-limit-exceeded
subscription-surface-invalid
```

Resource acquisition suspension belongs to `PROCESS_ATTEMPT` as
`NeedsResources`; it is not a completed status and carries no committed state,
events, progress, or portable gas.

Every noncommitting result returns the exact input Root and an empty Root event
sequence. Runtime failure no longer writes a terminated marker or emits a
fatal lifecycle event. Graceful application termination remains a successful
business transition; a later invocation observes `terminated`.

## Removed pre-release behavior

The following preview behavior is not part of Contracts 1.0:

- committed fatal termination and `Document Processing Fatal Error`;
- partial commit of effects produced before a deterministic runtime failure;
- public propagation of descendant events without an explicit Root emission;
- recursive serialized-payload-size gas at patch or event boundaries;
- a fixed-price fatal or out-of-gas closeout;
- caller-authored target occurrences, child processing sessions, child-commit
  envelopes, or a public transitive effect log;
- processor history inherited from a type;
- checkpoints that are not bound to both raw channel key and checkpoint domain;
- `needs-resources` as a completed processor status.

The removed fatal-error registry node is not retained as an executable runtime
type. Compatibility enum or method aliases, where retained for source or binary
transition, normalize into the Contracts 1.0 status and diagnostic vocabulary
and do not re-enable the removed behavior.

## Processor-managed writes

Application patches and generated type-generalization writes create Document
Updates. Direct initialized-marker, checkpoint, checkpoint-cleanup, and
terminated-marker writes do not. Lifecycle channels are the observation
surface for initialization and graceful termination.

All invocation effects are tentative until a successful result. Persistent
mutation rebuilds the changed direct container and ancestor spine while
retaining unchanged exact children by BlueId. Protected effective state,
active-scope cut-off, direct-container limits, and changed subscription surface
are checked before commit.

## Conformance artifacts

The vendored Language and Contracts fixture packages are exact copies of the
baseline packages. Their manifests are authoritative closed inventories.
Unknown operations, controls, projections, assertions, counters, and fixture
fields fail closed.

The machine-readable implementation report records the release and package
identities above plus one pass/fail entry for every manifest-listed fixture.
There is no skip status.

The exact published packages currently produce 125/125 Language passes and
113/127 Contracts passes. The remaining 14 Contracts records are reported as
failures rather than skipped or manufactured into passes. Making them pass
would require changing an identity-bound fixture or inventing a scope, cyclic
set, provider node, or mutation source that is absent from its declared input:

| Fixture | Published-package inconsistency |
| --- | --- |
| `c-disc-04` | Provider key `7f1ZXEZsUdZrciGtAQkR1Pav7s3Ngfbv8q9Ct2C9iNYE` is bound to content whose direct Node BlueId is `3gwbrYjenX1ji8fHvwnrBv6fijVbau47NchRQtNQxei3`. |
| `c-disc-05` | The handler adds `/h2Ran` to a scalar Root, which would create an invalid mixed payload. |
| `c-e2e-02` | `/child` is a scalar payload, not the object processing scope selected by the feeder snapshot. |
| `c-emb-02` | `/child` is a scalar payload, not an executable embedded object scope. |
| `c-emb-07` | The control replaces `/child`, but neither that child nor a `Process Embedded` declaration exists. |
| `c-evt-01` | The runtime declares a handler at `/child`, but no child scope or embedded route exists. |
| `c-evt-03` | `childEmissions` has no selected non-Root delivery occurrence from which a child can emit. |
| `c-life-03` | Lifecycle replacement has no exact non-Root scope to replace. |
| `c-prot-02` | A `replace` targets absent `/contracts/embedded/paths`; there is no `Process Embedded` contract to receive the paths-only exception. |
| `c-rep-04` | The asserted direct-identity-work bound is below the mandatory initialization, checkpoint, and changed-spine work in the same fixture. |
| `c-snd-04` | The patch targets absent `/cyclic/member/x`; the Root declares no cyclic set or member. |
| `c-upd-01` | A Root handler patches `/child/x`; the Document Update origin is Root, not the undeclared child processing scope. |
| `c-upd-02` | Adding `/new` to the scalar Root would create an invalid mixed payload before the asserted add/remove sequence. |
| `c-upd-03` | The only possible Document Update source is Root, so the source cannot be cut off while propagation continues to Root. |

The combined release report therefore contains exactly 252 results: 238
`PASS`, 14 `FAIL`, and zero skipped. It is intentionally non-conformant until
the bound fixture package is corrected. Each failure record includes the exact
fixture ID, operation, exception class, and deterministic message.

This repository deliberately does not implement application-specific
Coordination behavior, Timeline-provider persistence, feeder databases, or
BEX/expression evaluation.
