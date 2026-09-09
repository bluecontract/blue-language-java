# Blue Coordination Specification 1.0 — rooted checkpoint candidate

> **Current revision:** `blue-rooted-checkpoint/1.0-draft.2`. The [RCP-1 companion](rooted-checkpoint-processing-1.0-draft.md) is normative for this draft. Feeder selection remains separate from the one-input Contracts processor. This revision narrows root scope, clarifies checkpoint-driven historical advancement and records root-bound compatibility requirements; it is not a released or implemented replacement RC.

**Status:** Proposed normative candidate aligned with the Blue Language empty-object revision, the BEX null-boundary correction, and the Contracts embedded-collection revision; exact specification/type identities pending regeneration
**Intended audience:** authors of Coordination types, Timeline Providers, feeder implementers, Blue Contracts processors, managed-document hosts, Mandate resolvers, conformance tools, and application platforms
**Normative dependencies:** Blue Language 1.0; Blue Contracts and Processor 1.0; the exact Coordination types that reference this specification
**Informative background:** *Blue Timelines and Mandates* architecture white paper, version 1.3

---

## 1. Purpose

Blue separates shared state from the independent histories that may change it.

A Blue document represents an exact shared situation and contains deterministic contracts. A Timeline records an attributed external history. A Timeline Channel binds one Timeline and one actor to one role in a document. A feeder proves that the relevant outside history is complete, determines the next canonical entry, verifies direct or delegated authority, and supplies the original eligible Timeline Entry to Blue Contracts. Blue Contracts then computes the deterministic next state.

This specification is organized around three promises:

```text
Independent histories.
Explicit authority.
One deterministic result.
```

This specification defines:

- the common semantics of Timelines and Timeline Entries;
- the minimum guarantees every conforming Timeline Provider supplies;
- exact ordering within one Timeline and across several Timelines;
- completeness and finality evidence;
- Timeline Channels, Composite Timeline Channels, and All Timelines Channels;
- the external source surface observed by a feeder;
- Operation Requests and the feeder-visible part of Operation invocation;
- direct authority, Actor Policy, Mandates, and delegated authority;
- the feeder algorithm and its boundary with Blue Contracts;
- checkpoint ownership, exact Timeline Entry checkpoint subjects, and idempotency;
- entry dispositions, retry, failure, audit, security, and conformance;
- the rules by which concrete Coordination types reference this specification.

This specification does **not** define:

- Blue node identity, references, canonicalization, or type resolution, which belong to Blue Language;
- workflow step execution, BEX evaluation, patch semantics, internal event propagation, gas accounting, closure processing, or cyclic identity, which belong to Blue Contracts and the registered runtimes;
- one database schema, transport protocol, cloud service, queue, UI, or provider implementation;
- provider-specific business claims beyond the common Timeline guarantees defined here.

---

## 2. Normative language and exactness

The key words **MUST**, **MUST NOT**, **REQUIRED**, **SHALL**, **SHALL NOT**, **SHOULD**, **SHOULD NOT**, **RECOMMENDED**, **MAY**, and **OPTIONAL** are normative.

An **exact value** is a Blue value whose content is known and whose BlueId has been calculated or independently verified according to Blue Language.

A pure reference:

```yaml
blueId: <exact-blue-id>
```

and the verified materialization of that reference are the same exact Blue value. This equivalence applies throughout this specification, including Timeline Entries, Timelines, Mandates, documents, completeness evidence, and checkpoint subjects.

Coordination also preserves the Language distinction between absence and empty
content. In a Blue object field, omission or Source `null` is absence, while
`{}` is a present exact empty object and `[]` is a present exact empty list.
A feeder, Mandate resolver, request matcher, or provider adapter MUST NOT
collapse these values. An unavailable or unexpanded node is neither absence nor
an empty object.

A conforming implementation MUST NOT treat network location, database row identity, Java object identity, insertion order, cache state, thread scheduling, or notification arrival as semantic input unless an exact type defined by this specification explicitly makes it so. No type in this specification does.

---

## 3. Specification identity and type binding

### 3.1 The specification is one exact Markdown value

The canonical source of this specification is the complete Markdown text of this file.

For release identity, the exact text MUST use:

```text
UTF-8
LF line endings
no byte-order mark
no implicit Unicode normalization
one final LF at end of file
```

The ordinary BlueId of that exact Text value identifies **Blue Coordination Specification 1.0**. The identity of this proposal is intentionally not published here and MUST be calculated only after the final text is frozen.

This specification does not contain the final BlueIds of the types that implement it. The specification is finalized first. The types are then finalized with a reference to the specification BlueId. This creates a one-way dependency and no identity cycle.

### 3.2 Coordination types reference the specification

Every canonical type whose semantics are defined by this specification MUST carry the exact specification reference, preferably as a machine-readable field:

```yaml
specification:
  blueId: <Blue Coordination Specification 1.0 BlueId>
```

The type description SHOULD also identify the specification in human-readable prose.

At minimum, the canonical definitions of these roles MUST reference this specification:

```text
Timeline
Timeline Entry
Timeline Channel
Composite Timeline Channel
All Timelines Channel
Operation Request
Operation
Actor Policy
Authority
Mandate Authority
Mandate
Operation Mandate
```

Concrete provider-specific Timeline, Actor, Source, completeness-evidence, and Timeline Entry subtypes MUST either carry the same specification reference directly or inherit it unchanged through their exact effective type.

### 3.3 The type BlueId selects concrete semantics

The exact type BlueId is the runtime selector.

A conforming host maintains an implementation registry keyed by exact type BlueId. Markdown is normative documentation; it is not dynamically executed as code. A host MAY retrieve a specification for explanation or validation, but it MUST NOT guess runtime behavior by inspecting field names.

For an effective Coordination value:

```text
exact type BlueId
    -> exact registered runtime or adapter

effective specification reference
    -> Blue Coordination Specification 1.0
```

An unknown Coordination type is unsupported. Structural similarity is not compatibility.

### 3.4 Mixed specification versions

All effective Coordination types participating in one feeder decision MUST reference the same supported Coordination specification, unless an exact later specification defines a compatibility rule.

Without such a rule, mixed versions produce:

```text
INCOMPATIBLE_COORDINATION_TYPES
```

and the feeder MUST NOT process the candidate entry.

### 3.5 Consequence of a normative change

Changing this normative specification changes its BlueId. Types referencing it then receive new BlueIds when rebuilt.

This proposal is still pre-stable. It intentionally rotates the Coordination specification identity and every dependent type identity when rebuilt. Earlier RC identities MUST NOT be silently reinterpreted or aliased.

This is intentional. Editorial tutorials, white papers, examples, website prose, and nonnormative commentary SHOULD remain outside the exact specification so ordinary documentation improvements do not rotate all Coordination type identities.

---

## 4. Architectural boundaries

### 4.1 Blue document

The Blue document is exact shared state plus deterministic contracts. It does not prove:

- who submitted an external entry;
- how the entry reached a provider;
- where the entry sits in provider history;
- whether earlier entries can still appear;
- whether delegated authority was active.

Those are external evidence questions.

### 4.2 Timeline Provider

A Timeline Provider accepts, attributes, timestamps, links, retains, and serves Timeline Entries. It supplies binding completeness evidence.

Every conforming provider supplies the same minimum Coordination guarantees defined in §6 and §8. Providers may differ in latency, retention, availability, legal accountability, privacy, cryptographic proof, and additional attestations. Those differences do not create a separate `Timeline Provider Profile` semantic object.

Provider-specific behavior is selected by the exact concrete Timeline and evidence types.

### 4.3 Feeder

The feeder closes the outside world for the selected root's next processing decision. It follows declared forward managed dependencies, preserves each exact selected view and its existing channel/application progress, obtains complete ordered evidence, and derives the earliest eligible unconsumed input. Unrelated incoming observers are notification targets, not part of this root's source surface.

For external inputs, it validates attribution and direct/delegated eligibility, then supplies one original eligible Timeline Entry and its frozen receiving set to the processor. For retained history it supplies the existing exact contiguous application cause. These are different admitted input forms to the same deterministic processing machinery.

The feeder answers:

> Which exact input is next for this selected root and its embedded views, after their individual progress?

The processor does not repeat this selection or wait for provider completeness. RCP-1 §§1–4 provides the reference head-merge rule. A scan and an equivalent optimized selector are both conforming.

### 4.4 Blue Contracts processor

The processor receives exact state and one exact admitted cause. It executes registered Channel, Handler, Operation, Workflow, BEX, lifecycle, checkpoint, event, gas, and closure semantics.

It does not contact Timeline Providers, wait for completeness, search for Mandates, use a wall clock, or invent missing evidence.

The processor answers:

> Given this exact state and this exact admitted cause, what is the deterministic next state?

### 4.5 Host platform

The host stores Timeline Entries, provider evidence, managed document histories, source progress, processing receipts, checkpoints, outbox events, and commit evidence. It may use memory, a database, object storage, or another architecture.

Physical architecture MUST NOT alter the semantics in this specification.

---

## 5. Terminology

**Timeline Provider**
A party or system that maintains a Timeline and supplies the guarantees required by this specification.

**Timeline**
An append-only, tamper-evident history for one provider-scoped identity or context.

**Timeline Entry**
One immutable provider-attributed envelope in a Timeline.

**Timeline Channel**
A document contract binding one exact Timeline and one exact actor to a role in one document scope.

**Source Channel**
The external Channel through which the feeder accepts an entry.

**Target Channel**
The Channel named by an Operation Request and bound to the target Operation.

**External source surface**
The complete set of active external source Channel occurrences that may admit provider-backed entries for the exact current document or managed closure.

**Completeness frontier**
An exclusive timestamp boundary before which a provider proves all entries have been revealed and behind which no future accepted entry will be placed.

**ExternalOrderKey**
The canonical total-order tuple defined in §9.

