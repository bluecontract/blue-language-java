# Implementation handoff checker patch — H1/H2/H3

9 September 2026. This is a test-driver correction on Iteration 2, not another normative design revision.
All files under `specifications/`, identity constructors/vectors, gas tariff weights and historical fixtures remain byte-for-byte unchanged.

- H1: ordered literal plan steps expand repeats in ascending index order. Exactly one semantic completion per `(stepId, repeatIndex)`; incomplete retry/call records do not count as completions. Reversed completions, duplicates, malformed repeats and malformed completion flags reject.
- H2: every successful at/above-budget trace must equal the entire uncached calibration trace, not merely its total or peer variants. Below-budget prefix/rejected-charge validation is retained.
- H3: RUN-001/002 explicitly require the source's `RCP2/Tick` event, origin alias S and ordinal 0. The aliases are fixture roles mapped by a reviewed adapter to actual verified identities. Exact event/type bodies must still come from real SDK/HTTP evidence; these field anchors are not a cryptographic type verifier.

The original 64 tests remain, with their synthetic positive event changed from generic `tick` to the exact kind authored by the literal source. A separate negative regression suite is added. Synthetic records never receive production credit.

Original package and review are preserved by the enclosing handoff. The enclosing `patches/checker-hardening.patch` and JSON record identify changed paths/hashes. This prepared copy has freshly calculated package manifests; old result files remain historical, not current production receipts.

Run `python3 -B tools/verify_package.py`, `python3 -B tools/test_harness.py`, `python3 -B tools/test_iteration2.py` and `python3 -B tools/test_checker_hardening.py`. Record new results outside this extracted immutable input tree.
