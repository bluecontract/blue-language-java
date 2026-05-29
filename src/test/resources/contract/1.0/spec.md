# Blue Contracts and Processor Specification 1.0

> **Positioning.** Blue contracts are Blue's form of **smart contracts**: deterministic, content-addressed runtime declarations attached to Blue documents. They react to events, invoke supported channel and handler implementations, and update document state only through the processor rules defined by this specification. Unlike blockchain-specific smart contracts, Blue contracts do not imply any particular consensus protocol, ledger, account model, token model, authorization system, network transport, or persistence layer.

> **Scope.** This document defines Blue runtime contract processing: contracts, channels, handlers, markers, active scopes, embedded document processing, lifecycle events, JSON patch execution, update cascades, event FIFOs, embedded-event bridging, checkpoints, termination, gas accounting, and processor conformance. It does **not** define the Blue content language, BlueId, type resolution, schema, canonicalization, expansion, or minimization. Those are defined by the separate **Blue Language Specification 1.0**.

Where this document references runtime types such as **Contract**, **Channel**, **Handler**, **Marker**, **Document Update Channel**, **Triggered Event Channel**, **Lifecycle Event Channel**, **Embedded Node Channel**, **Process Embedded**, **Channel Event Checkpoint**, **Type Generalization Policy**, **Type Generalization Rule**, and processor-emitted events, their canonical type definitions and canonical BlueIds are supplied by the canonical Blue runtime type registry.

Appendix A defines the normative runtime semantics of those core runtime types and shows their intended registry source nodes. The canonical registry is the authority for exact node content and BlueIds.

Canonical runtime type nodes are identity-bearing Blue content. Their `description` fields define runtime semantics and affect BlueId. Editing a canonical runtime description changes the runtime type identity and therefore must be treated as a registry/versioning change, not as ordinary documentation editing.

## Conventions

The key words **MUST**, **MUST NOT**, **REQUIRED**, **SHOULD**, **SHOULD NOT**, **MAY**, and **OPTIONAL** are to be interpreted as normative requirement levels.

Sections marked **normative** define required behavior for conforming Blue Contracts and Processor 1.0 implementations. Sections marked **informative** explain intent, examples, or implementation guidance.

The term **Blue Language** means Blue Language Specification 1.0 unless another version is explicitly named.

---

## 0. Overview

Blue contracts are smart contracts for Blue documents: they make a Blue document executable in a deterministic, content-addressed way.

A processor is a deterministic state-transition function:

```text
PROCESS(document, event) -> (new_doc, triggered_events, total_gas)
```

- **document** is a Blue processing document: a valid Blue document after Blue Language preprocessing, suitable for runtime interpretation.
- **event** is a Blue node delivered by an external feeder or by a calling environment.
- **new_doc** is the updated document after all processing performed by this invocation.
- **triggered_events** is the root-scope outbox for this invocation, including root-scope triggered events and root lifecycle events, whether or not they were handled locally.
- **total_gas** is a deterministic tally of abstract gas units consumed during the invocation.

Contracts live under a node's `contracts` map. They are ordinary Blue content for identity purposes, but a Blue processor gives supported contract types runtime meaning.

A processor run is organized around **active scopes**. The root document is always an active scope. Additional active scopes are declared by a scope's **Process Embedded** marker. Each active scope has its own local contracts, lifecycle, checkpoint, triggered-event FIFO, and termination state.

Runtime execution follows this shape:

```text
PROCESS(root, event)
  -> process embedded child scopes first
  -> initialize this scope if needed
  -> match channels for the incoming event
  -> run handlers in deterministic order
  -> apply patches immediately
  -> after every patch, deliver Document Update cascades bottom-up
  -> bridge child emissions to the parent
  -> drain this scope's Triggered FIFO exactly once
  -> return updated document, root outbox, total gas
```

Several separations are fundamental:

| Boundary | Meaning |
|---|---|
| **Language vs processor** | The Blue Language parses, resolves, canonicalizes, and hashes content. The processor executes supported contracts. |
| **Feeder vs processor** | The feeder collects and orders external events. The processor deterministically handles one delivered event. |
| **Scope vs embedded child** | A parent may add, replace, or remove an embedded child root, but may not patch inside the child's embedded domain. |
| **Effect buffering vs application** | Handlers and supported channels may request patches, emissions, gas, and termination during execution, but those requests are buffered and applied only through the normalized result order. |
| **Patch vs Direct Write** | Handler/channel patches cause Document Update cascades. Processor Direct Writes update reserved runtime state without cascades. |
| **Capability failure vs runtime fatal** | Unsupported contract capabilities detected before execution produce no mutation. Deterministic errors during a run terminate a scope. |

This specification is intentionally deterministic. Contract execution MUST NOT depend on wall-clock time, CPU speed, random sources, network latency, operating-system scheduling, or hidden mutable state.

---

## 1. Scope, Goals, Versioning, and Conformance

### 1.1 Goal

Blue Contracts and Processor 1.0 defines a deterministic processor model for Blue documents with:

- scope-local contracts;
- deterministic channel and handler ordering;
- explicit, isolated document mutation through JSON-patch entries;
- immediate bottom-up Document Update cascades after every successful patch;
- per-scope Triggered FIFOs with exactly one drain per scope per invocation;
- embedded child scopes and parent-side event bridging;
- first-run initialization lifecycle;
- channel checkpoints for external-event idempotency;
- graceful and fatal termination semantics;
- deterministic gas accounting.

### 1.2 Out of scope

The following are not defined by this specification:

- external event collection, consensus, scheduling, delivery guarantees, or retries;
- authorization, authentication, signatures, encryption, or access-control policy;
- network, storage, or provider protocols;
- user-interface semantics;
- contract programming languages or bytecode formats;
- non-deterministic operations such as timers, random numbers, ambient clocks, or network reads inside handlers;
- Blue Language content identity and BlueId algorithms.

A profile MAY define contract languages, authorization, signing, or deployment protocols, but those profiles MUST preserve the deterministic observable behavior defined here.

### 1.3 Versioning

This document defines **Blue Contracts and Processor 1.0**.

A runtime contract type is identified by its BlueId in the canonical Blue runtime type registry. A processor MUST declare which Blue Contracts and Processor version it implements and which external contract type BlueIds it supports.

Blue Contracts 1.x revisions MUST preserve the observable behavior of valid Blue Contracts 1.0 documents. Any incompatible change to event ordering, patch semantics, termination semantics, gas formulas, or processor-managed type semantics requires a new major processor version.

### 1.4 Conformance

A conforming Blue Contracts and Processor 1.0 implementation MUST implement all normative requirements in this specification.

A conforming processor MUST support:

- root-scope processing;
- active embedded scopes declared by **Process Embedded**;
- all processor-managed channel families in §5;
- all required runtime markers in Appendix A;
- deterministic contract discovery and must-understand capability checks;
- deterministic sorting by `(order, key)`;
- patch application and Document Update cascades;
- post-patch type soundness validation and dynamic type generalization;
- Triggered FIFO behavior;
- embedded-event bridging;
- lifecycle delivery;
- checkpoint lazy creation and update for external channels;
- termination semantics;
- gas accounting formulas;
- the Blue Contracts 1.0 conformance suite.

A tool that implements only a subset may be useful, but it MUST NOT describe itself as a conforming Blue Contracts and Processor 1.0 implementation.

### 1.5 Runtime registry dependency

The canonical Blue runtime type registry is part of the Blue Contracts 1.0 release surface. Its entries for processor-managed contracts and events are content-addressed and versioned with this specification.

A conforming processor MUST use the registry BlueIds for runtime contract type recognition. A different registry binding does not produce portable Blue Contracts 1.0 behavior.

Canonical runtime registry nodes are self-describing Blue content.

A runtime registry node's `name` and `description` fields are identity-bearing content under the Blue Language. A canonical runtime registry entry SHOULD include a concise normative `description` that defines the semantics of the runtime type. Changing that semantic description changes the node's BlueId and therefore defines a different runtime type.

Non-normative examples, rationale, translations, tutorial material, implementation notes, and editorial commentary MUST NOT be included in canonical runtime registry nodes unless intentionally made identity-bearing. Such material belongs in the prose specification, registry documentation, or examples outside the canonical node.

The registry file is the authority for exact string content of canonical runtime nodes. Code blocks in this specification should be generated from, or kept equivalent to, the registry entries used to calculate the published BlueIds.

The canonical runtime registry entry for each processor-managed type MUST include:

- the exact registry source node;
- the exact preprocessed/canonical node used for BlueId calculation, or a deterministic rule for producing it;
- the node's calculated BlueId;
- the Blue Contracts and Processor version that publishes it;
- the conformance fixture package identity that verifies it.

A conforming processor MUST verify, at release or test time, that every bundled runtime type node hashes to the published registry BlueId.

Canonical runtime registry source nodes MUST be reproducible under one of these release-defined modes:

1. all cross-references are exact `blueId` references in the registry source; or
2. the registry manifest defines the exact preprocessing environment used to replace both Blue Language core aliases and Blue runtime registry aliases.

A runtime registry release MUST publish enough information for an independent implementation to calculate every runtime type BlueId from the registry source nodes. Implementations MUST NOT rely on implementation-local alias maps to reproduce runtime registry BlueIds.

---

## 2. Runtime Document Model and Processing Inputs

### 2.1 Processing Document (normative)

The normative `PROCESS` function operates on a **Processing Document**: a Blue Language Preprocessed Document used as the mutable **Selected Document View**. The document MUST NOT contain the root `blue` preprocessing directive or unresolved authoring aliases.

A Processing Document is not required to be a fully Resolved View before `PROCESS` begins. Contract entries are resolved on demand during contract discovery and execution, using the resolved contract views required by §2.5.

A Blue document whose root is not an object is valid Blue content, but it is not a processable Blue Contracts document under this specification because the root active scope is an object scope. A conforming `PROCESS` implementation MUST reject such input as an invalid Processing Document before runtime begins, with no mutation, no lifecycle events, and zero gas.

A higher-level API MAY accept Blue Source Documents and apply Blue Language preprocessing before invoking `PROCESS`. Such preprocessing is outside the runtime run:

- it consumes no gas under this specification;
- it does not emit lifecycle events;
- it does not trigger Document Update cascades;
- it is not a handler/channel mutation.

### 2.2 Event input (normative)

The normative `PROCESS(document, event)` function receives a **Processing Event**: a Blue node after Blue Language preprocessing. It MUST NOT contain a root `blue` directive or unresolved authoring aliases.

The input `event` is not wrapped in a processor envelope by this specification.

The processor MUST treat the input event as read-only. External channels may adapt it into channelized payloads for handlers, but the original event node is the event stored in channel checkpoints unless a concrete channel type explicitly defines a different checkpoint subject.

A higher-level API MAY accept Source-event syntax and preprocess it before calling `PROCESS`. This preprocessing is outside the runtime run, consumes no gas, and emits no lifecycle or Triggered events.

### 2.3 Runtime views (normative)

A processor may use different internal views of the same document:

| View | Purpose |
|---|---|
| **Selected Document View** | The mutable document tree patched by runtime operations. |
| **Resolved Contract View** | The Blue Language resolved view of contract entries, used to identify supported contract types and effective fields. |
| **Snapshot View** | A read-only snapshot used in Document Update `before` and `after` payloads. |

Only the selected document view is mutated. Contract resolution, provider expansion, and type-materialization are view operations unless explicitly represented by a patch or Direct Write.

### 2.4 Scopes (normative)

A **scope** is an absolute runtime pointer to an object node in the selected document. The root scope is `/`.

An active scope is either:

- the root scope `/`; or
- a child root declared by the nearest active ancestor's **Process Embedded** marker and processed by the algorithm in §7.

Contracts are scope-local. A contract under one scope's `contracts` map is not inherited by parent scopes, child scopes, embedded scopes, or referenced nodes.

A `contracts` map on a node that is not an active scope is ordinary Blue content and is not executed during this processor invocation.

### 2.5 Contract discovery and type recognition (normative)

When a processor is about to execute a scope, it MUST discover the scope's `contracts` map, if present, and perform **Contract Recognition Resolution** for each contract entry.

Contract Recognition Resolution MUST resolve the contract entry's effective type chain far enough to identify:

- the effective contract type BlueId;
- whether the effective type is a subtype of **Contract**, **Channel**, **Handler**, or **Marker**;
- processor-relevant effective fields such as `order`, `channel`, `event`, `path`, `childPath`, `paths`, `lastEvents`, `cause`, `reason`, and any fields required by the concrete supported contract type.

Contract Recognition Resolution MUST use Blue Language provider verification for referenced type content. The resolved contract entry and all consulted effective fields MUST be valid under Blue Language resolution and schema rules.

A processor MUST NOT resolve unrelated document subtrees merely for discovery, and MUST NOT execute contracts during discovery.

If a supported concrete contract type requires additional fields to decide acceptance, matching, or processor behavior, those fields are part of that contract type's required recognition view.

If a contract entry's type cannot be resolved because required provider content is unavailable or fails BlueId verification, the scope MUST enter fatal termination unless the failure is detected during the pre-execution capability check in §2.6.

A contract entry whose effective type is not a subtype of **Contract** is inert content unless it appears under a processor-reserved key. If it appears under a processor-reserved key, it is incompatible and causes runtime fatal termination (§3.6, §11.2).

### 2.5.1 Runtime Contract Discovery View (normative)

Blue Contracts 1.0 discovers runtime contracts from the selected document's materialized scope-local `contracts` map only.

A contract entry is runtime-discoverable only when it is present as a materialized entry under `JOIN_SCOPE_PATH(scope, "/contracts/<key>")` in the Selected Document View at the point of discovery. Contract entries that would appear only by resolving the scope node's own type chain are Blue Language content, but they are not executed by the Blue Contracts 1.0 core runtime unless they have been materialized into the Selected Document View by preprocessing, by an explicit runtime patch, or by a profile that explicitly extends this rule.

Once a materialized contract entry is discovered, the entry itself is resolved using Contract Recognition Resolution (§2.5) to determine its effective contract type BlueId and processor-relevant effective fields.

Processor-managed runtime markers at reserved keys are always selected-document state. They MUST NOT be inherited from a scope type. If Contract Recognition Resolution would expose a type-derived reserved processor marker that is not materialized in the Selected Document View, the processor ignores it for runtime state. If such a marker is materialized under a non-reserved key, §3.6 duplicate/incorrect-key rules apply.

### 2.6 Must-understand capability check (normative)

Before mutating the document or delivering lifecycle events, a processor MUST perform a must-understand check for the contract entries that are in the initial active processing closure.

The initial active processing closure consists of:

1. the root scope;
2. embedded object scopes reachable by reading **Process Embedded** markers from existing scopes before any runtime patches have been applied.

The initial active processing closure excludes:

- missing embedded child paths;
- non-object child roots, which are invalid embedded scopes if selected during runtime traversal;
- scopes with a valid pre-existing **Processing Terminated Marker**, except that the terminated marker itself MUST be recognizable enough to prove the scope is inactive.

Unsupported contracts inside a pre-existing terminated inactive scope do not cause must-understand failure because that scope is not active for this invocation.

If any contract in that initial active processing closure has a contract type BlueId that the processor does not support, the processor MUST return a must-understand capability failure and MUST NOT:

- mutate the document;
- create markers;
- deliver lifecycle events;
- emit triggered events;
- consume gas.

If an unsupported contract type is introduced or discovered only after runtime mutation has begun, the processor MUST treat it as a deterministic runtime fatal at the scope where it is discovered.

### 2.7 Provider requirements (normative)

A processor MAY use a Blue Language provider to resolve contract types or compute scope Content BlueIds. Provider use MUST follow Blue Language provider verification rules.

If required provider content is unavailable during processing, the affected scope MUST terminate fatally. If the failure is detected during the initial must-understand capability check, the result is a capability failure instead.

### 2.8 Existing terminated markers (normative)

If a scope contains a valid **Processing Terminated Marker** at `contracts/terminated` before `_PROCESS` begins for that scope, the scope is inactive. The processor MUST NOT initialize it, match channels, run handlers, bridge from it, or drain its FIFO during this invocation.

Entering `_PROCESS` for an existing terminated scope still incurs the scope-entry charge, because the processor has entered and recognized the scope. No initialization, channel matching, lifecycle delivery, bridging, FIFO drain, or checkpoint work occurs for that inactive scope.

A parent may replace or remove an embedded child root containing a terminated marker, subject to the boundary rules in §4. A parent replacing the child root may thereby install a fresh child scope for a later invocation.

---

## 3. Contracts and Runtime Capabilities

### 3.1 `contracts` map (normative)

Every active scope MAY contain a `contracts` object:

```yaml
contracts:
  <key>: <Contract>
```

The map key is the contract's scope-local runtime key. It participates in deterministic ordering and handler binding.

Contracts are Blue nodes and are identity-bearing content under the Blue Language. Runtime execution does not change that language fact.

Contracts are ordinary mutable document content except for reserved processor keys. A handler may add, replace, or remove non-reserved contract entries subject to boundary rules, Blue Language validity, and must-understand discovery rules. Such mutations do not execute immediately merely because they were written; they affect only later contract-discovery points defined by this specification.

### 3.1.1 Contract-map key grammar (normative)

A contract-map key is the object member name under a scope's `contracts` map. For Blue Contracts 1.0, a contract-map key MUST:

- be a non-empty Text string;
- be representable as a Blue ordinary child-field key;
- not equal any Blue Language reserved key;
- not equal any Blue Language reserved-invalid key;
- not contain an empty runtime-pointer segment when escaped and used in a runtime pointer;
- be addressable by a Blue Runtime Pointer after RFC 6901 segment escaping.

Keys may contain `/` or `~`; those characters are escaped only when constructing runtime pointers. The stored object key remains the raw key string.

Invalid contract-map keys are deterministic runtime fatals when discovered in an active scope, or capability failures when detected during the initial must-understand check.

### 3.2 Contract roles (normative)

A contract entry MUST have one of these runtime roles, determined by its effective type:

| Role | Meaning |
|---|---|
| **Channel** | Event entry point. It decides whether an event is accepted at a scope and may adapt it into a channelized payload. |
| **Handler** | Deterministic logic bound to exactly one channel key in the same scope. |
| **Marker** | Informational state or policy. Markers do not run contract logic, but the processor obeys supported marker semantics. |

A concrete contract type MAY be external to this specification. If a processor claims to support it, it MUST implement that type's deterministic semantics exactly.

Blue Contracts 1.0 core recognizes only Channel, Handler, and Marker roles. A contract whose effective type is a subtype of Contract but not a subtype of one of these roles is an extension-role contract. If the processor does not declare support for that exact extension role type BlueId, the contract is unsupported and subject to must-understand/fatal rules. Extension roles MUST NOT be treated as inert merely because they are not Channel, Handler, or Marker.

### 3.3 Channels (normative)

A channel evaluates an incoming event in a scope and produces either:

- no delivery; or
- one channelized delivery payload for handlers bound to that channel.

A channel MAY:

- accept or reject events according to its type semantics;
- adapt or reshape an accepted event into a channelized payload;
- read and, where allowed by this specification, cause processor updates to the scope's **Channel Event Checkpoint**;
- call `consumeGas(units: Integer)` through the processor interface;
- invoke `terminate(cause, reason?)`.

A channel MUST NOT directly mutate the selected document. The only processor state a channel can affect is through permitted processor operations specified here.

Processor-managed channels are fed only by the processor. External events MUST NOT directly enter **Document Update**, **Triggered Event**, **Lifecycle Event**, or **Embedded Node** channels.

### 3.4 Handlers (normative)

A handler is bound to exactly one channel in the same scope by its `channel` field, whose value is the channel's contract-map key.

A handler MAY:

- request document changes by returning a list of **Json Patch Entry** objects;
- emit Blue event nodes;
- call `consumeGas(units: Integer)`;
- invoke `terminate(cause, reason?)`.

No other side effects are permitted.

A handler MUST be deterministic. Given the same document snapshot, channelized payload, contract content, and allowed context, it MUST return the same result.

### 3.4.1 Contract execution context (normative)

A handler/channel execution context exposes at most:

- executing scope pointer;
- current selected document view, read-only;
- channelized payload, read-only;
- original `PROCESS` event, read-only;
- current contract entry resolved content, read-only;
- current channel entry resolved content, read-only when applicable;
- processor version;
- supported external contract type IDs;
- deterministic gas interface;
- effect-buffering methods for allowed result effects.