**Operation Request**
A message asking to invoke one named Operation through one target Channel, with a request payload and optional exact-document precondition.

**Mandate**
A living Blue document recording bounded authority delegated by an authority holder to an authorized actor and confirmed by a mandate guarantor.

**Mandate Authority**
Authority evidence carried by a Timeline Entry, identifying the authority holder and the initial Mandate whose processed state governs the action.

**Checkpoint**
Processor-owned document state recording the exact input most recently consumed by one source Channel key in one scope occurrence.

**Feeder terminal progress**
Host state recording that one entry received a terminal feeder disposition even when no processor checkpoint was written.

---

## 6. Timeline

### 6.1 Base shape

The base Timeline role has this conceptual shape:

```yaml
name: Timeline
specification:
  blueId: <Blue Coordination Specification 1.0 BlueId>

timelineId:
  type: Text
  schema:
    required: true
```

A concrete Timeline type MAY add provider-specific fields. It MUST NOT weaken any requirement in this specification.

### 6.2 Exact Timeline identity

The exact Timeline identity used by Coordination is the ordinary BlueId of the complete exact Timeline value, including its concrete type and stable `timelineId`.

The `timelineId` MUST NOT be reused by the same concrete provider type for another history or owner.

A provider endpoint, region, database, transport, or tenant MAY change without creating a new Timeline type when semantic interpretation is unchanged. A change to Timeline ordering, completeness, predecessor semantics, or verification requires a new exact concrete Timeline type and therefore a new type BlueId.

### 6.3 Meaning

A Timeline is a provider-backed perspective, not a claim that it contains every fact in the world.

A Timeline may represent:

- a person;
- a business account;
- an AI agent runtime;
- a device;
- a service;
- a document session;
- a registry subject;
- a bank account context;
- a blockchain-derived stream;
- another stable provider-scoped context.

A document may use many Timelines maintained by many independent providers.

### 6.4 Provider-specific extra fields

A provider may add stronger or more detailed claims through its concrete Timeline, Actor, Source, Entry, or evidence types. Examples include:

```text
regulated account identity
agent runtime identity
banking-session identity
block height and transaction proof
legal registry identity
provider signature
retention commitment
```

The generic feeder does not infer semantics from arbitrary fields. The exact concrete type selects the adapter that verifies those fields and yields the common Coordination facts required by this specification.

---

## 7. Timeline Entry

### 7.1 Base shape

A Timeline Entry has this conceptual shape:

```yaml
name: Timeline Entry
specification:
  blueId: <Blue Coordination Specification 1.0 BlueId>

timeline:
  type: Coordination/Timeline
  schema:
    required: true

prevEntry:
  description: Exact previous Timeline Entry or pure BlueId reference.

timestamp:
  type: Integer
  schema:
    required: true

actor:
  type: Coordination/Actor
  schema:
    required: true

source:
  type: Coordination/Source

onBehalfOf:
  type: Coordination/Authority

message:
  schema:
    required: true
```

Concrete Timeline Entry subtypes MAY add fields but MUST preserve the meaning of these base fields.

### 7.2 Immutability

After a provider accepts an entry, the complete exact entry is immutable.

The provider and host MUST retain the exact entry or an exact resolvable representation. A checkpoint pure reference is not useful if no conforming resource can ever materialize it.

### 7.3 Timeline binding

`timeline` identifies the exact Timeline containing the entry.

Every entry in one Timeline MUST carry the same exact Timeline identity.

### 7.4 Predecessor

The first accepted entry in a Timeline MUST omit `prevEntry`.

Every later accepted entry MUST identify the immediately preceding accepted entry in that Timeline by exact value or pure BlueId reference.

The provider MUST verify the predecessor chain at append time. A reader or feeder MAY independently verify it.

Changing a historical entry changes its BlueId and breaks the next entry's predecessor reference. This makes rewriting detectable. It does not prove the truth of the message.

### 7.5 Timestamp

The provider assigns `timestamp` in integer microseconds.

Within one Timeline:

- timestamps MUST be unique;
- timestamps MUST be strictly increasing in accepted-entry order;
- a later accepted entry MUST NOT receive an earlier or equal timestamp;
- when two requests arrive within one physical microsecond, the provider MUST assign the later request the next unused microsecond.

A business message MAY contain a separate claimed occurrence time. It does not replace the provider-assigned Timeline timestamp.

### 7.6 Actor

`actor` identifies who or what the provider attributes as the submitter or writer.

Actor attribution is not by itself authorization to change every target document.

### 7.7 Source

`source` optionally identifies how the entry reached the provider, such as a browser session, API call, banking session, service request, device connection, or agent runtime.

Source attribution does not itself grant authority.

A policy requiring a source category treats an absent source as not satisfying the policy.

### 7.8 Authority

`onBehalfOf` optionally records authority under which `actor` claims to exercise authority held by another actor.

The base delegated-authority mechanism defined here is Mandate Authority. Other exact Authority types MAY be supported by another specification and installed runtime. Unknown Authority types MUST NOT be guessed.

### 7.9 Message

`message` is the exact business event or request recorded by the provider.

A provider normally does not decide what the message means in every Blue document. Documents, feeder eligibility, Mandates, and Contracts processing decide whether it changes state.

### 7.10 Entry identity and append-once storage

The ordinary BlueId of the complete exact Timeline Entry is its entry identity.

A host MUST store one exact Timeline Entry once by identity. It MUST NOT copy the entry once per recipient or rewrite it with target-document metadata.

Appending the same exact entry again is idempotent and MUST NOT create a second physical entry or a second semantic processing occurrence.

---

## 8. Timeline Provider requirements and completeness

### 8.1 Universal provider duties

Every conforming Timeline Provider MUST:

1. authenticate or otherwise authorize the party or system allowed to append;
2. attribute the exact actor;
3. attribute the submission source when its concrete type or service requires it;
4. assign the Timeline timestamp according to §7.5;
5. preserve the exact predecessor chain;
6. retain and serve exact entries and required evidence to authorized readers;
7. issue binding completeness guarantees;
8. prevent later accepted entries from being placed behind a closed frontier;
9. expose invalid evidence and unavailability distinctly;
10. preserve enough audit evidence to explain its claims.

All providers satisfy this same minimum contract. Providers may provide stronger service or additional facts, but no provider-specific extension may weaken it.

### 8.2 Binding completeness guarantee

A completeness guarantee:

```text
COMPLETE_BEFORE(Timeline L, timestamp B)
```

means both:

```text
Every entry accepted into L with timestamp < B has been revealed.

No entry accepted in the future into L will receive timestamp < B.
```

The second promise is essential. A scan of current storage without the future commitment is not completeness.

A provider preserves the promise by rejecting a request that would require a timestamp behind the frontier or by assigning it a timestamp at or after the frontier.

### 8.3 Completeness evidence

Completeness evidence MAY be represented by a generic exact value or a provider-specific subtype. Conceptually it binds:

```yaml
timeline:
  blueId: <exact Timeline>
completeBefore: <exclusive timestamp>
evidence: <provider-specific exact proof or receipt>
```

The exact concrete evidence type selects its verifier.

No separate provider-profile object is required.

### 8.4 Closed acquisition outcomes

A provider adapter MUST distinguish at least:

```text
COMPLETE
    The requested complete-before fact is verified.

COMPLETE_EMPTY
    The requested interval is verified complete and contains no entries.

DEFERRED_UNAVAILABLE
    Required entries or evidence are temporarily unavailable.

NOT_FOUND
    A requested Timeline, entry, or proof is not found; this is not automatically an empty interval.

INVALID_EVIDENCE
    Evidence is malformed, forged, inconsistent, or fails verification.

INCOMPATIBLE_FRONTIER
    The provider cannot satisfy the requested frontier under the current history or finality state.
```

`DEFERRED_UNAVAILABLE` and `NOT_FOUND` MUST NOT be treated as `COMPLETE_EMPTY` unless the concrete provider type defines that exact positive meaning.

### 8.5 Finality-backed providers

A provider based on a blockchain or another reorganizable history conforms only for finalized history.

Its adapter MUST map its finality rule to the common `COMPLETE_BEFORE` promise. Unfinalized entries remain unavailable for deterministic processing.

### 8.6 Service differences are not protocol profiles

Providers may differ in:

```text
latency
retention
privacy
availability
legal accountability
cryptographic proof
institutional authority
additional actor or source attestations
```

These differences are useful when selecting a provider for a document role, but they do not create another Coordination protocol object. The exact concrete types and the provider's external service contract describe them.

### 8.7 Completeness is not approval

Completeness proves only that no earlier Timeline Entry can still appear. It does not prove:

- that the message is relevant to a document;
- that the actor may call the requested Operation;
- that a document-state precondition holds;
- that a Mandate was active;
- that the message assertion is true.

Those are separate eligibility and business-semantics questions.

---

## 9. Canonical external order

### 9.1 Source-local order

Within one Timeline, timestamp order is authoritative and total because timestamps are unique and strictly increasing.

A valid predecessor chain MUST agree with timestamp order.

### 9.2 Cross-Timeline order

Across Timelines, the canonical `ExternalOrderKey` is:

```text
1. Timeline Entry timestamp, ascending;
2. exact Timeline BlueId text, ascending by unsigned ASCII byte;
3. exact Timeline Entry BlueId text, ascending by unsigned ASCII byte.
```

BlueId text is ASCII, so this ordering is unambiguous.

The order MUST NOT depend on:

```text
notification arrival
network latency
append API call order
database row identity
worker scheduling
map insertion order
cache state
```

### 9.3 Common safe window

For an exact active source surface containing Timelines `L1 ... Ln`, the feeder obtains a verified exclusive completeness frontier from each:

```text
B1 ... Bn
```

The common safe frontier is:

```text
safeBefore = min(B1 ... Bn)
```

Only entries with:

```text
timestamp < safeBefore
```

are in the complete window.

To safely include an entry at timestamp `S`, every relevant Timeline must be complete before at least `S + 1`.

