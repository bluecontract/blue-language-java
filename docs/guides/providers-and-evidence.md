# Providers and evidence

A `NodeProvider` retrieves candidate content. The Language verification
boundary decides whether that content proves the requested BlueId.

## Preserve all outcomes

| Outcome | Meaning | Cache/retry guidance |
| --- | --- | --- |
| `FOUND` | candidate content is available | verify before admission |
| `NOT_FOUND` | provider definitively has no candidate | may be cached as a transport result |
| `UNAVAILABLE` | answer cannot currently be established | retry according to host policy |
| `INVALID_EVIDENCE` | candidate/proof failed verification | deterministic for that evidence |

Do not collapse unavailable or invalid evidence into a null/miss. A transport
miss does not prove that a semantic field is absent.

## Verification modes

Plain exact content is checked against the requested direct BlueId. Source
content is bound to a declared Language/preprocessing environment before its
Source Document BlueId is established. Exact fragments are assembled and
verified as ordinary nodes. Finalized cyclic members require an admitted set
proof.

## Provider rules

- Return defensive values; callers must not mutate provider storage.
- Keep transport acquisition separate from identity verification.
- Never let cache hits change logical demand or semantic outcomes.
- Bound positive and negative caches; do not retain transient unavailability
  as definitive absence.
- Keep scanning, authorization, and application storage policy outside core
  semantics.

Run `ExpandCollapseProviderExample` from `:examples`. The implementation guide
is [Building a NodeProvider](building-a-node-provider.md); the physical model
is [provider-and-fragment-model.md](../architecture/provider-and-fragment-model.md).
