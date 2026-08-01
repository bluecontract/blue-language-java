# Fragmented PROCESS inputs and logical delivery

This note describes the runtime-neutral graph boundary used by:

```text
PROCESS(document, event)
```

There are still exactly two semantic inputs and one authoritative Root. A
fragment, cache entry, provider batch, and logical-delivery plan are execution
representations; none is a third authored input.

## Exact fragments are ordinary Blue

An exact fragment is ordinary Blue content whose preserved child subtrees may
be pure references. Replacing an inline child with a pure reference to that
child's exact BlueId preserves the identity of every ancestor, including
Root. There is no partial-node identity and no second graph model.

`ExactNodeGraphFragments` accepts one or more exact ordinary Blue roots and
exposes:

- the original, direct-fragment, and pure-reference form of each Root;
- immutable exact fragments keyed by their calculated BlueIds;
- a verified in-memory `NodeProvider`; and
- the canonically ordered fragment identity set.

Every served fragment is rechecked against the requested identity. Defensive
copies prevent caller mutation from changing the admitted graph. Plain
provider misses remain `NOT_FOUND`; invalid stored evidence is reported as
`INVALID_EVIDENCE`.

A finalized cyclic-set member reference, `MASTER#index`, is an opaque external
edge. The direct fragment preserves that exact reference and records it in its
edge metadata, but the local fragment map and provider do not claim content
under the member identity. A composed `CyclicAwareNodeProvider` may supply it
only with the owning set proof; a plain provider cannot make a member valid by
hashing its content independently. `this#index`, `ZERO_BLUEID`, malformed
member suffixes, materialized content that claims a member identity, inline
object cycles, and cycles among local ordinary fragments remain invalid.
Verified member content is never inserted into an ordinary canonical cache
under `MASTER#index`; that cache requires a standalone hash, which a cyclic
member deliberately does not have. Providers claiming cyclic awareness must
prove that the base identity is an admitted complete set and that the requested
index is a member—an ordinary node stored under the base cannot counterfeit
`base#0`.

The helper's fragments are deliberately ordinary provider content. A storage
runtime may choose coarser exact fragments—for example, a complete selected
executable body—or finer direct fragments. The semantic constraint is the
exact BlueId at each replaced edge, not the physical page size.

## Pure-reference Root and Event admission

For Node entry points, processing performs the following read-only admission
before semantic execution:

1. If Root or Event is a pure reference, request exactly that identity through
   the invocation's verified `ProcessingSnapshotManager`.
2. Verify that the returned direct content calculates to the requested BlueId.
3. Derive or validate immutable external-delivery evidence.
4. Open only the ancestor closure of evidence-selected scope paths.
5. Start `ProcessorEngine` from a deferred snapshot rather than resolving the
   complete transitive Root.

Snapshot-native entry points apply the same top-level check to the snapshot's
canonical Root. Supplying a resolved companion cannot turn a pure cyclic member
into an independently processable Root.

The Event-scoped external-channel context can materialize an exact reference
needed by registered immutable event functions. The default subscription-key
projection uses that boundary for referenced `subscriptionKey` or
`subscriptionKeys` fields. Header-time use fails closed: event evidence is not
available while constructing the revision-complete subscription surface.

Contract recognition continues to open exact contract contributions and type
headers. Executable body fields remain pure references until a matching
handler has been selected. A selected body is then fetched and verified once.
Unselected handlers and unrelated embedded branches are not opened merely
because they exist behind Root.

On mutation, persistent patching rebuilds the changed scope and its ancestor
spine to Root. Unchanged siblings retain their exact identities and, for
snapshot-native execution, their frozen structural instances. The resulting
Root remains ordinary Blue: it can be collapsed to one pure Root reference and
expanded through its exact fragment set without changing its BlueId or value.

The locality fixtures deliberately store External Channel and Handler headers
as independent exact fragments while retaining the processor-managed
initialization marker inline for direct reserved-state validation. Runtime
type definitions come from the verified registry provider, selected Handler
bodies come from separate fragments, and unselected bodies stay cold.

## Semantic demand versus physical acquisition

The portable execution model records logical contract recognition, selected
body demands, handler work, patches, checkpoint work, and Root events. It does
not charge provider calls, bytes, cache hits, transport pages, or batch shape.

Consequently:

```text
semantic demand + logical gas + canonical work trace
    are portable

provider calls + provider bytes + cache hits + backend batches
    are host diagnostics
```

A warm cache may eliminate backend reads, and a provider may prefetch a
bounded batch, without changing the logical demand set, gas, result, or trace.
Invalid evidence is deterministic. Transient `UNAVAILABLE` evidence uses the
noncommitting attempt/suspension boundary. Definitive absence does not prove a
semantic field is absent unless a complete exact direct node or manifest
establishes that fact.

## Source classification and logical handler delivery

External source classification and handler dispatch are separate immutable
phases.

An External source member has registered subscription, acceptance, payload,
and checkpoint functions. A read-only Channel member is narrower: it proves
that one effective same-scope contract is an External or processor-managed
Channel and freezes only its key, order, effective type, ordered source
contributions, role, deterministic header dependencies, and sanitized header
identity. It grants no source acceptance, checkpoint, handler execution, or
executable-body capability.

