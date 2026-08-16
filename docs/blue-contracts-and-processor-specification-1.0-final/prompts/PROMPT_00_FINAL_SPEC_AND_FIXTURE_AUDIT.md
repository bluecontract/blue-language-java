# Codex Prompt 00 — Freeze Audit for Blue Contracts and Processor Specification 1.0

You are performing the final pre-implementation audit of the normative package in this archive.

This is **Blue Contracts and Processor Specification 1.0**. There is no previously published 1.0 and there must be no 1.1 rename. The specification and fixture corpus in this package are the implementation target. Do not redesign the architecture, invent a Coordination profile, add Mandate semantics, or alter existing core type nodes merely to make an implementation easier.

## Inputs

Treat these files as authoritative:

```text
specifications/blue-contracts-and-processor-specification-1.0.md
conformance/contracts/registry/
conformance/contracts/gas-manifest.yaml
conformance/contracts/identity-constructors.yaml
conformance/contracts/fixtures/
conformance/contracts/oracles/
conformance/contracts/release-manifest.yaml
```

Treat these files as informative implementation aids:

```text
examples/CYCLIC_PROCESSING_EXAMPLES.md
SPEC_FINALIZATION_NOTES.md
MANDATE_LAYER_COMPATIBILITY.md
java-templates/
tools/
```

The central semantic decisions are already made:

```text
1. Process Embedded is the only authored processing graph.
2. The graph may be finite and cyclic.
3. Acyclic regions use deepest-first processing.
4. Strongly connected regions are one cyclic processing component.
5. One affected-closure invocation owns one frozen frame, one work identity
   domain, one shared gas meter, and one atomic publication result.
6. After every identity-affecting transition, the complete changed cyclic
   component and required containing reference spine are tentatively finalized
   before later work may observe them.
7. Application code sees exact temporary Blue values and BlueIds. Invocation-
   local handles may exist only below the semantic boundary.
8. Dynamic cycle formation, component merge, component split and closure
   expansion preserve already-completed direct work, accepted gas, event
   ordinals, the queue, and the original cause.
9. ADMIT_CLOSURE is a first-class processor-managed operation and does not
   fabricate a Timeline Entry or provider timestamp.
10. PROCESS_CLOSURE may contain multiple cyclic and acyclic components and
    returns every resulting component and containing document.
11. Stable DocumentId and managed occurrence bindings are exact platform
    evidence, not Blue Language primitives and not a second authored graph.
12. Graph changes are ordinary Blue patches. No componentPatches or magic host
    mutation protocol exists.
13. A release-default finite gas policy is mandatory. Documents and hosts may
    lower it only; embedded/member work never gets an independent fresh meter.
14. Mandate resolution and delegated-authority eligibility are outside this
    specification and belong to a later Coordination feeder/profile.
15. `closureIdentity` identifies authoritative durable closure state only.
    Cause and frozen direct-delivery evidence are bound once by
    `invocationIdentity`, not persisted as closure state.
16. One closed `ClosureInvocationInput` owns the state snapshot and every
    invocation adjunct. A processor API must not accept duplicate cause, event,
    route, execution-policy, or environment sources.
```

## Required audit

### 1. Normative-text closure

Read the complete Contracts specification, not only the new cyclic sections. Prove that no surviving clause contradicts supported cycles, closure-level atomicity, exact temporary identity visibility, dynamic formation, split, admission, or the frozen gas policy.

Search at minimum for variants of:

```text
no declared embedded ancestry cycle
reject an embedded ancestry cycle
cyclic member mutation is rejected
finalize after quiescence
PROCESS_COMPONENT
componentPatches
runtime-failure
invalid-component
gas-exhausted
provisional
pending calibration
126 vectors
Mandate
onBehalfOf
```

A clause rejecting an ordinary isolated mutation of an opaque cyclic-set member is valid. A universal prohibition on a verified bounded cyclic closure is not.

### 2. API/result completeness

