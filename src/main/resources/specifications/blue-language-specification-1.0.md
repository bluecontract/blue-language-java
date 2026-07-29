# Blue Language Specification 1.0

> **Status.** Final Implementation Baseline. Blue Language 1.0 is the first public-version Language specification and the normative implementation target for this package. Final public publication MUST bind this prose, the canonical core-type registry, published BlueIds, the machine-readable conformance fixtures, and implementation-conformance evidence in one content-addressed release manifest.

> **Scope.** This document defines Blue's content language: the node model, Blue Graph, Blue Documents, typing, overlays, schema constraints, preprocessing, complete and demand-limited resolution, expansion, collapse, canonicalization, minimization, and BlueId. It defines the semantic equivalence of verified pure references and their materializations. It does **not** define runtime execution, handlers, events, channels, gas prices, provider transport, storage layout, or contract processing. Those belong to runtime specifications and implementations.

Where this document references core types such as **Text**, **Integer**, **Double**, **Boolean**, **Dictionary**, and **List**, their canonical type definitions and canonical BlueIds are supplied by the canonical Blue type registry. Appendix A defines their normative semantics and shows the intended canonical registry nodes. The registry is the authority for the exact node content and BlueIds.

Canonical core type nodes are identity-bearing Blue content. Their `description` fields define type semantics and affect BlueId. Editing a canonical description changes the type identity and therefore MUST be treated as a registry/versioning change, not as ordinary documentation editing.

The complete Blue Language 1.0 conformance release is defined by this prose specification, the canonical Blue type registry, the Blue Language 1.0 conformance fixture package, and the content-addressed release manifest together. If these artifacts conflict, the release process MUST be corrected; implementations MUST NOT guess.

## Conventions

The key words **MUST**, **MUST NOT**, **REQUIRED**, **SHOULD**, **SHOULD NOT**, **MAY**, and **OPTIONAL** are to be interpreted as normative requirement levels.

Sections marked **normative** define required behavior for conforming Blue Language 1.0 implementations. Sections marked **informative** explain intent, examples, or implementation guidance.

---

## 0. Overview

Blue Language describes reality as a **content-addressed graph of typed nodes**. Text, integers, doubles, booleans, lists, and dictionaries are the basic building blocks. Larger nodes are formed by connecting those smaller nodes.

An informative mental model is to treat a Blue node as a perfectly defined word. A human-readable `name` helps people discuss the word, while its **BlueId** identifies one exact immutable meaning. The same exact node has the same BlueId wherever it appears, and a BlueId may stand in place of the node's complete verified explanation.

This analogy does not replace the formal rules below. In particular, a BlueId is a content address, not merely a chosen label: changing identity-bearing content changes the BlueId.

A **Blue Graph** is the conceptual network of Blue nodes. Nodes are connected by ordinary object fields, list elements, type links, and `blueId` references. A **Blue Document** is one serialized root and whatever part of that graph is currently materialized with it. It is **not required to contain the whole graph**.

A node may therefore appear in either of these equivalent forms:

```yaml
x:
  a: 1
  b: 1
```

```yaml
x:
  blueId: <blue-id-of-the-same-x-node>
```

When the materialized node verifies to the referenced BlueId, these forms identify the same graph edge and the same Blue node. Inline versus referenced representation is not a semantic distinction.

This equivalence is a load-bearing invariant. A semantic Blue operation MUST be a function of node identity and logical content demanded by that operation. It MUST NOT be a function of whether a node was inline, collapsed, already expanded, cached, fetched from one blob, fetched from many chunks, or represented internally by one host object or many.

The Blue Language defines four ordinary graph operations:

| Operation | Meaning |
|---|---|
| **Expand** | Replace selected pure references with verified materialized content. |
| **Collapse** | Replace selected verified materialized nodes with pure references to their Node BlueIds. |
| **Resolve** | Apply type inheritance, overlays, merge rules, fixed values, and schema rules. |
| **Minimize** | Produce a smaller Source overlay that resolves to the same semantic result. |

Expansion and collapse change representation only. Resolution and minimization change how explicit or type-derived content is expressed. These operations act on ordinary Blue nodes; they do not create a second graph model.

Expansion and resolution are independent dimensions. A processor may expand and resolve only the paths needed for its next decision while leaving unrelated branches collapsed. Limits are supplied out-of-band to the Language operation and do not become Blue content, affect BlueId, or change semantic meaning.

Blue also permits **extension through typing and overlays**. Extension is not a fifth graph operation. To extend a node is to create a new, more specific node that uses another node as its `type` and adds compatible overlay content. The extended node normally has a new BlueId. By contrast, expanding a node only reveals more of the same node and preserves its BlueId.

Blue content commonly appears in the following forms:

| Form | Purpose | Identity status |
|---|---|---|
| **Source Document** | Authored input. May use authoring sugar and the root `blue` directive. | Not necessarily direct BlueId Input. |
| **Preprocessed Document** | Source after preprocessing has applied authoring transforms and removed `blue`. | Eligible for resolution and, if otherwise valid, direct hashing. |
| **Expanded or collapsed form** | The same node with more or fewer referenced descendants materialized. | Expansion and collapse preserve Node BlueId. |
| **Resolved Form** | Type-merged and schema-validated semantic content. It may be complete or explicitly limited to demanded paths. | Carries semantic meaning; not necessarily direct BlueId Input. |
| **Minimized Overlay** | A reduced author-facing overlay that resolves to the same complete Resolved Form. | Produces the same Content BlueId through the full identity pipeline. |
| **Canonical Identity Input** | The one deterministic identity form derived from a complete Resolved Form. | Direct input to Node BlueId; produces Content BlueId. |

Canonicalization is separate from minimization. Canonicalization produces the deterministic BlueId input. Minimization produces a convenient smaller Source overlay and is not necessarily unique.

The identity pipeline for a Source Document is:

```text
Source Document
   -- preprocess    --> Preprocessed Document
   -- fully resolve --> complete Resolved Form
   -- canonicalize  --> Canonical Identity Input
   -- BlueId algorithm --> Node BlueId
                         = Content BlueId of the Source Document
```

Ordinary processors do not need to run this entire pipeline merely to inspect or update a document. They may expand and resolve only demanded fields, preserve unchanged children by BlueId, and collapse the result again.

A Blue Document is a rooted slice of a larger graph:

```text
Selected document slice
+-----------------------------+
| root                        |
| +- local field              |
| +- local list               |
| +- type: { blueId: T } -----+----> external type node T
+-----------------------------+
                                \--> more graph reachable by BlueId
```

This specification defines content-language semantics only.

---

## 1. Scope, Goals, Versioning, and Conformance

### 1.1 Goal

Blue is a universal, deterministic **content language** with:

- a strict, mergeable type system with overlay and subtyping rules;
- a content address called **BlueId** that is stable across equivalent content forms;
- a precise pipeline that maps an authored document to deterministic content identity;
- graph-slice semantics, so documents can contain local content and external `blueId` references;
- identity-preserving expansion and collapse;
- complete or demand-limited resolution;
- semantics-preserving minimization;
- explicit operation outcomes in which unavailable or unexpanded content is never confused with semantic absence;
- local verification of a directly materialized node whose complete children remain represented by their exact BlueIds.

### 1.2 Out of scope

The following are not defined by this specification:

- runtime execution;
- event processing;
- channels;
- handlers;
- gas accounting;
- document update listeners;
- processor lifecycle markers;
- contract execution.

The field `contracts` is reserved by the language because it is a possible field in Blue content and therefore can affect BlueId. Its runtime meaning is defined only by the separate Blue Contracts and Processor Specification 1.0.

### 1.3 Versioning and specification selection

This document defines **Blue Language 1.0**, the first public-version Language specification.

A Blue node does **not** carry a required `languageVersion`, `specification`, or similar field. Adding such a field would make version selection part of content identity and would create a bootstrapping problem: an implementation would need to interpret identity-bearing content before knowing which identity rules apply. The processing environment therefore selects Blue Language 1.0 out-of-band and MUST declare that selection before parsing identity-bearing content.

The exact BlueIds of referenced types remain the normal way in which content selects type semantics. Runtime execution languages are selected by their exact runtime-type BlueIds under the applicable runtime specification; ordinary documents do not require a Language-version field.

Blue Language 1.0 publishes the canonical nodes and BlueIds for `Text`, `Integer`, `Double`, `Boolean`, `Dictionary`, and `List` exactly as contained in the release registry. Those nodes have already been reproduced by multiple implementations and their identity-bearing descriptions intentionally name Blue Language 1.0. Implementations MUST load and verify the registry nodes rather than reconstructing them from prose or source-code constants.

After publication, an existing core-type BlueId MUST never acquire different semantics. A semantic change requires a new type node and BlueId. Editorial clarification that is not intended to alter identity-bearing meaning belongs outside the canonical node.

Blue Language 1.0 is intended to remain stable. Editorial changes that do not alter normative meaning may be published as errata outside canonical registry nodes. Any change that alters the node model, BlueId algorithm, preprocessing, resolution, canonicalization, minimization, or the meaning of valid 1.0 content requires a new Language version and an out-of-band version-selection rule known before the node is interpreted.

A valid unprefixed plain BlueId always denotes the BlueId v1 algorithm defined by this specification. A future incompatible BlueId version MUST use syntax that is not valid as a plain BlueId v1; it MUST NOT reinterpret an existing valid v1 string.

### 1.4 Conformance

A conforming Blue Language 1.0 implementation MUST implement all normative requirements in this specification.

A conforming implementation MUST support:

- parsing Blue Source Documents and BlueId Input;
- preprocessing, including the standard baseline preprocessing environment;
- type resolution and overlay merging;
- schema validation;
- list merge semantics and list control forms;
- provider-backed resolution when referenced content is required;
- complete and demand-limited resolution with explicit complete, absent, incomplete, and invalid outcomes;
- representation-transparent graph access through verified pure references;
- expansion semantics, including provider-backed materialization when referenced content is required;
- the semantics of expansion, collapse, resolution, and minimization; an implementation need not expose each as one public method, but all corresponding behavior it exposes MUST follow this specification;
- canonicalization for Content BlueId calculation;
- author-facing minimization behavior sufficient to pass the conformance fixtures;
- Node BlueId and Content BlueId calculation;
- circular reference set BlueIds;
- rejection of invalid Blue Language 1.0 documents and invalid BlueId Input;
- the Blue Language 1.0 conformance suite.

An implementation MAY expose detailed demand enums, node handles, provider batches, storage indexes, work diagnostics, or caches. Those are implementation surfaces. They MUST preserve the semantic results required here and MUST NOT become observable Blue content.

A library or tool that implements only a subset of this specification may be useful, but it MUST NOT describe itself as a conforming Blue Language 1.0 implementation.

### 1.5 Core registry and release artifacts

The canonical Blue type registry is part of the Blue Language 1.0 release surface. Its entries for `Text`, `Integer`, `Double`, `Boolean`, `Dictionary`, and `List` are content-addressed and versioned with this specification.

A conforming implementation MUST use the published registry BlueIds for core type aliases. A different registry binding does not produce portable Blue Language 1.0 Content BlueIds.

Canonical registry nodes are self-describing Blue content. A registry node's `name` and `description` fields are identity-bearing. A concise normative `description` SHOULD define the type's semantics. Changing that semantic description changes the type BlueId and defines a different type.

Non-normative examples, rationale, translations, tutorial material, implementation notes, and editorial commentary MUST NOT be included in canonical registry nodes unless intentionally made identity-bearing. Such material belongs in this prose specification or in separate documentation.

The registry file is the authority for the exact parsed string content of canonical nodes. Code blocks in this specification that claim to show canonical nodes SHOULD be generated from, or kept Blue-equivalent to, the registry entries used to calculate the published BlueIds.

The core-registry manifest MUST publish, for every entry:

- registry kind and specification version;
- stable entry key;
- path of the canonical node file;
- the calculated Node BlueId;
- the SHA-256 digest of the exact node file;
- `semanticDescriptionIdentityBearing: true`;
- the Language fixture-package identity that verifies it.

The manifest itself MUST publish one content-addressed package identity calculated by the release rule declared in that manifest. The top-level release manifest MUST bind that core-registry package identity.

A complete Blue Language 1.0 conformance release consists of:

1. this prose specification;
2. the canonical Blue 1.0 core-type registry and published BlueIds;
3. the machine-readable Blue Language 1.0 fixture package and its identity;
4. a content-addressed release manifest that binds the preceding artifacts.

The release manifest MUST identify at least the specification revision, core-registry identity, fixture-package identity, and artifact digests. If the prose, registry, fixtures, or manifest conflict, the release is inconsistent and MUST be corrected. Implementations MUST NOT guess which artifact wins.

Until all four artifacts exist and independent fixture execution has succeeded, this package remains an implementation baseline rather than a final public conformance release.

---

## 2. Serialization and Data Model

### 2.1 JSON data model (normative)

Blue documents use the JSON data model:

- objects;
- arrays;
- strings;
- numbers;
- booleans;
- null.

YAML is an authoring syntax for this JSON data model. A YAML parser used for Blue MUST NOT introduce YAML-specific data types into the Blue data model.

### 2.2 YAML restrictions (normative)

When YAML is used for Blue serialization:

- duplicate object keys MUST be rejected;
- custom YAML tags MUST be rejected;
- Portable Blue YAML MUST reject YAML anchors, aliases, and merge keys. An implementation MAY expose a non-portable preprocessing mode that expands them deterministically before Blue parsing, but documents relying on that mode are not portable Blue Source Documents.
- non-JSON implicit types, including timestamps, binary blobs, sets, and ordered maps, MUST be disabled;
- timestamp-like values SHOULD be quoted by authors. Blue Language 1.0 defines no timestamp scalar.

Blue Language 1.0 YAML uses the YAML 1.2 JSON schema data model. Portable Blue YAML MUST reject custom tags, non-string object keys, binary tags, sets, ordered maps, and non-JSON implicit scalar types.

The parsed value of a YAML block scalar is the exact Text value. Blue performs no block-scalar normalization. Different YAML scalar styles, indentation, folding, chomping indicators, trailing newlines, or line endings that produce different parsed strings produce different BlueIds.

Examples:

```yaml
# Text, not a Date/Time type in Blue Language 1.0
ts: "2025-09-01T12:00:00Z"
```

Blue Language 1.0 does not define a core Date or Timestamp scalar type.

### 2.3 Duplicate keys (normative)

Serialized Blue documents MUST NOT contain duplicate object keys. Parsers MUST reject duplicate keys. Later-key-wins behavior is not conforming.

### 2.4 Number tokens and large integers (normative)

Blue distinguishes the mathematical value of an integer from the JSON/YAML encoding used to carry it.

The interoperable **safe JSON numeric integer range** for Blue Language 1.0 is:

```text
[-9007199254740991, 9007199254740991]
```

JSON itself does not define a numeric range. Blue uses this safe range because it is exactly representable by JSON implementations that store numbers as IEEE 754 binary64 values.

Rules:

1. An unquoted integer token within this range MAY be used as an `Integer` value.
2. An integer value outside this range MUST be authored as a quoted canonical decimal string and MUST have explicit type `Integer` or a type that resolves to `Integer`.
3. In Canonical Identity Input and BlueId Input, an `Integer` value outside this range MUST be represented as its quoted canonical decimal string while retaining the explicit `Integer` type.
4. The canonical decimal string form is an optional leading `-` followed by decimal digits, with no leading zeros except the single digit `0`.
5. Quoted decimal text without an explicit `Integer` type is Text, not Integer.

A quoted canonical decimal string value is interpreted as an `Integer` when the node has an explicit effective type that resolves to `Integer`. The effective type may be authored locally or inherited from the resolved type chain.

If no effective type resolves to `Integer`, quoted decimal text is Text.

If an effective type resolves to `Integer` and the quoted value is not a valid canonical decimal integer string, resolution MUST fail.

Primitive scalar inference for quoted strings is provisional for Source Documents. Resolution MUST refine a quoted scalar's effective scalar type to `Integer` when the inherited or explicit effective type resolves to `Integer` and the quoted value is a valid canonical decimal integer string. It MUST fail when that effective type requires `Integer` and the quoted value is not canonical Integer text.

Examples:

```yaml
small:
  type: Integer
  value: 42

large:
  type: Integer
  value: "9007199254740992"
```

The same rule applies below the negative bound:

```yaml
veryNegative:
  type: Integer
  value: "-9007199254740992"
```

Example with inherited Integer type:

```yaml
# Type
name: Account
accountId:
  type: Integer

# Source instance
type: Account
accountId: "9007199254740992"
```

After preprocessing and resolution, `accountId` is an Integer value because the effective inherited type resolves to `Integer`.

Without the inherited or explicit Integer type, the same quoted value is Text.

Floating-point `Double` values MUST be finite. `NaN`, `Infinity`, and `-Infinity` are not valid Blue scalar values.

Double parsing MUST produce a finite IEEE 754 binary64 value using round-to-nearest, ties-to-even semantics. A numeric token that overflows to positive or negative Infinity, underflows to a non-finite value, or parses as NaN is invalid.

A parsed `-0.0` Double value compares equal to `0.0` and canonicalizes as JSON number `0` under RFC 8785. The node remains Double because its effective type is Double.

A Double whose RFC 8785 canonical JSON representation is integer-looking, such as `1`, remains Double because its effective type is represented in BlueId Input.

If a parser cannot deterministically parse a numeric token as binary64 with these semantics, the implementation MUST reject the token or require explicit authoring in a supported form.

