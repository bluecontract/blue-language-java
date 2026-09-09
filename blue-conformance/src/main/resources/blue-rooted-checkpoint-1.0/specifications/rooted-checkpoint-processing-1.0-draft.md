# Blue rooted checkpoint processing — RCP-1

**Revision:** `blue-rooted-checkpoint/1.0-draft.2` · 8 September 2026  
**Status:** normative implementation-target draft. This is not a published runtime profile or a claim that the current Java implementation conforms. The candidate adopts the rooted selection requirements clarified by the user. Iteration 2 completes the draft.1 publication and identity gaps; these explicit completion rules are proposed normative changes, not a claim of prior runtime conformance. The operation-boundary choices in §8 are explicit candidate choices, not consequences of the selector proof.  
**Dependencies:** the exact four documents in this directory, identified in `../manifests/specification-set.json`. `C` denotes Contracts, `Q` Coordination, `L` Language, and `B` BEX. Section references use the preserved numbering in those documents.

## 1. The algorithm

### 1.1 One processing step

The processor receives one exact document state and one already selected, admitted input:

```text
PROCESS(document, input) -> completed result | defined failure | NeedsResources
```

The fixed execution environment supplies the registered runtimes, exact evidence and finite gas allowance. It is not a new application-level decision made on every event.

The processor MUST apply the input only to its frozen eligible receiving bindings, execute its deterministic immediate consequences, and return when those consequences are quiescent or a defined failure/resource condition occurs. It MUST NOT poll a Timeline, select a later external entry, use wall-clock time as semantic input, or fetch an arbitrary latest dependency head. Existing C work kinds, patch/update continuations, event queues, lifecycle, checkpoint settlement, validation, cyclic finalization and failures apply.

“Step complete” means the supplied input has no unfinished immediate consequences. It does not mean that every historical successor or future external entry has already been processed.

### 1.2 The driver

The driver MUST:

1. Start with the selected root and its exact declared managed embedded views.
2. Follow their **forward** `Process Embedded` relationships. Preserve exact selected historical positions and distinct occurrences; do not traverse unrelated incoming observers to enlarge this root's operation.
3. Inspect the applicable accepted-input checkpoints and the already-defined terminal/application progress for each receiving context.
4. Establish each context's next admissible unconsumed input. Provider evidence, eligibility and completeness are feeder responsibilities, outside `PROCESS`.
5. Select the smallest eligible candidate under its prescribed order, freeze the receiving set and logical grouping, and invoke the processor once.
6. After a committed change or terminal disposition, recompute affected membership and next-input heads. Repeat when further required work exists.

For `A -> B -> C`, arrows mean “embeds/depends on.” Advancing A uses A/B/C; advancing B uses B/C; advancing C does not use A/B merely because they observe C. A reverse index may notify those roots that they have work. Notification is not inclusion in C's processing domain.

Historical embedding is not a second business algorithm. An older selected view introduces older pending inputs. The same driver selects them, and the same processor settles each admitted step. C §16.9 retains its separate contiguous-successor boundaries.

## 2. Normative rooted scope

**RCP-SCOPE-01.** The selected input root and the exact forward dependency/view evidence determine the semantic processing domain. The host's global registry, account-visible document list, cache, reverse indexes, or weakly connected cohort MUST NOT add semantic participants.

**RCP-SCOPE-02.** An incoming edge from X to an already reachable document does not include X unless the selected root also has a required forward path to X. A real return path can form a cycle and is not an unrelated-observer case.

**RCP-SCOPE-03.** Dynamic expansion follows new declared forward dependencies and the exact evidence required to evaluate/finalize them. Every containing-reference update in C refers to containing paths **inside this rooted domain**, plus the exact current cyclic ownership necessarily reached by an admitted live join. It does not require synchronous publication into every external parent.

**RCP-SCOPE-04.** A supplied snapshot MAY carry immutable evidence needed to verify a selected retained position or complete cyclic owning proof. The presence of that evidence does not make its owner a delivery target, mutable member, new subscription, or participant in gas accounting beyond the prescribed verification work.

**RCP-SCOPE-05.** Root scope is a semantic selection, not permission to truncate required dependencies. Missing required evidence returns the existing typed wait/failure. A host MUST NOT pass an incomplete forward graph off as a complete one.

**RCP-SCOPE-06.** Shared physical content storage does not merge root-local progress. Two paths selecting A5 and A8 keep those positions even if another session has A10. Equal content bytes alone do not prove equal history context or applied source position.

## 3. Progress and receiving contexts

**RCP-PROGRESS-01.** “The document's checkpoint” means the existing per-Channel checkpoint entries at the exact scope and activation generation. Raw channel keys in different documents/scopes are independent. A single maximum timestamp over the whole root MUST NOT replace them. Preserve Q §16 and C §10.

**RCP-PROGRESS-02.** The next input is relative to the selected receiving view. If the root has consumed E20 while its new embedded A has consumed only E5, E10 may be new for A and stale for the root. Deliver E10 only to the contexts for which it is eligible and new; resulting embedded-event reactions are separate internal work.

