# Processor results, diagnostics, and recovery

This guide explains how a host should interpret a completed Contracts 1.0
`ProcessResult`, with particular attention to portable-limit and subscription-
surface failures. For channel and handler execution rules, see
[Processor contract matching](processor-contract-matching.md). For the complete
normative model, see the
[bundled Contracts specification](../src/main/resources/specifications/blue-contracts-and-processor-specification-1.0.md).

## Completed result contract

`PROCESS(Root, event)` returns:

```text
ProcessResult {
    status
    document
    events
    totalGas
    diagnostic?
}
```

Only `success` commits. Every noncommitting status returns the exact input Root
and an empty Root event sequence. `totalGas` is the exact gas admitted before
completion or failure; admitted gas is not rolled back with application effects.

The result status is authoritative. A diagnostic explains a deterministic
failure, but it does not decide whether the result commits and is not part of
Root or event identity.

| Status | Commits | Diagnostic | Host interpretation |
| --- | ---: | ---: | --- |
| `success` | Yes | No | Use the resulting Root and Root events. A host-managed subscription index must commit through the separate platform companion described below. |
| `no-match` | No | No | No eligible Channel or Handler accepted the event. Record terminal progress against the exact unchanged Root revision. |
| `stale` | No | No | The accepted occurrence was not newer than its checkpoint. Record terminal progress against the exact unchanged Root revision. |
| `terminated` | No | No | The input Root already carried a valid direct termination marker. Record terminal progress against the exact unchanged Root revision. |
| `invalid-processing-document` | No | Yes | Reject invalid Root, event, reserved state, or execution evidence. The diagnostic category identifies the precise cause. |
| `capability-failure` | No | Yes | A required runtime type, role, or contract capability is unsupported or cannot be interpreted safely. |
| `runtime-fatal` | No | Yes | Deterministic admitted execution failed. Fix the contract, runtime, or data before retrying. |
| `gas-limit-exceeded` | No | Yes | The next canonical gas charge could not be admitted. |
| `portable-limit-exceeded` | No | Yes | A fixed structural or cardinality boundary was exceeded. More gas does not make the input portable. |
| `subscription-surface-invalid` | No | Yes | The input or tentative Root cannot produce a finite, canonical external-subscription index. |

`DocumentProcessingResult.commits()` is the convenient Java check for the first
column. Do not assume that every noncommitting result has a diagnostic:
`no-match`, `stale`, and `terminated` are normal terminal outcomes.

```java
DocumentProcessingResult result =
        processor.processDocument(document, event);

if (result.commits()) {
    Node committedDocument = result.document();
    List<Node> rootEvents = result.events();
    // Consume the semantic result. It does not itself contain a subscription delta.
} else {
    ProcessorDiagnostic diagnostic = result.diagnostic();
    if (diagnostic == null) {
        // Expected non-error outcome: NO_MATCH, STALE, or TERMINATED.
        recordTerminalProgress(result.status());
    } else {
        handleDeterministicFailure(
                result.status(),
                diagnostic.category(),
                diagnostic.details(),
                diagnostic.message());
    }
}
```

The helper calls above represent host policy; they are not library methods.
`DocumentProcessingResult` intentionally contains only the five semantic result
fields. A host that persists Root revisions, delivery progress, and an external
subscription index must use `processDocumentForPlatformCommit(...)` and commit
its separate `PlatformCommitCompanion` atomically with the semantic result.

## Diagnostic contract

A `ProcessorDiagnostic` contains:

```text
category
message?
details?
```

- `category` is the stable PascalCase `ProcessorErrorCategory` protocol value.
  Use it for programmatic classification.
- `message` is optional human-readable prose. It must not control program logic
  or cross-language conformance.
- `details` is an immutable `Map<String, String>` of stable context. Compare it
  as key/value data rather than serialized object-key order.

The built-in structured detail bundles are:

