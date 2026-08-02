# Blue Language 1.0 and Contracts Kernel 1.0 migration

This release aligns `blue-language-java` with the corrected enum-normalized
Language and Contracts package identified by:

```text
release:
  blue-language-contracts-embedded-modules-collection-paths
releasePackage:
  sha256:0268c0adc8badf0d1ab5cdef4a323117b82253a3695f9125af750437a23014b6
languageSpecification:
  sha256:a234b0b42190a7982809781b5efdaa2e5f1ab4b7f8d870fbd1ffe7020cc7e869
contractsSpecification:
  sha256:6406153791ed99cf97163726b8d2a272e3f0ca1078dc6c9f69b81855d81e5c81
languageRegistryPackage:
  sha256:b705171a6ca62c990792bcb78db9d921caf5b0ed06370648b9a81769d69dd71e
languageFixturePackage:
  sha256:44465973c5c5a8c1e60712fc7970236015d9500e2e9e3fc904e364552ec74a55
contractsRegistryPackage:
  sha256:46a7744c1cbfa4b00e1d8a99f6ca3f0089ef697de968fee08547894ab02b0ca1
contractsGasPackage:
  sha256:88c7bbe77d531c9e973cae13002c3464a2c14568833adf5d804d13b7b3d26af5
contractsFixturePackage:
  sha256:16392301655431695df6a7cc142a7e388e426c382bf4e3c5f06ddfafb8efecdc
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

### Fragmentation and finalized cyclic members

`ExactNodeGraphFragments` now accepts a finalized `MASTER#index` pure
reference as an opaque edge inside an otherwise ordinary Root or Event. It
preserves the parent identity and direct-edge metadata but deliberately does
not publish a local fragment under the member identity. Compose its provider
with a `CyclicAwareNodeProvider` when member content is required; plain
independently hashed member content remains invalid evidence.

This does not introduce independent member processing or mutation. A top-level
pure member is rejected as a `PROCESS` Root/Event, mutation and
`Process Embedded` traversal below an opaque member edge fail before provider
demand, and whole-edge replacement remains supported.

Downstream splitters should use
`BlueContracts.effectiveFragmentationCatalog(Node)` or the lower-level
`DocumentProcessor.administration().effectiveFragmentationCatalog(Node)`.
The immutable catalog reports each structured embedded-scope plan—including
exact declarations, collection declarations, frozen member keys, concrete
paths, and provenance—and, for each scope, ordered
`EffectiveContractSnapshot` entries with exact source contributions, sanitized
header fields, registered executable-body field names, and present body BlueIds
by field. Inspection is provider-verified, body-cold, read-only, and outside
Contracts gas.

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

`events` contains Root emissions only. The preview `triggeredEvents` alias was
removed.

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

Gas is admitted run state, not a rollbackable application effect. A named
runtime child ledger is live-bounded and, when submitted, is merged immediately
before that handler's buffered patches, events, or termination request. If the
handler throws or a later effect fails, the Root and public events still roll
back, while the admitted child-ledger gas and its exact ordered trace remain in
`totalGas`. This is the Contracts 1.0 §12.3 rule that deterministic failures
report all gas admitted before the failure.

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
type. Preview enum and method aliases were removed rather than carried into the
first public API.

## Removed API and replacements

This is an intentionally breaking pre-1.0 cleanup. The release does not retain
deprecated forwarding methods or compatibility-only carrier types.

See the
[final JVM API report](language-1.0-contracts-kernel-1.0-api-report.md)
for the exhaustive HEAD-to-final member inventory, replacement map, and
checked-in baseline mechanics.