### 9.4 Equal timestamps

When independent Timelines contain entries with the same timestamp, the Timeline and entry BlueId tie-breaks determine their exact order.

A provider must not use the same timestamp twice within one Timeline.

### 9.5 Notifications

Provider notifications are wake-up hints only. They do not establish completeness, canonical order, eligibility, or target routing.

---

## 10. Timeline Channels

### 10.1 Base shape

A Timeline Channel has this conceptual shape:

```yaml
name: Timeline Channel
type: Channel
specification:
  blueId: <Blue Coordination Specification 1.0 BlueId>

timeline:
  type: Coordination/Timeline
  schema:
    required: true

actor:
  type: Coordination/Actor
  schema:
    required: true
```

### 10.2 Role binding

A Timeline Channel says:

> In this document scope, entries from this exact Timeline, attributed to this exact actor, represent this role.

The feeder matches both the Timeline and the actor. A message field claiming a name or role does not replace provider-backed attribution.

### 10.3 Scope-local keys

Timeline Channels are stored under raw contract-map keys such as:

```text
ownerChannel
customerChannel
restaurantChannel
pricingAgentChannel
```

Keys are local to one contract scope. The same raw key in another scope is a different Channel occurrence.

### 10.4 Exact matching

An entry matches a Timeline Channel only when:

```text
entry.timeline equals channel.timeline as an exact Blue value;
entry.actor equals channel.actor under the exact registered Actor equality law;
the Channel occurrence is active for the entry's processing context;
the exact Channel runtime accepts the entry.
```

Provider-specific actor subtypes may define additional exact fields. The exact actor type selects the comparison and verifier.

### 10.5 Active external source surface


The feeder discovers the active external source surface from the exact current processing state.

For ordinary Root processing, this includes the Root and every active external source occurrence in processor-participating scopes.

For managed-document or closure processing, it includes every active Timeline-derived source occurrence in the exact affected managed documents and `Process Embedded` relationships defined by Blue Contracts.

A declared `Process Embedded.collectionPaths` target that is semantically absent
contributes zero managed occurrences and therefore zero descendant Timeline
source occurrences. A present object with zero ordinary members has the same
source-surface cardinality, although the containing Blue document has a
different exact identity. A present incompatible value is invalid, and
incomplete or unavailable evidence blocks planning rather than being treated as
an empty collection.

An inactive prospective managed-occurrence row for one direct collection member
may exist while the collection container itself is absent. It does not enter the
source surface until a successful Contracts result creates and activates the
exact member under an effective declaration. Coordination MUST NOT invent the
target lineage, `DocumentId`, or binding evidence from the authored patch.

A subscription index MAY accelerate discovery. It MUST be rebuildable and MUST have no false negatives. The exact current state remains authoritative.

### 10.6 Activation intervals

A source Channel introduced by an external entry does not admit that creating entry through the new occurrence.

Its ordinary live interval begins strictly after the creating entry's canonical order.

A host importing an existing managed process MAY establish a historical catch-up interval under §19. It MUST NOT silently use old entries without exact frontier and completeness evidence.

### 10.7 Same Timeline bound to several Channels

The same Timeline and actor may be bound to several Channel keys.

The feeder derives every exact matching source occurrence. The registered Contracts runtime may coalesce several physical source occurrences into one logical delivery only under an exact deterministic grouping law. Coalescing MUST NOT execute a business Operation more than once merely because equivalent bindings match.

Checkpoint ownership remains defined by §16.

---

## 11. Composite Timeline Channel and All Timelines Channel

### 11.1 Composite Timeline Channel

A Composite Timeline Channel names same-scope Timeline Channel keys:

```yaml
name: Composite Timeline Channel
type: Channel
specification:
  blueId: <Blue Coordination Specification 1.0 BlueId>

channels:
  type: List
  itemType: Text
```

It has no Timeline or actor of its own. It delegates source matching to its exact active member Channels.

If several members accept the same entry, the composite produces one logical delivery to handlers bound to the composite key.

The composite does not create another provider history, another timestamp, another completeness promise, or another external order.

### 11.2 All Timelines Channel

An All Timelines Channel is the dynamic union of all same-scope active Timeline-derived Channels recognized by its exact runtime.

It has no Timeline or actor of its own. It follows current document structure and delegates eligibility to its members.

### 11.3 Completeness

The feeder obtains completeness from the exact underlying Timelines. Composite and All Timelines Channels do not replace or weaken those requirements.

### 11.4 Ordering

Entries accepted through Composite or All Timelines Channels retain the canonical external order defined in §9.

The authored member-list order is not an event-order rule.

---

## 12. Operations and workflows at the Coordination boundary

### 12.1 Why Operations are included

This specification includes the external invocation boundary of Operations because feeder eligibility and Mandate authorization must know:

```text
the requested target Channel;
the requested Operation;
the request payload;
the target document or document-state precondition.
```

This specification does not redefine workflow execution.

### 12.2 Operation Request

A standard Operation Request has this conceptual shape:

```yaml
name: Operation Request
type: Coordination/Request
specification:
  blueId: <Blue Coordination Specification 1.0 BlueId>

operation:
  type: Text
  schema:
    required: true

channel:
  type: Text
  schema:
    required: true

document:
  description: Optional exact target document state or pure reference.

requireExactDocumentVersion:
  type: Boolean

request:
  description: Operation-specific request payload.
```

`channel` and `operation` are raw same-scope contract keys.

An omitted `request` and a present `request: {}` are distinct. The first supplies
no request value; the second supplies the exact empty-object request. Feeder
eligibility, Operation matching, Mandate validation, exact-document
preconditions, checkpoint subjects, and audit evidence MUST preserve that
distinction. A concrete Operation that requires a non-empty object must declare
that requirement explicitly, for example with a type and `minFields: 1`.

When an Operation Request is constructed by BEX, the BEX-to-Blue output boundary MUST apply the Language-equivalent context rule before the feeder observes the Message:

```text
request: null  -> request omitted
request: {}    -> present exact empty-object request
```

A feeder or Mandate resolver MUST NOT observe execution-time BEX `null`, convert it to `{}`, or allow a null sentinel to satisfy a required-request or request-pattern check. The exact admitted Blue Message is the only Coordination input.

### 12.3 Operation

The base Operation role binds:

```text
one target Channel key;
one request description or schema;
one concrete registered Operation runtime.
```

Conceptually:

```yaml
name: Operation
type: Handler
specification:
  blueId: <Blue Coordination Specification 1.0 BlueId>

channel:
  type: Text

request:
  description: Expected request shape.
```

The feeder verifies that the requested Operation exists and is bound to the requested target Channel before it treats a targeted command as eligible.

### 12.4 Sequential Workflow Operation

Sequential Workflow Operation is one standard concrete Operation runtime.

Its step order, BEX evaluation, patches, events, gas, termination, and failure semantics are defined by Blue Contracts and the exact registered runtime. This specification does not duplicate those rules.

A feeder does not need to inspect or understand workflow steps. It needs only the exact Operation header and the eligibility facts defined here.

### 12.5 Other Operation types

Another specification MAY define another concrete Operation runtime. It may be used when:

- its exact type is installed;
- it references a supported specification;
- its target-Channel and request boundary are unambiguous;
- its composition with this feeder is deterministic.

Unknown Operation types are unsupported. The feeder MUST NOT guess from fields such as `steps` or `script`.

### 12.6 General messages

A Timeline Entry message need not be an Operation Request.

General events may be accepted through Timeline Channels and delivered to registered Contracts Channel/Handler runtimes. Mandate target-operation checks apply only when the concrete message and authority semantics require them.

---

## 13. Actor Policy and direct authority

### 13.1 Attribution is not authority

An entry's actor and source identify who submitted it and how. They do not automatically authorize every Operation.

### 13.2 Actor Policy

Actor Policy is document-local eligibility policy restricting actor and source categories for an Operation.

Actor Policy may require or exclude exact actor or source types and may impose additional deterministic constraints defined by its exact runtime.

Actor Policy does not grant authority to exercise another Channel.

### 13.3 Direct operation

An Operation Request is directly eligible when:

1. the entry matches an active source Channel occurrence;
2. the requested target Channel is the same exact Channel authority binding as an accepted source Channel;
3. Actor Policy and source restrictions pass;
4. target-document and request preconditions pass;
5. required provider evidence is valid and complete.

Direct eligibility is evaluated before Mandate evidence.

When direct eligibility succeeds, the feeder MUST NOT require, resolve, or reject the entry because of an unnecessary `onBehalfOf` value.

### 13.4 Target document precondition

When `requireExactDocumentVersion` is true, `document` MUST equal the current exact target document state.

When false or absent, a host MAY allow a newer state only under one deterministic documented policy. It MUST NOT silently redirect the request to an unrelated document lineage.

A generic Timeline Entry does not require a universal `documentId` field. Candidate documents are derived from subscriptions and exact message semantics. Concrete message types or host APIs MAY provide stable managed-document targeting.

---

## 14. Mandates and delegated authority

### 14.1 Mandate is a living Blue document

A Mandate is not an opaque token or private permission row.

It has exact content, participant Channels, contracts, validation, lifecycle state, checkpoints, and processed history.

The base roles are:

```text
authority holder
    Controls the authority being delegated.

authorized actor
    Receives bounded authority.

mandate guarantor
    Confirms that the declared authority relationship is legitimate.
```

Each role is represented by an exact Timeline Channel in the Mandate document.

### 14.2 Lifecycle

The base lifecycle includes:

```text
Pending
Authority Confirmed
Active
Terminated
Failed
```

Authority confirmation and activation are separate facts.

The exact processed Mandate state at the action's `ExternalOrderKey` governs authority.

Later activation MUST NOT authorize an earlier action retroactively. Later termination MUST NOT invalidate an earlier action that was valid before termination.

