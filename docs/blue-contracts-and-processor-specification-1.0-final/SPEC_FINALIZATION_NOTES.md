# Contracts 1.0 Finalization Notes

This document records how the final Contracts 1.0 package closes the major
review findings. It is informative; the specification and fixture manifests
are normative.

## Final decisions

1. The release remains **1.0**. There is no published earlier Contracts 1.0 to
   preserve and no 1.1 label in this package.
2. Blue Language and the existing core runtime type nodes are unchanged.
3. `Process Embedded` is the only authored processing graph.
4. Bounded finite cycles are supported through complete closure processing.
5. The whole affected cyclic component is tentatively re-finalized after every
   identity-affecting transition, before later work observes it.
6. Invocation-local handles may optimize implementation but are never
   application-visible Blue values.
7. `PROCESS_CLOSURE` can begin acyclic, form/merge/split components, expand the
   closure, and continue without replaying completed direct work.
8. `ADMIT_CLOSURE` supports initialization without an external event.
9. `ClosureAttemptResult` separates `Complete(ClosureProcessResult)` from
   `NeedsResources`. A completed result represents every final acyclic/cyclic
   component state and proof, changed document, containing Root, occurrence
   binding, graph change, event occurrence, checkpoint, subscription delta,
   gas result and, on commit, exact commit companion.
10. Stable `DocumentId` and managed occurrence bindings are exact platform
    evidence, not Blue Language primitives and not a second authored graph.
    Stable occurrence lineage identity excludes the current target BlueId;
    state-specific binding identity includes it. Every domain-separated
    platform `sha256:` constructor uses the uniform `{domain,value}` RFC 8785
    envelope; the exact checkpoint-domain BlueId is the explicit direct Blue
    Language constructor recorded in decision 25.
11. Direct delivery, scope, transition, event, work, graph, component and
    activation identities have deterministic constructors and complete keys.
    Stable `componentIdentity` binds partition/generation; exact
    `componentStateIdentity` separately binds member BlueIds, nullable master
    and nullable cyclic-proof identity.
12. Frozen edge behavior is mandatory; edge retirement, termination and scope
    cut-off are distinct. Created work freezes eligible stable lineages and
    creation-time binding evidence, then reads the latest exact tentative state
    of those lineages when delivered.
13. A release-default finite gas policy is mandatory. Authored and host limits
    may only lower it and share the same meter. The gas trace field registry
    includes managed document, activation, component and work occurrence
    identity where applicable. Rejected caps use a structured shared/local
    object, and rejected-charge ownership distinguishes invocation, queued work
    and finalization boundaries. A finalization owner includes the required
    invocation-global `finalizationOrdinal` as well as stable component identity
    and generation, so repeated tentative finalizations of unchanged lineage
    remain distinguishable.
14. `NeedsResources` is the non-completed branch of `ClosureAttemptResult`; it
    carries no completed processor status, gas evidence or commit companion.
15. Invocation and commit evidence bind exact Language and Contracts
    specification identities, runtime registry, gas manifest, finalizer,
    verifier, provider/order/identity policies and portable-limit policy without
    introducing a fixture/release hash cycle. Implementation artifact identity
    remains separate conformance metadata.
16. Authority/delegation semantics are excluded from Contracts and remain a
    later Coordination feeder concern.
17. Patch continuations and finalization boundaries are synchronous control
    frames, not queued work occurrences. Only actual matching Document Update
    deliveries—not patch frames—pay update-related closure queue charges; every
    queued work kind pays its own applicable enqueue/dequeue charges.
18. Cyclic canonical-byte limits are measured on a representation-normalized
    form. Exact acyclic identity work is incremental and tentative cyclic
    finalization gas is interleaved at the identity-changing work boundary.
19. Boundary microfixtures prove each named safety guard independently; they do
    not falsely claim a full at-bound invocation can also finish below the
    independent 100000 shared gas ceiling.
20. Platform constructor integers use the RFC 8785/I-JSON safe range
    `0..9007199254740991`; no implementation may round a larger platform
    generation, ordinal, epoch or count.
21. Closure snapshots and results use closed records. Managed documents bind
    initialized, terminated, public-Root, epoch and component-generation state;
    committing results expose full structured effects and their exact sequence
    identities rather than hidden harness fields.
