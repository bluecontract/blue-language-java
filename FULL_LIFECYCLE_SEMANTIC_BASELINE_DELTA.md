# Full-lifecycle semantic-baseline delta

## Result

Two fresh external captures produced byte-identical candidates with SHA-256
`f537b8d712072163652aa2de8ad049d905f31a9a77ac4591990a5e6b5d8db61e`.
The candidate changes exactly nine JSON fields from the tracked baseline. No
tenth field changes.

The capture evidence was regenerated at semantic implementation commit
`2f7c3754d51dddbd17ff433026dc64e07f75451c` with
`SOURCE_DATE_EPOCH=1787528582`. The official `clean build` passed 136 tasks in
10m27s, and the release/conformance report chain passed 104 tasks in 3m.

## Exact changes

| JSON Pointer | Classification | Reason |
| --- | --- | --- |
| `/source/commit` | source provenance | Bind the clean full-lifecycle semantic implementation commit. |
| `/source/sourceInputIdentity` | source provenance | Bind its exact reviewed source closure. |
| `/specifications/contractsSha256` | Contracts specification identity | Bind the full-lifecycle normative Contracts specification. |
| `/release/contractsReleaseIdentity` | Contracts release identity | Bind the regenerated deterministic Contracts release. |
| `/packages/contractsFixtures` | Contracts fixture/package identity | Bind the 247-fixture package including 13 full-lifecycle cases. |
| `/packages/contractsRelease` | Contracts release identity | Match the deterministic Contracts release identity. |
| `/gas/oraclePackageIdentity` | Contracts fixture/package identity | Rebind the containing package; all 71 gas trees remain equal. |
| `/artifacts/javadocJar` | derived release artifact provenance | Expected documentation provenance change from reviewed Contracts conformance API/package ownership updates. |
| `/artifacts/sourceRelease` | derived release artifact provenance | Expected source-release provenance change from reviewed source, spec, fixtures, tooling and reports. |

The exact old/new values and reasons are recorded in
`full-lifecycle-semantic-baseline-delta.json`.

## Protected equality proofs

The following values are exactly equal before and after capture:

- Language specification SHA-256:
  `01b038b64e3f0a9a11f3f70d544a63ff78a01d5169f1a03f8b8629cf73645a7d`.
- Gas fixture count: `71`.
- All gas expected trees, canonical JSON SHA-256:
  `583a1346a55261d80accf1c806f4583f5c1b71c102a871ac33ccc81cdbed15a4`.
- Locality evidence, canonical JSON SHA-256:
  `3282d9a5a3dfea279f6b95438dd555b901d32ff7d8dc840a58116c61da682f87`.
- Preserved public API, canonical JSON SHA-256:
  `c0815bef82317013f9383717941a9ff3c041a63039eb2728bbd53c8209dda4fc`.
- Runtime JAR:
  `sha256:0de1584be094515ddd27938819464dc024a993c7eb06e4145cac129ad5bbfed0`.
- Sources JAR:
  `sha256:68d1069c56f754c2e76f208a4126a967533cc91059062c2e86b70e098f33a518`.
- Test failures: `0`.
- Test skips: `0`.

The Javadoc JAR changes because the reviewed conformance API and package
ownership are documented. The source-release archive changes because it
contains the reviewed full-lifecycle source, specification, fixtures, tooling,
and reports. Neither change alters Language value semantics or runtime JAR
content.
