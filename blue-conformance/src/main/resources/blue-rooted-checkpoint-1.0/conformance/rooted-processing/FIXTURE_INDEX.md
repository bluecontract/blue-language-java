# Iteration 2 fixture index

100 retained/new candidate scenario records: 66 executable abstract fixtures and 34 production obligations. Nine production obligations have literal plans; 25 remain recipe-level. All production runtime results are NOT_RUN.

| ID | Kind | Qualification | Title |
|---|---|---|---|
| [RCP-SEL-001](fixtures/sel/rcp-sel-001.json) | selector | EXECUTABLE_ABSTRACT | No pending bindings |
| [RCP-SEL-002](fixtures/sel/rcp-sel-002.json) | selector | EXECUTABLE_ABSTRACT | Single channel scans an ordered suffix |
| [RCP-SEL-003](fixtures/sel/rcp-sel-003.json) | selector | EXECUTABLE_ABSTRACT | Consumed prefix is not delivered again |
| [RCP-SEL-004](fixtures/sel/rcp-sel-004.json) | selector | EXECUTABLE_ABSTRACT | Different keys in one document have independent progress |
| [RCP-SEL-005](fixtures/sel/rcp-sel-005.json) | selector | EXECUTABLE_ABSTRACT | Root E20 cannot suppress child E10 |
| [RCP-SEL-006](fixtures/sel/rcp-sel-006.json) | selector | EXECUTABLE_ABSTRACT | Two Timelines interleave |
| [RCP-SEL-007](fixtures/sel/rcp-sel-007.json) | selector | EXECUTABLE_ABSTRACT | Timeline ASCII tie break ignores input array order |
| [RCP-SEL-008](fixtures/sel/rcp-sel-008.json) | selector | EXECUTABLE_ABSTRACT | One exact entry is a frozen set of fresh bindings |
| [RCP-SEL-009](fixtures/sel/rcp-sel-009.json) | selector | EXECUTABLE_ABSTRACT | Inactive bindings do not become live subscriptions |
| [RCP-SEL-010](fixtures/sel/rcp-sel-010.json) | selector | EXECUTABLE_ABSTRACT | Distinct occurrence generations retain separate cursors |
| [RCP-SEL-011](fixtures/sel/rcp-sel-011.json) | selector | EXECUTABLE_ABSTRACT | Root-local duplicate source positions select different suffixes |
| [RCP-SEL-012](fixtures/sel/rcp-sel-012.json) | selector | EXECUTABLE_ABSTRACT | Unicode address enumeration has a stable logical receiver set |
| [RCP-SEL-013](fixtures/sel/rcp-sel-013.json) | selector | EXECUTABLE_ABSTRACT | A completed view contributes no candidate |
| [RCP-SEL-014](fixtures/sel/rcp-sel-014.json) | selector | EXECUTABLE_ABSTRACT | Different histories can share an empty checkpoint |
| [RCP-SEL-015](fixtures/sel/rcp-sel-015.json) | selector | EXECUTABLE_ABSTRACT | Reject a negative checkpoint index in the abstract fixture |
| [RCP-SEL-016](fixtures/sel/rcp-sel-016.json) | selector | EXECUTABLE_ABSTRACT | Reject a prefix beyond supplied history |
| [RCP-SEL-017](fixtures/sel/rcp-sel-017.json) | selector | EXECUTABLE_ABSTRACT | Reject out-of-order projected history |
| [RCP-SEL-018](fixtures/sel/rcp-sel-018.json) | selector | EXECUTABLE_ABSTRACT | Reject repeated entry inside one projected history |
| [RCP-SEL-019](fixtures/sel/rcp-sel-019.json) | selector | EXECUTABLE_ABSTRACT | Reject duplicated receiving addresses |
| [RCP-SEL-020](fixtures/sel/rcp-sel-020.json) | selector | EXECUTABLE_ABSTRACT | Entry tie breaker does not legalize duplicate timestamps in one Timeline |
| [RCP-SEL-021](fixtures/sel/rcp-sel-021.json) | safe-window | EXECUTABLE_ABSTRACT | A known lower completeness bound prevents selecting a later visible entry |
| [RCP-SEL-022](fixtures/sel/rcp-sel-022.json) | safe-window | EXECUTABLE_ABSTRACT | A fully evidenced earlier entry may be selected before a later frontier |
| [RCP-SEL-023](fixtures/sel/rcp-sel-023.json) | terminal-progress | EXECUTABLE_ABSTRACT | Feeder rejection advances operational progress without forging checkpoint |
| [RCP-SEL-024](fixtures/sel/rcp-sel-024.json) | selector | EXECUTABLE_ABSTRACT | External cause identities preserve large exact microsecond timestamps |
| [RCP-SCOPE-001](fixtures/scope/rcp-scope-001.json) | graph | EXECUTABLE_ABSTRACT | Chain root A includes its forward descendants |
| [RCP-SCOPE-002](fixtures/scope/rcp-scope-002.json) | graph | EXECUTABLE_ABSTRACT | Chain root B does not pull its parent A |
| [RCP-SCOPE-003](fixtures/scope/rcp-scope-003.json) | graph | EXECUTABLE_ABSTRACT | Chain leaf C does not open its incoming observers |
| [RCP-SCOPE-004](fixtures/scope/rcp-scope-004.json) | graph | EXECUTABLE_ABSTRACT | Sibling Order is outside Order1 root |
| [RCP-SCOPE-005](fixtures/scope/rcp-scope-005.json) | graph | EXECUTABLE_ABSTRACT | Shared Agreement is independent of both one-way Orders |
| [RCP-SCOPE-006](fixtures/scope/rcp-scope-006.json) | graph | EXECUTABLE_ABSTRACT | A genuine return path includes the cycle |
| [RCP-SCOPE-007](fixtures/scope/rcp-scope-007.json) | graph | EXECUTABLE_ABSTRACT | Self cycle is a finite graph |
| [RCP-SCOPE-008](fixtures/scope/rcp-scope-008.json) | graph | EXECUTABLE_ABSTRACT | Diamond discovery shares a vertex but preserves downstream edges |
| [RCP-SCOPE-009](fixtures/scope/rcp-scope-009.json) | graph | EXECUTABLE_ABSTRACT | Incoming observer and its sibling do not enter root |
| [RCP-SCOPE-010](fixtures/scope/rcp-scope-010.json) | graph | EXECUTABLE_ABSTRACT | Disconnected stored graph is irrelevant |
| [RCP-SCOPE-011](fixtures/scope/rcp-scope-011.json) | graph | EXECUTABLE_ABSTRACT | A return path makes previously separate consumers relevant |
| [RCP-SCOPE-012](fixtures/scope/rcp-scope-012.json) | graph-generated | EXECUTABLE_ABSTRACT | Ten-thousand one-way observers do not enlarge the source root |
| [RCP-SCOPE-013](fixtures/scope/rcp-scope-013.json) | graph-generated | EXECUTABLE_ABSTRACT | A real forward fan-in still counts toward the portable boundary |
| [RCP-DYN-001](fixtures/dyn/rcp-dyn-001.json) | dynamic | EXECUTABLE_ABSTRACT | Historical attachment contributes A6 through A10 then later root input |
| [RCP-DYN-002](fixtures/dyn/rcp-dyn-002.json) | dynamic | EXECUTABLE_ABSTRACT | Two imported histories are merged rather than drained child by child |
| [RCP-DYN-003](fixtures/dyn/rcp-dyn-003.json) | dynamic | EXECUTABLE_ABSTRACT | A historical successor can reveal still earlier nested history |
| [RCP-DYN-004](fixtures/dyn/rcp-dyn-004.json) | dynamic | EXECUTABLE_ABSTRACT | Genuinely born channel does not receive its creating entry |
| [RCP-DYN-005](fixtures/dyn/rcp-dyn-005.json) | dynamic | EXECUTABLE_ABSTRACT | Removed binding cannot receive its later input |
| [RCP-DYN-006](fixtures/dyn/rcp-dyn-006.json) | dynamic | EXECUTABLE_ABSTRACT | Retarget uses the selected new source not the retired suffix |
| [RCP-DYN-007](fixtures/dyn/rcp-dyn-007.json) | dynamic | EXECUTABLE_ABSTRACT | Re-add original source uses a new generation with its selected history |
| [RCP-DYN-008](fixtures/dyn/rcp-dyn-008.json) | dynamic | EXECUTABLE_ABSTRACT | Already-current attachment contributes no old input |
| [RCP-DYN-009](fixtures/dyn/rcp-dyn-009.json) | dynamic | EXECUTABLE_ABSTRACT | Same-entry source set is frozen before fresh birth |
| [RCP-DYN-010](fixtures/dyn/rcp-dyn-010.json) | dynamic | EXECUTABLE_ABSTRACT | Two generations selecting different source checkpoints remain independent |
| [RCP-HIST-001](fixtures/hist/rcp-hist-001.json) | history | EXECUTABLE_ABSTRACT | Five contiguous successors from selected epoch five |
| [RCP-HIST-002](fixtures/hist/rcp-hist-002.json) | history | EXECUTABLE_ABSTRACT | A gap cannot be skipped |
| [RCP-HIST-003](fixtures/hist/rcp-hist-003.json) | history | EXECUTABLE_ABSTRACT | Wrong exact predecessor cannot be substituted |
| [RCP-HIST-004](fixtures/hist/rcp-hist-004.json) | history | EXECUTABLE_ABSTRACT | Event-only application advances application position, not business value |
| [RCP-HIST-005](fixtures/hist/rcp-hist-005.json) | history | EXECUTABLE_ABSTRACT | A no-listener source state update is still applied |
| [RCP-HIST-006](fixtures/hist/rcp-hist-006.json) | history | EXECUTABLE_ABSTRACT | Failure at one successor retains earlier committed application only |
| [RCP-HIST-007](fixtures/hist/rcp-hist-007.json) | history | EXECUTABLE_ABSTRACT | Exact retry resumes from remaining position |
| [RCP-HIST-008](fixtures/hist/rcp-hist-008.json) | history | EXECUTABLE_ABSTRACT | Repeating an applied receipt is not another event |
| [RCP-HIST-009](fixtures/hist/rcp-hist-009.json) | historical-read | EXECUTABLE_ABSTRACT | Historical handler reads the selected B2 not ambient current B |
| [RCP-HIST-010](fixtures/hist/rcp-hist-010.json) | position-chain | EXECUTABLE_ABSTRACT | Representation positions cannot be collapsed by equal epoch number |
| [RCP-HIST-011](fixtures/hist/rcp-hist-011.json) | position-chain | EXECUTABLE_ABSTRACT | Reordered same-epoch predecessor fails |
| [RCP-WORK-001](fixtures/work/rcp-work-001.json) | reaction | EXECUTABLE_ABSTRACT | Finite pair countdown |
| [RCP-WORK-002](fixtures/work/rcp-work-002.json) | reaction | EXECUTABLE_ABSTRACT | One unit below required work rolls back |
| [RCP-WORK-003](fixtures/work/rcp-work-003.json) | reaction | EXECUTABLE_ABSTRACT | Extra budget does not create extra work |
| [RCP-WORK-004](fixtures/work/rcp-work-004.json) | reaction | EXECUTABLE_ABSTRACT | Fresh same-payload reemission cannot be deduplicated |
| [RCP-WORK-005](fixtures/work/rcp-work-005.json) | reaction | EXECUTABLE_ABSTRACT | Three-node finite cycle |
| [RCP-WORK-006](fixtures/work/rcp-work-006.json) | reaction | EXECUTABLE_ABSTRACT | Finite self cycle |
| [RCP-WORK-007](fixtures/work/rcp-work-007.json) | reaction | EXECUTABLE_ABSTRACT | Zero budget commits no processing effect |
| [RCP-WORK-008](fixtures/work/rcp-work-008.json) | reaction | EXECUTABLE_ABSTRACT | Inert target handles one supplied work |
| [RCP-RUN-001](fixtures/run/rcp-run-001.json) | runtime | LITERAL_CRITICAL | Counter baseline remains valid through public API |
| [RCP-RUN-002](fixtures/run/rcp-run-002.json) | runtime | LITERAL_CRITICAL | Adding a sibling observer cannot change source results |
| [RCP-RUN-003](fixtures/run/rcp-run-003.json) | runtime | LITERAL_CRITICAL | An out-of-gas sibling cannot veto source or another root |
| [RCP-RUN-004](fixtures/run/rcp-run-004.json) | runtime | RECIPE_ONLY | Root direct checkpoint stays ahead while historical child advances |
| [RCP-RUN-005](fixtures/run/rcp-run-005.json) | runtime | RECIPE_ONLY | Two channels in one document retain distinct progress |
| [RCP-RUN-006](fixtures/run/rcp-run-006.json) | runtime | RECIPE_ONLY | Two occurrences of A5 and A8 apply distinct successor suffixes |
| [RCP-RUN-007](fixtures/run/rcp-run-007.json) | runtime | LITERAL_CRITICAL | Exact A10/B-attaches-A5 and live cycle join |
| [RCP-RUN-008](fixtures/run/rcp-run-008.json) | runtime | RECIPE_ONLY | Two historical sources interleave by cause order |
| [RCP-RUN-009](fixtures/run/rcp-run-009.json) | runtime | RECIPE_ONLY | No listener still permits required reference advancement |
| [RCP-RUN-010](fixtures/run/rcp-run-010.json) | runtime | RECIPE_ONLY | Descendant listener survives a silent intermediate document |
| [RCP-RUN-011](fixtures/run/rcp-run-011.json) | runtime | RECIPE_ONLY | Initially empty Orders collection receives later members |
| [RCP-RUN-012](fixtures/run/rcp-run-012.json) | runtime | RECIPE_ONLY | Removed/re-added key has a distinct occurrence and correct later event |
| [RCP-RUN-013](fixtures/run/rcp-run-013.json) | runtime | RECIPE_ONLY | Historical retarget does not read the retired or latest wrong source |
| [RCP-RUN-014](fixtures/run/rcp-run-014.json) | runtime | LITERAL_CRITICAL | Diamond preserves canonical event and Handler-visible read order |
| [RCP-RUN-015](fixtures/run/rcp-run-015.json) | runtime | RECIPE_ONLY | Equal payload events remain distinct occurrences |
| [RCP-RUN-016](fixtures/run/rcp-run-016.json) | runtime | LITERAL_CRITICAL | An event-only source step survives restart without duplication |
| [RCP-RUN-017](fixtures/run/rcp-run-017.json) | runtime | RECIPE_ONLY | Same-epoch representation chain uses exact predecessors |
| [RCP-RUN-018](fixtures/run/rcp-run-018.json) | runtime | RECIPE_ONLY | Saved-original pair then ring forms without rewriting history |
| [RCP-RUN-019](fixtures/run/rcp-run-019.json) | runtime | RECIPE_ONLY | Breaking/advancing/reconnecting preserves missed history |
| [RCP-RUN-020](fixtures/run/rcp-run-020.json) | runtime | LITERAL_CRITICAL | Cold/warm reuse preserves full live-root charge trace and failure |
| [RCP-RUN-021](fixtures/run/rcp-run-021.json) | runtime | LITERAL_CRITICAL | Already committed source is not rolled back by failed consumer |
| [RCP-RUN-022](fixtures/run/rcp-run-022.json) | runtime | RECIPE_ONLY | Cycle gas failure rolls back its required result without meter reset |
| [RCP-RUN-023](fixtures/run/rcp-run-023.json) | runtime | LITERAL_CRITICAL | Cycle formation and split do not change gas ownership midway |
| [RCP-RUN-024](fixtures/run/rcp-run-024.json) | runtime | RECIPE_ONLY | New child birth failure cannot publish a half-created graph |
| [RCP-RUN-025](fixtures/run/rcp-run-025.json) | runtime | LITERAL_CRITICAL | Suspending early materialization cannot change source history |
| [RCP-RUN-026](fixtures/run/rcp-run-026.json) | runtime | RECIPE_ONLY | Explicit new-from-cause remains distinct from history discovery |
| [RCP-RUN-027](fixtures/run/rcp-run-027.json) | runtime | RECIPE_ONLY | Missing exact input creates a typed wait and exact resume |
| [RCP-RUN-028](fixtures/run/rcp-run-028.json) | runtime | RECIPE_ONLY | Crash around publication does not lose state or duplicate events |
| [RCP-RUN-029](fixtures/run/rcp-run-029.json) | runtime | RECIPE_ONLY | Nanosecond timestamp round-trip cannot masquerade as conflicting evidence |
| [RCP-RUN-030](fixtures/run/rcp-run-030.json) | runtime | RECIPE_ONLY | False and mutated root contexts must be rejected |
| [RCP-RUN-031](fixtures/run/rcp-run-031.json) | runtime | RECIPE_ONLY | Foo/Gender/empty-schema identity remain correct under the new profile |
| [RCP-RUN-032](fixtures/run/rcp-run-032.json) | runtime | RECIPE_ONLY | Large fan-out is scheduled as independent rooted obligations |
| [RCP-RUN-033](fixtures/run/rcp-run-033.json) | runtime | RECIPE_ONLY | All four explicit attachment starting positions preserve their intended suffixes |
| [RCP-RUN-034](fixtures/run/rcp-run-034.json) | runtime | RECIPE_ONLY | Source advances while a one-way observer stays behind without false readiness |

Additional finite ownership/cause tests and 13 exact internal-envelope vectors are separate from these 100 records; see `tools/test_iteration2.py`, `model/operation_model.py` and `identity/vectors.json`. Passing these does not increment the production denominator.
