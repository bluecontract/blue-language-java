# ADR 0005: Fragments are ordinary Blue nodes

Status: accepted for Blue Language and Contracts 1.0.

## Context

Large graphs benefit from provider-backed paging, but a second “partial node”
value model would create different identity and processing rules.

## Decision

An exact fragment is ordinary exact Blue content. At a selected cut, an inline
child is replaced by a pure reference to that child's exact BlueId. Replacing
the representation in this way preserves every ancestor identity.

Each fragment is verified at its provider boundary before use. Fragment size,
cache layout, batching, bytes, and backend trips are host concerns; semantic
demand and portable gas remain representation-invariant. Finalized cyclic-set
member references remain opaque unless the provider supplies the owning set
proof.

## Consequences

- There is no fragment BlueId or partial-node identity.
- Missing, unavailable, and invalid evidence remain distinct typed outcomes.
- Unselected executable bodies and unrelated branches stay cold.
- Persistent patching rebuilds only the changed spine while unchanged exact
  fragments retain identity.

See [Providers and evidence](../guides/providers-and-evidence.md) and
[Fragmented processing](../guides/fragmented-processing.md).