Confirm the normative API can represent all of these without hidden harness fields:

```text
ordinary PROCESS
PROCESS_CLOSURE starting acyclic and becoming cyclic
ADMIT_CLOSURE with zero external direct deliveries
multiple SCCs in one affected closure
one cyclic component splitting into acyclic and/or cyclic components
merging two existing cyclic components
changed acyclic containing Roots
all resulting exact documents
all resulting component proofs and identities
all active and inactive prospective occurrence bindings, activation
generations, and nullable historical catch-up cursors
public Root events only
one gas trace and rejected next charge
one atomic commit payload
```

The normative result must not assume that one final master BlueId exists after a split.

### 3. Exact identity semantics

Confirm that the specification defines what later work reads after every identity-affecting transition:

```text
A changes
    -> re-finalize complete affected cyclic component tentatively
    -> later B work reads the exact new A BlueId and exact new B-internal link
B changes
    -> re-finalize again
    -> later A work reads the next exact state
```

No application-visible value may be an identity-less or virtual component handle. Any internal handle must resolve before a Handler, schema, event construction, patch value, BlueId read, equality operation, or provider demand can observe it.

Also verify the three identity layers cannot be conflated:

```text
closureIdentity:
    graph generation + exact document records + complete occurrence-binding
    set + exact component states + public Root set

invocationIdentity:
    input closure state + exact cause + frozen direct-delivery snapshot +
    candidate + execution/environment identities

historical resource retry:
    additional exact transition evidence preserves invocationIdentity only
    while the state, operation, cause, admission candidate, route, execution
    policy and environment all revalidate unchanged; consumed transitions
    remain bound by transition/work identities and completed result evidence
```

Reject a constructor or storage adapter that includes cause or direct-delivery
identity in durable `closureIdentity`, persists an attempt-scoped hash as the
current state, trusts a caller-supplied closure digest without recomputation, or
accepts duplicate API arguments that could disagree with the closed invocation
input.

### 4. Work and order identities

Verify exact constructors and total ordering exist for:

```text
DocumentId
ManagedScopeKey
ManagedOccurrenceBinding / stable occurrence identity / exact-state binding identity
TransitionId
EventOccurrenceId
WorkOccurrenceId
ComponentIdentity
ComponentGeneration
ActivationGeneration
GraphGeneration
```

Confirm the comparison rules do not use Java insertion order, authored map insertion order, current BlueId lexical order, thread scheduling, cache state, or admission order.

DocumentId must be nonempty NFC-normalized Unicode, case-sensitive, unique in the selected managed-environment domain, and compared by Unicode scalar/code-point sequence.

### 5. Queue semantics

Confirm the spec unambiguously defines:

```text
ExternalDelivery
InitializationDelivery
DocumentUpdateDelivery
TriggeredEventDelivery
EmbeddedEventDelivery
LifecycleDelivery
HistoricalTransitionDelivery
ContainingReferenceUpdate
```

`PatchContinuationFrame` and tentative-finalization boundaries are synchronous
control frames owned by the current work occurrence, not queued work variants.
Only an actual matching Document Update delivery becomes a queued occurrence.
Verify that this distinction is reflected consistently by the schema, fixtures,
gas trace, templates, and implementation prompt.

For one Handler result containing several patches, each patch and its immediate Document Update cascade must occur at the exact specified continuation point. A fixture implementation must not be allowed to choose whether all patches happen before updates.

### 6. Frozen-edge semantics

Confirm the mandatory distinction:

```text
edge retirement:
    does not cancel an already-created occurrence whose frozen eligible lineage set
    included that edge

receiver termination or existing scope cut-off:
    may suppress work only under the exact existing termination/cut-off rule

new occurrence after removal:
    uses the new graph

remove and re-add:
    creates a new activation generation; the old cursor/lineage is not reused
```

### 7. Gas and limits

Confirm all numeric weights and limits are final and exact. Verify:

```text
maxProcessGas = 100000
release default applies when no authored/host policy exists
host/document override may lower only
one shared meter covers Root, embedded and cyclic members
member-local cap uses the same shared meter
next charge is admitted before work
rejected charge is absent from the trace
wall-clock timeout is nonsemantic
```

Confirm every closure counter and limit has an owner, scope, increment point, rejected-step behavior and failure precedence.

Also prove that cyclic canonical-byte accounting is invariant under equivalent
inline versus exact-reference child representation, that changed acyclic
containing spines are charged incrementally without double charging an already
established patch value, and that tentative-finalization gas is interleaved
immediately after the identity-changing work.

### 8. Fixture semantic integrity

Do not accept vector-name presence as proof. For every closure fixture:

- validate against `closure-fixture-schema.yaml`;
- ensure every active Process Embedded edge exists at the exact source document path;
- ensure that source document declares that path through the exact current `Process Embedded` type;
- ensure direct deliveries name real `ScriptedExternalChannel` contracts, not Handler keys;
- ensure runtime handler keys name real `ScriptedHandler` contracts and their bound Channel exists;
- validate every BlueId grammar;
- independently recompute cyclic-set oracles with `tools/blue_identity.py`;
- prove candidate ambiguity from member bodies instead of trusting an input failure label;
- independently execute or stream every normative boundary generator and prove
  each named guard measures the advertised at-bound/first-above value; separately
  prove the executable 128/129-member ring fixtures contain their advertised
  complete documents, edges, identities, and proofs;
- prove merge and split fixtures assert complete final partition and identities;
- prove suffix-remap fixtures include an outside reference when claiming outside-reference correctness;
- prove gas fixtures freeze the accepted trace, total, rejected next work identity and retry parity.

### 9. Exact flagship scenarios

The following must be represented by real fixtures and independent constants:

```text
Dynamic finite A <-> B:
    initial graph only B -> A
    A external Handler adds A -> B through an ordinary patch and emits X
    A local X Handler runs
    B embedded X Handler runs and emits Y
    A embedded Y Handler runs and finishes
    exact temporary cyclic identities after every state change
    one final commit

Same-event infinite loop:
    A and B emit the same exact LOOP value
    event occurrences remain distinct
    default release policy stops deterministically
    full rollback and retry-identical trace

A epoch 10, B attaches A epoch 5:
    without exact revision evidence -> NeedsResources
    with exact A5->A10 chain -> apply in order without downgrading A
    if a cycle closes, re-finalize after every identity-affecting application

Dynamic cycle during initialization:
    cycle is not already present in the input graph
    an ordinary initialization Handler patch introduces the reciprocal edge

Merge and split:
    two existing cycles merge
    one cycle splits into two cycles
    one cycle splits into a smaller cycle plus an acyclic singleton

Frozen edge mutation:
    work before removal retains old targets
    work after removal uses new graph
    remove/re-add receives a new activation generation
```

### 10. Mandate boundary

Verify the normative Contracts specification, registry and fixtures contain no Mandate-specific semantics, `onBehalfOf` processing, Mandate resolver, Mandate status, authority-holder rule, or Coordination eligibility outcome.

The generic processor must remain capable of processing any exact Blue document whose runtime types are registered. A later Coordination feeder can resolve Mandate evidence before constructing the frozen direct-delivery snapshot. That does not require a Contracts revision.

## Deliverables

Produce:

```text
build/reports/contracts-1.0-spec-audit.json
build/reports/contracts-1.0-spec-audit.md
```

The report must list:

- every normative source hash;
- the exact `identity-constructors.yaml` SHA-256 and its normative status from
  the release manifest;
- every vector and fixture count;
- every independent oracle verified;
- every static rule checked;
- every warning or contradiction;
- the exact final result: PASS or BLOCKED.

Do not claim implementation conformance. This audit proves only the normative package and independent reference scenarios.

If a real contradiction remains, stop. Provide the exact section, fixture, minimal example and least invasive correction. Do not silently change semantics.