It MUST NOT expose wall-clock time, randomness, network access, hidden mutable state, object identity, or host process state unless a supported extension explicitly defines deterministic semantics.

### 3.5 Markers (normative)

Markers carry runtime state or policy. They do not run logic.

Processor-managed marker slots are:

- **Process Embedded** at `contracts/embedded`;
- **Type Generalization Policy** at `contracts/generalization`, when present;
- **Processing Initialized Marker** at `contracts/initialized`;
- **Processing Terminated Marker** at `contracts/terminated`;
- **Channel Event Checkpoint** at `contracts/checkpoint`.

A processor MAY support additional marker types. Unsupported marker types in an active scope are subject to must-understand rules because markers are contract types.

### 3.6 Reserved processor keys (normative)

The following keys are reserved under a scope's `contracts` map:

| Key | Required type |
|---|---|
| `embedded` | Process Embedded |
| `generalization` | Type Generalization Policy |
| `initialized` | Processing Initialized Marker |
| `terminated` | Processing Terminated Marker |
| `checkpoint` | Channel Event Checkpoint |

If any reserved key exists with an incompatible type or invalid shape, the scope MUST terminate fatally, except when detected during the initial must-understand check, in which case the processor returns capability failure with no mutation.

Each processor-managed marker type listed above MUST appear at most once per scope and only at its reserved key. A marker of one of these types under any key other than its reserved key is a deterministic runtime fatal.

### 3.7 Reserved-key write protection (normative)

Handlers and channels MUST NOT patch any reserved key path or its descendants:

```text
/.../contracts/embedded
/.../contracts/generalization
/.../contracts/initialized
/.../contracts/terminated
/.../contracts/checkpoint
```

Attempting to `add`, `replace`, or `remove` such a path is a deterministic runtime fatal at the executing scope.

Exception: a handler or channel executing in scope `S` MAY patch `JOIN_SCOPE_PATH(S, "/contracts/embedded/paths")` and its list elements, provided the resulting `contracts/embedded` marker remains a valid Process Embedded marker. The patch MUST NOT replace or remove `contracts/embedded` as a whole, MUST NOT change `contracts/embedded/type`, and MUST NOT write any other field under `contracts/embedded` unless this specification explicitly defines it.

This exception exists because Process Embedded `paths` is a scope-local processing policy, not a processor-generated lifecycle/checkpoint state. It is what makes dynamic embedded traversal and the no-resurrection rule observable.

Patches to `contracts/generalization`, `contracts/initialized`, `contracts/terminated`, and `contracts/checkpoint` remain forbidden to handlers and channels.

Processor writes to reserved keys are permitted only as specified in this document.

A handler or channel patch MUST NOT target the executing scope's `contracts` map as a whole if the effect would add, replace, remove, or change any reserved processor key or reserved-key descendant in that same scope. In particular, a patch at `JOIN_SCOPE_PATH(scope, "/contracts")` is a deterministic runtime fatal unless every existing reserved processor key and reserved-key descendant in that scope is preserved as the same selected-document Blue node after the Blue Language node normalization required for selected-document insertion and canonical comparison.

For this rule, equality is semantic Blue-node equality of the selected-document subtree, not source serialization byte equality. Implementations MUST NOT compare YAML or JSON source bytes.

The ancestor-write exemption applies only when the patch target is a declared embedded child root being replaced or removed as a whole by its parent. In that case, reserved processor keys inside the replaced child subtree are child-scope state and the operation is governed by the embedded boundary rules in §4.

### 3.8 Read-only inputs (normative)

Contracts MUST treat delivered event objects, document snapshots, and context objects as read-only.

All document changes MUST occur only through explicit **Json Patch Entry** operations returned to the processor.

A processor MAY enforce read-only inputs by cloning, freezing, capability-safe references, or contract sandboxing. Observable behavior MUST be as if contracts cannot mutate delivered payload objects.

### 3.9 Deterministic ordering (normative)

Whenever multiple channels or handlers are eligible at a scope, the processor MUST sort them by:

1. effective `order` value, ascending; missing `order` is `0`;
2. contract-map key, lexicographic by Unicode code point.

This ordering applies to:

- external channel matching;
- Document Update channels;
- Triggered Event channel handlers;
- Lifecycle Event channels;
- Embedded Node channels;
- handlers within any channel.

### 3.9.1 Dispatch snapshots (normative)

For a single channel delivery, the processor determines the eligible handler list once, immediately before the first handler for that delivery is invoked. The list contains handler keys and resolved handler recognition views in `(order, key)` order.

The dispatch snapshot includes the resolved executable contract content needed to execute each snapshotted handler or channel under its concrete runtime. If a prior handler mutates, replaces, or removes a later handler's or channel's contract entry during the same delivery or Phase 3 candidate loop, the later snapshotted contract still executes using its snapshotted resolved contract content. The ordinary selected document view supplied as document context remains the current post-mutation selected document at the time of execution.

A dispatch snapshot freezes what contract is being called; it does not freeze ordinary document state read by that contract unless the concrete contract runtime defines a read snapshot.

Mutations to `contracts` during that delivery do not add, remove, reorder, or alter handlers already snapshotted for that delivery. Such mutations affect only later contract-discovery points.

External channel candidates for Phase 3 are snapshotted once at the beginning of Phase 3 for that scope. The snapshot contains candidate channel keys and resolved channel recognition views in `(order, key)` order. Mutations during Phase 3 do not add, remove, reorder, or alter candidates in the current Phase 3 loop, but later phases and later invocations observe the mutated selected document.

Processor-managed channel discovery for each Document Update, Triggered, Lifecycle, or Embedded Node delivery is performed immediately before that delivery's channel routing begins and is then snapshotted for that delivery.

For Triggered FIFO processing, processor-managed Triggered Event Channel discovery is performed separately for each dequeued FIFO event, immediately before routing that event.

For Embedded Node bridging, Embedded Node Channel discovery is performed separately for each recorded child emission, immediately before routing that emission to the parent.

For Document Update, discovery is performed separately for each Document Update payload created by each successful patch, generated generalization write, or processor-managed patch.

For Lifecycle, discovery is performed separately for each lifecycle event.

A scope termination or cut-off still stops remaining work even if the handler or channel was present in a dispatch snapshot.

### 3.10 Same-scope binding (normative)

Handlers MUST only bind to channels in the same scope. A handler whose `channel` field names no channel in the same scope is inert unless a profile declares it invalid. A handler MUST NOT bind to a parent, child, embedded, or referenced node's channel.

A handler is eligible only for channelized deliveries produced by the channel it names.

### 3.11 Contract result application order (normative)

Handlers and supported channels may request effects during execution. The processor captures those requests into an effect buffer. The effects do not mutate the selected document, enqueue events, or terminate the scope until the processor applies the normalized result through `APPLY_CONTRACT_RESULT`.

When a handler returns a result, or when a concrete supported channel type explicitly permits a channel result, the processor applies it in this order:

1. add explicit gas consumed to `RUN.total_gas`;
2. apply patches in result order, each with immediate cascades and post-patch soundness validation;
3. record and enqueue emitted Triggered events in result order;
4. apply requested termination, if any.

A host-language API may expose methods such as `emitEvent`, `applyPatch`, or `terminate`, but in Blue Contracts 1.0 core these calls are effect-buffering requests, not immediate side effects.

External channel evaluation results MUST NOT contain handler-only effects such as document patches or Triggered events unless a concrete supported channel type explicitly extends the channel capability surface. In Blue Contracts 1.0 core, patches and Triggered emissions are handler effects. If a core external channel returns patches or Triggered events, the evaluating scope MUST terminate fatally.

If a fatal error occurs while applying a result, remaining unapplied effects from that result are discarded after the currently failing operation completes its termination handling.

### 3.11.1 Contract result normalization (normative)

Before applying a contract result, the processor normalizes absent optional result fields as follows:

- absent `gasConsumed` is `0`;
- absent `patches` is `[]`;
- absent `triggeredEvents` is `[]`;
- absent `termination` is `null`.

If a present result field has an invalid shape, the executing scope MUST terminate fatally before any effects from that result are applied, except that handler/channel overhead already charged remains charged.

For Blue Contracts 1.0 core external channels, result normalization does not grant handler-only effects. A normalized external-channel result containing non-empty `patches` or `triggeredEvents` remains fatal unless a supported profile explicitly extends channel capabilities.

---

## 4. Active Scopes, Embedded Documents, and Isolation

### 4.1 Process Embedded marker (normative)

A **Process Embedded** marker under `contracts/embedded` declares embedded child scopes beneath the current scope:

```yaml
contracts:
  embedded:
    type: Process Embedded
    paths:
      - /payment
      - /shipping
```

Each path is a scope-relative absolute runtime pointer resolved against the current scope by `ABS(scope, path)` (§6.3).

The processor reads this list dynamically during Phase 1 of `_PROCESS` (§7.3).

### 4.2 Dynamic traversal (normative)

When processing embedded children of a scope, the processor MUST:

1. read the current effective `paths` list;
2. select the first path in list order that has not already been processed in this parent invocation;
3. process the child if its node exists;
4. mark the path as processed whether or not the node existed;
5. re-read `paths` before choosing the next child.

Additions, removals, and reorderings of `paths` take effect for the next child selection.

Once a child path has entered the parent invocation's `processed_paths` set, it MUST NOT be processed again in the same invocation, even if removed and re-added. This is the **no resurrection** rule.

### 4.3 Embedded path validity (normative)

A path in **Process Embedded** `paths` MUST:

- be a valid runtime pointer beginning with `/`;
- not be `/`, because a scope cannot embed itself;
- resolve to an absolute pointer location within the current scope's pointer domain;
- be unique within the list.

A malformed embedded path is a deterministic runtime fatal at the scope that declares it.

If a valid embedded path does not exist in the selected document when selected for traversal, it is skipped and marked processed for this invocation.

If a selected embedded path exists but its root node is not an object, it is not a valid embedded scope and causes deterministic runtime fatal termination at the declaring scope.

### 4.4 Single selected document view (normative)

All scopes patch the same selected document view.

An embedded child patches its subtree in place. Parent and ancestor scopes observe those changes through Document Update cascades and subsequent reads.

Referenced nodes remain compact unless a Blue Language expansion operation materializes them as part of a view operation. Expansion is not a runtime patch unless performed through an explicit patch.

### 4.5 Boundary rule (normative)

Let the executing scope be absolute pointer `S`. Let `E` be the set of embedded child root pointers declared by `S`'s current **Process Embedded** marker, resolved to absolute pointers.

A patch issued while executing in scope `S` is permitted only if:

1. `STRICTLY_INSIDE(patch.path, S)`, or, for a parent patch, `patch.path` is equal to a declared embedded child root;
2. `DESCENDANT_OR_EQUAL(patch.path, S)`; and
3. `STRICTLY_INSIDE(patch.path, X)` is false for every embedded child root `X` in `E`.

Consequences:

- A parent MAY add, replace, or remove an embedded child root as a whole.
- A parent MUST NOT patch inside an embedded child root.
- A child MAY patch strict descendants inside its own subtree.
- A child MUST NOT add, replace, or remove its own scope root.
- No contract at any scope may patch the document root `/`.

Violations are deterministic runtime fatals at the executing scope.

### 4.6 Self-root mutation forbidden (normative)

While executing in scope `S`, a handler or channel MUST NOT target exactly `S` with `add`, `replace`, or `remove`.

Only an ancestor may add, replace, or remove a child root. This prevents a scope from cutting or replacing the balloon that contains its own execution context.

### 4.7 Root target forbidden (normative)

No handler or channel may target the document root `/` with any patch operation. Replacing or removing the entire document is forbidden.

A higher-level API MAY replace the entire document between invocations, but that is outside `PROCESS`.

### 4.8 Balloon cut-off (normative)

If an ancestor removes or replaces an active child scope root while that child is being processed, the child scope is cut off for the remainder of the current invocation.

The currently executing channel or handler call is allowed to return. The processor completes the effect currently being applied, records any emissions already produced, and then performs no further work for that cut-off scope:

- no additional handlers;
- no local FIFO drain;
- no further patches from that scope;
- no further emissions from that scope.

Already recorded emissions remain in `RUN.emitted_by_scope[child]` and may be bridged to the parent if the parent has a matching **Embedded Node Channel**.

Re-adding the same path later in the same parent invocation does not schedule it again because of the no-resurrection rule.

---

## 5. Events and Processor-Managed Channels

### 5.1 Event model (normative)

Events are Blue nodes. They may be scalar, list, object, or pure reference nodes, subject to Blue Language validity.

An event has no processor envelope unless a concrete channel type defines one as its event payload.

Events delivered to contracts are read-only.

A node passed to `emitEvent` MUST normalize successfully under `NORMALIZE_RUNTIME_NODE_FOR_INSERTION(node, event)` and be a valid Blue node for event delivery. If an emitted node is invalid under the Blue Language data model, the emitting scope MUST terminate fatally before the event is recorded, enqueued, bridged, or charged as a successful emission.

Processor-emitted event instances MUST include a `type` field whose value is a pure reference to the canonical runtime event type BlueId.

For example, a Document Update event instance has:

```yaml
type:
  blueId: <DocumentUpdateRuntimeTypeBlueId>
op: replace
path: /...
before: ...
after: ...
```

The same requirement applies to Document Processing Initiated, Document Processing Terminated, and Document Processing Fatal Error.

### 5.2 Channelized payloads (normative)

A **channelized payload** is the event object delivered by a channel to its handlers.

For processor-managed channels, this specification defines the payload. For external channels, the channel type defines whether the payload is the original event, a projection of it, or a channel-specific wrapper.

Handler event matching operates on the channelized payload, not on hidden processor state.

Channelized payloads have the same immutability guarantees as input events, snapshots, and context objects.

An external channel may return the original event, a newly constructed Blue node, a deterministic projection, or a wrapper, but any structure sharing MUST be unobservable to contracts.

Portable external channel types SHOULD declare a payload type or payload schema BlueId. If omitted, payload shape is part of the concrete channel type's prose semantics and is not independently portable.

If an accepted external channel delivery declares an effective `payloadType` or payload schema BlueId, the produced channelized payload MUST conform to that type or schema under Blue Language resolution rules. If the channel accepts but produces a non-conforming payload, the evaluating scope MUST terminate fatally. A channel MAY reject the event before producing a payload.

### 5.3 Document Update Channel (normative)

The processor MUST support **Document Update Channel**.

A Document Update Channel is fed only by the processor after a successful patch.

For each patch, the processor delivers one **Document Update** event per participating scope in the cascade, from origin scope to ancestors up to root.

A Document Update Channel declares a scope-relative `path`. It matches when `DESCENDANT_OR_EQUAL(patch.path, ABS(scope, path))` is true.

Payload fields:

- `op`: `add`, `replace`, or `remove`;
- `path`: changed path relative to the receiving scope;
- `before`: snapshot at the changed path before the patch, or null if absent;
- `after`: snapshot at the changed path after the patch, or null for remove.

All handlers at the same receiving scope for the same patch MUST see the same immutable payload object, except that handler-local context may differ.

`before: null` and `after: null` in Document Update payloads are processor runtime absence sentinels. They are part of the delivered runtime payload. They indicate that the target did not exist before the patch or does not exist after a remove.

Because Blue Language identity cleaning removes null object fields, processors MUST NOT rely on the BlueId of a Document Update event to preserve absence sentinels unless a concrete event type defines an identity-preserving wrapper. Handlers read these sentinels from the delivered payload before any BlueId cleaning step.

A future version may replace these sentinels with explicit `beforePresent` and `afterPresent` booleans. Blue Contracts 1.0 uses null sentinels for delivery compatibility.

### 5.4 Triggered Event Channel (normative)

The processor MUST support **Triggered Event Channel**.

Handlers emit Triggered events through `emitEvent(node)`. The processor records each emitted node under the emitting scope and enqueues it into that scope's persistent FIFO.

A scope's Triggered FIFO is drained at most once per `_PROCESS` invocation for that scope, during Phase 5. It MUST NOT drain during Document Update cascades.

If a scope has no Triggered Event Channel, emitted events are still recorded under that scope and may be bridged upward, but they are not locally delivered.

### 5.5 Lifecycle Event Channel (normative)

The processor MUST support **Lifecycle Event Channel**.

Lifecycle events are processor-emitted nodes such as:

- **Document Processing Initiated**;
- **Document Processing Terminated**.

Lifecycle events are delivered at a scope through Lifecycle Event Channels in that scope. They are also recorded as bridgeable emissions for parent **Embedded Node Channel** handling.

Lifecycle events themselves are not enqueued into the scope's Triggered FIFO. Lifecycle handlers may emit Triggered events, and those emitted events are enqueued normally.

At root, lifecycle events recorded through `RECORD_BRIDGEABLE` are appended to the run's `triggered_events` outbox.

### 5.6 Embedded Node Channel (normative)

The processor MUST support **Embedded Node Channel**.

An Embedded Node Channel in a parent scope bridges emissions from a processed child scope after the child finishes and after the parent handles the external event, but before the parent drains its Triggered FIFO.

A channel declares `childPath`. It matches child emissions from the processed child whose path equals `ABS(parentScope, childPath)`.

Bridgeable child emissions include:

- Triggered events emitted by the child;
- lifecycle events recorded by the child.

The child emissions are delivered to the parent's Embedded Node Channel handlers in the order they were recorded by the child. They are not automatically enqueued into the parent's Triggered FIFO; parent handlers may emit events if forwarding is desired.

### 5.7 Processor-managed channels are not checkpoint-gated (normative)

Document Update, Triggered Event, Lifecycle Event, and Embedded Node channels are never subject to Channel Event Checkpoint gating.

Only external channels are checkpoint-gated (§10).

---

## 6. Runtime Pointers and JSON Patch Semantics

### 6.1 Blue Runtime Pointer (normative)

This specification uses **Blue Runtime Pointer** strings for patch paths, channel paths, and scope paths.

A Blue Runtime Pointer is a deterministic JSON-pointer-compatible path with these conventions:

- `/` denotes the root of the current pointer domain;
- child pointers begin with `/` followed by one or more escaped path segments;
- the empty string is not a valid runtime pointer;
- segment escaping follows RFC 6901: `~0` represents `~`, and `~1` represents `/`;
- unescaped `/` separates path segments;
- the array append token `-` is valid only as a patch target segment where this specification permits it.

Because `/` is the root pointer in this specification, a direct object key equal to the empty string is not addressable by Blue Runtime Pointer. Applications needing such keys must use an application-level escaped representation.

Blue Runtime Pointers are not identical to Blue Language view paths. In Blue Contracts 1.0, `/` denotes the runtime document root and is not a patch target. In Blue Language view paths, the empty string `""` denotes the root under RFC 6901 semantics. Implementers MUST NOT reuse one parser for the other without an explicit mode.

### 6.2 Pointer normalization (normative)

Processors MUST normalize pointers before comparison:

- no trailing slash except `/` itself;
- valid escape sequences only;
- no empty segments;
- no `.` or `..` path semantics;
- no percent-encoding or URI-fragment decoding unless performed by an external envelope before runtime.

Malformed pointers are deterministic runtime fatals when used by a contract or marker.

### 6.3 Helper functions (normative)

`ABS(S, P)` is the absolute document pointer for a scope-relative pointer `P` declared at scope `S`.

Examples:

```text
ABS("/", "/a")       = "/a"
ABS("/order", "/id") = "/order/id"
ABS("/order", "/")   = "/order"
```

`JOIN_SCOPE_PATH(S, P)` is equivalent to `ABS(S, P)`, where `P` is a scope-relative runtime pointer beginning with `/`. Implementations MUST NOT construct runtime pointers by raw string concatenation, because root scope `/` would otherwise produce double slashes.

`DESCENDANT_OR_EQUAL(A, B)` is true when normalized pointer `A` equals normalized pointer `B`, or when `B` is an ancestor of `A` by complete path segments. Implementations MUST NOT use raw string prefix tests; for example `/ab` is not inside `/a`.

`STRICTLY_INSIDE(A, B)` is true when `DESCENDANT_OR_EQUAL(A, B)` and `A != B`.

`escape_pointer_segment(text)` returns one RFC 6901-escaped runtime pointer segment.

`relativize_pointer(S, A)` returns a pointer relative to scope `S` for an absolute pointer `A`. It returns `/` when `A == S`.

Examples:

```text
relativize_pointer("/", "/a/b")       = "/a/b"
relativize_pointer("/a", "/a/b")      = "/b"
relativize_pointer("/a/b", "/a/b")    = "/"
```

