# E2 packages 3, 4 and 6: collections and finite reactions

Language base: `26fd05dbd16fa4a59a470aa9d3caf6b64b9fd658`.
BEX base: `e0597120d9a57015c6631a6acd3fadac8376e073`.
Final slice commits, test counts and profile measurements: `00-handoff.md`.

| Requirement | Executable evidence |
| --- | --- |
| Compiled omitted-sourcePath descriptor, collection descriptors, zero/one/multiple members, escaped stable keys, unrelated/nested events | CompositionCollectionChannelTest |
| Initialization-caused births, nested collections, both commerce and non-commerce labels | CompositionCollectionChannelTest |
| Generated fan-out 0/1/3/6 and nesting depths 1/2/4, reverse physical insertion | CompositionCollectionChannelTest |
| Retirement during a reaction, frozen containing targets versus receiving-channel removal | CompositionCollectionChannelTest |
| Remove/re-add generation and same-invocation retirement fence | Existing ProcessEmbeddedSurfaceReconcilerTest, executed in the full module suite |
| Collection scope cutoff and lifecycle | Existing EmbeddedCollectionLifecycleIntegrationTest, focused root suite |
| Three-, five-, eight-member cycles in Agreement/Orders/Payments and Experiment/Samples/Readings domains | CompositionReactionCycleTest |
| Independent countdown transition/event-order/count oracle | CompositionReactionCycleTest |
| Failures at reactions 1/4/9, all-root state/event/receipt rollback | CompositionReactionCycleTest |
| Nonquiescent cycle bounded by one shared budget | CompositionReactionCycleTest |
| Exact measured gas cap succeeds, one unit below fails atomically | CompositionReactionCycleTest |
| Equal content/distinct lineages, late nested evidence, charged full replay | ProspectiveBirthRetryTest |
| Insertion during retained-source reaction, no duplicate source publication | RetainedEpochBirthCompositionTest |

Existing lifecycle work already handles arbitrary finite affected-closure cycles
under portable work/gas limits. No short-cycle cap was added. The missing public
birth-evidence preparation was supplied in package 5's slice, and scalar output
and inherited lifecycle resolution were fixed in package 2's slice. No broad
closure-engine rewrite was required.

Queue boundary: membership reconciles after synchronous mutation/finalization;
events freeze containing targets at emission; matching uses the latest channel
surface at FIFO dequeue; all matching deliveries for the current event are
admitted before the first executes. Retiring a containing edge cannot cancel
already-emitted events. Removing a receiving channel prevents a later event
from matching. Re-add at a retired path requires a later invocation and a fresh
occurrence generation. These are existing Contracts §§6.7, 7.9 and 9.4 rules;
the guide makes the F2 routing boundary explicit without inventing wildcard
syntax or changing Coordination rules.

`CompositionCampaignProfile` is an explicit runnable harness, excluded from
normal JUnit execution. It checks an eight-member ring with eighteen reactions
and eight receipts, warms up three times, measures twelve runs, and rejects any
physical-warmup change in logical gas. The local Gradle init script and profile
summary are retained beside this receipt. Infrastructure observations are
addressed to B2 in the final handoff; no performance optimization or semantic
shortcut was introduced.
