# Concrete multi-occurrence and interleaved-history obligations

RCP-RUN-006 and RCP-RUN-008 now have complete literal inputs and step sequences,
ported from Coordination's maintained RootedMultipleHistoricalOccurrencesTest.
The original supplied recipes are retained byte-for-byte in
`conformance/rooted-processing/provenance/recipe-inputs/`, with their hashes in
each plan. The immutable handoff is unchanged. Fixture result metadata remains
NOT_RUN; actual executions are separate artifact-bound result records.

006 starts A independently, advances it through10real epochs, and retains the
actual A5/A8 IDs. The parent attaches them at distinct /left and /right paths.
Seven separate SDK historical calls must apply exactly6..10and9..10to those
respective occurrences. Source state, complete receipts and events stay unchanged.

008 commits A60/A90 and C70/C80 before attaching saved initialized A0/C0. A later
parent mark300is already pending. Four historical calls must run A60,C70,C80,A90,
with the parent still unmarked after each; mark300then runs LIVE. This preserves
the distinction between external order and each exact retained predecessor.

The independent checker binds each application to its actual source lineage,
occurrence, retained source receipt, exact transition/cause/predecessor, epoch and
source order. It rejects missing/duplicate/reordered work, swapped occurrences,
wrong lineage, unretained receipts, forged predecessors/timestamps and overtaking.
Ten new synthetic checker tests cover these boundaries; they are not runtime credit.
All43hardening,64Iteration2and18harness tests pass.

Actual production execution of both complete literal plans passed on MyOS JAR
1088f02b314741913f0205cba4bde858937485b4f38c384cb21c304d7d9ea331,
using the independent source/dependency lock and the maintained SDK adapter.
Results: campaign evidence/rooted-production-multiple-history-first.json.
No tariff, normative rule, runtime behavior, historical receipt or gate limit changed.