Timestamp fields such as `activatedAt` and `terminatedAt` are audit state. They do not replace exact state-at-order resolution when equal timestamps across Timelines require the full tie-break key.

### 14.3 Operation Mandate

An Operation Mandate authorizes one authorized actor to invoke one named Operation through one named target Channel on one logical target document, subject to exact request constraints.

Conceptually:

```yaml
target:
  initialDocument:
    blueId: <initial target document>
  channel: ownerChannel
  operation: setPromotionalPrice

validation:
  request:
    amount:
      minimum: 100
      maximum: 300
```

The Mandate does not authorize everything the authority holder can do.

### 14.4 Mandate Authority on a Timeline Entry

Mandate Authority has this conceptual shape:

```yaml
name: Mandate Authority
type: Coordination/Authority
specification:
  blueId: <Blue Coordination Specification 1.0 BlueId>

actor:
  type: Coordination/Actor
  schema:
    required: true

initialMandateDocument:
  schema:
    required: true
```

On the containing Timeline Entry:

```text
entry.actor
    is the actor that actually submitted the entry;

onBehalfOf.actor
    is the authority holder whose Channel authority is exercised;

onBehalfOf.initialMandateDocument
    identifies the logical Mandate whose processed state must be resolved.
```

The entry MUST NOT pretend that the authority holder submitted the action.

### 14.5 Direct-first rule

When the requested target Channel is directly eligible through an accepted source Channel, no Mandate is required.

Mandate validation is required only when the actor seeks to exercise authority represented by a different target Channel or when another exact authority runtime requires it.

### 14.6 Exact-time Mandate resolution

For delegated Operation eligibility, the feeder MUST resolve the authoritative processed Mandate state immediately before the candidate action in canonical external order.

The feeder MUST ensure that the Mandate's active source surface is complete through the candidate action order and that every earlier eligible Mandate entry is terminally processed.

When Mandate and action entries share a timestamp, the Timeline and entry BlueId tie-breaks determine whether the Mandate transition is effective before or after the action.

The feeder MUST verify:

```text
exact Mandate Authority type;
logical Mandate identified by initialMandateDocument;
Mandate initialized successfully;
Mandate status Active at the action order;
entry.actor equals the authorized actor;
onBehalfOf.actor equals the authority holder;
mandate guarantor confirmation required by the Mandate type;
target logical document;
target Channel;
target Operation;
request constraints;
document-version constraints;
any exact additional restrictions defined by the Mandate subtype.
```

### 14.7 Target feeder verifies independently

A KYA provider, bank, MyOS Admin, or another intermediary MAY pre-authorize or withhold an action.

The target feeder MUST still verify the Mandate independently before delivery.

Provider admission and target feeder eligibility are two separate controls.

### 14.8 Mandate outcomes

Mandate resolution yields one of:

```text
ELIGIBLE
    Exact evidence resolves and permits the delegated action.

INELIGIBLE
    Exact evidence resolves and proves that authority is absent, inactive,
    terminated, mismatched, or outside constraints.

DEFERRED_UNAVAILABLE
    Mandate state, history, completeness, or exact resources are temporarily unavailable.

INVALID_EVIDENCE
    Mandate evidence is malformed, forged, contradictory, or fails verification.

AMBIGUOUS
    More than one incompatible Mandate lineage or target interpretation remains.
```

The feeder MUST NOT treat unavailable evidence as permission.

It SHOULD NOT treat temporary unavailability as permanent denial.

### 14.9 Source and target Channels

For a delegated Operation:

```text
source Channel
    records where the action came from and owns the successful checkpoint;

target Channel
    identifies whose Operation authority is exercised.
```

The target Channel is not checkpointed merely because its Operation ran.

### 14.10 No recursive authority chain by default

The base Mandate Authority model contains one attributed actor and one authority holder.

Recursive authority chains are outside this specification unless a future exact Authority type defines their resolution and conformance rules.

### 14.11 Provider-mediated and responder flows

An intermediary may resolve a Mandate, perform a service, and append a new attributable Operation Request or response on its own Timeline.

The target feeder validates the authority on the new entry again. A source document cannot force target processing merely by naming a Mandate.

---

## 15. Feeder source discovery and safe-window planning

### 15.1 Freeze the selected rooted views

At the beginning of a planning turn, the feeder freezes:

```text
selected root and exact forward document/occurrence views;
active source Channel occurrences;
source activation intervals;
current processor checkpoints;
current managed-document readiness;
registered type implementations;
current provider access and evidence requirements.
```

The frozen snapshot is revision-bound to this root and its actual mutable dependencies. Unrelated reverse observer registration does not invalidate its semantic identity. Required historical read positions remain exact even if their source later advances. Replan on a change to a relevant writable/activation assumption, not because an unrelated global graph counter changed.

### 15.2 Runtime selection

For every active external source type, the feeder selects an installed runtime by exact type BlueId.

A type referencing this specification but lacking an installed runtime produces:

```text
UNSUPPORTED_COORDINATION_TYPE
```

The feeder MUST NOT infer behavior from field names.

### 15.3 Candidate source groups

The feeder groups physical retrieval by exact Timeline identity. Several Channel occurrences may refer to the same Timeline.

A retrieval group does not merge their processor checkpoints or logical delivery semantics.

### 15.4 Lower bounds

For each active Channel occurrence, the semantic lower bound comes from:

```text
its current checkpoint subject;
or
an exact verified initial/catch-up cursor;
or
the beginning of the Timeline when full-history admission is required.
```

The feeder may fetch from the earliest required lower bound for one Timeline and filter per Channel afterward. For selection it compares each context’s first admissible pending input and chooses the minimum under §9. A root checkpoint at E20 cannot suppress E10 for a newly embedded view checkpointed at E5. Existing terminal/application progress handles inputs that do not produce a new direct checkpoint.

An operational global watermark MUST NOT suppress historical entries required by a newly activated imported Channel.

### 15.5 Complete window

The feeder obtains verified completeness from every required Timeline, calculates the common safe frontier, fetches exact entries within the window, verifies them, deduplicates identical physical entries, and sorts them by `ExternalOrderKey`.

### 15.6 Incremental replanning

The feeder processes entries incrementally.

After any successful state transition that may change:

```text
Timeline Channels;
actors;
Composite/All-Timelines membership;
Process Embedded topology;
Mandate source requirements;
source access;
activation intervals;
```

it recomputes the exact active source surface before selecting the next entry.

Creating the first direct member of an absent embedded collection may be one
such topology change. The host may pre-supply prospective occurrence evidence
for that exact future member, but the row remains inactive until the completed
Contracts result contains the object-compatible collection and verified member.

A precomputed remainder of an old window MUST NOT be processed blindly under stale topology. New historical bindings may introduce earlier inputs; recompute the candidate heads rather than rejecting them through a global root watermark. Do not redeliver the creating entry to new bindings through the already frozen batch.

### 15.7 Notifications and polling

Notifications MAY wake the feeder. Correctness comes from exact reads, completeness, checkpoints, and canonical order—not notification delivery.

---

## 16. Checkpoints and idempotency

### 16.1 Checkpoint shape

A scope may contain direct processor-owned checkpoint state conceptually shaped as:

```yaml
contracts:
  checkpoint:
    type: Channel Event Checkpoint
    entries:
      ownerChannel:
        subject:
          blueId: <exact accepted Timeline Entry>
```

The raw dictionary key `ownerChannel` already identifies the Channel key in that scope. The checkpoint entry MUST NOT repeat a Channel BlueId.

This specification defines no separate Checkpoint Domain value.

### 16.2 Checkpoint address

The complete checkpoint address is:

```text
exact document or managed document lineage;
exact scope occurrence;
scope activation generation;
raw Channel key.
```

Within the Blue document, scope and raw key locate the checkpoint. Host closure evidence may additionally bind the managed lineage and activation generation.

### 16.3 Subject

For every Timeline-derived Channel defined by this specification, the checkpoint subject is the complete exact accepted Timeline Entry, normally stored as a pure BlueId reference.

The subject MUST NOT be replaced by a copied partial projection such as:

```yaml
semantics: ...
timestamp: ...
timelineBlueId: ...
entryBlueId: ...
```

Those values already belong to the exact Timeline Entry. Parsed positions MAY be cached outside Blue state as rebuildable optimization data.

### 16.4 Why no Channel BlueId is stored

The raw checkpoint key identifies the current same-scope Channel. The processor owns checkpoint lifecycle.

Whenever a successful transition removes that key or changes the exact effective Channel's source semantics, the processor MUST remove or reset the checkpoint in the same tentative transition.

A change requiring reset includes at least:

```text
Timeline binding change;
actor binding change;
Timeline Channel runtime type change;
Composite/All-Timelines membership change;
source-newness semantics change;
scope occurrence or activation-generation change.
```

A verified processor history proves that retained checkpoints were produced under the current Channel lineage. A raw imported snapshot without such provenance MUST NOT silently reuse authored checkpoint state; admission must verify it, migrate it exactly, reset it, or reject the import.

### 16.5 Virtual empty state

An absent checkpoint marker, absent Channel key, retired key, reset key, or unverified imported entry is virtual empty state.

No empty checkpoint is written before a source occurrence is accepted, new, successfully processed, and committed.

### 16.6 Timeline Channel newness

For a direct Timeline Channel:

1. resolve the previous subject Timeline Entry when present;
2. verify it belongs to the exact current Timeline and accepted Channel lineage;
3. compare the candidate timestamp with the previous subject timestamp;
4. treat the same exact entry as stale;
5. treat a lower timestamp as stale or invalid ordered input;
6. treat a greater timestamp as new.

If the previous exact subject cannot be resolved, processing returns `NEEDS_RESOURCES`; it does not trust a copied timestamp cache as semantic authority.

### 16.7 Composite and All Timelines newness

For Composite and All Timelines Channels, the subject remains one exact Timeline Entry.

The registered runtime compares the previous and candidate `ExternalOrderKey` values under the exact current member surface.

