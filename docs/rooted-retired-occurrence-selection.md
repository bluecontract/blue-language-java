# Historical source selection after collection removal

RCP-GRAPH-05 and Contracts §5.6 preserve an inactive successor reservation after
removal. Re-adding the same lineage uses that reserved activation generation and
may select old history. It does not inherit the retired occurrence's applied cursor.

The RUN-012 public adapter exposed a discovery gap: after source S advanced from
S0 to S1 and `/orders/same` was removed, re-adding saved S0 returned
`INVALID_PROCESSING_DOCUMENT / ManagedOccurrenceBindingMissing`. Demand discovery
skipped every inactive row, so Coordination never received the typed request that
could establish S0's retained historical position.

Discovery now requests exact content, then managed-occurrence evidence, when an
inactive reservation for an initialized source encounters a different exact value.
The existing resolver must still prove the selected state in the reserved lineage.
Discovery does not change bindings, retained receipts, source heads or gas.
An active lineage's prior-finalization shortcut cannot establish a new generation's
historical selection. A prospective uninitialized draft still rejects an exact-state
mismatch at the existing closed boundary; it has no retained source history.

Validation: the three new demand tests failed before the repair. The first broader
patch failed the maintained wrong-prospective-draft rejection; it was narrowed without
changing that assertion. The resulting complete Contracts core suite passed all 528
tests and strict Javadoc. Existing foreign-target and same-invocation reactivation
rejections remain unchanged. Public SDK, packaged adapter, rollback and final component
gates remain required; these processor results do not establish RC readiness.

The subsequent SDK test exposed a second adapter error: replacing the committed
inactive row while expanding read evidence violated `RootedInputExpansion`. That
authenticity check remains unchanged. Coordination must retain the original row
and supply `ManagedOccurrenceEvidenceResolution` as part of the existing exact
retry protocol. The processor consumes it at the emitted demand boundary, keeping
the reserved generation and setting the selected historical cursor. The input
reservation must already be inactive, have no historical cursor, refer to the same
initialized lineage and match the original binding; a row newly retired during
this invocation cannot qualify. The same-invocation retirement fence also remains.

Result verification independently requires that exact resolution for an inactive
reservation becoming historical, including the selected identity, epoch, lineage,
generation and input closure. It rejects a forged pending cursor without evidence.
The updated complete Contracts core suite passed all 529 tests and strict Javadoc.
The SDK lifecycle, foreign-lineage, late-gas rollback and public HTTP/restart cases
are maintained separately and must pass on the rebuilt complete dependency tuple.
