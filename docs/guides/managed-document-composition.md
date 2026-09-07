# Managed document composition and birth recovery

Contracts owns initialization, reaction execution, gas, finalization, and the
receipts of an affected closure. The host owns durable lineage reservations,
historical ordering, retry orchestration, and atomic publication. BEX may
construct documents through ordinary functions; it does not initialize them
privately.

## Scalar document factories

A function may return a document with an inline or exact referenced type and
scalar properties. A type obtained through an import or a document field is
subject to the same rules. The Contracts `SemanticOutputBoundary` validates
business fields as complete values while preserving runtime-owned fields,
including partial Handler `event` matchers and executable bodies. It returns
an invocation-scoped `ExactBlueValue` for effects. Missing required values,
wrong scalar kinds, and an empty object supplied where Text is required fail
before publication. BEX null, a missing property, and an explicit empty object
remain distinct; see the BEX null-boundary acceptance tests.

Use a snapshot-backed processor from `BlueContracts.runtimeAccess()` with the
same immutable runtime registry as the hosted adapter. The provider must
supply exact canonical type/contract content. Custom snapshot managers must
implement `materializeVerifiedExactReference` with exact evidence, rather than
returning a recursively resolved type body. Inherited lifecycle handlers run
through the ordinary managed admission path.

## New lineage discovered during an attempt

Contracts §9.4 already permits a resolved retry to expand closed input evidence.
`ClosureEvidenceFactory.withProspectiveBirths` makes that preparation explicit:

1. Run admission with `admitClosureWithLifecycleQueue`, or run the retained
   revision through `processClosure`.
2. On `NEEDS_RESOURCES`, retain the unchanged invocation input. Do not publish
   any tentative result, initialization, event, receipt, or gas total.
3. For each `ManagedOccurrenceEvidenceDemand` that represents a genuinely new
   lineage, reserve a fresh durable `DocumentId`. Use the demand's exact supplied
   canonical value, or retrieve and verify precisely its `suppliedValueBlueId`.
4. Construct `ManagedDocumentBirth(demand, reservedId, exactDocument)` and pass
   all births from that attempt to `withProspectiveBirths(input, births)`.
5. Execute the resulting input with the same entry point. Further nested births
   can produce further demands; repeat with the newest input and its demands.
6. Publish only a successful result and its commit companion, atomically with
   the host's expected-head and application checks.

This helper adds inactive prospective occurrences and uninitialized epoch-zero
members. It preserves predecessor heads, components, epochs, public roots,
logical cause, direct deliveries, environment, and the complete execution
policy. The expanded closure and invocation identities change. All execution
starts again at the beginning under the same cap; the helper never grants a
free suffix or carries a partial marker into the retry. Nonquiescent reactions
fail through the shared gas contract and publish nothing.

The helper rejects stale or unrelated demands, represented lineages, duplicate
occurrence paths, conflicting content, and direct lifecycle/checkpoint markers.
A birth is untrusted evidence, not a receipt. The normal verifier and lifecycle
engine still validate it. Durable global uniqueness of a reservation is the
host's responsibility. Equal content at distinct creation occurrences needs
distinct reservations. Historical retargets of existing lineages use the
existing retained-receipt/resolution APIs, not the birth helper. Reattachment
at a retired path uses its existing generation history; this helper deliberately
does not replace an already represented occurrence row.

## Collections and queue visibility

`Process Embedded.collectionPaths: [/children]` selects concrete direct members
of an object or list collection. Object member addresses escape `~` as `~0` and
`/` as `~1`. Physical object insertion order has no semantic significance.

An `Embedded Node Channel` with omitted `sourcePath` accepts events from any
contained descendant (§6.7). An explicit `sourcePath` selects exactly that
relative pointer. An `Embedded Collection Event Channel` with
`collectionPath: /children` selects direct member occurrences in that
collection. These are existing descriptors; `/children/*` is not a wildcard
contract. F2 should consume the compiled type, contribution identity, channel
key, and concrete occurrence path emitted by Contracts.

Membership changes become visible after each synchronous patch/finalization
continuation. An event freezes its containing targets when emitted. Retirement
of an edge does not cancel that event. At FIFO dequeue, Contracts matches the
latest receiving channel surface and admits all matching deliveries before the
first executes (§7.9). Removing a channel prevents a later event from matching;
it does not cancel work already admitted for the current event. New emissions
use the then-current containing graph. Removed paths retire their occurrence
generation; re-addition is permitted in a later invocation with a fresh
occurrence identity. Same-invocation reactivation remains explicitly rejected.

## Retained epochs and F2

`RetainedEpochBirthCompositionTest` is the minimal executable handoff. It first
executes a source and obtains a real Contracts transition receipt. A consumer
then applies that retained epoch; its reaction creates a new child, resolves a
birth demand, initializes that child, and publishes consumer/birth receipts.
The source's initialized flag, epoch, and exact head remain unchanged. Source
handlers do not run again and source public emissions are not republished.

F2 must supply the verified retained receipt and historical position, preserve
the logical cause while resolving resources, and compare/publish the resulting
application atomically. Exact replay of an unchanged input produces the same
receipt identities. Durable command deduplication and deciding whether a new
command is a legitimate creation remain host responsibilities; Contracts does
not infer them from equal document content.

No normative Language or Coordination rule changes are required. This guide
implements and illustrates existing Contracts §§6.7, 7.9, and 9.4.