### 2.5 Numeric token inference (normative)

When a numeric Source Document value has no explicit type:

- an unquoted integer token with no decimal point and no exponent infers `Integer`;
- an unquoted numeric token with a decimal point or exponent infers `Double`, even if its mathematical value is integral.

Examples:

```yaml
a: 1      # Integer
b: 1.0    # Double, canonical numeric payload may render as 1
c: -0.0   # Double, canonical numeric payload renders as 0
d: 1e999  # invalid Double
```

If a parser cannot preserve the lexical distinction between integer tokens and decimal/exponent tokens, it MUST require explicit type annotations for ambiguous numeric values or document that such inputs are not portable Source Documents.

### 2.6 String and multiline scalar identity (normative)

After parsing, a Blue string value is identity-bearing exactly as parsed. Blue Language performs no automatic whitespace normalization, line-ending normalization, trailing newline stripping, indentation rewriting, Unicode normalization, case folding, or YAML block-scalar canonicalization.

Different YAML scalar styles may produce different string values and therefore different BlueIds. In particular, YAML block scalar choices such as `|`, `|-`, `|+`, `>`, and `>-` may differ in line folding and trailing newline behavior.

Canonical registry nodes SHOULD be generated, fixture-checked, or otherwise protected against accidental string drift. Authors of identity-sensitive documents SHOULD treat edits to multiline `description` fields as content edits, not formatting edits.

Blue Language uses the parsed Unicode code-point sequence. Implementations MUST NOT normalize Text by default. Applications that need a normalization convention, such as NFC, SHOULD apply it explicitly at the application/preprocessing layer.

---

## 3. Blue Graph, Blue Documents, and References

### 3.1 The Blue Graph (normative)

The **Blue Graph** is the conceptual content-addressed network of Blue nodes. Edges in the graph arise from:

- ordinary object fields, for example `address -> child node`;
- list elements;
- type links, for example `type: ...`;
- `blueId` references.

Nodes are identified by BlueId. The graph is global and content-addressed; it is not owned by any single document.

### 3.2 Blue Documents as graph slices (normative)

A **Blue Document** is a serialized rooted slice of the Blue Graph. It may contain:

- fully materialized child nodes;
- pure references to external nodes using `{ blueId: ... }`;
- a mixture of local content and external references.

A Blue Document is not required to be closed. A `{ blueId: X }` reference may point to content outside the selected document. Implementations use a provider only when an operation demands referenced content.

A materialized child whose Node BlueId is `X` and a pure `{ blueId: X }` reference are representation-equivalent. Language operations, validators, and higher-level processors MUST NOT assign different semantic meaning merely because one form is expanded and the other is collapsed.

### 3.3 Pure references (normative)

A **pure reference** is exactly:

```yaml
blueId: <id>
```

or, as a field value:

```yaml
field:
  blueId: <id>
```

A pure reference object MUST NOT carry sibling fields. The following is not a pure reference:

```yaml
blueId: <id>
name: Something
foo: bar
```

Mixed `blueId` forms MUST be rejected in Source Documents, Preprocessed Documents, Canonical Identity Input, and BlueId Input. Provider metadata MUST be represented out-of-band or in a non-Blue envelope.

A non-Blue envelope is packaging metadata outside the Blue Document root. It is not part of the Blue node and is not included in BlueId calculation.

A pure reference cannot carry sibling fields. To refine or extend referenced content, the reference MUST appear in a type position or be resolved as an ancestor/type, and the overlay MUST be written as ordinary instance content outside the pure reference object.

Invalid:

```yaml
blueId: X
extra: value
```

Valid as a typed overlay:

```yaml
type:
  blueId: X
extra: value
```

### 3.4 Document identity (normative)

The BlueId of a Blue Document is the BlueId of its root node. There is no separate document-level identity above the root node.

A Blue Document root MAY be a scalar, list, object, or pure reference. Scalar and list roots follow the same wrapper-equivalence rules as field values. A Blue Document root MUST NOT be `null`.

---

### 3.5 Exact-node equivalence and materialization state (normative)

Let `X` be a valid Node BlueId. A pure reference:

```yaml
blueId: X
```

and any verified materialization whose Node BlueId is `X` denote the same exact Blue node.

For semantic Blue operations, materialization state is out-of-band. It MUST NOT change:

- node kind;
- field or list membership;
- equality or matching;
- effective type or schema;
- presence or absence;
- any semantic conclusion once the same logically required evidence is available;
- Node BlueId or Content BlueId.

A serialization-inspection API MAY expose that a supplied syntax object contains the key `blueId`. A semantic graph API MUST NOT expose the pure-reference wrapper as an ordinary child field of the referenced node. For example, if `/x` denotes node `X`, a semantic lookup of `/x/blueId` does not succeed merely because `/x` was supplied in collapsed form. Exact identity is obtained through an explicit node-identity operation.

Expansion state, provider location, cache state, and storage segmentation are not Blue content and MUST NOT be inserted into a Blue node.

### 3.6 Identity-preserving implementation values (normative behavior)

An implementation MAY represent an exact node internally by a handle containing its Node BlueId, optional verified materialization, and out-of-band provider or coverage information. No particular handle class or public API is required.

Whenever an implementation passes, snapshots, emits, stores, or returns an already verified node, it MUST preserve the exact Node BlueId and MUST NOT require recursive cloning or transitive materialization merely to carry that value.

Portable application semantics MUST NOT depend on whether such an implementation value currently carries materialized content. When an operation demands unavailable content, the operation returns an incomplete or provider outcome under §§10 and 12 rather than inventing semantic absence.

## 4. Node Model and Reserved Fields

### 4.1 Node anatomy (normative)

A **Blue node** consists of reserved language fields and, optionally, one primary payload kind.

```text
Node = reserved language fields + zero or one payload kind
```

The permitted payload kinds are:

- **scalar payload**: a `value` field carrying a string, number, or boolean;
- **list payload**: an `items` field carrying an ordered sequence;
- **object payload**: one or more ordinary child fields, where ordinary child fields are fields whose keys are not reserved language keys.

A node MUST NOT combine payload kinds. For example, a node MUST NOT contain both `value` and `items`, or both `value` and ordinary child fields.

A node MAY have no payload. Such a node is a metadata-only, type-only, schema-only, or overlay-only node. Examples include:

```yaml
age:
  type: Integer
```

and:

```yaml
name: Person
```

A pure reference is a special metadata-only reference node. It is valid only when the object contains exactly `blueId`.

If a node has no payload and no retained reserved content after object-field cleaning, it may normalize to an empty map and be omitted when it appears as an object field. It MUST NOT be silently deleted when it appears as a list element; list element normalization is context-sensitive (§11.5, §14.2).

### 4.2 Reserved language keys (normative)

The following keys are reserved by the language:

```text
name, description,
type, itemType, keyType, valueType,
value, items,
blueId, blue,
schema, mergePolicy,
contracts
```

The following keys are reserved-invalid and MUST be rejected wherever they would appear as object fields:

```text
properties, constraints
```

Reserved fields are grouped as follows:

| Category | Fields |
|---|---|
| Identity labels | `name`, `description` |
| Type and constraint metadata | `type`, `itemType`, `keyType`, `valueType`, `schema`, `mergePolicy` |
| Payload wrappers | `value`, `items` |
| Reference and preprocessing controls | `blueId`, `blue` |
| Reserved extension field | `contracts` |

`contracts` is reserved by the language but semantically defined only by the Blue Contracts and Processor Specification 1.0.

The key `blue` is valid only as a preprocessing directive on the root of a Source Document. A conforming implementation MUST reject `blue` anywhere else. Direct Node BlueId calculation MUST reject any node containing `blue` as direct BlueId Input.

There is no `properties` field in the Blue Language. The key `properties` is reserved-invalid in Blue Language 1.0 and MUST NOT appear as an ordinary child field or language wrapper. Applications that need a data key literally named `properties` MUST use an escaped representation defined by the application's type.

Reserved language keys cannot be used as ordinary child-field names in direct object encoding. Direct object encoding can therefore represent only data keys that do not collide with reserved language keys.
Applications that need arbitrary user keys, including keys that equal reserved language keys, MUST use an escaped representation defined by the application's type.

### 4.3 Reserved field value types (normative)

Implementations MUST validate reserved field value types.

| Field | Required value shape |
|---|---|
| `name` | string, or absent |
| `description` | string, or absent |
| `type` | node, string alias in Source Documents before preprocessing, or pure reference |
| `itemType` | node, string alias in Source Documents before preprocessing, or pure reference |
| `keyType` | node, string alias in Source Documents before preprocessing, or pure reference |
| `valueType` | node, string alias in Source Documents before preprocessing, or pure reference |
| `value` | string, number, boolean, or absent |
| `items` | list, or absent |
| `blueId` | string BlueId, only in pure references |
| `blue` | string or object directive; root Source Document only |
| `schema` | object using only schema keywords from §9, pure reference to such an object, or absent |
| `mergePolicy` | `append-only`, `positional`, or absent |
| `contracts` | object, pure reference to such an object, or absent; runtime semantics out of scope |

Wrong reserved-field types MUST be rejected. Implementations MUST NOT silently coerce reserved field values such as `blueId: 123` or `name: true` into strings. A pure reference accepted for `schema` or `contracts` MUST be expanded when the operation needs to validate or interpret the referenced object's contents; its collapsed form is not an exemption from the field's semantic shape rules.

### 4.4 `contracts` boundary (normative)

In Blue Language 1.0, `contracts` is a reserved identity-bearing content field. A language implementation MUST parse, preserve, resolve, canonicalize, and hash `contracts` as content. It MUST NOT execute `contracts`.

Unless a separate processor specification is explicitly being applied, `contracts` participates in language-level merge and canonicalization according to ordinary object-field rules. Runtime interpretation, reserved processor keys under `contracts`, processor lifecycle behavior, and contract capability handling are outside this specification.

When a `contracts` value is a pure reference and an operation needs to merge or inspect that map, the reference MUST be expanded and verified first. Language-level merge of the resulting `contracts` maps is field-wise:

- If only the ancestor contributes a contract entry at key `k`, the entry is materialized in the Resolved Form as type-derived content.
- If only the instance contributes a contract entry at key `k`, the entry is preserved as instance-supplied content.
- If both ancestor and instance contribute `contracts[k]`, the two contract nodes are merged recursively under the same fixed-value, type-compatibility, schema, and object-field rules used for ordinary child fields.
- A descendant MUST NOT remove an inherited contract entry during language resolution. Runtime removal or mutation of contracts, if allowed, belongs to the Blue Contracts and Processor Specification 1.0.
- The language resolver MUST NOT interpret, execute, sort, dispatch, or validate processor-specific contract behavior.

Processor-reserved keys inside `contracts` have no runtime effect in this specification. They are still parsed, resolved, canonicalized, and hashed as content.

### 4.5 `name` and `description`: identity vs field semantics (normative)

`name` and `description` are content on the node. They affect BlueId.

They are also matcher-neutral. Matchers MUST ignore `name` and `description` for:

- type conformance checks;
- subtype compatibility checks;
- structural or shape matching;
- resolution matching.

Identity equality includes `name` and `description`. Structural and type equality ignore them.

### 4.6 Document identity vs field semantics for labels (normative)

A node whose `type` is `T` is not `T`; it is a new entity. The resolved node's top-level `name` and `description` come only from the instance and MUST NOT be inherited from the type. The embedded type object may carry its own `name` and `description` inside `node.type`.

When a type materializes declaration-only fields or list elements into an instance, those child nodes carry the type's `name` and `description` as inherited labels until the instance explicitly overrides them.

However, when the inherited child node contains a fixed payload value, fixed list payload, fixed object subtree, or pure reference, the labels on that node are part of the inherited fixed value's identity. A descendant MUST NOT change `name` or `description` on such a fixed-value node unless the inherited type leaves that label absent or the change is otherwise allowed by an explicit resolution rule.

Dereferencing `{ blueId: X }` to materialize a node may copy the referenced node's `name` and `description` onto that materialized node, because the node itself is being materialized. This is expansion, not type inheritance.

---

## 5. Authoring Forms and Wrapper Equivalence

### 5.1 Wrapper equivalence (normative)

To improve ergonomics, Blue admits equivalent authoring forms for scalars and lists, provided the wrapper has no other keys.

Scalar sugar:

```yaml
x: 1
```

is equivalent to the wrapped form:

```yaml
x:
  value: 1
```

List sugar:

```yaml
x: [a, b]
```

is equivalent to:

```yaml
x:
  items: [a, b]
```

### 5.2 Sugar vs explicit metadata (normative)

The sugar rule applies only when the wrapper has no other keys. Therefore:

```yaml
x: 1
```

is sugar for:

```yaml
x:
  value: 1
```

but:

```yaml
x:
  type: Integer
  value: 1
```

is not sugar. It is the explicit scalar node form with metadata.

A node may carry metadata such as `type`, `description`, `schema`, or `mergePolicy` alongside a payload kind. Metadata is not a payload kind.

### 5.3 Object nodes (normative)

Object payloads are written directly as ordinary child fields:

```yaml
x:
  a: 1
  b: 2
```

There is no `properties` wrapper. The key `properties` is reserved-invalid (§4.2).

### 5.4 Identity over forms (normative)

Equivalent authoring forms of the same semantic content MUST produce the same Content BlueId.

The BlueId algorithm operates on the abstract node model after canonical input normalization, not on authoring syntax. In particular, a bare scalar and its `{ value: ... }` wrapped form normalize identically. A bare list and its `{ items: ... }` wrapped form normalize identically.

---

## 6. Preprocessing and the `blue` Directive

### 6.1 Purpose (normative)

The root of a Source Document MAY contain a `blue` field. The `blue` directive declares preprocessing transforms that normalize authoring conveniences before the document is treated as identity-bearing content.

A string-valued `blue` directive identifies a preprocessing environment or import document according to the implementation's declared preprocessing configuration.
An object-valued `blue` directive declares imports and preprocessing transforms directly. The exact object fields supported by a preprocessing environment MUST be deterministic and documented by that environment.

Preprocessing is part of Content BlueId calculation. It is not part of direct Node BlueId calculation, because direct Node BlueId accepts only BlueId Input.

A conforming implementation MUST support this portable `blue.imports` shape:

```yaml
blue:
  imports:
    AliasName:
      blueId: <BlueId>
```

Each key under `imports` is an authoring alias. Each value MUST be a pure reference object. During preprocessing, occurrences of that alias in `type`, `itemType`, `keyType`, or `valueType` positions are replaced by the corresponding pure reference.

Aliases declared in `blue.imports` are scoped to the Source Document being preprocessed. They are removed with the `blue` directive and are not identity content after preprocessing.

An alias name MUST NOT be declared more than once in the same `imports` object. An alias declared in `blue.imports` MUST NOT redefine a built-in core type name unless it maps to the same canonical BlueId.

### 6.2 Standard baseline preprocessing (normative)

A conforming implementation MUST support the standard baseline preprocessing environment:

1. **Core type aliases to BlueIds.** Core aliases such as `Text`, `Integer`, `Double`, `Boolean`, `Dictionary`, and `List` are replaced by canonical type references supplied by the canonical Blue type registry.
2. **Document-declared aliases to BlueIds.** Aliases other than the built-in core type names MUST be declared by the Source Document, for example through the root `blue` directive, or by content-addressed import documents referenced from it.
3. **Primitive scalar inference.** Bare scalar payloads with no explicit type are assigned the corresponding core primitive type: `Text`, `Integer`, `Double`, or `Boolean`.
4. **Wrapper normalization.** Scalar and list sugar are normalized into the abstract node model.
5. **List placeholder normalization.** In Source Documents, list elements that are `null`, `{}`, or that recursively normalize to an empty object after object-field cleaning are normalized to `$empty: true` (§11.5).

If the root `blue` directive is omitted, conforming implementations MUST still apply the standard baseline preprocessing environment. If a `blue` directive is present, it MAY configure imports and additional declared supported transforms, but it MUST NOT disable the mandatory baseline transforms required for interoperability.

Implementation-local alias configuration MAY be used for authoring convenience, but documents depending on undeclared implementation-local aliases do not have portable Content BlueIds.

### 6.3 Additional preprocessing transforms (normative)

Additional preprocessing transforms MAY be used only when they are explicitly declared by the root `blue` directive and supported by the implementation.
Such transforms MUST be deterministic. If a Source Document requires a transform that the implementation does not support, preprocessing MUST fail.
Any imported preprocessing document that affects Content BlueId MUST itself be identified by BlueId or by a deterministic registry binding declared by the Source Document.
A document that depends on implementation-local transforms not declared by the Source Document does not have a portable Content BlueId.

### 6.4 Preprocessing rules (normative)

- The `blue` directive is valid only on the root of a Source Document.
- The `blue` directive is not semantic content.
- A document containing `blue` is not valid BlueId Input.
- Preprocessing MUST remove the `blue` directive after applying it.
- Direct Node BlueId calculation MUST reject a node containing `blue`.
- Content BlueId calculation MUST preprocess the document and remove `blue` before hashing.

Simply ignoring `blue` is not correct. The directive may define aliases and transforms that change the canonical content. A direct hasher that sees `blue` MUST reject the input rather than hash a partially processed structure.

### 6.5 Security (normative)

Remote fetch of preprocessing imports or transforms is DISABLED by default. Implementations MAY support remote preprocessing documents only through explicit opt-in configuration and deterministic caching rules.