| Failure | Stable details |
| --- | --- |
| Gas exhaustion | `namespace`, `counter`, `quantity`, `weight`, `admittedGas`, `gasLimit`, `effectiveBudget` |
| Portable limit | `limitName`, `observed`, `limit` |
| Subscription surface | Optional `scopePath` and `contractKey` |
| Categorized runtime abort | `scopePath` |

Other built-in failures normally carry a category and message with an empty
detail map. Extensions may add stable details, but must not include stack
traces, exception class names, timestamps, cache state, transport data, or
locale-dependent text as machine-readable context.

### Category vocabulary

Status identifies the broad result boundary; category identifies the precise
reason. They are not one-to-one. For example, `runtime-fatal` can carry
`InvalidPatch`, `CheckpointPolicyError`, or `RuntimeExecutionFailure`, while
`subscription-surface-invalid` normally carries `SubscriptionSurfaceInvalid`
but may preserve a more precise underlying category.

The exact current Java protocol vocabulary is the
[`ProcessorErrorCategory` enum](../src/main/java/blue/language/processor/ProcessorErrorCategory.java):

- input: `InvalidProcessingDocument`, `InvalidProcessingEvent`;
- runtime pointers, contracts, and patches: `InvalidRuntimePointer`,
  `InvalidPatch`, `PatchBoundaryViolation`,
  `ProtectedProcessorStateMutation`, `InvalidReservedRuntimeState`,
  `UnsupportedRuntimeType`, `UnsupportedRuntimeRole`, `InvalidContractKey`,
  `InvalidContractBinding`;
- evidence, routing, subscriptions, and checkpoints:
  `InvalidExternalChannelSnapshot`, `ExternalSubscriptionLawViolation`,
  `EmbeddedRouteNotFound`, `EmbeddedScopeNotObject`, `EmbeddedScopeCycle`,
  `ActiveScopeCutOff`, `CheckpointDomainError`, `CheckpointPolicyError`,
  `InconsistentLogicalDelivery`;
- values, schemas, generalization, and cyclic sets: `FixedValueConflict`,
  `TypeCompatibilityViolation`, `SchemaViolation`,
  `TypeGeneralizationFailure`, `CyclicSetMutationUnsupported`,
  `CyclicMemberProcessingRootUnsupported`,
  `CyclicMemberProcessingEventUnsupported`,
  `CyclicSetEmbeddedBoundaryUnsupported`;
- portable limits and gas: `DirectNodeLimitExceeded`,
  `MatchingDeliveryLimitExceeded`, `ParticipatingScopeLimitExceeded`,
  `InternalEventLimitExceeded`, `PatchLimitExceeded`,
  `RuntimeLedgerLimitExceeded`, `GasLimitExceeded`;
- general surface and runtime failures: `SubscriptionSurfaceInvalid`,
  `RuntimeExecutionFailure`.

Consumers should preserve the exact PascalCase value and must not derive a new
category from the diagnostic message or `limitName`.

## Portable-limit failure

### What it protects

Portable limits are structural limits bound by the selected gas-manifest
package. They ensure that every implementation using that package can bound
individual collections, recursion, identity input, event queues, patches, and
runtime ledgers independently of CPU speed or memory size.

Portable limits are different from gas:

- gas is the accumulated weighted semantic work of the invocation;
- a portable limit bounds one exact dimension;
- an input may have gas remaining and still exceed a portable limit;
- increasing only the gas budget cannot repair a portable-limit failure.

The exact names and values come from the
[bundled Contracts gas manifest](../src/main/resources/blue/language/processor/contracts-gas-1.0.yaml).
It binds limits for contract-result patches and events, internal and Root event
queues, participating and embedded scopes, pointer and key sizes, direct
containers and identity input, type chains, cascade depth, and runtime-ledger
shape. These limits are ceilings, not promises that an input at the ceiling fits
under the gas budget.

### Example

