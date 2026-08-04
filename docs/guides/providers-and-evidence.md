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

Run
[`ExpandCollapseProviderExample`](../../examples/src/main/java/blue/language/examples/ExpandCollapseProviderExample.java)
from `:examples`. The implementation guide is [Building a
NodeProvider](building-a-node-provider.md); the physical model is
[provider-and-fragment-model.md](../architecture/provider-and-fragment-model.md).

## Strict invocation providers

`PlatformProcessInvocation.nodeProvider()` is the complete provider graph for
one platform PROCESS attempt. The Language bridge opens a fresh strict scope
over exactly that graph. It wraps and BlueId-verifies provider results, but it
does not add the Language bootstrap provider, the provider configured when the
service was built, or entries discovered in a different invocation.

That strictness applies to admission, matching, resolution, selected contract
and executable content, patch opening, and final validation. It preserves the
four outcomes above at the point of demand:

- a verified `FOUND` candidate can participate;
- `NOT_FOUND` remains a definitive miss in the supplied provider domain;
- `UNAVAILABLE` remains incomplete execution evidence and may be retried under
  host policy;
- `INVALID_EVIDENCE` remains a deterministic evidence failure even when some
  construction-time or global provider could have returned valid content.

No hidden fallback is attempted after any of those outcomes. If an invocation
requires the canonical registry and an application store, for example, the
caller must compose both into the supplied provider intentionally. Each scope
uses isolated provider-derived caches, so concurrent invocations cannot turn
one provider's miss, outage, or invalid candidate into another provider's
result.

The provider is borrowed: scope closure does not close it. The same is true of
the Language runtime borrowed by `BlueContracts`. Only the transient scope,
snapshot state, matching/conformance views, and Contracts caches created for
the invocation are released. See [Process an already prepared
plan](runtime-projection-and-indexed-delivery.md#process-an-already-prepared-plan)
for the complete public call.

This provider and the evaluator-produced delivery plan are verified execution
environment, not additional Blue inputs. Root and event remain the complete
semantic input pair. Contracts independently revalidates the supplied plan's
Root, event, revision, order, registry, activation, contribution, dependency,
completeness, and canonical-order bindings; it does not call the
construction-time `ExternalDeliveryPlanDeriver` on this path. The registry
binding is calculated from portable registration metadata and excludes Java
class names and processor object identity.
