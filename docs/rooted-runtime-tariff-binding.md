# Hosted runtime labels in the rooted checker

The checker table previously described Contracts' processor/semantic labels and
standalone BEX labels. The actual Contracts result uses `runtime.*` for hosted
BEX and Coordination counters. Consequently the literal counter's successful
665-gas trace was rejected as UNKNOWN_COUNTER by the original table.

The correction preserves all 86 original entries and adds exactly 44:
30 BEX counters under their hosted runtime namespace and 14 Coordination
channel/workflow counters from the unchanged governing schedule. Every amount,
quantity, ordering and weight remains intact. The full original contextual
runtime trace remains in each transcript alongside the five-field checker view.

All three exact source manifests are packaged and hash-bound. Before execution,
`verified_tariff_weights` derives the entire expected table and rejects missing,
extra, colliding or changed entries. Source-byte mutation also fails its hash.
An unreviewed future counter cannot pass simply by adding a table row.

The Coordination source manifest matches baseline commit
44aa25b47a72d70ea3ce5e575b3a28def7559501 byte for byte. Language and BEX source
hashes match the original handoff's pinned tariff sources. The governing Contracts
composition rule already includes registered runtime counters; no gas model,
tariff, processor behavior or numeric allowance changes here.

Evidence in the MyOS campaign:

- `evidence/rooted-production-counter-first.json`: original UNKNOWN_COUNTER failure.
- `evidence/rooted-runtime-tariff-binding-review/`: exact identities, isolated
  proposal, unchanged checker tests, actual traces and mutation negatives.
- `evidence/rooted-production-first-three.json`: RUN001/002/003 pass all seven
  literal variants on application1088f02b314741913f0205cba4bde858937485b4f38c384cb21c304d7d9ea331.

33 hardened checker tests, 64 Iteration2 model checks and 18 original harness
checks pass. The four new hardening tests cover exact derivation, an added label,
a changed weight and modified source bytes. Final 34-obligation and RC/product
acceptance remain required; this evidence has no release-readiness claim.
