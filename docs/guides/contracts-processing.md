# Contracts processing

The generic kernel evaluates exactly two semantic inputs:

```text
PROCESS(Root, event) -> ProcessResult
```

Root is one exact authoritative document. Embedded scopes are owned paths in
that Root, not separate sessions. Only success publishes a replacement Root
and Root-scope events.

## One invocation

```mermaid
flowchart TD
    Feeder["feeder selects and orders"] --> Admission["admit exact Root/event"]
    Admission --> Evidence["derive or verify delivery evidence"]
    Evidence --> Preflight["freeze participating closure and headers"]
    Preflight --> Classify["classify source and target Channel"]
    Classify --> Body["load selected executable body"]
    Body --> Execute["execute handlers and apply patches"]
    Execute --> Drain["drain internal events FIFO"]
    Drain --> Validate["soundness + subscription validation"]
    Validate --> Commit["atomic Root/checkpoint/lifecycle commit"]
```

Every phase receives immutable input and owns one deterministic failure
boundary. Executable bodies remain cold until selected. Patches rebuild changed
spines persistently. Internal events can trigger more work, but only Root
emissions cross the output boundary.

## Feeder and processor

The feeder watches the finite subscription surface, obtains external events,
and orders candidate occurrences. It does not decide semantic acceptance or
handler behavior. Revision-complete delivery evidence lets the processor prove
that the occurrence belongs to the exact Root/registry generation.

The processor returns a semantic result. Platform delivery progress and an
external subscription index are committed through a separate companion when a
host needs one atomic database transaction.

## Source and target Channels

A source External Channel can accept an event and select a different
same-scope target Channel:

```text
source "inbox" accepts and owns checkpoint
target "orders" selects handlers
matching handlers execute once for one logical delivery group
```

The target is read-only for this classification unless it independently
participates as an external source. Dependency declarations freeze the exact
target header or bounded catalog used by event-time routing.

## Exact paths and stable-key collections

`Process Embedded` declares owned child scopes in two ways:

```text
paths:           one exact child scope per pointer
collectionPaths: every direct object member under the pointer
```

A `collectionPaths` entry is not a glob. It cannot contain `*`, select List
items, or enter `/contracts`. The direct member key becomes part of the
concrete scope path and remains the occurrence address. The processor freezes
those concrete paths before classification, so adding a member cannot make it
receive the event that created it. The successful post-commit subscription
delta makes it eligible for the next event.

Local Channels are exact child data. They can be inline or pure references to
the same exact value. No Channel is imported from a parent merely because its
raw key is the same, and replacing a parent Channel does not rebind an existing
child. A Channel runtime's finite keys continue to select a concrete member;
`collectionPaths` only establishes which members are active scopes.

## Result atomicity

Success commits patches, lifecycle markers, checkpoints, snapshot publication,
subscription changes, and Root events together. No-match, stale, terminated,
invalid input, runtime failure, gas exhaustion, portable-limit failure, and
subscription-surface failure publish no partial application state. Admitted
gas remains visible because it records work already performed.

Run
[`CustomExternalChannelExample`](../../examples/src/main/java/blue/language/examples/CustomExternalChannelExample.java),
[`RootOnlyEventsExample`](../../examples/src/main/java/blue/language/examples/RootOnlyEventsExample.java),
and
[`PureReferenceFragmentsExample`](../../examples/src/main/java/blue/language/examples/PureReferenceFragmentsExample.java)
from `:examples`. See the [Contracts pipeline](../architecture/contracts-pipeline.md).
The complete tested Agreement/Lessons workflow is in
[`EmbeddedCollectionAgreementExample`](../../examples/src/main/java/blue/language/examples/EmbeddedCollectionAgreementExample.java)
and is explained in [Embedded collection paths](embedded-collection-paths.md).
