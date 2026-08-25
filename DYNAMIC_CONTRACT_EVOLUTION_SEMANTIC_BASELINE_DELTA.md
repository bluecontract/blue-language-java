# Dynamic contract evolution semantic-baseline delta

## Verdict

The tracked semantic baseline is stale for this reviewed Contracts round and
requires a controlled refresh. This is not a generic baseline reset. The
candidate changes exactly 19 logical JSON Pointers: 13 scalar values and six
ordered request arrays. Every change is classified in
`dynamic-contract-evolution-semantic-baseline-delta.json`; replacing those 19
values with one marker leaves byte-canonical protected projections with the
same SHA-256, `791cfddaad4abb35a03daf1a556e4d02c34051c02e60bba284bd26640476c6ac`.

The accepted baseline has SHA-256
`e75ad45f0721af593100a4db85ac16de4cc37ba799a8ca6ddef9fc8c6cd900d7`.
The controlled candidate has SHA-256
`c117aafc09319a336364e93bbbc2fa1caa9e60b57d8efc9a19774c23b2e4d92a`.
It was generated twice from clean semantic candidate
`a36d57f34366af8c070a8cdf5b76f5e365433954` with
`SOURCE_DATE_EPOCH=1787644014` by the repository's
`semanticBaselineCapture` task. The second capture ran in the independent
clean clone `/private/tmp/dce-semantic-repeat-a36d57f` after a successful
136-task `clean build`; its clean-build receipt has SHA-256
`59eac8a32b5f801995e12ae40f876ce1280949e7ec07354dbb6e7de0b055d517`.
Both captures produced byte-identical candidates with SHA-256 `c117aafc…`, so
the controlled candidate is eligible to bind.

## Exact change classes

| Class | Pointers | Review |
| --- | ---: | --- |
| Source provenance | 2 | Candidate commit and exact clean source closure. |
| Contracts specification/release/package binding | 5 | Normative Contracts spec, fixture corpus, direct release, and matching gas-oracle package provenance. |
| Monotonic test characterization | 3 | `2381` to `2423`; failed and skipped remain zero. |
| Bounded locality work order | 7 | One payload identity and six ordered requested-ID arrays. |
| Derived artifacts | 2 | Javadoc and source release only. |

There are no added or removed fields, no JSON type changes, and no unclassified
value changes.

## Locality review

The locality delta is a reviewed consequence of the complete entry/tentative
contract-surface capture in
`blue-contracts-core/src/main/java/blue/language/processor/ProcessingResultCoordinator.java`.
The processor now traverses the complete effective contract surface before
reconciling its immutable before/after evidence.

Six variants change only `primaryRequestedBlueIds`:

- B grows from 73 to 75 requests.
- D, F, G, and H each grow from 74 to 76 requests.
- E grows from 42 to 54 requests because its partial/cold path performs both
  complete surface captures.

The machine receipt records zero-based ordered move/insert scripts and the
exact before/after sequence hashes. Across all six variants there are no added
backend loads, no added backend bytes, no changed required or forbidden IDs,
no changed semantic demands, and no changed replay evidence. Every observation
field other than those six arrays has the same canonical SHA-256,
`b4ac3d4e795da84b57bd900654027a141610ab7b0e5ededea610f1dae51da7df`.

## Protected equality proofs

The following remain exact before and after capture:

- Language specification and package identity chain.
- Aggregate release identity
  `sha256:0268c0adc8badf0d1ab5cdef4a323117b82253a3695f9125af750437a23014b6`.
- Contracts registry and gas package identities.
- Gas fixture count `71` and every gas fixture body.
- Entire preserved public-API characterization.
- Runtime JAR and sources JAR.
- Test failure and skip counts (`0`, `0`).
- Locality source/test inventory, payloads 0 and 2, and every unchanged field
  in fragmented payload 1.

The full protected hashes, scalar old/new values, and exact ordered array edits
are in the machine-readable receipt. The historical full-lifecycle baseline
delta remains untouched.

## Gate policy

The candidate may be bound only after two independent clean captures are
byte-equal. After binding, the final rebind commit must rerun
`semanticBaselineVerify`, semantic API migration/final API gates, Contracts
conformance, deterministic package regeneration, immutable staging, full clean
build, `finalQualityVerify`, and `rcVerify`. A passing verification against a
newly captured oracle is not by itself approval.
