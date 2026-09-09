# Explicit authored-initial prerequisite in RUN-023

The real SDK probe on Coordination341325d and Language6997bbc proves the supplied literal plan is missing one setup invocation. B attaches the unchanged saved A.initialBlueId, which selects the authored pre-initialization cursor-1. The first LIVE connect operation leaves that occurrence pending. Contracts§2.3 explicitly requires the separate authenticated-1→0 revision cause before activation. This does not rerun source initialization.

The maintained plan now inserts setup-004-genesis before setup-005's live-component assertion. Its required positions are exactly[-1,0]. All existing step IDs, saved inputs, finite-operation and split oracles, gas comparisons, identity-envelope checks, and restart requirements remain unchanged. No semantic clause, tariff, historic fixture or hardened checker rule was changed. The original handoff package is preserved outside this repository.

Old planSHA256: eac7e2716527f0cbc89b27fc511991373e843eedfa6239562d02766ed748009b
New planSHA256: 8581d084d37b1b68ae7db29b927e8bbfbe90dbf29f375e8f8f0b7cab3889c4ce

The SDK test preserves the input and verifies exactly one published A epoch-zero application, then executes both literal variants, compares full rooted identities/gas/receipts, splits and restarts. Its failed original prerequisite and final passing XML are retained in the MyOS campaign evidence. The new checker regressions require the additional completion in order; omitting it still fails. Production fixture status remains NOT_RUN until the actual adapter executes the complete corrected plan on the bound final artifact.
