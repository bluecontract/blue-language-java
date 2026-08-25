# Blue Contracts 1.0 fixture harness

## 1. Purpose and authority

The harness executes the normative ordinary envelope
`blue-contracts-fixture/1.0` and the affected-closure envelope
`blue-contracts-closure-fixture/1.0`.

These files are executable conformance data, not scenario sketches. A conforming runner MUST implement every operation, control, preparation rule, projection, and assertion defined by:

```text
fixture-schema.yaml
closure-fixture-schema.yaml
CONTROL-LANGUAGE.md
TRACE-SCHEMA.md
projection-catalog.yaml
```

Unknown data fails closed. A fixture runner MUST NOT invent semantics for a field, treat a label as a transformation, or mutate the expected result directly.

## 2. Exact inputs

After deterministic builders are applied, `input.root` and `input.event` are exact Blue Language 1.0 values. Every pure reference uses a canonical plain BlueId. Every provider node is validated as BlueId Input and MUST hash to its map key.

The semantic operation remains:

```text
PROCESS(root, event) -> ProcessResult
```

Feeder evidence, provider evidence, runtime registration, cache state, and fixture controls are execution-environment data. They are not a hidden third semantic input and cannot select behavior inconsistent with the exact Root, event, and registered runtime laws.

## 3. Operations

### 3.1 `process`

Execute one atomic `PROCESS(root, event)`. Its public projection is exactly:

```text
result.status
result.document
result.events
result.totalGas
result.diagnostic?
```

`result.document` is the resulting authoritative Root. `result.events` is an out-of-band ordered sequence of exact events emitted by Root only. It is not itself a Blue List node. Internal descendant events and Document Update payloads appear only in conformance traces.

### 3.2 `process-attempt`

Execute:

```text
PROCESS_ATTEMPT(root, event, verifiedEvidence)
    -> Complete(ProcessResult)
     | NeedsResources(canonicallyOrderedTypedDemands,
                      sortedExactBlueIdProjection)
```

A `NeedsResources` result is represented under `attempt.*`. It has no `ProcessResult`, no committed state, no Root events, no progress, and no portable gas. A fixture MUST NOT encode `needs-resources` as `result.status`.

### 3.3 `platform`

Execute the explicitly declared managing-feeder behavior around two-input `PROCESS`: revision barrier, canonical preselection, activation intervals, event ordering, revision-bound terminal progress, compare-and-swap, quarantine, subscription delta, and Root outbox commit.

A platform fixture cannot create an alternative processor result. Whenever semantic processing occurs it invokes the same `PROCESS(root,event)` operation.

### 3.4 `gas-micro`

Evaluate one exact named counter or one formula declared by the bound Contracts gas manifest. No document processing is implied unless Root/event/runtime fields are also supplied. Trace entries and arithmetic are exact.

### 3.5 `process-closure` and `admit-closure`

Execute the one closed `ClosureInvocationInput` under the operation/cause
coupling in the closure schema. `process-closure` accepts an external or
`managed-revision` cause; `admit-closure` accepts an admission cause. Admission
and managed revision have empty direct-delivery snapshots. Both operations
return exactly `Complete(ClosureProcessResult)` or
`NeedsResources(canonicallyOrderedTypedDemands,
sortedExactBlueIdProjection)`.

One `ManagedRevisionCause` is one invocation and one
`CONTAINING_REFERENCE_UPDATE`; the runner MUST NOT pass a transition list or
construct a historical queued work kind. A sequential A5→A10 campaign is six
commits after the initial missing-resource attempt: the external A5 attachment,
then five separately metered/committed one-step revision invocations.

## 4. Fixture controls

`CONTROL-LANGUAGE.md` defines every builder, provider, runtime, feeder, and variant control. Important constraints are:

- scripted runtime results are returned only by an actually selected runtime contract;
- cascades and cut-off scenarios are installed as ordinary fixture handlers, never host mutations;
- `input.feeder.deliverySnapshot` is compact fixture shorthand and MUST be expanded and verified as the complete normative `ExternalDelivery` snapshot;
- representation variants transform exact preparation only and cannot alter semantic values;
- every variant is an object that explicitly names its transformation; a bare variant label is invalid.

For an affected-closure fixture the following are closed **top-level harness
fields**, not members of normative `input` or production `expected` result:

- `runtime`: scripted Handler/initialization behavior;
- `sharedLimitSource`: release-default versus explicit fixture-override
  provenance for the normative gas-policy value;
- `provider`: exact available nodes plus expected exact requests/loads;
- `locality`: unrelated-document setup and expected open count;
- `limit`: invocation probe or limit-micro generator;
- `oracle`: oracle path plus component-state/finalization-stage routing.

