# Rooted checkpoint implementation target

The selected contract is `blue-rooted-checkpoint/1.0-draft.2`. The canonical Contracts
resource and RCP companion come from the reviewed 2026-09-09 handoff. The former rc.24
Contracts specification is retained under `specifications/historical/rc24`.

The prepared rooted conformance resources are in
`blue-conformance/src/main/resources/blue-rooted-checkpoint-1.0`. Their existing relative
`conformance/rooted-processing`, `identity`, `model`, and `tools` paths are preserved.
`IMPORT.json` identifies their initial input bytes. Existing Language, Contracts, gas and
historical-representation fixture directories are unchanged. The complete historical
baseline remains identified by the handoff archive and by its baseline Git commits; it
is not copied over maintained runtime sources.

The normative resource uses the standalone relative `../conformance` link. In the Maven
layout its target is the adjacent blue-conformance artifact's rooted resource package.
The same five-specification set is bundled there for self-contained review.

`rootedCheckerTest` runs only the 18 + 64 + 26 abstract/checker tests. It does not establish
production conformance. The new SDK adapter and final local RC acceptance are required
separately. Old-profile receipts and fixture outputs cannot be relabelled as rooted results.