**RCP-PROGRESS-03.** Source checkpoints do not replace a parent's retained-application cursor. Event-only transitions, repeated equal payloads, and same-epoch representation positions remain distinguishable by their existing receipt/position/ordinal evidence. A host MAY store this compactly; it MUST preserve the distinctions.

**RCP-PROGRESS-04.** Feeder `NO_MATCH`, `REJECTED`, `WITHHELD`, and other terminal outcomes that do not call the processor use Q §16.11 terminal progress. They MUST NOT invent a successful processor checkpoint, and MUST NOT cause endless reevaluation of the same frozen target context.

**RCP-PROGRESS-05.** Only successful atomic publication advances the accepted-input checkpoint. Resource waits and failed invocations preserve the previous processor state. Committed-response loss is reconciled using existing companion/idempotency evidence, not by executing the successful effects again.

## 4. Selecting the earliest input

For external input selection, let each current binding q expose the ordered suffix H(q) after its already handled progress, under its activation and authority rules. Define:

```text
head(q) = first admissible pending entry of H(q), if any
next = min(head(q) for all currently relevant q)
```

**RCP-ORDER-01.** Use Q §9's `ExternalOrderKey`: integer microsecond timestamp, then exact Timeline BlueId under unsigned ASCII ordering, then exact entry BlueId under that ordering. Notification time, row ID, map order, worker ID and polling order MUST NOT break ties.

**RCP-ORDER-02.** The feeder establishes the applicable completeness window before claiming a candidate is next. A comparator does not prove that an earlier unseen entry is absent. This proof is a precondition of the supplied processing input, not network work inside the processor.

**RCP-ORDER-03.** Deduplicate physical fetches of an entry, not its semantically distinct receiving occurrences. Where several bindings admit one logical operation, preserve C §3.3's exact grouping and Q §16.8's checkpoint ownership. Freeze recipients before execution.

**RCP-ORDER-04.** Retained successor work preserves its original external cause order **and** its authenticated predecessor/position constraints. Multiple source transitions can have the same originating entry; a key collision does not merge them. Existing source epoch, representation predecessor and occurrence ordering are required tie constraints. Do not order A6 and B8 by comparing 6 with 8 as if they were global times.

**RCP-ORDER-05.** Finish one input's immediate reaction machine before selecting another independent external input. Internal emissions are ordered by the existing C work/FIFO rules, not by manufacturing Timeline timestamps.

**RCP-ORDER-06.** After topology changes, discard stale next-input selections whose binding or prerequisite set changed. A newly selected historical view may introduce a key smaller than the root's most recent direct entry. This does not rewind the root's existing checkpoints or permit redelivery to already-consumed bindings.

A scan of all heads is the reference selector. A heap, index or incremental scheduler is permitted when it produces the same selected input, recipient set, dependency reads, results and failures.

## 5. Forward graphs, repeated occurrences and internal delivery

**RCP-GRAPH-01.** Graph discovery terminates on the finite exact graph. Current live cycles use existing verified owning-set and component evidence. Historical views are identified by exact selected positions: encountering the same lineage name at an older position does not substitute today's current state.

**RCP-GRAPH-02.** A visited set may bound graph discovery. It MUST NOT suppress distinct work occurrences, distinct authored paths, or fresh re-emissions. Preserve vertex-simple routing and original multiplicity rules.

**RCP-GRAPH-03.** With no matching local Handler, the processor may omit Handler execution. Required embedded-state advancement, containing-reference reconstruction, occurrence progress, checkpoint settlement and descendant routing still occur. A silent intermediate document is not an event-routing firewall.

**RCP-GRAPH-04.** An initially empty declared collection `orders: {}` is present exact content. Later direct members become occurrences under C's collection rules. No wildcard syntax or implicit ambient parent channel is introduced. `Embedded Collection Event Channel` retains `collectionPath` and `includeDescendants`; pointer segments are compared after decoding, not by raw string prefix.

**RCP-GRAPH-05.** Removal, re-addition and retargeting obey the existing occurrence-generation rules. The new generation may select old history; it does not inherit the retired generation's applied position merely because the path string was reused. Frozen batches and the creating-entry exclusion remain in force.

## 6. Historical attachment and A10/A5

A checkpointed/retained attachment changes the selected view of the new occurrence, not the authoritative head of an already established source.

**RCP-HISTORY-01.** Preserve the four existing starting-position rules:

| Selected value | Newly required progression |
|---|---|
| Authored pre-initialization state | Retained initialization and subsequent eligible source steps |
| Initialized epoch 0 | Successors after epoch 0; do not initialize again |
| Retained epoch k | Contiguous successors after that exact retained position |
| Current-at-activation view | No earlier reactions; then later eligible progression |

“Current” refers to the defined logical activation position, not whatever a worker most recently cached. Exact position evidence resolves ambiguous repeated content. An arbitrary caller-supplied integer epoch is not sufficient authority.