| Removed preview API | Final API or migration |
| --- | --- |
| `Blue.reverse(Node/Object)` | Use `Blue.canonicalize(...)` for canonical identity input or `Blue.minimize(...)` for an author-facing minimized overlay. |
| `MergeReverser` and bare `reverse(...)` | Use `CanonicalIdentityInputBuilder` or `MinimizedOverlayBuilder`; the two operations no longer share an ambiguous name. |
| Candidate conformance identity/source constants | Use `FIXTURE_PACKAGE_IDENTITY`, `BLUE_SPEC_SOURCE`, and `CONTRACTS_FIXTURE_PACKAGE_IDENTITY`. |
| `Schema.get*Value()` numeric conveniences | Use the corresponding exact `BigInteger` getters, such as `getMinItemsExact()`. |
| Two-argument `SourceProviderEnvironment` construction | Supply the complete Language release, preprocessing, registry, and evidence identities. |
| Legacy trusted behavior behind `NodeProviderWrapper.unverified(...)` | The released descriptor remains for binary linkage, but now delegates to `wrap(...)` and verifies every provider leaf. `isExplicitlyHostTrusted(...)` remains linkable and always returns `false`; there is no trust bypass. |
| `ChannelDelivery`, `ChannelEvaluation.matchDeliveries(...)`, and `deliveries()` | Derive occurrences through `ExternalDeliveryPlan`/`VerifiedExecutionEvidence`; a channel evaluation is one `match(...)` or `noMatch()`. |
| Deprecated `ProcessorErrorCategory` aliases | Use the exact Contracts 1.0 diagnostic categories. |
| Anonymous `consumeGas(...)`, fixed gas additions, and fatal closeout shortcuts | Charge named child-ledger counters and submit the live-bounded ledger once. Submission merges it immediately into admitted run gas; later rollback still discards application effects, not that gas trace. |
| Committing fatal termination and fatal lifecycle aliases | Throw a deterministic runtime failure, or request ordinary graceful termination for a successful business effect. |
| `EmbeddedNodeChannel.childPath` | Use `sourcePath`. |
| Legacy checkpoint event maps/accessors | Use entries keyed by raw channel key with explicit domain and subject identities. |
| Legacy checkpoint pointer aliases | Use `relativeCheckpointEntry(...)`. |
| Legacy `ResolvedReferenceCache` alias/interner lane | Use verified canonical/resolved entries and structural interning. |
| `DocumentProcessingResult.triggeredEvents()` | Use `events()`. |
| Large mutable `Blue` service-locator surface | Use `BlueLanguage` for Language operations, `BlueContracts` for processing, or `BlueRuntime` when one owned composition root is useful. The retained `Blue` class is a thin 24-member convenience façade. |
| `blue.language.utils.BlueIdReferenceValidator`, `BlueIds`, identity-input builders, and identity helpers | Import the supported equivalents from `blue.language.identity`. |
| `blue.language.utils.MinimizedOverlayBuilder` and `MinimizedOverlayReconstructor` | Import the supported equivalents from `blue.language.resolve`; canonical identity construction remains in `blue.language.identity`. |
| `blue.language.utils.NodePathEditor` and `Nodes` | Import the stable value helpers from `blue.language.model`. `NodePathSelector` is internal; call `NodePathEditor.select(...)`. |
| `blue.language.utils.UncheckedObjectMapper` | Import `blue.language.codec.jackson.UncheckedObjectMapper` only when Jackson-specific integration is required; semantic code should normally use `BlueCodec`. |
| Public `blue.language.utils.limits.*` implementation classes | Use `ResolutionLimits` and its named factories/builder. Concrete stateful limit implementations are intentionally not API. |

Production source is guarded by build checks that reject new `@Deprecated`
declarations and ambiguous bare `reverse` semantics.

The checked-in `api/blue-language-java-1.0.json` file is the immutable
pre-modernization distribution baseline. Each published module owns its final
`api/public-api.txt` inventory, while
`api/modernization-api-migration-ledger-1.0.json` classifies the exact
baseline-to-final removals, relocations, and additions. `apiBaselineDiff`
protects each settled module surface and `verifySemanticApiMigration` proves
that the aggregate JVM delta is exactly the reviewed ledger—neither more nor
less.

## Repository-independent provider boundary

The Language API depends only on the generic `NodeProvider` contract. It does
not recognize a concrete repository, catalog artifact, or manifest.
`NodeProviderWrapper.unverified(NodeProvider)` is retained only as a binary
signature and delegates to the same strict direct-node verification as
`NodeProviderWrapper.wrap(...)`. Applications that explicitly admit authored
source documents use `ProviderEvidenceVerifier` with a fully bound
`SourceProviderEnvironment`.
`isExplicitlyHostTrusted(NodeProvider)` always returns `false`; no provider
entry point restores host-trusted evidence.

## Generic hosted-runtime extension boundary