For example, if the bound manifest allows 1,024 patches per contract result and
one Handler attempts to return 1,025:

```text
status = portable-limit-exceeded

diagnostic = {
    category = PatchLimitExceeded
    message = "Portable limit exceeded: patchesPerContractExecutionResult"
    details = {
        limitName = "patchesPerContractExecutionResult"
        observed = "1025"
        limit = "1024"
    }
}
```

The category identifies the limit family, while `limitName` identifies the
exact manifest boundary. Public limit-family categories include:

- `DirectNodeLimitExceeded`;
- `MatchingDeliveryLimitExceeded`;
- `ParticipatingScopeLimitExceeded`;
- `InternalEventLimitExceeded`;
- `PatchLimitExceeded`;
- `RuntimeLedgerLimitExceeded`.

Several concrete manifest limits can share one family category. Hosts should
therefore retain both `category` and `details.limitName`.

A portable limit known during admission can fail with zero gas. A limit reached
after semantic execution begins reports the gas admitted before the failed
check. In either case the rejected observation is not partially accepted.

### Recovery

Repeating the same Root, event, evidence, registry, and limit manifest produces
the same failure. A host should not blindly retry it. Recovery requires one of:

- reducing or partitioning the document, event, emitted effects, or embedded
  scope structure;
- changing the responsible contract or runtime behavior;
- adopting a different limit only as part of a compatible, identity-bound
  protocol manifest.

Changing cache size, worker memory, thread count, or the ordinary gas budget is
not a portable fix.

## Subscription-surface failure

### What the surface represents

The external subscription surface is the finite set of External Channel
occurrences the managing feeder must observe for one Root revision, including
occurrences in transitively declared Process Embedded scopes.

One occurrence carries deterministic indexing and revalidation information,
including:

```text
scopePath
channelKey
ordered source-contribution BlueIds
effective type BlueId
channel order
subscription keys
checkpoint domain
same-scope dependency snapshot
activation and retirement bounds
```

Without this surface the feeder cannot know which external sources and keys to
observe, or prove that a later delivery belongs to the same Root revision.

### Pre-commit validation

The processor can reject an invalid input surface during preflight or while
recognizing a changed embedded closure. After successful Handler execution, all
state is still tentative, and the same failure boundary protects final
before/after validation. If changed paths can affect subscriptions, the
`SubscriptionSurfaceValidator` derives the affected surface before and after
the tentative change:

```text
before = SUBSCRIPTION_SURFACE(input Root)
after  = SUBSCRIPTION_SURFACE(tentative Root)

removed = before - after
added   = after - before
```

The resulting `SubscriptionDelta` is canonically ordered. A platform commit
must atomically install the new Root revision, Root outbox, subscription delta,
activation or retirement intervals, and delivery progress. A failure in
surface derivation therefore rejects the entire tentative invocation.

### What makes a surface invalid

The validator fails closed when it cannot derive one finite, unambiguous,
deterministic index. Examples include:

- an External Channel returns no subscription-key set, an empty set, duplicate
  keys, null keys, or more keys than the manifest permits;
- the same immutable subscription function produces different output or gas
  trace when evaluated again;
- a Process Embedded path is malformed, duplicated, cyclic, ambiguous, or
  selects a non-object child;
- embedded ancestry revisits the same exact node;
- more than one effective Process Embedded contract exists in one scope;
- the effective `contracts` value is not a direct object map;
- two entries create the same external-subscription occurrence;
- effective Channel content, source contributions, dependencies, activation
  data, or checkpoint-domain identity cannot be established;
- the committing Root revision would overflow.

For example, a Channel returning duplicate keys:

```text
channelKeys(snapshot) -> ["customer/42", "customer/42"]
```

produces a result shaped like:

```text
status = subscription-surface-invalid

diagnostic = {
    category = SubscriptionSurfaceInvalid
    message = "Subscription keys must be unique non-empty Text"
    details = {
        scopePath = "/"
        contractKey = "<external-channel-key>"
    }
}
```

