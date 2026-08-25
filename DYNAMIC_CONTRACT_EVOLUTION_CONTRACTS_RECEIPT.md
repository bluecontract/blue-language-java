# Dynamic contract evolution Contracts checkpoint receipt

Created: 2026-08-25T15:33:10Z

## Outcome

**PASS.** The deterministic Contracts package, fixture delta, reviewed
implementation commits, exact change manifest, full gate campaign, and
immutable staged-repository evidence are bound to
`a74dfb310f71073493803d7817b8b09ebfbed211`. This checkpoint claims Contracts
implementation conformance and authorizes the downstream Coordination phase.

This is a local Contracts checkpoint. It is not a publication, deployment, or
production-readiness claim.

## Exact provenance

| Role | Commit |
| --- | --- |
| Accepted base | 03db5ee45f96698a45a3f5556dcc1ef6222d8e6f |
| Recovery classification | d5484d870f30048009fdbcceb4365f56c235fb7f |
| Deterministic contract evolution | 15f23e65fabfb9942f712389f265ca31144e7516 |
| Typed noncommitting demands | c98230a45ce59a673a7152c9aa49f9795b917e2c |
| Dynamic graph conformance | 1714cc7b6343619112caff5356f0b89363e20582 |
| Normative specification | 37968eedc1d259ecf0c4274bd23125bc2f86f5c2 |
| Release evidence rebind | 914bc4906208735eceeee4e9744f4e28896b2874 |
| Semantic source under test | a74dfb310f71073493803d7817b8b09ebfbed211 |

Branch: feat/dynamic-contract-evolution-resume

The receipt-only commit containing this file is not the semantic source under
test and must have a74dfb3 as its tested parent. The reproducible gate
environment is bound to GIT_COMMIT=a74dfb310f71073493803d7817b8b09ebfbed211
and SOURCE_DATE_EPOCH=1787669428.

## Normative and release identities

| Evidence | Identity |
| --- | --- |
| Contracts specification | sha256:8fa141d5babb21a0b5df064a1b715e3d57f868a9a087fc1fd20b686761375242 |
| Contracts fixtures | sha256:0d70b0399a61364774fe0509b18b89db27c4ce8bce27db2e5c238a8c6cd59b79 |
| Contracts release | sha256:32a5c3f8dfe99a421ca0d6862bc1f59bddcfb10e4762dcf3d8200b4726defad3 |
| Portable package | sha256:a55331488b488ab7236307711fc4e8390a63a897891e47c6ae424fe22b87b1ec |
| Portable package ZIP | sha256:11894c3580d1e5d5192f1e9bde758df76ff4f2966a8e793df0026dd083a17c6c |
| Validation output | sha256:08c0b1f777494968ed5649586316c68148f6c30e94e9d3b6d512ed0d1bbc9bce |
| Package manifest | sha256:634d5c69e1d47cbfbdc32624803a39f1ccd2419587c18d3f5ba8717011fbf44f |
| Package checksum manifest | sha256:ad2629596df74e195fb5bf05342cf0fb7d538456a2fdfde123533716703f1b79 |
| Oracle package | sha256:6c2ad2b484aa259e2b0a609172ac56882a0b1cb588209809cab979605b98af9d |

The release-gap audit is bound by
sha256:dee90eb347f67618c90fe741d721c66626d9b0346f8c8fecf14e6595106569c4
for DYNAMIC_CONTRACT_EVOLUTION_RELEASE_GAP_AUDIT.md and
sha256:8b93a0a119547e0205e66b7d8045abed6cf6eca28959abff1136751c22f628bf
for dynamic-contract-evolution-release-gap-audit.json.

## Deterministic portable package

Two independent complete generations passed:

| Evidence | Generation A | Generation B |
| --- | --- | --- |
| Package root | /private/tmp/dce-contracts-final-reviewed-package-a.thIeIK/blue-contracts-and-processor-1.0 | /private/tmp/dce-contracts-final-reviewed-package-b.iWuzRr/blue-contracts-and-processor-1.0 |
| ZIP | /private/tmp/dce-contracts-final-reviewed-package-a.thIeIK/blue-contracts-and-processor-1.0.zip | /private/tmp/dce-contracts-final-reviewed-package-b.iWuzRr/blue-contracts-and-processor-1.0.zip |
| Complete tree | 463 files | 463 files |
| Result | PASS | PASS |

