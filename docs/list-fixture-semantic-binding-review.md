# Exact List fixture binding correction

The tracked semantic baseline still binds the Language fixture package from before commit `43c119bb3c9451e8c52dccd150fde9f6cf1bce51`. That commit corrected the Source/canonical input boundary for positional Lists and added an explicit missing-prefix negative. The current unfiltered conformance report passes all 480 fixtures; the unchanged semantic baseline gate fails before semantic comparison on the stale package identity.

This proposal changes exactly two JSON values in `api/semantic-baseline-1.0.json`:

- `/release/packageIdentity`: `sha256:0b94e3ad61b650abb4b249eeb51d910ef93ec25133d3717cf90714c07032148a` → `sha256:b2477354910cde66b7c06b51956df98f0873d57033a5e7fa8e5e301096bd5e64`.
- `/packages/languageFixtures`: `sha256:2b305ecf1fabcdd7990868e8453d7d77647bef4131de64064f6f6d4e42a6e60e` → `sha256:f323e169fc2e18d918686cf5cb8843dd6b8ce7e81fd9484e2921ce554b004695`.

No specification text, fixture, runtime behavior, test count, gas expectation, locality payload, historical source/artifact provenance, API ledger or checker rule changes. The containing release identity follows from its exact package manifest, not from accepting a file count.

The complete inventories include every path, byte count and SHA-256, including the manifest itself:

- baseline: commit `35de814a0add8ddb24992a8179d08539490379ec`, 194 files; canonical complete inventory SHA-256 `ab459fc8417e26cb5f14653467a8e1c64ec5e6256ad1241ca7ca327d688b2845`.
- candidate: commit `aa71917e8dba9869957c6af97502120dd61d90ba`, 195 files; canonical complete inventory SHA-256 `6d63e2f52d6839822c95abd12092a4b38a23c3dee0290cd3302d67b0911acb34`.

`inventory-delta.json` and `fixture-correction.patch` show all six changed paths. The positive canonical-overlay example now supplies an authenticated inherited prefix before `$pos: 0`; the original prefix-free Source is preserved as a rejection. The reorder fixture now explicitly distinguishes rejected canonical `[B,A]`, Source append producing `[A,B,B,A]`, and the original rejected fixed-element replacement. The harness selects the declared input boundary without fixture-name special cases; support coverage and hashes reflect that added negative.

Independent checks rederive all seven package/release identities and hash 534 manifest-listed entries. All 71 gas fixtures retain exact expected objects, and all three locality payloads retain exact content and identities. `candidate.json` differs from `original.json` only at the two reviewed pointers; it preserves every other byte.

The compiled, unchanged SemanticBaselineVerifierCli accepts the proposed temporary baseline and rejects all four deliberate mutations: specification identity, fixture package identity, a gas oracle, and a locality payload. `probe-results.json` records the exact executable/classpath/arguments and exit codes. This focused proposal does not establish a final clean-source gate pass or release readiness. The API-ledger and previous Contracts classifier approvals do not themselves authorize this change.

Approved by the user on 2026-09-06: “Approve this exact two-field binding correction”. The historical source inventories and baseline provenance above remain historical; this correction adds no future inventory acceptance rule. Full final gates on the resulting commit remain required.