`relativize_snapshot(S, node)` returns an immutable subtree snapshot as observed at scope `S`.

### 6.4 Json Patch Entry validation (normative)

Handlers return **Json Patch Entry** objects. A runtime patch entry MUST have this effective shape:

```yaml
op: add | replace | remove
path: <absolute Blue Runtime Pointer>
val: <Blue node>   # required for add/replace; absent for remove
```

Rules:

- `op` and `path` are required.
- `op` MUST be one of `add`, `replace`, or `remove`.
- `path` MUST be an absolute Blue Runtime Pointer.
- `path` MUST NOT be `/`.
- `val` is required for `add` and `replace`.
- `val` MUST be absent for `remove`.
- Other RFC 6902 operations such as `move`, `copy`, and `test` are unsupported and cause deterministic runtime fatal termination.

A malformed patch entry is a deterministic runtime fatal at the executing scope.

Despite the historical name **Json Patch Entry**, this is not full RFC 6902. It uses RFC 6901-compatible runtime pointers but supports only `add`, `replace`, and `remove`, with Blue-specific upsert and auto-materialization rules. The canonical runtime type name remains `Json Patch Entry` for Blue Contracts 1.0 registry stability.

### 6.4.1 Runtime node insertion normalization (normative)

`NORMALIZE_RUNTIME_NODE_FOR_INSERTION(node, context)` converts a patch `val`, emitted event, checkpoint subject, or processor-created runtime node into the selected-document form used by `PROCESS`.

The algorithm:

1. rejects a root `blue` directive;
2. rejects unresolved authoring aliases unless a higher-level API has already preprocessed them outside runtime;
3. applies Blue Language wrapper normalization;
4. applies primitive scalar inference for bare scalars;
5. applies Source-list placeholder normalization for list elements, including recursive empty-object normalization;
6. validates reserved field shapes and payload-kind exclusivity;
7. rejects invalid Blue Language nodes.

This algorithm does not perform full Blue Language resolution unless resolution is required for subsequent contract discovery, type soundness validation, event identity, checkpoint-subject identity, or Content BlueId calculation.

### 6.5 Patch application order (normative)

Patches returned by one handler are applied immediately in list order. Each successful patch triggers its full Document Update cascade before the next patch is applied.

Patches resolve against the current selected document state after all prior patches, cascades, and Direct Writes in the same run.

### 6.6 Object targets (normative)

For object containers:

- `add` inserts a new member or replaces an existing member;
- `replace` behaves as upsert;
- `remove` deletes an existing member;
- removing a non-existent member is a deterministic runtime fatal.

Missing intermediate object containers are auto-materialized as empty objects when applying `add` or `replace`. Auto-created containers are part of the same patch operation; the Document Update event describes the final requested path, not each intermediate container.

If an existing intermediate value is not an object when an object container is required, the patch is a deterministic runtime fatal.

### 6.7 Array targets (normative)

For array containers:

- path segments used against arrays MUST be canonical non-negative decimal indices with no leading zeros, except the single digit `0`, or `-`;
- `add /items/- val` appends;
- `add /items/i val` inserts at index `i`, where `0 <= i <= length`;
- `replace /items/i val` overwrites an existing element, where `0 <= i < length`;
- `remove /items/i` deletes an existing element and shifts later elements left;
- `-` is invalid for `replace` and `remove`;
- `/items/01` is malformed for array addressing;
- out-of-range array indices are deterministic runtime fatals.

The processor MUST NOT auto-materialize arrays. If an intermediate array is missing, a patch may create an object member containing an array as its `val`, but it cannot infer an array solely from a numeric path segment.

### 6.8 Snapshots (normative)

For every successful patch, the processor captures:

- `before`: the snapshot at `patch.path` before mutation, or null if the target did not exist;
- `after`: the snapshot at `patch.path` after mutation, or null for `remove`.

Snapshots delivered to handlers are immutable. A processor MAY clone, freeze, or use immutable persistent data structures.

### 6.9 Patch validity and Blue Language validity (normative)

A patch `val` MUST normalize successfully under `NORMALIZE_RUNTIME_NODE_FOR_INSERTION(val, patchValue)` before insertion into the selected document. If applying a patch would make the selected document invalid under the Blue Language data model, the patch is a deterministic runtime fatal.

This specification does not require the processor to re-resolve the entire Blue Language document after every patch unless resolution is needed for post-patch type soundness validation, subsequent contract discovery, contract execution, event identity, checkpoint-subject identity, or Content BlueId calculation.

### 6.10 Post-patch type soundness and dynamic generalization (normative)

Every successful handler/channel patch and every processor-managed patch MUST leave the selected document as a valid, type-sound Blue document before any Document Update cascade for that patch is delivered.

A processor MUST NOT expose a transient state that violates the effective type or schema constraints of the selected document.

After applying a patch to a tentative copy of the selected document, the processor MUST run `RESTORE_TYPE_SOUNDNESS` for the affected path. The processor MAY implement this incrementally, but the observable result MUST be as if the affected subtree and all relevant ancestors were rechecked under Blue Language resolution and subtype rules.

If type soundness can be restored by deterministic dynamic type generalization allowed by the effective generalization policy, the processor commits the patch and generated generalization writes atomically. If type soundness cannot be restored, the patch is a deterministic runtime fatal and the tentative patch is not committed.

### 6.10.1 Dynamic type generalization (normative)

Dynamic type generalization is the processor's deterministic repair mechanism for a patch that makes a node no longer conform to its current declared type but still conform to an ancestor type in that type's chain.

Given a node `N` with current effective type `T`, the processor may generalize `N` by replacing its selected-document `type` with the nearest ancestor type `A` of `T` such that:

1. `N` conforms to `A` under Blue Language resolution and schema rules;
2. `A` is permitted by the effective Type Generalization Policy;
3. replacing `T` with `A` does not violate an embedded-scope boundary rule;
4. all child and parent constraints remain type-sound after propagation.

Generalization is unidirectional. A processor MUST NOT specialize a node to a more specific type as a result of a patch unless that specialization was explicitly requested by the patch and validates normally.

The processor MUST choose the nearest valid permitted ancestor type. If no such ancestor exists, the patch fails with `GeneralizationNoValidType` or `GeneralizationRejected`.

### 6.10.2 `RESTORE_TYPE_SOUNDNESS` algorithm (normative)

For a patch whose requested path is `P`, the affected closure is:

1. the node directly changed by the patch;
2. each ancestor node up to the executing scope root;
3. if the executing scope is the document root, ancestors continue to the document root, which is the same node;
4. if the patch was issued by an ancestor against a declared embedded child root as a whole, the affected closure includes that child root and the executing ancestor path as allowed by §4.5.

A patch executing inside an embedded child scope MUST NOT generalize ancestor scopes outside that embedded scope. If the child change would require ancestor-scope generalization to restore global type soundness, the patch is a runtime fatal unless the ancestor itself issued the patch or a future profile explicitly permits cross-scope generalization.

Algorithm:

```text
function RESTORE_TYPE_SOUNDNESS(document, executingScope, changedPath):
    candidate = tentative patched document
    writes = []

    for nodePath from deepest affected node upward to executingScope:
        result = CHECK_NODE_CONFORMS(candidate, nodePath, currentType(nodePath))
        CHARGE_TYPE_SOUNDNESS_CHECK(nodePath)
        if result conforms:
            continue

        gen = NEAREST_VALID_GENERALIZATION(candidate, nodePath, policy(nodePath))
        if gen none:
            fail

        replace nodePath/type with canonical reference to gen.type
        append generated write nodePath/type to writes

    repeat upward validation until no new generalization writes are required
    return candidate, writes
```

A processor MAY optimize this algorithm, but must produce the same selected document, generated writes, Document Update ordering, gas, and fatal behavior.

### 6.10.3 Type Generalization Policy marker (normative)

A scope MAY contain a Type Generalization Policy marker at `contracts/generalization`. If absent, the effective default is:

```yaml
defaultMode: nearest-valid
rules: []
```

The policy controls processor-generated type generalization in that scope.

Fields:

- `defaultMode`: `nearest-valid` or `reject`. Missing means `nearest-valid`.
- `rules`: optional List of rules.

Each rule has:

- `path`: scope-relative runtime pointer identifying the subtree governed by the rule;
- `mode`: `nearest-valid` or `reject`;
- `mustRemainSubtypeOf`: optional type reference. If present, any generalized type at that path MUST be equal to or a subtype of this type.

Rule selection:

- Normalize each rule path with `ABS(scope, rule.path)`.
- The most specific matching rule applies, where specificity is longest normalized path by complete segments.
- If two rules have the same normalized path, the later rule in list order wins.

`mode: reject` means a patch that would require generalization at the governed path fails instead of generalizing.

`contracts/generalization` is processor-managed. Handlers and channels MUST NOT patch it or its descendants unless a future profile explicitly allows policy mutation. It is read during post-patch soundness validation.

### 6.10.4 Generalization writes, cascades, and gas (normative)

A generated type generalization write is a processor-managed companion write to `<nodePath>/type`. It is not a handler/channel patch and does not pay boundary check gas. It is still an observable selected-document mutation.

A patch and its generated generalization writes are committed atomically. If any required generalization fails, neither the requested patch nor any generated write is committed.

After a successful commit, Document Update cascades are delivered in this order:

1. the original requested patch path;
2. generated generalization writes in deepest-to-root order.

Each generated type write produces its own Document Update cascade. Triggered FIFO is not drained until all cascades for the original patch and all generated generalization writes have completed.

For gas:

- post-patch type-soundness validation costs `5` gas per checked node;
- each generated generalization write costs the same as a processor-managed `replace` patch for the new `type` value, without handler/channel boundary check gas;
- each generated write's Document Update cascade charges cascade gas normally for participating scopes.

### 6.10.5 Generalization examples (informative)

Price example:

```yaml
# Before
price:
  type: { blueId: <PriceInEUR> }
  amount: 150
  currency: EUR

# Patch
- op: replace
  path: /price/currency
  val: USD

# After generalization
price:
  type: { blueId: <Price> }
  amount: 150
  currency: USD
```

Parent propagation:

```text
If the root type European Product requires price: Price in EUR, and /price
generalizes to Price, the root must generalize to the nearest valid parent
type, such as Global Product, when policy permits it.
```

Policy floor:

```yaml
contracts:
  generalization:
    type: Type Generalization Policy
    rules:
      - path: /
        mode: nearest-valid
        mustRemainSubtypeOf: { blueId: <BankTransferPayNote> }
```

This allows generalization from `EU Bank Transfer PayNote` to `Bank Transfer PayNote`, but forbids generalization to plain `PayNote`.

---

## 7. PROCESS Algorithm

### 7.1 Run state (normative)

A processor invocation maintains deterministic run state:

```text
RUN.root_events       = []     # returned triggered_events outbox
RUN.total_gas         = 0
RUN.emitted_by_scope  = {}     # scope -> recorded bridgeable nodes
RUN.fifo_by_scope     = {}     # scope -> FIFO of Triggered events
RUN.terminating_scopes = {}    # scope -> true while termination is in progress
RUN.terminated_scopes = {}     # scope -> true for current-run termination
RUN.cut_off_scopes    = {}     # scope -> true when removed/replaced by ancestor
RUN.stop_lifecycle_delivery = {} # scope -> true after fatal during termination lifecycle
RUN.root_fatal_error_appended = false
```

`RUN.emitted_by_scope[scope]` contains Triggered events and lifecycle events recorded at that scope.

`RUN.fifo_by_scope[scope]` contains only Triggered events emitted at that scope.

### 7.2 Top-level wrapper (normative)

The top-level processor algorithm is:

```text
function PROCESS(document, event):
    assert document is a Processing Document
    assert event is a Blue node

    RUN = new run state

    capability = CHECK_MUST_UNDERSTAND(document, root="/", event)
    if capability fails:
        return capability failure with unchanged document, no triggered_events, total_gas = 0

    try:
        document = _PROCESS(document, event, scope="/")
        return (document, RUN.root_events, RUN.total_gas)
    catch ROOT_GRACEFUL_TERMINATION:
        return (document, RUN.root_events, RUN.total_gas)
    catch ROOT_FATAL_TERMINATION:
        return (document, RUN.root_events, RUN.total_gas)
```

A conforming API MAY represent capability failure as an error object or exception rather than the three-value success tuple. In all cases the observable requirements are no mutation, no events, and zero gas.

### 7.3 Core `_PROCESS` routine (normative)

```text
function _PROCESS(document, event, scope):
    CHARGE_SCOPE_ENTRY(scope)

    if scope does not exist:
        return document

    if has_existing_terminated_marker(document, scope):
        return document

    VALIDATE_SCOPE_CONTRACTS_OR_FATAL(document, scope)

    scope_bucket = ensure_bucket(RUN.emitted_by_scope, scope)
    scope_fifo   = ensure_fifo(RUN.fifo_by_scope, scope)

    # PHASE 1 — Process embedded children dynamically
    processed_paths = insertion_ordered_set()
    loop:
        paths = read_process_embedded_paths(document, scope)
        next_rel = first path in paths where ABS(scope, path) not in processed_paths
        if next_rel is None:
            break

        child_scope = ABS(scope, next_rel)
        processed_paths.add(child_scope)

        if node_exists(document, child_scope) and not is_object_node(document, child_scope):
            document = ENTER_FATAL_TERMINATION(document, scope, "Embedded scope root is not an object: " + child_scope)
        elif node_exists(document, child_scope):
            document = _PROCESS(document, event, child_scope)

        if INACTIVE(scope):
            break

        # Re-read paths after each child. No resurrection because processed_paths is retained.

    if INACTIVE(scope):
        return document

    # PHASE 2 — Initialize this scope on first run
    if not has_initialized_marker(document, scope):
        document = INITIALIZE_SCOPE(document, scope)

    if INACTIVE(scope):
        return document

    # PHASE 3 — Evaluate external channel candidates for the incoming event
    external_channels = snapshot_sorted_external_channel_candidates(document, scope)
    for ch in external_channels:
        if INACTIVE(scope):
            break

        delivery = EVALUATE_EXTERNAL_CHANNEL(document, scope, ch, event)
        if INACTIVE(scope):
            break
        if delivery.rejected:
            continue

        document = ENSURE_CHECKPOINT_FOR_ACCEPTED_DELIVERY(document, scope)

        if not CHECKPOINT_ALLOWS(document, scope, ch.key, event, delivery):
            continue

        document = RUN_HANDLERS_FOR_DELIVERY(document, scope, ch, delivery.payload)

        if not INACTIVE(scope):
            document = DIRECT_WRITE_CHECKPOINT_UPDATE(document, scope, ch, event, delivery)

    if INACTIVE(scope):
        return document

    # PHASE 4 — Bridge processed child emissions into this scope
    for child_scope in processed_paths in insertion order:
        if not node_was_processed_or_attempted(child_scope):
            continue
        child_events = RUN.emitted_by_scope.get(child_scope, [])
        if child_events is empty:
            continue

        for ev in child_events in recorded order:
            if INACTIVE(scope):
                break
            embedded_channels = snapshot_embedded_channels_now(document, scope, child_scope, ev)
            if embedded_channels is empty:
                continue
            CHARGE_BRIDGE_CHILD_EMISSION(child_scope, scope, ev)
            for ch in embedded_channels:
                if INACTIVE(scope):
                    break
                delivery = make_embedded_delivery(ch, ev)
                document = RUN_HANDLERS_FOR_DELIVERY(document, scope, ch, delivery.payload)

    if INACTIVE(scope):
        return document

    # PHASE 5 — Drain this scope's Triggered FIFO exactly once
    if has_triggered_event_channel(document, scope):
        document = DRAIN_TRIGGERED_QUEUE(document, scope)

    return document
```

`INACTIVE(scope)` is true when the scope is terminated, cut off, or no longer exists.

Informative phase diagram:

```text
Phase 1: process embedded children
Phase 2: initialize this scope if needed
Phase 3: evaluate external channel candidates
Phase 4: bridge child emissions
Phase 5: drain local Triggered FIFO
```

### 7.4 External channel evaluation (normative)

For each candidate external channel, the processor:

1. charges a channel match attempt (§12);
2. evaluates the channel's deterministic acceptance logic;
3. adds any explicit gas consumed by the channel;
4. handles channel-requested termination, if any;
5. if accepted, produces a channelized payload.

External channel candidates are all supported external-channel contract entries in the current scope, sorted by `(order, key)`, before applying the channel's event acceptance logic. Processor-managed channels are excluded. A processor MUST NOT pre-filter candidate external channels by event acceptance in a way that avoids the channel match attempt charge.

`snapshot_sorted_external_channel_candidates` returns the Phase 3 candidate snapshot defined by §3.9.1. It captures candidate keys and resolved candidate recognition/execution views. It does not pre-apply event acceptance.

`EVALUATE_EXTERNAL_CHANNEL` performs acceptance or rejection for a candidate channel. A candidate that rejects still consumes the channel match attempt charge.

In Blue Contracts 1.0 core, external channel evaluation may return only rejection or accepted delivery, explicit gas consumed, channelized payload for accepted delivery, and an optional termination request. Handler-only effects from external channel evaluation are fatal unless a supported profile explicitly extends channel capabilities.

External channel evaluation MUST NOT mutate the selected document directly.

### 7.5 Handler execution helper (normative)

```text
function RUN_HANDLERS_FOR_DELIVERY(document, scope, channel, payload):
    handlers = sort_by_order_then_key(find_handlers_for_channel(document, scope, channel.key, payload))
    for h in handlers:
        if INACTIVE(scope):
            break

        CHARGE_HANDLER_OVERHEAD()
        result = execute_handler(h, context_for(scope, channel, payload))
        document = APPLY_CONTRACT_RESULT(document, scope, result)
        if RUN.stop_lifecycle_delivery[scope]:
            break

    return document
```

Handler event matchers, if present, are evaluated against the channelized payload according to the handler type's deterministic semantics.

### 7.6 Contract result helper (normative)

```text
function APPLY_CONTRACT_RESULT(document, scope, result):
    result = NORMALIZE_CONTRACT_RESULT_OR_FATAL(scope, result)
    if INACTIVE(scope):
        return document

    VALIDATE_GAS_OR_FATAL(scope, result.gasConsumed)
    if INACTIVE(scope):
        return document
    ADD_EXPLICIT_GAS(result.gasConsumed)

    for patch in result.patches:
        if INACTIVE(scope):
            break
        VALIDATE_PATCH_OR_FATAL(scope, patch)
        if INACTIVE(scope):
            break
        document = APPLY_PATCH_WITH_CASCADE(document, origin_scope=scope, patch=patch)

    for event in result.triggeredEvents:
        if INACTIVE(scope):
            break
        EMIT_TO_SCOPE(scope, event)

    if result.termination is not null and not INACTIVE(scope):
        if result.termination.cause == "graceful":
            document = ENTER_GRACEFUL_TERMINATION(document, scope, result.termination.reason)
        elif result.termination.cause == "fatal":
            document = ENTER_FATAL_TERMINATION(document, scope, result.termination.reason)
        else:
            document = ENTER_FATAL_TERMINATION(document, scope, "Invalid termination cause: " + result.termination.cause)

    return document
```

`gasConsumed` MUST be a non-negative integer. Negative or non-integer gas consumption is a deterministic runtime fatal.

### 7.7 Emit helper (normative)

```text
function EMIT_TO_SCOPE(scope, node):
    if INACTIVE(scope):
        return
    node = NORMALIZE_RUNTIME_NODE_FOR_INSERTION(node, event)
    VALIDATE_EVENT_NODE_OR_FATAL(scope, node)
    if INACTIVE(scope):
        return
    CHARGE_EMIT_EVENT(node)
    RUN.emitted_by_scope[scope].append(node)
    RUN.fifo_by_scope[scope].enqueue(node)
    if scope == "/":
        RUN.root_events.append(node)
```

Emitted nodes are recorded even if the scope lacks a Triggered Event Channel. Local delivery depends on Phase 5 and channel presence.

### 7.8 Lifecycle record helper (normative)

```text
function RECORD_BRIDGEABLE(scope, node):
    RUN.emitted_by_scope[scope].append(node)
    if scope == "/":
        RUN.root_events.append(node)
```

Lifecycle nodes are bridgeable but are not enqueued in the scope's Triggered FIFO.

### 7.9 Patch and cascade helper (normative)