Any preprocessing import document or transform document fetched by BlueId MUST be verified against that BlueId before use. If verification fails, preprocessing MUST fail deterministically.

A preprocessing import that is not identified by BlueId MUST be supplied by a deterministic registry binding declared by the Source Document or by the implementation's declared preprocessing configuration. Such bindings are outside the portable Source Document unless their identity is included in the conformance fixture or release artifact.

---

## 7. BlueId and Content Identity

### 7.1 BlueId summary (normative)

Every Blue node has a content identity called its **BlueId**. The BlueId of a Blue Document is the BlueId of its root node.

BlueId is a content address: equivalent representations of the same content produce the same identity after the relevant language operations have been applied.

This section defines BlueId conceptually. The algorithmic details are in §14.

### 7.2 Node BlueId and Content BlueId (normative)

Blue defines two related identities.

**Node BlueId** is the result of applying the BlueId algorithm directly to valid **BlueId Input**.

**Content BlueId** is the semantic identity of a Source Document. It is calculated as:

1. preprocess the Source Document (§6);
2. resolve type chains and validate constraints (§10), producing a Resolved Form;
3. canonicalize the Resolved Form into a Canonical Identity Input (§13);
4. compute the Node BlueId of the Canonical Identity Input (§14).

All conforming implementations MUST produce the same Content BlueId for equivalent Source Documents under the same declared Language release and canonical registry bindings when every demanded reference resolves to the same verified node. Provider location, cache contents, lookup order, and other ambient provider state are not identity inputs.

### 7.3 Identity preservation across forms (normative)

Expansion preserves Node BlueId when the provider returns verified content. Pure references hash to their target BlueId; materializing a reference into content does not change the surrounding node's Node BlueId if the materialized content has that BlueId.

Collapse preserves Node BlueId. Replacing materialized content with a pure reference to its known BlueId yields the same Node BlueId.

Resolution preserves semantic identity. A Source Document and its Resolved Form have the same Content BlueId when the Resolved Form is canonicalized.

A Resolved Form is not generally direct BlueId Input. It may contain inherited or materialized fields that are derivable from the type chain. Directly hashing a Resolved Form is not guaranteed to produce the Content BlueId.

### 7.4 BlueId Input (normative)

**BlueId Input** is any node valid for direct application of the BlueId algorithm after BlueId input normalization.

BlueId Input MUST NOT contain:

- the `blue` directive;
- unresolved aliases introduced only for authoring convenience;
- illegal payload combinations;
- invalid list-control forms;
- mixed `blueId` reference shapes;
- unresolved cyclic placeholders such as `this#0`, except inside the explicit cyclic-set calculation API defined in §15;
- `$pos` overlays;
- `null` list elements;
- empty-object list elements that have not been normalized to `$empty: true`.

A node containing `blue` MUST NOT be accepted as direct BlueId Input. The `blue` directive is never identity content.

### 7.5 Allowed BlueId forms (normative)

A **plain BlueId** is the Base58 encoding of a SHA-256 digest using the following alphabet:

```text
123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz
```

Blue Language 1.0 does not define alternative BlueId alphabets. A registry MAY define aliases or packaging metadata, but MUST NOT redefine the BlueId hash alphabet.

A plain BlueId MUST be the canonical Base58 encoding of exactly 32 bytes, the output length of SHA-256. Implementations MUST reject non-canonical Base58 encodings, strings containing characters outside the BlueId alphabet, and strings that decode to any length other than 32 bytes.

A plain BlueId MUST NOT contain `#`. The `#` suffix syntax is reserved for cyclic-set member BlueIds.

A valid unprefixed plain BlueId always denotes the BlueId v1 form defined here. A future incompatible BlueId version MUST use syntax that is not valid as a plain BlueId v1 and MUST NOT reinterpret an existing valid v1 string.

The ZERO_BLUEID sentinel defined in §15.2 is not a plain BlueId because the character `0` is not in the BlueId alphabet.

A **cyclic-set member BlueId** has the form:

```text
<MASTER>#<index>
```

where `MASTER` is the plain BlueId of the ordered cyclic set list and `index` is a non-negative decimal integer.

`this#<index>` is an algorithm-internal placeholder accepted only by the explicit cyclic-set calculation API defined in §15. It MUST NOT appear in ordinary BlueId Input or provider-stored content.

---

## 8. Types, Overlays, and Subtyping

### 8.1 Any node can be a type (normative)

There is no schema-versus-instance bifurcation in Blue. Any node can appear under `type`.

If `T` is used in `type: T`, then `T` contributes:

- structure;
- nested type chains;
- schema constraints;
- fixed values.

A type is an **overlay source**, not a class declaration.

### 8.2 Fixed-value invariant (normative)

A concrete value embedded in a type is immutable in descendants at that path. A descendant MUST NOT replace, remove, or contradict that value. Any attempted override MUST fail resolution.

For example, if a type fixes:

```yaml
country:
  value: PL
```

then a descendant cannot resolve with:

```yaml
country:
  value: US
```

### 8.3 Fixed-value equality (normative)

Fixed-value equality is evaluated after preprocessing and wrapper normalization.

- Scalar equality compares the parsed scalar value and effective scalar type.
- Object and list equality compares the Node BlueId of the normalized subtree.
- `name` and `description` are content for fixed-value equality. Matcher neutrality applies to type/shape matching, not to identity equality of fixed values.

Scalar payload equality compares parsed scalar value and effective scalar type. Full fixed-node equality compares the normalized Blue node identity, including `name`, `description`, metadata, and payload. Thus a descendant may not change labels on an inherited fixed-value node, because doing so changes the fixed node's identity.

Therefore these are equal after wrapper normalization:

```yaml
city: Warsaw
```

```yaml
city:
  value: Warsaw
```

but these are different fixed values because labels are identity content:

```yaml
city:
  name: City
  value: Warsaw
```

```yaml
city:
  name: Location
  value: Warsaw
```

Valid label override on declaration-only field:

```yaml
# Parent type
city:
  name: City
  type: Text

# Descendant
city:
  name: Location
  value: Warsaw
```

Invalid label override on fixed-value field:

```yaml
# Parent type
city:
  name: City
  value: Warsaw

# Descendant
city:
  name: Location
  value: Warsaw
```

The second case fails because the inherited fixed node includes the label `name: City` as identity content.

### 8.4 Subtyping and Liskov substitutability (normative)

When resolving, descendants MUST satisfy:

1. **No fixed-value override.** Immutable values inherited from types cannot be changed.
2. **Type compatibility.** A descendant type at a path must be equal to or a subtype of the inherited type at that path (§8.4.1).
3. **Additive structure.** Guaranteed fields cannot be deleted.
4. **Collection compatibility.** `itemType`, `keyType`, and `valueType` compatibility must be preserved.

Every instance of a subtype MUST be substitutable for its parent.

If `itemType`, `keyType`, or `valueType` is inherited at a path, a descendant that omits the field inherits it. A descendant MAY narrow the inherited type by supplying an equal type or subtype. A descendant MUST NOT widen, remove, or replace the inherited type with an incompatible type.

Omitting `itemType`, `keyType`, or `valueType` means unconstrained only when there is no inherited effective type constraint at that path.

### 8.4.1 Formal subtype relation (normative)

For Blue Language 1.0, `T <: P` ("T is a subtype of P") iff resolving `T` as a descendant overlay of `P` succeeds under the resolution rules in §10, and every valid instance of `T` is substitutable where an instance of `P` is required.

A subtype check MUST ignore `name` and `description` for matcher/type-shape purposes, but fixed-value equality still includes `name` and `description` because they are identity content (§8.3).

For each path contributed by parent type `P`, subtype `T` MUST satisfy all of the following:

1. **Fixed values preserved.** If `P` fixes a scalar, object, list, or subtree value at a path, `T` MUST preserve the same fixed value under §8.3.
2. **Guaranteed structure preserved.** If `P` guarantees a field or list prefix element, `T` MUST keep it present in all valid instances unless a specific list merge rule explicitly refines it without removal.
3. **Schema constraints compatible.** Every schema constraint contributed by `P` MUST remain satisfied by `T`. Additional constraints in `T` are allowed only when their intersection with inherited constraints is non-empty and not weaker.
4. **Type constraints narrowed only.** If `P` declares `type`, `itemType`, `keyType`, or `valueType` at a path, `T` may repeat the same type or provide a subtype. It MUST NOT omit, widen, or replace the inherited effective type constraint with an incompatible type.
5. **Payload kind compatible.** Scalar, list, and object payload kinds MUST remain compatible with inherited guarantees. A subtype MUST NOT turn an inherited scalar requirement into a list/object requirement, or vice versa, unless resolution can prove the inherited requirement is not applicable.
6. **List policies preserved.** An inherited `mergePolicy: append-only` MUST remain append-only. A descendant MUST NOT weaken append-only to positional. If no merge policy is inherited and none is authored, the effective default is positional.

Equivalently, `T <: P` when the Resolved Form produced by resolving `T` over `P` is valid and does not violate any invariant or guarantee of `P`.

If checking `T <: P` requires resolving a type chain that revisits a type already on the active resolution stack, resolution MUST fail with a type-cycle error (§10.2.1).

### 8.4.2 Nominal core type identity (normative)

The canonical core primitive and collection types `Text`, `Integer`, `Double`, `Boolean`, `Dictionary`, and `List` are **nominal** Blue Language types identified by their canonical registry BlueIds.

A type resolving to one of these canonical core types is compatible with another such type only when the canonical registry BlueId is equal, unless the canonical registry explicitly declares a subtype relationship. Blue Language 1.0 declares no implicit subtype relationship between distinct core types.

Matcher-neutral treatment of `name` and `description` applies to structural field matching and subtype shape checks. It does **not** make two different canonical registry type identities interchangeable. If a core type description changes and therefore the type BlueId changes, it is a different nominal type.

Examples:

- The canonical `Integer` type is compatible with itself by registry BlueId.
- A node named `Integer` with a different description and different BlueId is not the canonical `Integer` type.
- `Integer` and `Double` are not subtypes of each other in Blue Language 1.0.

### 8.5 Instance-as-type (normative)

Nodes representing individuals can be used as types.

For example:

- `Alice` may have `type: Person`.
- `Alice Smith` may have `type: Alice`.

All fixed values in `Alice` become invariants in `Alice Smith`. Alice's top-level `name` and `description` do not flow to Alice Smith (§4.6).

### 8.6 Requirement overlays (normative)

An ancestor may partially constrain a subtree without binding a concrete type at that path.

Example:

```yaml
# Parent
name: A
prop1:
  x: 1
  schema:
    minFields: 1
```

A descendant may later set:

```yaml
name: B
type: A
prop1:
  type: Some
```

This is valid only if the merged result still satisfies all overlay obligations, including fixed values and schema constraints. If the overlay had a type, the descendant's type must be equal to or a subtype of that type.

If the overlay forces `x = 1` but `Some` forces `x = 2`, resolution MUST fail.

### 8.7 Extension versus expansion (normative distinction)

**Expansion** materializes a verified reference to an existing node. It reveals more of the same exact node and MUST preserve Node BlueId.

**Extension** is the authoring act of creating a new node whose `type` points to another node and whose overlay adds compatible meaning. Extension is governed by the fixed-value, subtype, merge, and schema rules in this section. An extended node is not the node it extends and normally has a different BlueId.

Example:

```yaml
# Existing type
name: Price
amount:
  type: Integer
currency:
  type: Text
```

```yaml
# New, more specific node
name: PLN Price
type:
  blueId: <Price>
currency: PLN
```

Expanding `<Price>` reveals the existing `Price` node. Creating `PLN Price` extends it. Implementations and documentation MUST NOT use these terms interchangeably.

---

## 9. Schema Constraints

### 9.1 Attaching schema (normative)

A materialized `schema` object or a pure reference to such an object MAY be attached to any node. An operation that needs the constraints behind a pure reference MUST expand and verify that reference before interpreting the schema.

All schema constraints accumulate along the type chain. Compatible constraints are intersected according to §9.9. Irreconcilable constraints MUST fail resolution.

### 9.2 Schema vocabulary (normative)

Only the keywords listed in §§9.3-9.8 are valid inside a materialized `schema` object. Implementations MUST reject any other key after a referenced schema object has been expanded and verified. The `blueId` key of the pure-reference wrapper is not a schema keyword and is never interpreted as one.

The valid schema keywords are:

```text
required,
minItems, maxItems, uniqueItems,
minFields, maxFields,
minimum, maximum, exclusiveMinimum, exclusiveMaximum, multipleOf,
minLength, maxLength,
enum
```

A schema object MUST NOT contain any key outside this list.

### 9.2.1 Schema keyword value types (normative)

| Keyword | Required value shape |
|---|---|
| `required` | boolean |
| `minItems`, `maxItems`, `minFields`, `maxFields`, `minLength`, `maxLength` | non-negative integer in the safe JSON numeric integer range |
| `uniqueItems` | boolean |
| `minimum`, `maximum`, `exclusiveMinimum`, `exclusiveMaximum`, `multipleOf` | numeric scalar or explicit numeric scalar node |
| `enum` | list of scalar values or explicit scalar nodes |

A schema keyword value with the wrong shape MUST be rejected. Implementations MUST NOT coerce schema keyword values across scalar types.

### 9.2.2 Schema applicability (normative)

Each schema keyword applies only to the effective node kind for which it is defined.

- String constraints apply only to effective Text values.
- Numeric constraints apply only to effective Integer or Double values.
- List constraints apply only to effective list payloads.
- Object field-count constraints apply only to effective object payloads.
- `enum` applies to scalar values unless an explicit scalar-node enum entry is used.
- `required` applies to the child field declaration at the path where it appears.

If a schema keyword is evaluated against an incompatible effective node kind, validation MUST fail with a schema violation. Implementations MUST NOT silently ignore incompatible schema keywords.

### 9.2.3 Required fields (normative)

`required: true` on a child field declaration requires that the field be semantically present in resolved descendants.

A required field is satisfied only if the resolved child node contains at least one of:

- a scalar payload `value`;
- a list payload `items`, including an empty list;
- an object payload with at least one ordinary child field;
- a pure reference;
- a fixed payload or fixed subtree inherited from an ancestor type.

A metadata-only child declaration, such as a node containing only `type`, `schema`, `name`, or `description`, does not by itself satisfy `required: true`.

If a field is required but has no semantic payload or fixed inherited content after resolution and cleaning, validation MUST fail.

### 9.2.4 Field counting (normative)

`minFields` and `maxFields` count ordinary child fields of the effective object payload after resolution and object-field cleaning.

Reserved language fields such as `name`, `description`, `type`, `schema`, `contracts`, `value`, and `items` do not count as ordinary fields.

Fields removed by object-field cleaning do not count. Inherited ordinary child fields that are materialized in the Resolved Form do count.

### 9.3 Presence

```yaml
required: true
```

When a schema with `required: true` is attached to a child field in a type or object overlay, that field MUST be semantically present in resolved descendants according to §9.2.3. If used at a document root, `required` is trivially satisfied by the existence of the root node.

### 9.4 Lists

```yaml
minItems: <non-negative integer>
maxItems: <non-negative integer>
uniqueItems: true | false
```

`maxItems` MUST be greater than or equal to `minItems` when both are present.

`uniqueItems: true` compares items by item BlueId, not by textual rendering.

### 9.5 Objects

```yaml
minFields: <non-negative integer>
maxFields: <non-negative integer>
```

`maxFields` MUST be greater than or equal to `minFields` when both are present.

The term **fields** is used because Blue objects have direct ordinary fields and no `properties` wrapper.

### 9.5.1 Dictionary direct encoding validation (normative)

For direct Dictionary object encoding, each direct key MUST be valid under the effective `keyType`.

For direct object encoding, `keyType` MUST resolve to one of the scalar key types with a canonical textual representation: Text, Integer, Double, or Boolean. If `keyType` is omitted and no effective `keyType` is inherited, it defaults to Text.

A key's serialized object-member name MUST be exactly the canonical textual form of the parsed key value. If two key values canonicalize to the same object-member string, the document has a duplicate key conflict and MUST be rejected.

Every value in a Dictionary with an effective `valueType` MUST resolve as an instance of, or subtype-compatible with, the effective `valueType`.

Applications needing arbitrary non-scalar keys or reserved-key collisions MUST use an application-defined escaped representation rather than direct object encoding.

### 9.6 Numerics

```yaml
minimum: number
maximum: number
exclusiveMinimum: number
exclusiveMaximum: number
multipleOf: number
```

Numeric schema keyword values MAY be authored in either scalar form or explicit scalar-node form.

Scalar form:

```yaml
schema:
  minimum: 5
```

Explicit scalar-node form:

```yaml
schema:
  minimum:
    type: Integer
    value: "9007199254740992"
```

A quoted decimal string without explicit `type: Integer` is Text and MUST NOT be accepted as a numeric constraint.

Rules:

- `minimum: m` means the numeric value must be greater than or equal to `m`.
- `maximum: m` means the numeric value must be less than or equal to `m`.
- `exclusiveMinimum: m` means the numeric value must be strictly greater than `m`.
- `exclusiveMaximum: m` means the numeric value must be strictly less than `m`.
- `multipleOf` must be greater than zero.

If multiple numeric constraints appear in the type chain, the value must satisfy all of them. For integer `multipleOf` constraints, implementations MUST combine compatible constraints using least common multiple (LCM). The effective merged schema MUST contain one `multipleOf` value equal to that LCM, and the Resolved Form and Canonical Identity Input MUST NOT preserve an implementation-specific list of equivalent integer `multipleOf` constraints.