The two trees and ZIPs are byte-identical, the extracted A ZIP has an empty
recursive diff from A, all 461 checksum entries pass, and the package manifest
records 460 payload files. The validator reports PACKAGE_VALID and
SEMANTIC_REFERENCE_VALID.

Package inventory:

| Class | Count |
| --- | ---: |
| Ordinary fixtures | 183 |
| Closure fixtures | 93 |
| Total fixtures | 276 |
| Ordinary vectors | 114 |
| Closure vectors | 54 |
| Total vectors | 168 |
| Java template source files | 58 |
| BlueIds checked | 14,489 |
| Direct channels checked | 36 |
| DocumentIds checked | 410 |
| Occurrences checked | 768 |
| Oracle files / stages | 42 / 121 |
| Runtime handlers checked | 125 |
| Managed revision sequence fixtures | 5 |
| Remove/re-add fixtures | 2 |

The complete 363-file conformance subtree is equal in both generations and to
the tracked resources. All 36 tools and the Contracts specification are equal
to the worktree. The informative reference, README, and 58 Java templates are
bound to clean canonical source commit
5dc8096276652156e248c9c018a0850fcd8dbdbb, tree
73601034296006576ca8cfae5a1f993cefda529e.

Python evidence is complete: tool compilation passes, the demand and rollback
marker self-checks pass, all 93 closure fixtures validate, and the 30 generator
and classifier tests pass. An independent adversarial audit rejected forged
cause, closure, generation, value, provider, present-null, demand-only
membership, and cloned-marker/full-spine evidence while accepting a valid
later same-document failure after an earlier marker.

The optional source archive was not supplied. Its declared expected identity
is 7be5116d8e7a64bccf471c11a93127d4924a36686e23bbbf634fc0713d6d33c9;
its absence is not a package-validation blocker.

## Existing-fixture delta

DYNAMIC_CONTRACT_EVOLUTION_FIXTURE_DELTA.md is preserved unchanged at
sha256:68b272de2adaa5ded64a9806743f09428e19b3bf894064640170b2c90602531c.
Its machine-readable sibling is
sha256:769cc7df68f47e8b717c4e81d870a3d45d555d78808d391b54f08376e2c19a30.

| Measure | Value |
| --- | ---: |
| Before files | 333 |
| After files | 363 |
| Changed files | 102 |
| Unexpected files | 0 |
| Actual semantic state/result changes | 43 |
| Invocation identity rebinds | 63 |
| Specification identity rebinds | 65 |
| Work-event trace identity rebinds | 55 |
| Fixture-byte-only formatting | 0 |

The baseline tree is 69716bc726117a8587b16ac0757b1a5468b80355 and the
baseline fixture package is
sha256:3bb21b5df6eb87b578e9647f11d094aff2cf45c56b3b7050f8d854147bdb3e3d.

## Focused behavior inventory

The focused run passed 67 tests across these 15 suites:

| Suite | Tests | Fresh a74dfb3 result |
| --- | ---: | --- |
| ContractEvolutionClosureAcceptanceTest | 9 | PASS |
| ClosureResourceDemandTest | 10 | PASS |
| DifferentLineageRetargetReceiptTest | 3 | PASS |
| ManagedOccurrenceDemandDiscoveryTest | 9 | PASS |
| ProcessEmbeddedSurfaceReconcilerTest | 7 | PASS |
| ContractEvolutionFixtureFamilyTest | 5 | PASS |
| ContractsSoundnessGeneralizationFixtureTest | 3 | PASS |
| ApplicationContractOwnershipTest | 1 | PASS |
| ContractSurfaceProjectionIntegrationTest | 2 | PASS |
| ContractSurfaceReconcilerTest | 4 | PASS |
| DocumentProcessorContractSurfaceEvolutionTest | 2 | PASS |
| InheritedContractContributionEvolutionTest | 1 | PASS |
| ManagedProcessEmbeddedSurfaceProjectionTest | 3 | PASS |
| ProcessingResultCoordinatorContractSurfaceTest | 5 | PASS |
| ProcessorOwnedContractsReplacementTest | 3 | PASS |