**RCP-HISTORY-02.** Applying a retained source transition does not execute the source workflow again or republish its source event as a fresh source emission. It exposes the recorded complete result, ordered event occurrences, and required exact read views to the consumer. A cold reference execution may reconstruct them from verified inputs under their original rules; it MUST produce the same result.

**RCP-HISTORY-03.** If A is at epoch 10 and B attaches A5, authoritative A stays at 10. B's selected occurrence advances through A6, A7, A8, A9 and A10 by the existing separate applications. Its direct channels retain their own progress. If another newly imported source has earlier pending input between these successors, Q §19.7 determines interleaving.

**RCP-HISTORY-04.** A5's historical reference to B2 remains B2 while reconstructing the A5 successor. It does not resolve to current B solely by lineage name. Otherwise historical Handler reads can change.

**RCP-HISTORY-05.** The terminal join follows C §16.9 and the retained representation-position extension. Verify the exact successor chain, reconcile the required current same-lineage relationship, update activation/generation, re-partition and finalize the resulting real cycle before subsequent affected work. Neither “epoch number equals 10” nor a historical content-ID comparison alone establishes this join.

**RCP-HISTORY-06.** Preserve immutable receipts and the one-epoch predecessor rule. Same-epoch representation transitions use the separately authenticated representation domain; they are not fabricated numbered epochs. Captured representation goals are not expanded by newly generated representation-only feedback. The ordinary target-advancement policy is not silently replaced with the representation-tail rule.

The profile additionally requires C §2.3a.1's independently authenticated
checkpoint-only containing-reference positions. They retain the original rooted
publication proof and use their separate closed position domain; no generic
eventless or equal-epoch acceptance is introduced. Legacy pending-target
positions, numbered epochs, ordinary gas and source publication remain unchanged.

**RCP-HISTORY-07.** A failed required application remains outstanding and blocks dependent later work. It does not invalidate its already committed source receipt. A terminally rejected live input and a failed import of committed history are different outcomes and retain their existing disposition rules.

## 7. Creation, discovery and suspension

**RCP-BIRTH-01.** Storage materialization is not process birth. Cache eviction, worker order, first successful load, or another user's storage must not select a different eligible source history.

**RCP-BIRTH-02.** Source history is determined by the exact initial/retained state, its explicit semantic initialization/activation context and the applicable input history. Those facts are established once at the proper boundary and reused; ordinary processor calls do not choose another policy.

**RCP-BIRTH-03.** In this candidate, attaching a bare existing/authored source value follows its declared checkpoints and established history basis, independent of whether the host already materialized it. In the absence of an explicit newborn-from-cause admission, the containing operation's timestamp MUST NOT silently become a new lower bound that discards otherwise required earlier input.

A genuinely new process or channel explicitly born by E still has the specified post-E activation. Its birth evidence distinguishes it from discovering a pre-existing source. Do not change an explicit birth policy to full history, or the reverse. Do not encode birth semantics through “record exists in SQL.”

**RCP-BIRTH-04.** Newly born provisional children remain subject to the creating invocation's existing atomic initialization/admission rule. Partial publication is forbidden on failure. This rule does not pull in already-existing unrelated parent observers.

**RCP-BIRTH-05.** A resource-suspension test with the same semantic inputs must settle to the same source history after the resource is supplied. Any candidate history that depends on which root's worker first ran is nonconforming.

## 8. One rooted operation: exact ownership and gas

### 8.1 What remains unchanged

One supplied rooted input has one ordered immediate-work machine, one shared semantic meter, and one atomic **owned result**. Dependency calculations inside that reference step are not independently committed merely because a worker evaluates them separately. Acyclic roots are not merged merely because they read the same source. These are the draft.1 candidate choices retained by this revision, not conclusions derived from the selector theorem.

The read/input domain and the authoritative write domain are deliberately different. A root may contain and calculate an exact child view without acquiring permission to overwrite that child's independently retained source head. A root result includes its exact embedded values and progress; independently authoritative source history belongs to the source's own operation context.

### 8.2 Derive the owned result, without a caller-supplied allowlist

**RCP-OWN-01 — entry ownership.** At the admitted input boundary, freeze the selected root's exact view graph. Let `G0` be its **current live** directed dependency graph after required pre-entry history is settled. Let `K0` be the strongly connected component containing the selected root, or the singleton root when acyclic. Historical pending edges and immutable proof members are not live edges in `G0`. The initial authoritative owner set is `K0`. The selected root's other forward-reachable documents are required **root-local dependency views**; unselected incoming parents are excluded. This does not make every downstream SCC a separately committed operation inside this call.

**RCP-OWN-02 — immutable witnesses and local calculations.** For each outgoing occurrence, retain its selected state and progress. An authenticated source receipt already published by another operation is immutable input. An uncommitted cached calculation is only a reusable computation. Neither fact changes entry ownership. The reference machine may advance a dependency's root-local view, including the child's local checkpoints and ordered effects. Store that view as part of the root's committed result and occurrence evidence, **not** as a replacement for an independently authoritative source head. A source's old receipts and public outbox remain unchanged.