For `Double` `multipleOf`, both the tested value and the `multipleOf` constraint are interpreted as their exact IEEE 754 binary64 rational values after parsing. A Double value `v` satisfies `multipleOf: m` iff `m > 0` and the exact rational quotient `v / m` is an integer. Implementations MUST NOT use epsilon comparisons, decimal string rounding, host-language modulo on binary floating point, or implementation-specific approximation.

For cross-type numeric comparisons, an `Integer` value is interpreted as an exact rational integer. A `Double` bound or value is interpreted as its exact IEEE 754 binary64 rational value. Comparison between Integer and Double uses exact rational comparison.

A numeric token that cannot be parsed to a finite IEEE 754 binary64 value under §2.4 is invalid before schema evaluation.

Implementations MAY use arbitrary-precision rational arithmetic internally to implement these predicates. They MUST NOT expose host floating-point rounding differences in conformance behavior.

Numeric schema keyword values follow the same numeric representation rules as scalar values (§2.4). Integer constraints outside the safe JSON numeric integer range MUST be represented as typed Integer scalar nodes that preserve exact integer identity. Quoted decimal text without explicit Integer typing is Text and MUST NOT be treated as a numeric schema constraint.

### 9.7 Strings

```yaml
minLength: <non-negative integer>
maxLength: <non-negative integer>
```

Length is measured in Unicode code points. `maxLength` MUST be greater than or equal to `minLength` when both are present.

### 9.8 Enumerations

```yaml
enum: [v1, v2, ...]
```

Enumeration values are scalar Blue values. They MAY be authored as bare scalars when unambiguous, or as explicit scalar nodes with `type` and `value` when type disambiguation is required, for example for large integers represented as quoted canonical decimal text. Equality is by parsed scalar value, effective scalar type, and canonical JSON value semantics, not by textual rendering.

`enum` comparison is performed after preprocessing and scalar type inference. Therefore the untyped enum entry `1` is an `Integer`, while `1.0` and `1e0` are `Double`. A quoted decimal string is Text unless authored as an explicit `Integer` scalar node.

Example with a large integer enum value:

```yaml
schema:
  enum:
    - 1
    - 1.0
    - type: Integer
      value: "9007199254740992"
```

The first two enum entries above are distinct because their effective scalar types are different.

There is no separate `const` keyword. A fixed value in a type enforces a constant.

### 9.8.1 Enumeration normalization (normative)

`enum` is a set of allowed scalar identities. Authoring order is not semantic.

During schema validation, schema merge, and canonicalization, each enum entry MUST be normalized to its typed scalar identity: effective scalar type plus canonical scalar value. Duplicate entries with the same typed scalar identity are redundant and MUST be removed in the effective schema.

The canonical enum representation MUST sort entries by the RFC 8785 canonical JSON byte sequence of their typed scalar identity form. If two entries have identical canonical bytes, they are duplicates and only one is retained.

Therefore these schemas are semantically equivalent and MUST canonicalize identically:

```yaml
schema:
  enum: [A, B]
```

```yaml
schema:
  enum: [B, A, A]
```

The effective canonical enum contains `A` and `B` once each, in the canonical ordering defined above.

### 9.9 Schema merge rules (normative)

When schemas accumulate along the type chain, implementations MUST merge keyword constraints as follows:

| Keyword | Merge rule | Failure case |
|---|---|---|
| `required` | logical OR | never, for the keyword itself |
| `minItems` | maximum | merged `minItems > maxItems` |
| `maxItems` | minimum | merged `maxItems < minItems` |
| `uniqueItems` | logical OR | never, for the keyword itself |
| `minFields` | maximum | merged `minFields > maxFields` |
| `maxFields` | minimum | merged `maxFields < minFields` |
| `minimum` | strongest lower bound | incompatible with upper bounds |
| `maximum` | strongest upper bound | incompatible with lower bounds |
| `exclusiveMinimum` | strongest exclusive lower bound | incompatible with upper bounds |
| `exclusiveMaximum` | strongest exclusive upper bound | incompatible with lower bounds |
| `multipleOf` | all constraints must hold; integer constraints MUST be merged to their LCM; Double constraints MUST be evaluated by exact rational arithmetic over IEEE 754 binary64 values under §9.6 | no possible numeric value satisfies all constraints |
| `minLength` | maximum | merged `minLength > maxLength` |
| `maxLength` | minimum | merged `maxLength < minLength` |
| `enum` | normalize both sides under §9.8.1, then intersect by typed scalar identity; canonical effective enum is duplicate-free and sorted under §9.8.1 | empty intersection |

For lower/upper-bound interactions, an exclusive bound at the same numeric value is stricter than an inclusive bound. For example, `minimum: 5` merged with `exclusiveMinimum: 5` yields `exclusiveMinimum: 5`.

---

## 10. Resolution

### 10.1 Resolution (normative)

**Resolution** applies Blue type and overlay semantics to a Source Node. It follows effective type links, merges inherited and instance contributions, enforces fixed values, applies list merge rules, accumulates schema constraints, and validates the resolved result.

A **complete Resolved Form** contains the complete semantic result for the root being resolved.

A **limited resolution result** contains only explicitly demanded paths and the supporting content needed to establish them. It is an operation result, not a different Blue node. Coverage and completeness information are out-of-band and do not affect BlueId.

For every path covered by limited resolution, the resulting value, effective type, and applicable constraints MUST be exactly the same as in complete resolution of the same source with the same provider content.

A complete Resolved Form is the input to minimization and canonicalization. An incomplete result MUST NOT be used to calculate Content BlueId, claim complete schema validity, or produce a whole-node Minimized Overlay.

### 10.2 Complete resolution algorithm (normative)

Given a Source Node `S`, complete resolution performs:

1. **Preprocess** `S` (§6), producing a Preprocessed Document.
2. **Resolve the type chain.** If `S.type` exists, recursively resolve it. If the type is a pure reference, expand it through a provider and verify the fetched content (§12.4). The result is the ancestor Resolved Form `A`.
3. **Merge ancestor and source.** Merge `A` into target `T`, then merge `S` into `T`:
    - **Root labels:** when merging a type into an instance root, do not copy the type root's `name` or `description` onto the instance root (§4.6).
    - **Values:** copy if absent; if both are present, they must be equal under fixed-value equality (§8.3).
    - **Types:** assign and propagate under §8.
    - **Schema:** accumulate under §9.
    - **Object fields:** merge recursively; children must remain compatible.
    - **Lists:** merge under §11.
    - **Contracts:** preserve and merge as identity-bearing content under §4.4; do not execute.
4. **Validate schema** after merging.
5. **Produce the complete Resolved Form.** Implementations MAY freeze it into an immutable snapshot when needed.

Schema validation is performed after inherited and instance values are merged at a node. Therefore an inherited schema applies to inherited fixed values, type-derived fields, and instance-supplied values in the final Resolved Form.

Type-chain resolution is depth-first: the effective ancestor type is resolved before it is merged into the descendant target. A resolver MUST track the active type-resolution stack for cycle detection.

### 10.2.1 Type-chain cycle detection (normative)

Type-chain cycles are invalid for Blue Language 1.0 resolution.

If resolving a node requires resolving a type that is already present on the active type-resolution stack, resolution MUST fail deterministically with a type-cycle error.

Example invalid cycle:

```yaml
# A
name: A
type:
  blueId: <B>

# B
name: B
type:
  blueId: <A>
```

Circular-set BlueIds (§15) identify cyclic document sets. They do not make cyclic inheritance or cyclic type chains resolvable. Blue Language 1.0 does not define fixed-point type semantics.

### 10.2.2 Complete resolution pseudocode (informative)

```text
resolve_complete(source, provider):
    S = preprocess(source)
    if S.type exists:
        T_ref = normalize_type_reference(S.type)
        T_node = expand_reference(T_ref, provider)
        A = resolve_complete(T_node, provider)
    else:
        A = empty node
    R = merge_as_instance(ancestor=A, instance=S, path="/")
    validate_schema_recursively(R)
    return ResolvedForm(R, provenance, complete=true)

merge_as_instance(ancestor, instance, path):
    T = copy_type_derived_content(ancestor, path)
    if path == "/" and ancestor is the effective type of instance:
        do not copy ancestor.name or ancestor.description to T
    merge reserved metadata using field-specific rules
    merge ordinary child fields recursively
    merge lists using §11
    merge contracts using §4.4
    reject fixed-value, type, schema, or payload-kind conflicts
    record provenance for each retained contribution
    return T
```

Precise implementation structure is not normative. The observable complete Resolved Form, validation behavior, canonicalization provenance, and resulting Content BlueId are normative.

### 10.3 Limited resolution (normative)

A resolver MAY accept out-of-band **Limits** that identify demanded paths or bound work. Typical limits include selected operation paths, maximum reference expansions, maximum graph depth, and maximum nodes visited.

For a requested path, limited resolution MUST resolve the complete semantic dependency closure required to establish that path. This may include:

- the source node and ancestors along the path;
- effective type nodes and inherited fields contributing at the path;
- applicable schema and collection constraints;
- object keys or list positions required by the requested operation;
- provider content needed to verify and interpret those contributions.

A limited resolver MUST NOT:

- treat an unexpanded reference as an empty object or missing field;
- report a field as semantically absent unless absence has been established from the required source and type contributions;
- return a guessed value when a limit prevents completion;
- expose provider, cache, or storage layout as semantic content.

When limits prevent a demanded result from being established, the operation MUST fail with a deterministic limit/incomplete result or explicitly report that the requested path is incomplete. It MUST NOT return a normal successful absence result.

Implementations may return demanded values directly or may return a partially materialized result with out-of-band coverage metadata. In either case, all covered values MUST equal complete resolution.

### 10.4 Resolution provenance (normative)

A conforming implementation performing complete resolution for canonicalization MUST track enough provenance to canonicalize deterministically. For each resolved path, it MUST be able to determine whether content was:

- **instance-supplied** by the Source Document after preprocessing;
- **type-derived** from an ancestor type;
- **provider-materialized** from a `blueId` reference;
- **preprocessing-derived** from mandatory or declared preprocessing;
- **merge-derived** from compatible instance and type contributions.

Limited resolution need track only the provenance required for its covered paths, unless the result will later be completed for canonicalization or minimization.

The exact internal representation is implementation-defined.

### 10.5 Identity guarantee (normative)

Resolution preserves semantic identity. A Source Document and its complete Resolved Form have the same Content BlueId when the complete Resolved Form is canonicalized.

Implementations MUST NOT assume that directly hashing a Resolved Form produces the Content BlueId.

Limited resolution does not create a new identity. It exposes only part of the semantics of the same source node.

### 10.6 Provider failures (normative)

A conforming implementation MUST expand referenced content when that content is required for the requested resolution, canonicalization, minimization, collapse verification, or validation. If required content is unavailable or fails verification, the operation MUST fail deterministically. Implementations MUST NOT silently substitute empty content for missing references.

Unrelated references outside the demanded dependency closure need not be fetched.

### 10.7 Limits (normative)

Limits are out-of-band operation controls. They MUST NOT be serialized into the Blue node, included in BlueId calculation, or alter the result that complete processing would produce.

An implementation SHOULD support path, depth, node-count, and reference-count limits for expansion and resolution of large graphs.

A result is complete only when every path and constraint required by the requested operation has been established. An incomplete result MUST NOT be used for whole-node Content BlueId, whole-node minimization, or a claim of complete validation.


### 10.8 Demand-limited operation outcomes (normative)

A demand-limited Language operation asks a semantic question about one or more selected paths without requiring complete graph expansion or complete document resolution.

Common demands include exact node identity, node kind, semantic existence, one object child, complete object keys, list length, one list item, effective type, applicable constraints, or the resolved value at a path.

The exact host-language API is not normative. A conforming operation MUST deterministically establish exactly one of these semantic conclusions:

- the requested result is established for the declared coverage;
- semantic absence is established from sufficient direct and inherited information;
- the request could not be completed because a limit, unavailable reference, unsupported provider operation, or another explicitly reported condition prevented proof;
- the demanded content or its required semantic closure is invalid.

Implementations MAY expose named result variants such as `Established`, `Absent`, `Incomplete`, and `Invalid`, but this specification does not require those class names or one particular public API.

Rules:

- a pure reference, cache miss, provider timeout, direct-node limit, or resolution limit MUST NOT be treated as semantic absence;
- a result established from graph-equivalent inline, collapsed, expanded, cached, or segmented forms MUST be the same once the same logical identities are available;
- a result that did not establish complete required coverage MUST NOT be used for whole-node canonicalization, Content BlueId calculation, complete minimization, or a claim of complete validation;
- diagnostic information about outstanding identities or covered paths is out-of-band and does not affect Blue content or identity.

### 10.9 Cache neutrality and diagnostic information (normative)

A Language implementation MAY expose diagnostic information such as demanded identities, covered paths, provider outcomes, semantic steps, or implementation timings.

Such diagnostics are not Blue content and do not affect identity. Cache state, prefetching, batching, storage pages, or previous operations MUST NOT change a successful semantic result or turn incomplete evidence into complete evidence.

Layered runtime specifications MAY define their own deterministic work ledger over Language operations. Such a ledger is not part of Blue content-language identity and MUST NOT redefine the semantic outcomes in §10.8.

## 11. Lists, Merge Policies, and List Control Forms

### 11.1 Authoring model (normative)

A list field SHOULD be authored in typed form when list semantics matter:

```yaml
<field>:
  type: List
  itemType: <Type>
  mergePolicy: append-only | positional
  items:
    - ...elements...
```

A surface list is permitted for simple cases:

```yaml
tags: [a, b, c]
```

Typed form is REQUIRED when `mergePolicy`, anchors, or overlays are used.

Every element of a resolved list with an effective `itemType` MUST resolve as an instance of, or subtype-compatible with, the effective `itemType`. If an item cannot be resolved or is incompatible with `itemType`, validation MUST fail.

If `itemType` is omitted and no effective inherited `itemType` exists, list elements are unconstrained by item type.

### 11.2 Allowed item forms inside `items` (normative)

Each item inside `items` MUST be exactly one of the following forms after Source Document preprocessing.

#### Normal element

```yaml
- <scalar | object | list | pure reference>
```

A normal element is content.

#### Append anchor

```yaml
- $previous:
    blueId: <PrevListBlueId>
```

Rules:

- `$previous` is allowed only as the first item.
- The shape MUST be exactly one top-level `$previous` key whose value is an object with exactly one `blueId` key.
- `$previous` is never content.

#### Positional overlay

Map overlay:

```yaml
- $pos: 1
  ...overlay fields...
```

Replacement overlay for an object:

```yaml
- $pos: 1
  $replace:
    type: Address
    city: Warsaw
```

Replacement overlay for a list:

```yaml
- $pos: 1
  $replace:
    items:
      - A
      - B
```

Replacement overlay for a pure reference:

```yaml
- $pos: 1
  $replace:
    blueId: X
```

Rules:

- `$pos` MUST be a non-negative integer using zero-based indexing.
- `$pos` is valid only when `mergePolicy: positional`.
- A `$pos` item without `$replace` is a map overlay. It is valid only when the inherited element at that index is an object-compatible node. If the inherited element is scalar, list, or pure reference, the overlay MUST use `$replace` and remain type-compatible.
- `$pos` overlays are consumed by resolution and do not appear as content in the final list.
- `$replace` is valid only inside a `$pos` item. Its value is a full Blue node used to replace the inherited element, subject to type and schema compatibility.
- For scalar replacement, the concise form below is equivalent to `$replace: { value: B }`:

```yaml
- $pos: 1
  value: B
```

The `value` form MUST NOT be used to carry list or object replacements. Use `$replace` for non-scalar replacements.

#### Placeholder element

```yaml
- $empty: true
```

`$empty: true` is content. It is a real element that occupies a position and affects BlueId. It is distinct from `null`, `{}`, and `[]`.

The shape MUST be exactly one top-level `$empty` key whose value is the boolean `true`. `$empty: false`, `$empty: null`, and `$empty` with sibling fields are invalid as list placeholder elements.

### 11.3 Scope of list control keys (normative)

The special keys `$previous`, `$pos`, `$replace`, and `$empty` are recognized only as top-level keys of elements inside a list payload.

`$empty` is valid in any list payload.

`$previous`, `$pos`, and `$replace` are list overlay controls. They are valid only when the list is being resolved as a typed or overlay-capable list. Authors SHOULD use the typed list form when using these controls.

Outside list-control position, `$previous`, `$pos`, `$replace`, and `$empty` are ordinary field names unless another specification gives them meaning. They do not act as list controls outside list elements.

### 11.4 Default merge policy (normative)

If no effective `mergePolicy` is inherited and no `mergePolicy` is authored on the list, resolvers MUST assume:

```yaml
mergePolicy: positional
```

If an inherited list has an effective `mergePolicy`, a descendant list overlay that omits `mergePolicy` inherits that effective policy. A descendant MAY repeat the same `mergePolicy`.

A descendant MUST NOT change an inherited `mergePolicy`. If an effective `mergePolicy` is inherited, omission by the descendant means inheritance, not defaulting. If no policy is inherited and no policy is authored, the effective default is `positional`.

In particular, `append-only` MUST NOT be weakened to `positional`.

For histories, ledgers, timelines, and append-only logs, authors MUST specify:

```yaml
mergePolicy: append-only
```

### 11.5 Semantics of `null`, `{}`, `[]`, and `$empty` (normative)