A membership change requiring historical catch-up or reset is handled through activation and checkpoint lifecycle, not by embedding member metadata into the subject.

### 16.8 Multiple matching source occurrences

Several matching source occurrences may coalesce into one logical business delivery only under a deterministic Contracts grouping rule.

Every checkpoint-owning source occurrence accepted as part of the successful delivery writes its own key to the exact same entry subject.

A Composite or All Timelines Channel owns its own checkpoint key. Its child Timeline Channels remain independent when used directly elsewhere.

### 16.9 Atomic write

A checkpoint is written only after:

```text
source acceptance and newness;
all matching Handler and Operation work;
all caused patches and document updates;
all caused internal events;
successful lifecycle or termination work;
final validation;
successful commit of the complete processor result.
```

The checkpoint and every processor effect commit together.

Failure before commit writes no checkpoint.

### 16.10 Source ownership in delegated calls

For delegated Operations, the source Channel that accepted the entry owns the checkpoint.

The target Channel does not advance.

### 16.11 Feeder terminal progress

`NO_MATCH`, `REJECTED`, `WITHHELD`, and other feeder-terminal outcomes may occur without a processor call and therefore without a document checkpoint.

The host MUST retain a durable terminal progress record so the same physical entry is not reevaluated forever for the same frozen target context.

The feeder ledger is operational/audit state, not a substitute for processor checkpoints.

### 16.12 Retry

After uncertain commit:

- if the new authoritative state committed, its checkpoint makes the completed source occurrence stale;
- if the old state remains, retry recomputes against unchanged state;
- if another revision is current, the feeder replans from authoritative state.

---

## 17. Feeder eligibility algorithm

### 17.1 Overview

For each candidate Timeline Entry and candidate target document or affected closure, the feeder performs the following steps in order.

### 17.2 Step 1 — verify provider evidence

Verify:

```text
exact Timeline type and identity;
exact Timeline Entry BlueId;
predecessor integrity;
strict Timeline timestamp order;
actor attribution;
source attribution when required;
read authorization;
completeness evidence covering the candidate window.
```

Invalid provider evidence MUST NOT reach Contracts.

### 17.3 Step 2 — derive source Channels

From the frozen exact source surface, derive every active Timeline-derived source Channel occurrence that accepts the exact entry.

If none accepts, the candidate target result is `NO_MATCH`.

### 17.4 Step 3 — checkpoint classification

For each checkpoint-owning source occurrence, classify the entry as new or stale under §16.

If every relevant source occurrence is stale, the result is `STALE`.

### 17.5 Step 4 — classify message

If the message is an Operation Request, continue with §17.6.

Otherwise, apply the exact registered external Channel and Handler eligibility law. Mandate checks are required only when the concrete message or authority semantics require delegated authority.

### 17.6 Step 5 — resolve target Operation

For an Operation Request:

1. resolve the requested target Channel key in the exact target scope;
2. resolve the requested Operation key;
3. verify that the Operation is bound to the requested Channel;
4. verify the optional target-document precondition;
5. verify request shape needed for feeder eligibility;
6. apply Actor Policy and source restrictions.

A missing targeted document, target Channel, or Operation is `REJECTED`, not processor `NO_MATCH`.

### 17.7 Step 6 — direct-first authority

If the requested target Channel is directly eligible through an accepted source Channel, classify:

```text
ELIGIBLE_DIRECT
```

and do not resolve Mandate evidence.

### 17.8 Step 7 — delegated authority

When source and target authority differ, require a supported `onBehalfOf` value.

For Mandate Authority, run §14.6.

Classify:

```text
ELIGIBLE_MANDATE
INELIGIBLE
DEFERRED_UNAVAILABLE
INVALID_EVIDENCE
AMBIGUOUS
```

### 17.9 Step 8 — preserve original entry

The feeder may resolve references and construct verified delivery evidence. It MUST NOT rewrite the Timeline Entry, replace the actor, replace the source, synthesize another timestamp, remove Mandate Authority, or create a hidden authorized event.

The original exact Timeline Entry is the processor cause.

### 17.10 Step 9 — invoke Contracts

Invoke the exact supported Contracts processing boundary with:

```text
selected-root exact document/view graph and required RCP-1 context;
original exact Timeline Entry;
frozen accepted source occurrences/logical deliveries;
exact required resources;
revision and graph evidence;
remaining gas/limit policy;
registered runtimes.
```

### 17.11 Step 10 — publish

On a committing result, atomically publish the complete required **selected-root** unit under RCP-1 §8:

```text
new document or closure state;
processor checkpoints;
public Root events;
subscription/source-surface changes;
graph or managed-document changes;
input progress;
commit companion/receipt.
```

On noncommitting result, publish no tentative processor state. Already committed independent source receipts remain intact. A reverse index may schedule another root’s required work; that root does not retroactively join this publication.

### 17.12 Recompute before next entry

After every commit or topology-affecting terminal result, reload authoritative state and replan the source surface before selecting the next entry.

---

## 18. Processor boundary

### 18.1 Processor inputs

The processor receives exact Blue state and the original eligible Timeline Entry.

It may also receive exact platform evidence structures defined by Blue Contracts, such as delivery snapshots, resources, managed-document closure evidence, and gas policy.

### 18.2 Excluded ambient inputs

The processor MUST NOT receive an unbound private object saying:

```text
Mandate approved
provider complete
current wall-clock time
user is admin
skip verification
```

All semantic evidence is exact, revision-bound, and auditable.

### 18.3 Internal reactions are not Timeline Entries

Lifecycle events, Triggered Events, Embedded events, Document Updates, initialization work, managed-revision work, and cyclic closure work are processor-managed causes.

They MUST NOT be appended to a provider Timeline or receive invented provider timestamps.

### 18.4 Public output

Only events emitted at the application-visible Root boundary become public Root output under Blue Contracts.

Internal embedded or Mandate events may cause ancestor reactions without automatically becoming public.

---

## 19. Activation, initialization, and historical catch-up

### 19.1 Explicit birth versus source materialization

A process or Channel **explicitly and semantically born** by entry E has no history before E; its ordinary live interval starts strictly after that exact order, as in §10.6. Processor-managed initialization is not a provider Timeline fact.

Discovering or loading an authored/retained source is not, by itself, such a birth. RCP-1 §7 requires its declared checkpoints and explicit history basis to determine eligible inputs, regardless of whether the host already stored the lineage. With no explicit newborn-from-cause admission, the embedding operation's time MUST NOT silently discard otherwise required earlier source inputs.

Keep explicit full-history, retained-position and specified activation policies distinct. A verified birth/admission context is established at that boundary, not chosen by a later worker or cache lookup. Physical deletion or eviction is not semantic reset. Temporary NeedsResources suspension must not make another root establish a different history for identical semantic inputs.

### 19.2 Imported existing process

When a host activates an existing process whose established frontier `F` precedes the attachment order `T`, it MUST NOT ignore eligible entries in `(F, T)` and declare the containing document ready.

The host creates a catch-up barrier and obtains exact completeness for every required Timeline through `T`.

### 19.3 Catch-up entry semantics

Every historical Timeline Entry:

- retains its original exact entry value and BlueId;
- retains its original `ExternalOrderKey`;
- is processed as a separate exact feeder/Contracts cause;
- is additionally associated in host audit with the attachment or admission that made it newly relevant;
- does not receive a synthetic timestamp;
- is processed before later live entries overtake the barrier.

### 19.4 Checkpoints do not prove completeness

A checkpoint subject proves the last successful input consumed by one Channel. It does not prove that no missing provider entry exists before a frontier.

Imported progress requires trusted managed-history and completeness evidence. A raw Blue document with checkpoint-looking fields is not sufficient by itself.

### 19.5 Readiness

A document or affected managed closure is ready through frontier `T` only when:

```text
all required initialization is complete;
all active Timeline sources are complete through T;
all eligible entries through T are terminally processed;
all required imported histories are caught up;
all required embedded or managed-document revisions are applied;
the exact source surface matches the authoritative state;
no required catch-up barrier through T remains.
```

The feeder MUST NOT deliver a later dependent entry while readiness through an earlier required frontier is incomplete. This is forward-dependency readiness. An unrelated incoming observer’s pending application does not block its source. Step completion is distinct from having drained every pending input; both are distinct from global idleness.

### 19.6 Source-surface changes during catch-up

A historical entry may add or remove Timeline Channels or embedded managed documents. The feeder recomputes the source surface after every historical commit and continues under the same outer cutoff.

### 19.6a LIVE versus retained-application classification (RCP-1)

RCP-1 CAUSE-01..03 bind the historical join interval at the attachment's logical boundary. They apply before inspecting storage warmth or independently available receipts. An already-live receiving view's original pending external input remains LIVE even if another worker has committed the child result. A new historical occurrence consumes its exact frozen interval, then joins through the existing terminal representation/activation rules; later physical source progress does not silently lengthen that interval or select a cheaper operation kind. OWN-01..06 and ID-01..05 define the resulting write roles and exact idempotency context. No host may choose these by a database-row existence branch.

### 19.7 Catch-up order

Historical advancement uses the same outer next-input loop, not a separate business-processing algorithm. Each selected occurrence retains its own progress and exact position. A6/B8 are lineage-local positions, not globally ordered timestamps. Source-transition and representation predecessor constraints remain required in addition to their original cause order.

When several imported sources are catching up together, the feeder selects the next exact required entry using the same canonical `ExternalOrderKey` used for live processing, then applies dependency ordering required by Blue Contracts.

It MUST NOT arbitrarily finish one child's complete history while an earlier eligible entry from another required source waits.

### 19.8 Historical representation positions (adopted dependency of the RCP-1 draft)

This subsection is mandatory for the RCP-1 draft.2 candidate under its new exact specification/profile binding. It is not retroactively applicable to an unamended historical runtime. It applies with the adopted candidate Contracts §§2.3a and 7.5a. Existing `ManagedRevisionCause` retains its
one-epoch rule, and existing managed-epoch receipts remain immutable.