A physical store may retain exact immutable node bodies and computation records at any time. Their existence is not evidence of an authoritative source commit. Reusable records must bind the complete input view, cause, processing profile, relevant policy and ordered result/trace. They contain no parent identity or current database timestamp that changes the source's own calculation. The source can later independently verify/reuse the computation, but must perform its own newness, gas, commit-fence and publication checks.

**RCP-OWN-03 — expansion and splitting during the step.** At every prescribed topology boundary, recompute the live SCC relation over the exact candidate graph. Expand the owned set to the least fixed point containing: (a) the entry owners, (b) every current live SCC intersecting an already owned member, and (c) provisional newborns created by owned work in this invocation under the existing authenticated birth contract. Recompute when those additions disclose further live dependencies. One-way outgoing dependencies remain local views. Incoming outsiders without a reachable return path remain excluded.

An existing independently authoritative document becomes co-owned only through a proved live cyclic join. Acquire its required exact causal view and publication fence before accepting that expansion; unavailable evidence is a wait, not authorization to use a newer head. A pending historical edge becomes live only after the existing terminal reconciliation proves its required position. Keep the invocation's initial operation context, cause and meter throughout expansion. Do not restart charging from zero or re-deliver completed work.

The owned set does not shrink during the step: breaking a cycle does not make already affected members independently publish halfway through the same invocation. The next invocation uses the resulting partition. If a late shared-source join cannot be established at the original causal view, do not rewind a newer committed head or invent a history: retain the typed prerequisite/conflict and resolve it through the existing ordered admission/representation protocol. Operational compare-and-swap retries do not become new semantic causes.

**RCP-OWN-04 — publication projection.** On success, atomically publish the resulting authoritative state/position for every owned member that changed, the owned occurrence/binding changes, accepted checkpoints, required application progress, exact member receipts, owned public-event outboxes, and the commit companion. Also retain the exact root-local dependency values/projections required to reproduce those owned results. A live input that changes only a root-local child still changes the root's exact containing value when prescribed. An unchanged owner does not receive an invented epoch merely to fill a record set.

Events generated while evaluating an unowned child remain exact origin-labelled input/effect evidence for this root's internal routing. They are not a second publication on the source's public outbox. A real event emitted by an owned parent's handler is the parent's new event. Equal payloads with different occurrence ordinals remain distinct. Finalizing an unowned dependency's local cycle requires its complete owning-set proof but does not grant authority to update that independent component's heads.

**RCP-OWN-05 — failure.** A defined noncommitting failure discards all tentative owned and root-local changes for this invocation; accepted-input checkpoints do not advance. Immutable independently committed source records remain untouched. Operational attempt logs may remain, and terminal feeder dispositions follow Q16/Q22. No successful source receipt may be fabricated from a failed or merely computed child prefix. A failed required retained application remains pending and cannot be skipped.

**RCP-OWN-06 — newly born versus discovered.** A provisional child explicitly created by this invocation is part of its owned birth transaction; failed parent work must not leave that birth published. An already-existing source discovered or first loaded here is not a newborn. A definition without a host row is not evidence of `NEWBORN_FROM_CAUSE`. For a bare source discovery, derive its declared history basis using §10; calculating its selected view can remain root-local, and independent source publication is a separate operation. If a local calculation would create a source-owned child, retain that creation as part of the local computation; do not globally publish it until the corresponding source operation owns and commits it. Explicit creation identity uses the causal input, not physical first materialization.

### 8.3 Choose LIVE versus historical application before examining storage warmth

**RCP-CAUSE-01 — classify from activation and progress.** At occurrence registration, freeze a semantic join anchor: the exact source position required at the attachment's **logical input boundary**, established using the selected history basis and the existing completeness/admission rules. This is not the wall-clock latest head. A current-view attachment installs that anchor directly. An older selected position creates an ordered interval of retained applications ending at the anchor. Authored-initial and initialized starting positions keep their existing initialization rules.

For a selected root already live when an external entry becomes eligible, deliver that original entry as a LIVE cause against its selected channel progress. The driver MUST NOT convert this pending LIVE cause into `ManagedRevisionCause` merely because another root or worker committed the corresponding source result first. A notification carries a wake-up/available-proof fact, not a new cause classification.

For a newly historical occurrence, consume successors in its frozen historical interval using the existing `ManagedRevisionCause`/`ManagedRepresentationCause` rules and exact predecessor evidence. No global root rewind or redelivery to stale direct channels occurs. At terminal join, reconcile the anchor into the live component under C16.9 and the representation extension. A later physical source head does not silently extend this historical interval. Later relevant original entries remain ordinary pending LIVE inputs. A genuine newly discovered prerequisite before the anchor must be resolved before joining; it is not skipped by the frozen interval.

