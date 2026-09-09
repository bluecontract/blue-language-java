# Equal event payloads retain distinct occurrences

RCP-RUN-015 contains nine literal SDK steps. The source tick contains two actual
appendEvent instructions with identical payloads. P processes that tick through
its saved S0 child view and must record two observations [1, 1]. Its independent
source remains at counter 0. Later independent source publication emits exactly
two public events, without changing P or creating any further eligible input.
Complete retained records remain equal across restart.

The independent checker reads the real calculated source transition receipt.
It requires two equal exact event values/BlueIds, source ordinals 0 and 1 and two
different occurrence identities. It then binds both ordered parent embedded
work items to those exact payloads and source occurrence identities. Nine
synthetic checks reject dropped events/deliveries, duplicate ordinals, wrong
lineage/payload, reused identities and reordered/reused deliveries.

The runtime literal passed on JAR
3b9422207ecb6008c3c3c6e33cb12574bd41ff6b457d3a3ebe52a9aa7ec85b44;
the final strengthened checker also accepts its retained actual records.
Evidence: rooted-production-equal-events-literal.json. All 74 hardened tests,
64 Iteration 2 tests and 18 harness tests pass. Original recipe bytes/hashes are
preserved and fixture production metadata remains NOT_RUN. No runtime, gas,
publication or identity rule changed.