A same-epoch exact-identity change permitted by Contracts §5.7.1 MUST be
retained as an ordered representation position by the same atomic publication
that commits the change. The position binds the immutable numbered-epoch
anchor, exact predecessor position, complete original input and result,
source transition receipt, and commit companion. The host MUST verify durable
membership and the original narrow finalizer-only classification. Neither
equal endpoints, equal epochs, nor a newly computed position hash supplies
that authority. Repeated identities at different positions remain distinct.

The host selects one representation transition as separately identified work.
Its identity binds the occurrence and activation generation, exact source
epoch and predecessor/successor positions, captured goal, original source
evidence, consumer committed head, graph generation, and existing catch-up
barrier. Its type MUST distinguish it from numbered-epoch application work.
The proposed backward-compatible Java envelope retains the existing numbered
epoch constructor. A representation work carries a separately typed
`ManagedRepresentationCause`, uses identity domain
`blue-coordination-managed-representation-application-work/1.0`, and binds
`representationCauseIdentity` in addition to every existing work fence.
The source receipt is the numbered anchor at N; its expected numbered cursor
remains N+1 throughout the positional step. Ordinary work retains its original
domain and source-epoch interpretation.

The corresponding application receipt uses domain
`blue-coordination-managed-representation-application-receipt/1.0` and additionally
binds `representationCauseIdentity` and the resulting representation cursor.
Its numbered `resultingSourceCursor` remains N+1. The cursor is absent only
after the separately proved terminal activation. SDK work exposes the exact
before/after positions, goal, next-revision anchor, endpoints and cause identity.
The new domains and schemas require explicit release compatibility bindings.

For an intermediate chain, the captured goal is the exact predecessor of the
next immutable numbered receipt. For a representation-only tail, the goal is
the exact terminal position captured when scheduling the chain. One work
invokes Contracts exactly once with one `ManagedRepresentationCause`. It MUST
NOT hide several positions inside one numbered-epoch work or advance the
numbered historical cursor. Missing proof blocks the named work without
declaring readiness or falling back to an ambient current snapshot.

The resulting consumer state, representation cursor, affected graph and
containing references, actual gas and Document Update effects, next-work
selection, and complete application receipt MUST commit atomically. The
application receipt binds the selected work, complete Contracts invocation
and result, source position evidence, before/after occurrence, and commit
companion. An exact committed-work retry reconciles that receipt; it does not
repeat the source or downstream effects. Unknown or partial publication
retains ordinary recovery behavior. Restart MUST reconstruct the identical
position and next-work identity from durable evidence before reporting ready.

Representation work inherits the existing barrier's attachment order and
outer timestamp cutoff. It has no synthetic Timeline timestamp. It cannot
overtake required earlier work, admit future source entries through an older
root frontier, or discard ordinary later source revisions. The terminal
Contracts action reconciles and activates once at the captured goal; newly
created representation-only feedback does not enlarge that captured chain.

Managed-History and Durable conformance additionally require authenticated
multi-position and repeated-identity traversal, missing/forged/reordered proof
rejection, source-head preservation, unchanged saved-original graph owners,
per-invocation gas rollback, response-loss reconciliation, and fresh durable
restart. Processor-only fixtures do not establish these host claims.

---

## 20. Other Channel specifications and extensibility

### 20.1 Exact type classification

The feeder classifies a Channel only by its exact effective type and installed runtime role.

Built-in processor-managed Channel types such as Lifecycle Event Channel and Triggered Event Channel are not external feeder sources.

Timeline Channel, Composite Timeline Channel, All Timelines Channel, and conforming subtypes are external sources governed by this specification.

### 20.2 Channels defined by another specification

Another specification MAY define another external Channel family.

A feeder may support it only when an exact installed module defines:

```text
source discovery;
external evidence verification;
completeness/finality;
canonical order;
source matching;
checkpoint subject and newness;
eligibility;
composition with other active external sources.
```

### 20.3 Deterministic composition

A document containing Timeline Channels from this specification and external Channels from another specification can be processed as one deterministic history only when an exact supported composition rule defines a common complete window and total order.

Without that rule, the host MUST NOT silently ignore the other external source or process only whichever notification arrives first.

It reports:

```text
UNSUPPORTED_EXTERNAL_SOURCE
```

or keeps the document non-ready.

### 20.4 Unknown unrelated contracts

A contract not registered as an external source is outside feeder source discovery. It may still be interpreted by Blue Contracts if its exact runtime is installed.

The feeder MUST NOT classify unknown contracts as external merely because they contain fields named `timeline`, `actor`, or `channel`.

### 20.5 Extension conformance

An implementation may support additional specifications. Its receipts SHOULD record the exact specification and type identities governing each external source.

Supporting extensions MUST NOT change the semantics of Coordination Timeline sources while claiming conformance to this specification.

---

## 21. Entry decisions and public dispositions

### 21.1 Provider acquisition result

Provider acquisition uses the outcomes in §8.4.

### 21.2 Feeder eligibility result

For one candidate target, the feeder uses:

```text
ELIGIBLE_DIRECT
ELIGIBLE_MANDATE
NO_MATCH
STALE
REJECTED
WITHHELD
DEFERRED_UNAVAILABLE
INVALID_EVIDENCE
UNSUPPORTED
```

### 21.3 Meaning

**ELIGIBLE_DIRECT**
Direct source authority permits processor delivery.

**ELIGIBLE_MANDATE**
Exact Mandate authority permits processor delivery.

**NO_MATCH**
No active source Channel accepts the entry for this candidate.

**STALE**
Every relevant checkpoint-owning source occurrence has already consumed this or a later subject.

**REJECTED**
A targeted document, Channel, Operation, request, or exact-state precondition is invalid or absent.

**WITHHELD**
Evidence resolves and proves that the action is ineligible, including inactive Mandate, Actor Policy denial, request constraint failure, or authority mismatch.

**DEFERRED_UNAVAILABLE**
Required entry, completeness, access, Mandate state, or exact resource is temporarily unavailable.

**INVALID_EVIDENCE**
Trusted evidence is malformed, forged, contradictory, or fails verification.

**UNSUPPORTED**
A required exact type, specification, external source, or runtime is not supported.

### 21.4 Processor result

Blue Contracts returns its own exact committing or noncommitting status, gas, diagnostic, and evidence.

### 21.5 Public entry disposition

A public host MAY aggregate independent target results into:

```text
APPLIED
NO_MATCH
STALE
REJECTED
WITHHELD
NEEDS_RESOURCES
GAS_LIMIT_EXCEEDED
PORTABLE_LIMIT_EXCEEDED
BLOCKED
FAILED
MIXED
```

`MIXED` means one physical Timeline Entry produced different terminal outcomes in independent target closures. It does not erase successful commits.

### 21.6 Terminality

`NO_MATCH`, `STALE`, `REJECTED`, `WITHHELD`, and successful application are terminal for the frozen target context.

`DEFERRED_UNAVAILABLE` is nonterminal and preserves the exact entry for reevaluation.

`INVALID_EVIDENCE` normally blocks or quarantines the affected source/target until corrected.

---

## 22. Failure, replay, and recovery

### 22.1 Unknown is not allowed

Missing completeness, unresolved Mandate state, missing exact checkpoint subject, unavailable provider history, or unsupported external source MUST NOT be treated as permission or absence.

### 22.2 Unknown is not permanent denial

Temporary unavailability SHOULD defer rather than permanently discard an otherwise potentially valid entry.

### 22.3 Duplicate delivery

Provider notifications, network retries, host retries, and crash recovery may present the same entry repeatedly.

Exact entry identity, feeder terminal progress, and source Channel checkpoints make successful behavior idempotent.

### 22.4 Invalid Timeline history

The feeder rejects or blocks on:

```text
broken predecessor chain;
duplicate timestamp in one Timeline;
timestamp not greater than predecessor;
entry Timeline mismatch;
entry BlueId mismatch;
completeness contradiction;
entry placed behind a closed frontier.
```

### 22.5 Provider unavailability

When any active required Timeline cannot provide complete history, the common safe window cannot advance beyond the last verified frontier.

Unrelated documents or disconnected closures MAY continue independently.

### 22.6 Mandate unavailability

A delegated entry waits when exact Mandate state cannot yet be resolved. It is not delivered under presumed authority.

### 22.7 Processor failure

A noncommitting processor result publishes no checkpoint, state change, public event, or subscription change.

Retry runs against authoritative unchanged state unless a commit companion proves the transition already committed.

### 22.8 Crash after commit

The host SHOULD retain an exact commit companion binding:

```text
input entry;
expected prior state;
result state;
checkpoint writes;
public events;
source-surface changes;
commit transaction identity.
```

After response loss, the host reconciles from the companion without rerunning committed processing.

### 22.9 Poison entries

A deterministic failing entry MUST NOT be retried forever without visible status and policy.

The host exposes failure, retry count, diagnostic identity, and operator action. A later canonical entry for the same dependent target MUST NOT overtake an earlier required nonterminal entry.

---

## 23. Audit, security, and privacy

### 23.1 Causal audit chain

A useful audit connects:

```text
exact Timeline and provider implementation;
verified completeness frontier and proof;
exact Timeline Entry BlueId;
actor and source attribution;
matching source Channel occurrences;
Operation Request and target Operation, when applicable;
Mandate state and validation decision, when applicable;
current target state and precondition;
processor result, gas, and checkpoints;
new document or closure BlueIds;
public Root event occurrences;
commit companion.
```

### 23.2 Preserve attribution

Delegated authority MUST NOT replace the actual entry actor with the authority holder.

### 23.3 Least disclosure

A feeder SHOULD resolve only the exact Mandate state, provider proof, document path, Channel header, Operation header, request fields, and resources needed for the decision.