**RCP-CAUSE-02 — stable identity of each obligation.** The already existing occurrence generation, source position/predecessor and causal input distinguish a historical application from a live input. Repeated equal-body epochs, event-only transitions and same-epoch representation positions use their explicit receipt order; epoch arithmetic alone is insufficient. A single root operation may bind more than one applicable receiving view, but one raw input is not broadcast again to direct channels that already consumed it. Frozen grouping and recipient rules remain unchanged.

**RCP-CAUSE-03 — physical schedules cannot change the kind.** Hold the root's selected state, checkpoint/activation evidence and input fixed. Missing cache, exact cache hit, eviction, source-first execution elsewhere, response loss and replay must choose the same semantic kind and produce the same root calculation. An implementation may reuse a complete matching computation or receipt as evidence; it still follows this operation's logical trace and publication rules. A new late historical attachment has different semantic activation evidence and is tested separately, not smuggled into a cache variant.

### 8.4 Shared live meter; no sibling debit

All immediate work in the selected LIVE rooted reference invocation—including required dependency calculation, reference/identity work and parent reactions—debits its one existing meter in the prescribed order. A lower member cap remains a ceiling, not a fresh meter. Cache reuse reproduces that logical work and its exact failure boundary. A final aggregate source total cannot replace interleaved work if the reference observes intermediate values or fails partway through a child trace.

For illustrative, **non-production** units, source work 80 followed by parent work 20 has total 100. A LIVE root with limit 90 fails whether the source is cold, cached or independently committed elsewhere. The independently processed source can commit its own 80-unit operation. A true new historical attachment is a different input with its existing application tariff; it does not inherit a fabricated 20-unit production charge from this illustration. Use the actual unchanged production tariff in SDK adapters and retain the rejected next charge.

No one-way sibling observer contributes to the selected root's membership, identity, gas, failure condition or publication set. Platform billing and aggregate scheduling quotas are not semantic gas.

### 8.5 Constructive shared-source outcome table

Initial state: source `S.counter=0`; independent parent `P` embeds the selected S0 view and records `seen=0`; another parent `Q` is unrelated incoming observation. E increments S and emits one source event. P's reaction sets `seen=1`. `P` was already active for E in the LIVE rows. Observe both the **owned P value** and the **independent S head**; they need not be at the same progress while separately scheduled.

| Operation/schedule | Input kind | Required successful owned writes | Independent S history | Source public emission | P failure after child work |
|---|---|---|---|---|---|
| Process S alone | LIVE | S1 and its own evidence/outbox | Advances once | One origin event | Not a P invocation |
| Process P first, S not published | LIVE | P1, embedding local S1, and P-local progress/effects | Remains S0 | No independent S publication | P remains P0/S0 view; independent S still S0 |
| P uses cached but uncommitted S computation | LIVE | Same P1 and same P logical trace as cold | Remains S0 | None by P | Same cold failure |
| S commits first, then P processes pending E | LIVE | Same P1 and same P logical trace as cold | Already S1; unchanged by P | S's existing emission is not duplicated | P remains P0; S1 remains committed |
| P succeeds, then S is processed directly | two LIVE operations | P's earlier result stays; S performs its own source publication | S0→S1 independently | Exactly one from S's publication | Not applicable |
| Q processes E before/after P | LIVE in Q's own context | Q result only; no P write or debit | Unchanged unless S's own operation runs | No duplicated S outbox | Q failure does not change P or S |
| P is newly attached to old S0 when logical anchor is S1 | historical application after separate attachment | Selected P occurrence advances S0→S1 with application evidence | S1 remains unchanged | No source re-emission; P handler may emit | Required P application remains pending |
| E forms a genuine P↔S live cycle | original kind frozen at entry; dynamic ownership expands | P and S's verified current co-owned result publishes jointly | Advances only as permitted by join/cyclic rule | Only emissions actually generated in the joined operation | Neither newly owned result publishes; old S history stays intact |

The table is normative for the ownership distinction, not a replacement for complete per-step state/identity rules. A later source operation may reuse a stored local calculation only after verifying identical source semantic inputs and performing its own publication. It never treats P's commit as S's commit. A two-parent host must not store all these views as aliases to one mutable `latest S` object.

### 8.6 Refined output shape (internal, not a new business API)

The logical result has three record classes:

```text
ownedTransitions       exact changed owner states/positions and their receipts
embeddedViewResults    exact selected dependency views/progress in owned results
immutableWitnesses     the already committed source evidence read by the step
```

These names describe a verification projection, not mandatory Java classes or three duplicate document stores. Derive the classes by OWN-01..06, then verify the implementation's actual companion/records against them. Authored applications cannot supply an arbitrary mutable-owner list. Database caching cannot add or remove members. Unrelated observer registration may update a physical reverse index but cannot rotate this result's semantic identity.

### 8.7 Dynamic-graph fences and crash recovery