22. Closure-fixture inputs carry required nullable `admissionCandidate` and
    `admissionCandidateIdentity` fields. A non-null value is one closed branch:
    `BAD_CYCLIC_PROOF`, `AMBIGUOUS_PRELIMINARY_MEMBERS`, or
    `INVALID_OCCURRENCE_BINDING`. Its exact `{kind,evidence}` identity is bound
    into the invocation, but the candidate is untrusted audit evidence and never
    creates authoritative proof, membership, occurrence, or graph state.
23. Initialization marker publication is whole-component atomic. Every missing
    member's exact pre-initialization BlueId is frozen before component lifecycle
    work, all missing direct markers are installed together after the batch's
    causal work drains, and one immediate exact finalization pass covers each
    changed resulting component and containing spine.
24. External checkpoint comparison remains pre-initialization, but checkpoint
    mutation moves to one settlement barrier after the external cause's complete
    direct and queued causal closure drains. Accepted-source writes and cleanup
    form one direct-write batch followed immediately by exact component and
    containing-spine finalization. Every accepted raw source pays exactly one
    `checkpointCompared` in frozen Phase B order before initialization or
    Handler work; grouping never coalesces that charge. A pre-barrier
    noncommitting result performs no checkpoint mutation or checkpoint-caused
    finalization.
25. The default checkpoint-domain identity is the ordinary BlueId of the exact
    `CheckpointDomain` value containing `contractsVersion`,
    `effectiveTypeBlueId`, ordered `sourceContributionNodeBlueIds`, and optional
    ordered `deterministicDependencyNodeBlueIds` and `runtimeDiscriminator`.
    It is not an opaque string or platform `sha256:` constructor. Checkpoint
    write evidence carries each present side's complete domain value beside its
    recomputed BlueId, and the sequence identity binds both.
26. Event FIFO and delivery-work gas have separate ownership. Every event
    occurrence pays one `internalEventDequeued`, including a zero-target event;
    every actual Triggered or Embedded delivery separately pays one closure-work
    enqueue/dequeue pair and its delivery work. Marker/checkpoint batches are
    synchronous processor boundaries, not synthetic queued work.
27. Snapshot flags and fixture summary receipts are derived assertions. They
    must agree with exact markers, structured writes, and input/output closure
    identities and never substitute for that evidence.
28. Checkpoint Direct Write counter ownership is closed. Each actual
    accepted-source add/replace or cleanup removal pays one `checkpointWritten`;
    that counter includes processor-side checkpoint-address traversal and
    marker-shape/add/replace/removal work. The same mutation does not also pay
    pointer, patch, or `processorMarkerWritten` counters. Changed Blue identity,
    semantic validation when actually required, component finalization, and
    containing-spine work remain separately metered. No-op entries pay none.
29. A structurally valid, invocation-bound non-null admission candidate begins
    the shared meter and admits `processInvocation` then `closureInvocation`
    before the ordinary authoritative-closure admission trace and branch
    verification. The three branches then have closed short-circuit traces:
    proof-record/member comparisons use existing validation/scalar/text
    counters, preliminary ambiguity uses the unchanged Language identity and
    stable-sort counters including the canonical-input tie-break, and occurrence
    evidence uses one binding-verification charge per row plus exact path and
    declaration traversal. C-CLO-14, C-CLO-15, and C-CLO-29 therefore no longer
    have a zero-gas semantic rejection; their frozen totals are respectively
    199, 191, and 196 gas.
30. C-CLO-33 combines checkpoint-domain retirement paths in one successful
    external invocation. A present source entry with a verified old domain is
    virtual empty for newness, then is replaced by the accepted source's frozen
    current domain/subject at the post-quiescence barrier; an orphan raw-key
    entry is removed in the same atomic batch. The accepted-source add/replace
    receipt precedes the cleanup-removal receipt. Every present receipt side
    carries its complete checkpoint-domain value beside a recomputed matching
    BlueId, while every absent side uses the required null value/BlueId/subject
    fields. This vector covers comparison, source replacement, cleanup removal,
    receipt self-verification, combined ordering, and one immediate exact
    marker-finalization boundary.
31. Tentative-finalization receipts now locate every initialization marker
    boundary exactly. `WORK` retains required `afterWorkOrdinal`;
    `INITIALIZATION_BATCH` also requires it and binds the last accepted causal
    initialization/lifecycle work before the marker batch; and
    `CHECKPOINT_SETTLEMENT` has no such field. Marker installation and its exact
    component finalization are consecutive immediately after the initialization
    ordinal. C-CLO-08 therefore completes initialization/lifecycle works 0–3,
    including the second member's lifecycle work, before its marker batch and
    records `afterWorkOrdinal: 3`; the former placement after work 1 is invalid.
