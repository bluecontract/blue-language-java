# Full-lifecycle post-refactor semantic-baseline delta

## Result

The controlled post-refactor capture changed exactly three scalar JSON
Pointers from the approved full-lifecycle baseline. The approved baseline had
SHA-256 `f537b8d712072163652aa2de8ad049d905f31a9a77ac4591990a5e6b5d8db61e`;
the captured baseline has SHA-256
`e75ad45f0721af593100a4db85ac16de4cc37ba799a8ca6ddef9fc8c6cd900d7`.
No fourth field changed.

The candidate was generated only by the repository's
`semanticBaselineCapture` task from clean semantic implementation commit
`3ea346d13dba31e65e32827972bcc501776fa49f`, with
`SOURCE_DATE_EPOCH=1787567817`. Commit
`5357b87bc4b37d6f8306797b57341e2c5905b258` contains only the controlled
baseline rebind.

## Exact changes

| JSON Pointer | Classification | Old | New | Reason |
| --- | --- | --- | --- | --- |
| `/source/commit` | source provenance | `2f7c3754d51dddbd17ff433026dc64e07f75451c` | `3ea346d13dba31e65e32827972bcc501776fa49f` | Bind the semantic-neutral exporter decomposition commit. |
| `/source/sourceInputIdentity` | source provenance | `sha256:10cf588b8d6905b4019c094e109bde3d5cc15e439467ceaa748a95e37926327e` | `sha256:313ced19ab3f8a32052f15931ae7d63fed705858af656934fdc676d7e8442aa7` | Bind the exact reviewed source closure at the decomposition commit. |
| `/artifacts/sourceRelease` | derived release artifact provenance | `sha256:17f59ef2877f80f77fd56f90b0a0bd1e43efb5b89bfbba1658a22054838ddf47` | `sha256:522543f2851ad1405bcce6fabe48824b47ece771ed07b6b99452cba6a26d83b9` | The source archive contains the semantic-neutral split and architecture catalog; executable artifacts are unchanged. |

The exact machine-readable delta is in
`full-lifecycle-semantic-baseline-delta.json`.

## Subsequent intentional scenario delta

The three-field result above remains the exact record of the earlier
semantic-neutral exporter refactor. The later Option 2 contract-evolution
round intentionally changes one existing full-lifecycle scenario and records
it separately from those baseline hashes:

| Scenario | Old result | New result | Reason |
| --- | --- | --- | --- |
| `FL-ADM-10` | `Complete` with `SubscriptionSurfaceInvalid` | `NeedsResources` with one `MANAGED_OCCURRENCE_EVIDENCE` demand | The initialization patch creates a managed Process Embedded occurrence for which no occurrence evidence is supplied. Typed noncommitting resource discovery is now authoritative and suspends before the later subscription-surface rejection. |

This is an intentional semantic correction, not an expectation relaxation.
The suspended attempt exposes no partial process result, gas total, trace,
checkpoint, or public event. Its legacy exact-node projection remains the
empty list (`requiredBlueIds: []`), preserving compatibility while the typed
demand carries the occurrence requirement.

## Protected equality proofs

All semantic and release identities below are exactly equal before and after
the capture:

- Language specification:
  `01b038b64e3f0a9a11f3f70d544a63ff78a01d5169f1a03f8b8629cf73645a7d`.
- Contracts specification:
  `389746c3faddebde4a4958cce0037ce2ec3a64a67c854053f0f6fa209a105e18`.
- Contracts fixture package:
  `sha256:3bb21b5df6eb87b578e9647f11d094aff2cf45c56b3b7050f8d854147bdb3e3d`.
- Contracts release:
  `sha256:5917b16adfde2ed6bb21bac74c40a1b44526d7c9ddb3faaaf5fbe8a13aae3b1c`.
- Release package:
  `sha256:0268c0adc8badf0d1ab5cdef4a323117b82253a3695f9125af750437a23014b6`.
- Gas fixture count: `71`; exact gas-fixture subtree canonical SHA-256:
  `583a1346a55261d80accf1c806f4583f5c1b71c102a871ac33ccc81cdbed15a4`.
- Locality subtree canonical SHA-256:
  `3282d9a5a3dfea279f6b95438dd555b901d32ff7d8dc840a58116c61da682f87`.
- Preserved public-API subtree canonical SHA-256:
  `c0815bef82317013f9383717941a9ff3c041a63039eb2728bbd53c8209dda4fc`.
- Runtime JAR:
  `sha256:0de1584be094515ddd27938819464dc024a993c7eb06e4145cac129ad5bbfed0`.
- Sources JAR:
  `sha256:68d1069c56f754c2e76f208a4126a967533cc91059062c2e86b70e098f33a518`.
- Javadoc JAR:
  `sha256:7634fd796a8daabca094755f2bd0c47ec3e19768acd33d68ced010e93c788e7f`.
- Frozen characterization: `2381` passed, `0` failed, `0` skipped.

No specification, fixture, gas tree, locality payload, API characterization,
runtime bytecode, sources JAR, test expectation, quality threshold, allowlist,
or semantic result changed.

## Receipt-layer distinction

The final gate campaign ran on clean baseline-refresh commit `5357b87` while
pinning semantic provenance to `3ea346d`. Consequently, the current clean
source closure includes the refreshed baseline and records source-input
identity `sha256:166ca281de37fcedc3788d5c3c6ad2b700dd622d1fcca52e92afb8d5dadb9a8b`
and source-release identity
`sha256:175c7844b050d5e06720b0c4da9c3b688f547472331b8a88b8b535409c434c42`.
The semantic verifier intentionally keeps the characterization identities
above separate from this later evidence layer and passed with
`verified=true`.