```text
function APPLY_PATCH_WITH_CASCADE(document, origin_scope, patch):
    CHARGE_BOUNDARY_CHECK(patch)
    if boundary_violation(document, origin_scope, patch):
        document = ENTER_FATAL_TERMINATION(document, origin_scope, "Boundary violation at " + patch.path)
        return document

    before = snapshot_at(document, patch.path)
    CHARGE_PATCH_OP(patch)
    tentative = apply_patch(copy(document), normalize_patch_value_if_present(patch))
    soundness = RESTORE_TYPE_SOUNDNESS(tentative, origin_scope, patch.path)
    if soundness fails:
        document = ENTER_FATAL_TERMINATION(document, origin_scope, soundness.error)
        return document
    document = soundness.document
    after = snapshot_at(document, patch.path)

    document = DELIVER_DOCUMENT_UPDATE_CASCADE(document, origin_scope, patch.op, patch.path, before, after)

    for write in soundness.generatedTypeWrites in deepest_to_root_order:
        document = APPLY_GENERATED_GENERALIZATION_CASCADE(document, origin_scope, write)

    UPDATE_CUT_OFF_SCOPES_AFTER_PATCH(document)
    return document
```

Document Update cascades execute immediately. Triggered emissions produced during cascades are enqueued but not drained until the receiving scope's Phase 5.

`DELIVER_DOCUMENT_UPDATE_CASCADE` performs the per-patch cascade described in §9.1-§9.3. Document Update channel discovery is performed independently for each cascade payload.

`APPLY_GENERATED_GENERALIZATION_CASCADE` delivers the Document Update cascade for one generated `<nodePath>/type` write without charging handler/channel boundary-check gas. It uses the same snapshots, payload construction, type soundness, and cascade routing rules as a processor-managed `replace` patch.

`APPLY_PROCESSOR_PATCH_WITH_CASCADE` has the same patch application, snapshot, Blue Language validity, patch operation gas, and Document Update cascade behavior as `APPLY_PATCH_WITH_CASCADE`. It bypasses handler/channel reserved-key write protection only for processor-authorized marker writes explicitly allowed by this specification. It does not charge handler/channel boundary-check gas unless §12 says otherwise. It MUST still reject invalid Blue nodes and malformed runtime pointers.

### 7.10 Triggered FIFO drain helper (normative)

```text
function DRAIN_TRIGGERED_QUEUE(document, scope):
    fifo = RUN.fifo_by_scope[scope]
    while fifo is not empty and not INACTIVE(scope):
        event = fifo.dequeue()
        CHARGE_DRAIN_FIFO(event)
        channels = snapshot_triggered_channels_now(document, scope, event)
        for ch in channels:
            if INACTIVE(scope):
                break
            delivery = make_triggered_delivery(ch, event)
            document = RUN_HANDLERS_FOR_DELIVERY(document, scope, ch, delivery.payload)

    return document
```

Events emitted during drain are appended to the tail of the same FIFO and processed deterministically during the same drain, unless the scope becomes inactive.

### 7.11 Lifecycle delivery helper (normative)

```text
function DELIVER_LIFECYCLE(document, scope, lifecycle_node):
    CHARGE_LIFECYCLE_DELIVERY(scope, lifecycle_node)
    RECORD_BRIDGEABLE(scope, lifecycle_node)

    channels = sorted_lifecycle_channels(document, scope)
    for ch in channels:
        if RUN.stop_lifecycle_delivery[scope]:
            break
        if INACTIVE(scope):
            break
        delivery = make_lifecycle_delivery(ch, lifecycle_node)
        document = RUN_HANDLERS_FOR_DELIVERY(document, scope, ch, delivery.payload)

    return document
```

Lifecycle delivery may run handlers, which may patch, emit, consume gas, or terminate.

### 7.12 Direct Writes (normative)

A **Direct Write** is a processor mutation that does not produce a Document Update cascade and does not schedule cascade work.

Direct Writes are used only for:

- creating a **Channel Event Checkpoint** lazily before accepted external-channel newness evaluation;
- updating a checkpoint after successful external-channel processing;
- writing a **Processing Terminated Marker** at a scope on termination.

Direct Writes mutate selected document state and return the updated selected document in functional pseudocode. They are visible to subsequent logic in the same run and persist in `new_doc`.

A Direct Write to a processor-managed reserved path MUST create any missing object containers required for that reserved path, such as `contracts`, `checkpoint`, and `lastEvents`, when those containers are needed to perform a processor-required Direct Write. Such container creation is part of the Direct Write, produces no Document Update cascade, and has only the Direct Write gas specified in §12. If an intermediate path exists but is not an object where an object container is required, the Direct Write is a deterministic runtime fatal at the scope performing the processor operation.

Handlers and channels cannot perform Direct Writes.

---

## 8. Initialization and Lifecycle

### 8.1 First-run initialization (normative)

If a scope does not have `contracts/initialized` when Phase 2 begins, the processor initializes the scope.

Initialization performs, in order:

1. compute the scope Content BlueId before initialization;
2. publish **Document Processing Initiated** through Lifecycle Event Channels at the scope;
3. add **Processing Initialized Marker** under `contracts/initialized` using a processor-managed patch, which MUST trigger a Document Update cascade.

The marker stores the pre-init scope Content BlueId in `documentId`.

If the processor cannot compute the scope Content BlueId because required provider content is unavailable or invalid, the scope MUST terminate fatally.

The pre-initialization scope Content BlueId is calculated from the selected scope subtree immediately after Phase 1 embedded processing for that scope and before the Processing Initialized Marker is written.

The input to Content BlueId calculation is the scope subtree as a Blue Language Source-equivalent document after runtime selected-document normalization. It includes materialized non-runtime content and materialized contract content that exists at that scope at that moment. It excludes no fields merely because they are runtime fields, except that the not-yet-written initialized marker is absent.

If a scope already has a valid terminated marker, initialization does not run. If Content BlueId cannot be computed deterministically because required provider content is unavailable, the scope terminates fatally.

### 8.2 Initialization pseudocode (normative)

```text
function INITIALIZE_SCOPE(document, scope):
    CHARGE_INITIALIZATION(scope)
    pre_init_id = compute_scope_content_blue_id(document, scope)

    initiated = make_document_processing_initiated(documentId=pre_init_id)
    document = DELIVER_LIFECYCLE(document, scope, initiated)

    if INACTIVE(scope):
        return document

    marker = make_processing_initialized_marker(documentId=pre_init_id)
    patch = { op: "add", path: JOIN_SCOPE_PATH(scope, "/contracts/initialized"), val: marker }
    document = APPLY_PROCESSOR_PATCH_WITH_CASCADE(document, origin_scope=scope, patch=patch)

    return document
```

Processor-managed initialization marker patches are not handler/channel patches and may target the reserved `initialized` key. They still produce Document Update cascades.

### 8.3 No eager checkpoint creation (normative)

Initialization MUST NOT create `contracts/checkpoint` merely because a scope is initialized. Checkpoints are created lazily only when an external channel candidate accepts at that scope and requires newness evaluation (§10).

### 8.4 Lifecycle events and root outbox (normative)

A lifecycle event recorded at root MUST be appended to the run's `triggered_events` outbox.

Lifecycle events recorded at non-root scopes are not returned directly unless they are bridged by an ancestor and re-emitted at root by handlers.

### 8.5 Persistent initialization (normative)

Once a valid **Processing Initialized Marker** exists at a scope, subsequent invocations MUST NOT re-run initialization for that scope unless the marker has been removed by an ancestor replacing or removing the scope root outside the scope's own execution.

Handlers and channels cannot remove or replace `contracts/initialized` directly because reserved keys are write-protected.

---

## 9. Document Updates, Cascades, FIFOs, and Bridging

### 9.1 One patch, one cascade (normative)

Every successful patch causes exactly one Document Update cascade.

If a patch generates type generalization writes, each generated write also causes exactly one Document Update cascade after the requested patch cascade, in deepest-to-root order.

The cascade starts at the patch's origin scope and proceeds to each ancestor up to root, in order.

For an origin scope `/a/b`, cascade scope order is:

```text
/a/b -> /a -> /
```

Scopes that no longer exist are marked cut off and do not receive further work.

### 9.2 Cascade matching (normative)

At each receiving scope `S`, a Document Update Channel with path `P` matches iff `DESCENDANT_OR_EQUAL(patch.path, ABS(S, P))` is true.

Matching uses absolute paths. Payload paths are scope-relative.

Document Update channel discovery for a patch uses the post-patch Selected Document View. A Document Update Channel removed by the patch does not receive that patch's Document Update. A Document Update Channel added by the patch may receive that same patch's Document Update if it exists in a participating scope and matches the changed path in the post-patch view.

If post-patch Document Update discovery encounters a materialized contract entry whose type is unsupported, malformed, or invalid under Contract Recognition Resolution, the receiving scope where discovery occurs MUST terminate fatally under the normal runtime-discovery rules. The original patch remains applied unless the failing scope is otherwise rolled back by a supported profile; Blue Contracts 1.0 core has no rollback.

Example:

```text
Patch path: /a/z/k
At scope /a, payload path: /z/k
At root /, payload path: /a/z/k
```

### 9.3 Uniform payload per scope (normative)

For a given patch and receiving scope, the processor creates one immutable Document Update payload. All matching channels and handlers at that scope receive that same payload object.

The payload object MUST NOT be mutated by handlers.

### 9.4 No drain during cascades (normative)

Triggered events emitted by handlers during a Document Update cascade are:

- recorded under the emitting scope;
- enqueued into that scope's Triggered FIFO;
- not delivered through the Triggered Event Channel during the cascade.

They may be delivered only during that scope's Phase 5 drain.

### 9.5 FIFO persistence within a run (normative)

Each scope has one FIFO for the entire processor invocation.

Events are enqueued in emission order. Events emitted during FIFO drain append to the tail and are processed during the same drain if the scope remains active.

If a scope terminates or is cut off, its FIFO is dropped.

### 9.6 Bridge timing (normative)

A parent bridges child emissions in Phase 4:

- after embedded children have been processed;
- after the parent handles the incoming external event;
- before the parent drains its own Triggered FIFO.

This ordering is normative.

Informative rationale: bridge-before-drain lets parent Embedded Node Channel handlers react to child emissions and enqueue parent-scope Triggered events that can still be drained in the same parent invocation; reversing the order would defer those reactions to a later invocation.

### 9.7 Bridge ordering (normative)

Bridge processing order is:

1. child scopes in the parent invocation's `processed_paths` insertion order;
2. child emissions in the order recorded under that child;
3. matching Embedded Node Channels sorted by `(order, key)`;
4. handlers within each Embedded Node Channel sorted by `(order, key)`.

### 9.8 Bridge scope (normative)

Embedded Node Channel handlers execute in the parent scope, not in the child scope. Patches they produce are parent-scope patches and are subject to the parent's boundary rules.

Informative cascade/bridge diagram:

```text
child patch
  -> child Document Update cascade upward
  -> child emissions recorded

parent Phase 4 bridge
  -> parent Embedded Node Channel delivery
  -> parent FIFO enqueue by parent handlers
  -> parent Phase 5 drain
```

---

## 10. External Channels and Channel Event Checkpoints

### 10.1 External channels (normative)

An **external channel** is any supported Channel type other than the processor-managed channel families defined in §5.

External channels match the input `event` delivered to `PROCESS`. Concrete external channel types define their acceptance and channelization semantics.

### 10.2 Checkpoint marker (normative)

A **Channel Event Checkpoint** records the last processed checkpoint subject per external channel key:

```yaml
contracts:
  checkpoint:
    type: Channel Event Checkpoint
    lastEvents:
      channelKey: <last checkpoint subject>
```

There MUST be at most one checkpoint per scope, and it MUST be under the reserved key `checkpoint`.

`lastEvents` is keyed by the raw contract-map key of the external channel. The key is escaped only when constructing a runtime pointer used for Direct Write. The selected document stores the raw object key.

### 10.3 Lazy creation (normative)

A scope may lack `contracts/checkpoint` until an accepted external channel delivery first requires newness evaluation at that scope.

Rejected external channel candidates do not create checkpoints. Lazy checkpoint creation occurs after an external channel candidate accepts the input event and before that accepted delivery's newness policy is evaluated.

When an accepted external channel delivery at scope `S` requires newness evaluation and `contracts/checkpoint` is absent, the processor MUST Direct Write an empty checkpoint before newness evaluation:

```yaml
lastEvents: {}
```

This Direct Write does not emit Document Update and does not consume checkpoint-update gas unless a gas profile explicitly says otherwise. Under §12, lazy creation itself costs zero gas.

### 10.4 Newness policy (normative)

For each external channel key, the processor uses a deterministic **newness policy** to decide whether the incoming event should be processed.

Each external channel has an effective `checkpointIdentityMode`:

- `contentBlueId` (default): compare Content BlueIds of checkpoint subjects;
- `nodeBlueId`: compare direct Node BlueIds of checkpoint subjects, requiring valid BlueId Input;
- `channelDefined`: the concrete channel type defines deterministic identity.

The default for Blue Contracts 1.0 external channels is `contentBlueId`.

A concrete external channel type MAY define its own newness policy. That policy MUST be deterministic and MUST depend only on:

- the previous checkpoint subject stored in `lastEvents[channelKey]`, if any;
- the incoming event node;
- the accepted channelized payload, if the channel type declares that payload as part of its newness policy;
- the channel contract content;
- deterministic Blue Language identity operations.

If a concrete channel type does not define a more specific policy, the default policy is **content-idempotent**:

- if no previous incoming event is stored for the channel key, the event is new;
- otherwise, the event is new iff the incoming event's Content BlueId differs from the previous incoming event's Content BlueId.

The default content-idempotent policy computes the incoming and stored event identities using the effective `checkpointIdentityMode`. Under the default `contentBlueId` mode, the processor uses the Blue Language Content BlueId pipeline over the normalized checkpoint subjects. Under `nodeBlueId`, the subject MUST already be valid BlueId Input after runtime insertion normalization.

Provider failure required for this identity calculation is a runtime fatal at the evaluating scope, unless discovered during the initial capability check.

The stored checkpoint subject remains the incoming event node by default, not the channelized payload. A concrete channel type that declares a non-default `checkpointSubject` MUST define how its newness policy uses that subject.

The processor stores the checkpoint subject after event preprocessing and runtime checkpoint-subject normalization. It does not store an ambiguous source form unless the concrete channel type explicitly defines that behavior.

The default policy detects duplicates but does not impose temporal ordering. Channels that require sequence numbers, ledgers, vector clocks, or monotonic timestamps MUST define those rules in their concrete channel type.

### 10.5 Gating rule (normative)

For each accepted external channel delivery:

1. ensure the checkpoint exists, lazily creating it if needed;
2. read `lastEvents[channelKey]`;
3. evaluate the channel's newness policy;
4. if not new, skip handlers and leave the checkpoint unchanged;
5. if new, run handlers;
6. if channel handling completes without scope termination or fatal error, Direct Write `lastEvents[channelKey] = checkpoint_subject(channel, incomingEvent, delivery)`.

The checkpoint stores the incoming event node, not the channelized payload, unless the concrete external channel type explicitly defines a different checkpoint subject.

```text
function ENSURE_CHECKPOINT_FOR_ACCEPTED_DELIVERY(document, scope):
    checkpoint_path = JOIN_SCOPE_PATH(scope, "/contracts/checkpoint")
    if checkpoint absent at checkpoint_path:
        emptyCheckpoint = ChannelEventCheckpoint(lastEvents={})
        document = DIRECT_WRITE(document, checkpoint_path, emptyCheckpoint)
    return document

function CHECKPOINT_ALLOWS(document, scope, channelKey, incomingEvent, delivery):
    # Pure decision after lazy checkpoint existence has been ensured.
    return evaluate_newness_policy(document, scope, channelKey, incomingEvent, delivery)

function DIRECT_WRITE_CHECKPOINT_UPDATE(document, scope, channel, incomingEvent, delivery):
    channelKey = channel.key
    subject = NORMALIZE_RUNTIME_NODE_FOR_INSERTION(checkpoint_subject(channel, incomingEvent, delivery), checkpointSubject)
    CHARGE_CHECKPOINT_UPDATE()
    path = JOIN_SCOPE_PATH(scope, "/contracts/checkpoint/lastEvents/" + escape_pointer_segment(channelKey))
    return DIRECT_WRITE(document, path, subject)

function checkpoint_subject(ch, incomingEvent, delivery):
    if ch.checkpointSubject == "incoming-event":
        return incomingEvent
    if ch.checkpointSubject == "channelized-payload":
        return delivery.payload
    if ch.checkpointSubject == "channel-defined":
        return deterministic_subject_defined_by_channel_type(ch, incomingEvent, delivery)
    return incomingEvent
```

The value returned by `checkpoint_subject` MUST be a valid Blue node. If a channel-defined checkpoint subject is invalid or cannot be computed deterministically, the evaluating scope MUST terminate fatally and the checkpoint MUST NOT be updated.

The object member created at the Direct Write path is the raw `channelKey`; pointer escaping is not part of the stored key.

### 10.6 Successful channel processing (normative)

An external channel is considered successfully processed when:

- its accepted handlers have all run in deterministic order;
- all their patches, emissions, and termination requests have been applied; and
- the scope has not terminated fatally or gracefully during that channel.

If the scope terminates during the channel, the checkpoint MUST NOT be updated for that channel unless the concrete termination policy explicitly says otherwise. Blue Contracts 1.0 default is no checkpoint update on termination.

### 10.7 Multiple external channels (normative)

External channel candidates at a scope are considered in `(order, key)` order. Candidates that accept the same input event each use their own checkpoint entry keyed by their contract-map key.

One channel being stale does not prevent another channel from running.

If an earlier channel's handlers patch ordinary document state, later Phase 3 candidate channel executions see the updated Selected Document View as context. However, the Phase 3 external candidate set and candidate recognition views are snapshotted at Phase 3 start under §3.9.1.

### 10.8 Checkpoint tamper resistance (normative)

Handlers and channels cannot patch `contracts/checkpoint` or its descendants. Attempts are deterministic runtime fatals.

Only the processor may create or update checkpoints through Direct Write.

---

## 11. Failure and Termination Semantics

### 11.1 Capability failure (must-understand) (normative)

If the initial must-understand capability check fails, the processor MUST NOT run. It returns a capability failure with:

- unchanged document;
- no triggered events;
- total gas `0`;
- no lifecycle events;
- no termination markers.

Capability failure is not a runtime fatal because runtime never begins.

### 11.2 Runtime fatal (normative)

A deterministic runtime error terminates the executing scope fatally. Runtime fatal causes include, but are not limited to:

- boundary violation;
- root target patch;
- self-root mutation;
- invalid contract-map key discovered in an active scope;
- malformed patch entry;
- unsupported patch operation;
- invalid pointer;
- invalid patch value after runtime insertion normalization;
- array out-of-range;
- removing a non-existent member;
- non-object embedded scope root selected for traversal;
- malformed required marker;
- reserved-key write attempt;
- post-patch type soundness violation;
- generalization rejected by Type Generalization Policy;
- no valid permitted generalization target;
- duplicate required marker;
- unsupported contract type discovered after runtime mutation has begun;
- invalid contract result shape;
- handler or channel execution error;
- checkpoint creation or update failure;
- gas accounting failure;
- termination Direct Write failure after fallback;
- provider verification failure required for runtime contract recognition, scope Content BlueId calculation, or event identity calculation.

### 11.3 Contract-requested termination (normative)

A channel or handler may request graceful termination by invoking `terminate(cause="graceful", reason?)`.

Graceful termination ends the scope without treating the run as erroneous.

Contract-requested termination cause MUST be either `graceful` or `fatal`. A graceful request enters graceful termination. A fatal request enters fatal termination and is treated as a contract-declared fatal condition, not as a processor validation error. Any other cause value is a deterministic runtime fatal at the executing scope.

Profiles MAY restrict handlers or channels to graceful-only termination, but such restriction is outside the Blue Contracts 1.0 core unless represented by a supported policy.

### 11.4 Termination effects (normative)

When a scope begins termination, gracefully or fatally, the processor MUST:

1. If the scope is already terminating or terminated, apply the reentrancy rule below and return.
2. Mark `RUN.terminating_scopes[scope] = true`.
3. Direct Write `JOIN_SCOPE_PATH(scope, "/contracts/terminated")` with **Processing Terminated Marker**:
    - `cause: graceful` or `cause: fatal`;
    - optional `reason`.