Pure BlueId references SHOULD be used where full materialization is unnecessary.

### 23.4 No authority from descriptive fields

Names, display labels, account strings, operation text, or request claims do not grant authority unless an exact registered type and verified evidence give them that meaning.

### 23.5 Provider accountability

This specification verifies protocol facts. It cannot prove that a provider's real-world attribution or statement is truthful.

Provider choice remains an application trust decision. Blue makes the provider and evidence explicit so that decision is inspectable.

---

## 24. Conformance claims

### 24.1 Coordination Core

A Coordination Core implementation proves:

```text
exact specification/type binding;
Timeline and Entry validation;
append-once storage;
Timeline Channel matching;
checkpoint subject = exact Timeline Entry;
direct Operation eligibility;
original-entry processor delivery;
no-match, stale, rejection, retry, and audit behavior.
```

It may support one closed-world provider.

### 24.2 Multi-Timeline Completeness

This claim additionally proves:

```text
several independent Timelines;
binding complete-before evidence;
common safe frontier;
cross-Timeline total order;
Composite and All Timelines source discovery;
topology-change replanning.
```

### 24.3 Mandate-Aware Coordination

This claim additionally proves:

```text
Mandate lifecycle processing;
exact state-at-action-order resolution;
direct-first behavior;
source/target Channel distinction;
request and document constraints;
deferred unavailable evidence;
withheld ineligible authority;
independent target-feeder verification.
```

An implementation that does not claim Mandate awareness MUST fail closed on delegated authority.

### 24.4 Managed-History Coordination

This claim additionally proves:

```text
verified imported frontiers;
historical catch-up;
initialization barriers;
source-surface recomputation;
no overtaking;
ready-through semantics;
original source order plus application-order audit.
```

### 24.5 Durable Coordination

This claim additionally proves fresh-process recovery from durable stores, exact commit reconciliation, provider access recovery, and no duplicate processing after crashes.

---

## 25. Required conformance fixture families

A final release MUST include executable fixtures covering at least the following.

### 25.1 Specification and type binding

1. Every canonical Coordination type references the exact specification BlueId.
2. Unknown Coordination type is rejected.
3. Missing specification reference is rejected in portable conformance mode.
4. Mixed Coordination specification versions are rejected.
5. Structural lookalike types are not accepted.

### 25.2 Timeline and entry integrity

6. First entry omits `prevEntry`.
7. Later entry references the exact previous entry.
8. Duplicate timestamp in one Timeline is rejected.
9. Backdated timestamp is rejected.
10. Broken predecessor is rejected.
11. Entry Timeline mismatch is rejected.
12. Exact duplicate append is idempotent.
13. One physical entry is stored once while affecting several targets.
14. Provider-specific extra fields remain exact and do not alter base semantics.

### 25.3 Completeness and order

15. Binding complete-before promise closes history.
16. Current-storage scan without future promise is insufficient.
17. Common frontier uses the slowest required Timeline.
18. Equal timestamps use Timeline and entry BlueId tie-breaks.
19. Notification arrival order has no effect.
20. `COMPLETE_EMPTY` differs from unavailable and not found.
21. Finality-backed provider exposes only finalized history.
22. Entry behind closed frontier is rejected.

### 25.4 Timeline Channels

23. Exact Timeline and actor match succeeds.
24. Message claim without provider-backed actor match fails.
25. Same Timeline bound to several Channels does not duplicate logical Operation execution.
26. Same raw key in different scopes has independent checkpoints.
27. Composite member match produces one composite delivery.
28. All Timelines follows current same-scope source membership.
29. Channel introduced by entry does not process the creating entry.
30. Unsupported Timeline-derived Channel blocks rather than being ignored.
31. Unknown nonexternal contract is not guessed as a source.

### 25.5 Operations

32. Direct source and target Channel succeeds without Mandate lookup.
33. Missing target document is rejected precisely.
34. Missing target Channel is rejected precisely.
35. Missing Operation is rejected precisely.
36. Operation bound to another Channel requires authority.
37. Exact-document precondition succeeds on exact state.
38. Exact-document precondition rejects stale state.
39. General non-Operation event follows its registered Handler semantics.
40. Sequential Workflow execution remains Contracts-owned.

### 25.6 Mandates

41. Pending Mandate does not authorize.
42. Authority Confirmed but inactive Mandate does not authorize.
43. Active Mandate authorizes only its exact actor, holder, target, Channel, Operation, and request range.
44. Later activation does not authorize earlier action.
45. Later termination does not erase earlier valid action.
46. Same-timestamp Mandate/action order follows full `ExternalOrderKey`.
47. Direct eligibility ignores unnecessary Mandate evidence.
48. Unavailable Mandate state defers.
49. Inactive, terminated, mismatched, or out-of-range Mandate withholds.
50. Forged Mandate evidence blocks.
51. Target feeder verifies independently of provider preauthorization.
52. Source Channel owns successful delegated checkpoint.
53. Target Channel checkpoint remains unchanged.
54. Provider-mediated target entry is independently reverified.

### 25.7 Checkpoints

55. Checkpoint entry contains only exact subject under raw Channel key.
56. No Checkpoint Domain is required.
57. Direct Timeline Channel subject is the exact Timeline Entry.
58. Missing subject resource yields `NEEDS_RESOURCES`.
59. Repeated committed entry is stale.
60. Failed processing writes no checkpoint.
61. Successful no-business-change consumption may still advance checkpoint.
62. Channel removal removes checkpoint entry.
63. Timeline or actor binding change resets checkpoint.
64. Composite membership change resets or catches up under exact activation rules.
65. Remove and re-add starts virtual empty state.
66. Unverified imported checkpoint is not trusted.
67. Two accepted source occurrences write independently when required.

### 25.8 Feeder and topology

68. Notifications are wake hints only.
69. Candidate documents are derived from the source index, not caller-supplied recipient lists.
70. Source surface is recomputed after a topology-changing commit.
71. Captured old-window suffix is not blindly processed.
72. One disconnected target failure does not erase successful independent target commits.
73. Later entry does not overtake earlier nonterminal work for the same target.
74. No-match is terminal feeder progress without processor checkpoint.
75. Withheld denial is terminal feeder progress without processor checkpoint.
76. Deferred evidence retries the exact same entry.
76a. An absent declared embedded collection yields zero source occurrences without invalidating the document.
76b. A present empty collection and an absent collection produce the same external source-surface cardinality but retain distinct containing-document BlueIds.
76c. Incomplete collection evidence never becomes successful absence.
76d. A prospective member under an absent collection remains inactive until the exact collection/member result commits.
76e. An omitted Operation Request `request` and a present empty-object `request: {}` remain distinct through feeder matching, Mandate validation, checkpoint subject construction, and audit output.
76f. A BEX-produced Operation Request with `request: null` is admitted without a `request` member and behaves exactly like the omitted-request form; it MUST NOT behave like `request: {}` or satisfy a required non-empty request.

### 25.9 Historical catch-up

77. Newly born source receives no pre-creation history.
78. Imported source catches up from verified frontier to attachment order.
79. Checkpoint alone does not prove frontier completeness.
80. Historical entries retain original BlueIds and order.
81. Historical entries are processed one per invocation/commit boundary unless Contracts explicitly batches equivalent work.
82. New source discovered during catch-up is recursively completed.
83. Live entry after cutoff cannot overtake catch-up.
84. Several imported sources merge by canonical order.
85. Future supplied state is rejected or explicitly migrated.

### 25.10 Other specifications

86. Supported other external Channel spec composes through an exact common-order rule.
87. Unsupported other external source prevents false readiness.
88. Other processor-managed internal Channels, including `Embedded Node Channel` and `Embedded Collection Event Channel`, do not enter feeder completeness.
89. Extension support is recorded by exact type/specification identity.

### 25.11 Recovery and audit

90. Crash before commit safely retries.
91. Crash after commit reconciles without rerunning.
92. Audit links provider proof through resulting Root.
93. Actor attribution remains the actual submitter under Mandate.
94. Public Root events preserve occurrence order and multiplicity.

---

## 26. Worked example: direct operation

A document contains:

```yaml
contracts:
  customerChannel:
    type: Coordination/Timeline Channel
    timeline:
      type: MyOS/MyOS Timeline
      timelineId: myos:alice:orders
    actor:
      type: MyOS/Principal Actor
      accountId: alice

  confirmBouquet:
    type: Coordination/Sequential Workflow Operation
    channel: customerChannel
    request:
      orderId:
        type: Text
```

Alice's provider appends:

```yaml
type: Coordination/Timeline Entry
timeline:
  type: MyOS/MyOS Timeline
  timelineId: myos:alice:orders
prevEntry:
  blueId: <previous>
timestamp: 1785312001000000
actor:
  type: MyOS/Principal Actor
  accountId: alice
source:
  type: Coordination/Browser Session
  uiSessionNonce: session-71
message:
  type: Coordination/Operation Request
  channel: customerChannel
  operation: confirmBouquet
  request:
    orderId: order-204
```

The feeder:

1. verifies the Timeline Entry and completeness;
2. matches `customerChannel`;
3. verifies the target Operation is bound to `customerChannel`;
4. classifies direct authority;
5. passes the original entry to Contracts;
6. commits the result and:

```yaml
entries:
  customerChannel:
    subject:
      blueId: <the exact Alice Timeline Entry>
```

No Mandate is resolved.

---

## 27. Worked example: delegated agent operation

The target document contains:

```yaml
contracts:
  ownerChannel:
    type: Coordination/Timeline Channel
    timeline:
      type: MyOS/MyOS Timeline
      timelineId: myos:alice:owner
    actor:
      type: MyOS/Principal Actor
      accountId: alice

  pricingAgentChannel:
    type: Coordination/Timeline Channel
    timeline:
      type: AgentTrust/KYA Timeline
      timelineId: agenttrust:alice:pricing-agent
    actor:
      type: AgentTrust/Verified Agent Actor
      agentId: alice-pricing-agent

  setPromotionalPrice:
    type: Coordination/Sequential Workflow Operation
    channel: ownerChannel
```