Blue distinguishes object-field absence from list position.

#### Object fields

In object fields, `null` means no information. Before hashing:

- fields whose value is `null` MUST be omitted;
- fields whose value normalizes to an empty object `{}` MUST be omitted;
- empty lists `[]` MUST be preserved.

This removal is recursive and may cascade.

#### List elements

List elements are positional. Implementations MUST NOT delete list elements during cleaning, because doing so changes list length and shifts later indices.

In Source Documents, a list element that is `null`, an empty object `{}`, or an object that recursively normalizes to an empty object after object-field cleaning MUST be normalized to:

```yaml
$empty: true
```

It MUST NOT be deleted from the list, because list position is content.

In Canonical Identity Input and BlueId Input, `null` list elements and empty-object list elements MUST NOT appear. They MUST already have been normalized to `$empty: true` or rejected.

The marker `$empty: true` is content. It occupies a list position and affects BlueId.

Empty lists `[]` are preserved as list elements and are distinct from `$empty: true`.

Consequences:

```text
id([A, null, B] after preprocessing) == id([A, {$empty: true}, B])
id([A, null, B] after preprocessing) != id([A, B])
id([A, {}, B] after preprocessing) == id([A, {$empty: true}, B])
id([A, [], B]) != id([A, {$empty: true}, B])
```

### 11.6 Merge semantics (normative)

Let `P` be the resolved parent list and `C` be the child overlay list.

#### `append-only`

For `mergePolicy: append-only`:

- inherited indices `< length(P)` MUST NOT be modified or deleted;
- `$pos` overlays are forbidden;
- normal items after the inherited prefix are appended;
- an optional `$previous` anchor may appear as the first child item.

Errors:

- any `$pos` overlay;
- malformed `$previous`;
- `$previous` not first;
- repeated `$previous`;
- attempted modification, removal, or reordering of the inherited prefix.

#### `positional`

For `mergePolicy: positional`:

- `$pos: i` refines inherited index `i`, where `0 <= i < length(P)`;
- map overlays merge field-wise, subject to type and schema compatibility;
- `$replace` overlays replace the inherited element, subject to compatibility;
- scalar `value` overlays replace the inherited element with a scalar node, subject to compatibility;
- normal items without `$pos` are appended after the inherited prefix in author order;
- reordering, removal, and gaps within the inherited prefix are forbidden.

Errors:

- `$pos` missing or non-integer;
- `$pos` out of range;
- duplicate overlays for the same index;
- type or schema incompatibility at the index;
- attempted reordering or removal of parent elements;
- `value` used as a non-scalar positional replacement.

### 11.7 `$previous` validation (normative)

`$previous` is a resolution-time anchor.

During resolution, the resolver MUST verify that the inherited prefix hashes to `$previous.blueId`. If it does not match, resolution MUST fail.

During direct Node BlueId calculation of valid BlueId Input that already contains a leading `$previous`, the anchor MAY be used as a list-fold seed (§14.8). Validity of the anchor is a precondition of the input. An implementation performing direct Node BlueId calculation without resolution context MAY reject `$previous` inputs.

A direct hasher MUST NOT silently ignore `$previous` and recompute when it cannot verify the prefix. A direct hasher has no provider or inheritance context and therefore cannot determine whether an anchor is stale.

### 11.8 List conformance checklist (normative)

Implementations supporting lists MUST satisfy:

- `id([])` is defined and distinct from absent values and cleaned object fields;
- `[A]` hashes differently from `A`;
- `[[A, B], C]` hashes differently from `[A, B, C]`;
- Source list `[A, null, B]` normalizes to `[A, {$empty: true}, B]`, not `[A, B]`;
- Source list `[A, {}, B]` normalizes to `[A, {$empty: true}, B]`, not `[A, B]`;
- Source list `[A, {x: null}, B]` normalizes to `[A, {$empty: true}, B]`, not `[A, B]`;
- `$previous` is recognized only as the first item;
- `$previous` mismatch fails resolution;
- `append-only` rejects `$pos`;
- inherited `append-only` remains effective when a child overlay omits `mergePolicy`;
- `positional` accepts valid `$pos` overlays and rejects duplicate or out-of-range overlays;
- `$empty: true` remains content and affects BlueId;
- malformed `$empty` placeholder items are rejected;
- object-field cleaning removes `null` and object fields that normalize to `{}`, but does not delete list positions.

### 11.9 Worked examples (informative)

Present-empty vs absent:

```yaml
# Absent
doc: {}

# Present-empty
doc:
  list:
    type: List
    items: []
```

Append-only timeline:

```yaml
# Parent
entries:
  type: List
  itemType: Timeline Entry
  mergePolicy: append-only
  items:
    - { type: Timeline Entry, ts: "2025-09-01T12:00:00Z", message: A }
    - { type: Timeline Entry, ts: "2025-09-01T12:05:00Z", message: B }

# Child
entries:
  type: List
  itemType: Timeline Entry
  mergePolicy: append-only
  items:
    - $previous: { blueId: PrevId }
    - { type: Timeline Entry, ts: "2025-09-01T12:10:00Z", message: C }
```

Positional hole and refinement:

```yaml
# Parent
entries:
  type: List
  mergePolicy: positional
  items:
    - A
    - $empty: true
    - C

# Child
entries:
  type: List
  mergePolicy: positional
  items:
    - $pos: 1
      value: B
# Resolved: [A, B, C]
```

---

## 12. References, Providers, Expansion, and Collapse

### 12.1 Providers (informative)

A **BlueId provider** retrieves Blue content by BlueId.

Providers may be local maps, databases, object stores, package registries, network services, or composed provider chains.

### 12.2 Provider trust model (normative/informative)

A provider is not trusted merely because it returned content. Returned content MUST verify against the requested BlueId before it is used as that node.

Provider location, cache state, transfer size, paging, and physical storage layout are not Blue Language semantics.

### 12.3 Provider content form (normative)

The default portable provider model returns BlueId Input or cyclic-set-aware member content appropriate to the requested identity.

A Source Document provider MAY be supported as an implementation extension or registry mode. Such a provider verifies returned content by Content BlueId, not direct Node BlueId. The provider mode MUST bind the exact Blue Language release, preprocessing environment, canonical registry bindings, and the exact Source Document snapshot or other identity-bearing evidence being resolved. Ambient provider state is never part of Content BlueId. A Source Document provider is not the default portable provider model.

### 12.4 Plain BlueId provider verification (normative)

For an ordinary BlueId `X`, provider content is valid only if direct Node BlueId calculation over the returned BlueId Input produces `X`.

If verification fails, the demanding operation MUST fail deterministically.

Implementations MUST NOT silently use provider content whose computed BlueId differs from the requested BlueId.

### 12.5 Cyclic-set member provider verification (normative)

A cyclic member BlueId `<MASTER>#<index>` is verified in the context of its complete declared cyclic set under §15. The provider or caller must supply enough context to reconstruct and verify the set.

An implementation MUST NOT verify `<MASTER>#<index>` by hashing the returned member alone.

### 12.6 Expansion (normative)

**Expansion** replaces selected pure references with verified materialized content.

Given:

```yaml
field:
  blueId: X
```

expansion fetches content for `X`, verifies it (§12.4), and makes that content available at `field`. Nested references remain collapsed unless they are also demanded by the operation and permitted by its Limits.

Expansion may begin at a document root that is itself a pure reference.

Expansion changes representation, not meaning. It MUST preserve Node BlueId. A pure reference contributes its target BlueId, and verified materialized content contributes that same identity.

A conforming expansion API SHOULD accept operation paths and limits. Its **semantic demand closure** MUST contain only references needed for the requested result. References left outside that closure, or left collapsed because of a limit, MUST NOT be treated as absent content.

An implementation MAY physically prefetch additional verified nodes. Prefetched content outside the semantic demand closure MUST NOT enter the operation result, change completeness, affect identity, or alter a layered portable work ledger. Provider caching, internal paging, and physical storage chunks are implementation details and MUST NOT change the expanded result.

### 12.7 Collapse (normative)

**Collapse** replaces selected materialized content with a pure reference `{ blueId: X }` to the same node.

Collapse is permitted when the node's Node BlueId is known or has been calculated and, for provider-originated content, verification established that identity. The collapsed result MUST be a pure reference with no sibling fields.

Collapse changes representation, not meaning, and MUST preserve the enclosing node's Node BlueId.

An implementation MAY collapse the document root, an object field, a list element, a type node, a workflow body, or any other complete Blue node. It MAY leave other parts materialized.

### 12.8 Expansion, resolution, and limits (normative)

Expansion and resolution are composable but distinct:

- expansion obtains referenced node content;
- resolution interprets type and overlay semantics;
- a resolver expands only references needed for the demanded semantic result;
- unrelated branches may remain collapsed in a successful operation result when their identity is sufficient and their internal content is not needed by that operation;
- a limited result MUST explicitly report incompleteness when demanded semantics cannot be established.

Limits affect work, not meaning. The same demanded path resolved from an inline node and from a verified pure reference MUST produce the same value and effective type.

### 12.9 Graph boundary (normative)

A Blue Document need not be a closed tree. A `{ blueId: ... }` reference may point outside the serialized document. Implementations materialize referenced content only as needed and within configured limits.

The fact that a referenced node is stored in another file, database row, object-store chunk, or network location has no Blue Language meaning.

### 12.10 Blue Language operation paths (normative when exposed)

Blue Language operation paths are out-of-band selectors used for expansion limits, collapse selection, limited resolution, diagnostics, and provenance. They are not Blue content and do not affect BlueId.

A conforming implementation that exposes path-limited operations MUST support RFC 6901 JSON Pointer paths over the abstract Blue node model:

- the empty string `""` selects the root node;
- `/field` selects an object field named `field`;
- `/items/0` selects list payload item index `0` in the abstract node model;
- `~0` represents `~`, and `~1` represents `/`, following RFC 6901.

The wildcard `*`, such as `/spent/*`, is not part of the required Blue Language 1.0 path grammar. Implementations MAY support wildcards as an extension, but portable conformance fixtures MUST use RFC 6901 paths unless a future path-selector specification defines more.

### 12.11 Direct-node materialization pattern (informative)

An implementation may keep one selected node materialized while collapsing any or all complete direct children to pure references. This is ordinary expansion and collapse with a depth or path limit; it is not a fifth Language operation or a new node form.

For an object, such a representation normally retains the complete direct key set, inline identity-bearing metadata such as `name`, `description`, and scalar `value`, and the exact Node BlueId of every other direct child. For a list, it normally retains list metadata and the ordered exact Node BlueId of every direct element. Metadata-only nodes, including nodes carrying `type`, `schema`, `mergePolicy`, or `contracts`, follow the same rule: direct identity-bearing content remains available and complete child nodes may be collapsed.

This representation has the same Node BlueId as the fully materialized node. Under the map and list hashing rules in §14, the selected direct node can be verified without fetching transitive descendant bodies. This is the language-level reason path-by-path graph navigation is possible.

### 12.12 Provider and storage guidance (informative)

A content-addressed provider can support practical lazy expansion by storing every admitted node in direct-node materialization pattern, keyed by exact Node BlueId, and fetching one direct node at a time along a demanded path.

A useful provider distinguishes:

```text
Found            verified exact node content is available
NotFound         definitive absence in the provider's declared domain
Unavailable      transient infrastructure failure
InvalidEvidence  returned content failed verification
```

These outcomes are provider or host concerns. `NotFound` and `Unavailable` do not mean that a graph path is semantically absent. Provider transport, batching, authorization, storage layout, and retry rules are outside this Language specification.

The current BlueId algorithm requires a complete direct manifest to verify an ordinary object or list node. It does not provide logarithmic proofs for one member of a very wide direct container. Applications requiring large mutable maps, vectors, text, or blobs SHOULD use bounded-fanout content-addressed structures.

## 13. Canonicalization and Minimization

### 13.1 Distinction (normative)

Blue defines two operations that may both reduce explicit content but serve different purposes.

**Minimization** takes a complete Resolved Form and produces a smaller Source overlay that resolves back to the same complete Resolved Form. Resolution and minimization are semantic counterparts, but minimization is not necessarily unique.

**Canonicalization** derives the one deterministic BlueId Input used to compute Content BlueId. Canonicalization is an identity operation, not an authoring preference.

A runtime processor does not need to minimize a whole document after every read or patch. It may preserve unchanged nodes by BlueId and use ordinary collapse. Whole-node minimization is needed only when a reduced Source overlay is requested.

### 13.2 Canonical Identity Input (normative)

A **Canonical Identity Input** is the deterministic identity form derived from a complete Resolved Form. It contains the deterministic identity-bearing content needed for BlueId calculation. It may contain final canonical payloads, including final list payloads, that are not ordinary Source overlays. A Canonical Identity Input MUST be valid BlueId Input. It is not required to be accepted as a Source Document or to re-resolve under ordinary Source overlay semantics.

The Content BlueId of a Source Document is the Node BlueId of its Canonical Identity Input.

A Canonical Identity Input is unique for a given complete Resolved Form under the selected Blue Language release and canonical registry bindings. The provider may be needed to obtain verified referenced nodes, but its cache, location, response order, availability history, and other ambient state do not participate in canonical identity.

### 13.3 Minimized Overlay (normative)

A **Minimized Overlay** is an author-facing reduced Source overlay that re-resolves to the same complete Resolved Form.

A conforming implementation MUST implement canonicalization. A conforming implementation MAY expose minimization. If it does, every whole-node Minimized Overlay it produces MUST be based on a complete Resolved Form, MUST re-resolve to that same form, and MUST produce the same Content BlueId through the full identity pipeline.

Different minimizers MAY produce different valid Minimized Overlays. Such overlays MAY have different direct Node BlueIds, but when processed through the full identity pipeline they MUST produce the same Content BlueId.

A Minimized Overlay MAY use authoring controls such as `$previous`, `$pos`, and `$replace` when valid, and MAY collapse complete subtrees to verified pure references under §13.7.

### 13.4 Canonicalization requirements (normative)

Given a Resolved Form `R`, canonicalization MUST:

- preserve all instance contributions that are not derivable from the type chain;
- remove fields fully derivable from the type chain;
- preserve instance-level `name` and `description` when present on the instance;
- not inherit top-level `name` or `description` from the type;
- preserve instance-fixed values that are not derivable from the type chain;
- replace materialized type objects with canonical `type: { blueId: ... }` references when their BlueId is known;
- ensure the Canonical Identity Input contains no type aliases; if an instance supplied a type alias, preprocessing MUST replace it with the canonical `type: { blueId: ... }` reference before resolution;
- for provider-materialized content, preserve the original pure reference when that reference is an instance contribution and the materialized subtree contributes no additional instance-supplied content;
- remove the `blue` directive if present, because it is invalid after preprocessing;
- normalize list placeholders so that list `null` and empty-object elements become `$empty: true`;
- consume all `$pos` overlays and produce final canonical list content;
- produce valid BlueId Input.

Schema objects included in Canonical Identity Input MUST use normalized effective schema form. In particular, `enum` values are duplicate-free and sorted under §9.8.1, and integer `multipleOf` constraints are represented by the merged LCM value rather than by raw inherited/descendant contributions.

### 13.5 Canonicalization as deterministic diff (normative)

Canonicalization can be understood as a deterministic diff between the Resolved Form and the resolved ancestor form contributed by the effective type chain.

For each node:

1. If the node has an effective type, include the canonical type reference unless the type reference itself is fully derivable at that path and not required by the canonical identity form.
2. For each reserved metadata field other than `type`, include it only when it is an instance contribution that is not derivable from the ancestor form, except where this specification requires preservation.
3. For each ordinary child field, omit it when the child is fully derivable from the ancestor form. Otherwise include the canonical identity input of the child.
4. For scalar values, omit an inherited fixed value and include an instance value not derivable from the ancestor.
5. For lists, use the canonical list rules in §13.6.
6. After the identity input is constructed, apply BlueId input normalization and object-field cleaning. Empty object fields are omitted. Empty lists are preserved.

Implementations MUST make all tie-breakers deterministic and covered by conformance vectors.

### 13.5.1 Canonicalization tie-breakers (normative)

When multiple candidate identity inputs would represent the same Resolved Form, the Canonical Identity Input MUST be selected by the following tie-breakers, in order:

1. **Omit derivable non-list content.** A field, metadata entry, or non-list subtree that is fully derivable from the effective type chain MUST be omitted from the Canonical Identity Input, unless another rule in this section explicitly requires it. **List payloads are special:** for list nodes, §13.6 overrides this general omission rule. Canonicalization of a list produces the final canonical list payload for identity calculation, including inherited prefix elements, positional refinements, append-only appends, and `$empty` placeholders after normalization.
2. **Preserve non-derivable instance content.** Content supplied by the instance or Source Document and not derivable from the type chain MUST be preserved.
3. **Use pure references for referenced ancestors/types.** A materialized type or referenced ancestor whose BlueId is known MUST be represented as `{ blueId: X }` in type positions and other reference-preserving positions.
4. **Preserve source pure references materialized only for resolution.** If a Source Document provided a pure reference and the provider materialized it only to resolve or validate content, the Canonical Identity Input MUST prefer the original pure reference form unless the instance supplied an overlay that must be represented.
5. **Consume overlay controls.** `$pos`, `$replace`, `$previous`, source list `null`, and empty-object list elements MUST NOT appear in Canonical Identity Input. Their effects must be represented as ordinary canonical content.
6. **No authoring aliases.** Type aliases and `blue` preprocessing directives MUST NOT appear in Canonical Identity Input.
7. **Deterministic map ordering.** When serializing helper maps or canonical JSON, property order is the order defined by RFC 8785 canonical JSON. No locale-sensitive ordering, implementation insertion order, or host map order is permitted.
8. **Smallest semantic identity input wins.** If two candidate identity inputs both satisfy the rules above, the one with fewer non-derivable fields and fewer materialized subtrees wins. If still tied, the RFC 8785 canonical JSON byte sequence of the candidate identity input is compared lexicographically and the smaller byte sequence wins.

