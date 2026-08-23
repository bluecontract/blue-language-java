# Full-lifecycle admission fixture coverage

## Status and identity boundary

This report maps the planned portable `FL-ADM-01` through `FL-ADM-10`
scenario families to their identity-free authored sources. The sources are
present; executable fixture generation, manifest admission, and execution are
pending the canonical generator pipeline.

| Evidence stage | Status |
|---|---|
| Identity-free authored sources | **AUTHORED** |
| Independent source-design audit | **PASS** |
| Generator structural/semantic validation | **PENDING** |
| Canonical identity/result generation | **PENDING** |
| Two clean generations compared byte-for-byte | **PENDING** |
| Executable fixture manifest admission | **PENDING** |
| Normative full-lifecycle fixture execution | **PENDING** |

No source file contains a hand-authored document BlueId, managed-binding
identity, component identity, closure identity, invocation identity, work or
event occurrence identity, gas trace, commit identity, package identity, or
expected exact processor result. Registry BlueIds and all derived evidence
must be supplied by the canonical generator. A source-level `expect` block is
only a post-execution guard over the generated actual result; it is never
passed to the processor.

## Scenario and generated-case plan

The family has ten semantic scenarios and thirteen executable cases. The
three FL-ADM-06 cases are required to compare input order and representation;
the two FL-ADM-08 cases are required to compare deterministic retry evidence.

| Family | Authored source | Planned executable fixture(s) | Cases | Expected work and result boundary | Generated | Executed |
|---|---|---|---:|---|---|---|
| FL-ADM-01 | `blue-conformance/src/main/fixture-sources/full-lifecycle/fl-adm-01-root-patch-event.yaml` | `fl-adm-01-root-patch-event.yaml` | 1 | `INITIALIZATION ×1`, `LIFECYCLE ×1`, `TRIGGERED_EVENT ×1`; state patch and local reaction commit; one Root public event; zero checkpoints | PENDING | PENDING |
| FL-ADM-02 | `blue-conformance/src/main/fixture-sources/full-lifecycle/fl-adm-02-duplicate-equal-events.yaml` | `fl-adm-02-duplicate-equal-events.yaml` | 1 | `INITIALIZATION ×1`, `LIFECYCLE ×1`, `TRIGGERED_EVENT ×2`; two equal values retain occurrence ordinals `0,1`, matching delivery-source ordinals, and two public projections | PENDING | PENDING |
| FL-ADM-03 | `blue-conformance/src/main/fixture-sources/full-lifecycle/fl-adm-03-non-public-containing-route.yaml` | `fl-adm-03-non-public-containing-route.yaml` | 1 | `INITIALIZATION ×2`, `LIFECYCLE ×1`, `EMBEDDED_EVENT ×1`; private B event routes to public containing A; only A's new event is public | PENDING | PENDING |
| FL-ADM-04 | `blue-conformance/src/main/fixture-sources/full-lifecycle/fl-adm-04-document-update-continuation.yaml` | `fl-adm-04-document-update-continuation.yaml` | 1 | `INITIALIZATION`, then `LIFECYCLE`, then immediate `DOCUMENT_UPDATE`; both source and reaction patches commit; no public event/checkpoint | PENDING | PENDING |
| FL-ADM-05 | `blue-conformance/src/main/fixture-sources/full-lifecycle/fl-adm-05-graceful-termination.yaml` | `fl-adm-05-graceful-termination.yaml` | 1 | Self-cyclic Root: `INITIALIZATION ×1`, initiated and terminated `LIFECYCLE ×2`; terminated Handler patch and cause/reason commit; one marker with exact `WORK, WORK, TERMINATION_MARKER` boundaries | PENDING | PENDING |
| FL-ADM-06 | `blue-conformance/src/main/fixture-sources/full-lifecycle/fl-adm-06-canonical-order-representation-parity.yaml` | `fl-adm-06-order-representation-reference.yaml`, `...-reversed.yaml`, `...-inline.yaml` | 3 | Per case: `INITIALIZATION ×2`, `LIFECYCLE ×2`; canonical result, gas, work trace and finalization parity across forward/reversed maps and pure-reference/inline exact child forms | PENDING | PENDING |
| FL-ADM-07 | `blue-conformance/src/main/fixture-sources/full-lifecycle/fl-adm-07-finite-cyclic-route.yaml` | `fl-adm-07-finite-cyclic-route.yaml` | 1 | Explicit A/B member order; `INITIALIZATION ×2`, `LIFECYCLE ×1`, `EMBEDDED_EVENT ×2`; ping→pong reaches quiescence; exact `WORK, WORK, INITIALIZATION_BATCH` boundaries; atomic component commit | PENDING | PENDING |
| FL-ADM-08 | `blue-conformance/src/main/fixture-sources/full-lifecycle/fl-adm-08-infinite-cycle-gas-retry.yaml` | `fl-adm-08-infinite-cycle-gas-retry-first.yaml`, `...-retry.yaml` | 2 | Looping `EMBEDDED_EVENT` route exhausts shared gas; literal input rollback; no marker, public event, checkpoint or commit companion; retry must reproduce result, gas, work/finalization trace and rejected-charge evidence | PENDING | PENDING |
| FL-ADM-09 | `blue-conformance/src/main/fixture-sources/full-lifecycle/fl-adm-09-late-member-rollback.yaml` | `fl-adm-09-late-member-rollback.yaml` | 1 | Exact `INITIALIZATION, LIFECYCLE, INITIALIZATION, LIFECYCLE` order proves A patches before B fails; `RUNTIME_FATAL`; every tentative effect rolls back; no publication | PENDING | PENDING |
| FL-ADM-10 | `blue-conformance/src/main/fixture-sources/full-lifecycle/fl-adm-10-unknown-occurrence.yaml` | `fl-adm-10-unknown-occurrence.yaml` | 1 | Initialization patch creates an unbound `/child`; exact `SUBSCRIPTION_SURFACE_INVALID` / `SubscriptionSurfaceInvalid`; literal rollback and no partial publication | PENDING | PENDING |
| **Total** | **10 authored families** | **13 executable cases** | **13** |  | **PENDING** | **PENDING** |