Hosted runtimes now receive one processor-owned `RuntimeWorkSession` in each
deterministic processor phase. A runtime opens immutable, named counter
catalogs, charges live-bounded child ledgers before work, and submits their
ordered traces; the processor alone merges or discards them according to
success, deterministic failure, evidence suspension, or gas exhaustion.
Several independently named ledgers can additionally share one
invocation-owned `RuntimeWorkBudget`. Its weighted maximum is enforced before
child-trace or parent-reservation mutation, and exhaustion follows the same
structured session rejection path as the parent invocation limit.

Transient runtime output must cross `SemanticOutputBoundary`, which returns an
immutable `ExactBlueValue` and meters semantic construction and changed
identity work. External Channel payload and checkpoint-subject functions use
that boundary automatically. Executing handlers can additionally use
`SelectedExecutableBody` to open only verified references reachable from the
selected exact body.

Composite Channel implementations can declare a bounded subtype-family
dependency with `membersAssignableToType(...)`. Fragment splitters can consume
`ExecutableBodySourceDescriptor` to locate the exact owning contribution and
RFC 6901 pointer without rerunning overlay precedence or loading the body.

Contracts 1.0 §4.9 binds each Handler to exactly one same-scope channel key, and
§7.7 starts from an accepted raw source `channelKey`. Context-aware
`ExternalChannelSubscriptionFunctions.handlerChannelKey(...)` can select a
different frozen same-scope Handler channel, while
`logicalDeliveryKey(...)` can coalesce several fresh accepted sources into one
handler execution. Eligibility and checkpoint ownership remain attached to
the accepted raw sources; the target is neither evaluated nor checkpointed
unless it independently appeared as a source. This supplies the generic
Coordination routing boundary without restoring caller-authored
`ChannelDelivery`. Application parsing of `request.channel`, authorization,
and registry policy remain downstream responsibilities.

### Phase-B Channel dependencies

Source semantics and target proof are intentionally separate:

- `ExternalChannelMemberSnapshot` remains the surface for composing genuine
  External sources and their subscription/checkpoint functions.
- `ChannelMemberSnapshot` is a read-only frozen header for any effective
  External or processor-managed Channel. It exposes no acceptance,
  checkpoint, execution, or executable-body capability.

When the target key is fixed by the channel header, declare it with
`ExternalChannelFunctionContext.dependOnSameScopeChannel(key)`. When an event
may select any raw key, declare
`dependOnSameScopeChannelCatalog()` and use the event-only
`channel(key)` lookup. Every peer route must be covered by one of those
declarations; the source key alone is implicit.

The retained active interval carries the exact Channel entries and, for the
whole selector, canonical effective raw-key membership. Phase B rehydrates
only the source and declared Channel headers. Thus a dynamic lookup can
distinguish exact absence from a present non-Channel key without recognizing
that unrelated contract. Bodies remain collapsed. Catalog additions,
removals, retyping, ordering changes, contribution changes, or header changes
rotate the checkpoint-domain dependency and invalidate stale retained
evidence.

The selected target snapshot is frozen with classification and compared with
the fully preflighted Phase-C bundle before mutation. Selecting a target does
not evaluate it as an External source and does not create a target checkpoint.

### Composite and All channel dependencies

The generic External Channel SPI now exposes
`ExternalChannelFunctionContext`:

- `member(key)` resolves one required same-scope External Channel and records
  its exact, transitive header dependency;
- `members()` intentionally resolves and depends on the complete same-scope
  External Channel surface;
- `membersByEffectiveType(typeBlueId)` returns shallow immutable snapshots for
  one exact effective runtime-type family without recursively resolving other
  families;
- `matchesPattern(candidate, pattern)` is available only to event-evaluation
  functions and applies the frozen matcher through the captured verified
  processing-snapshot boundary.

The filtered view is the intended building block for an All-Timelines channel.
It avoids recursion between peer All channels and avoids making unrelated
External Channel types part of the All channel's domain. Its dependency retains
even an empty family selection, so adding the first matching member invalidates
the subscription. Matching member additions, removals, replacements, order
changes, contribution changes, and retyping likewise rotate the dependency.

Captured member and type-family identities are carried by
`SubscriptionDelta.Entry`, included in `CheckpointDomain`, compared when
retained subscription intervals are revalidated, and included in the sparse
Root projection used to verify feeder evidence. A same-key member replacement
therefore retires and re-adds the dependent composite snapshot even when its
union of subscription keys did not change.

This is runtime-neutral dependency context, not application-specific
Coordination behavior. A registered immutable function may route an accepted
occurrence through `handlerChannelKey(...)`; the default remains the
composite's own accepted raw channel key.