At publication, compare the prescribed prior state and occurrence fences for the **owned** result and verify read witnesses at their selected exact positions. A later independently published source head does not invalidate an immutable read witness merely because it is newer. A real conflict in an owned head requires the existing ordered retry/replan path, not a partial commit. Keep the original causal identity and compare terminal companions after response loss.

A source-first operation need not wait for a one-way consumer. Its durable notification obligation may be committed with the source, but the consumer's completion is not a source publication prerequisite. Delayed notifications cannot erase pending work: source evidence and occurrence progress must reconstruct it.


## 9. Stable step, ready document and liveness

**RCP-READY-01.** A completed step has no immediate work left. A document ready through an advertised input boundary additionally satisfies all required progress and completeness through that boundary. Neither claim implies perpetual global idleness.

**RCP-READY-02.** A source does not wait merely because a one-way incoming observer is behind. A root does wait for its actual required forward dependencies, historical successors and topology prerequisites. Discovery of a return dependency changes this classification legitimately.

**RCP-READY-03.** A stalled or failed consumer may expose its previous exact ready state as such. It must not claim the newer source frontier while its required application remains unapplied.

**RCP-READY-04.** Finite gas bounds one invocation only if all unbounded internal work paths have positive charges or enforced structural bounds. It does not prove termination of an infinite sequence of separately successful invocations that keeps creating new histories/generations. Host drain/cancellation limits are operational and MUST NOT be reported as semantic `GAS_LIMIT_EXCEEDED`.

**RCP-READY-05.** The portable numeric limits are unchanged. They count the supplied rooted invocation's prescribed semantic records/work, not all stored reverse observers. 10,000 independent Orders pointing to one Agreement do not automatically create a 10,001-document Agreement invocation. A root whose actual required forward graph exceeds a limit still fails; batching cannot evade it.

## 10. Closed history-basis and operation-identity construction

### 10.1 Status and common encoding

This section completes draft.1's ambiguous `rootHistoryBasisIdentity`. It defines **new internal draft.2 envelopes**, not new authored Blue fields or production BlueIds. These constructions must be independently verified from actual content, admission evidence and graph rows. Correct SHA syntax alone is not evidence of a valid history.

For every constructor below:

```text
H(domain, value) = "sha256:" + lowerhex(SHA-256(UTF8(JCS({"domain":domain,"value":value}))))
```

The supported envelope subset contains closed objects, lists and strings only; there are no JSON numbers, booleans, nulls or floating values. Optional evidence is represented by the specified closed tagged object, not a missing or arbitrary extra field. All object keys are ASCII; identity-bearing values are valid Unicode scalar strings with no lone surrogate. Existing DocumentIds must satisfy the Contracts NFC/NUL/UTF-8-length rule before use. Arrays described as sets are sorted by unsigned UTF-8 bytes of their stated key and reject duplicates. This is ordinary internal envelope hashing; Blue values inside referenced content still use Blue Language's actual identity algorithm.

Exact constructors, closed JSON schemas, executable standard-library code and frozen unit vectors live in `conformance/rooted-processing/identity-constructors.json`, `schemas/` and `identity/`. Unit-vector hashes authenticate their literal envelope operands; they do not assert that synthetic profile hashes authorize production admission.

### 10.2 A document's stable history basis

**RCP-ID-01 — history basis.** Construct `DocumentHistoryBasis` from exactly:

```text
documentId                  verified stable managed DocumentId
initialDocumentBlueId       verified exact authored/pre-initialization BlueId
runtimeSemanticsIdentity    exact selected semantic profile/runtime binding identity
admission                   one of the closed objects below
```

`admission` has exactly one of these forms:

```text
{"mode":"FULL_HISTORY"}
{"mode":"FROM_FRONTIER", "lowerExclusiveOrder": OrderKey}
{"mode":"FROM_NOW", "lowerExclusiveOrder": OrderKey}
{"mode":"FROM_NOW", "lowerExclusiveOrder": {"kind":"BEGINNING"}}
{"mode":"CREATED_IN_OPERATION", "creatorOperationIdentity": SHA,
 "birthOccurrenceIdentity": SHA, "lowerExclusiveOrder": OrderKey}
```

`OrderKey` is exactly `{timestampUs, timelineBlueId, entryBlueId}`. `timestampUs` is the canonical unsigned decimal string of the existing safe-integer microsecond value (no signs, leading zeroes except "0", or exponent); comparison uses its numeric value and then Q9's ASCII BlueIds. The explicit modes retain distinct admission claims even where lower bounds happen to coincide. `FROM_NOW` is bound to the original logical activation entry, never a later worker's wall clock.

**RCP-ID-01a — authenticated beginning admission.** The additional closed
`FROM_NOW` bound `{"kind":"BEGINNING"}` applies only when no logical activation
entry exists because the applicable input history is positively established at
its beginning at the original admission boundary. It is not an OrderKey, a
Timeline Entry, a successful input checkpoint, or an invented timestamp. It is
below every real OrderKey, including a real OrderKey with timestamp `"0"`.
Only this FROM_NOW form accepts it; FROM_FRONTIER and CREATED_IN_OPERATION
continue to require the existing exact three-field OrderKey. Nonempty FROM_NOW
admission keeps the exact original activation entry. FULL_HISTORY and FROM_NOW
at beginning remain different admission claims and history-basis identities.