`scopePath` and `contractKey` are optional because some failures concern the
whole Root. When a more precise deterministic failure caused surface
validation, its category may be preserved instead of the general
`SubscriptionSurfaceInvalid` category.

### Portable limits at this boundary

The two statuses describe different failure boundaries:

- `portable-limit-exceeded` means an explicit portable size or cardinality
  guard rejected work;
- `subscription-surface-invalid` means the Root cannot produce a valid
  subscription index.

A generic portable guard reached during surface processing still produces
`portable-limit-exceeded`. A surface rule such as duplicate keys, ambiguous
routes, nondeterministic subscription functions, or a surface-specific
cardinality violation produces `subscription-surface-invalid`.

### Recovery

More gas does not repair an invalid subscription surface. The Root or runtime
must be changed so every active External Channel has deterministic finite keys,
valid embedded ancestry, exact dependency evidence, and one canonical
checkpoint domain.

Hosts should use `processDocumentForPlatformCommit(...)` when they persist Root
revisions and the external subscription index. Its `PlatformCommitCompanion`
binds the exact input revision and validated `SubscriptionDelta` required for
the atomic compare-and-swap. Only committing success carries the validated
delta. A validated noncommitting semantic result can carry a progress-only
companion, but never a new Root, Root outbox, or non-empty subscription delta.
If the compare-and-swap fails because the authoritative Root changed, the host
records nothing and recomputes against the new revision.

## Failure, suspension, and retry

Resource unavailability is not a diagnostic status. `PROCESS_ATTEMPT` returns:

```text
Complete(ProcessResult)
or
NeedsResources(sortedExactBlueIds)
```

For `NeedsResources`, the host acquires and verifies the named exact nodes
outside deterministic execution, then retries from the exact same Root and
event. Suspension commits no Root, events, progress, or portable gas. The
ordinary `processDocument(...)` API propagates
`ExecutionEvidenceUnavailableException`; `processAttempt(...)` converts it to
`NeedsResources` only when the missing resources can be represented by exact
BlueIds. Unavailability without such an exact demand remains a host exception.

By contrast, portable-limit failure, subscription-surface failure, gas
exhaustion, capability failure, runtime failure, and deterministic invalid
inputs are completed terminal results for that exact Root revision. A platform
should record their terminal progress using compare-and-swap and should
quarantine or explicitly administer a deterministic poison event rather than
retrying it without a change.

The ordinary `processDocument(...)` API converts deterministic invalid delivery
evidence to an `invalid-processing-document` result. The
`processDocumentForPlatformCommit(...)` boundary instead throws
`InvalidExecutionEvidenceException`: untrusted evidence cannot produce a
trustworthy compare-and-swap companion or progress record.

Programming and lifecycle errors such as null arguments, a closed processor, or
initializing an already initialized document are Java exceptions, not completed
`ProcessResult` diagnostics.

## Cross-language determinism checklist

Java, JavaScript, and other implementations agree when they use the same:

- graph-equivalent Root and event;
- verified revision-bound delivery evidence;
- runtime registry identity and deterministic runtime behavior;
- gas manifest, gas budget, and portable-limit manifest;
- normative phase ordering and failure precedence.

Cross-language conformance compares:

- status wire value;
- diagnostic category;
- relevant structured details such as scope, key, path, and numeric limit;
- resulting Root BlueId;
- ordered Root event BlueIds;
- total gas;
- when using the separate debug or conformance API, the exact out-of-band gas
  trace.

Diagnostic prose is informative and may differ. Do not compare `message`,
serialized map-key order, stack traces, physical fetch counts, cache hits, or
allocation behavior. JavaScript implementations must preserve exact integer
semantics for gas and limit observations and must not rely on ordinary object
enumeration when the Contracts algorithm requires canonical Unicode code-point
ordering.
