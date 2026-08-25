# Contract-evolution fixture source

`src/main/tools/generate_contract_evolution_fixtures.py` is the authoritative
source for ordinary C-EVO fixture bytes. It generates the ordinary and closure
package mirrors independently; checked mirrors must remain byte-equal to one
another and to a fresh generation.

The first portable tranche covers C-EVO-01 through C-EVO-10 and C-EVO-14
through C-EVO-17. C-EVO-11 through C-EVO-13 are aliases on existing exact
closure fixtures because their laws depend on managed graph identity:

* C-EVO-11: `c-clo-12-frozen-edge-removal.yaml`
* C-EVO-12: `c-clo-11-split-into-two-cycles.yaml`
* C-EVO-13: `c-clo-02-dynamic-finite-cycle.yaml`

C-EVO-18 through C-EVO-23 form the typed-demand tranche and are added only
after the typed-demand runtime gate is green. In particular, C-EVO-21 must
compare the complete execution result, patch/event prefix, canonical demands,
and trace across the original attempt and retry.

Option 2 is normative for this family: generalization may remove subtype-only
Process Embedded declarations, but it does not reveal a declaration hidden by
a subtype. No fixture in this source requires a Blue Language change.