An availability-only provider retry preserves both normative
`inputClosureIdentity` and `invocationIdentity`. The authoritative
`resourceDemands` list contains exact-node and/or exact managed-occurrence
evidence demands in canonical source order; its legacy `requiredBlueIds`
projection contains exact-node demands only and can be empty. A runner must
never turn either form into a range query or historical discovery operation.
Resolving an occurrence-evidence demand retains the authoritative document
heads and logical cause but constructs a new closed occurrence set, so the
occurrence-set, input-closure, and invocation identities are recomputed. A
byte-equal automatically derived prospective row and explicit prospective row
must then produce byte-equal resolved invocation input and execution.
Oracle stage labels never appear in component or finalization production API
records.

Prospective occurrence rows are closed invocation evidence except for the exact
inactive successor derived when an active row retires. When exact resulting
content lacks exact-node or prospective-row evidence, the harness expects the
authoritative typed demand rather than a hidden row or invalid-surface shortcut.
Every resulting Root's complete Process Embedded surface is projected and all
demands are aggregated before any Root is reconciled.

A pending-null row's declared path may be absent; a historical cursor value may
be present while the row remains inactive. Root managed-scope generation is 0,
and the first embedded reservation/activation is 1. Active removal allocates
its same-lineage successor at exactly generation plus one; that successor is
output-only until committed and supplied as a later invocation input, whose
re-add preserves its generation and occurrence identity. An inactive row cannot
retarget. An active different-lineage `REBIND` is atomic: it retains source path
and binding policy, retires the old lineage, changes target DocumentId, uses the
next generation and fresh occurrence/binding identities, advances the active
graph generation, and does not redirect frozen old-lineage work.
Same-invocation remove-then-re-add remains unsupported. Pure references,
verified inline acyclic values, and verified materialized cyclic members are
exact parity variants; mixed `blueId` objects are invalid.

Direct initialized, terminated, and checkpoint state plus the effective
generalization policy are protected. Workflow, Handler, Operation, Channel,
lifecycle, actor-policy, and the complete Process Embedded declaration are
application-owned; their mutation is admitted only through frozen-current-work
and complete post-write reconciliation.

### 4.1 Portable contract-evolution family

`C-EVO-*` is the release fixture family for application contract-surface
evolution. Ordinary vectors use the exact `PROCESS(root,event)` harness;
closure vectors use the production closure/admission harness. No
evolution-specific host mutation is permitted.

| Vector | Portable proof |
|---|---|
| `C-EVO-01` | A selected Workflow removes itself after its frozen current delivery. |
| `C-EVO-02` | A selected distinct Scripted Operation removes itself after its frozen invocation. |
| `C-EVO-03` | A newly added Operation does not receive its creating entry. |
| `C-EVO-04` | Channel removal and re-add retire the old interval and create fresh lineage. |
| `C-EVO-05` | Required-Workflow removal selects the nearest valid ancestor. |
| `C-EVO-06` | Frozen reject policy rolls the mutation back completely. |
| `C-EVO-07` | Initialized, checkpoint, and terminated processor state is protected. |
| `C-EVO-08` | Whole-Root/contracts replacement preserves the initialized marker and its exact identity. |
| `C-EVO-09` | A complete Process Embedded declaration can be removed atomically. |
| `C-EVO-10` | A former embedded child remains passive application content. |
| `C-EVO-11` | Removing an edge dissolves a two-member cycle. |
| `C-EVO-12` | Edge removal splits one component into two cycles. |
| `C-EVO-13` | Closed prospective evidence permits reciprocal cycle formation. |
| `C-EVO-14` | A generated type write retains exact causal attribution. |
| `C-EVO-15` | Generalization removes a subtype-only Channel. |
| `C-EVO-16` | Generalization removes a subtype-only Process Embedded declaration. |
| `C-EVO-17` | A direct Process Embedded addition and generalization reconcile in one transition. |
| `C-EVO-18` | Missing exact child content yields an exact-node demand. |
| `C-EVO-19` | Known content without a frozen row yields an occurrence-evidence demand. |
| `C-EVO-20` | Mixed demands use source order, never demand-kind grouping. |
| `C-EVO-21` | Unchanged retries reproduce demands, tentative effect prefix, and completed evidence. |
| `C-EVO-22` | Expanded occurrence evidence can reach metered work and still roll back completely on gas failure. |
| `C-EVO-23` | Automatic-demand retry and explicit prospective evidence produce byte-equal resolved execution. |

The harness-owned `ScriptedOperation` is a distinct fixture subtype of
`Handler`, loaded beside (and excluded from) the frozen production runtime
registry. It receives the ordinary frozen selection and
`ProcessorExecutionContext`, and can apply only its declared
`ContractExecutionResult`. It is not a substitute for a downstream
Coordination implementation. The intentionally deferred inherited
Process-Embedded reveal scenario is not part of this family.

## 5. Canonical delivery derivation

For each delivery hint, the runner MUST derive and retain:

```text
scopePath
channelKey
orderedSourceContributionNodeBlueIds
effectiveTypeBlueId
order
checkpointDomainBlueId
```

It MUST verify:

1. `scopePath` is exactly `/` and its activation generation is exactly `0`;
2. the selected managed Root exists as an object and is not terminated;
3. the effective contract at `channelKey` is an External Channel;
4. any asserted `order` and activation frontier agree with the derived state;
5. the complete ordered hint set equals the canonical preselected occurrence set for the fixture.

The compact hints do not substitute for missing identity fields and are never passed to application contracts.
The Contracts 1.0 affected-closure harness is deliberately Root-scoped. It
MUST reject a non-Root direct delivery, WorkOccurrence, ChannelOccurrence,
subscription delta, checkpoint receipt, or scoped gas context rather than
silently applying ordinary `PROCESS` nested-scope behavior. Nested exact
content and `Process Embedded` occurrence paths remain valid graph and patch
evidence; they are not independent closure work scopes.

## 6. Projections

The only legal assertion paths are listed in `projection-catalog.yaml`.

Projection families are:

- `result.*`: public `ProcessResult`;
- `attempt.*`: alternate `PROCESS_ATTEMPT` result;
- `trace.*`: canonical semantic and gas trace;
- `demands.*`: logical semantic demands;
- `feeder.*`: canonical feeder derivations;
- `commit.*`: revision-bound persistence decision;
- `platform.*`: platform terminal state;
- `variants.*`: exact named variant outputs.

A dot path selects an object field. Decimal segments select list positions. Braced paths such as `result.{status,document,events,totalGas}` select the fields in the written order. A missing declared projection fails the fixture unless the assertion operator is `absent`.

## 7. Assertions

Supported operators are:

- `equals`, `notEquals`: exact semantic equality or inequality;
- `equalsProjection`: compare `actual` with the projection named by `expectedProjection`;
- `absent`, `present`: exact projection absence or presence;
- `sequenceEquals`: ordered equality preserving duplicates;
- `contains`, `notContains`: containment in the selected value;
- `lessThan`, `greaterThan`: exact numeric comparison;
- `sameAcrossVariants`: exact equality across every declared variant;
- `failsWith`: exact deterministic diagnostic category;
- `all`, `none`: universal or empty predicate over the selected projection.

A string written in `expected` is always a literal string. Projection comparison MUST use `expectedProjection`; implicit strings such as `input.root` are forbidden.

`ordered: true` requires the expected elements to occur in the listed relative order. `variant` restricts an assertion to the named exact variant.

## 8. Trace model

`TRACE-SCHEMA.md` defines the canonical named entry, logical demand record, and every derived trace. `trace.namedEntries` is the authoritative gas trace. The weighted sum MUST equal `result.totalGas`.

A runner MAY retain richer implementation diagnostics, but fixtures cannot observe them unless they are normalized into a catalogued projection. Host stack traces, object identities, thread schedules, cache hits, and physical provider details are nonportable.

Every closure public-event record contains both ordinals:
`publicEventOrdinal` is contiguous in the Root-only public projection, while
`eventOccurrenceOrdinal` is the invocation-global emission ordinal bound by
`eventOccurrenceIdentity` and may contain public-projection gaps.

## 9. Failure and rollback

Unknown fields, invalid exact Blue nodes, provider mismatch, malformed delivery hints, unsupported controls, unsupported operations, undeclared projections, invalid assertion shapes, or disagreement among prose, registry, gas manifest, and fixture package are harness failures.

A Contracts deterministic failure follows the specification’s atomic rollback rules. A fixture control never authorizes partial host-side result construction.

## 10. Package integrity

`manifest.yaml` is the authoritative inventory for this fixture package. It lists every behavior fixture, gas fixture, and support file with its relative path, role, LF-normalized byte length, and SHA-256 digest. It binds the exact Contracts runtime-registry package identity, gas-manifest package identity and file digest, and vector-coverage map.

The fixture-package identity is calculated as:

```text
sha256(
  UTF-8 canonical JSON of manifest.yaml
  with packageIdentity set to null
  and object keys sorted lexicographically
)
```

The package validator MUST check:

- JSON-Schema closure of every fixture;
- exact Blue-node validity before BlueId calculation;
- delivery-hint derivability for statically available fixture Roots;
- projection-catalog and control-language closure;
- completed status vocabulary and attempt-result separation;
- vector coverage;
- runtime-registry and gas-manifest identities;
- fixture-package and release-manifest identities.

A fixture, support file, gas schedule, registry dependency, or coverage-map change requires a new fixture-package identity. The registry manifest's reverse fixture binding is excluded from the registry package identity to avoid an identity cycle.


### Collection-derived embedded scopes

For `Process Embedded.collectionPaths`, the harness MUST enumerate the complete direct ordinary key set of each present object-compatible collection in Unicode code-point order. It MUST derive one concrete scope path per direct key using Runtime Pointer escaping. Lists are not collection targets, wildcard syntax is invalid, and no path may traverse `contracts` or another reserved Language field.

The compact delivery hints always name concrete scope paths such as `/lessons/lesson-17`; they never name a wildcard or collection selector.
