# Embedded collection paths

Use `Process Embedded.collectionPaths` when one Root owns a dynamic set of
process occurrences addressed by stable object keys. Use `paths` when the Root
owns one child at one exact pointer.

```text
paths:           /payment means the one scope /payment
collectionPaths: /lessons means every direct member /lessons/<stable-key>
```

Both declarations produce concrete scope paths before an event is processed.
The collection container is not itself selected.

## Declaration

```yaml
name: Agreement Root
lessons:
  lesson-a: { ... }
  lesson-b: { ... }
contracts:
  embedded:
    type: Process Embedded
    collectionPaths:
      - /lessons
```

The effective plan contains `/lessons/lesson-a` and
`/lessons/lesson-b`. Direct member keys are ordered by Unicode code point, then
each key is RFC 6901 escaped to form its concrete pointer. Exact and generated
pointers are then combined in canonical Runtime Pointer order; this final
encoded-pointer order can differ from the preceding raw-key order for keys
containing `/` or `~`. Map insertion order and host-language iteration order do
not affect processing.

## Closed selection rules

Contracts 1.0 intentionally keeps collection selection finite and stable:

- `collectionPaths` accepts a normalized scope-relative Runtime Pointer,
  beginning with `/`, to an object-compatible collection.
- Every present direct member must be an object or verified pure reference to
  an object.
- `*` has no wildcard meaning anywhere in the pointer.
- A pointer to a List does not embed its positions.
- `/contracts` and every path below it are reserved and cannot be embedded.
- An exact path and a generated collection-member path cannot overlap.
- A member key is the occurrence address. Removing and re-adding the same key
  begins a fresh occurrence lineage.

Stable object keys avoid renumbering scope paths, subscriptions, checkpoints,
and audit references when another member is inserted or removed.

## Exact local Channel bindings

Each child is self-contained. A Lesson may define local semantic roles such as
`teacherChannel` and `studentChannel`, and several Lessons may reuse the same
exact Channel value either inline or as a pure BlueId reference. Those two
forms are semantically equivalent after exact evidence is verified.

There is no implicit parent lookup. A child Handler cannot bind to a parent
Channel merely because both contracts use the same raw key. Replacing a parent
participant Channel does not rewrite or rebind existing children. An operation
that creates a new child may explicitly copy or reference the parent's current
exact Channel value into that new child.

The same complete child BlueId may also appear at two keys:

```yaml
lessons:
  lesson-a: {blueId: <same-lesson-blue-id>}
  lesson-b: {blueId: <same-lesson-blue-id>}
```

This shares immutable initial content, not mutable state. A successful patch at
`/lessons/lesson-a` rebuilds that occurrence and its ancestor spine;
`/lessons/lesson-b` stays unchanged.

## Activation is a commit boundary

Membership is frozen at invocation entry:

```text
event N entry      lesson-a and lesson-b are active
event N executes   Root adds lesson-c
event N commit     subscription delta adds /lessons/lesson-c
event N + 1        lesson-c may receive an eligible event
```

The creating event cannot also process `lesson-c`. Its added subscription
interval starts strictly after the creating event's external order key. A
failed or rolled-back event publishes neither the new member nor its interval.

Removing an active member cuts off that occurrence and its descendants during
the current invocation. Re-adding the key later creates a fresh activation and
checkpoint lineage.

## Targeting remains a Channel concern

`collectionPaths` says which nodes are active scopes; it does not define an
external addressing protocol. Each registered External Channel runtime must
derive a finite deterministic key set. It can include a continuing document
identity, participant identity, timeline identity, or another protocol-defined
component. The feeder supplies the matching concrete occurrence, and the
processor verifies that evidence against the frozen scope and Channel header.

Consequently, many Lessons can reuse one exact participant Channel while one
event still targets only `/lessons/lesson-a`. Generic Contracts does not
require a field literally named `documentId` or `lessonId`; the concrete
Channel runtime owns that vocabulary.

## Complete Agreement example

The runnable example has this shape:

```text
Agreement Root
└── lessons
    ├── lesson-a
    └── lesson-b
```

[`EmbeddedCollectionAgreementExample`](../../examples/src/main/java/blue/language/examples/EmbeddedCollectionAgreementExample.java)
registers an application-neutral occurrence Channel and a generic declared-
patch Handler. It then proves the complete lifecycle:

1. `lesson-a` and `lesson-b` start from the same Lesson BlueId and reuse the
   same exact participant Channel value.