The admission owner MUST derive the complete required external source surface
from the authenticated admission input and successful result, including newly
initialized source Channels. For every required Timeline, its installed exact
provider verifier MUST positively establish the beginning-of-history fact at
that frozen boundary. A provider-authoritative empty journal may establish this
fact only while it owns the complete admitted history of every such exact
Timeline and preserves that boundary through atomic admission. An exact,
verified source surface with no required Timelines has an empty obligation set;
a missing or incompletely resolved source surface does not. A finite empty
interval that does not prove beginning, absence from a cache or local mirror,
an unregistered or unknown provider, NOT_FOUND, DEFERRED_UNAVAILABLE,
INVALID_EVIDENCE and INCOMPATIBLE_FRONTIER MUST NOT establish BEGINNING.

The verified fact and complete source-surface binding MUST be retained with the
exact successful admission evidence and atomic publication. A supplied tagged
object or recomputed wrapper hash alone grants no admission authority. A retry
or restart uses the original retained admission fact, not a newly observed
empty/nonempty journal or wall clock. Newly admitted later inputs still require
ordinary provider completeness, authority, newness and checkpoint validation;
BEGINNING does not certify readiness through a future boundary. Failure leaves
no published admission, history basis, accepted checkpoint, initialization
emission or owned state. This addition does not change §8 ownership or gas,
§9 readiness, any existing admission form or any retained historical receipt.

Hash the record with domain `blue-document-history-basis/1.0-draft.2`. The selected runtime profile and admission evidence are independently checked. Creation refers to an already fixed entry operation identity and deterministic birth-occurrence identity; it must not hash a parent result or newborn-dependent value recursively. The current source head, SQL/session ID, cache residency, observer set and physical first-loader are absent.

Bare source discovery uses the explicitly established declaration/checkpoint basis. In the absence of a different authenticated admission selector, this candidate's canonical authored-source discovery uses FULL_HISTORY. A genuine causal newborn requires the CREATED_IN_OPERATION witness. Unknown versus known storage must not select between them. Two incompatible history bases are distinct histories and cannot silently share one mutable source row.

### 10.3 Canonical owner context for an existing live group

**RCP-ID-02 — requested view versus owner.** First derive K0 under OWN-01 from the pre-input exact live graph. Sort its stable DocumentIds by unsigned UTF-8 bytes. `canonicalRootDocumentId` is the first. This is only a canonical orchestration anchor: every member still executes with its own `$document`, own channels and required handler order. An A-view request and a B-view request for the same already-live A↔B group use the same anchor. A root outside that group that merely embeds it keeps its own root; it is not replaced by a downstream component's anchor.

Construct `OperationOwner` from:

```text
members:       sorted [{documentId, historyBasisIdentity}]
internalEdges: sorted [{occurrenceIdentity, parentDocumentId, sourcePath,
                       childDocumentId, activationGeneration}]
```

Members are exactly K0. Internal edges are precisely active edges with both endpoints in K0, sorted by `occurrenceIdentity`, with no deduplication of distinct paths. `activationGeneration` is a canonical unsigned decimal string. Source paths are the exact escaped pointer strings authenticated by the occurrence, not normalized display paths. Hash with domain `blue-operation-owner/1.0-draft.2`. The same member set rejoined by new occurrence generations has a different owner identity; a physical global graph counter is excluded.

The closed `RootProcessingContext` is exactly:

```text
canonicalRootDocumentId
operationOwnerIdentity
```

Verify the entire owner descriptor and root projection before accepting the context. Its domain is `blue-contracts-root-processing-context/1.0-draft.2`. The requested public view is outside that object. An actual Operation Request targeting a different document is not an alternate public view: the original exact cause differs and remains distinct.

### 10.4 Canonical base input and delivery identity

**RCP-ID-03 — no hidden entrypoint dependence.** Before calculating the existing base invocation identity, project its exact snapshot from the canonical anchor and owner context, preserve the full required forward dependency views, and normalize orchestration-only root flags to this owner. Preserve semantic application fields and the exact original cause byte-for-byte. Do not include unrelated observer rows, global graph-generation counters, physical timestamps, request routing choices or unneeded newer source heads. Required immutable proof views are sorted and bound under the existing constructors.

For internal terminal idempotency, construct `DeliveryBasis` from exactly:

```text
operationOwnerIdentity
causeIdentity
kind                        ADMISSION | LIVE | MANAGED_REVISION | MANAGED_REPRESENTATION
receivingBindings: sorted [{documentId, scopePath, activationGeneration, channelKey,
                            occurrenceIdentity}]
sourcePositionIdentity     tagged {kind:"NONE"} or {kind:"POSITION", identity:SHA}
```