4. Create the **Document Processing Terminated** lifecycle event.
5. Deliver the lifecycle event using `DELIVER_LIFECYCLE`; `DELIVER_LIFECYCLE` records it as bridgeable before routing it to Lifecycle Event Channels.
6. Mark `RUN.terminated_scopes[scope] = true`.
7. Drop the scope's Triggered FIFO.
8. Treat further patch/emit attempts from that scope as no-ops for the remainder of the run.
9. If the terminated scope is root, apply root graceful/fatal completion rules from §11.6-§11.7.

The termination marker Direct Write does not emit a Document Update.

If writing the Processing Terminated Marker by Direct Write fails because a required intermediate container is malformed, the processor MUST make one fallback attempt to replace the executing scope's `contracts` field with an object containing only a valid `terminated` marker and any reserved runtime subtrees that can be preserved without violating Blue Language validity.

If that fallback also fails, the processor MUST abort the run with `TerminationError`. The returned document is the last valid selected document state before the failed termination write, and root fatal outbox behavior is implementation-exposed through the conformance result envelope rather than by a marker that could not be written.

A processor MUST NOT loop indefinitely attempting termination writes.

Termination is single-entry per scope per invocation. Once `ENTER_GRACEFUL_TERMINATION` or `ENTER_FATAL_TERMINATION` begins for a scope, that scope is in terminating state. The termination marker and exactly one **Document Processing Terminated** lifecycle event are produced for the first termination cause. Additional `terminate(...)` requests from handlers invoked during termination lifecycle delivery are ignored after their already-applied prior effects.

Additional termination requests after `RUN.terminated_scopes[scope] = true` are ignored.

If a deterministic runtime fatal occurs while a root scope is already terminating, the processor MUST append exactly one root outbox-only **Document Processing Fatal Error** if one has not already been appended. This does not change the already-written **Processing Terminated Marker** or the already-created **Document Processing Terminated** event. For non-root scopes, the fatal is suppressed as an additional termination cause; it MUST NOT write a second marker or emit a second lifecycle event. In all cases, the processor MUST abort remaining effects from the currently failing handler result and stop further lifecycle delivery at that terminating scope. No second termination marker charge, lifecycle delivery charge, or fatal termination overhead is charged for a suppressed additional termination cause.

Terminating state is a reentrancy guard. It does not by itself make lifecycle handlers inactive before the first termination lifecycle delivery completes.

### 11.5 Non-root fatal (normative)

A fatal termination in a non-root scope is scope-terminal only by default.

The parent continues processing unless it is itself terminated by a handler or by a separate fatal error. The child's already recorded emissions, including the termination lifecycle event, remain bridgeable to the parent.

### 11.6 Root graceful termination (normative)

If the root scope terminates gracefully, the processor records **Document Processing Terminated** in the root outbox and ends the run. It returns the current document, root outbox, and total gas.

If §11.4 appends **Document Processing Fatal Error** because a deterministic runtime fatal occurs during root graceful termination lifecycle delivery, the already-written graceful termination marker and lifecycle event remain unchanged, and the root outbox also contains the fatal error signal.

When both **Document Processing Terminated** and **Document Processing Fatal Error** appear in the root outbox for the same root termination sequence, **Document Processing Terminated** MUST appear before **Document Processing Fatal Error**.

### 11.7 Root fatal termination (normative)

If the root scope terminates fatally, the processor MUST:

1. record **Document Processing Terminated** at root;
2. append **Document Processing Fatal Error** to the root outbox as an outbox-only event;
3. abort the run;
4. return the current document, root outbox, and total gas.

**Document Processing Fatal Error** is not delivered to Lifecycle Event Channels and is not bridgeable. It is a root outbox signal only.

When both **Document Processing Terminated** and **Document Processing Fatal Error** appear in the root outbox for the same root termination sequence, **Document Processing Terminated** MUST appear before **Document Processing Fatal Error**.

### 11.8 Termination pseudocode (informative)

```text
function ENTER_GRACEFUL_TERMINATION(document, scope, reason):
    if RUN.terminated_scopes[scope]:
        return document
    if RUN.terminating_scopes[scope]:
        return document
    RUN.terminating_scopes[scope] = true
    CHARGE_TERMINATION_MARKER_WRITE()
    document = DIRECT_WRITE(document,
                            JOIN_SCOPE_PATH(scope, "/contracts/terminated"),
                            ProcessingTerminatedMarker(cause="graceful", reason=reason))
    event = DocumentProcessingTerminated(cause="graceful", reason=reason)
    document = DELIVER_LIFECYCLE(document, scope, event)
    RUN.terminated_scopes[scope] = true
    clear_fifo(scope)
    if scope == "/":
        if RUN.root_fatal_error_appended:
            raise ROOT_FATAL_TERMINATION
        raise ROOT_GRACEFUL_TERMINATION
    return document

function ENTER_FATAL_TERMINATION(document, scope, reason):
    if RUN.terminated_scopes[scope]:
        return document
    if RUN.terminating_scopes[scope]:
        if scope == "/" and not RUN.root_fatal_error_appended:
            RUN.root_events.append(DocumentProcessingFatalError(reason=reason))
            RUN.root_fatal_error_appended = true
        RUN.stop_lifecycle_delivery[scope] = true
        abort_current_handler_result()
        return document
    RUN.terminating_scopes[scope] = true
    CHARGE_TERMINATION_MARKER_WRITE()
    CHARGE_FATAL_OVERHEAD()
    document = DIRECT_WRITE(document,
                            JOIN_SCOPE_PATH(scope, "/contracts/terminated"),
                            ProcessingTerminatedMarker(cause="fatal", reason=reason))
    event = DocumentProcessingTerminated(cause="fatal", reason=reason)
    document = DELIVER_LIFECYCLE(document, scope, event)
    RUN.terminated_scopes[scope] = true
    clear_fifo(scope)
    if scope == "/":
        if not RUN.root_fatal_error_appended:
            RUN.root_events.append(DocumentProcessingFatalError(reason=reason))
            RUN.root_fatal_error_appended = true
        raise ROOT_FATAL_TERMINATION
    return document
```

The pseudocode is informative. The observable state changes and ordering above are normative.

If a termination helper raises `ROOT_GRACEFUL_TERMINATION` or `ROOT_FATAL_TERMINATION`, the raised control signal carries the current updated document. The top-level wrapper returns that updated document. This is pseudocode notation only; implementations may use exceptions, tagged returns, or another deterministic control-flow representation.

`abort_current_handler_result()` means that the processor stops applying any remaining unapplied effects from the currently executing contract result. Effects already fully applied remain applied. No additional patches, Triggered emissions, or termination requests from that result are processed.

---

## 12. Gas Accounting

### 12.1 Philosophy and unit (normative)

Gas is an abstract deterministic unit used to measure work.

Processors MUST NOT base gas on wall-clock time, CPU model, memory pressure, I/O latency, scheduler behavior, or implementation-specific performance.

Given the same input document, event, provider state, supported contract set, and deterministic contract implementations, all conforming processors MUST return the same `total_gas`.

### 12.2 Accumulation (normative)

`RUN.total_gas` MUST include:

- all processor charges from this section;
- all explicit `consumeGas(units)` calls made by channels and handlers.

`consumeGas(units)` MUST use a non-negative integer. Invalid gas amounts are deterministic runtime fatals.

### 12.3 Scope management charges (normative)

| Operation | Formula | Charge point |
|---|---:|---|
| Scope entry | `50 + 10 * depth` | On entry to `_PROCESS` for a scope. Root depth is 0. |
| Scope exit | `0` | On return from `_PROCESS`. |
| Initialization | `1000` | When first-run initialization starts for a scope. |

`depth` is the number of embedded edges from root.

Entering `_PROCESS` for an existing terminated scope still incurs the scope-entry charge. The terminated-marker check happens after scope entry and before any initialization, channel matching, lifecycle delivery, bridging, FIFO drain, or checkpoint work.

### 12.4 Matching and contract-call charges (normative)

| Operation | Formula | Charge point |
|---|---:|---|
| External channel match attempt | `5` per candidate tested | Each external channel candidate considered for an input event at a scope. |
| Handler call overhead | `50` | Immediately before executing each handler. |

Explicit gas consumed by channel and handler code is added separately.

### 12.5 Patch and cascade charges (normative)

| Operation | Formula | Charge point |
|---|---:|---|
| Boundary check | `2` per patch | Before applying each handler/channel patch. |
| Patch `add` / `replace` | `20 + ceil(bytes / 100)` | After validation, before mutation. |
| Patch `remove` | `10` | After validation, before mutation. |
| Post-patch type soundness check | `5` per checked node | During `RESTORE_TYPE_SOUNDNESS`. |
| Cascade routing | `10` per participating scope | For each scope that receives the resulting Document Update. |

For cascade-routing gas, a participating scope is an ancestor-or-origin scope that has at least one matching Document Update Channel for the changed path and therefore receives a Document Update delivery.

For gas byte formulas, `bytes` is the UTF-8 byte length of the RFC 8785 canonical JSON representation of the node after `NORMALIZE_RUNTIME_NODE_FOR_INSERTION`, using selected-document form. It is not Content BlueId canonicalization and does not require resolving unrelated type chains. For `remove`, no `val` bytes are charged.

Processor-managed initialization marker patches and generated type generalization writes are charged as patches and cascades. They are not charged for boundary checks because they are processor-internal and allowed to write their reserved or generated paths.

### 12.6 Event, bridge, and FIFO charges (normative)

| Operation | Formula | Charge point |
|---|---:|---|
| Emit event | `20 + ceil(bytes / 100)` | When `emitEvent(node)` succeeds. |
| Bridge child emission to parent | `10` per child emission delivered to at least one matching Embedded Node Channel | Before delivering the node to Embedded Node Channel handlers. |
| Drain FIFO event | `10` per dequeued event | Immediately before Triggered Channel handler routing. |

`bytes` is the UTF-8 byte length of the emitted event node's RFC 8785 canonical JSON representation after `NORMALIZE_RUNTIME_NODE_FOR_INSERTION`, using selected-document form.

The same gas-byte view applies to emitted event nodes after event validation and normalization.

Emit-event gas is charged only after the emitted node has passed Blue Language validity checks. An invalid emitted event causes fatal termination but does not incur the successful emit-event charge.

Bridge gas is not charged merely because a child emission was recorded. It is charged once per recorded child emission that is actually delivered to at least one matching Embedded Node Channel in the parent, regardless of how many matching channels receive that emission.

### 12.7 Direct Write and checkpoint charges (normative)

| Operation | Formula | Charge point |
|---|---:|---|
| Lazy checkpoint creation | `0` | When creating an empty checkpoint before accepted external-channel newness evaluation. |
| Checkpoint read | `0` | When consulting a checkpoint. |
| Checkpoint update | `20` | After successful external channel processing. |
| Termination marker Direct Write | `20` | When writing `contracts/terminated`. |

Direct Writes never trigger Document Update cascades.

### 12.8 Lifecycle and termination charges (normative)

| Operation | Formula | Charge point |
|---|---:|---|
| Lifecycle delivery | `30` | Per `DELIVER_LIFECYCLE` call, before lifecycle handlers. |
| Graceful termination overhead | `0` | Marker write and lifecycle delivery are charged separately. |
| Fatal termination overhead | `100` | On fatal termination, in addition to marker write and lifecycle delivery. |
| Must-understand capability failure | `0` | Pre-execution failure. |

A fatal termination step costs at least `150` gas: marker Direct Write `20`, lifecycle delivery `30`, and fatal overhead `100`, plus any handler, patch, cascade, or emitted-event costs already incurred.

A graceful termination step costs at least `50` gas: marker Direct Write `20` plus lifecycle delivery `30`.

### 12.9 Accounting-only default (normative)

This specification defines gas accounting, not enforcement.

Absent an active supported gas policy, a processor MUST NOT skip work, change behavior, or terminate solely because gas is high. It records and returns `total_gas`.

A separate supported policy marker MAY define budgets and overrun behavior. Such policies MUST be deterministic. If an unsupported gas policy contract is present in an active scope, must-understand rules apply.

### 12.10 Gas examples (informative)

Already-initialized root with one accepted external channel, one small `replace`, one matching root Document Update handler, and a successful checkpoint update:

```text
scope entry        50
channel match       5
handler overhead   50
boundary check      2
replace            21    # about 1-100 bytes
cascade routing    10
update handler     50
checkpoint update  20
---------------------
minimum total     208    # plus explicit consumeGas
```

Already-initialized root reached through one accepted external channel whose handler violates the boundary before any checkpoint update:

```text
scope entry          50
channel match         5
handler overhead     50
boundary check        2
termination marker   20
lifecycle delivery   30
fatal overhead      100
-----------------------
minimum total       257
```

Exact totals depend on the number of channels tested, handlers invoked, patch sizes, cascades, emissions, bridges, lifecycle handlers, initialization work, and explicit contract gas.

---

## 13. Processor vs Feeder

### 13.1 Feeder responsibilities (informative)

A feeder is an external component that may:

- collect events from users, networks, ledgers, queues, or sensors;
- order or batch events;
- retry delivery;
- deduplicate at the transport level;
- attach signatures or proofs;
- decide which document receives which event.

Feeder behavior is outside this specification.

### 13.2 Processor responsibilities (normative)

Given one `document` and one `event`, the processor executes exactly the rules in this specification.

The processor MUST NOT assume that the feeder has removed stale or duplicate events. External channel checkpoints provide deterministic in-document gating.

### 13.3 Event ordering (normative)

The processor handles only the single event supplied to one invocation. Ordering across multiple invocations is outside this specification except where persisted state, such as checkpoints and document mutations, affects later invocations.

---

## 14. Security, Determinism, and Sandboxing

### 14.1 Deterministic execution (normative)

Contract execution MUST be deterministic. A contract MUST NOT read or depend on:

- wall-clock time;
- process uptime;
- random numbers;
- CPU speed, thread scheduling, or memory addresses;
- ambient environment variables;
- network calls;
- filesystem state not represented as deterministic provider content;
- hidden mutable global state.

All data affecting contract behavior MUST be present in the selected document, the delivered event payload, supported contract content, deterministic provider content verified by BlueId, or explicit processor context defined by this specification.

### 14.2 Side-effect isolation (normative)

Handlers and channels MUST NOT perform external side effects. Their only observable effects are the processor operations defined here.

A conforming processor SHOULD sandbox contract implementations to enforce this boundary.

Informative examples of common enforcement strategies include a pure interpreter, deterministic WASM with disabled host imports, capability-safe host APIs, frozen/immutable input objects, deterministic gas/fuel counters, and denying filesystem, network, clock, or random access unless represented as verified provider content.

### 14.3 Payload immutability (normative)

Delivered payloads, snapshots, and context objects are read-only. Contracts MUST NOT mutate them.

If a contract implementation attempts mutation and the processor can detect it, the processor SHOULD treat it as a deterministic runtime fatal. If the processor prevents mutation by construction, no fatal is needed.

### 14.4 Resource exhaustion (normative)

Processors SHOULD expose implementation limits for:

- maximum recursion depth;
- maximum embedded scopes per run;
- maximum FIFO length;
- maximum emitted events per run;
- maximum patch size;
- maximum canonicalization size for gas measurement;
- maximum provider materialization depth.

If a limit is exceeded, the processor MUST handle it deterministically, normally as a runtime fatal at the affected scope unless a supported policy specifies otherwise.

### 14.5 Provider safety (normative)

Provider content used for contract type resolution, Content BlueId calculation, or event identity MUST be verified against its BlueId according to the Blue Language specification.

A processor MUST NOT execute unverified provider content as a contract.

### 14.6 Authorization out of scope (informative)

This specification does not decide who is allowed to submit events or install contracts. Authorization can be expressed by supported contract types or by feeder policy, but the processor semantics here remain deterministic.

---

## 15. Conformance Checklist and Test Vectors

### 15.1 Conformance checklist (normative)

A compliant Blue Contracts and Processor 1.0 implementation MUST satisfy the requirements below.

**Inputs and capabilities**

- Operate on Processing Documents, or preprocess Source Documents outside the runtime run.
- Reject non-object document roots before runtime as invalid Processing Documents.
- Do not require a fully Resolved View before `PROCESS` begins.
- Treat input events as read-only Blue nodes.
- Enforce must-understand before mutation for the initial active processing closure.
- Treat unsupported contracts discovered after mutation as runtime fatal at the discovering scope.
- Use canonical runtime type registry BlueIds for processor-managed contracts.

**Contract model**

- Discover runtime contracts from materialized selected-document `contracts` entries only, unless a profile explicitly extends runtime discovery.
- Execute contracts only in active scopes.
- Keep contracts scope-local.
- Sort channels and handlers by `(order, key)`.
- Use dispatch snapshots so in-flight handler/channel lists and executable contract content are not changed by contract mutations.
- Normalize contract results before applying effects.
- Buffer handler/channel effects during execution and apply normalized results only through the specified gas, patches, events, termination order.
- Enforce same-scope handler binding.
- Enforce reserved processor key compatibility and write protection.
- Allow handler/channel mutation of `contracts/embedded.paths` only through the narrow exception in §3.7.
- Treat delivered payloads and snapshots as immutable.
- Enforce contract-map key grammar.

**Embedded traversal and isolation**

- Read **Process Embedded** paths dynamically and re-read after each child.
- Process each child path at most once per parent invocation.
- Enforce no resurrection.
- Enforce boundary rules, self-root mutation forbidden, and root target forbidden.
- Implement balloon cut-off when an active child root is removed or replaced.

**Initialization and lifecycle**

- Initialize a scope only when `contracts/initialized` is absent.
- Publish **Document Processing Initiated** before writing the initialized marker.
- Write **Processing Initialized Marker** by processor-managed patch that triggers Document Update cascade.
- Do not create checkpoints during initialization.
- Honor pre-existing **Processing Terminated Marker** by making the scope inactive.

**Patch semantics**

- Support only `add`, `replace`, and `remove`.
- Use absolute Blue Runtime Pointers.
- Auto-materialize missing intermediate objects for `add` and `replace`.
- Support array append and insert semantics.
- Reject array out-of-range, malformed pointers, missing `val`, invalid `val`, and unsupported operations.
- Normalize every inserted patch value using `NORMALIZE_RUNTIME_NODE_FOR_INSERTION`.
- Restore post-patch type soundness before exposing a Document Update cascade.
- Apply dynamic type generalization when required and permitted by policy; reject/fatal atomically when soundness cannot be restored.
- Capture `before` and `after` snapshots.

**Cascades, queues, and bridges**

- After every successful patch, deliver Document Update cascade origin to root.
- Match Document Update channels using absolute changed path and scope-relative channel path.
- Deliver uniform immutable payload per receiving scope per patch.
- Never drain Triggered FIFO during cascades.
- Drain each scope's FIFO at most once in Phase 5.
- Record every emitted event under its emitting scope.
- Bridge child emissions in Phase 4 before parent FIFO drain.
- Discover Triggered Event Channels separately for each dequeued FIFO event.
- Discover Embedded Node Channels separately for each recorded child emission.
- Include a `type` pure reference on every processor-emitted event instance.

**External channels and checkpoints**

- Create checkpoint lazily only for accepted external channel deliveries before newness evaluation.
- Do not create checkpoints for rejected external channel candidates.
- Evaluate external channel candidates before acceptance, charging each candidate match attempt.
- Gate external channels only; processor-managed channels are not gated.
- Store the normalized checkpoint subject by default in `lastEvents[channelKey]` under the raw contract-map key after successful processing, unless the channel type defines a different checkpoint subject.
- Apply the effective checkpoint identity mode: `contentBlueId` by default, `nodeBlueId` only for valid BlueId Input, or `channelDefined` for concrete supported channel types.
- Create missing reserved object containers required by processor-managed Direct Writes.
- Leave checkpoint unchanged for stale events and channels that terminate the scope.
- Enforce checkpoint tamper resistance.

**Termination and failures**

- Return capability failure with no mutation, no events, and zero gas.
- On scope termination, Direct Write **Processing Terminated Marker**, publish **Document Processing Terminated**, deactivate scope, and drop FIFO.
- Enforce single-entry termination per scope per invocation.
- Non-root fatal does not escalate by default.
- Root graceful ends the run with termination lifecycle in outbox.
- Root fatal appends **Document Processing Fatal Error** and aborts the run.
- Classify conformance-visible failures using Appendix C categories.
- Use the termination Direct Write fallback exactly once when malformed containers prevent writing `contracts/terminated`.

