# Normative Closure-Limit Generators

The C-CLO-16 and C-CLO-17 boundary microfixtures use the generators below. A
runner must construct or stream the described semantic input and independently
measure it; it must not trust the fixture's `observed` field as the result.

All generated DocumentIds use ASCII decimal ordinals padded to eight digits.
All generated paths are normalized RFC 6901 pointers. Generated collections
are enumerated in ordinal order. Values are exact Blue values calculated by the
unchanged Blue Language 1.0 algorithms.

`managed-documents`

: Construct `parameters.count` initialized acyclic documents named
  `document-00000000`, …, with no active edge and no semantic delivery. The
  measured value is the number of unique managed DocumentIds in the closure.

`embedded-edges`

: Construct the least number of initialized source documents needed to contain
  `parameters.count` distinct direct object-member occurrences `/edges/eNNNNNNNN`.
  Every member is an exact reference to one initialized target document and is
  covered by one `Process Embedded.collectionPaths` declaration. The measured
  value is the number of unique active `(sourceDocumentId, sourcePath,
  activationGeneration)` tuples after deterministic deduplication.

`cyclic-members`

: Construct a directed ring of `parameters.count` identity-distinguishable
  documents. Member `i` contains `/next -> (i + 1) mod count`; a one-member ring
  is a self-edge. Calculate and independently verify the complete Language
  cyclic proof. The measured value is the component member count.

`cyclic-edges`

: Construct `parameters.members` identity-distinguishable cyclic members.
  Enumerate candidate directed internal edges in source-ordinal then
  target-ordinal order, beginning with a ring, and retain the first
  `parameters.count` unique edges. The measured value is the number of active
  internal edges in the one component.

`cyclic-canonical-bytes`

: Construct a two-member ring whose first member has a direct literal `value`
  string. Select the lexicographically first ASCII string consisting of zero or
  more `a` code points followed, when necessary, by one code point in the range
  `b`…`z` such that the exact byte count defined below equals
  `parameters.count`. If no such string exists, deterministically add direct
  literal fields `pad00000000`, … before varying the final field. The measured
  value is the UTF-8 length of the RFC 8785 canonical representation-normalized
  cyclic limit form after preliminary-order sorting and canonical `this#n`
  remapping. Retain internal references, their direct container spines, and
  complete direct literal members. Collapse every complete non-literal child
  outside those spines to its pure exact BlueId reference. This makes the count
  invariant under equivalent inline versus exact-reference representation. No
  preliminary ZERO form, proof, or materialized `MASTER#index` byte is included.

`graph-changes`

: Starting from two initialized acyclic documents, alternate add and remove of
  the exact `/peer` occurrence. Each successful active-edge change increments
  the measured value once. A rejected next change is not applied and advances
  no generation.

`closure-expansions`

: Begin with one admitted initialized document. Each step adds exactly one
  previously absent initialized target and its verified occurrence binding to
  the affected closure. The measured value is the number of successful
  expansion steps, not the final document count.

`work-occurrences`

: Create `parameters.count` distinct no-op historical-transition delivery
  occurrences with consecutive ordinals and distinct transition identities.
  Each admitted exact work occurrence counts once regardless of equal payload
  values. The rejected next occurrence is neither enqueued nor charged.

`tentative-finalizations`

: Start from one verified self-cycle. Each step changes one direct literal
  scalar to its step ordinal and performs one complete exact tentative cyclic
  finalization before later work. The measured value is the number of completed
  finalization boundaries. The rejected next boundary performs no Language
  identity work and installs no temporary member identity.

## Boundary result

An at-bound microfixture expects `limitDecision: ACCEPT`. It does not execute
the rest of the potentially gas-expensive invocation. A first-above fixture
expects `limitDecision: REJECT`, the limit's exact diagnostic, and
`rejectedStepAdmitted: false`. Separate executable closure fixtures prove the
owners, increment points, gas interaction, and rollback behavior of these
counters.