These rules are part of the Blue Language 1.0 identity definition and MUST be implemented consistently. The conformance fixture suite provides examples but does not replace these rules.

### 13.6 Canonical list rules (normative)

Canonical list rules produce final list payload content for identity calculation.

For list payloads, final canonical list content is the canonical identity form. This rule overrides the general "omit derivable content" tie-breaker in §13.5.1. Blue Language 1.0 does not define a canonical list-diff representation.

For a list with no inherited prefix, the Canonical Identity Input contains the canonicalized full list.

For an inherited list under `mergePolicy: append-only`, a Minimized Overlay MAY use a valid `$previous` anchor followed by appended elements. A Canonical Identity Input MUST NOT contain `$previous`. Canonicalization MUST produce the final canonical list payload before hashing. Implementations MAY internally optimize list hashing by using a verified inherited-prefix BlueId, but that optimization is not part of the serialized Canonical Identity Input.

For an inherited list under `mergePolicy: positional`, a Minimized Overlay MAY represent inherited-index refinements using `$pos` overlays. A Canonical Identity Input MUST NOT contain `$pos`. Canonicalization MUST apply all positional overlays and produce the final canonical list payload before hashing.

A final canonical list payload in Canonical Identity Input is identity input, not an instruction to append to or refine an inherited list under ordinary Source overlay semantics.

### 13.7 Deterministic collapse during minimization (normative)

A Minimized Overlay MAY collapse a subtree to `{ blueId: X }` only when:

1. the subtree's Node BlueId is known to be `X`;
2. provider verification has established that `X` identifies that content if the subtree came from a provider;
3. collapse at that path is deterministic under the implementation's declared minimization rules;
4. the collapsed overlay re-resolves to the same Resolved Form.

A Canonical Identity Input MUST follow the deterministic canonicalization rules. Unless this specification explicitly requires collapse at a path, Canonical Identity Input MUST prefer the materialized canonical identity form. Optional collapse is an author-facing minimization feature, not a source of variation in Content BlueId.

A Canonical Identity Input MUST NOT depend on implementation-local collapse preferences.

---

## 14. BlueId Algorithm

### 14.1 Hash function (normative)

Let:

```text
H(x) = Base58(SHA-256(RFC 8785 canonical JSON of x))
```

BlueId is computed bottom-up over canonical BlueId Input using `H`.

### 14.2 Context-sensitive cleaning and placeholder normalization (normative)

Before hashing, implementations MUST normalize BlueId Input context-sensitively.

#### Object-field cleaning

For object fields:

- remove fields whose value is `null`;
- remove fields whose value normalizes to an empty object `{}`;
- preserve fields whose value is an empty list `[]`;

This removal is recursive and may cascade.

#### List-element rules

For list elements:

- list elements MUST NOT be deleted merely because they are `null` or `{}`;
- in Source Documents, `null`, `{}`, and elements that recursively clean to empty objects MUST have been normalized to `$empty: true` before BlueId calculation;
- in BlueId Input, `null` and `{}` list elements are invalid;
- `[]` is preserved as an empty list element;
- `$empty: true` is preserved as placeholder content.

This rule preserves list length, order, and positional meaning.

In object-field context, an object that becomes empty after cleaning is omitted. In list-element context, a Source element that becomes empty after recursive cleaning is normalized to `$empty: true` before BlueId Input is produced. Direct BlueId Input MUST NOT contain raw empty-object list elements.

#### Root normalization

The root of BlueId Input is never omitted by cleaning.

If the root is an empty object `{}`, its Node BlueId is `H({})`.

If object-field cleaning causes the root object to become empty, the root remains `{}` and hashes as `H({})`.

A root `null` value is not valid BlueId Input. Source Documents whose root is `null` MUST be rejected. Authors who intend an empty object document MUST write `{}`; authors who intend an empty list document MUST write `[]`.

### 14.3 Canonical BlueId input normalization (normative)

The BlueId algorithm hashes the abstract node model, not authoring syntax.

Direct Node BlueId calculation does not run the full Source Document preprocessing pipeline. However, BlueId input normalization includes the mandatory primitive scalar inference needed to make bare scalar nodes identity-stable across conforming implementations. This inference is limited to the core primitive types listed below and does not apply aliases, imports, `blue` directives, or declared preprocessing transforms.

Before hashing a Node value:

- scalar sugar is normalized to scalar payload;
- list sugar is normalized to list payload;
- bare scalar payloads with no explicit type are assigned the corresponding core primitive type reference;
- integer values outside the safe JSON numeric integer range are represented as quoted canonical decimal text while retaining explicit `Integer` type (§2.4);
- finite `Double` values are converted to their canonical scalar representation;
- pure references are represented exactly as `{ blueId: X }`;
- `blue` is rejected;
- `$pos` is rejected;
- list `null` and empty-object elements are rejected unless already normalized to `$empty: true`.

Primitive scalar inference for BlueId input normalization uses:

| Parsed value kind | Inferred type |
|---|---|
| string | `Text` |
| integer numeric token with no decimal point or exponent, or explicitly typed canonical integer text | `Integer` |
| numeric token with a decimal point or exponent, or other non-integer finite number | `Double` |
| boolean | `Boolean` |

A scalar payload with explicit type uses the explicit type, subject to resolution and validation.

### 14.4 Scalars (normative)

For BlueId calculation, every scalar payload node is normalized to a **typed scalar identity form** before hashing. If no explicit effective type is present, the inferred primitive type from §14.3 is inserted. Therefore an untyped Source scalar token `1` hashes as a scalar node with effective type `Integer`, while source tokens `1.0` and `1e0` hash as scalar nodes with effective type `Double`. The effective scalar type is part of identity.

A bare scalar payload is represented as the canonical scalar value and, when converted to canonical BlueId input as a node, includes its inferred primitive type unless an explicit type is already present.

Scalar values are encoded using RFC 8785 canonical JSON value rules after Blue scalar normalization.

For `Integer`, implementations MUST preserve mathematical integer identity. Integer values outside the safe JSON numeric integer range MUST be encoded as canonical decimal text while retaining `type: Integer` in the canonical BlueId input (§2.4).

For `Double`, only finite numbers are valid. `NaN`, `Infinity`, and `-Infinity` are invalid Blue scalar values.

A `Double` value whose canonical JSON number renders as an integer-looking number, such as `1`, remains distinct from `Integer` because the canonical BlueId input retains `type: Double`. Numeric rendering alone does not determine scalar type after preprocessing.

### 14.4.1 Payload normalization before hashing (normative)

The BlueId algorithm hashes the abstract Blue node model, not raw JSON/YAML syntax.

Before map hashing is applied, each node is classified as one of:

1. pure reference;
2. scalar payload node;
3. list payload node;
4. object payload node;
5. metadata-bearing node.

A node with a scalar payload and no retained metadata other than its effective scalar type and value hashes as the typed scalar identity form. "Payload-only scalar" does not mean hashing the raw JSON scalar alone; it means hashing the canonical Blue scalar node consisting of the effective primitive type reference and the canonical scalar value. If no explicit effective type is present, the inferred primitive type is inserted before hashing.

A node with a list payload and no retained metadata other than the payload itself hashes as the list payload.

Therefore these forms hash identically:

```yaml
x: 1
```

```yaml
x:
  value: 1
```

and these forms hash identically:

```yaml
x: [a, b]
```

```yaml
x:
  items: [a, b]
```

Thus these Source scalar tokens do not all have the same typed scalar identity unless an explicit type or schema says otherwise:

```yaml
1    # effective type Integer, value 1
1.0  # effective type Double, canonical numeric payload may render as 1
1e0  # effective type Double, canonical numeric payload may render as 1
```

`1.0` and `1e0` are equivalent Double values, but they are not equivalent to Integer `1` because the effective type differs.

When a node has retained metadata such as `type`, `schema`, `name`, `description`, `itemType`, `mergePolicy`, or `contracts`, it hashes as a metadata-bearing map. In that case, `value` or `items` is the payload field of that metadata-bearing node and participates in map hashing as defined below.

A node MUST NOT contain more than one payload kind.

### 14.5 Map hashing (normative)

Map hashing applies only after payload-only scalar and payload-only list nodes have been normalized as described above.

If and only if a map is exactly:

```json
{ "blueId": "<id>" }
```

then its BlueId is `<id>`. This is the pure reference short-circuit.

A map containing `blueId` together with sibling fields is not a pure reference and MUST NOT appear in BlueId Input.

Otherwise, build the helper map `M` conceptually. Its serialized property order is the order defined by RFC 8785 canonical JSON. Implementations MUST NOT use locale-sensitive collation or implementation insertion order.

- for `name`, `description`, and `value`, inline their cleaned scalar values;
- for every other key `k` with value `v`, include:

```json
"k": { "blueId": id(v) }
```

Then compute:

```text
id(map) = H(M)
```

This rule ensures nested structure contributes through BlueId rather than through byte shape. It also makes materialized subtrees and pure references identity-equivalent when they have the same BlueId.

### 14.6 Object fields with `null` (normative)

Object fields with `null` values are omitted before map hashing:

```yaml
a: null
b: 1
```

normalizes as:

```yaml
b: 1
```

If recursive cleaning makes a child object empty, the child field is also omitted. Empty lists are preserved.

### 14.7 List hashing (normative)

Lists are hashed using a domain-separated streaming fold over element BlueIds.

Empty list seed:

```text
id([]) = H({ "$list": "empty" })
```

Fold step:

```text
fold(prevId, x) =
  H({
    "$listCons": {
      "prev": { "blueId": prevId },
      "elem": { "blueId": id(x) }
    }
  })
```

The object passed to `H` in the fold step is serialized by RFC 8785; therefore property serialization order is determined by RFC 8785, not by the order shown in pseudocode.

Whole list:

```text
id([a1, ..., an]) = fold(fold(...fold(id([]), a1)...), an)
```

Properties:

- order is significant;
- multiplicity is preserved;
- lists are not flattened;
- `[A]` is distinct from `A`;
- `[]` is distinct from absent values and cleaned object fields;
- `[A, {$empty: true}, B]` is distinct from `[A, B]`;
- append hashing can be O(delta) when seeded by a valid `$previous` anchor.

### 14.8 List control normalization before hashing (normative)

For direct anchored BlueId Input:

- `$previous` MAY appear only as the first item.
- If present and well-formed, `$previous.blueId` MAY seed the list fold.
- Anchor validity is a precondition of direct anchored BlueId Input.
- A Canonical Identity Input produced by the Content BlueId pipeline MUST NOT contain `$previous`.
- Implementations MAY use a verified prefix BlueId as an internal hashing optimization.

`$pos` and `$replace` MUST NOT appear in BlueId Input. `$empty: true` remains content and hashes as a normal object element.

Malformed list controls MUST be rejected.

### 14.8.1 Canonical JSON examples (informative but behavior-defining through referenced rules)

#### Large Integer scalar node

An Integer outside the safe JSON numeric integer range is represented as quoted canonical decimal text with explicit Integer type.

Canonical BlueId Input shape:

```yaml
type:
  blueId: <IntegerTypeBlueId>
value: "9007199254740992"
```

Map hashing builds helper map `M` conceptually:

```json
{
  "type": { "blueId": "<IntegerTypeBlueId>" },
  "value": "9007199254740992"
}
```

The RFC 8785 canonical JSON byte sequence is the UTF-8 encoding of:

```json
{"type":{"blueId":"<IntegerTypeBlueId>"},"value":"9007199254740992"}
```

#### Double negative zero

`Double` values use finite IEEE 754 binary64 semantics. Negative zero and positive zero compare as the same numeric value. Under RFC 8785 canonical JSON, the numeric value canonicalizes as JSON number `0`.

A Source token such as `-0.0` infers `Double` if no explicit type is provided, but the canonical scalar numeric payload is `0` and the effective `type: Double` preserves the fact that the node is a Double rather than an Integer.

#### Integer-looking Double

A Source token such as `1.0` or `1e0` infers `Double`. The canonical JSON representation of the numeric payload may render as `1`, but the effective `type: Double` remains part of canonical BlueId input. Therefore `1` as Integer and `1.0` as Double are distinct Blue values unless an explicit type or schema says otherwise.

#### List fold helper map ordering

The list fold step uses the exact object keys `$listCons`, `prev`, and `elem`:

```json
{"$listCons":{"elem":{"blueId":"<ElemId>"},"prev":{"blueId":"<PrevId>"}}}
```

The example shows the RFC 8785 canonical JSON serialization for these keys. Implementations MUST NOT rely on insertion order or host map order.

### 14.9 Storage rule (normative)

A node MUST NOT store its own BlueId as authoritative content.

Using `{ blueId: ... }` to reference other nodes is permitted and encouraged. A provider or envelope MAY store a node's BlueId out-of-band, but the self-BlueId MUST NOT be treated as part of the node's own content.

### 14.10 Inputs containing `blue` (normative)

BlueId Input MUST NOT contain `blue`. A direct hasher MUST reject such input.

---

### 14.11 Identity locality and direct-container cost (normative)

BlueId is transitive through direct child identities rather than transitive child bytes. Therefore establishing or verifying an object's identity requires its complete direct helper map and the Node BlueIds of its direct children, but not the bodies of those children.

Consequences:

- a large descendant behind one direct child BlueId does not need to be expanded to verify or rebuild its parent;
- changing one member of a direct object requires rebuilding that object's complete direct helper map;
- appending to a list may continue from a verified prior fold identity;
- replacing, inserting, or removing an early list element requires recomputing the affected suffix fold;
- one extremely wide flat object or positional list remains expensive under Language 1.0 even when represented by a pure reference.

These costs are properties of the current identity algorithm, not of inline versus referenced representation. The inline and referenced forms of the same exact node require the same direct identity information for the same structural update.

Language 1.0 does not define Merkle maps or random-access Merkle vectors. Applications needing logarithmic point updates or proofs SHOULD use bounded-fanout application structures. A future major Language version may standardize such collection identities.

## 15. Circular Reference Sets

### 15.1 Purpose

Some authoring graphs contain direct cycles across documents, for example `Person` references `Dog` and `Dog` references `Person`. Blue supports a combined BlueId for a cyclic set, with stable per-document suffixes.

### 15.2 ZERO_BLUEID sentinel (normative)

During cyclic-set calculation, each direct cyclic reference is temporarily replaced with the **ZERO_BLUEID** sentinel: forty-four ASCII `0` characters.

ZERO_BLUEID is a sentinel only. It MUST NOT appear in finalized BlueId Input.

During cyclic-set calculation, ZERO_BLUEID and `this#<index>` are permitted only in positions where a BlueId string is expected inside the temporary cyclic-set calculation input.

They are not valid ordinary BlueId Input and MUST NOT appear in finalized provider-stored content.

### 15.3 Cyclic-set input (normative)

The input to the cyclic-set algorithm is a finite set of document roots plus explicit internal reference markers indicating which references point to documents within the set.

The algorithm applies to a strongly connected cyclic set. Independent strongly connected components SHOULD be processed separately.

A cyclic-set calculation input MUST contain at least one internal cyclic reference. A set with no internal cyclic references SHOULD be treated as ordinary independent documents rather than as a cyclic set.

If two cyclic-set members have identical preliminary BlueIds, implementations MUST compare the RFC 8785 canonical JSON byte sequence of their preliminary BlueId input as a deterministic tie-breaker.

If the tie remains equal, the cyclic-set input is invalid in Blue Language 1.0 unless the members contain an explicit identity-bearing disambiguator before preliminary hashing. Implementations MUST fail cyclic-set calculation with `CircularSetError` rather than assigning arbitrary positions.

Blue Language 1.0 does not define graph-isomorphism rules for duplicate preliminary cyclic members.

### 15.4 Cyclic-set algorithm (normative)

Given a finite set of documents participating in a direct cycle:

1. Temporarily replace each internal cyclic `blueId` reference with ZERO_BLUEID.
2. Calculate preliminary BlueIds for each document in isolation.
3. Sort documents lexicographically by preliminary BlueId, with the tie-breaking rule from §15.3.
4. Assign positions `#0` through `#(n-1)` according to that order.
5. Rewrite each internal cyclic reference as:

```yaml
blueId: this#<index>
```

where `<index>` is the assigned position of the target document.

6. Build a list:

```text
L = [doc#0, doc#1, ..., doc#(n-1)]
```

with `this#<index>` references in place.

7. Compute:

```text
MASTER = id(L)
```

8. The final BlueId of document `i` is:

```text
MASTER#i
```

The **preliminary BlueId input** for each document is the document after replacing each direct internal cyclic `blueId` reference with ZERO_BLUEID and before rewriting those references to `this#<index>`.

`this#<index>` is accepted only by the cyclic-set calculation API. It MUST NOT appear in stored provider content, ordinary BlueId Input, Source Documents outside explicit cyclic-set serialization, or Canonical Identity Input.