**Gas**

- Apply all formulas in §12 deterministically.
- Charge cascade routing only for participating scopes that receive Document Update delivery.
- Charge bridge gas once per child emission delivered to at least one matching Embedded Node Channel.
- Charge post-patch type soundness checks and generated generalization writes under §6.10.4 and §12.
- Use the runtime insertion normalization byte view for patch and emitted-event byte charges.
- Include explicit `consumeGas` units.
- Do not enforce budgets unless a supported deterministic policy says so.

### 15.2 Behavior-defining test vectors (normative)

The following vectors are normative. Machine-readable fixtures MAY add exact document inputs, event inputs, and expected gas totals.

**T1 — Dynamic embedded list**  
Root declares embedded paths `/a`, `/b`. While processing `/a`, a root-scope handler is invoked by a Document Update cascade or another root-scope delivery and patches only `/contracts/embedded/paths`, removing `/b` and adding `/c`. The handler does not replace `contracts/embedded` as a whole, change `contracts/embedded/type`, or write any other field under `contracts/embedded`.  
**Then:** after `/a`, the processor re-reads paths and visits `/c`; `/b` is skipped if it no longer exists.

**T2 — Boundary enforcement**  
Root attempts `replace /a/x` while `/a` is an active embedded child.  
**Then:** root terminates fatally; `contracts/terminated` is written with cause `fatal`; **Document Processing Fatal Error** is appended to root outbox; run aborts.

**T3 — Initialization once**  
First run at `/a` has no initialized marker.  
**Then:** **Document Processing Initiated** is published, **Processing Initialized Marker** is patched into `/a/contracts/initialized`, and that patch triggers a Document Update cascade. Later runs do not reinitialize `/a`.

**T4 — Update cascades: absolute match and relative payload**  
A handler at `/a` applies `replace /a/z/k`. Root has a Document Update Channel watching `/a/z`.  
**Then:** at `/a`, payload path is `/z/k`; at root, payload path is `/a/z/k`; matching uses absolute paths.

**T5 — Cascade emissions are enqueued, not delivered**  
A patch at `/a/b` causes a Document Update handler at `/a` to emit `E`.  
**Then:** `E` is recorded under `/a` and enqueued; it is delivered only during `/a` Phase 5 if `/a` has a Triggered Event Channel.

**T6 — Triggered FIFO order**  
A handler at `/a` emits `E1`, then `E2`. `/a` has a Triggered Event Channel.  
**Then:** `/a` drains `E1` then `E2`; events emitted during drain append to the tail.

**T7 — Bridging child emissions**  
Child `/x` emits events during its run. Parent has an Embedded Node Channel for `/x`.  
**Then:** parent bridges `/x` emissions in recorded order during Phase 4, before parent FIFO drain.

**T8 — Checkpoint gating**  
Two external channels accept the same event; one event is stale under its channel policy, one is new.  
**Then:** stale channel handlers are skipped; new channel handlers run; only the new channel's checkpoint entry is updated.

**T9 — Capability failure**  
The initial active processing closure contains an unsupported contract type.  
**Then:** processor returns must-understand capability failure; document unchanged; no lifecycle events; total gas `0`.

**T10 — No accepted external channel**  
All external channel candidates reject the event in every active scope.  
**Then:** document changes only if first-run initialization is required; otherwise no patches, emissions, or checkpoint creation occur except measured channel-match gas.

**T11 — Object auto-materialization**  
A handler applies `add /a/b/c { ... }` where `/a` exists and `/a/b` does not.  
**Then:** processor creates `/a/b` as an object and writes `/a/b/c`; one Document Update cascade runs for `/a/b/c`.

**T12 — Array append and insert**  
Given `/a/items: ["x", "y"]`, `add /a/items/- "z"` yields `["x", "y", "z"]`; `add /a/items/1 "q"` yields `["x", "q", "y"]`.  
**Then:** each patch triggers one cascade.

**T13 — Deterministic runtime fatal for invalid patch**  
`replace /a/items/7 "z"` when length is less than 8, or `remove /a/missingKey`.  
**Then:** executing scope terminates fatally; root fatal only if executing scope is root.

**T14 — Scope-relative payload**  
Patch replaces `/a/b/x`.  
**Then:** payload path is `/x` at `/a/b`, `/b/x` at `/a`, and `/a/b/x` at root.

**T15 — Root lifecycle inclusion**  
First processing at root publishes **Document Processing Initiated**. Later root fatal occurs.  
**Then:** root outbox includes root lifecycle events and **Document Processing Fatal Error**.

**T16 — Local delivery depends on Triggered Channel presence**  
During a cascade at `/a`, a handler emits `E`. `/a` lacks Triggered Event Channel.  
**Then:** `E` is recorded and bridgeable, but not locally delivered at `/a`.

**T17 — Uniform event per scope**  
Multiple Document Update Channels at `/a` match the same patch.  
**Then:** all handlers at `/a` receive the same immutable Document Update payload object.

**T18 — Lazy checkpoint creation**  
A scope has an accepted external channel delivery and lacks `contracts/checkpoint`.  
**Then:** before newness evaluation, the processor Direct Writes an empty checkpoint; no Document Update is emitted.

**T19 — Duplicate checkpoint marker**  
A scope contains a **Channel Event Checkpoint** under a non-reserved key in addition to `contracts/checkpoint`.  
**Then:** runtime fatal.

**T20 — Stale external event**  
`lastEvents.testChannel` holds `E_old`; incoming event is not new under `testChannel` policy.  
**Then:** channel handlers are skipped; checkpoint unchanged.

**T21 — Checkpoint updated after success**  
A new external event on a default-subject `testChannel` is processed successfully.  
**Then:** `lastEvents.testChannel` is Direct Written to the entire incoming event node; no Document Update is emitted.

**T22 — Multiple external channels**  
Two external channels accept the event and are both new.  
**Then:** they run in `(order, key)` order; each updates its own checkpoint key after successful processing.

**T23 — Self-root mutation forbidden**  
While executing at `/a`, a contract attempts `remove /a`, `replace /a`, or `add /a`.  
**Then:** fatal termination at `/a`.

**T24 — Root-document mutation forbidden**  
Any contract targets `/` with any patch operation.  
**Then:** fatal termination at the executing scope; if root, run aborts with fatal outbox.

**T25 — Balloon cut-off**  
While `/b` is being processed, a parent watcher removes `/b`.  
**Then:** current effect completes; no further work, handlers, or drain occur for `/b`; already recorded emissions remain bridgeable; re-adding `/b` in the same run does not schedule it again.

**T26 — Termination is final in a run**  
A scope terminates gracefully; later a handler at that scope would emit or patch.  
**Then:** further patch/emit from that scope are no-ops.

**T27 — Child fatal does not escalate by default**  
`/a` terminates fatally.  
**Then:** `/a` is marked terminated; parent continues; child termination lifecycle is bridgeable.

**T28 — Child graceful termination bridges lifecycle**  
`/a` terminates gracefully.  
**Then:** parent may observe **Document Processing Terminated** via Embedded Node Channel if configured.

**T29 — Root graceful termination ends run**  
Root terminates gracefully.  
**Then:** run ends; root outbox includes **Document Processing Terminated**.

**T30 — Root fatal termination ends run with fatal outbox**  
Root terminates fatally.  
**Then:** run ends; root outbox includes **Document Processing Terminated** followed by **Document Processing Fatal Error**.

**T31 — Pre-existing terminated marker**  
An embedded scope has a valid `contracts/terminated` marker before processing.  
**Then:** processor charges scope entry for entering and recognizing that scope, but does not initialize, match, run, bridge, drain, or create checkpoint state for that scope.

**T32 — Default content-idempotent checkpoint policy**  
An external channel defines no custom newness policy. Incoming event has same Content BlueId as stored previous event.  
**Then:** event is stale and handlers are skipped.

**T33 — Reserved-key tamper**  
A handler attempts `replace /contracts/checkpoint/lastEvents/x ...`.  
**Then:** executing scope terminates fatally.

**T34 — Direct Write does not cascade**  
Checkpoint creation, checkpoint update, or termination marker write occurs.  
**Then:** no Document Update event is emitted solely for the Direct Write.

**T35 — Unsupported contract introduced mid-run**  
A handler patches a supported scope to add an unsupported contract type, and a later step attempts to execute that scope.  
**Then:** the discovering scope terminates fatally, not capability-fails retroactively.

**T36 — Processing Document is not eagerly resolved**  
A document has a contract whose type reference can be resolved, but unrelated type references elsewhere are unavailable.  
**Then:** processing may proceed unless the unavailable reference is needed for contract discovery, contract execution, Content BlueId calculation, or event identity.

**T37 — Termination reentrancy**  
A termination lifecycle handler calls `terminate(...)` again.  
**Then:** exactly one terminated marker and one **Document Processing Terminated** event are produced for that scope.

**T38 — External channel payload type declaration**  
An external channel declares `payloadType`.  
**Then:** handler matching is against the channelized payload conforming to that type, not the original input event.

**T39 — Candidate channel gas**  
A scope has three external candidate channels; two reject and one accepts.  
**Then:** channel match gas is charged for all three candidate evaluations.

**T40 — Root path joining**  
Root initialization or root termination writes a processor marker.  
**Then:** the processor writes `/contracts/initialized` or `/contracts/terminated`, never `//contracts/initialized` or `//contracts/terminated`.

**T41 — Rejected external candidates do not create checkpoint**  
A scope has an external channel candidate that rejects the event and no accepted external channel.  
**Then:** no checkpoint marker is lazily created.

**T42 — Termination lifecycle fatal is deterministic**  
A root graceful termination lifecycle handler causes a deterministic runtime fatal.  
**Then:** the original terminated marker and **Document Processing Terminated** event are not duplicated; the root outbox contains **Document Processing Terminated** followed by exactly one **Document Processing Fatal Error**.

**T43 — Type-derived contracts are not executed by core**  
A scope's type contains a `contracts.audit` entry, but the selected document scope has no materialized `contracts.audit`.  
**Then:** Blue Contracts 1.0 core does not execute `audit`. If a profile wants inherited runtime contracts, it must define that as an extension.

**T44 — Contracts-map reserved-key bypass is forbidden**  
A root handler attempts `replace /contracts` with a map omitting `checkpoint` or `initialized`.  
**Then:** the executing scope terminates fatally, even though the patch did not directly target `/contracts/checkpoint` or `/contracts/initialized`.

**T45 — Handler list snapshot**  
Two handlers `H1` and `H2` are eligible for one delivery. `H1` removes `H2`'s contract entry.  
**Then:** `H2` still runs for that delivery unless the scope is terminated or cut off. The removal affects later deliveries only.

**T46 — External candidate snapshot**  
External candidate `C1` runs before `C2`. `C1` removes `C2`'s contract entry.  
**Then:** `C2` remains in the current Phase 3 candidate snapshot. Later invocations observe the removal.

**T47 — Post-patch Document Update discovery**  
A patch adds a Document Update Channel watching the changed path.  
**Then:** that channel is eligible to receive the Document Update for the same patch, because discovery uses the post-patch Selected Document View.

**T48 — Snapshotted executable handler content**  
Two handlers `H1` and `H2` are eligible for one delivery. `H1` replaces `H2`'s contract body before `H2`'s turn.  
**Then:** `H2` still executes using the snapshotted resolved contract content captured for that delivery. Later deliveries observe the replacement.

**T49 — Direct Write creates missing reserved containers**  
A root document has no `contracts` map. An accepted external channel requires checkpoint creation, or root termination requires writing `contracts/terminated`.  
**Then:** the processor creates the required reserved object containers by Direct Write, emits no Document Update solely for those writes, and never writes `//contracts/...`.

**T50 — Reserved-key preservation uses Blue-node equality**  
A handler replaces `/contracts` with a serialization-different but canonically identical reserved marker subtree.  
**Then:** the replacement is allowed only if all reserved processor keys and descendants are preserved as the same selected-document Blue nodes under Blue Language normalization; raw source byte equality is not used.

**T51 — Embedded paths mutation exception**  
A root-scope handler invoked by a Document Update cascade or other root-scope delivery during `/a` processing patches only `/contracts/embedded/paths` to remove `/b` and add `/c`.  
**Then:** the patch is allowed if the Process Embedded marker remains valid; after `/a`, the processor re-reads paths and visits `/c`.

**T52 — Embedded marker type remains protected**  
A handler attempts to patch `/contracts/embedded/type`.  
**Then:** the executing scope terminates fatally with `ReservedKeyWrite`.

**T53 — Embedded marker whole replace remains protected**  
A handler attempts to replace `/contracts/embedded` as a whole.  
**Then:** the executing scope terminates fatally with `ReservedKeyWrite`.

**T54 — Triggered channel discovery is per FIFO event**  
A Triggered FIFO drain handles `E1`, and a handler during `E1` adds a Triggered Event Channel that matches `E2`.  
**Then:** the new channel does not affect `E1`, but it is discoverable for later dequeued `E2`.

**T55 — Embedded channel discovery is per child emission**  
A parent bridges a child emission and a handler adds or removes an Embedded Node Channel during that bridge delivery.  
**Then:** the mutation does not change the already-snapshotted emission delivery, but later emissions use fresh discovery.

**T56 — Contract-map key grammar**  
A scope contains contract-map keys `""`, `type`, or `value`.  
**Then:** the keys are invalid when discovered in an active scope; a key containing `/` remains stored raw and is escaped only when constructing runtime pointers.

**T57 — Checkpoint raw key storage**  
An accepted external channel has contract key `orders/incoming`.  
**Then:** the checkpoint object member key is `orders/incoming`; the pointer used for Direct Write escapes it as `orders~1incoming`.

**T58 — Default checkpoint identity mode**  
An external channel declares no checkpoint identity mode.  
**Then:** newness compares Content BlueIds of normalized checkpoint subjects, and an authored `eventId` field has no special meaning unless the concrete channel defines it.

**T59 — Node BlueId checkpoint identity mode**  
An external channel selects `nodeBlueId` checkpoint identity mode and supplies a checkpoint subject that is not valid BlueId Input.  
**Then:** processing fails deterministically with `CheckpointError`.

**T60 — Effect buffering order**  
A handler calls host APIs in the order `emitEvent(E)`, `applyPatch(P)`, `terminate(graceful)`.  
**Then:** the normalized result applies explicit gas first, then patch `P` and its cascades, then records/enqueues `E`, then terminates.

**T61 — Buffered effects are discarded on handler throw**  
A handler buffers a patch and then throws before returning a valid result.  
**Then:** the buffered patch is not committed; the executing scope terminates fatally with `HandlerExecutionError`.

**T62 — Runtime insertion normalization**  
A patch value is a bare scalar, a wrapped scalar, or a list containing a recursively empty object.  
**Then:** the value is normalized under `NORMALIZE_RUNTIME_NODE_FOR_INSERTION` before insertion, gas-byte calculation, event identity, and downstream contract discovery.

**T63 — Processor-emitted event instances carry type**  
The processor emits Document Update, Document Processing Initiated, Document Processing Terminated, or Document Processing Fatal Error.  
**Then:** the delivered event instance includes a `type` pure reference to the corresponding runtime event type BlueId.

**T64 — Document Update null sentinels**  
A patch adds a previously absent node or removes an existing node.  
**Then:** delivered Document Update payloads use `before: null` or `after: null` as runtime absence sentinels; handlers observe them in the delivered payload even though Blue Language identity cleaning removes null object fields.

**T65 — Initialization Content BlueId timing**  
A scope initializes for the first time.  
**Then:** `documentId` is the scope Content BlueId immediately after Phase 1 embedded processing and before the initialized marker is written.

**T66 — Termination Direct Write fallback**  
The processor must write a terminated marker but the scope's existing `contracts` container is malformed.  
**Then:** it makes one fallback attempt as defined in §11.4; if fallback also fails, the run aborts with `TerminationError`.

**T67 — Extension role contract is unsupported, not inert**  
A contract's effective type is a subtype of Contract but not Channel, Handler, or Marker, and the processor does not support that exact extension role type BlueId.  
**Then:** the contract is unsupported and subject to must-understand or runtime-fatal rules.

**G1 — Fixed value violation generalizes nearest node type**  
Given `/price` typed `Price in EUR`, a patch replaces `/price/currency` with `USD`, and `/price` conforms to ancestor type `Price`.  
**Then:** the processor generalizes `/price/type` to `Price` when policy permits.

**G2 — Generalization propagates to parent**  
The root type requires `/price: Price in EUR`; `/price` generalizes to `Price`; and the root conforms only to ancestor type `Global Product`.  
**Then:** the processor also generalizes root type to the nearest valid permitted parent type.

**G3 — Policy floor prevents over-generalization**  
A Type Generalization Policy rule requires root to remain equal to or a subtype of `Bank Transfer PayNote`.  
**Then:** a patch that would require generalizing above that type is runtime fatal with `GeneralizationRejected` or `GeneralizationNoValidType`.

**G4 — Reject mode prevents generalization**  
The effective generalization policy for a changed path is `reject`.  
**Then:** a patch that would require generalization is runtime fatal and no tentative patch or generated write is committed.

**G5 — Generalization writes produce Document Updates**  
A patch requires generated writes to child and parent `/type` fields.  
**Then:** the requested patch cascade is delivered first, followed by generated type-write cascades in deepest-to-root order.

**G6 — Embedded child cannot generalize ancestor**  
A patch executing inside an embedded child would require generalizing the parent scope to restore global type soundness.  
**Then:** the child patch is runtime fatal unless the ancestor itself issued the patch or a future extension explicitly permits cross-scope generalization.

### 15.3 Machine-readable fixtures (normative)

The Blue Contracts 1.0 conformance suite MUST publish machine-readable fixtures for the vectors above.

The canonical Blue Contracts 1.0 fixture package is part of the Blue Contracts 1.0 release artifact and is versioned with this specification. The release authority MUST publish the fixture package identity, either as a BlueId or as a content-addressed release artifact digest. This prose specification intentionally does not include placeholder fixture BlueIds.

The fixture package identity for this Blue Contracts and Processor 1.0 publication is:

```text
sha256:2f197ca3bbdc41b75e772777cc48e51019754347e1bee26b5f3209b71d9bd9ca
```

A fixture with `operation: processDocument` is executable unless it explicitly sets `informativeOnly: true`.

An executable process fixture MUST include enough machine-readable input and expected output to be run by an independent implementation. At minimum it MUST include:

- `initialDocument`;
- `event`;
- either `mockRuntime`, concrete supported runtime contract types, or a declared deterministic `processorCapabilities` entry sufficient to execute the fixture;
- at least one machine-checkable expected result such as `expectedStatus`, `expectedDocument`, `expectedDocumentPaths`, `expectedAbsentDocumentPaths`, `expectedRootEvents`, `expectedRootEventTypes`, `expectedTotalGas`, `expectedErrorCategory`, `expectedErrorCategories`, or `expectedNoDocumentMutation`.

The free-text `assertions` field is informative only. It MUST NOT be the only evidence for a conformance-required executable fixture.

A fixture that contains only prose assertions MUST set `informativeOnly: true` and MUST NOT be counted as passing executable conformance coverage.

The release fixture manifest is the canonical list of required executable fixtures for this Blue Contracts 1.0 release. A conforming implementation MUST report the fixture package identity it passes. Release tooling MUST verify that every manifest entry exists, every fixture ID is unique, and no unlisted fixture YAML files are present.

Registry fixtures, pointer utility fixtures, and other non-`processDocument` fixtures MAY use operation-specific inputs instead of `initialDocument` and `event`. The executable input requirements above apply only to `operation: processDocument`.

The fixture package identity algorithm is part of the release artifact. To calculate `fixturePackageIdentity`:

1. Normalize all line endings to LF.
2. Start the digest with the UTF-8 bytes of `manifest.yaml\n`.
3. Read `manifest.yaml`, replace the line beginning `fixturePackageIdentity:` with `fixturePackageIdentity: ""`, normalize line endings, and append those bytes.
4. Iterate manifest `fixtures` in manifest order. Do not sort paths separately.
5. For each fixture, append the UTF-8 bytes of `\n--- <path>\n`, then append the fixture file bytes after LF line-ending normalization.
6. Encode the SHA-256 digest as lowercase hexadecimal prefixed by `sha256:`.

Fixtures SHOULD include:

```yaml
id: T21
category: Checkpoint
initialDocument: ...
event: ...
expectedDocument: ...
expectedRootEvents: ...
expectedTotalGas: ...
```