32. Prospective occurrence bindings are no longer hidden fixture/provider
    input. The authoritative snapshot and result use one closed occurrence-row
    set containing both active rows and explicit `active: false` prospective
    rows. Every row has required nullable `pendingHistoricalEpoch`, non-null
    only while exact admitted historical binding evidence is being caught up.
    Activation turns the same reserved row active without changing its
    occurrence lineage or generation; only active rows contribute graph edges,
    SCC membership, deliveries, or initialization. The occurrence-binding-set
    identity binds `occurrenceIdentity`, `bindingIdentity`, `active`, and
    `pendingHistoricalEpoch` for every row, so no unbound staged map can drive
    behavior.
33. The invocation constructor's operation field uses the exact lowercase
    literal `process-closure` or `admit-closure`. Uppercase API spellings and
    obsolete harness labels are never hashed as portable identity input.
34. `closureIdentity` is durable state identity only: graph generation, exact
    document records, occurrence-binding-set identity, component-state
    identities, and public Root set. Cause and frozen direct-delivery evidence
    belong only to `invocationIdentity`. One closed `ClosureInvocationInput`
    owns the state snapshot and all invocation adjuncts, preventing conflicting
    duplicate event/cause/route/policy/environment arguments and making
    sequential closure compare-and-swap coherent.
35. `NeedsResources` discards the attempt. Once exact requested BlueIds are
    available, processing starts again from the exact input closure and cause;
    no queue, continuation, gas prefix, or tentative state resumes. Historical
    resource evidence may retain the same logical invocation identity only if
    every identity-bound non-resource input revalidates unchanged.
36. For one internal event occurrence, all actual Triggered and Embedded
    delivery work is materialized and charged for enqueue in canonical order
    before the first delivery is dequeued. Each delivery then runs in that same
    order and finishes its synchronous patch/update/finalization continuation
    before the next delivery. This fixes both the accepted gas prefix and
    temporary-identity visibility.

## Review findings explicitly fixed

- no unconditional final cycle prohibition;
- no one-finalization-after-quiescence model;
- no undefined application-visible stable handle;
- no `PROCESS_COMPONENT` API limited to one SCC;
- no single-master result incapable of representing splits;
- no admission disguised as an empty external delivery;
- no hidden `componentPatches` graph mutation protocol;
- no active fixture edges absent from exact document content;
- no direct fixture delivery targeting a Handler key as though it were a Channel;
- no placeholder cyclic IDs in the new closure oracle corpus;
- no pre-labelled invalid proof oracle for ambiguity;
- no fake limit count disconnected from the actual fixture graph;
- no status/diagnostic aliases outside the normative vocabulary;
- no provisional gas/limit wording;
- no filename-dependent source archive lookup in package validation.
- no flat-versus-wrapped identity-constructor disagreement;
- no occurrence identity churn caused only by `MASTER#index` state changes;
- no missing rejected-charge evidence on gas exhaustion;
- no assumption that every rejected charge belongs to a queued work occurrence;
- no conflation of stable component continuity with exact component state;
- no ambiguous string encoding for a rejected local cap;
- no inline/reference-dependent cyclic canonical-byte safety count;
- no impossible claim that every large portable safety bound completes as a
  full successful closure below the independent gas limit.
- no unbound out-of-band evidence driving negative admission vectors;
- no member-by-member initialized-marker state inside one component;
- no checkpoint mutation visible between direct seeds of one external cause;
- no opaque checkpoint-domain label in place of the exact runtime domain value;
- no per-delivery multiplication of `internalEventDequeued`, and no missing
  dequeue charge for a zero-target event;
- no Boolean initialized/checkpoint/rollback assertion standing in for exact
  marker, write, or closure evidence.
- no hidden prospective-binding map outside the authoritative occurrence row
  set, and no inactive row silently contributing an active graph edge;
- no API-enum or obsolete harness spelling substituted for the closed
  invocation-operation literal;
- no attempt-scoped cause or route mixed into durable closure-state identity;
- no resumable tentative suffix behind `NeedsResources`;
- no source-delivery execution before all deliveries of one event occurrence
  have paid their canonical enqueue charges;
- no local gas ceiling duplicated inside the closed managed-document record;
  all local ceilings live in the exact execution policy;

## Release boundary

This package is the final normative implementation target. Implementations must
not edit the specification to make code pass. A discovered contradiction must
stop the implementation round and be reported with a minimal reproducer. Any
future semantic change requires a new Contracts release identity and version.