The agent entry contains:

```yaml
actor:
  type: AgentTrust/Verified Agent Actor
  agentId: alice-pricing-agent

onBehalfOf:
  type: Mandate/Mandate Authority
  actor:
    type: MyOS/Principal Actor
    accountId: alice
  initialMandateDocument:
    blueId: <initial Operation Mandate>

message:
  type: Coordination/Operation Request
  channel: ownerChannel
  operation: setPromotionalPrice
  document:
    blueId: <current target state>
  requireExactDocumentVersion: true
  request:
    amount: 175
```

The feeder:

1. accepts the entry through `pricingAgentChannel`;
2. sees that the target is `ownerChannel`;
3. resolves the exact Mandate state before the action in canonical order;
4. verifies actor, authority holder, target document, Channel, Operation, amount, and state precondition;
5. passes the unchanged entry to Contracts;
6. checkpoints `pricingAgentChannel`, not `ownerChannel`.

---

## 28. Worked example: two providers and one safe order

A Root observes:

```text
Alice / MyOS complete before:      10:00:00.200000
Bob / RiverBank complete before:   10:00:00.180000
Permit / Government before:        10:00:00.250000
```

The common safe frontier is:

```text
10:00:00.180000
```

Suppose known entries are:

```text
Bob     10:00:00.090000 confirm dinner
Alice   10:00:00.100000 confirm bouquet
Permit  10:00:00.170000 permit confirmed
Alice   10:00:00.190000 request capture
```

The first three are safe. The last Alice entry waits because the RiverBank frontier is still earlier.

The feeder processes the first three in timestamp order, using Timeline and entry BlueIds only for ties. It recomputes the source surface after every commit.

---

## 29. Worked example: a Channel from another specification

A document contains Coordination Timeline Channels and one exact external Channel type defined by another specification.

A conforming feeder may proceed only when it has an installed module that provides:

```text
complete external evidence;
a canonical order compatible with `ExternalOrderKey` or an exact composition rule;
source matching;
checkpoint and newness;
eligibility;
recovery semantics.
```

If that module is absent, the feeder reports:

```text
UNSUPPORTED_EXTERNAL_SOURCE
```

It does not ignore the Channel and falsely claim the document is complete.

A Triggered Event Channel or Lifecycle Event Channel is different: its exact type marks it as processor-managed, so it is not an external feeder source.

---

## 30. Required alignment with Blue Contracts

An implementation claiming conformance to this specification MUST use a Blue Contracts release whose Channel Event Checkpoint model is compatible with §16.

The checkpoint type is conceptually:

```yaml
name: Channel Event Checkpoint
entries:
  type: Dictionary
  valueType:
    subject:
      description: Exact checkpoint subject, normally a pure Timeline Entry reference.
```

The older `domain + subject` checkpoint form is not part of this specification.

No other Contracts processing rule is changed by this statement. Workflow execution, lifecycle, internal events, gas, closure processing, and atomic processor publication remain governed by Blue Contracts.

---

## Appendix A. Normative feeder pseudocode

The provider/authority machinery below is outside `PROCESS`. `state` contains exact selected views and existing progress; it is not a map that silently replaces every historic child with its current head.

```text
FEED(selectedRoot):
  loop:
    state = LOAD_ROOTED_STATE_AND_PROGRESS(selectedRoot)
    bindings = DISCOVER_FORWARD_BINDINGS(state)
    REQUIRE_EXACT_SUPPORTED_RUNTIMES(bindings)

    # Reuse physical Timeline reads, not semantic checkpoint state.
    evidence = OBTAIN_REQUIRED_COMPLETE_EVIDENCE(bindings)
    if evidence is unavailable:
      RETAIN_TYPED_WAIT_WITHOUT_PROCESSOR_MUTATION()
      return WAITING_FOR_EVIDENCE
    if evidence is invalid:
      return BLOCKED_INVALID_EVIDENCE

    heads = NEXT_ADMISSIBLE_HEAD_PER_BINDING(bindings, evidence)
    # Include existing retained applications with their predecessor order;
    # do not finish one child's entire history before inspecting others.
    next = CANONICAL_MINIMUM_READY_HEAD(heads)
    if next is absent:
      VERIFY_AND_REPORT_READY_THROUGH_ESTABLISHED_BOUNDARY(state, evidence)
      return IDLE_AT_THAT_BOUNDARY

    decision = FREEZE_ELIGIBILITY_RECIPIENTS_AND_ROOT_CONTEXT(state, next)
    if decision is feeder-terminal:
      COMMIT_TERMINAL_FEEDER_PROGRESS_WITHOUT_FAKE_CHECKPOINT(decision)
      continue
    if decision requires evidence:
      RETAIN_TYPED_WAIT(next, decision)
      return WAITING_FOR_EVIDENCE
    if decision is invalid or unsupported:
      return THE_EXACT_DEFINED_FAILURE(decision)

    result = PROCESS_ONE_ADMITTED_INPUT(state, next, decision)
    if result commits:
      ATOMICALLY_INSTALL_ROOTED_RESULT_AND_PROGRESS(result)
      RETAIN_DERIVABLE_NOTIFICATIONS_FOR_OTHER_ROOTS(result)
    else:
      RETAIN_DEFINED_FAILURE_OR_WAIT(result)
      # A required retained application remains pending. A terminal live
      # input follows its existing poison/disposition policy, not an
      # endless automatic retry and not a fabricated successful checkpoint.
      APPLY_EXISTING_RESULT_PROGRESS_RULES(result)

    # Immediate processor work is finished. Recompute affected topology,
    # selected positions and candidate heads before another input.
```

`CANONICAL_MINIMUM_READY_HEAD` is the prescribed minimum across complete external projections and exact retained predecessor obligations. It does not use worker readiness to bypass an earlier required unavailable input. A changed topology invalidates affected cached head selections.

The simple scan and any optimized heap/index implementation MUST agree on selected causes, frozen recipients, exact reads, results, gas/failure and publication. RCP-1 and its fixtures define root isolation; a global reverse-cohort index is never substituted for `DISCOVER_FORWARD_BINDINGS`.

## Appendix B. Normative checkpoint pseudocode

```text
CLASSIFY_NEWNESS(scope, channelKey, candidateEntry):

  channel = RESOLVE_CURRENT_EFFECTIVE_CHANNEL(scope, channelKey)
  checkpoint = scope.contracts.checkpoint.entries[channelKey]

  if checkpoint absent:
    return NEW

  previous = RESOLVE_EXACT(checkpoint.subject.blueId)
  if previous unavailable:
    return NEEDS_RESOURCES

  if channel no longer accepts previous under the same verified source lineage:
    return NEW_WITH_CHECKPOINT_RESET

  if channel is direct Timeline Channel:
    if previous.timeline != channel.timeline:
      return NEW_WITH_CHECKPOINT_RESET
    if candidateEntry.blueId == previous.blueId:
      return STALE
    if candidateEntry.timestamp <= previous.timestamp:
      return STALE_OR_INVALID_ORDER
    return NEW

  if channel is Composite or All Timelines Channel:
    if ExternalOrderKey(candidateEntry) <= ExternalOrderKey(previous):
      return STALE
    return NEW

  return channel.registeredNewnessPolicy(previous, candidateEntry)
```

On successful commit:

```text
checkpoint.entries[channelKey].subject = { blueId: candidateEntry.blueId }
```

On Channel removal, source-semantic change, or activation-lineage change:

```text
remove checkpoint.entries[channelKey]
```

---

## Appendix C. Migration from the former projected checkpoint subject

A host migrating this former shape:

```yaml
subject:
  semantics: coordination-timeline-position-v3
  timestamp: 1785312001000000
  timelineBlueId: <timeline>
  entryBlueId: <entry>
```

MUST:

1. resolve `entryBlueId` to the exact Timeline Entry;
2. verify the resolved timestamp equals the copied timestamp;
3. verify the resolved Timeline BlueId equals the copied Timeline BlueId;
4. verify the current exact Channel lineage;
5. write:

```yaml
subject:
  blueId: <entry>
```

6. remove the copied projection.

If the entry cannot be resolved, return `NEEDS_RESOURCES`.

If copied values disagree, return `INVALID_CHECKPOINT_EVIDENCE`.

The copied tuple MUST NOT remain an independent authority.

---

## Appendix D. Final normative statement

A conforming Blue Coordination implementation follows this chain:

```text
exact Coordination types reference one exact Coordination specification;
concrete type BlueIds select installed runtimes;
Timeline Providers append and retain exact attributed entries;
all providers supply the same minimum strict order and completeness promise;
the feeder closes a common window and orders entries deterministically;
Timeline Channels bind provider-backed histories to document roles;
direct authority is checked before delegated authority;
Mandates are resolved as living documents at the exact action order;
the original Timeline Entry is preserved;
Blue Contracts computes the deterministic next state;
source Channel checkpoints store the exact accepted Timeline Entry;
no custom Checkpoint Domain or duplicated position tuple is required;
unknown evidence defers or fails closed;
retries and crashes do not duplicate committed work;
other external Channel specifications participate only through exact installed and
composition-safe semantics.
```

That is the complete Coordination boundary between independent external histories and deterministic Blue document processing.

## Appendix E. Rooted checkpoint processing revision

RCP-1 supplies the common normative model for this candidate. In particular, provider completeness and authority remain feeder responsibilities; one already-admitted input is settled by Contracts; older embedded states introduce their own pending successors rather than resetting the root; and unrelated reverse observers do not alter a root's input or wait condition.

The new fixture suite is `../conformance/rooted-processing/`. Its source fixtures and abstract model are not a substitute for the complete registered-runtime, exact-evidence, gas, durable-restart and MyOS product gates. Previous exact histories remain associated with their original profiles.
