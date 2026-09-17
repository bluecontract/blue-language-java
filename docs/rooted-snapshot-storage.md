# Lossless physical storage of resolver snapshots

## Problem and example

A worker resolves a `PaymentInstruction` whose authored type is a BlueId reference.
Its snapshot retains Source, Canonical and Resolved lanes, the exact type evidence,
and verified-reference provenance. A fresh worker must retain that evidence without
re-running resolution. Constructing `new ResolvedSnapshot(canonical, resolved, id)`
preserves the bodies and BlueId but deliberately does not reconstruct the evidence:
an effective-type identity lookup that previously succeeded now fails closed.

`ResolvedSnapshotStorageCodecTest.bodyPairReconstructionCannotReplaceRetainedResolverEvidence`
demonstrates both the body-only limitation and the lossless storage alternative.
This is not a defect in the existing public constructor; it must not invent evidence.

## Minimal change

Language owns three bounded physical codecs: exact mutable Node/Schema fields,
FrozenNode construction modes and sharing, and the complete ResolvedSnapshot.
Package-private restore helpers preserve complete/incomplete type coverage,
ambiguous type buckets, inline/reference origins and independently retained
verified-reference evidence. Existing processing, identity and matching algorithms
are unchanged. Derived hashes and path indexes rebuild lazily.

The branch starts at `origin/next` commit
`806536457fd2ff284159fe65439973aa0f02ca4f` (RC25). Its production sources before this
change match the qualified `7ec0fdaafff41ad8e41387e7e5647d5a800d807c` artifact.
Only physical codecs and package-local export/restore hooks are selectively reused
from the earlier experiment. No runtime body-map API, execution policy, old closure
semantics or additional public fingerprint API is ported.

## Authority and failure boundaries

The host must authenticate the immutable storage reference before decoding.
Checksums and canonical framing detect corruption; they are not evidence that an
untrusted producer executed or resolved a document correctly. Decoding calls no
provider and executes no workflow. Custom non-exportable type lookups fail closed.
Byte/traversal-depth limits are operational storage bounds, never semantic gas
outcomes or graph-depth limits. Previously encoded frozen subgraphs use one
back-reference token without another traversal; a deeper shared graph can therefore
fit within a shallow physical traversal bound. A focused control verifies this
distinction and rejects the same deep chain when it actually requires traversal.
All codec failures abort the host attempt; they do not advance document history.

The frozen format is version 2 because it deliberately omits the old experimental
additional fingerprint field. There is no migration requirement for unpublished
POC artifacts. Java object serialization and arbitrary class loading are not used.
Programmatic host-specific Java scalars such as enums are not supported by this
physical transport even where Language accepts them as values. Retention fails
closed without mutating the snapshot. YAML/JSON scenario values do not need this
extension; an explicit allowlisted host-type transport would be separate scope.

## Alternatives rejected

- Body-only reconstruction: loses evidence even when BlueIds match.
- Resolve again on reopen: adds cold-provider work and does not restore the exact
  issued evidence independently of provider availability.
- Host reflection/private-field serialization: couples the host to Language
  internals and bypasses the library's validation boundaries.

## Verification scope

11 September 2026 final focused gate: **25/25 passed**, zero skips/errors, plus Javadoc.
Owners: resolver-snapshot storage (6), exact node/frozen storage (7), existing
canonical type evidence (4), existing frozen type matching parity (8).
The final gate used Java 21, offline Gradle, one worker, and unchanged sources
throughout the run. It completed in 21 seconds. No MyOS/library candidate pin has
been changed by this component gate.

The focused gate covers a genuine resolver result, source/type/provenance parity,
deferred and canonical-backed distinctions, ambiguous type lookup, mixed frozen
construction modes, previous-anchor context, all Node/Schema fields, exact scalar
representations, corruption and operational bounds. Existing type-identity and
frozen-matching owners are run alongside it. This codec gate is not SDK runtime
recovery or PostgreSQL application E2E; those must consume the same retained bytes
at a real pending-work boundary.
