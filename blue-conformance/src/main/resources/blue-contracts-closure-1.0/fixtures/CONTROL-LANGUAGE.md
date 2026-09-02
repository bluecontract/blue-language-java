# Blue Contracts 1.0 fixture control language

## 1. Status and purpose

This file defines every non-Blue control accepted by the fixture envelope `blue-contracts-fixture/1.0`. It is normative for the conformance package. A runner MUST reject any control field not declared by `fixture-schema.yaml` and this document.

Controls prepare exact inputs or deterministic fixture implementations. They never bypass `PROCESS`, write the result directly, suppress required validation, or alter portable gas merely because a fixture requested a scenario.

## 2. Preparation order

For `process`, `process-attempt`, and `platform`, the runner performs these steps in order:

1. Parse the fixture under `fixture-schema.yaml`.
2. Apply `input.builders` to a private copy of `input.root`.
3. Parse and validate the resulting Root and `input.event` as Blue Language 1.0 values.
4. Verify every `input.provider.nodes` entry against its map key.
5. Load the exact fixture runtime registry named by `runtime.typeRegistryManifest`.
6. Install the deterministic scripted implementations described below.
7. Derive the full canonical ExternalDelivery snapshot from Root, event, intervals, and registry.
8. Check every `feeder.deliverySnapshot` hint against that derivation.
9. Invoke the selected fixture operation.
10. Evaluate assertions against the closed projection catalog.

A builder or runtime script that creates invalid Blue content causes the same deterministic admission or runtime failure that ordinary content would cause.

## 3. Builders

Builders exist only to avoid committing megabytes of repetitive literal YAML. Their expansion is deterministic and occurs before Blue parsing. The `kind` field selects exactly one builder algorithm from the table below.

| Kind | Required fields | Exact expansion |
|---|---|---|
| `generated-object` | `target`, `memberCount`, `keyPrefix`, `value` | Set `target` to an object with keys `keyPrefix + zero-padded decimal index`, for indexes `0..memberCount-1`, each containing a deep copy of `value`. Padding width is the decimal width of `memberCount - 1`, with width one for an empty or one-item object. |
| `generated-list` | `target`, `itemCount`, `item` | Set `target` to a list of `itemCount` deep copies of `item`. |
| `repeated-text` | `target`, `codePointCount`, `text` | `text` MUST contain exactly one Unicode code point. Set `target` to that code point repeated `codePointCount` times. |

The target is an RFC 6901 pointer. Missing intermediate objects are created. Existing scalar/list intermediates are an invalid fixture.

## 4. Provider controls

`mode: exact-node` means each provider map key is a plain BlueId and each value is exact BlueId Input whose Node BlueId MUST equal that key.

`semanticDemandsOnly: true` prohibits physical cache/page/chunk observations from appearing in portable traces.

`transientUnavailableAt` names one canonical demand phase or exact demanded BlueId. The first matching demand produces `PROCESS_ATTEMPT -> NeedsResources`; no `ProcessResult`, progress, state, events, or portable gas exists for that attempt.

## 5. Runtime controls

### 5.1 `handlers`

The key is an absolute Root pointer to a fixture `ScriptedHandler`. Its `result` is returned when—and only when—the ordinary Contracts processor selects and executes that exact Handler. Patches, events, termination, failures, and named runtime counters pass through normal result normalization, boundary checks, charging, cascades, checkpointing, and rollback.

### 5.2 `initializationPatches`

Installs one fixture Lifecycle Handler at every participating scope selected by the fixture. It returns the listed patches only for `Document Processing Initiated`. The patches are not host writes.

### 5.3 `childEmissions`

Installs one selected child Handler that emits the listed values in list order. The values enter the ordinary internal EventOccurrence queue.

### 5.4 `rootForwardAll`

Installs a Root Embedded Node Handler that explicitly re-emits every received descendant event once, preserving order and multiplicity. This is application behavior; descendant events are not public without this handler.

### 5.5 `nestedEnqueues`

Installs a deterministic Triggered Handler that emits the next numbered fixture event until exactly `nestedEnqueues` events have been enqueued. It exercises queue order and limits through normal event delivery.

### 5.6 `cascadeMutation`

This control installs fixture Document Update or Lifecycle Handlers that perform the named mutation at the named causal point:

- `afterPatchIndex`: zero-based patch index after whose complete update cascade the mutation runs;
- `replaceScope`: exact embedded scope root replaced by a valid fixture replacement node;
- `thenReaddSamePath`: after replacement/removal, add a fresh node at the same path during the same invocation;
- `replaceScopeDuringLifecycle`: perform replacement while the selected lifecycle delivery is active;
- `sourceCutOffDuringUpdate`: replace/remove the update source scope while its update is propagating.

These handlers are processed normally and are the only cause of the mutation. The control never mutates run state directly.

### 5.7 Generalization controls

`generalizationCandidates` is the exact existing ancestor chain supplied by the fixture type provider, most-specific first. `validCandidate` is the first candidate whose fixture validation function returns valid. The runner MUST still execute the normative nearest-valid-ancestor algorithm and report its candidate order.

### 5.8 Termination controls

`terminationRequests` installs ordered fixture results that request successful graceful termination with the supplied application `cause` and optional `reason`. The first request wins.

`gasLimitDuringTermination: true` selects the smallest fixture gas limit that admits the preceding work but rejects the next canonical termination charge. It is a shorthand for a precisely derived limit, not host intervention during execution.

`gasLimit` directly sets the invocation limit for that fixture.

## 6. Feeder controls