During preliminary BlueId calculation with ZERO_BLUEID placeholders, a pure reference `{ blueId: ZERO_BLUEID }` is treated as a temporary pure reference whose identity contribution is the sentinel value for the purpose of preliminary ordering only. ZERO_BLUEID MUST NOT be returned as a finalized BlueId.

During MASTER calculation, pure references `{ blueId: "this#<index>" }` are treated as internal cyclic placeholders as defined by the cyclic-set algorithm, not as ordinary provider references.

Cyclic-set identity flow:

```text
authoring refs
   |
   v
replace internal refs with ZERO_BLUEID
   |
   v
preliminary ids -> sort -> assign #0..#(n-1)
   |
   v
rewrite internal refs to this#k
   |
   v
MASTER = id([doc#0, doc#1, ...])
   |
   v
final ids = MASTER#0, MASTER#1, ...
```

### 15.5 BlueId grammar for cyclic sets (normative)

A cyclic-set member BlueId has the form:

```text
<MASTER>#<index>
```

where `MASTER` is a plain BlueId and `index` is a non-negative decimal integer with no leading zeros, except for the single digit `0`.

`this#<index>` is an algorithm-internal placeholder. It is accepted only by an implementation API explicitly performing cyclic-set calculation over a declared finite cyclic set. It MUST be rejected by ordinary parsing, preprocessing, resolution, provider storage, expansion, canonicalization, and direct BlueId calculation outside that cyclic-set calculation API.

### 15.6 Example (informative)

```yaml
# Dog (#0 after sorting)
name: Dog
owner:
  type:
    blueId: this#1
breed:
  type: Text

# Person (#1 after sorting)
name: Person
pet:
  type:
    blueId: this#0
```

If `MASTER = 12345...`, then:

```text
Dog    = 12345...#0
Person = 12345...#1
```

---

## 16. Conformance Vectors

The Blue Language 1.0 conformance suite, canonical core registry, and this prose specification jointly define Blue Language 1.0. The prose rules are normative, the registry supplies exact identity-bearing core type nodes and BlueIds, and the fixtures provide behavior-defining executable examples.

A fixture package identity MUST be published with the Blue Language 1.0 release. A conforming implementation MUST report which fixture package identity it passes.

If the prose specification, registry, and fixture package conflict, the release artifact is invalid and MUST be corrected. Implementations MUST NOT guess which artifact wins.

Conformance vectors are behavior-defining. A conforming Blue Language 1.0 implementation MUST pass all vectors in this section and all machine-readable fixtures in the Blue Language 1.0 conformance suite.

The labels `B`, `R`, and `F` identify fixture categories: BlueId algorithm, resolution/canonicalization, and provider/full-graph behavior. They do not define separate conformance levels.

### 16.1 BlueId algorithm vectors

- **B1.** `id([])` is defined and distinct from absent values and cleaned object fields.
- **B2.** `[A]` hashes differently from `A`.
- **B3.** `[[A, B], C]` hashes differently from `[A, B, C]`.
- **B4.** `x: 1` and `x: { value: 1 }` produce the same Node BlueId after canonical input normalization.
- **B5.** `x: [a, b]` and `x: { items: [a, b] }` produce the same Node BlueId.
- **B6.** A map exactly `{ blueId: X }` hashes to `X`.
- **B7.** Object-field cleaning removes `null` fields and fields that normalize to empty objects.
- **B8.** Cleaning preserves `[]`.
- **B9.** A node containing `blue` is rejected as direct BlueId Input.
- **B10.** A map mixing `blueId` with sibling fields is rejected as BlueId Input.
- **B11.** Primitive scalar inference assigns `Text`, `Integer`, `Double`, and `Boolean` deterministically.
- **B12.** `$empty: true` remains content and affects BlueId.
- **B13.** Direct BlueId Input containing a `null` list element is rejected.
- **B14.** Direct BlueId Input containing an empty-object list element is rejected unless it has already been normalized to `$empty: true` before direct hashing.
- **B15.** `[A, {$empty: true}, B]` hashes differently from `[A, B]`.
- **B16.** Integer values above `9007199254740991` or below `-9007199254740991` are represented as quoted canonical decimal text with explicit `Integer` type.
- **B17.** `this#<index>` is rejected outside the explicit cyclic-set calculation API.
- **B18.** A source numeric token `1` infers `Integer`; source numeric tokens `1.0` and `1e0` infer `Double`; explicit `type: Double` remains Double even when the canonical JSON number renders as `1`.
- **B19.** Root `{}` is valid BlueId Input and hashes as an empty object; it is not omitted.
- **B20.** Root `null` is invalid as Source Document root and as BlueId Input.
- **B21.** Plain BlueIds validate as canonical Base58 encodings of exactly 32 bytes; invalid alphabet characters, non-canonical encodings, wrong decoded length, and plain ID strings containing `#` are rejected.
- **B22.** `$empty` list placeholder shape is exactly `{ "$empty": true }`; malformed `$empty` items are rejected.
- **B23.** `Double` negative zero canonicalizes to numeric payload `0` while retaining Double type.
- **B24.** `Double` overflow is rejected.
- **B25.** Integer-looking Double canonical rendering retains Double type.
- **B26.** Payload-only scalar hashing uses typed scalar identity form, not raw JSON scalar hashing.
- **B27.** Enum order and duplicate entries do not affect effective canonical schema identity.
- **B28.** `Double` `multipleOf` is evaluated by exact rational arithmetic over IEEE 754 binary64 values.
- **B29.** A cyclic-set input with duplicate preliminary member inputs fails unless the members contain identity-bearing disambiguators before preliminary hashing.
- **B30.** A fully materialized node and its direct-node materialization pattern have the same Node BlueId.
- **B31.** Replacing a direct child by a pure reference to that child preserves the parent Node BlueId.

### 16.2 Resolution and canonicalization vectors

- **R1.** Preprocessing removes `blue` and applies baseline transforms before resolution.
- **R2.** Source list `[A, null, B]` preprocesses to `[A, {$empty: true}, B]`, not `[A, B]`.
- **R3.** Source list `[A, {}, B]` preprocesses to `[A, {$empty: true}, B]`, not `[A, B]`.
- **R4.** Type chains merge according to the overlay and subtyping rules.
- **R5.** Fixed-value invariants cannot be overridden.
- **R6.** Schema constraints accumulate; irreconcilable constraints fail resolution.
- **R7.** Schema objects containing keys outside §9.2 are rejected.
- **R8.** `name` and `description` are ignored by matchers and subtype checks.
- **R9.** Type root `name` and `description` are not inherited onto the instance root.
- **R10.** A Source Document and its Resolved Form, after canonicalization, produce the same Content BlueId.
- **R11.** Requirement overlays bind valid type completions and reject conflicting completions.
- **R12.** `$previous` is validated against the resolved inherited prefix; mismatch fails resolution.
- **R13.** `mergePolicy` defaults to `positional` only when there is no inherited effective `mergePolicy`.
- **R14.** Append-only lists reject `$pos`.
- **R15.** Positional lists reject inherited-prefix reordering and removal.
- **R16.** A Minimized Overlay re-resolves to the same Resolved Form.
- **R17.** Canonical Identity Input does not contain `$previous`, `$pos`, `blue`, unresolved aliases, `null` list elements, or empty-object list elements.
- **R18.** Direct hashing of a Resolved Form is not used as Content BlueId unless the Resolved Form is already identical to its Canonical Identity Input.
- **R19.** Canonical Identity Input for append-only lists does not serialize `$previous`; `$previous` may appear only in Minimized Overlay or direct anchored BlueId Input.
- **R20.** Canonical Identity Input contains no type aliases; all type references are canonical BlueId references.
- **R21.** A source pure reference that is materialized only for resolution canonicalizes back to the pure reference unless the source overlays additional instance content onto it.
- **R22.** A child overlay of an inherited `append-only` list that omits `mergePolicy` remains `append-only`; `$pos` is still rejected.
- **R23.** A descendant collection that omits inherited `itemType`, `keyType`, or `valueType` retains the inherited constraint.
- **R24.** Canonical positional list refinements produce final canonical list payloads, not Source overlay instructions.
- **R25.** Minimized positional list overlays may use `$pos` and re-resolve to the same Resolved Form.
- **R26.** Canonical append-only list overlays do not contain `$previous`; minimized append-only overlays may use `$previous`.
- **R27.** Inherited effective Integer type accepts quoted canonical large decimal text.
- **R28.** Quoted decimal text without effective Integer type remains Text.
- **R29.** Inherited effective Integer type rejects non-canonical decimal text.
- **R30.** Declaration-only label overrides are allowed, but label overrides on inherited fixed-value nodes are rejected.
- **R31.** Type-chain cycles and self-type cycles are rejected.
- **R32.** Required metadata-only fields fail, while required instance payloads and inherited fixed payloads pass.
- **R33.** `minFields` and `maxFields` count ordinary fields only.
- **R34.** Wrong-kind schema keywords fail schema validation.
- **R35.** `itemType`, `keyType`, and `valueType` validate resolved collection members.
- **R36.** Direct Dictionary integer keys use canonical textual form and reject duplicate key conflicts after canonicalization.
- **R37.** Source list `[A, { x: null }, B]` preprocesses to `[A, { $empty: true }, B]`.
- **R38.** Canonical core type compatibility is nominal by registry BlueId.
- **R39.** Blue Language operation path root is the empty string under RFC 6901; `/` selects the empty-key member.
- **R40.** Limited resolution of a demanded path yields the same value, effective type, and applicable constraints as complete resolution.
- **R41.** A limited resolver never reports an unexpanded or unresolved field as absent merely because a limit prevented access.
- **R42.** An incomplete limited result is rejected as input to whole-node canonicalization, Content BlueId calculation, and minimization.
- **R43.** A limit, unexpanded reference, or unavailable provider resource never produces a successful `Absent` result.
- **R44.** Semantic lookup through a pure reference is transparent: a collapsed wrapper does not create a semantic child named `blueId`.
- **R45.** A demand-limited exact-node-identity request returns the same Node BlueId for inline, collapsed, and partially expanded forms.
- **R46.** A pure reference used as `schema` or `contracts` is semantically equivalent to its verified materialization; operations expand it only when its contents are demanded.
- **R47.** A source pure reference used for `schema` or `contracts`, when materialized only for resolution or validation, is preserved as the source pure reference by canonicalization unless a non-derivable instance overlay must be represented.

### 16.3 Provider, expansion, and collapse vectors

- **F1.** All B-vectors and R-vectors pass.
- **F2.** Expansion preserves Node BlueId.
- **F3.** If the implementation exposes collapse, collapse preserves Node BlueId and produces only valid pure references.
- **F4.** Expansion supports configurable depth or path limits that do not affect identity.
- **F4a.** A document root supplied as `{ blueId: X }` can be expanded only at demanded paths without recursively materializing all descendants.
- **F4b.** Inline and verified referenced forms produce identical demanded expansion and resolution results.
- **F5.** Cross-document references resolve through a provider without changing identity.
- **F6.** Missing provider content required for resolution fails deterministically.
- **F7.** Ordinary BlueId provider content whose computed Node BlueId does not equal the requested BlueId is rejected.
- **F8.** Source Document provider content requires a declared Source Document provider mode and Content BlueId verification.
- **F9.** Cyclic-set member provider content requires cyclic-set-aware verification context.
- **F16.** An exact direct-fragment graph reconstructs the original Root and preserves every Root Node BlueId.
- **F17.** Fragment identity order and provider results are deterministic and defensive.
- **F18.** A finalized `MASTER#index` edge is preserved opaquely; the ordinary fragment provider does not claim member content.
- **F19.** A cyclic-aware provider can open an opaque member only with complete owning-set proof.
- **F10.** One materialized object node can be verified from its complete direct keys, inline identity scalars, and child BlueIds without fetching child bodies.
- **F11.** One materialized list node can be verified from its ordered element BlueIds without fetching element bodies.
- **F11a.** Provider-internal append anchors or prefix folds do not replace the complete ordered direct element identities needed to reconstruct a requested direct list node.
- **F12.** Expanding one node while leaving complete direct children collapsed, and then collapsing the selected node again, preserves the exact root Node BlueId and does not demand descendant bodies that were never selected.
- **F13.** Demanding `/a/b/c` from a direct-node provider requires only the root and the direct nodes on that path, unless type or schema semantics demand additional nodes.
- **F14.** Provider batching, prefetching, and cache state do not change semantic results.
- **F15.** A provider that omits a demanded direct key cannot report absence unless the complete direct manifest has been verified.

### 16.4 Machine-readable fixtures (normative)

The Blue Language 1.0 conformance suite MUST publish machine-readable fixtures with exact expected BlueIds.

The canonical fixture package is part of the Blue Language 1.0 conformance release and is versioned with this specification. The fixture package included with this freeze candidate contains 125 machine-readable fixtures and a complete vector-to-fixture coverage map.

Its fixture-package identity is:

```text
sha256:267145c335c26e5a27121c31986ff53cc630a2ce1755aad97c376ef234560dd5
```

The canonical core-registry package identity bound by this fixture package is:

```text
sha256:b705171a6ca62c990792bcb78db9d921caf5b0ed06370648b9a81769d69dd71e
```

The release manifest MUST bind this exact fixture package and the canonical registry manifest. Any fixture or registry change requires a newly calculated package identity.

Each fixture SHOULD use this shape:

```yaml
id: B4
category: BlueId
description: scalar sugar and wrapped scalar are equivalent
input:
  x: 1
expectedNodeBlueId: "<blueId>"
alsoEquivalentTo:
  x:
    value: 1
```

Fixtures involving Content BlueId SHOULD include:

```yaml
id: R10
category: Resolution
source: ...
provider: ...
expectedCanonicalIdentityInput: ...
expectedContentBlueId: "<blueId>"
```

Error fixtures MAY include:

```yaml
expectedErrorCategory: SchemaViolation
```

or, for multiple valid categories:

```yaml
expectedErrorCategories: [InvalidBlueId, InvalidReferenceShape]
```

The expected BlueIds are part of the specification test surface. Changing one requires either correcting an error in the specification or declaring a new incompatible language version.

The fixture suite MUST cover:

- scalar values;
- large integers represented as quoted canonical decimal strings;
- wrapped vs sugar forms;
- pure references;
- root scalar, list, object, and pure reference forms;
- empty list;
- empty object root;
- root null rejection;
- plain BlueId validation;
- portable `blue.imports` alias resolution;
- portable YAML rejection of anchors, aliases, merge keys, custom tags, YAML-only types, and implicit timestamp typing;
- YAML multiline block scalar identity;
- schema keyword value-shape validation;
- schema wrong-kind validation;
- enum order and duplicate normalization;
- exact `Double` `multipleOf` validation using rational binary64 semantics;
- required field semantic-presence validation;
- field counting for ordinary object fields only;
- deterministic integer `multipleOf` LCM merge;
- enum scalar type inference;
- typed scalar identity for payload-only scalar hashing;
- object-field null removal;
- list null placeholder normalization;
- list empty-object placeholder normalization;
- recursive list element placeholder normalization after object-field cleaning;
- `$empty`;
- malformed `$empty` rejection;
- `$pos` map overlay and `$replace` compatibility;
- append-only `$previous`;
- Canonical Identity Input final list payloads are identity input, not ordinary Source overlays;
- Minimized Overlay re-resolution for `$pos` and `$previous` list controls;
- inherited `mergePolicy`;
- inherited collection type constraints;
- `itemType`, `keyType`, and `valueType` validation;
- direct Dictionary key canonicalization and duplicate conflict rejection;
- reserved-invalid `properties` rejection;
- materialized subtree vs pure reference;
- direct-node object and list verification;
- transparent semantic access through pure references;
- reference-backed `schema` and `contracts` values;
- explicit `Established`, `Absent`, `Incomplete`, and `Invalid` demand outcomes;
- semantic result invariance across warm/cold, inline/reference, and batched/unbatched variants;
- demanded-path navigation through a direct-node provider;
- provider Node BlueId verification, declared Source provider verification, and cyclic-set member verification;
- RFC 6901 Blue Language operation paths, including empty-string root and `/` empty-key member behavior;
- type alias preprocessing;
- type-chain cycle detection;
- nominal core type compatibility by registry BlueId;
- primitive inference;
- core registry Text node hashes to its published BlueId;
- core registry Integer node hashes to its published BlueId;
- core registry Double node hashes to its published BlueId;
- core registry Boolean node hashes to its published BlueId;
- core registry Dictionary node hashes to its published BlueId;
- core registry List node hashes to its published BlueId;
- changing a core type `description` changes the node BlueId;
- circular references;
- duplicate preliminary cyclic-set member rejection unless identity-bearing disambiguators are present before preliminary hashing;
- error category classification;
- publication lint that rejects obsolete conformance terminology in publishable Blue Language 1.0 files and requires the §1 heading used by this specification.

The Blue Language core registry manifest MUST make identity-bearing descriptions explicit. Each entry in the registry manifest MUST identify the registry kind, specification version, entry key, canonical node path, published BlueId, and `semanticDescriptionIdentityBearing: true`.

Release checks MUST verify that:

- registry nodes are loaded from files, not reconstructed from implementation constants;
- registry file content hashes to the published BlueIds;
- core type alias constants equal the calculated registry BlueIds;
- no canonical registry node is edited without updating its BlueId and fixture package identity;
- generated documentation is derived from registry nodes, or explicitly marked non-canonical;
- publishable Blue Language files pass the documentation lint before release;
- the six preserved core registry files hash to the published mature core BlueIds;
- the core-registry manifest publishes file paths, file hashes, identity-bearing-description flags, fixture binding, and its own package identity;
- the content-addressed release manifest binds the exact prose, registry, and fixture artifacts.