2. A Channel-specific occurrence key targets only `lesson-a`, so its progress
   becomes `1` while `lesson-b` remains `0`.
3. An Agreement Root event adds `lesson-c` and replaces the parent's participant
   Channel.
4. `lesson-c` remains at progress `0` during the creating event.
5. The platform commit companion contains the concrete added interval for
   `/lessons/lesson-c`, starting after that event.
6. Existing Lessons retain the old exact participant Channel; `lesson-c`
   explicitly uses the new one.
7. The next eligible concrete event targets `lesson-c` and changes its progress
   to `1`.

The example is compiled, executed through `DocumentProcessor`, and asserted by
the examples test suite. Run its focused test with:

```bash
./gradlew :examples:test --tests \
  blue.language.examples.ContractsProcessingExamplesTest.shouldActivateCreatedLessonOnlyAfterTheCreatingEventCommits
```

Every examples-project `main()` is also discovered and run by:

```bash
./gradlew documentationVerify
```

## Collection performance campaign

`EmbeddedCollectionPathsBenchmark` measures projection, member addition and
removal, selected-member processing, pure-reference targets and headers,
fragmentation inspection, final subscription validation, and logical gas at
10, 100, 1,000, and 4,096 direct members. The GC profiler reports allocation
rate and bytes per operation; auxiliary counters report exact provider demands,
materialized references/manifests, handlers executed, and gas-trace entries.

Compile every benchmark, then run a review-sized campaign with:

```bash
./gradlew :blue-contracts-core:jmhClasses
./gradlew :blue-contracts-core:jmh \
  -PblueJmhIncludes='.*EmbeddedCollectionPathsBenchmark.*' \
  -PblueCollectionJmhSize=10
```

The projection lanes intentionally enumerate the complete direct key set; the
optimization claim is linear enumeration plus branch-local executable-body
loading, not constant-time collection discovery.

## Failure and recovery checklist

When a declaration is rejected, check these in order:

1. the declaration is a List of normalized relative pointers;
2. the target is object-compatible rather than a List or scalar;
3. every direct member is an object or verified object reference;
4. no pointer contains wildcard syntax or enters a reserved field;
5. generated and explicit concrete paths do not overlap;
6. the feeder's active intervals and delivery paths use the same escaped
   concrete occurrence paths;
7. the creating event is not being replayed as if the new interval were already
   active.

Malformed declarations fail before ordinary no-match classification. Exact
provider evidence that is temporarily unavailable remains a resumable proof
requirement; it is not treated as semantic absence.

### Deterministic diagnostics and limits

Collection declaration failures complete with
`SUBSCRIPTION_SURFACE_INVALID`; the diagnostic category identifies the exact
semantic law:

| Category | Meaning |
| --- | --- |
| `EmbeddedCollectionMustBeObject` | A present collection target is a scalar, List, or another non-object value. |
| `EmbeddedCollectionMemberMustBeObject` | A present direct member is not object-compatible after exact evidence is verified. |
| `InvalidEmbeddedCollectionPath` | The declaration is malformed, enters a reserved field, or cannot name a collection target. |
| `EmbeddedPathSelectorUnsupported` | The declaration attempts wildcard, glob, selector, or query syntax. |
| `CyclicSetEmbeddedBoundaryUnsupported` | A cyclic-set member would have to be traversed as an embedded scope. |
| `OverlappingEmbeddedDeclaration` | Exact and collection declarations overlap, are ancestor-related, graph-equivalent, or generate one concrete path twice. |

Two independent portable limits are both 4,096 per owning scope:

```text
authored declarations: paths.size + collectionPaths.size <= 4096
frozen concrete children: exact present paths + generated members <= 4096
```

Exceeding either limit completes with `PORTABLE_LIMIT_EXCEEDED`, not
`SUBSCRIPTION_SURFACE_INVALID`. Its deterministic details identify the limit,
observed count, and maximum. Provider `NOT_FOUND`, `UNAVAILABLE`, and
`INVALID_EVIDENCE` also remain distinct: absence is semantic only where the
specification permits an absent target; unavailable evidence suspends
`PROCESS_ATTEMPT`; invalid evidence fails admission. None is rewritten as a
collection shape diagnostic.

The design rationale is recorded in
[ADR 0008: Explicit stable-key embedded collections](../adr/0008-explicit-stable-key-embedded-collections.md).