`receivingBindings` is the verified pre-input checkpoint-owning direct set for LIVE, the affected application binding for a retained cause, or the initialized owner binding set for admission. A synthetic admission/retained channel address uses the base specification's actual cause-owner address; the adapter must not guess a user Channel. Sort by unsigned UTF-8 bytes of the JCS record; identical rows reject. Channel grouping remains the base rule and cannot merge genuinely distinct deliveries. For a retained cause, `sourcePositionIdentity` binds its authenticated exact predecessor/ordinal position, including event-only and representation positions. It is NONE for LIVE/admission. The optionality uses the closed tags above, not an empty hash.

Hash with domain `blue-rooted-delivery-basis/1.0-draft.2`. Then:

```text
rootedInvocationIdentity = H("blue-contracts-rooted-invocation/1.0-draft.2",
 {baseInvocationIdentity, rootProcessingContextIdentity, deliveryBasisIdentity})

rootedCommitCompanionIdentity = H("blue-contracts-rooted-commit-companion/1.0-draft.2",
 {baseCommitCompanionIdentity, rootedInvocationIdentity, rootProcessingContextIdentity})

rootedTerminalKey = H("blue-coordination-rooted-terminal-key/1.0-draft.2",
 {operationOwnerIdentity, deliveryBasisIdentity})
```

There is no reference from the history basis to this later result; genesis/creation DAG inputs precede the operations that consume them. Companions bind the existing actual resulting records/fences, not a reported success boolean. Equal payload events still have distinct base occurrence identities. Cold versus cached compute does not enter any constructor.

### 10.5 Existing cycles, merge, split and retry

**RCP-ID-04 — freeze the entry context.** The operation context/delivery basis is fixed at admission of the supplied input. A cycle-forming operation begins with its original K0 and acquires additional writable members under OWN-03 without recomputing its terminal identity or resetting its meter. The resulting companion binds the expanded result. On a split, the entry group remains co-owned for this operation; later operations derive new group descriptors from the resulting graph. Retrying a pending operation uses its retained original context, not today's group partition.

A retry after successful publication reconciles the existing terminal companion and checkpoint/cursor. It does not create another operation because the requested view changed or the cycle now has a new canonical anchor. A new, genuinely distinct Operation Request remains a distinct cause. Stale-checkpoint rejection and receipt ownership checks remain required even when operation identity canonicalization is correct.

### 10.6 Compatibility and finite identity obligations

**RCP-ID-05 — migration.** These are draft.2 domains. Draft.1 wrappers remain historical prototype material; base release 1.0/1.1 constructors and old receipts remain unmodified. An old session is not relabelled by changing a version string. Each runtime must advertise the adopted profile, verify its exact constructor/input bindings, and either apply an explicit reviewed compatibility path or reject an unsupported history. No production RC coordinate or BlueId is allocated by this package.

The identity vectors test exact hashes, reorder invariance of sets, A/B group entrypoint equality, distinction of actual causes and occurrence generations, dynamic entry-context freezing, and malformed/extra-field rejection. They verify these wrappers only. Blue cyclic MASTER/index finalization, source identity, actual authority, and real receipt verification still require the existing implementation/conformance mechanisms.


## 11. Conformance and migration

The candidate suite is `../conformance/rooted-processing/`. Normative observable assertions are separate from abstract selector/reference-model checks and production result receipts. All production adapter results start `NOT_RUN`.

Conformance requires root isolation, independent channel progress, exact historical views, interleaved source histories, duplicate occurrence/event handling, dynamic topology, cold/warm equivalence, gas boundary behavior and durable no-duplication. The worked A10/A5 cyclic join must execute in the real runtime; a merge-model pass cannot certify it.

Legacy fixture bytes and their exact manifests are preserved under `../baseline/`. They are historical input material, not freshly accepted outputs for RCP-1. The disposition register distinguishes preserved semantic assertions, identity-only rebinding and scenarios that need new expectations because the old reverse-observer scope was intentional. No negative assertion is removed merely because it fails.

The publication package, runtime/profile/type bindings, production gas traces and affected full fixture oracles must be regenerated by the implementation under these target rules and independently reviewed. This draft's SHA-256 inventory authenticates files only; it is not `IMPLEMENTATION_CONFORMANT`, a sealed RC or a public artifact release.

## 12. Informative proof and implementation freedom

For ordered pending suffixes, let h be the minimum head. If another pending x were earlier, its suffix head would be no later than x and hence earlier than h: contradiction. Thus heads suffice. Repeating the argument after each deterministic progress/topology update establishes deterministic selection, given exact evidence and specified processing.

This does not prove arbitrary BEX code, cryptographic evidence, cyclic finalization, gas tariff or storage correctness. Separate storage refines the reference calculation only if every visible read, occurrence, result, charge/failure and publication observation agrees. The proof and bounded exhaustive tests support that obligation; they do not replace production conformance.