`managedRootRevision`, `indexedRootRevision`, and `eventOrderKey` are exact platform state for the attempt.

### 6.1 Delivery snapshot hints

`deliverySnapshot` is a compact fixture hint, not the normative `ExternalDelivery` value. Each hint contains `scopePath`, `channelKey`, optional asserted `order`, and optional interval frontier. The runner MUST independently derive the full snapshot:

```text
scopePath
channelKey
orderedSourceContributionNodeBlueIds
effectiveTypeBlueId
order
checkpointDomainBlueId
```

It then verifies that the hints identify exactly the same ordered occurrences and that any supplied order/frontier agrees. A hint never supplies missing identity fields to `PROCESS`.

Every non-root scope path must be reachable at each ancestor through either an effective exact `Process Embedded.paths` declaration or one concrete direct object member generated from an effective `Process Embedded.collectionPaths` declaration. Every selected scope must contain the named effective External Channel. The validator performs this check for fixture content that is statically available.

For collection declarations, the runner reads the complete direct ordinary member-key set, orders keys by Unicode code point, escapes each key as one Runtime Pointer segment, and derives concrete paths. A compact hint always names the resulting concrete path. The runner MUST reject list targets, non-object members, duplicate/overlapping declarations, wildcard syntax, reserved-field traversal, and cyclic-member boundaries.

### 6.2 Remaining feeder controls

| Control | Exact meaning |
|---|---|
| `canonicalPreselection` | Expected compact occurrence hints after raw-index filtering and before complete acceptance. |
| `rawIndexCandidates` | Physical over-approximation returned by the fixture index. False positives are permitted; omissions are not. |
| `acceptanceStateVariants` | Root-state cases used only to prove that immutable External Channel acceptance does not depend on mutable business state. |
| `channelLawCases` | Truth table rows checked against `ACCEPTS => PRESELECTS => key intersection`. |
| `currentEventAddsChannel` | The event's successful transition adds a new channel; it begins strictly after the current event key and cannot join the current snapshot. |
| `intervalHistory` | Ordered add/remove/re-add actions used to derive fresh activation intervals. |
| `targetsByEvent` | Exact retained target order for each queued external event. |
| `eventQueue` | External events waiting at the feeder; one event's retained delivery set must reach terminal progress before the next begins. |
| `evaluatedRevision` | Root revision against which a terminal outcome was calculated. |
| `casConflict` | The final compare-and-swap fails because the current Root revision differs. No portable gas is added by the conflicted persistence attempt. |
| `sameFailureCount` | Number of identical revision-bound failures already recorded for the quarantine-policy fixture. |

## 7. Variants

Every variant is a complete deterministic transformation of the base input:

- `rootForm`: inline, pure reference, eager materialization, or lazy materialization of the same exact node;
- `rootOverrides`: ordered-by-field JSON Pointer replacements used to express semantically equivalent nested representations, such as an inline value versus its verified pure reference; a Root replacement is forbidden;
- `cache`: warm/cold physical provider state, never semantic evidence;
- `batching`: batched/unbatched physical retrieval;
- `accept`: replace the fixture channel's immutable `accept` header;
- `checkpointSubject`: replace the exact subject returned by the fixture channel function;
- `listOperation`: run the declared append or head-replacement identity scenario;
- `newEmbeddedSurface`: exact replacement value for the changed embedded declarations;
- `rootRevision`: use the stated managed Root revision;
- `sameEvent`: retain the exact event identity when testing retries.

A variant name alone has no semantics; every variant object MUST declare the transformation fields it uses.

## 8. `gas-micro`

A gas microfixture does not execute `PROCESS` unless it explicitly provides Root/event/runtime controls. Its input describes one counter or formula. Its expected trace and total are exact. Unknown counter/formula inputs fail closed.

## 9. Scripted External Channel dependency and routing fields

The conformance-only `Scripted External Channel` registry type implements the generic same-scope Channel dependency and logical-delivery laws from Contracts §3.3.

Its Blue fields have these exact meanings:

| Field | Semantics |
|---|---|
| `dependencyMode: none` or absent | Declares no peer Channel dependency. Event-time lookup of another key is forbidden. |
| `dependencyMode: exact` | Declares exactly `dependentChannelKey`; event-time lookup is permitted only for that raw same-scope key. |
| `dependencyMode: catalog` | Declares the bounded complete same-scope Channel header catalog. Event-time exact-key lookup is allowed against that frozen catalog. |
| `handlerChannelKey` | Requested same-scope Channel used for Handler binding after the source accepts. It does not become an external source and receives no source checkpoint. |
| `logicalDeliveryKey` | Logical grouping key. When absent, the raw source channel key is used. |
| `fallbackToSourceOnAbsentOrNonChannel` | If true and lookup yields `ABSENT` or `NON_CHANNEL`, the raw source key remains the Handler Channel. If false, the fixture source rejects the delivery. Incomplete or undeclared evidence never falls back. |

The scripted implementation derives payload, checkpoint domain, checkpoint subject, target key, and logical-delivery key from immutable header fields and the exact event. It does not inspect mutable business fields. Several fresh sources in one `(scopePath, logicalDeliveryKey)` group execute Handlers once only when their exact payload and target identities agree. Each fresh source retains its own checkpoint authority.


## 11. Exact participant bindings and parent lookup

Fixtures may place the same exact Channel node inline or behind a pure BlueId reference. The runner MUST treat these forms identically after exact verification. It MUST NOT import a parent or ancestor Channel into an embedded scope merely because the raw key is equal. The fixture runtime has no implicit Parent Channel feature.