Pattern matching uses one fresh matcher cache for each of the two deterministic
function-evaluation passes. Nested selected-member evaluation shares the cache
only inside its current pass. At pass completion the cache is cleared and the
manager-backed materializer is severed, so a retained context cannot match.
Non-core pure references are obtained solely through
`ProcessingSnapshotManager.materializeVerifiedExactReference`;
unavailable, still-reference-only, or identity-mismatched provider results are
errors rather than a negative match. The context clones and freezes both
arguments. Header-time functions, including `channelKeys` and checkpoint-domain
derivation and event-time header consistency recomputation, cannot invoke the
matcher directly or through `ExternalChannelMemberSnapshot.evaluate(...)` and
cannot cause provider demand. Exact canonical multi-hop type lineage is
supported, but this event-scoped primitive does not preprocess or merge
canonical definitions whose constraints depend on the broader Language
resolution pipeline.

The same event-scoped context now exposes
`materializeExactReference(...)`. The default context-aware `eventKeys(...)`
uses it to project referenced `subscriptionKey` and `subscriptionKeys`
fragments, preserving inline/reference parity for the core finite-key
vocabulary. Application-specific projections—such as mapping a domain event
through a final Coordination registry—remain downstream policy. Header-time
materialization remains outside the generic kernel and fails closed.

### Timeline checkpoint subjects

`CHECKPOINT_SUBJECT` is an exact node, not necessarily a pure reference. A
Timeline runtime can return a minimal inline subject:

```yaml
timeline: <exact timeline identity>
timestamp: <exact comparable timestamp>
```

The runtime stores that exact inline node in the checkpoint entry.
`ChannelCheckpointContext.currentSubject()` returns the current frozen subject;
`lastEvent()` returns the exact prior subject defensively, materializing and
verifying a pure-reference subject lazily only when requested.
`eventSignature()` and `lastEventSignature()` expose the corresponding subject
BlueIds without forcing materialization. A Timeline `isNewerEvent(...)` policy
can therefore enforce its same-timeline rule with a strict timestamp increase.
Composite and All functions can return the selected member evaluation's
checkpoint subject unchanged, and their newness policy sees that exact current
and prior pair.

`VerifiedExecutionEvidence.eventOrderKey` continues to order feeder occurrences
and subscription activation intervals. It is not per-channel checkpoint
newness evidence and must not replace the Timeline comparison.

## Refactored class map

The kernel retains one processing algorithm and separates its phase ownership
as follows:

| Owner | Final responsibility |
| --- | --- |
| `ProcessorEngine` | Top-level admission, one invocation run state, phase sequencing, result and diagnostic selection. |
| `ProcessingDocumentValidator` and `RootExternalDeliveryEvidenceVerifier` | Raw document admission and independent revision-bound feeder-evidence verification. |
| `ScopeExecutor` | Participating-scope preflight, initialization, external/internal delivery, cascades, cut-off, and quiescence. |
| `TerminationService` | Deferred graceful-termination lifecycle and marker completion. |
| `DocumentProcessingRuntime` | The invocation’s semantic reads, persistent mutation state, Document Updates, event queue, lifecycle writes, and work ledger. |
| `ImmutablePatchPlanner`, `PatchPlanningEngine`, and `BatchPatchTransaction` | Immutable patch planning, state-aware sequential planning, atomic commit/rollback, and changed-spine rebuilding. |
| `WorkingDocument` | Noncommitting read-your-writes previews over the same immutable patch machinery. |
| `ContractLoader` and `ContractContributionResolver` | Type recognition, must-understand enforcement, frozen effective snapshots, ordered Source contributions, dispatch projection, and selected body admission. |
| `ExternalChannelFunctionResolver`, `ExternalChannelFunctionContext`, and `ChannelMemberSnapshot` | Deterministic immutable source functions, exact/whole same-scope Channel-header declaration, Phase-B target lookup and freezing, External member/type-family composition, event-scoped verified matching, and checkpoint-domain contribution. |
| `EffectiveFragmentationCatalogBuilder` | Read-only effective `Process Embedded`, header, contribution, and executable-body-boundary inspection through the verified snapshot context. |
| `DeclaredTypeLineageMatcher` | Exact declared-type ancestry matching without structural guesses. |
| `ProtectedStateGuard`, `TypeGeneralizationPolicyResolver`, and `DirectSubscriptionSurfaceValidator` | Precommit protected-state, generalized-type, and subscription-surface validation. |

