# Embedded Process Modules, Participant Bindings, and Dynamic Collections

## Status

This document summarizes the decisions incorporated into the accompanying Blue Language 1.0 and Blue Contracts and Processor 1.0 specifications and conformance packages.

The changes are intentionally narrow. They do not redesign BlueId, the one-Root processor, event propagation, checkpointing, gas, or the feeder/processor boundary.

## 1. Reusable process modules are owned embedded scopes

A process such as a Lesson, Cancellation, Refund, Delivery leg, or Approval flow may be represented as a reusable Blue type with:

- its own state;
- local participant Channel roles;
- operations and workflows;
- local lifecycle and checkpoints;
- emitted events;
- nested owned processes.

One occurrence is an owned scope inside one authoritative Root. It is not an independently committed child session. A successful change rebuilds the child and every changed ancestor to one new Root.

## 2. Reuse external Timelines without creating a Timeline per process

Several embedded process occurrences may use the same exact Timeline, actor, or Channel definition.

```yaml
contracts:
  teacherChannel:
    blueId: <AliceTeacherChannelBlueId>
```

is equivalent to materializing the exact Channel node whose BlueId is supplied. Reusing the node does not copy Timeline history. Each scope occurrence still has its own path, lifecycle state, checkpoint state, and document state.

A single provider subscription may serve many logical bindings. Concrete Channel subscription and event keys determine which scope occurrences are candidates.

## 3. Participant roles are bound explicitly when an occurrence is created

Reusable types define local semantic roles, not parent lookups:

```text
teacherChannel
studentChannel
buyerChannel
sellerChannel
```

A concrete process occurrence supplies exact Channel values for those keys. The values may be inline or pure references.

The occurrence is self-contained after creation. Existing occurrences do not silently change when a parent Channel changes.

Recommended application behavior is:

```text
new occurrence:
  use the enclosing document's current participant configuration

existing occurrence:
  retain the exact bindings used when it was created

local participant change:
  use an explicit workflow inside the occurrence

agreement-wide migration:
  explicitly update or replace selected existing occurrences
```

The pre-change Channel snapshot governs the event that introduces a new participant set. The new subscription surface becomes active only after commit. This permits Alice and Bob to authorize a transition to Alice and Celine, after which Alice and Celine govern later events.

## 4. No informal live Parent Channel in Contracts 1.0

Contracts 1.0 does not define:

- `Parent Channel`;
- nearest-ancestor contract lookup;
- implicit import of parent Channels;
- live rebinding based on raw key equality;
- context-dependent child behavior based on whichever document embeds it.

The same child BlueId therefore does not acquire different participant semantics merely because it appears beneath a different parent.

A future cross-scope Channel port remains possible, but it must be an explicit separately published runtime type with complete rules for dependencies, subscription invalidation, checkpoint domains, cycles, ordering, gas, and missing targets. It must not be inferred informally.

## 5. Contract entries are not embedded scopes

`Process Embedded` continues to reject paths through `/contracts` and all other Language-reserved fields.

A Channel may be ordinary identity-bearing Blue content and may itself contain a `contracts` field as data, but the generic processor discovers executable contracts only from the effective `contracts` map of participating scopes. A contract entry is not made into a child process by embedding `/contracts/<key>`.

Governance of a parent Channel should normally be expressed through sibling operations and workflows at the parent scope, or through a separate ordinary embedded governance module that emits an event observed by the parent.

## 6. Dynamic process collections use `collectionPaths`

`Process Embedded` now supports two explicit declaration forms:

```yaml
contracts:
  embedded:
    type: Process Embedded

    paths:
      - /payment

    collectionPaths:
      - /lessons
```

`paths` declares one exact embedded scope per pointer.

`collectionPaths` declares that every direct ordinary member of an object-compatible collection is one embedded scope:

```text
/lessons/lesson-17
/lessons/lesson-18
```

The collection container itself is not implicitly a scope.

## 7. Stable object keys, not list positions or wildcards

Contracts 1.0 does not interpret:

```yaml
paths:
  - /lessons/*
```

as a wildcard, and it does not interpret a path to a List as “embed every item.”

Dynamic embedded collections use stable object keys. This avoids renumbering scope paths, activation intervals, checkpoints, and audit references when a list item is inserted or removed.

A collection target must be object-compatible. Every present direct member must be an object or a verified pure reference to an object.

## 8. Creating a new member makes it active on the next revision

A workflow may append a complete new Lesson under a stable key and inject existing participant Timelines or Channel references:

```yaml
op: add
path: /lessons/lesson-17
val:
  type: Lesson
  contracts:
    teacherChannel:
      blueId: <AliceTeacherChannelBlueId>
    studentChannel:
      blueId: <BobStudentChannelBlueId>
```

The creating event does not also process the new Lesson. After the Root commits:

- the new concrete scope path is indexed;
- its subscription interval starts strictly after the creating event;
- it is fully active for the next eligible event.

Removing a member retires its occurrence. Re-adding the same key begins a fresh interval and checkpoint lineage.

## 9. Same exact child content at two keys means two owned occurrences

This is valid:

```yaml
lessons:
  lesson-a:
    blueId: <LessonTemplateBlueId>
  lesson-b:
    blueId: <LessonTemplateBlueId>
```

The exact initial content is shared, but the occurrences are independent. Processing `lesson-a` creates a new state at `/lessons/lesson-a`; `/lessons/lesson-b` remains unchanged.

Shared mutable state must be an autonomous Root. Reusing an initial BlueId does not create shared mutation.

## 10. Event targeting remains Channel-specific

`collectionPaths` defines which nodes are active scopes. It does not define the addressing protocol for external events.

Every concrete External Channel type defines its own finite subscription and event keys. A Timeline protocol may use:

```text
documentId + timeline identity + actor identity
```

so that many Lessons reuse Alice's Timeline while one Timeline Entry targets exactly one Lesson document occurrence.

The stable protocol document identity identifies the continuing occurrence. The BlueId identifies one exact immutable state of that occurrence.

Generic Contracts does not require a field literally named `documentId`; it requires the exact Channel runtime to publish deterministic keys and acceptance semantics.

## 11. Composite Channel versus Group Timeline

A logical group of existing participant Channels is a concrete Composite Channel concern, not a new “Group Timeline.” A Group Timeline would mean one provider-maintained shared append-only history, which is a different concept.

Composite OR, quorum, unanimous approval, and membership governance are concrete runtime/workflow semantics outside the generic Contracts core. Participant changes that require several approvals should be represented as explicit stateful workflows rather than inferred from group membership alone.

## 12. Specification and artifact impact

The Language semantics and Language conformance fixtures are unchanged. The Language prose receives only an informative example of exact-node reuse.

Contracts changes include:

- `Process Embedded.collectionPaths`;
- exact collection-member snapshot and activation rules;
- mutation-boundary rules for collection members;
- same-scope self-containment and no implicit parent binding;
- channel-specific addressing guidance;
- updated protected-state rules;
- new diagnostics and conformance vectors.

The canonical `Process Embedded` node changed, so its BlueId and the Contracts runtime-registry package identity changed. Every fixture reference to the marker and the Contracts fixture-package identity were regenerated.

## 13. Final architecture in one sentence

> Reusable embedded processes are self-contained owned scopes instantiated with exact local participant bindings; dynamic stable-key collections are declared explicitly through `collectionPaths`; external targeting remains the responsibility of each concrete Channel type; Contracts 1.0 does not introduce live parent-channel inheritance.