## Mapping to the 17 specification claims

The numbered claims are the normative list in Contracts specification
§15.2.1. Portable fixtures cover processor semantics. Two non-semantic claims
remain mandatory companion gates because expressing them as closure fixture
controls would be inaccurate: claim 13 concerns physical cache availability,
and claim 17 selects a different Java API method.

| Claim | Required evidence | Portable family/case | Mandatory companion evidence | Status |
|---:|---|---|---|---|
| 1 | Root initialization patch applies | FL-ADM-01 | — | SOURCE AUTHORED |
| 2 | Initialization emission enters FIFO and local Handler reacts | FL-ADM-01 | — | SOURCE AUTHORED |
| 3 | Equal emissions remain distinct occurrences/deliveries | FL-ADM-02 | — | SOURCE AUTHORED |
| 4 | Private member emission reaches containing public Root without becoming public | FL-ADM-03 | — | SOURCE AUTHORED |
| 5 | Public Root initialization emission appears exactly once | FL-ADM-01 | — | SOURCE AUTHORED |
| 6 | Initialization patch routes its Document Update | FL-ADM-04 | — | SOURCE AUTHORED |
| 7 | Valid initialization termination completes deterministically | FL-ADM-05 | — | SOURCE AUTHORED |
| 8 | Canonical document initialization ignores input order | FL-ADM-06 `reference` vs `reversed` | — | SOURCE AUTHORED |
| 9 | Finite cyclic initialization/event route reaches quiescence | FL-ADM-07 | — | SOURCE AUTHORED |
| 10 | Infinite cyclic route exhausts shared gas and rolls back | FL-ADM-08 | — | SOURCE AUTHORED |
| 11 | Later member failure rolls back earlier tentative initialization | FL-ADM-09 | — | SOURCE AUTHORED |
| 12 | Inline and pure-reference input forms return the same result | FL-ADM-06 `reference` vs `inline` | — | SOURCE AUTHORED |
| 13 | Cold and warm exact-node availability return identical result/gas/trace | Not modeled as processor-semantic fixture control | `FullLifecycleAdmissionTest.requirement13ColdAndWarmExactNodeRunsHaveExactParity` | COMPANION GATE; EXECUTION PENDING |
| 14 | Identical retry reproduces deterministic evidence | FL-ADM-08 `first` vs `retry` | — | SOURCE AUTHORED |
| 15 | Admission writes no Timeline checkpoint | FL-ADM-01 explicitly; every successful family asserts zero writes | — | SOURCE AUTHORED |
| 16 | Unknown initialization occurrence is noncommitting exact failure | FL-ADM-10 | — | SOURCE AUTHORED |
| 17 | Bounded behavior exists only through explicit legacy API and is not normative | Not representable by the `admit-closure` protocol operation without calling the wrong API | focused bounded-compatibility test plus production-code architecture scan | COMPANION GATE; EXECUTION PENDING |

## Generator hard gates

Generation is acceptable only when all of the following hold:

1. every `$registryBlueId`, `$eventBlueId`, `$eventValue`, and
   `$documentBlueId` macro is resolved from checked-in exact sources;
2. final documents and managed occurrences are produced by the ordinary
   Language identity and Contracts component-finalization kernels;
3. every source-level expectation is verified against an actual
   `admitClosureWithLifecycleQueue` result;
4. generated fixtures contain complete exact expected results and no source
   macro remains;
5. FL-ADM-06 parity and FL-ADM-08 retry equality are checked across their
   generated cases;
6. two clean generation directories are byte-for-byte identical;
7. manifests, vector coverage, inventory/report constants, fixture package,
   registry reverse binding, and release identities are regenerated together;
8. no existing `process-closure` semantic state, route order, gas total,
   checkpoint behavior, or expected business result changes.
