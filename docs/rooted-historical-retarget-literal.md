# Historical retargeting to the saved source

RCP-RUN-013 now has 21 concrete SDK completions. P first observes B at 100.
C independently publishes revisions at 110, 210 and 500. At 250, P replaces B
with the actual saved initialized C0 reference. Two separate historical calls
must consume exactly C1 and C2, with the full source receipts and exact
predecessors checked. C3 at 500 remains a later LIVE input.

B is then independently processed through its old 100 entry and a new 600 entry.
Neither changes P's [1, 1, 2] history of observations. P next selects C500 and
records exactly one additional value, 3, then has no eligible work. This also
checks that the retired B600 cannot follow as a spurious delivery. Complete
records survive retained-store restart. Independent C remains at revision 3;
historical attachment never rewrites or re-emits its source receipts.

The literal passed on JAR
3b9422207ecb6008c3c3c6e33cb12574bd41ff6b457d3a3ebe52a9aa7ec85b44.
Evidence: rooted-production-retarget-saved-c0-first.json. It reuses the existing
strict multiple-history oracle and its negatives. The supplied original recipe
is preserved byte-for-byte with its hash. No runtime behavior or tariff changed.