Exact gas fixtures MUST specify contract implementations or mock contract result functions so that handler gas and emitted effects are deterministic.

Fixture packages MUST include registry conformance fixtures proving that:

- the Contract registry node hashes to its published BlueId;
- the Channel registry node hashes to its published BlueId;
- the Handler registry node hashes to its published BlueId;
- the Marker registry node hashes to its published BlueId;
- the Json Patch Entry registry node hashes to its published BlueId;
- the Contract Execution Result registry node hashes to its published BlueId;
- the Process Embedded registry node hashes to its published BlueId;
- the Processing Initialized Marker registry node hashes to its published BlueId;
- the Processing Terminated Marker registry node hashes to its published BlueId;
- the Channel Event Checkpoint registry node hashes to its published BlueId;
- the Type Generalization Policy registry node hashes to its published BlueId;
- the Type Generalization Rule registry node hashes to its published BlueId;
- the Document Update Channel registry node hashes to its published BlueId;
- the Triggered Event Channel registry node hashes to its published BlueId;
- the Lifecycle Event Channel registry node hashes to its published BlueId;
- the Embedded Node Channel registry node hashes to its published BlueId;
- the Document Update event registry node hashes to its published BlueId;
- the Document Processing Initiated event registry node hashes to its published BlueId;
- the Document Processing Terminated event registry node hashes to its published BlueId;
- the Document Processing Fatal Error event registry node hashes to its published BlueId;
- documentId fields use Text with BlueId-string semantics unless a formal canonical BlueId type is intentionally published;
- changing a processor-managed runtime type `description` changes the node BlueId.

The Blue Contracts runtime registry manifest MUST make identity-bearing descriptions explicit. Each entry in the registry manifest MUST identify the registry kind, specification version, entry key, exact registry source node path, the exact preprocessed/canonical node used for BlueId calculation or deterministic preprocessing rule, published BlueId, conformance fixture package identity, and `semanticDescriptionIdentityBearing: true`.

Release checks MUST verify that:

- registry nodes are loaded from files, not reconstructed from implementation constants;
- registry file content hashes to the published BlueIds;
- runtime constants equal the calculated registry BlueIds;
- no canonical registry node is edited without updating its BlueId and fixture package identity;
- generated documentation is derived from registry nodes, or explicitly marked non-canonical.

Fixture packages MAY use the following portable mock runtime format:

```yaml
id: Txx
category: ...
initialDocument: ...
event: ...
mockRuntime:
  channels:
    - contract: /contracts/incoming
      calls:
        - when:
            event: any
          accepted: true
          payload:
            $event: true
          gasConsumed: 0
    - contract: /a/contracts/orders
      calls:
        - when:
            eventContentBlueId: "<blueId>"
          accepted: true
          payload:
            type: Example Payload
          gasConsumed: 0
          termination: null
  handlers:
    - contract: /contracts/saveName
      calls:
        - when:
            channelKey: incoming
            payload: any
          result:
            gasConsumed: 0
            patches:
              - op: replace
                path: /name
                val: Alice
            triggeredEvents: []
            termination: null
expectedDocument: ...
expectedRootEvents: ...
expectedTotalGas: ...
```

Normative fixture rules:

- `contract` is an absolute pointer to a contract entry.
- Calls are consumed in order.
- For channel mock calls, `accepted` is required.
- If `accepted: true`, `payload` is required unless the channel terminates before delivery.
- If `accepted: false`, `payload` MUST be absent.
- `gasConsumed` defaults to `0`.
- `patches` and `triggeredEvents` default to `[]`.
- `termination` defaults to `null` and may be `null` or `{ cause: graceful|fatal, reason?: Text }`.
- Handler mock `result` uses the abstract **Contract Execution Result** fields.
- `payload: { $event: true }` means the original input event node.
- Matchers such as `any`, `eventContentBlueId`, and `payload` are fixture matcher syntax, not Blue content.
- A mock result MUST NOT grant a contract effects outside its role unless the fixture explicitly declares a profile extension.
- A fixture is invalid if a channel or handler call occurs with no matching mock call.
- `processorCapabilities` names deterministic fixture-harness capabilities required to execute the fixture. A conforming implementation may satisfy a capability natively or through a test harness, but it MUST report unsupported capabilities as fixture execution failures.
- `typeGraph` is fixture provider syntax for dynamic type generalization and type-soundness fixtures. It maps fixture type names to exact BlueId strings and parent relationships used by the conformance runner's provider.
- `expectedNoDocumentMutation: true` means the selected document after the operation is semantically equal to `initialDocument` under the selected-document normalization rules.
- `expectedDocumentUpdateOrder` is a machine-checkable list of Document Update changed paths in delivery order when a fixture needs to prove cascade ordering.
- Error fixtures MAY use `expectedErrorCategory: <category>` when exactly one primary diagnostic is asserted.
- Error fixtures MAY use `expectedErrorCategories: [<category>, ...]` when more than one category is acceptable.
- Fixtures that intentionally contain multiple independent errors MUST assert only failure, or MUST list all acceptable categories.
- This mock schema is for conformance fixtures only; it is not a contract language.

### 15.4 Fixture harness capabilities (normative for fixtures)

Fixture harness capabilities are deterministic conformance-fixture tools. They are not Blue Contracts core contract languages and MUST NOT be treated as canonical runtime registry types.

`blue-contracts-fixture-scripted-runtime-v1` defines a scripted fixture runtime for channel and handler behavior. It recognizes fixture-only channel and handler contracts whose type BlueIds are declared by the fixture package or whose behavior is supplied by `mockRuntime`. These fixture-only types are not part of the canonical runtime registry.

A scripted external channel accepts an event when its configured `mockRuntime.channels[].calls[].when` matcher matches and `accepted: true`. If a fixture marks a contract as a generic fixture external channel and supplies no more specific matcher, the channel accepts all events. A rejected channel call has `accepted: false` and MUST NOT supply `payload`. An accepted channel call supplies a channelized `payload`, optional non-negative `gasConsumed`, and optional `termination`.

A scripted handler produces a **Contract Execution Result** from either `mockRuntime.handlers[].calls[].result` or fixture-only fields on the contract entry. The following fixture-only handler fields map to buffered result effects:

| Field | Fixture meaning |
|---|---|
| `patches` | List of Json Patch Entry objects buffered as handler patches. |
| `triggeredEvents` | List of Blue event nodes buffered as emitted Triggered events. |
| `gasConsumed` | Non-negative explicit gas consumed by the contract. |
| `consumeGas` | Fixture shorthand for explicit gas or a host `consumeGas` API call. |
| `termination` | `null`, `graceful`, `fatal`, or `{ cause: graceful|fatal, reason?: Text }`. |
| `emitInvalidEvent` | Emits a deliberately invalid event for error fixtures. |
| `hostApiCalls` | Ordered fixture host API calls used to prove buffering semantics. |

`hostApiCalls` supports these operations:

```yaml
hostApiCalls:
  - emitEvent: <Blue node>
  - applyPatch: { op: replace, path: /x, val: y }
  - consumeGas: 5
  - terminate: { cause: graceful, reason: done }
  - throw: { category: HandlerExecutionError }
```

These calls are fixture-harness host API calls. They are buffered according to §3.11 and are not immediate side effects. A `throw` aborts the current contract call before a valid result is returned; effects buffered by that throwing call are discarded unless a fixture explicitly says otherwise.

The scripted runtime also defines these helper fields used by the release fixture package:

| Field | Fixture meaning |
|---|---|
| `addDocumentUpdateChannelAt` | Fixture shorthand for a handler patch that installs a Document Update Channel at the given runtime pointer. |
| `documentUpdatePath` | Path value used with `addDocumentUpdateChannelAt` for the installed channel's watched `path`. |
| `childEmissions` | Fixture-provided recorded child emissions for Embedded Node bridge-order fixtures. |
| `bridgeMutations` | Fixture-provided mutations that occur during bridge delivery to test per-emission snapshots. |
| `forcedFatal` | Fixture instruction that forces a deterministic runtime fatal at the given scope. |
| `orderLog` | Ordinary fixture document field used to record observable ordering when a fixture expects it. |

Matchers under `when` are fixture matcher syntax. `event: any` matches any Processing Event. `payload: any` matches any channelized payload. `eventContentBlueId` matches the Content BlueId of the normalized Processing Event. Exact map/list/scalar matcher values match by Blue node equality after runtime insertion normalization.

`blue-contracts-fixture-type-graph-v1` defines a fixture provider for dynamic type generalization and type-soundness tests. It is fixture provider syntax only, not canonical Blue type syntax.

```yaml
typeGraph:
  Price:
    blueId: <id>
  PriceInEUR:
    blueId: <id>
    parent: Price
    fixedValues:
      /currency: EUR
  Product:
    blueId: <id>
    fields:
      /price:
        type: Price
```

`blueId` is the type identity used in fixture documents. `parent` defines the type-chain parent used for nearest-valid generalization. `fixedValues` defines path/value invariants. `fields` defines child-type constraints used for parent revalidation. Fixture paths in `typeGraph` are Blue Runtime Pointers unless stated otherwise.

### 15.5 Fixture assertion fields (normative for fixtures)

Any fixture field beginning with `expected` that is not defined here or in an operation-specific fixture manifest schema is invalid.

| Field | Meaning |
|---|---|
| `expectedStatus` | Abstract fixture status: `success`, `runtime-fatal`, `capability-failure`, `invalid-processing-document`, or `invalid-input`. `invalid-processing-document` is the preferred fixture status when the selected document is not valid enough to enter runtime; `invalid-input` remains available for invalid event, fixture, or processor API input cases. |
| `expectedCapabilityFailure` | Boolean legacy assertion. If true, implies capability failure with no mutation, no gas, and no root events unless explicitly overridden. |
| `expectedNoDocumentMutation` | Final selected document is semantically equal to `initialDocument` after selected-document normalization. |
| `expectedDocument` | Exact selected document expected after the operation. |
| `expectedDocumentPaths` | Map from Blue Runtime Pointer to expected Blue node/value shape in the final selected document. |
| `expectedDocumentPathExists` | List of Blue Runtime Pointers that must exist in the final selected document. |
| `expectedDocumentPathValues` | List form of path/value assertions in the final selected document. |
| `expectedAbsentDocumentPaths` | Blue Runtime Pointers that must not exist in the final selected document. |
| `expectedAbsentDocumentPathValues` | Path/value pairs that must not match in the final selected document. |
| `expectedDocumentUpdates` | Expected Document Update payload assertions. |
| `expectedDocumentUpdateOrder` | Ordered list of changed paths for Document Update deliveries. |
| `expectedRootEvents` | Exact root outbox events. |
| `expectedRootEventTypes` | Ordered root event type BlueIds or symbolic fixture aliases. |
| `expectedRootEventPathValues` | Path/value assertions inside root outbox events. |
| `expectedRootEventCount` | Exact root outbox event count. |
| `expectedProcessorEventTypes` | Expected processor-emitted event type BlueIds by symbolic event name. |
| `expectedTotalGas` | Exact total gas. |
| `expectedExactGas` | Alias for exact total gas. A fixture MUST NOT use both `expectedTotalGas` and `expectedExactGas` unless they are equal. |
| `expectedTotalGasMin` | Lower bound on total gas. It is allowed only for non-exact smoke or performance-tolerant fixtures. |
| `expectedErrorCategory` | Exact diagnostic category. Fixture should isolate one primary error. |
| `expectedErrorCategories` | List of acceptable diagnostic categories for intentionally ambiguous multi-error cases. |
| `expectedFailureReasonContains` | Legacy substring check for diagnostic text. It is weaker than `expectedErrorCategory` and SHOULD NOT be used by new fixtures unless no category is stable. |
| `expectedCheckpointLastEvents` | Expected checkpoint subjects under raw channel keys in `lastEvents`. |
| `expectedRuntimeInsertionNormalizedValues` | Assertions about selected-document form after `NORMALIZE_RUNTIME_NODE_FOR_INSERTION`. |
| `expectedGasByteView` | Assertion describing which normalized form is used for patch or emitted-event gas byte calculation. |
| `expectedEffectApplicationOrder` | Ordered observable effect labels proving buffered effect order. |
| `expectedStoredObjectKeys` | Raw object keys stored in selected document at the given path. |
| `expectedEmbeddedDeliveryOrder` | Exact embedded scope processing or bridge delivery order. |
| `expectedTriggeredDeliveryOrder` | Exact Triggered FIFO event/channel delivery order. |
| `expectedTriggeredFifoAfterDocumentUpdates` | Boolean assertion that Triggered FIFO drain occurs only after all requested and generated Document Update cascades in the fixture. |
| `expectedPointerReads` | Expected runtime-pointer read paths used by pointer utility fixtures. |
| `expectedPointerWrites` | Expected runtime-pointer write paths used by pointer utility or Direct Write fixtures. |
| `expectedInitializationContentBlueIdInput` | Expected input/timing used to calculate initialization Content BlueId. |
| `expectedTerminationFallback` | Expected termination Direct Write fallback behavior. |
| `expectedBlueId` | Expected BlueId for registry or BlueId-calculation fixtures. |
| `expectedOriginalBlueId` | Expected original BlueId before mutation in identity-change fixtures. |
| `expectedRuntimeBlueIds` | Map of runtime type constants to expected registry BlueIds. |
| `expectedValid` | Boolean validity result for pointer utility or validation fixtures. |
| `expectedDescendantOrEqual` | Boolean result for runtime-pointer descendant-or-equal utility fixtures. |

---

## 16. Worked Examples

### 16.1 Minimal external event handler (informative)

```yaml
contracts:
  incoming:
    type: Example External Channel
    order: 0
  saveName:
    type: Example Patch Handler
    channel: incoming
    patch:
      op: replace
      path: /name
      val: Alice
```

When the `incoming` channel accepts an event, `saveName` patches `/name`. The patch triggers a Document Update cascade from root to root.

### 16.2 Embedded child event bridge (informative)

```yaml
contracts:
  embedded:
    type: Process Embedded
    paths: [/payment]
  paymentEvents:
    type: Embedded Node Channel
    childPath: /payment
  forwardPayment:
    type: Example Forward Handler
    channel: paymentEvents

payment:
  contracts:
    incoming:
      type: Example External Channel
    emitReceipt:
      type: Example Emit Handler
      channel: incoming
```

The root processes `/payment` first. If `/payment` emits a receipt event, root's `paymentEvents` channel bridges it in Phase 4. `forwardPayment` may re-emit a root-scope event, which is returned in root `triggered_events` and can be locally drained if root has a Triggered Event Channel.

### 16.3 Document Update watcher (informative)

```yaml
contracts:
  watchAmount:
    type: Document Update Channel
    path: /amount
  onAmount:
    type: Example Audit Handler
    channel: watchAmount
```

Any successful patch at `/amount` or below it triggers `watchAmount`. A patch at `/amount/currency` matches; a patch at `/status` does not.

### 16.4 Checkpoint behavior (informative)

```yaml
contracts:
  orders:
    type: Example Ordered Event Channel
  handleOrder:
    type: Example Order Handler
    channel: orders
```

On first accepted external delivery requiring newness evaluation, the processor Direct Writes:

```yaml
contracts:
  checkpoint:
    type: Channel Event Checkpoint
    lastEvents: {}
```

If the event is new and `handleOrder` completes successfully, the processor Direct Writes:

```yaml
contracts:
  checkpoint:
    type: Channel Event Checkpoint
    lastEvents:
      orders: checkpoint_subject(orders, event, delivery)
```

For the default checkpoint subject, this is the entire incoming event node.

No Document Update is emitted for either Direct Write.

### 16.5 End-to-end root update, audit, and checkpoint (informative)

```yaml
contracts:
  incoming:
    type: Example External Channel
    payloadType: Example Status Command
  setStatus:
    type: Example Patch Handler
    channel: incoming
    patch:
      op: replace
      path: /status
      val: accepted
  statusUpdates:
    type: Document Update Channel
    path: /status
  emitAudit:
    type: Example Audit Emit Handler
    channel: statusUpdates
  auditEvents:
    type: Triggered Event Channel
  storeAudit:
    type: Example Audit Sink Handler
    channel: auditEvents

status: pending
```

Expected high-level order:

1. During Phase 3, `incoming` is evaluated as an external channel candidate and accepts the input event.
2. If `contracts/checkpoint` is absent, the processor Direct Writes an empty checkpoint before newness evaluation.
3. `setStatus` receives the channelized payload and patches `/status` to `accepted`.
4. The patch produces a root Document Update cascade; `statusUpdates` receives the update payload.
5. `emitAudit` emits an audit event, which is recorded under root and appended to root's Triggered FIFO.
6. After successful external channel handling, the processor Direct Writes `lastEvents.incoming` to `checkpoint_subject(incoming, event, delivery)`, which is the incoming event node under the default checkpoint subject.
7. During Phase 5, `auditEvents` drains the audit event and `storeAudit` handles it.

No exact BlueIds are shown here; concrete fixture packages provide exact canonical identities when needed.

---

## Appendix A — Runtime Type Catalog

Appendix A defines the canonical runtime types referenced throughout Blue Contracts and Processor 1.0.

The canonical Blue runtime type registry supplies the exact Blue nodes and BlueIds for these types. The registry is the authority for exact string content, canonicalized node content, and published BlueIds.

The canonical runtime type nodes below are intentionally self-describing. Their `description` fields are normative, identity-bearing content. Changing a canonical runtime description changes the node's BlueId and therefore defines a different runtime type.

The YAML blocks in this appendix are intended registry source nodes. If a block uses symbolic core type aliases such as `Text`, `Integer`, `List`, or `Dictionary`, those aliases are resolved by the standard Blue Language baseline preprocessing environment before the canonical runtime registry BlueId is published. The registry release MUST publish the exact nodes and BlueIds it uses.

Non-normative examples, rationale, translations, and implementation notes are not part of canonical runtime type nodes unless explicitly included in the registry node.

### A.1 Base runtime type nodes

#### Contract

```yaml
name: Contract
description: >
  Base Blue Contracts and Processor 1.0 runtime type for executable or
  processor-interpreted declarations under an active scope's contracts map.
  A Contract is scope-local, identity-bearing Blue content. The processor
  discovers materialized contract entries in the selected document, resolves
  each entry far enough to identify its effective runtime type BlueId, and
  either executes supported behavior or applies must-understand and fatal
  rules. Contract entries are sorted by effective order and contract-map key
  when ordering is required. A Contract by itself has no executable behavior;
  concrete subtypes define Channel, Handler, Marker, or extension semantics.
order:
  type: Integer
  description: >
    Optional deterministic sort key within a scope. Missing order is treated
    as 0. Ordering compares order first, ascending, then contract-map key in
    lexicographic Unicode code-point order.
```

#### Json Patch Entry

```yaml
name: Json Patch Entry
description: >
  Blue Contracts and Processor 1.0 runtime patch request produced by handlers.
  A Json Patch Entry describes one deterministic mutation request against the
  selected document. Only add, replace, and remove are supported. The path is
  a Blue Runtime Pointer and must not target the document root. Despite its
  historical name, Json Patch Entry is not full RFC 6902; it uses Blue-specific
  upsert, auto-materialization, runtime insertion normalization, and post-patch
  type-soundness rules. The val field is required for add and replace and must
  be absent for remove. Patches are applied in result order; each successful
  patch triggers its full Document Update cascade before the next patch is
  applied. Field is named val, not value, because value is Blue's scalar
  payload wrapper.
op:
  type: Text
  description: >
    Required patch operation. Allowed values are add, replace, and remove.
  schema:
    required: true
    enum: [add, replace, remove]
path:
  type: Text
  description: >
    Required absolute Blue Runtime Pointer identifying the mutation target.
    The empty string is invalid. The root pointer / is not a valid runtime
    patch target for handlers or channels.
  schema:
    required: true
val:
  description: >
    Patch payload for add and replace. It may be any valid Blue node. It must
    be absent for remove.
```

#### Contract Execution Result