## Final gate campaign

The deterministic package generation and all final gates are PASS against
`a74dfb3`. The exclusion-free clean build executed 2,950 tests with zero
failures across the root and module suites.

| Gate | Result |
| --- | --- |
| Focused C-EVO suites | PASS — 67 tests |
| blue-contracts-core full tests | PASS — 294 tests |
| Root processor tests | PASS — 2,423 tests |
| blue-conformance full tests | PASS — 161 tests |
| releaseConformanceTest | PASS |
| verifySemanticApiMigration | PASS — 337 approved incompatible, 482 additive |
| verifyFinalApiBaseline | PASS |
| moduleApiVerify | PASS |
| moduleArchiveVerify | PASS |
| documentationVerify | PASS |
| verifyPublishedRepository | PASS |
| publishedArtifactSmoke | PASS |
| Exclusion-free clean build | PASS |
| finalQualityVerify | PASS |
| rcVerify | PASS |
| Java 8 class-major verification | PASS — major 52 |
| git diff --check and final clean status | PASS |

The receipt JSON records exact SHA-256 values for the fresh final-quality,
release, semantic-baseline, publication, conformance, documentation, and
fragmented-processing reports. The clean-build marker is bound to source input
identity `sha256:9f0fa0536c196e46ba4e83db988adee11f6151890f44a484c6a202703f8164fe`.

## Immutable staged repository

The immutable stage is PASS at
`/private/tmp/dce-contracts-stage-a74dfb3.3oI5E8/repository`. It uses schema
`blue-staged-dependency-repository/1.0`, group `blue.language`, version
`3.1.0-rc.21`, source commit `a74dfb3`, seven coordinates, 28 artifact records,
and 58 repository files. Each coordinate contains POM, runtime, sources, and
Javadoc payloads plus checksum sidecars.

The artifact-manifest SHA-256 is
`454fbeec1767e73a2025d7435a512ad8da423e0f8de6afc8d28947257fe2cf47` and its
sidecar SHA-256 is
`add318f32becf62f0eab57a3f36df17c848dce52532f87c828aae390c9f77f84`.
The complete sorted repository evidence hash was
`529184972aa44c3ceb78f497e6f1400bcba247f32f8f202aef4b2278145525c0`
before and after the negative overwrite test. Java class major 52,
`verifyPublishedRepository`, and independent `publishedArtifactSmoke` passed.
An identical second assembly caused no mutation; a different source commit at
the same release coordinate was rejected with `Immutable staged repository
already exists with different bytes`. Maven Local and remote fallback were not
used. All 28 payload SHA-256 values are recorded in the JSON receipt.

## Change and ownership boundary

changed-files.sha256 and
dynamic-contract-evolution-changed-files.sha256 are byte-identical:

| Fact | Value |
| --- | --- |
| Baseline | 03db5ee45f96698a45a3f5556dcc1ef6222d8e6f |
| Tested commit | a74dfb310f71073493803d7817b8b09ebfbed211 |
| Entries | 310 |
| Deleted files | 0 |
| Production Java files | 94 |
| Test Java files | 39 |
| Manifest SHA-256 | 1d7f5b3411399a3a395fbea947f14406292c8dfb54b8202477a7b94d642884e8 |
| Frozen model/core/mapping production files | 0 |

Each line hashes the exact a74dfb3 Git blob. The manifests exclude both receipt
files and both checksum aliases, so they contain no self-reference.

## Truthful scope and checkpoint flags

| Claim | Value |
| --- | --- |
| Blue Language value/model/core/mapping changed | false |
| Blue Contracts processor changed | true |
| BEX changed | false |
| Coordination changed | false |
| MyOS changed | false |
| Application contract mutation supported | true |
| Whole Process Embedded mutation supported | true |
| Generalization after contract mutation supported | true |
| Active cross-lineage rebind supported | true |
| Typed resource demands supported | true |
| Same-invocation remove/re-add supported | false |
| Implementation conformance claimed | true |
| Release checkpoint complete | true |
| Phase D allowed | true |
| Production ready | false |
| Published | false |
| Deployed | false |
| Maven Local used as release evidence | false |

Out of scope are historical retained-epoch catch-up, Timeline
import/completeness, Mandates, and production multi-node durability.
