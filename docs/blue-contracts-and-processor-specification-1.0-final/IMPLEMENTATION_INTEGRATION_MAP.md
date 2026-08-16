# Implementation Integration Map

This document is informative. It maps the final Contracts 1.0 semantics to the
current Java repositories so implementation can proceed without another
specification round.

## Semantic ownership

```text
Blue Language
    exact nodes, direct BlueIds, cyclic-set identity and proof

Blue Contracts
    ordinary PROCESS, affected-closure processing, initialization, Channels,
    Handlers, patches, events, checkpoints, one shared gas ledger, validation,
    exact closure result and atomic commit companion

Coordination
    append one external entry, prove/choose direct targets, supply stable
    DocumentId and occurrence bindings, select historical revision evidence,
    construct the exact closure snapshot, invoke Contracts, and atomically
    publish the complete returned closure
```

The processor does not discover provider history or delegated authority. The
host does not execute application workflows or calculate cyclic BlueIds.

## `blue-language-java`

### Existing Language classes to preserve

```text
blue-language-core/.../identity/CircularSetIdentityCalculator.java
blue-language-core/.../provider/CyclicSetProof.java
blue-language-core/.../provider/CyclicSetProofResult.java
blue-language-core/.../provider/CyclicAwareNodeProvider.java
```

Only additive APIs exposing complete finalization/member-mapping/proof results
should be needed. The underlying Language §15 algorithm and `MASTER#index`
format remain unchanged.

### Contracts classes to extend

```text
blue-contracts-core/.../processor/DocumentProcessor.java
blue-contracts-core/.../processor/ProcessorInvocationOrchestrator.java
blue-contracts-core/.../processor/ProcessorExecutionContext.java
blue-contracts-core/.../processor/EmbeddedScopePlanner.java
blue-contracts-core/.../processor/ProcessGasMeter.java
blue-contracts-core/.../processor/GasSchedule.java
blue-contracts-core/.../processor/PlatformCommitCompanion.java
blue-contracts-core/.../processor/ExternalDeliveryPlan.java
blue-contracts-core/.../processor/VerifiedExecutionEvidence.java
```

Add the closure model, deterministic component/work planning, exact temporary
cyclic finalization, dynamic reclassification, component-safe initialization,
and a complete closure result. Preserve the existing ordinary PROCESS path as
the one-document acyclic fast path.

## `../blue-contract-java` (canonical project `blue-coordination-java`)

### Current assumptions to replace

```text
ProcessEmbeddedGraphSnapshot.validateAcyclic()
recursive child-first scheduling as the only graph algorithm
same-entry independent child and parent publication
lossy managedOwnershipProjection / processingRoot
host-side direct child materialization
fresh gas meter for every factorized document invocation
```

### Integration components

```text
ManagedGraphProjector
ComponentIndexBuilder
AffectedClosurePlanner
ExternalEntryClosureExecutor
AdmissionClosureExecutor
ClosureCommitCoordinator
ClosureCommitReconciler
HistoricalRevisionEvidenceResolver
CoordinationExecutionPolicyResolver
BlockedEntryRegistry
```

Coordination should use one immutable graph/component index and one
storage-neutral closure commit transaction. It may retain document epochs and
indexes as operational/audit evidence, but it must publish same-entry closure
results atomically.

## Exact implementation order

```text
1. Run Prompt 00 and freeze package hashes.
2. Implement Prompt 01 in blue-language-java.
3. Execute all ordinary and closure Contracts fixtures.
4. Publish one exact Contracts artifact and conformance report.
5. Implement Prompt 02 in `../blue-contract-java` against that artifact.
6. Run closure fixtures through the Coordination adapter plus all existing
   Counter/NBA/Wadowice regressions.
7. Run Prompt 03 cross-repository clean-release gates.
```

Use `prompts/PROMPT_04_IMPLEMENTATION_SEQUENCE.md` as the orchestration entry
point. Language/Contracts production remains Java 8; Coordination production is
Java 17 with Java 17 and Java 21 test lanes. The Contracts artifact handoff must
use a staged/published coordinate and exact JAR hash, not an implicit sibling
source substitution.

## No further specification changes expected

The final specification already defines:

```text
static and dynamically formed cycles
exact temporary identity visibility
one or several SCCs in one closure
admission without an external event
component merge and split
containing Root results
managed occurrence binding
work/event/transition identities
frozen edge behavior
shared gas/default/local-cap behavior
A epoch 10 with an attached epoch-5 occurrence
public Root event boundary
atomic rollback and commit evidence
```

Implementation must stop and report a genuine contradiction rather than adding
an undocumented behavior. Performance optimizations, storage adapters,
provider-backed Timeline completeness, delegated authority and UI integration
are separate layers and do not require changing these Contracts semantics.

## Mandate compatibility

No Mandate rule is part of the Language or Contracts normative package.
Mandate documents can still use the generic type, Channel, Handler, workflow,
lifecycle, event and checkpoint mechanisms. A later Coordination feeder can
resolve exact historical authority and include or withhold an external delivery
before Contracts processing, without changing this specification.
