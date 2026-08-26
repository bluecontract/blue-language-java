# Managed transition receipt Contracts source checkpoint

Created: 2026-08-26T19:31:09Z

## Outcome

**SOURCE CHECKPOINT PASS; RELEASE REBIND PENDING.** The additive Contracts
processor implementation, focused coverage, normative source specification,
public API inventory, Javadocs, Java 8 bytecode, and reproducible module
archives are bound to clean semantic source commit
`889a3fcae1f76d00c27248a7b9f1538b5c2cdd8e` on
`feat/managed-epoch-receipts`.

This checkpoint is sufficient to assemble an invocation-owned immutable
compile stage for the downstream Coordination implementation. It is not the
final Contracts release checkpoint: the existing portable closure fixtures
still bind the Contracts 1.0 commit-companion identities and must be
mechanically rebound to the intentional 1.1 receipt-aggregate constructor.
No artifact was published or deployed, and Maven Local was not used.

## Exact source custody

| Role | Value |
| --- | --- |
| Published baseline coordinate | `blue.language:blue-contracts-core:3.1.0-rc.22` |
| Published baseline checkout | `cf8f4242b41e25d67606a5dfd58a7e5ba61f39bf` |
| Published baseline tree | `7ed4106215c83dc8beed6220b166020ded8db2d5` |
| Published runtime JAR SHA-256 | `7c5abb3c87273f640dcc9754d43c1a6f5f1fcb85605ed496d35c2514304031d6` |
| Published sources JAR SHA-256 | `d678b8f462933bc1358f8439c09bf756d3bea6bba2ba5fa9aa13b5b0394c5477` |
| Semantic source under test | `889a3fcae1f76d00c27248a7b9f1538b5c2cdd8e` |
| Semantic source tree | `e8ede9c85f87bfa09e7e44828faa9de1d36b0f9c` |
| Contracts specification SHA-256 | `637c5e94aee20c1f8a5bfec4df0b37e8b4209c6455f430239f49664727f909e8` |
| Public API inventory SHA-256 | `6176c288c89e2475b028da5afb356386962f1ecad88945538c09bd0750025313` |
| Provisional rc.23 module JAR SHA-256 | `6198aa771910633656ce31c73d573522fb336ef594768b8d69a4a8149fdba3fd` |

The published rc.22 sources were audited byte-for-byte against the baseline
checkout before implementation. The receipt-only commit that contains this
document is not the semantic source under test.

## Reviewable commit series

| Commit | Subject |
| --- | --- |
| `9ec3d11` | `feat(contracts): expose complete managed transition receipts` |
| `f4e0a8c` | `test(contracts): conform managed receipt and revision delivery` |
| `889a3fc` | `docs(contracts): specify managed transition receipts` |

## Implemented receipt surface

Contracts now returns one authenticated receipt for every changed managed
document and for every event-only Root transition whose BlueId is unchanged.
Each receipt binds:

- source invocation and canonical transition occurrence;
- stable managed `DocumentId`, original cause, and exact before/after BlueIds;
- the complete ordered duplicate-preserving Root event sequence, including
  events from non-public managed Roots;
- each event's receipt-local ordinal, source invocation occurrence ordinal and
  identity, exact event BlueId/value, source document, and source visibility;
- a deterministic partition of the complete admitted closure gas; and
- the aggregate receipt identity authenticated by the commit companion.

When the receipt sequence is non-empty, every admitted gas-trace row belongs to
exactly one receipt and the receipt gas sum equals `ClosureProcessResult.totalGas()`.
Rows attributed to a receipt-bearing document remain with that document;
invocation-owned rows and rows for non-receipt documents belong to the first
canonical receipt.

The managed-revision cause has an additive typed-receipt form. The processor
revalidates the exact named occurrence and source receipt, replaces the
containing reference, and delivers each retained source event through that
occurrence using the existing event queue, Handler routing, gas, finalization,
and cyclic closure machinery. It neither reruns source-local work nor
republishes the source event. Newly emitted parent reactions retain ordinary
public-output behavior.

Legacy public-event APIs, the Contracts 1.0 companion constructor, and the
state-only managed-revision constructor remain available for compatibility.
Rollback and runtime failure expose no managed-transition receipts.

## Identity constructors

| Evidence | Domain |
| --- | --- |
| Managed transition occurrence | `blue-contracts-managed-transition-occurrence/1.0` |
| Complete Root event sequence | `blue-contracts-managed-root-events/1.0` |
| Managed document transition receipt | `blue-contracts-managed-document-transition-receipt/1.0` |
| Ordered receipt aggregate | `blue-contracts-managed-document-transition-receipts/1.0` |
| Receipt-binding commit companion | `blue-contracts-platform-commit-companion/1.1` |
| Retained compatibility companion | `blue-contracts-platform-commit-companion/1.0` |

The canonical event identity projection binds `eventBlueId`; the receipt
constructor independently verifies that the complete defensive-copy event
value has exactly that BlueId. No concatenated identity or unverified
host-supplied digest is accepted.

## Gate evidence

| Gate | Result |
| --- | --- |
| Focused `FullLifecycleAdmissionTest` + `ManagedTransitionReceiptTest` | PASS — 31 tests |
| `:blue-contracts-core:test` | PASS — 304 tests, 0 failed/skipped |
| `:blue-contracts-core:check` | PASS — tests, Javadocs with `-Werror`, API baseline, module archives, archive replicas, package cycles |
| New public type bytecode | PASS — class major 52 (Java 8) |
| `git diff --check` | PASS |
| `changed-files.sha256` | PASS — 15/15 entries verified |
| `:blue-conformance:test` | EXPECTED RELEASE-EVIDENCE FAILURE — 162 tests, 53 stale companion-identity assertions |
| Release conformance / final quality / RC gates | PENDING conformance rebind and immutable stage |

The full conformance run did not expose a processor behavior failure. Its
observed failures compare released fixture `platformCommitCompanion` identities
from the 1.0 constructor with the new genuine 1.1 companion identities. That
is the expected evidence delta from authenticating the receipt aggregate, but
it remains a real red gate until the portable fixture/specification package and
constructor registry are regenerated and reviewed. This receipt therefore does
not claim a clean final release checkpoint.

## Change boundary and checksums

`changed-files.sha256` binds the exact 15 files changed between the published
baseline and semantic source commit. It excludes itself and both receipt files
to avoid self-reference.

| Evidence | SHA-256 |
| --- | --- |
| `changed-files.sha256` | `a06e9949e1705e65e5e26be7d2007ac4fa4d3cf996d214c53260ddbe3f366a7e` |
| `managed-transition-receipt-contracts-receipt.json` | `062e8e0750953f61002730b2c36693cbc8926521bc34ce512ecbdd7204cec85a` |

Truthful scope flags:

```text
blueLanguageValueModelChanged = false
blueLanguageCoreChanged = false
blueLanguageMappingChanged = false
blueIdSemanticsChanged = false
bexChanged = false
repositorySemanticsChanged = false
contractsProcessorChanged = true
coordinationChanged = false
myosChanged = false
published = false
deployed = false
mavenLocalUsed = false
phase1ImplementationComplete = true
phase1FinalReleaseCheckpointComplete = false
```

## Pending immutable stage

The expected next coordinate is `3.1.0-rc.23`, subject to the invocation-owned
stage proving that it is unused. The final addendum must record the immutable
repository path, complete artifact manifest and checksum, remote-fallback
rejection, Maven Local non-use, packaged-artifact smoke, and the post-rebind
release gates. Those values are deliberately `null`/pending in the JSON receipt
until independently observed.