---

## 17. Worked Examples

BlueIds ending in `...` in this section are illustrative placeholders, not conformance vectors. Exact expected BlueIds are defined by the machine-readable fixture suite (§16.4).

### 17.1 Content-addressable types (informative)

```yaml
name: Simple Amount
amount:
  type: Double
currency:
  type: Text
# => blueId: FgHZjS...

name: Person
age:
  type: Integer
spent:
  type:
    blueId: FgHZjS...   # Simple Amount
# => blueId: GRwTYs...
```

Instance:

```yaml
name: Alice
type:
  blueId: GRwTYs...     # Person
age: 25
spent:
  amount: 27.15
  currency: USD
# => Content BlueId: 3JTd8s...
```

Expanding the demanded type links makes the required nodes available. Resolving them produces the same semantic values as complete resolution. Complete resolution followed by canonicalization produces a Canonical Identity Input whose Node BlueId is the Content BlueId of the instance.

### 17.2 `blue` directive (informative)

```yaml
blue:
  imports:
    Person:
      blueId: GRwTYs...
name: Alice
type: Person
age: 25
```

Preprocessing replaces `Person` with its BlueId reference, infers primitive scalar types, and removes `blue` before hashing.

### 17.3 Large integer (informative)

```yaml
accountId:
  type: Integer
  value: "9007199254740992"
```

The value is quoted because it is outside the safe JSON numeric integer range. The explicit `Integer` type distinguishes it from Text.

Numeric token inference:

```yaml
a: 1      # inferred Integer
b: 1.0    # inferred Double
c: 1e0    # inferred Double
d:
  type: Double
  value: 1
```

`b`, `c`, and `d` are Double values even when their canonical JSON number renders as `1`.

### 17.4 Same image, different meaning (informative)

```yaml
# A
name: Person to Avoid
description: This guy will kill you today
type: Image
image:
  blueId: 123...456

# B
name: Family Member
description: Trust this person
type: Image
image:
  blueId: 123...456
```

These have different Content BlueIds because `name` and `description` are identity content. Structural and type matchers ignore those labels.

### 17.5 Requirement overlay followed by type binding (informative)

```yaml
# Parent
name: A
prop1:
  x: 1

# Child
name: B
type: A
prop1:
  type: Some
```

The child is valid only if `Some` can resolve while preserving `x = 1`. If `Some` forces `x = 2`, resolution fails.

### 17.6 Lists: refine and append (informative)

```yaml
# Parent
name: Trip
segments:
  type: List
  itemType: Flight Segment
  items:
    - type: Flight Segment
      carrier: BA

# Child
name: Trip LHR to SFO
type: Trip
segments:
  items:
    - $pos: 0
      from: LHR
      to: JFK
    - type: Flight Segment
      carrier: BA
      from: JFK
      to: SFO
```

The child refines inherited index `0` and appends a second segment. Reordering or deleting the inherited prefix would be invalid.

### 17.7 Null list element as placeholder (informative)

```yaml
items:
  - A
  - null
  - B
```

preprocesses to:

```yaml
items:
  - A
  - $empty: true
  - B
```

It does not preprocess to `[A, B]`.

### 17.8 Expansion with limits (informative)

Starting from:

```yaml
blueId: 3JTd8s...   # Alice
```

expanding `/spent` may hydrate only the `spent` subtree:

```yaml
name: Alice
type:
  blueId: GRwTYs...
age: 25
spent:
  amount: 27.15
  currency: USD
```

Node BlueId is unchanged if the hydrated content verifies to the referenced BlueIds.

### 17.9 Canonicalization (informative)

From a complete Resolved Form with the type content required for canonicalization, canonicalization:

- collapses type objects to `{ blueId: ... }` when available;
- removes structure derivable from the type chain;
- consumes `$pos` overlays;
- normalizes list placeholders to `$empty: true`;
- keeps instance contributions;
- produces valid BlueId Input.

The Canonical Identity Input yields the Content BlueId. A Minimized Overlay, when produced, re-resolves to the same Resolved Form through ordinary Source overlay semantics.

### 17.10 Contracts merge as content (informative)

```yaml
# Parent type
name: With Audit
contracts:
  audit:
    type: Audit Contract
    enabled: true

# Child instance
type: With Audit
contracts:
  audit:
    retentionDays: 30
```

Language resolution merges `contracts.audit` as content. It does not execute the contract. The resolved contract entry contains both `enabled: true` and `retentionDays: 30`, unless normal fixed-value, type, or schema rules reject the merge.

### 17.11 Common invalid forms (informative)

Mixed reference and content is invalid:

```yaml
blueId: X
name: Not allowed
```

`blue` is root-only and preprocessing-only:

```yaml
child:
  blue: something
```

`$pos` cannot appear in Canonical Identity Input or BlueId Input:

```yaml
items:
  - $pos: 0
    value: A
```

Use `$replace` for non-scalar positional replacement:

```yaml
# Invalid
- $pos: 0
  value:
    items: [A, B]

# Valid
- $pos: 0
  $replace:
    items: [A, B]
```

---

## Appendix A — Core Primitive and Collection Types

Appendix A defines the canonical primitive and collection types referenced throughout this specification.

The nodes in §A.1 are canonical type definitions, not illustrative sketches. Their `description` fields are normative, identity-bearing Blue content. The exact registry files used to calculate published BlueIds MUST be byte/string equivalent after Blue parsing to the intended canonical nodes.

The core registry nodes in this appendix are the canonical Blue Language 1.0 primitive and collection definitions. Their `1.0` wording is identity-bearing content and agrees with this first public-version specification. The exact registry files—not retyped copies in implementation code—are authoritative for their published BlueIds.

The execution environment selects Blue Language 1.0; the exact core-type BlueIds select the primitive meanings. After publication, an existing core-type BlueId may receive only errata outside the node. Changing identity-bearing semantics requires a new type identity.

Changing a canonical node's `description` is a type-identity change. Implementations MUST NOT silently update canonical descriptions while keeping the old BlueId.

If a typo or editorial issue is found after publication and it does not change semantics, publish errata outside the canonical node. If the text change is intended to alter or clarify the type's meaning in an identity-bearing way, publish a new registry entry with a new BlueId.

### A.1 Canonical core type nodes

#### Text

```yaml
name: Text
description: >
  Core Blue Language 1.0 primitive scalar representing Unicode text. Text
  values are exact Unicode code-point sequences after parsing. Blue Language
  performs no Unicode normalization, case folding, locale-sensitive collation,
  whitespace normalization, or line-ending normalization by default. String
  schema constraints minLength and maxLength count Unicode code points. The
  empty string is valid unless restricted by schema. Applicable schema
  constraints are minLength, maxLength, and enum.
```

#### Integer

```yaml
name: Integer
description: >
  Core Blue Language 1.0 primitive scalar for exact mathematical integer
  values. Integer values are arbitrary precision in the language model.
  Unquoted integer tokens are portable only in the safe JSON numeric integer
  range [-9007199254740991, 9007199254740991]. Integer values outside that
  range are represented as quoted canonical decimal text with explicit or
  inherited effective Integer type. The canonical decimal text form uses an
  optional leading minus sign followed by decimal digits, with no leading
  zeros except the single digit zero. Applicable schema constraints are
  minimum, maximum, exclusiveMinimum, exclusiveMaximum, multipleOf, and enum.
```

#### Double

```yaml
name: Double
description: >
  Core Blue Language 1.0 primitive scalar for finite IEEE 754 binary64
  floating-point values. NaN, positive Infinity, and negative Infinity are
  invalid Blue values. Double parsing uses round-to-nearest, ties-to-even
  binary64 semantics; numeric tokens that overflow to Infinity or parse as NaN
  are invalid. Source numeric tokens with a decimal point or exponent infer
  Double when no explicit type is provided, even when their mathematical value
  is integral. Negative zero and positive zero compare as the same numeric
  value and canonicalize as JSON number zero, while the effective Double type
  remains part of canonical BlueId input. Applicable schema constraints are
  minimum, maximum, exclusiveMinimum, exclusiveMaximum, multipleOf, and enum.
```

#### Boolean

```yaml
name: Boolean
description: >
  Core Blue Language 1.0 primitive scalar with exactly two values: true and
  false. Blue Language defines no truthiness conversion for Boolean values.
  Only the literal parsed boolean values true and false are Boolean values.
  Applicable schema constraint is enum.
```

#### Dictionary

```yaml
name: Dictionary
description: >
  Core Blue Language 1.0 object-map collection type. A Dictionary is encoded
  as a Blue object node whose ordinary child fields represent direct keys
  when those keys do not collide with reserved language fields. Direct object
  encoding cannot represent data keys named name, description, type, itemType,
  keyType, valueType, value, items, blueId, blue, schema, mergePolicy,
  contracts, properties, or constraints. Direct object encoding cannot
  represent reserved language keys as data keys. Applications needing
  arbitrary keys use an escaped entry representation such as a list of { key,
  val } entries. keyType is optional; if
  omitted and no effective keyType is inherited, keys default to Text for
  direct object encoding. For direct object encoding, keyType must resolve to
  a scalar key type with a canonical textual form, such as Text, Integer,
  Double, or Boolean. valueType is optional; if omitted and no effective
  valueType is inherited, values may be any Blue node. Applicable schema
  constraints are minFields and maxFields.
```

#### List

```yaml
name: List
description: >
  Core Blue Language 1.0 ordered collection type. Surface array form and
  wrapped items form are equivalent authoring forms. Order and multiplicity
  are preserved. List BlueId calculation uses a domain-separated streaming
  fold over element BlueIds. itemType is optional; if omitted and no effective
  itemType is inherited, elements are not constrained by itemType. If
  mergePolicy is omitted and no effective mergePolicy is inherited, resolvers
  assume positional. append-only forbids changes to the inherited prefix.
  positional allows $pos overlays within the inherited prefix. $previous,
  $pos, $replace, and $empty are recognized only at the top level of items
  when the node's effective type is List. Source list null and empty object
  elements normalize to $empty: true and are not deleted. Applicable schema
  constraints are minItems, maxItems, and uniqueItems.
```

### A.2 Editorial and registry rules

The canonical registry nodes above are the Blue Language 1.0 core type nodes, retaining their established exact content and BlueIds. Their registry manifest is published under the Language 1.0 release and MUST be fixture-verified together with this specification. Non-normative examples, tutorials, rationale, translations, and implementation notes are not part of the canonical type nodes unless intentionally included in the registry entries.

Additional explanatory documentation MAY follow this appendix or appear in separate registry documentation, but it MUST be clearly marked non-canonical unless it is included in the registry node itself.

---

## Appendix B — Reserved Extension Boundary

`contracts` is reserved for the Blue Contracts and Processor Specification 1.0. Blue Language 1.0 treats it as identity-bearing content only. See §4.4.

---

## Appendix C — Common Implementer Mistakes

This appendix is informative.

### C.1 Do not delete list positions

`[A, null, B]` does not mean `[A, B]`. Source list `null` and `{}` elements normalize to `$empty: true`.

### C.2 Do not hash `blue`

`blue` is a preprocessing directive. Direct BlueId input containing `blue` must be rejected.

### C.3 Do not treat `value` as a generic replacement field

`value` is the scalar payload wrapper. Positional non-scalar replacement uses `$replace`.

### C.4 Do not let `$pos` reach BlueId input

`$pos` is an overlay instruction. Canonical Identity Input and direct BlueId Input must not contain `$pos`.

### C.5 Do not trust provider content without verification

When expanding `blueId: X` through an ordinary BlueId provider, compute the returned content's Node BlueId and verify that it equals `X`.

### C.6 Do not treat `name` and `description` as comments

They affect BlueId. They are ignored by matchers, not by identity.

### C.7 Use only the schema keywords defined in §9

A `schema` object accepts only the keywords listed in §9.2.

### C.8 Do not use reserved language keys as ordinary object fields

Reserved keys such as `type`, `value`, `items`, and `schema` have language meaning.

---

### C.9 Do not expose the pure-reference wrapper as semantic content

A semantic graph lookup must treat `{ blueId: X }` as node `X`, not as an application object containing a data field named `blueId`.

### C.10 Do not let physical representation change semantic results

Cache hits, provider pages, network bytes, batching, and host allocations are not Blue content. They must not change a Language operation's established, absent, incomplete, or invalid outcome.

### C.11 Do not require transitive expansion to verify a direct node

The existing map and list BlueId algorithms verify one direct node from direct child identities. Fetching all descendants is unnecessary.

## Appendix D — Error Categories

This appendix is normative for conformance diagnostics but does not require a particular exception class, wire format, or exact error message.

When an operation fails deterministically, implementations MUST be able to classify the failure into one of these categories for conformance reporting:

| Category | Meaning |
|---|---|
| `InvalidSyntax` | Serialized JSON/YAML is malformed or outside the Blue JSON data model. |
| `DuplicateKey` | A serialized object contains duplicate keys. |
| `InvalidReservedField` | A reserved field has an invalid type, shape, or position. |
| `InvalidBlueId` | A BlueId string is malformed or invalid for its context. |
| `InvalidReferenceShape` | `blueId` appears with sibling fields or invalid mixed reference shape. |
| `InvalidBlueIdInput` | Direct Node BlueId received a node that is not valid BlueId Input. |
| `ProviderUnavailable` | Required provider content is unavailable. |
| `ProviderBlueIdMismatch` | Provider content does not verify against the requested BlueId. |
| `OperationIncomplete` | A demanded semantic result could not be established because required content or coverage was not available. |
| `OperationLimitExceeded` | An out-of-band operation limit prevented completion of a demanded result. |
| `TypeCycle` | Resolution detected a type-cycle in the active type stack. |
| `FixedValueConflict` | A descendant attempted to override or contradict an inherited fixed value. |
| `TypeCompatibilityViolation` | A descendant type, itemType, keyType, or valueType is incompatible with an inherited constraint. |
| `SchemaVocabularyError` | A schema contains an unknown keyword or invalid schema value shape. |
| `SchemaViolation` | A node violates accumulated schema constraints. |
| `ListControlViolation` | `$previous`, `$pos`, `$replace`, or `$empty` has invalid shape or context. |
| `CanonicalizationError` | A Canonical Identity Input cannot be produced deterministically. |
| `CircularSetError` | Cyclic-set input is malformed or cannot produce deterministic member IDs. |
| `UnsupportedPreprocessingTransform` | A Source Document requires a preprocessing transform that is unsupported. |

An invalid document may contain multiple independent errors. Blue Language 1.0 does not require a universal precedence order for all possible simultaneous failures. Conformance fixtures that assert an exact error category MUST isolate one primary error so that a conforming implementation can deterministically report that category without ambiguity. If a fixture intentionally contains multiple independent errors, it MUST assert only that the operation fails, or it MUST explicitly declare acceptable error categories.

---

## Appendix E — Informative Direct-Node Storage Guidance

This appendix is informative. It does not add a separate Language conformance mode.

### E.1 Admission

A provider optimized for lazy graph access may normalize and verify a node, establish every direct child Node BlueId, and store one direct-node representation whose complete children are collapsed, keyed by the node's own Node BlueId.

### E.2 Retrieval

Retrieval of one Node BlueId should return enough direct content to verify that exact node without requiring descendant bodies. A provider may batch additional verified nodes, but batching is prefetch rather than semantics.

### E.3 Path navigation

A caller can verify the current direct node, select the direct child identity for the next path segment, fetch that child, and repeat. Type resolution or schema validation may demand additional nodes beyond the structural path.

### E.4 Direct-node limitation

A directly materialized node still contains its complete direct manifest and inline identity-bearing text. Very wide containers and very large direct scalars therefore remain unsuitable as fine-grained mutable structures. Chunking is the recommended Language 1.0 authoring pattern.

### E.5 Provider chains

Provider implementations should distinguish definitive `NotFound`, transient `Unavailable`, and deterministic `InvalidEvidence`. None of these outcomes is semantic path absence without the Language operation proving absence from sufficient graph content.

### E.6 Exact graph fragments

An exact graph fragment is ordinary Blue content. A fragment materializes one exact node while replacing any complete direct child with a pure reference to that child's exact Node BlueId. It is not a partial-node identity, cursor language, or fifth Language operation.

A portable fragment utility SHOULD:

- accept one or more exact Root nodes;
- calculate and verify every admitted fragment identity;
- expose original, direct-fragment, and pure-reference Root forms;
- serve defensive copies through a verified provider;
- order fragment identities canonically;
- preserve all Language metadata, schema, list, and reference semantics;
- report `NotFound` for identities it did not admit rather than fabricating content.

Expansion of the fragment graph reconstructs the same exact nodes. Collapsing the original graph to those fragment references preserves every Root Node BlueId.

### E.7 Cyclic-member edges in fragments

A finalized cyclic-set member identity `MASTER#index` is an opaque edge. An ordinary fragment may preserve that reference but MUST NOT claim that the member body is independently verifiable under that identity.

An ordinary fragment provider therefore returns `NotFound` for the member unless it is composed with a cyclic-aware provider that verifies the complete owning set and member index. `this#index`, `ZERO_BLUEID`, malformed member suffixes, inline host object cycles, and cycles among ordinary local fragments remain invalid.

A pure cyclic-set member is not an independently verifiable ordinary Root. A higher runtime may reject it as a processing Root while still permitting ordinary documents and events to contain opaque member references.

*End of Blue Language Specification 1.0.*
