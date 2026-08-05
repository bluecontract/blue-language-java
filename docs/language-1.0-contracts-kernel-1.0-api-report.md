# Distribution API report

The checked-in API report is generated from Java 8 artifacts. See the
[public API inventory](reference/public-api.md) for exact descriptors and the
[package inventory](reference/packages.md) for ownership. Intentional major-
version changes are explained in the
[modernization migration guide](language-1.0-contracts-kernel-1.0-migration.md).

## Additive platform invocation API

The indexed platform lane adds supported, runtime-neutral Contracts API:

| API | Classification | Purpose |
| --- | --- | --- |
| `PlatformProcessInvocation` and its builder/accessors | Public immutable value | Carries one evaluator-bound exact delivery plan and one borrowed invocation provider. |
| `BlueContracts.processForPlatformCommit(Node, Node, PlatformProcessInvocation)` | Public operation | Processes an already prepared plan without invoking the construction-time deriver. |
| `LanguageProcessing.openScope(NodeProvider)` and observed overload | Public Language SPI, additive default methods | Opens a strict isolated provider domain without implicit fallback. |
| `LanguageProcessing.Scope.runtimeAccess()` and `newConformanceEngine()` | Public Language SPI, additive default methods | Keeps matching, resolution, and conformance in the same scoped provider/cache domain. |
| `NodeProviderWrapper.verifyOnly(...)` / `verifyOnlyGuarded(...)` | Protected Language implementation hooks | Let the built-in processing bridge preserve a private, unforgeable fallback-free provider boundary and hold lifecycle admission around verified reads; ordinary callers use `LanguageProcessing.openScope(NodeProvider)`. |

The existing PROCESS, evidence, snapshot, attempt, projection, and
current-Root-deriver APIs remain available. The new value is execution
environment rather than a semantic carrier: Root and event remain the only
Blue inputs. The Language SPI additions are default methods so existing bridge
implementations remain linkable. The single-provider default fails closed;
the observed overload delegates to it so a bridge has one strict-scope opt-in
point and cannot silently fall back to a construction provider.
Exact descriptors and entry counts belong to the generated inventory and are
regenerated from the candidate Java 8 artifacts rather than maintained by hand
in this authored summary.
