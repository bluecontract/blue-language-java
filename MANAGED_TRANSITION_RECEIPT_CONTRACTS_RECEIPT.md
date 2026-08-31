# Managed transition receipt Contracts checkpoint and cyclic-proof addendum

Created: 2026-08-26T19:31:09Z
Updated: 2026-08-27T06:09:01Z

## Current cyclic managed-revision proof checkpoint

**IMPLEMENTATION, PORTABLE PACKAGE, API, AND DOCUMENTATION PASS; FRESH
IMMUTABLE STAGE PENDING.** Starting from commit
`fc9c59bcf45ae63b00fb3021f1be85373dd4282b` (tree
`5b91db327946299717c6f5b739bb129d30a6b08b`), Contracts now accepts a
managed-revision successor whose exact state is a cyclic member
`MASTER#index`. The additive invocation evidence is one defensive complete
`CyclicSetProof`; it is required exactly for a cyclic successor and forbidden
for an ordinary successor. Old constructors and their identities are
unchanged.

The verifier independently authenticates the proof through a verifying
cyclic-aware provider, recomputes the body, master, and suffix, and requires
the resolved member body to equal `afterDocument`. Admission retains the
ordinary resolved-body charge and additionally meters the historical proof
with the same preliminary/canonical/master identity formulas. It creates no
tentative component, component owner, component receipt, or
`cyclicMemberFinalized` charge.

This addendum describes a dirty, uncommitted review candidate. It deliberately
does not assign a new source commit/tree or claim a fresh rc.23 artifact. The
previous invocation-owned rc.23 stage at
`/private/tmp/managed-receipts-rc23-jdk21.YtFqVK/repository` (manifest SHA-256
`f49c4333ad0e3d6f795872bc6dd705d2e0e54dd05dc2516a2e7c663a8af20a95`)
is valid predecessor evidence for commit `fc9c59b`, but is superseded for this
new seam. A fresh immutable non-overwriting stage remains required.

### Cyclic-proof evidence

| Evidence | Result |
| --- | --- |
| Focused Contracts tests | PASS — 16 tests, 0 failed/skipped |
| Proof-aware conformance/facade + release-identity tests | PASS — 5 tests, 0 failed/skipped |
| Generated-pin report test | PASS — 8 tests, 0 failed/skipped |
| Schema validation of derived valid/missing/extra-proof envelopes | PASS — 3 tests |
| Release conformance | PASS — 429/429, 0 failed/skipped |
| API baseline / semantic migration | PASS — 5 additive descriptors; distribution total 4,750 |
| Javadocs and generated references | PASS |
| `git diff --check` | PASS |

The proof-bearing conformance test derives its envelope from the released
packaged `C-CLO-23` cyclic managed-transition evidence, recomputes cause and
invocation identities, and executes through the public facade. It freezes
total gas `477`, historical proof-admission gas `53`, and `17` proof trace
entries; missing proof and tampered proof/body/master/suffix fail. A separate
Draft 2020-12 schema test proves the same derived cyclic envelope valid and
the missing-proof and acyclic-extra-proof variants invalid. No claim is made
that this derived envelope is an additional manifest fixture.

### Deterministic portable package and generated pins

Two independent 363-file candidate packages are byte-identical. Their ordered
file-inventory digest is
`3060fe1a82821e795758284067e52a80a0f52b6b04c13ea382b0088b6b178244`.
The checked-in generator then published and revalidated that exact package.

| Binding | Old | New |
| --- | --- | --- |
| Contracts specification SHA-256 | `435a4bb9c63301006e115b51f291b94bba3c434a46c095244ed28deaacfbdbbc` | `0653dbbfc3d8b8ec1de5bd5c1d4f50680d0ce490df899bc2f254969fac3ba0bc` |
| Fixture package identity | `sha256:6030839a94f98161dc35dee7665ed2a87d8b109710235990b695ccf2834094c8` | `sha256:afaceefb48845495259962dea7202105c90c7396b7dee0aef483fb00b0438b52` |
| Contracts release identity | `sha256:0ebc325de03279aa580c6149f1d13a3ce4910918b3b285b241557ee811f2ebbe` | `sha256:977a5e7fa05c09363bb4ba16ce9fed765e306e7040c5229e405ccbbebe2dabfc` |

Candidate A/B release-manifest SHA-256 is
`f84eaeec67a23a50c71e15cdfe32d72e5fdbd3c14c6d5a9ef201afdb6f6a06f3`;
fixture-manifest SHA-256 is
`fac5bf5a73953c60e26af7ad03b80bb391e52dddef35c203e70a007bc156217c`.
Exactly the four previously authorized Java pin files were mechanically
rebound. Fixture counts, business outcomes, work/event ordering, and the gas
schedule did not change. `changed-files.sha256` was intentionally not
regenerated in this addendum.

## Historical source checkpoint (superseded by the addendum above)

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

## Pending fresh immutable stage

The coordinate remains `3.1.0-rc.23`; no coordinate was published or
overwritten. Root must commit the reviewed candidate, run the complete clean
JDK 17/JDK 21/release gates, and create a new invocation-owned immutable stage
whose manifest binds that exact commit. The final receipt must record its
repository path, complete artifact manifest/checksum, remote-fallback
rejection, Maven Local non-use, and packaged-artifact smoke. Those current-seam
values remain deliberately `null`/pending in the JSON receipt until observed.