During subscription-header evaluation, a runtime that knows one fixed target
uses `dependOnSameScopeChannel(key)`. A runtime whose event can name any target
uses `dependOnSameScopeChannelCatalog()`. During event evaluation,
`channel(key)` may read only a header covered by that retained declaration.
The whole-catalog dependency records both every Channel header and the
canonical raw-key membership of the effective contract map. Consequently an
empty result proves exact absence, while a present non-Channel key fails
distinctly without recognizing that unrelated header.

The dependency snapshot is stored in the active subscription interval and
participates in its checkpoint-domain identity. Phase B reconstructs the raw
External source plus exactly the declared Channel headers. Executable bodies
remain preserved references, and unrelated contract headers are not recognized
merely because they are inline. A removed, retyped, reordered, or replaced
dependency causes retained evidence to fail rather than becoming a false
negative. Missing provider evidence uses the existing noncommitting resource
acquisition boundary.

Each accepted-new source evaluation retains:

- its raw source channel and checkpoint domain;
- its exact frozen payload and checkpoint subject;
- a same-scope handler-selection channel; and
- a deterministic logical-delivery key.

The defaults return the raw source channel for both keys, preserving the
one-source/one-dispatch behavior of existing runtimes.

After rejected and stale sources are removed, accepted-new evaluations are
grouped by `(scope path, logical-delivery key)`. Members of one group must name
the same handler-selection channel and the same exact payload identity.
Routing output is validated before mutation. Every peer target must have been
declared exactly or through the catalog; only the source key is implicit. The
selected read-only target snapshot is frozen with the classification and
compared with the fully preflighted same-scope Channel again before Phase C
mutation.

One valid group executes its target handlers once. Every fresh participating
raw source owns a checkpoint write, but those writes become authoritative only
after the handler and its internal event drain complete successfully. A
failure, termination-before-checkpoint, gas exhaustion, cut-off, or rollback
commits none of the group's source checkpoints. A stale or rejected source is
not a participant. The target channel is never evaluated or checkpointed as an
external source unless it independently appeared as an accepted source.

The grouping plan is run-local and is not exposed through `ProcessResult`.

## Runtime extension rules

`ExternalChannelSubscriptionFunctions` is the only runtime-specific extension
surface involved here. Implementations may use the immutable
`ExternalChannelFunctionContext` to:

- declare and inspect one exact same-scope Channel header;
- declare a bounded same-scope Channel catalog and perform one exact event-time
  key lookup;
- inspect genuine same-scope External-source dependencies;
- enumerate a shallow effective-type family;
- match exact inline or referenced candidates against a Blue pattern;
- materialize an exact event-scoped reference; and
- select a handler channel and logical-delivery identity.

These functions must be deterministic and representation-blind. They cannot
perform ambient I/O, inspect mutable post-start state, invent source
occurrences, or demand executable bodies to decide routing.

## Effective fragmentation catalog

Application-specific splitters can inspect the kernel's effective boundaries
without executing contracts:

```java
EffectiveFragmentationCatalog catalog =
    documentProcessor.effectiveFragmentationCatalog(root);
```

The immutable result reports the exact Root BlueId, effective
`Process Embedded` paths by scope, and ordered effective contract snapshots by
scope. Each snapshot exposes its raw key, effective runtime type, runtime role,
ordered exact source-contribution identities, sanitized immutable header
fields, registered executable-body field names, and exact present body BlueIds
by field. It never assigns an identity to a synthetic merged contract and
never fetches a body merely to report the BlueId already present at its edge.

Inspection uses the processor's verified snapshot/provider context, so inline,
partially materialized, pure contracts-map reference, and pure Root reference
forms produce the same catalog. Inherited contracts and inherited
`Process Embedded` declarations are included. Unsupported effective types fail
closed. Discovery follows only declared participating scopes: a referenced
embedded child is opened when its effective `Process Embedded.paths` entry is
known, while unrelated data references remain cold. Registered body fields and
the Handler event edge are preserved before Language resolution. The operation
is read-only and outside Contracts gas.

## Deliberate limits

- A whole ordinary Root or Event may contain or be typed by an opaque finalized
  cyclic-member reference. A top-level pure member is not an independently
  processable Root/Event because the runtime has no identity-bound cyclic-set
  transaction.
- Reading through a member requires a cyclic-aware verified provider. Patching
  strictly below the pure member edge fails with
  `CyclicSetMutationUnsupported` before provider demand; replacing the whole
  edge remains allowed. `Process Embedded` traversal cannot cross the opaque
  edge. An ordinary type-inheritance cycle remains a distinct `TypeCycle`
  failure.
- Generic functions can materialize exact event fragments, but application
  parsing, authorization, registry policy, and source persistence remain
  outside this library.
- A direct fragment establishes absence only for fields covered by its exact
  direct content. An incomplete provider manifest cannot establish absence.
- The compatibility method named `NodeProviderWrapper.unverified` remains for
  released binary consumers, but it now enforces the same verification as
  `wrap`; Language 1.0 has no trusted-provider bypass.