These collaborators narrow ownership without introducing a second processor,
child commit path, or alternate semantic result.

## Processor-managed writes

Application patches and generated type-generalization writes create Document
Updates. Direct initialized-marker, checkpoint, checkpoint-cleanup, and
terminated-marker writes do not. Lifecycle channels are the observation
surface for initialization and graceful termination.

All application effects are tentative until a successful result. Persistent
mutation rebuilds the changed direct container and ancestor spine while
retaining unchanged exact children by BlueId. Protected effective state,
active-scope cut-off, direct-container limits, and changed subscription surface
are checked before commit. Portable gas already admitted to the live invocation
meter is reported even when those effects roll back.

## Conformance artifacts

The vendored Language and Contracts fixture packages are exact copies of the
baseline packages. Their manifests are authoritative closed inventories.
Unknown operations, controls, projections, assertions, counters, and fixture
fields fail closed.

The machine-readable implementation report records the release and package
identities above plus one pass/fail entry for every manifest-listed fixture.
There is no skip status.

### Canonical fixture-envelope clarifications

The final canonical package is retained byte-for-byte. Two envelope spellings
need narrow runner normalization because the specifications and registry remain
normative:

- `c-feed-14`, `c-feed-15`, and `c-feed-17` give both tied source Channels
  effective `order: 0`, while their compact hints spell the second tied
  occurrence as `order: 1`. The runner keeps the derived delivery order at
  zero and accepts the redundant hint only as the stable ordinal within that
  same-scope, same-order tie. A value outside that exact tie ordinal still
  fails closed as an order mismatch.
- `c-cyc-04` combines scalar shorthand `value: 0` with the authored `cyclic`
  object edge. Before strict Language decoding, the conformance runner promotes
  that scalar into its existing private fixture field and rewrites the
  fixture-authored `/value` patch to the private field. Production Blue
  decoding, processing, and patch admission are unchanged; the final cyclic
  member remains an opaque exact edge.
- `c-fail-05` sets an invocation limit of 500 gas but omits initialized state,
  while the bound gas manifest charges 1000 for `scopeInitialization`. The
  runner treats the fixture's declared `C-LOOP-01` scenario as preinitialized,
  adding a final-form marker whose `document` is a pure reference to the exact
  pre-initialization Root. This lets the published limit exercise the intended
  internal-event cycle; ordinary PROCESS inputs still pay initialization gas.

The final identity-bound packages produce 153/153 Language passes and 154/154
Contracts passes (96 behavior and 58 gas fixtures). The combined release report
contains exactly 307 unique results: 307 `PASS`, zero `FAIL`, and zero skipped.

Thirteen prior Contracts failures were corrected in the fixture package because
their old inputs or assertions did not describe executable normative scenarios:

```text
c-disc-04  c-disc-05  c-e2e-02  c-emb-02  c-emb-07
c-evt-01   c-evt-03   c-life-03 c-prot-02 c-rep-04
c-upd-01   c-upd-02   c-upd-03
```

No implementation exception remains for those IDs. `c-snd-04` exposed the one
runtime defect: traversal strictly below a pure cyclic-set member reference is
now rejected before provider demand with
`CyclicSetMutationUnsupported`. Replacing the whole reference remains an
ordinary patch operation.

Run the strict gate with:

```bash
BLUE_RELEASE_EPOCH="$(git show -s --format=%ct HEAD)"
SOURCE_DATE_EPOCH="$BLUE_RELEASE_EPOCH" ./gradlew clean build
SOURCE_DATE_EPOCH="$BLUE_RELEASE_EPOCH" ./gradlew rcVerify
```

It validates the exact package identities, executes every fixture, rejects any
failure or unexecuted case, verifies API and archive reproducibility, and
writes the machine-readable release evidence.

This repository deliberately does not implement application-specific
Coordination parsing, authorization, registry policy, Timeline-provider
persistence, feeder databases, or BEX/expression evaluation. It does provide
the generic same-scope handler-selection and logical-delivery coalescing
boundary that such a runtime can register.
The generic named child-ledger API is complete here. BEX 2.0 integrations bind
their named live counter stream through this Language-owned boundary; each
downstream release must validate the exact compatible artifact before claiming
the resulting Contracts 1.0 runtime ledger.