```yaml
name: Contract Execution Result
description: >
  Abstract processor result shape used to normalize effects returned by a
  supported handler or by a supported channel type that explicitly permits
  channel results. In Blue Contracts 1.0 core, patches and Triggered emissions
  are handler effects. External channels must not return patches or Triggered
  events unless a supported extension explicitly grants that capability. When
  a result is applied, the processor applies explicit gas first, then patches
  in order with immediate cascades, then emitted events in order, then a
  requested termination. Invalid present result fields cause runtime fatal
  termination before any effects from that result are applied, except for
  overhead already charged.
patches:
  type: List
  itemType:
    type: Json Patch Entry
  description: >
    Optional list of patch entries. Missing is equivalent to an empty list.
    Patches are applied in list order. Each successful patch triggers its
    Document Update cascade before the next patch.
triggeredEvents:
  type: List
  description: >
    Optional list of Blue event nodes to record and enqueue as Triggered
    events after all patches from the same result are applied. Missing is
    equivalent to an empty list.
gasConsumed:
  type: Integer
  description: >
    Optional non-negative explicit gas consumed by the contract. Missing is
    equivalent to 0. Negative gas is invalid and causes runtime fatal
    termination.
termination:
  description: >
    Optional termination request. If present, it requests graceful or fatal
    termination after gas, patches, and emitted events from the same result
    have been processed in the required order.
```

Canonical Blue field names use camelCase. Pseudocode may use snake_case aliases for readability; they refer to the same abstract result fields.

### A.2 Contract role runtime type nodes

#### Channel

```yaml
name: Channel
type: Contract
description: >
  Runtime contract role for event entry points within a scope. A Channel
  evaluates an incoming event or processor-managed delivery and either rejects
  it or accepts it by producing one channelized payload for same-scope
  handlers bound to that channel key. A Channel may consume gas and may request
  termination only through processor-defined interfaces. A Channel must not
  directly mutate the selected document. Processor-managed channel subtypes are
  fed only by the processor and are never directly entered by external events.
event:
  description: >
    Optional channel-specific matcher or matcher configuration. The meaning is
    defined by the concrete channel type.
```

#### Handler

```yaml
name: Handler
type: Contract
description: >
  Runtime contract role for deterministic logic bound to exactly one channel
  in the same scope. A Handler is eligible only for deliveries produced by the
  same-scope channel named by its channel field. A Handler may request patches,
  emit Blue event nodes, consume non-negative gas, or request termination. It
  has no other permitted observable side effects. For a given document
  snapshot, channelized payload, handler contract content, and allowed context,
  a Handler must produce deterministic results.
channel:
  type: Text
  description: >
    Required same-scope contract-map key of the channel this handler binds to.
    Handlers do not bind to channels in parent, child, embedded, or referenced
    nodes.
  schema:
    required: true
event:
  description: >
    Optional handler-specific matcher for the channelized payload. The meaning
    is defined by the concrete handler type or extension runtime.
```

#### Marker

```yaml
name: Marker
type: Contract
description: >
  Runtime contract role for processor-observed state or policy. Markers do not
  run contract logic. The processor obeys supported marker semantics when a
  supported marker appears at the correct reserved key. Unsupported marker
  types in an active scope are subject to must-understand rules. Required
  processor-managed markers have reserved keys under contracts and must not
  appear under other keys.
```

### A.3 Processor-managed marker runtime type nodes

#### Process Embedded

```yaml
name: Process Embedded
type: Marker
description: >
  Required processor-managed marker at contracts/embedded. It declares
  embedded child scopes beneath the current scope. The processor reads paths
  dynamically during embedded traversal, re-reads after each processed child,
  processes each normalized child path at most once per parent invocation, and
  rejects malformed, duplicate, self-root, or non-object embedded scope paths
  according to the processor rules. Missing child paths are skipped and marked
  processed for the current invocation.
paths:
  type: List
  itemType:
    type: Text
  description: >
    Required list of scope-relative Blue Runtime Pointers identifying embedded
    child roots. Each path must begin with /, must not be /, and must resolve
    inside the current scope's pointer domain. Duplicate resolved child paths
    are invalid.
  schema:
    required: true
    uniqueItems: true
```

#### Processing Initialized Marker

```yaml
name: Processing Initialized Marker
type: Marker
description: >
  Required processor-managed marker at contracts/initialized. It records that
  a scope has completed first-run initialization. The processor publishes the
  Document Processing Initiated lifecycle event before writing this marker.
  The marker is written by a processor-managed patch that triggers the normal
  Document Update cascade. The marker stores the pre-initialization Content
  BlueId of the scope subtree as documentId.
documentId:
  type: Text
  description: >
    Required BlueId string for the pre-initialization Content BlueId of the
    scope subtree. The value must be a valid Blue Language BlueId string.
  schema:
    required: true
```

#### Processing Terminated Marker

```yaml
name: Processing Terminated Marker
type: Marker
description: >
  Required processor-managed marker at contracts/terminated. It records final
  runtime state for a scope. A scope with a valid pre-existing terminated
  marker is inactive for processing: it incurs scope-entry gas when entered,
  but it is not initialized, matched, bridged, drained, checkpointed, or run.
  Termination markers are written by processor Direct Write and do not emit
  Document Update cascades. An ancestor may replace or remove an embedded child
  root containing this marker as a whole.
cause:
  type: Text
  description: >
    Required termination cause. fatal means deterministic runtime fatal
    termination. graceful means contract-requested non-error termination.
  schema:
    required: true
    enum: [fatal, graceful]
reason:
  type: Text
  description: >
    Optional human-readable deterministic reason supplied by the processor or
    contract. It is content in the selected document and in emitted lifecycle
    events when present.
```

#### Channel Event Checkpoint

```yaml
name: Channel Event Checkpoint
type: Marker
description: >
  Required processor-managed marker at contracts/checkpoint. It stores
  idempotency state for external channel deliveries. Checkpoints are never used
  for processor-managed Document Update, Triggered Event, Lifecycle Event, or
  Embedded Node channels. The processor creates this marker lazily when an
  external channel accepts an event and no checkpoint exists. It updates
  lastEvents by Direct Write after successful external channel processing.
  Checkpoint Direct Writes do not emit Document Update cascades. By default,
  lastEvents stores the normalized checkpoint subject for each external
  channel's raw contract-map key, and newness is determined by the channel's
  effective checkpointIdentityMode. Pointer escaping is used only when writing
  the member by Direct Write; it is not part of the stored key.
lastEvents:
  type: Dictionary
  keyType:
    type: Text
  description: >
    Required dictionary keyed by raw external-channel contract-map key. Each
    value is the previous normalized checkpoint subject for that external
    channel. The default subject is the preprocessed incoming event node.
  schema:
    required: true
```

#### Type Generalization Policy

```yaml
name: Type Generalization Policy
type: Marker
description: >
  Optional processor-managed marker at contracts/generalization. It controls
  whether post-patch type soundness may be restored by dynamic type
  generalization in the current scope. If absent, the processor uses
  defaultMode nearest-valid with no rules. Handlers and channels must not
  patch this marker or its descendants in Blue Contracts and Processor 1.0.
defaultMode:
  type: Text
  description: >
    Optional default generalization mode for paths not governed by a more
    specific rule. Missing means nearest-valid. nearest-valid permits the
    processor to choose the nearest valid permitted ancestor type. reject makes
    a patch fatal when restoring soundness would require generalization.
  schema:
    enum: [nearest-valid, reject]
rules:
  type: List
  itemType:
    type: Type Generalization Rule
  description: >
    Optional ordered list of path-specific generalization rules. The most
    specific matching path wins; if two rules normalize to the same path, the
    later rule in list order wins.
```

#### Type Generalization Rule

```yaml
name: Type Generalization Rule
description: >
  Rule entry used by Type Generalization Policy. It governs a scope-relative
  subtree path and can reject dynamic generalization or require the generated
  type to remain equal to or a subtype of a declared floor type.
path:
  type: Text
  description: >
    Required scope-relative Blue Runtime Pointer identifying the governed
    subtree. The pointer is normalized against the scope containing the policy
    marker before rule selection.
  schema:
    required: true
mode:
  type: Text
  description: >
    Optional mode for this path. Missing means the policy defaultMode. reject
    forbids generalization at the governed path. nearest-valid permits the
    nearest valid permitted ancestor type.
  schema:
    enum: [nearest-valid, reject]
mustRemainSubtypeOf:
  description: >
    Optional type reference floor. If present, any generated type selected for
    the governed path must be equal to or a subtype of this type.
```

### A.4 Processor-managed channel runtime type nodes

#### Document Update Channel

```yaml
name: Document Update Channel
type: Channel
description: >
  Processor-managed channel fed after each successful runtime patch. For every
  successful patch, the processor discovers matching Document Update Channels
  from the post-patch selected document and delivers one Document Update
  payload per participating scope, from the patch origin scope toward root. A
  Document Update Channel matches when the absolute changed path is
  descendant-or-equal to the channel path resolved against the receiving scope.
  The channel is never checkpoint-gated and is never entered directly by
  external events. Triggered FIFO is not drained during Document Update
  cascades.
path:
  type: Text
  description: >
    Required scope-relative Blue Runtime Pointer watched by this channel.
    The channel matches patches whose absolute changed path is
    descendant-or-equal to ABS(scope, path).
  schema:
    required: true
```

#### Triggered Event Channel

```yaml
name: Triggered Event Channel
type: Channel
description: >
  Processor-managed channel that drains events emitted into a scope's Triggered
  FIFO. A scope drains its Triggered FIFO at most once per PROCESS invocation,
  during the scope's FIFO phase. Triggered FIFO delivery does not occur during
  Document Update cascades. If a scope has no Triggered Event Channel, emitted
  events are still recorded and may be bridged to a parent, but they are not
  locally delivered.
```

#### Lifecycle Event Channel

```yaml
name: Lifecycle Event Channel
type: Channel
description: >
  Processor-managed channel for lifecycle events emitted by the processor at a
  scope. Lifecycle events include Document Processing Initiated and Document
  Processing Terminated. Lifecycle events are delivered through Lifecycle Event
  Channels, recorded as bridgeable emissions for parent Embedded Node Channels,
  and, at root, appended to the root outbox. Lifecycle events are not enqueued
  into the Triggered FIFO unless a lifecycle handler explicitly emits a
  Triggered event.
```

#### Embedded Node Channel

```yaml
name: Embedded Node Channel
type: Channel
description: >
  Processor-managed channel in a parent scope that bridges recorded emissions
  from a processed embedded child scope. Bridging occurs after the parent has
  handled the external event and before the parent drains its Triggered FIFO.
  Child emissions are delivered in the order recorded by the child, and child
  scopes are bridged in the parent invocation's processed-path insertion order.
  Bridge gas is charged only when an emission is actually delivered to at
  least one matching Embedded Node Channel.
childPath:
  type: Text
  description: >
    Required scope-relative Blue Runtime Pointer identifying the embedded child
    root whose emissions this channel receives. The resolved child path is
    compared with the processed child scope path.
  schema:
    required: true
```

### A.5 Processor-emitted event runtime type nodes

#### Document Update

```yaml
name: Document Update
description: >
  Processor-emitted event delivered through Document Update Channels after each
  successful runtime patch. One Document Update payload is created per
  participating receiving scope for that patch. The path is relative to the
  receiving scope. before and after are immutable snapshots of the changed
  path before and after the patch, using null when the changed path was absent
  or removed. All handlers at the same receiving scope for the same patch see
  the same immutable payload object.
op:
  type: Text
  description: >
    Required operation that caused the update: add, replace, or remove.
  schema:
    required: true
    enum: [add, replace, remove]
path:
  type: Text
  description: >
    Required path of the changed node, relative to the receiving scope. / means
    the receiving scope root itself.
  schema:
    required: true
before:
  description: >
    Snapshot at the changed path before the patch, or null when absent.
after:
  description: >
    Snapshot at the changed path after the patch, or null when removed.
```

#### Document Processing Initiated

```yaml
name: Document Processing Initiated
description: >
  Processor-emitted lifecycle event published at a scope before the Processing
  Initialized Marker is written. It represents first-run initialization of
  that scope for the current selected document state. At root, this event is
  also recorded in the root outbox. At non-root scopes, it is bridgeable to a
  parent Embedded Node Channel. The documentId field is the pre-initialization
  Content BlueId of the scope subtree.
documentId:
  type: Text
  description: >
    Required BlueId string for the pre-initialization Content BlueId of the
    scope subtree.
  schema:
    required: true
```

#### Document Processing Terminated

```yaml
name: Document Processing Terminated
description: >
  Processor-emitted lifecycle event published at a scope when that scope
  terminates gracefully or fatally. It is delivered through Lifecycle Event
  Channels, recorded as bridgeable for parent Embedded Node Channels, and, at
  root, included in the root outbox. For a root fatal termination, this event
  appears before Document Processing Fatal Error.
cause:
  type: Text
  description: >
    Required termination cause: fatal or graceful.
  schema:
    required: true
    enum: [fatal, graceful]
reason:
  type: Text
  description: >
    Optional deterministic reason for termination.
```

#### Document Processing Fatal Error

```yaml
name: Document Processing Fatal Error
description: >
  Processor-emitted root outbox event appended when root processing terminates
  fatally. It is appended after Document Processing Terminated for the same
  root termination sequence. It is outbox-only: it is not delivered to
  Lifecycle Event Channels, is not recorded as bridgeable, and is not placed in
  the Triggered FIFO.
reason:
  type: Text
  description: >
    Optional deterministic fatal error reason.
```

### A.6 Optional external-channel example

The following is an informative example of how a profile or application may define an external channel type. It is not a Blue Contracts and Processor 1.0 core runtime type and MUST NOT be included in the canonical runtime registry unless intentionally published as a separate extension type with its own BlueId.

```yaml
name: Example Ordered Event Channel
type: Channel
description: >
  Illustrative external channel that accepts events with a monotonically
  increasing sequence number. This is not a required Blue Contracts and
  Processor 1.0 core runtime type.
sequencePath:
  type: Text
  description: Optional event pointer to a sequence value.
payloadType:
  type: Text
  description: >
    Optional BlueId string for the expected Blue type or schema of the
    channelized payload delivered to handlers.
checkpointSubject:
  type: Text
  description: >
    Optional checkpoint subject policy for this illustrative channel.
  schema:
    enum: [incoming-event, channelized-payload, channel-defined]
newnessPolicy:
  type: Text
  description: Optional illustrative newness policy.
  schema:
    enum: [content-idempotent, increasing-sequence]
```

This example is informative and MUST NOT be included in the core runtime registry unless intentionally published as an extension type.

---

## Appendix B — Common Implementer Mistakes

This appendix is informative.

### B.1 Do not execute `contracts` during Blue Language processing

The Blue Language treats `contracts` as identity-bearing content. Runtime execution happens only under this processor specification.

### B.2 Do not drain Triggered events during Document Update cascades

Cascades enqueue Triggered events. FIFO drain happens once in Phase 5.

### B.3 Do not let children patch outside their subtree

A child scope can patch strict descendants of itself only. It cannot replace its own root and cannot patch siblings.

### B.4 Do not let parents patch inside embedded children

A parent can replace or remove a child root as a whole, but cannot patch inside it.

### B.5 Do not emit Document Updates for Direct Writes

Checkpoint creation, checkpoint update, and termination marker writes are Direct Writes. They are visible state changes but do not cascade.

### B.6 Do not create checkpoints during initialization

Checkpoint creation is lazy and external-channel-specific.

### B.7 Do not skip must-understand

Unsupported active contracts must be detected before mutation whenever they are in the initial active processing closure.

### B.8 Do not use wall-clock or random behavior in contracts

Determinism is part of conformance.

### B.9 Do not treat lifecycle events as Triggered events

Lifecycle events are delivered through Lifecycle Event Channels and recorded for bridging. They are not enqueued into the Triggered FIFO unless a lifecycle handler emits them explicitly.

### B.10 Do not update checkpoints for stale or terminated channels

Checkpoint entries update only after successful external channel processing.

### B.11 Do not concatenate runtime pointer strings

Use `ABS` or `JOIN_SCOPE_PATH`. Root scope `/` plus `/contracts/x` must produce `/contracts/x`, not `//contracts/x`.

### B.12 Do not pre-filter external channels for free

Candidate external channels are charged before acceptance or rejection. Optimizations must preserve the same candidate set and gas.

### B.13 Do not compare source bytes for reserved marker preservation

Reserved processor marker preservation is Blue-node semantic equality after normalization, not YAML or JSON byte equality.

### B.14 Do not let dispatch mutation rewrite the current call list

Dispatch snapshots freeze which handlers/channels and executable contract content are called for the current delivery or Phase 3 candidate loop. Contract mutations affect later discovery points only.

### B.15 Do not store escaped checkpoint keys

`lastEvents` object members use raw contract-map keys. Escape `/` and `~` only when constructing a Blue Runtime Pointer for Direct Write.

### B.16 Do not apply handler effects immediately

Host APIs that look like `emitEvent`, `applyPatch`, or `terminate` buffer effect requests. Observable mutation, enqueueing, and termination happen only when the normalized result is applied.

### B.17 Do not commit type-unsound patches

After every patch, restore type soundness before delivering any Document Update cascade. If required generalization is rejected or has no valid target, the tentative patch is not committed.

### B.18 Do not reuse Language view-path parsing for runtime pointers

Blue Runtime Pointer `/` denotes the runtime root and is not a patch target. Blue Language view paths use RFC 6901 root `""`.

---

## Appendix C — Processor Result Status and Diagnostic Categories

This appendix is normative for conformance reporting. It does not require a particular host-language exception class, wire format, or exact error message.

A conforming processor API MAY expose any host-language result type. Blue Contracts 1.0 conformance fixtures use this abstract result shape:

```yaml
status: success | capability-failure | runtime-fatal | invalid-input
newDocument: <Blue node or unchanged document>
rootEvents: <list>
totalGas: <integer>
errorCategory: <category or null>
fatalScope: <runtime pointer or null>
```

The status values mean:

| Status | Meaning |
|---|---|
| `success` | Processing completed without capability failure, invalid input, or runtime fatal. |
| `capability-failure` | Initial must-understand or capability checking failed before runtime mutation. |
| `runtime-fatal` | Runtime began and a deterministic fatal condition terminated the executing scope. |
| `invalid-input` | The processing document, event, fixture, or processor API input is not valid enough to enter runtime. |

Conformance-visible deterministic failures MUST be classifiable into one of these categories:

| Category | Meaning |
|---|---|
| `InvalidProcessingDocument` | The input document is not a valid Processing Document. |
| `InvalidEvent` | The input event is malformed, unresolved Source syntax under the runtime API, or otherwise invalid. |
| `UnsupportedContract` | A required active contract or extension role is unsupported. |
| `InvalidReservedMarker` | A processor-reserved marker has an invalid type, key, or shape. |
| `InvalidRuntimeType` | A runtime type reference is malformed, unavailable, or incompatible with the expected role. |
| `ProviderUnavailable` | Required provider content is unavailable. |
| `ProviderBlueIdMismatch` | Provider-returned content does not verify against the requested BlueId. |
| `InvalidPatch` | A patch operation, pointer, path target, or operation/value combination is invalid. |
| `BoundaryViolation` | A patch violates scope, embedded boundary, root, or self-root rules. |
| `ReservedKeyWrite` | A handler or channel attempted to write a protected processor-reserved path. |
| `InvalidRuntimePointer` | A Blue Runtime Pointer is malformed or cannot be interpreted in its context. |
| `InvalidPatchValue` | A patch `val` fails runtime insertion normalization or Blue Language validity. |
| `TypeSoundnessViolation` | A tentative selected document cannot satisfy required type/schema soundness. |
| `GeneralizationRejected` | Effective Type Generalization Policy rejects required generalization. |
| `GeneralizationNoValidType` | No nearest valid permitted ancestor type exists for required generalization. |
| `ContractResultShapeError` | A handler or channel returned an invalid result shape or disallowed effect. |
| `HandlerExecutionError` | A handler fails during execution before returning a valid result. |
| `ChannelExecutionError` | A channel fails during matching, payload creation, or supported execution. |
| `CheckpointError` | Checkpoint creation, identity, newness, or update fails. |
| `GasError` | Deterministic gas accounting or budget policy fails. |
| `EmbeddedScopeError` | Embedded traversal, path normalization, no-resurrection, or child-scope setup fails. |
| `TerminationError` | Termination marker Direct Write and fallback cannot complete deterministically. |

An invalid document or run may contain multiple independent errors. Blue Contracts 1.0 does not define a universal precedence order for simultaneous failures. Conformance fixtures that assert an exact error category MUST isolate one primary error so that a conforming implementation can deterministically report that category without ambiguity. If a fixture intentionally contains multiple independent errors, it MUST assert only that the operation fails, or it MUST explicitly declare acceptable error categories.

---

*End of Blue Contracts and Processor Specification 1.0.*
