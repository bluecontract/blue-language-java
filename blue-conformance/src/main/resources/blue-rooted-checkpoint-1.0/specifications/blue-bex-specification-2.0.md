# Blue BEX Specification 2.0

> **Status.** Proposed release-candidate revision aligned with the Blue Language empty-object change and the BEX-to-Blue null-boundary correction. BEX execution-time `null` remains distinct from `undefined` and `{}`, but its conversion to Blue content now follows the same context-sensitive null and empty-object normalization as an equivalent Blue Source value. Operator semantics, counter ownership, counter names, formulas, and trace ordering otherwise remain the implementation baseline. Final public publication MUST regenerate dependent runtime, fixture, gas-expectation, and package identities after the Language and Contracts changes are complete.

> **Scope.** This document defines Blue BEX: a deterministic expression and statement language encoded as Blue-compatible data. It specifies the program model, compilation, values, expressions, statements, functions, pointers, result accumulation, Blue host boundary, exact gas ledger, errors, fixtures, and conformance. It does not redefine Blue Language or Contracts processing.

BEX programs are Blue data. BEX execution is computation above Blue content. A BEX runtime integrated with Contracts 1.0 receives identity-preserving read-only Blue values and contributes one exact child ledger to the Contracts shared meter.

## Conventions

The key words **MUST**, **MUST NOT**, **REQUIRED**, **SHOULD**, **SHOULD NOT**, **MAY**, and **OPTIONAL** are normative requirement levels.

Sections marked **normative** define required behavior. Sections marked **informative** explain intent or implementation guidance.

The term **Language** means Blue Language 1.0. The term **Contracts** means Blue Contracts and Processor 1.0.

---

## 0. Overview

Blue BEX, short for **Blue Expression Objects**, is a deterministic scripting language written as Blue-compatible object trees.

A BEX program contains one of:

- a root expression under `expr`;
- a statement sequence under `do`;
- an `entry` function name;
- reusable `constants` and `functions`.

BEX has no ambient side effects. It does not mutate the input Root, perform network I/O, read clocks, use randomness, or execute arbitrary host callbacks. It computes a result containing:

```text
BexExecutionResult {
    value
    changeset
    events
    runtimeLedger
    diagnostic?
}
```

The Contracts host decides whether to admit the value, apply patches, emit events, or reject the result.

A typical pipeline is:

```text
BEX Source Blue node
  -> compile immutable program
  -> execute against identity-preserving context
  -> return value, changes, events, and exact child ledger
  -> Contracts validates and merges the result exactly once
```

### 0.1 Operator shape

An expression operator is an object with exactly one field whose key begins with `$`:

```yaml
$add: [1, 2]
```

An object with more than one field is a literal/computed object even if a key begins with `$`:

```yaml
$foo: 1
bar: 2
```

A statement object contains exactly one statement operator field and no siblings.

### 0.2 BEX and Blue

BEX operators are not Language operators. BEX may read Blue nodes, compare or match values, and produce Blue-compatible values, but it does not change BlueId rules.

A BEX implementation MUST preserve these boundaries:

- executable BEX is forbidden inside static Language fields such as `type`, `itemType`, `keyType`, `valueType`, `blue`, and `schema`;
- output claiming to be Blue content must pass the Blue output boundary;
- host Blue values are immutable and identity-preserving;
- a verified pure reference and its materialization are indistinguishable to portable BEX operators;
- exact identity is exposed only by `$nodeBlueId`;
- provider, cache, storage, and materialization state are not BEX values;
- all runtime work enters one named live-bounded ledger.

### 0.3 Existing exact values and transient values

BEX distinguishes two implementation-level categories without exposing them as ordinary application kinds:

```text
Exact Blue value:
  an admitted Blue node with a known exact Node BlueId and optional materialization.

Transient BEX value:
  a scalar, list, object, null, or undefined produced by BEX and not yet admitted as a Blue node.
```

Portable operators observe semantic kind and content, not the category. Passing an exact Blue value preserves its Node BlueId and does not recursively clone or size it. A transient aggregate pays construction work as it is produced. Crossing an identity/output boundary pays Blue normalization and identity work through the Contracts semantic ledger.

---

## 1. Scope, Versioning, and Conformance

### 1.1 Goal

Blue BEX 2.0 defines:

- deterministic expression and statement execution;
- immutable identity-preserving Blue context values;
- representation-blind access, equality, matching, iteration, and truthiness;
- explicit `$processingEvent` and `$nodeBlueId` operations;
- deterministic functions, constants, collection operations, pointers, patches, and events;
- fail-closed registered intrinsics;
- strict Blue output admission;
- one exact named gas schedule shared with Contracts;
- machine-readable conformance vectors and error classes.

### 1.2 Out of scope

BEX does not define:

- Blue parsing, BlueId, resolution, or provider transport;
- Contracts routing, checkpoints, lifecycle, or patch application;
- host authorization or persistence;
- clocks, randomness, I/O, or nondeterministic intrinsics;
- concurrent execution semantics.

### 1.3 Version selection

This document defines **Blue BEX 2.0**. BEX 2.0 is an incompatible runtime generation selected by the exact runtime-type BlueId, such as `Compute 2.0`. A platform may continue to recognize older runtime types under their own exact semantics, but a runtime type claiming BEX 2.0 conformance MUST implement this specification.

A BEX program does not carry a required `bexVersion` field. The exact executable runtime-type BlueId, such as the canonical type registered as `Compute 2.0`, selects this specification, its intrinsics, and its gas schedule.

An incompatible change to operator recognition, evaluation order, values, equality, pointer semantics, output admission, or gas requires a new BEX version and runtime-type BlueId.

### 1.4 Conformance

A conforming Blue BEX 2.0 implementation MUST implement the complete specification:

- compilation and static validation;
- execution and all required operators;
- exact context semantics;
- strict host boundary;
- exact runtime counters and weights;
- live gas-limit enforcement;
- deterministic diagnostics;
- the BEX 2.0 conformance suite.

A compiler-only component may describe itself as a BEX 2.0 compiler, but not as a conforming BEX 2.0 runtime.

The source-controlled conformance baseline is:

```text
normative vectors: 75
behavior fixtures: 120
gas microfixtures: 30
normative operators: 86
runtime registry: sha256:2ccbfc9d1a1c4425cdcaf37c924274cc4398f82ac72769a8c2cf1dd8ba2fd04b
gas manifest: sha256:41247c820d91a12fdfc17fd9e787a5d8d668d8acc5954fdcb131715bf9e6147d
fixture package: sha256:fdb896bf467c0ab8f3b2c9af3b609c9e7d0dc368dd3061f4b2909d0ee1fa89c3
```

These identities MUST be rebuilt after any identity-bearing registry,
manifest, or fixture change. The machine-readable `blue-bex/gas/2.0` manifest
remains normative for portable counters, weights, formulas, and forbidden
metering shortcuts.

### 1.5 Dependencies

Portable BEX 2.0 uses Blue Language 1.0 for node semantics and output admission. When hosted by Contracts 1.0:

- `$document` is the current Root view;
- `$event` is the current channelized payload;
- `$processingEvent` is the original external `PROCESS` event;
- `$currentContract` is the frozen executing contract;
- runtime counters merge into the Contracts meter exactly once.

---

## 2. BEX Source Documents and Program Model

### 2.1 BEX source as Blue data (normative)

A BEX source is a Blue-compatible node. BEX does not use a separate textual grammar. YAML and JSON authoring follow the host's Blue parser rules.

The root program node MAY contain:

| Field | Shape | Meaning |
|---|---|---|
| `constants` | plain object | Named compile-time constants. |
| `functions` | plain object | Named user-defined functions. |
| `expr` | any BEX expression | Root expression program. |
| `do` | list of statements | Root statement program. |
| `entry` | string | Name of the function to invoke as the root program. |

Other ordinary fields are not program-control fields. An exact runtime type may define host bindings and intrinsics, but it does not change the BEX program-selection rules. Programs commonly include Blue metadata such as `name` or `type`, but those fields do not by themselves affect BEX execution unless read or returned as data.

### 2.2 Program selection (normative)

The root executable is selected as follows:

1. If an explicit host API entry name is supplied, that function is the root executable.
2. Otherwise, if the program node contains an `entry` field, its string value is the root executable name.
3. Otherwise, if the program node contains `expr`, the root executable is that expression.
4. Otherwise, the root executable is the statement list under `do`.
5. If no executable content is present, the root statement program is empty and returns the default result value described in §7.9.

An entry function MUST exist and MUST declare no arguments. If an entry function declares arguments, compilation MUST fail.

### 2.3 Definition node plus program node (normative)

An implementation MAY accept a separate **definition node** and **program node**. When both are supplied:

- constants from the definition node are loaded first;
- constants from the program node are loaded second and replace same-named definition constants;
- functions from the definition node are loaded first;
- functions from the program node are loaded second and replace same-named definition functions.

Replacement is by name. Implementations MUST make replacement deterministic.

### 2.4 Plain name containers (normative)

The following BEX containers are **plain name containers**:

- `constants`;
- `functions`;
- function `args`;
- `$call.args`.

A plain name container MUST be an ordinary object map and MUST NOT use Blue language keys, BEX list-control keys, or Blue wrapper keys as user-defined names.

The reserved user-name set is:

```text
name, description,
type, itemType, keyType, valueType,
value, items,
blueId, blue,
schema, constraints, mergePolicy,
properties, contracts,
$previous, $pos, $replace, $empty
```

A plain name container MUST NOT be a scalar node, list node, pure reference, Blue wrapper node, or list-control node.

### 2.5 Static Blue definition fields (normative)

BEX expressions MUST NOT appear inside static Blue definition fields:

```text
type, itemType, keyType, valueType, blue, schema
```

This rejection applies recursively inside those fields. For example, a computed `type`, a computed `blueId` inside `type`, or a BEX operator embedded in `schema` MUST be rejected at compile time.

The same static rule applies to `$intrinsic.type`: the intrinsic operation BlueId MUST be knowable at compile time.

The purpose is to keep Blue type definitions, schema declarations, and intrinsic operation identity static Blue content, rather than executable BEX content.

### 2.6 Literal escape (normative)

`$literal` returns its body as literal data and prevents nested BEX operator interpretation, except that the compiler MUST still reject BEX expressions in static Blue definition fields before accepting the literal.

Example:

```yaml
$literal:
  $unknownOperator: kept as data
```

Invalid because the nested expression is inside a static Blue type field:

```yaml
$literal:
  type:
    $const: SomeType
```

### 2.7 Operator recognition (normative)

An expression object is a BEX operator if and only if it has exactly one field and that field name begins with `$`.

- If the sole operator name is unknown, compilation MUST fail.
- If an object has more than one field, it is not an expression operator solely by virtue of dollar-prefixed fields.
- Lists, scalars, and objects without an operator shape are literals unless they contain nested BEX expressions in ordinary expression positions.

A statement object MUST have exactly one statement operator field. Unknown statement operators or sibling fields MUST fail compilation.

Null or empty statement items MUST fail compilation.

---

## 3. BEX Values

### 3.1 Semantic value kinds (normative)

BEX exposes exactly these semantic value kinds:

| Kind | Meaning |
|---|---|
| `undefined` | Internal absence. It is not a Blue value and is invalid as root output. |
| `null` | Explicit null-like BEX value. |
| `text` | Exact Unicode text. |
| `integer` | Arbitrary-precision mathematical integer. |
| `double` | Exact decimal execution value used at the finite Blue `Double` boundary. |
| `boolean` | `true` or `false`. |
| `object` | String-keyed map of BEX values. |
| `list` | Ordered sequence of BEX values. |

An implementation may internally represent a value as an exact Blue node, a pure reference, an immutable cursor, a frozen snapshot, a transient aggregate, or an overlay. These are **not** additional BEX kinds and MUST NOT be observable through `$kind`, `$isKind`, truthiness, equality, matching, iteration, key enumeration, size, pointer access, or serialization.

### 3.2 Exact Blue values and transient values (normative)

An **exact Blue value** retains an established Node BlueId and may be materialized only as far as the program demands. A verified pure reference and its verified materialization are the same exact Blue value.

A **transient value** is constructed by BEX and does not yet have to possess a Blue Node BlueId. It becomes exact only when it crosses the Blue output boundary or when `$nodeBlueId` explicitly requests identity.

Rules:

1. Passing an exact Blue value through a variable, function, list, object, patch, event, or return value MUST preserve its exact identity without recursively cloning, serializing, sizing, hashing, or materializing it.
2. Access to an exact Blue value MAY demand the direct node or selected descendants. A limit, unavailable reference, or missing evidence MUST NOT be interpreted as a missing field or non-match.
3. Hidden cache state and current materialization MUST NOT change result, failure class, counter trace, or gas once the same required evidence is available.
4. BEX code may observe exact identity only through `$nodeBlueId`.
5. Reconstructing content equal to an already stored global node is still transient construction and pays construction and identity-boundary work. Cache presence cannot make that work free.

### 3.3 Undefined (normative)

`undefined` represents semantic absence within BEX.

- Reading a semantically absent object key returns `undefined`.
- Reading an out-of-range list index returns `undefined`.
- `$pointerGet` returns `undefined` for an absent path unless a default is supplied.
- Object construction omits fields whose evaluated value is `undefined`; it may retain `null` as an execution-time member. If that object later crosses a Blue output boundary, null-valued object members are omitted under §11.6a.
- Lists MUST NOT contain `undefined` items.
- Converting root `undefined` to a Blue node MUST fail.
- Incomplete access, provider unavailability, or a limit is an error/suspension outcome, not `undefined`.

### 3.4 Null (normative)

`null` is a BEX value, not BEX absence.

- `null` is falsy.
- `$exists(null)` is true.
- `null` is distinct from `undefined` and from an empty object during BEX execution.
- Blue Language 1.0 does not define a canonical null node. BEX `null` therefore has no direct Blue Node BlueId merely because it exists during execution.
- When BEX content crosses the strict Blue output boundary, `null` is normalized by its structural position under §11.6a:
  - a root `null`, patch value `null`, emitted-event root `null`, exact-node intrinsic input `null`, or `$nodeBlueId(null)` request fails Blue output admission;
  - a `null` object member is omitted;
  - a `null` list item becomes the exact Blue positional placeholder `{ $empty: true }`.
- A program that needs a present empty Blue object MUST construct `{}` explicitly, use `$emptyObject`, or use another operator such as `$object` whose specified result is an object.

BEX `null` and `undefined` may therefore produce the same *object-member omission* at the Blue boundary while remaining observably distinct during execution. In lists they do not converge: `undefined` is invalid, while `null` becomes the Blue positional placeholder. This boundary rule prevents an execution-time null sentinel from silently becoming present empty-object business content.

### 3.5 Scalars and the Blue numeric boundary (normative)

BEX scalar values are exact Text, arbitrary-precision Integer, exact decimal execution values, and Boolean.

A Blue `Integer` becomes a BEX Integer exactly. A Blue `Double` becomes the exact decimal value represented by the shortest round-tripping decimal rendering of its finite IEEE 754 binary64 value. Source token spelling is never observable.

BEX numeric equality is computation equality: Integer `1` and decimal `1.0` compare equal. This is not Blue scalar identity. `$nodeBlueId` is the operation for exact Blue identity.

Conversion back to Blue is deterministic:

- a BEX Integer becomes Blue `Integer`;
- every BEX decimal, including a mathematically integral decimal such as `1.0`, becomes Blue `Double` by round-to-nearest, ties-to-even binary64 conversion;
- decimal provenance is preserved at the Blue boundary, so BEX Integer `1` and BEX decimal `1.0` may compare numerically equal while producing different Blue scalar identities;
- overflow, NaN, or infinity fails;
- an explicit Blue-shaped scalar must satisfy Blue Language 1.0.

### 3.6 Objects (normative)

BEX objects map Text keys to BEX values.

- A semantically absent key reads as `undefined`.
- Keys are exposed in lexicographic Unicode code-point order for `$keys`, `$entries`, object iteration, deterministic conversion, sorting inputs, and diagnostics.
- Object construction omits `undefined` fields and preserves `null` fields during BEX execution. At the Blue output boundary, preserved null-valued object members are omitted under §11.6a.
- Exact Blue objects may remain collapsed behind their Node BlueIds until a direct member is demanded.
- Key enumeration requires the complete direct key set. Inability to establish that set is not an empty object.

Object equality compares key set and values, not insertion order or physical representation.

### 3.7 Lists (normative)

BEX lists are ordered sequences.

- Indexing is zero-based.
- An index must be a non-negative Integer.
- An out-of-range index reads as `undefined` only after length or absence is established.
- Lists MUST NOT contain `undefined`.
- Order and multiplicity are preserved.
- Exact Blue lists may retain exact element identities without materializing element bodies.

### 3.8 Truthiness (normative)

| Value | Truthy? |
|---|---|
| `undefined` | false |
| `null` | false |
| Boolean | its value |
| Text | true iff non-empty |
| Integer or decimal | true, including zero |
| object | true iff it has at least one semantic key |
| list | true iff it has at least one item |

Truthiness of an exact Blue object or list is determined from semantic direct structure, not whether the value is currently a pure reference or materialized. `$empty(x)` is `not truthy(x)`.

### 3.9 Equality and identity (normative)

`$eq` and `$ne` use BEX semantic equality:

- `undefined` equals only `undefined`;
- `null` equals only `null`;
- Boolean and Text compare by exact value;
- numeric values compare numerically;
- lists compare length and elements in order;
- objects compare semantic key set and values.

When both operands are exact Blue values with known Node BlueIds, an implementation MAY conclude equality or inequality from those identities where the semantic relation permits it. It MUST report the canonical counter trace defined in §12, independent of the physical shortcut. Otherwise equality visits only the semantic nodes required by the recursive comparison.

`$eq` is not a substitute for BlueId equality. `$nodeBlueId` returns exact Blue identity and establishes identity for a transient value when necessary.

### 3.10 Conversions (normative)

| Operator | Conversion |
|---|---|
| `$text` | `undefined` and `null` become `""`; scalars use §3.10.1; non-scalars fail. |
| `$integer` | Exact Integer conversion; fractional decimals and invalid Text fail. |
| `$number` | Decimal conversion from Integer, decimal, or numeric Text. |
| `$boolean` | `undefined`/`null` become false; Boolean stays; Text `true`/`false` map directly; otherwise truthiness. |
| `$object` | `undefined`/`null` become `{}`; objects pass through; otherwise fail. |
| `$list` | `undefined`/`null` become `[]`; lists pass through; otherwise fail. |

### 3.10.1 Scalar text representation (normative)

All Text-producing and Text-consuming scalar operations use one locale-independent representation:

- Booleans: `true` or `false`;
- Integers: canonical decimal text;
- decimals: canonical `BigDecimal.toString()`-equivalent rendering with no locale dependence;
- Text: unchanged, with no Unicode normalization.

Text work is metered in Unicode code-point blocks under §12, never in UTF-16 units and never by recursive value size.

## 4. Compilation

### 4.1 Compile-time failures (normative)

Compilation MUST fail for:

- unknown expression operators;
- unknown statement operators;
- malformed statement objects;
- null or empty statement items;
- unknown constants referenced by `$const`;
- unknown variables referenced by `$var`, including object-form `$var.name`;
- unknown functions referenced by `$call` or `entry`;
- missing function call arguments;
- extra function call arguments;
- reserved names in user-defined name containers;
- BEX expressions inside static Blue definition fields;
- dynamic or computed patterns for function arguments and `$is.pattern`;
- recursive function calls, whether direct or indirect;
- invalid operator body shapes that are statically known;
- unsupported `$intrinsic.type` BlueIds for the active processor registry;
- invalid `$isKind.kind` values;
- duplicate binding names within a single collection query or `$reduce` binding set;
- `$let.order` that omits, duplicates, or references names outside `$let.vars`.

Compile-time failures MUST expose an error class. They MUST include the operator name when the failing operator is known. They MUST include a source path within the BEX source document when the source path is known. They MUST NOT fabricate a path or operator name if unavailable.

### 4.2 Root compilation (normative)

A compiled program contains a root function. The root function either evaluates a root expression, executes root statements, or invokes an entry function.

Every execution invokes the root function and therefore charges function-call gas (§12).

### 4.3 Function compilation (normative)

A user function definition MAY contain:

| Field | Shape | Meaning |
|---|---|---|
| `args` | plain object | Required argument patterns by name. |
| `expr` | expression | Expression function body. |
| `do` | list of statements | Statement function body. |

If `expr` is present, the function is an expression function. Otherwise, the function is a statement function using `do`. If `do` is absent or empty, the statement function has an empty statement list and returns the default result value (§7.9).

All declared arguments are required. Extra call arguments are forbidden. Function argument names are matched by name, not by position.

Function argument patterns are static Blue nodes. The compiler MUST reject BEX expressions inside argument patterns.

### 4.4 Recursion (normative)

BEX functions MUST NOT be recursive. The compiler MUST reject direct or indirect cycles in the static call graph.

Calls hidden inside `$literal` are not executable calls and MUST NOT contribute to the call graph. Calls inside static `$is.pattern` content are invalid because static patterns cannot contain BEX expressions.

### 4.5 Local variables (normative)

Local variables are function-frame slots.

- `$let` declares or assigns a local variable in the current function frame.
- `$set` assigns an existing local variable and MUST fail compilation if the variable has not been declared in the current function's compile scope.
- `$let.vars` may declare or assign multiple local variables with either parallel or explicit sequential evaluation (§7.3).
- Function calls execute in a separate frame.
- Caller local variables MUST NOT leak into callee frames.
- Callee variables MUST NOT leak back into caller frames, except through the returned value, changeset, or events.

Nested statement blocks do not create separate lexical scopes under BEX 2.0. Duplicate loop binding names within a single `$forEach` statement MUST be rejected.

Collection query binding slots (`item`, optional `key`, optional `index`, and `$reduce.acc`) are temporary for the expression evaluation. If they reuse an outer local variable name, the previous slot value MUST be restored after the collection expression completes or fails.

### 4.6 Static versus dynamic operands (normative)

Many operators accept either static scalar shorthand or dynamic expressions. Implementations MUST distinguish:

- a static omitted path, which may intentionally mean a default root path;
- a dynamic expression that evaluates to `undefined` or `null`, which MUST fail when a path, key, operation, binding name, or step name is required.

Dynamic text operands that evaluate to `undefined` or `null` MUST fail for:

```text
$get.key, $objectSet.key, $hasKey.key, $binding.name, $steps.step,
$appendChange.op, $pointerSet.op
```

Dynamic pointer operands that evaluate to `undefined` or `null` MUST fail.

---

## 5. Execution Context and Blue Views

### 5.1 Execution context (normative)

A BEX execution context may provide:

| Context value | Meaning |
|---|---|
| `documentScope` | Scope-relative pointer base. |
| `rootDocument` | Current exact Root view. |
| resolved document access | Demand-limited resolved access when requested. |
| `event` | Current channelized payload. |
| `processingEvent` | Original external event passed to `PROCESS`; equal to `event` outside nested/internal delivery. |
| `currentContract` | Frozen effective executing contract snapshot. |
| `steps` | Named prior workflow-step values. |
| `bindings` | Named host bindings. |
| shared gas meter | Live-bounded child meter supplied by the host. |
| registered intrinsics | Deterministic operations selected by exact runtime type BlueId. |

Missing optional context values read as `undefined`, except that an operation requiring a missing mandatory host binding fails.

### 5.2 Document access (normative)

`$document` reads the current Root view. Static or dynamic pointers are relative to `documentScope` unless absolute.

```yaml
$document: /status
```

A demand-limited resolved read is requested with:

```yaml
$document:
  path: /status
  view: resolved
```

Only `view: resolved` selects resolved access. The host MUST either establish the requested semantic result, report deterministic invalidity, or suspend/fail because required evidence is unavailable. It MUST NOT substitute unresolved, empty, or absent content.

### 5.3 Event and causal-event access (normative)

`$event` reads the current delivery payload. `$processingEvent` reads the original external event that caused the complete Contracts invocation.

For an external Channel delivery:

```text
$event == $processingEvent
```

For a Document Update, Triggered Event, Lifecycle Event, or Embedded Event delivery, `$event` is the immediate payload while `$processingEvent` remains the original external event.

### 5.4 Scope-relative pointers (normative)

- A missing or empty static document path selects `documentScope`.
- An absolute JSON Pointer selects from Root.
- A relative pointer appends its segments to `documentScope`.
- Value-local pointer operators always begin at the evaluated value rather than Root.

### 5.5 Identity-preserving host values (normative)

Host Blue values are immutable exact-value carriers. The host may implement them as frozen nodes, cursors, handles, persistent nodes, or another representation, but BEX MUST observe only semantic value and explicit identity.

The host MUST NOT:

- expose a pure-reference wrapper as an object with a semantic `blueId` child;
- clone or recursively materialize exact values merely to pass them into BEX;
- let hidden cache state alter results or gas;
- convert incomplete access into absence;
- mutate a host value during execution.

A BEX execution may construct transient overlays and aggregates. Such values remain separate from host state until returned as effects or output.

## 6. Expressions

### 6.1 Expression evaluation (normative)

Each expression evaluation MUST:

1. admit one `expressionEvaluated` charge;
2. append that counter to the child ledger;
3. evaluate according to the operator or literal semantics;
4. return a BEX value or fail with a runtime error.

Source wrappers and compile-time structures do not themselves consume gas unless they execute as expressions.

### 6.1.1 Operand evaluation order (normative)

Unless an operator explicitly short-circuits or defines lazy behavior, evaluated operands MUST be evaluated in a deterministic order.

- List operand sequences are evaluated left to right by list index.
- List literal items are evaluated left to right by list index.
- Object literal fields are evaluated in BEX object key exposure order, not host map iteration order.
- `$call.args` expressions are evaluated in lexicographic argument-name order, not source map order.

The following operands remain lazy or selected-only according to their operator semantics:

```text
$and, $or, $coalesce, $choose, $if,
$listGet.default, $pointerGet.default,
$appendChange.val for remove, $pointerSet.val for remove,
$returnIf.expr when cond is false, $failIf.message when cond is false,
$some/$find/$findEntry/$includes items after a short-circuit match
```

Skipped lazy operands produce no side effects and consume no gas.

### 6.2 Literal expressions (normative)

Scalars, lists, and non-operator objects are expressions.

The explicit literal helper operators are:

| Operator | Semantics |
|---|---|
| `$null` | Return BEX `null`. |
| `$emptyObject` | Return an empty object. |
| `$emptyList` | Return an empty list. |

- A scalar literal evaluates to the corresponding scalar BEX value, or `null` for a null literal.
- A list literal evaluates each item and returns a list. If any item evaluates to `undefined`, list construction MUST fail.
- An object literal evaluates ordinary fields in BEX object key exposure order and returns an object. Fields whose values evaluate to `undefined` are omitted.
- Blue language metadata fields may be preserved as Blue output fields when the object is later converted to a Blue node.

### 6.3 Read expressions (normative)

| Operator | Body | Semantics |
|---|---|---|
| `$document` | pointer or `{path, view}` | Read canonical or resolved document view at a document pointer. |
| `$binding` | `name/path` or `{name, path}` | Read named host binding at value-local path. |
| `$event` | pointer | Read current delivery payload at value-local path. |
| `$processingEvent` | pointer | Read the original external `PROCESS` event at value-local path. |
| `$steps` | `step.path` or `{step, path}` | Read named prior step value at value-local path. |
| `$currentContract` | pointer | Read current contract value at value-local path. |
| `$var` | name or `{name, path?}` | Read local variable slot, optionally at a value-local path. |
| `$const` | name or `{name, path?}` | Read compile-time constant, optionally at a value-local path. |
| `$get` | `{object, key}` | Read object key; missing or non-object reads as `undefined`. |
| `$changeset` | ignored | Return accumulated changeset as a BEX value. |
| `$events` | ignored | Return accumulated events as a BEX value. |
| `$resultValue` | pointer | Read document value after accumulated changes have been overlaid. |

`$binding` short form splits at the first `/`. The text before the first slash is the binding name. The slash and following text are the path. If no slash appears, the path is `/`.

`$steps` short form splits at the first `.`. The text before the first dot is the step name. The dot and following text are converted to a path beginning with `/`. If no dot appears, the path is `/`.

`$var` and `$const` object forms have a static `name` and optional value-local `path`:

```yaml
$var:
  name: request
  path: /summary
```

```yaml
$const:
  name: Policy/minimumAmount
  path: /amount
```

The `name` operand is static and MUST NOT be computed. Dynamic variable names and dynamic constant names are not part of BEX 2.0. The `path` operand MAY be static text or a dynamic expression that evaluates to pointer text. A missing path target returns `undefined`.

There is no BEX 2.0 shorthand that splits `$var: request/summary` or `$const: Policy/minimumAmount`. Variable and constant names may contain `/`; object-form `path` exists to avoid changing the meaning of such programs.

All read operators are representation-blind. In particular, `$exists: {$document: /x/blueId}` does not reveal the internal pure-reference wrapper for `/x`; it reads a logical child named `blueId` only when that child is actual content. Exact identity is obtained through `$nodeBlueId`.

### 6.4 Type and conversion expressions (normative)

| Operator | Semantics |
|---|---|
| `$unwrap` | Repeatedly reads `value` while the current value is an object with a defined `value` field. |
| `$is` | Blue type/shape match of `node` against static `pattern`. |
| `$kind` | Return the visible BEX runtime kind of a value. |
| `$isKind` | Return whether a value's visible BEX runtime kind is in a static kind set. |
| `$text` | Convert to text (§3.10). |
| `$integer` | Convert to exact integer (§3.10). |
| `$number` | Convert to decimal number (§3.10). |
| `$boolean` | Convert to boolean (§3.10). |
| `$object` | Convert `undefined`/`null` to `{}` or pass through object; otherwise fail. |
| `$list` | Convert `undefined`/`null` to `[]` or pass through list; otherwise fail. |
| `$nodeBlueId` | Return the exact Node BlueId of a Blue value; establish identity for a transient value. |

`$is` body MUST contain `node` and static `pattern` operands. The pattern MUST NOT contain BEX expressions.

`$is` evaluates `node` first. If evaluating `$is.node` itself fails, that runtime failure propagates and is not converted to `false`.

If `$is.node` evaluates successfully but the resulting value is `undefined`, `$is` returns `false`. If conversion of the successfully evaluated value to a Blue node fails, `$is` returns `false`. If the Blue type matcher returns no match, `$is` returns `false`.

If the static pattern is malformed or contains BEX expressions, compilation fails. Host, provider, or type-resolution failures required to evaluate the pattern deterministically are errors according to the Blue Language 1.0 matcher semantics; implementations MUST NOT silently treat unavailable type definitions as a successful non-match. Function argument matching uses the same type matcher boundary (§8.3).

`$kind` returns one of:

```text
undefined, null, text, integer, double, boolean, object, list
```

`$kind` exposes semantic BEX kind only. A pure reference, cursor, frozen node, materialized node, and equivalent transient value report the same kind once the semantic kind is established. Incomplete access is not `object`, `undefined`, or `false`; it is an error/suspension outcome.

`$isKind` has body `{val, kind}`. The `kind` operand is static authored data and MUST be either a single kind text value or a list of kind text values. Unknown kind names MUST fail compilation. The `val` operand is evaluated at runtime.

Example:

```yaml
$isKind:
  val:
    $event: /message/request/amount
  kind: [integer, double]
```

### 6.5 String expressions (normative)

| Operator | Body | Semantics |
|---|---|---|
| `$concat` | list of operands | Convert each operand to text and concatenate. |
| `$pointerJoin` | list of segment operands | Convert each segment to text, JSON-Pointer-escape it, and join as an absolute pointer. No segments returns `/`. |
| `$join` | `{list, separator}` | Join list items converted to text using separator text. |
| `$split` | `{text, separator, limit?}` | Split text by non-empty separator. `limit` is optional; `-1` means no limit. |
| `$startsWith` | two operands | True if the first text starts with the second text. |
| `$sliceAfter` | two operands | If first text starts with second text, return the suffix after that prefix; otherwise return empty text. |

`$split.separator` MUST NOT be empty. A missing `$split.limit` is equivalent to `-1`.

`$split.limit` semantics are:

- `-1` means no limit and preserves trailing empty parts;
- `n > 0` returns at most `n` parts;
- for `n > 0`, the first `n - 1` separator occurrences split normally, and the final part contains the remaining suffix, including separators that were not consumed;
- `1` returns the whole input as a single-item list;
- `0` or any value less than `-1` MUST fail;
- if the separator does not occur, the result is a one-item list containing the input text.

Examples:

```yaml
{ $split: { text: "a,b,c", separator: ",", limit: 2 } }
# => ["a", "b,c"]

{ $split: { text: "a,", separator: "," } }
# => ["a", ""]

{ $split: { text: "a,", separator: ",", limit: 1 } }
# => ["a,"]
```

`$pointerJoin` MUST escape `~` as `~0` and `/` as `~1`.

### 6.6 Logic and comparison expressions (normative)

| Operator | Semantics |
|---|---|
| `$eq` | BEX equality of exactly two operands. |
| `$ne` | Negation of `$eq`. |
| `$gt`, `$gte`, `$lt`, `$lte` | Numeric comparison of exactly two operands using decimal numeric conversion. |
| `$and` | Left-to-right truthy conjunction with short-circuit. Empty operand list returns `true`. |
| `$or` | Left-to-right truthy disjunction with short-circuit. Empty operand list returns `false`. |
| `$not` | Truthy negation. |
| `$truthy` | Return truthiness as boolean. |
| `$empty` | Return inverse truthiness as boolean. |
| `$isEmpty` | Alias for `$empty`. |
| `$exists` | Return false only for `undefined`; true otherwise. |
| `$coalesce` | Return the first truthy operand; if none is truthy, return `undefined`. |
| `$default` | Alias for `$coalesce`. |

`$and`, `$or`, and `$coalesce` MUST NOT evaluate operands after their result is determined. Unevaluated operands consume no gas and produce no errors.

`$isEmpty` is an exact alias for `$empty`. It exists to avoid ambiguity with Blue empty-placeholder authoring syntax when an empty check appears in a list operand.

### 6.7 Numeric expressions (normative)

| Operator | Semantics |
|---|---|
| `$add` | Exact integer addition. |
| `$subtract` | Exact integer subtraction. |
| `$multiply` | Exact integer multiplication. |
| `$divide` | Exact integer division. |

Numeric arithmetic operators use exact integer conversion for all operands. Non-integer numeric values and invalid integer text MUST fail. Division by zero MUST fail. Division with a non-zero remainder MUST fail.

A one-operand numeric expression returns that operand converted to integer.

### 6.8 Object and list expressions (normative)

| Operator | Body | Semantics |
|---|---|---|
| `$keys` | expression | Return sorted object keys as a list; non-objects return `[]`. |
| `$entries` | expression | Return sorted object entries as `{key, val}` objects. |
| `$size` | expression | List length, object field count, scalar `1`, or `0` for `undefined`/`null`. |
| `$listGet` | `{list, index, default?}` | Read list index; use default only when missing. |
| `$listConcat` | list of list operands | Concatenate lists. All operands MUST be lists. |
| `$merge` | list of object operands | Shallow merge objects left to right; later keys win. |
| `$objectSet` | `{object, key, val}` | Set or remove object key. Undefined `val` removes the key. |
| `$pointerGet` | `{object, path, default?}` | Read a value-local JSON Pointer; evaluate default only when missing. |
| `$pointerSet` | `{object, path, op?, val?}` | Set or remove a value-local JSON Pointer. |
| `$map` | `{in, item, key?, index?, expr}` | Project each list item or sorted object value to a list. |
| `$filter` | `{in, item, key?, index?, where}` | Keep matching list items or object fields. |
| `$flatMap` | `{in, item, key?, index?, expr}` | Project each item to a list and concatenate. |
| `$reduce` | `{in, acc, init, item, key?, index?, expr}` | Fold a list or sorted object values into an accumulator. |
| `$some` | `{in, item, key?, index?, where}` | Return true on the first truthy match; otherwise false. |
| `$find` | `{in, item, key?, index?, where}` | Return the first matching item/value, or `undefined`. |
| `$findEntry` | `{in, item, key?, index?, where}` | Return the first matching entry object, or `undefined`. |
| `$includes` | `{list, val}` | Return whether a list contains `val` by BEX equality. |
| `$hasKey` | `{object, key}` | Return whether an object has a non-undefined value for `key`. |
| `$objectFromEntries` | list expression | Build an object from `{key, val}` entries. |

`$objectSet` treats an `undefined` or `null` object operand as `{}`. It MUST fail for scalar base values.

`$pointerSet.op` defaults to `set`. The only valid operations are `set` and `remove`. `remove` MUST NOT evaluate its `val` operand. `set` MUST evaluate `val`.

When `$pointerSet` needs to create missing intermediate containers, it creates objects. It MUST fail if an existing intermediate value is scalar or otherwise incompatible with traversal.

`$size` is a collection/cardinality helper. For scalar text it returns `1`, not text length. A dedicated text code-point length operator is a candidate for a later BEX revision.

#### 6.8.1 Collection query expressions (normative)

Collection query expressions accept either a list or an object in `in`. Non-list and non-object inputs MUST fail unless a specific operator says otherwise.

For list input:

- iteration order is list index order;
- `item` receives the list item;
- `index`, when present, receives the zero-based integer index;
- `key`, when present, receives `undefined`.

For object input:

- iteration order is lexicographic Unicode code-point order of object keys;
- `item` receives the field value;
- `key`, when present, receives the object key text;
- `index`, when present, receives the zero-based ordinal in sorted-key order.

The binding names `item`, optional `key`, optional `index`, and `$reduce.acc` within one collection expression MUST be distinct when present. Collection expression bindings MUST restore any previous slot values after the expression completes or fails.

`$map` evaluates `expr` for each item and returns a list of results. Object input still returns a list, in sorted-key order.

`$filter` evaluates `where` for each item. For list input it returns a filtered list preserving item order. For object input it returns a filtered object preserving BEX object key exposure semantics.

`$flatMap` evaluates `expr` for each item. Each result MUST be a list. The returned value is a concatenated list of all projected lists.

`$reduce` evaluates `init` once after evaluating `in`, assigns it to `acc`, then evaluates `expr` once per item. After each iteration, the expression result becomes the next accumulator value. The final accumulator is returned. For empty input, the `init` value is returned.

`$some`, `$find`, `$findEntry`, and `$includes` are short-circuiting. Items after a determined result MUST NOT be evaluated and consume no gas.

`$some` returns true for the first truthy `where`, otherwise false.

`$find` returns the first item/value whose `where` is truthy, otherwise `undefined`.

For list input, `$findEntry` returns:

```yaml
val: <item>
index: <zero-based-index>
```

For object input, `$findEntry` returns:

```yaml
key: <object-key>
val: <field-value>
index: <zero-based-sorted-key-index>
```

`$includes.list` MUST evaluate to a list. It evaluates `val` once, then compares each list item using BEX equality. It returns true on the first equal item, otherwise false.

`$hasKey.object` returns false for non-object inputs. For object inputs it returns true when reading `key` returns a non-`undefined` value. The `key` operand is a static or dynamic text operand and follows dynamic text failure rules (§4.6).

`$objectFromEntries` requires a list of object entries. Each entry MUST be an object. Each entry key is read from `key`; `undefined` or `null` keys MUST fail. Keys are converted to text. Each entry value is read from `val`; an `undefined` value removes/omits that key from the output object. Duplicate keys use the last non-undefined value unless a later undefined value removes the key.

### 6.9 Result helper expressions (normative)

`$changeset` returns the accumulated changeset as a list of patch objects.

`$events` returns the accumulated event list.

`$resultValue` reads the input document after applying all accumulated patches in order through the result overlay model (§10). It charges `resultValueRead` in addition to `expressionEvaluated`.

### 6.10 Control expressions (normative)

| Operator | Body | Semantics |
|---|---|---|
| `$choose` | `{cond, then, else?}` | Evaluate `cond`; if truthy evaluate `then`, otherwise evaluate `else` or return `undefined`. |
| `$call` | `{function, args}` | Invoke a user-defined function with named arguments. |
| `$intrinsic` | object with static `type` and payload fields | Invoke a registered host intrinsic processor by the BlueId of `type`. |
| `$literal` | any | Return literal body without nested BEX interpretation, subject to §2.6. |
| `$null`, `$emptyObject`, `$emptyList` | ignored | Return explicit null, empty object, or empty list. |

`$call` evaluates argument expressions, validates each argument against its declared static pattern, and then invokes the callee in a new frame. Argument validation failure is a runtime error.

`$call` may also appear as a statement; statement-form `$call` invokes the function for effects and discards its return value (§7.2).

### 6.11 `$intrinsic` (normative)

`$intrinsic` is BEX's explicit host capability boundary. It invokes a host-registered processor keyed by the BlueId of the static `type` field.

Example:

```yaml
$intrinsic:
  type:
    blueId: CommonCryptoEd25519Verify
  publicKey:
    $const: trustedSignerPublicKey
  message:
    $event: /message/canonicalBytes
  signature:
    $event: /message/signature
```

Rules:

- `$intrinsic` body MUST be an object.
- `type` is REQUIRED and MUST be static authored Blue data. BEX expressions inside `type` MUST fail compilation.
- The implementation resolves or computes the BlueId of `type` under Blue Language 1.0.
- The resolved BlueId MUST have a registered intrinsic processor for the exact active BEX runtime type. Otherwise compilation MUST fail.
- Payload fields are the ordinary object properties beside `type`. The `type` field itself is not passed as a payload field; processors receive it separately as static type data.
- Payload field expressions are evaluated normally in deterministic object key exposure order. Fields evaluating to `undefined` are omitted.
- A processor returns one BEX value. A host API that permits a null implementation return MUST normalize it to BEX `undefined` or fail deterministically.
- A processor is responsible for returning a deterministic named child ledger for its own work (§12.13).

`$intrinsic` MUST NOT be used for arbitrary non-deterministic host calls in a portable BEX 2.0 execution. Standard intrinsics such as signature verification MUST define their exact input bytes, failure behavior, and conformance vectors outside the BEX program text.

---

## 7. Statements

### 7.1 Statement execution (normative)

Each statement execution MUST:

1. admit one `statementExecuted` charge;
2. append the charge to the child ledger;
3. execute according to the statement operator;
4. either proceed to the next statement, return, or fail.

A statement list executes in order until it completes, returns, or fails.

### 7.2 Statement operators (normative)

| Operator | Body | Semantics |
|---|---|---|
| `$let` | `{name, expr}` or `{vars, order?}` | Declare or assign one or more local variables. |
| `$set` | `{name, expr}` | Assign existing local variable. |
| `$if` | `{cond, then?, else?}` | Execute selected statement list. |
| `$forEach` | `{in, item, key?, index?, do}` | Iterate list or object. |
| `$appendChange` | `{op, path, val?}` | Append one document patch. |
| `$appendChanges` | expression | Append a list of patch entries. |
| `$appendEvent` | expression | Append one event value. |
| `$appendEvents` | expression | Append a list of event values. |
| `$call` | `{function, args}` | Invoke function for effects and discard return value. |
| `$return` | expression or empty | Return from current function. |
| `$returnIf` | `{cond, expr?}` | Return from current function when `cond` is truthy. |
| `$fail` | expression or `{message}` | Throw a runtime failure with message text. |
| `$failIf` | `{cond, message}` | Throw a runtime failure when `cond` is truthy. |

### 7.3 `$let` and `$set` (normative)

Single-bind `$let` evaluates `expr` and assigns it to `name` in the current frame. Reusing an existing name assigns the existing slot.

Multi-bind `$let` uses `vars`:

```yaml
$let:
  vars:
    a: 1
    b: 2
```

Without `order`, `$let.vars` is parallel: all binding expressions are evaluated against the frame as it existed before the `$let`, then all variables are assigned. Implementations MAY sort variable names to make evaluation deterministic, but sorted evaluation MUST NOT create intra-batch dependencies.

With `order`, `$let.vars` is sequential:

```yaml
$let:
  order: [request, summary]
  vars:
    request:
      $event: /message/request
    summary:
      $var:
        name: request
        path: /summary
```

`order` MUST list every key in `vars` exactly once. Later ordered bindings may read earlier ordered bindings.

`$set` evaluates `expr` and assigns an existing slot. Compilation MUST fail if the name is not known in the current function compile scope.

### 7.4 `$if` (normative)

`$if` evaluates `cond`. If truthy, it executes `then`; otherwise it executes `else`. Only the selected branch executes and consumes gas.

A missing `then` or `else` branch is an empty statement list.

### 7.5 `$forEach` (normative)

`$forEach.in` MUST evaluate to a list or object.

For a list:

- `item` receives each item;
- `index`, when provided, receives the zero-based integer index;
- `key`, when provided, receives `undefined`.

For an object:

- keys are visited in lexicographic order over Unicode code points of the unescaped key text;
- if `key` is provided, `key` receives the key text and `item` receives the field value;
- if `key` is not provided, `item` receives an object of the form `{ key: <key>, val: <value> }`;
- `index`, when provided, receives `undefined`.

The loop body executes for each visited element. Each visited input pays exactly one `collectionItemVisited` charge plus the evaluated body. There is no additional generic loop-size charge and no charge for items not reached after a function return or failure.

The names `item`, `key`, and `index` within one `$forEach` statement MUST be distinct when present.

BEX 2.0 iteration has no loop-local `break` or loop-local `continue`. `$return` exits the current function, not merely the loop. `$fail` aborts execution.

### 7.6 `$appendChange` (normative)

`$appendChange` appends one patch entry to the changeset accumulator.

Valid operations are:

```text
add, replace, remove
```

Rules:

- `op` is a required text operand.
- `path` is a document pointer operand and is resolved relative to the document scope when not absolute.
- `add` and `replace` require a non-`undefined` `val`.
- `remove` MUST NOT evaluate `val`.
- The appended patch preserves author order. Duplicate paths are allowed and are not coalesced.

### 7.7 `$appendChanges` (normative)

`$appendChanges` evaluates its body as a list of patch entries. Each entry is validated as if supplied to `$appendChange`.

Invalid entries, bad operations, missing values for `add`/`replace`, or non-list inputs MUST fail. Successfully appended entries preserve list order.

### 7.8 `$appendEvent` and `$appendEvents` (normative)

`$appendEvent` evaluates its body and appends the resulting value to the event accumulator. The event value MUST NOT be `undefined`. Events need not be objects.

`$appendEvents` evaluates its body as a list and appends each item in order. Each item MUST NOT be `undefined`.

### 7.9 `$return` (normative)

`$return` returns from the current function.

If `$return` has an expression body, that expression is the returned value. If `$return` is empty or null, the default result value is returned.

At the root statement list, `$return` returns from the root function and therefore exits the whole BEX program. Statements after that root `$return` MUST NOT execute.

The default result value is an object with:

```yaml
changeset: <current changeset as value>
events: <current events as value>
```

If a statement function completes without an explicit `$return`, it returns the same default result value.

### 7.10 `$returnIf` and `$failIf` (normative)

`$returnIf` evaluates `cond`. If truthy, it evaluates optional `expr` and returns from the current function with that value. If `expr` is absent, it returns the default result value. If `cond` is falsy, `expr` MUST NOT be evaluated.

The return payload field is named `expr`. `value` is not a valid `$returnIf` payload field because `value` is a Blue scalar-wrapper field in authored Blue data.

`$failIf` evaluates `cond`. If truthy, it evaluates `message`, converts it to text, and raises a runtime error. If `cond` is falsy, `message` MUST NOT be evaluated.

### 7.11 `$fail` (normative)

`$fail` raises a runtime error. If the body is an object with a `message` field, the `message` operand supplies the error message. Otherwise, the body expression supplies the message. The message is converted to text.

---

## 8. Functions, Constants, and Static Patterns

### 8.1 Constants (normative)

`constants` is a plain name container. A constant value is compiled as static literal content. `$const` references a named constant.

A `$const` reference to an unknown constant MUST fail compilation.

Constants are immutable during execution.

### 8.2 Functions (normative)

`functions` is a plain name container. A function name maps to a function definition.

Function names are static. `$call.function` MUST resolve to a known function at compile time. Dynamic function dispatch is not part of BEX 2.0.

### 8.3 Function arguments (normative)

Function `args` is a plain name container whose values are static Blue type or shape patterns.

A call MUST supply exactly the declared argument names:

- missing declared argument: compile error;
- extra argument: compile error;
- unknown function: compile error;
- invalid reserved argument name: compile error.

At runtime, after each argument expression is evaluated, the value MUST match the declared Blue pattern if the pattern is non-empty. Mismatch is a runtime error.

Function argument pattern matching uses the same Blue type matcher boundary as `$is` (§6.4). If argument expression evaluation itself fails, that failure propagates. If an argument evaluates successfully but conversion or type matching returns non-match, argument validation fails as a runtime error. Host, provider, and type-resolution failures follow Blue Language 1.0 matcher semantics.

### 8.4 Static `$is.pattern` (normative)

`$is.pattern` is a static Blue node. It MUST NOT contain BEX expressions.

`$is.node` is an expression and MAY contain BEX.

### 8.5 Empty patterns (normative)

An empty or null Blue pattern matches any non-`undefined` value. `undefined` does not match a pattern.

### 8.6 Pattern labels (normative)

Blue `name` and `description` semantics are inherited from the Blue type matcher. They are matcher-neutral when Blue Language type matching treats them as matcher-neutral.

---

## 9. Pointers

### 9.1 JSON Pointer syntax (normative)

BEX pointer strings use JSON Pointer syntax with `/`-separated segments. Implementations MUST support `~0` for `~` and `~1` for `/` in pointer segments.

A pointer that does not begin with `/` may be interpreted relative to a scope depending on pointer kind.

### 9.2 Pointer kinds (normative)

BEX distinguishes document pointers from value-local pointers.

| Pointer kind | Used by | Scope |
|---|---|---|
| Document pointer | `$document`, `$resultValue`, `$appendChange.path`, `$appendChanges` entry path | Relative to current document scope unless absolute. |
| Value-local pointer | `$event`, `$currentContract`, `$steps.path`, `$binding.path`, `$pointerGet.path`, `$pointerSet.path` | Relative to the root of the operand value. |

### 9.3 Static pointer defaults (normative)

A missing or empty static pointer MAY intentionally mean the root/default path.

- Static document path omitted or empty: current document scope.
- Static value path omitted or empty: `/`.

### 9.4 Dynamic pointer failures (normative)

A dynamic pointer expression that evaluates to `undefined` or `null` MUST fail. It MUST NOT be silently interpreted as `/`.

Dynamic pointer text is canonicalized as JSON Pointer text. Relative dynamic document pointers are resolved against document scope. Relative dynamic value pointers are made value-local by prefixing `/`.

### 9.5 `$pointerJoin` (normative)

`$pointerJoin` builds an absolute JSON Pointer from unescaped segment values.

```yaml
$pointerJoin: [rooms, "12/34", "a~b"]
# => /rooms/12~134/a~0b
```

No segments returns `/`.

---

## 10. Changesets, Events, and Result Overlay

### 10.1 Changeset entries (normative)

A changeset is an ordered list of patch entries.

A patch entry has:

| Field | Meaning |
|---|---|
| `op` | `add`, `replace`, or `remove`. |
| `path` | Absolute document pointer after scope resolution. |
| `val` | Patch value for `add` and `replace`; absent for `remove`. |

Patch entries are accumulated. BEX does not itself persist or apply them to the host document.

### 10.2 Patch order and duplicates (normative)

Patch entries MUST remain in append order. Duplicate paths MUST be preserved. Implementations MUST NOT coalesce, reorder, or discard patches in the accumulator.

### 10.3 Events (normative)

Events are ordered BEX values. Events need not be Blue objects unless the host imposes such a requirement. BEX MUST preserve event append order.

### 10.4 `$resultValue` overlay (normative)

`$resultValue` reads from a transient overlay formed by applying accumulated patches, in order, to the canonical document view.

The overlay is a BEX execution view. It does not mutate the host document.

Rules:

- A later patch to the same path is visible to later `$resultValue` reads.
- A read of a parent object reflects descendant patches.
- A parent replacement followed by a child replacement is applied in order.
- Removing a child makes that child read as `undefined`.
- Adding to a missing parent creates intermediate object containers for overlay purposes.
- List index replacement is supported.
- List index removal is non-shifting in the overlay model; later indexes retain their positions.
- Root replacement replaces the overlay root.
- Root removal makes the overlay root `undefined`.

### 10.4.1 Sparse list overlay views (normative)

A list-index remove patch in `$resultValue` creates a sparse overlay slot at that index.

- Direct reads of the removed index return `undefined`.
- Later indexes retain their original positions.
- `$size` of such an overlay list returns the overlay list's positional extent, including removed slots.
- A sparse overlay list is a BEX overlay view, not a dense BEX list literal.
- Converting a sparse overlay list to a Blue list MUST fail if any indexed slot in `0..size-1` is `undefined`, because Blue/BEX output lists cannot contain `undefined`.
- Programs that need portable Blue output after a non-shifting removal MUST return specific paths, construct a dense list explicitly, or leave the change in the changeset for the host to apply.

### 10.5 Result value output (normative)

The final `value` returned by a program is independent of the changeset and event accumulators unless the program explicitly returns `$changeset`, `$events`, `$resultValue`, or the default result value.

---

## 11. Blue Output and Identity Boundaries

### 11.1 Purpose (normative)

BEX may manipulate semantic values without immediately creating Blue nodes. A **Blue output boundary** occurs when a value becomes:

- the root return value requested as Blue output;
- a patch value;
- an emitted event;
- an intrinsic input that requires an exact Blue node;
- the operand of `$nodeBlueId` when it is not already exact.

### 11.2 Existing exact values (normative)

An existing exact Blue value crosses the boundary by identity. The host MUST preserve its Node BlueId and MUST NOT recursively reconstruct, serialize, size, hash, or materialize it. The boundary charges only the BEX boundary counter plus any Contracts operation that receives it.

### 11.3 Transient conversion (normative)

A transient value is converted recursively to a valid Blue Language 1.0 node:

- `undefined` or `null` at the boundary root fails;
- an object omits members whose evaluated value is `undefined` or `null`;
- recursive omission of null-valued children does not remove their containing object; for example `{x: {y: null}}` becomes `{x: {}}`;
- a list containing `undefined` fails;
- a list item whose value is `null` becomes the exact Blue positional placeholder `{ $empty: true }`;
- an explicit BEX empty object remains the exact Blue empty object `{}`;
- an explicit BEX empty list remains the exact Blue empty list `[]`;
- Text, Integer, decimal, and Boolean use the deterministic scalar rules;
- object and list members are converted in canonical BEX order;
- the resulting node must satisfy Blue Language syntax, payload-kind, reserved-field, list-control, and schema rules.

BEX charges runtime construction and `blueOutputBoundary`; the host charges Blue semantic identity establishment exactly once under Contracts 1.0. The boundary-generated `$empty: true` placeholder for a list `null` is normalization work, not an authored BEX object member. Implementations MUST produce the same semantic ledger and resulting identity independent of cache state or internal representation. These ledgers MUST NOT both charge the same runtime member production or the same semantic identity step.

### 11.4 `$nodeBlueId` (normative)

`$nodeBlueId` evaluates one operand.

- If the operand is an exact Blue value, it returns that value's Node BlueId as Text without transitive expansion.
- If the operand is transient, it performs the Blue output conversion, establishes its exact Node BlueId, and returns that Text.
- `undefined` and `null` fail because neither is an admissible Blue root value under this boundary profile.
- The operation never exposes whether an exact value was originally inline or a pure reference.

### 11.5 Pure references and payload kinds (normative)

A Blue object exactly `{blueId: X}` is a pure reference. A converted output containing `blueId` with any sibling field fails.

A node MUST NOT mix scalar `value`, list `items`, and ordinary object payload fields. `properties` is not a Blue wrapper and remains an ordinary reserved-invalid key.

### 11.6 Schema and reserved fields (normative)

Converted output may use only Blue Language 1.0 schema keywords. `constraints`, `allowMultiple`, `options`, or any unsupported schema key fails. Computed language fields such as `type`, `itemType`, `keyType`, `valueType`, `schema`, `mergePolicy`, and `contracts` retain their Blue language meaning and are validated accordingly.

### 11.6a Empty objects, `null`, omission, and list position (normative)

For null and empty-aggregate semantics, the BEX-to-Blue boundary MUST produce the same preprocessed Blue value as the equivalent Blue Source structure. It applies this mandatory context-sensitive normalization directly; it does not execute a caller-supplied `blue` directive, imports, transformations, or Source-only list overlays.

The required mapping is:

| BEX value and structural position | Exact Blue boundary result |
|---|---|
| root `undefined` | fail |
| root `null` | fail |
| root `{}` | present exact `{}` |
| root `[]` | present exact `[]` |
| object member `undefined` | member omitted |
| object member `null` | member omitted |
| object member `{}` | present exact `{}` member |
| object member `[]` | present exact `[]` member |
| list item `undefined` | fail |
| list item `null` | exact `{ $empty: true }` placeholder |
| list item `{}` | ordinary exact `{}` item |
| list item `[]` | ordinary exact `[]` item |

Null removal is recursive inside objects but does not cascade into removal of the containing object:

```text
BEX {x: {y: null}} -> Blue {x: {}}
```

The same rule applies to reserved Blue fields. For example:

```text
BEX {type: null} -> Blue {}
BEX {type: {}}   -> Blue {type: {}}
```

The first form omits the `type` member before normal Blue validation. The second preserves an explicit empty inline type and is validated under Blue Language 1.0. An implementation MUST NOT first convert `null` to `{}` and thereby turn `{type: null}` into a present empty type.

The boundary MUST NOT convert an explicit empty object into `$empty: true`, remove it, or treat it as missing. Conversely, it MUST NOT convert a list `null` into an empty object. `$empty: true` remains the exact positional placeholder required by Blue Language for Source-equivalent list null.

### 11.7 Preprocessing and list controls (normative)

Computed output containing `blue` fails. BEX output is runtime-produced Blue content, not a retained authored Source Document. Nevertheless, §11.6a applies the same mandatory null-removal and list-placeholder normalization that the equivalent Source structure would receive. No caller-supplied preprocessing directive, import substitution, or transformation executes at this boundary.

Computed output MUST NOT contain `$previous`, `$pos`, or `$replace` as Blue list controls. `$empty: true` is allowed only in its exact Blue Language placeholder shape, including when the boundary creates it from a BEX list `null`.

### 11.8 Failure atomicity (normative)

Output-boundary failure produces no patch, event, returned Blue node, or identity result from that boundary. The child ledger up to the failing charge remains available to the host, while Contracts 1.0 determines invocation rollback.

## 12. Canonical Gas Accounting

### 12.1 Governing rule (normative)

BEX meters logical computation, not representation size.

```text
existing exact node carried:
  no recursive size charge

content inspected, compared, constructed, iterated, sorted, or converted:
  charge the exact canonical work below
```

There is no recursive `estimatedSize`, serialized-payload charge, UTF-16-length charge, cache-dependent discount, or opaque implementation-selected `gasConsumed` in portable BEX 2.0.

### 12.2 Shared child ledger (normative)

When hosted by Contracts 1.0, BEX receives the exact remaining process budget. Before every unit of work it MUST admit the corresponding named counter charge. If the next charge does not fit, execution stops before that work and returns the canonical admitted trace prefix.

The BEX child ledger is merged into the Contracts ledger exactly once in original order. A BEX-local gas limit may only reduce the remaining budget. It cannot replenish gas. A portable runtime MUST return named counters and MUST NOT both debit the parent meter and return the same debit as an opaque integer.

### 12.3 Counter weights (normative)

| Counter | Weight |
|---|---:|
| `expressionEvaluated` | 1 |
| `statementExecuted` | 1 |
| `functionCalled` | 2 |
| `intrinsicCalled` | 5 |
| `documentRead` | 2 |
| `eventRead` | 1 |
| `processingEventRead` | 1 |
| `currentContractRead` | 1 |
| `stepsRead` | 1 |
| `bindingRead` | 1 |
| `variableRead` | 1 |
| `constantRead` | 1 |
| `resultValueRead` | 2 |
| `pointerSegmentRead` | 1 |
| `pointerSegmentWritten` | 1 |
| `objectMemberRead` | 1 |
| `listItemRead` | 1 |
| `collectionItemVisited` | 1 |
| `collectionItemProduced` | 1 |
| `textBlockExamined` | 1 |
| `textBlockConstructed` | 1 |
| `integerLimbOperation` | 1 |
| `comparisonNodeVisited` | 1 |
| `sortComparison` | 1 |
| `patchAppended` | 5 |
| `eventAppended` | 5 |
| `transientObjectMemberProduced` | 1 |
| `transientListItemProduced` | 1 |
| `blueOutputBoundary` | 5 |
| `nodeIdentityRequested` | 5 |

The manifest and fixture package bind the current provisional weights. Final BEX 2.0 publication freezes the calibrated values and regenerates every dependent identity.

### 12.4 Universal evaluation charges (normative)

Every executed expression charges `expressionEvaluated` once. Every executed statement charges `statementExecuted` once. A root program and each user function invocation charge `functionCalled` once.

A statically compiled constant literal is not reconstructed on each read. Reading it charges the expression and `constantRead`; dynamic aggregates produced by expression evaluation pay production counters.

Skipped lazy operands, unselected branches, and post-short-circuit collection items charge nothing.

### 12.5 Read and pointer charges (normative)

A context read charges its named read counter. Traversing a value-local or document pointer charges `pointerSegmentRead` per examined segment. The direct member or list position examined additionally charges `objectMemberRead` or `listItemRead`.

The same logical access MUST NOT be charged both as a BEX runtime read and again as a Contracts semantic read. When BEX requests the access, the BEX child ledger owns the access counters; Blue identity admission and validation remain Contracts semantic work.

`$pointerSet` charges `pointerSegmentWritten` per traversed or created segment and transient member/item production for newly created transient structure. It never recursively sizes the assigned value.

### 12.6 Text work (normative)

One text block contains up to 64 Unicode code points.

A full scan of Text `t` charges:

```text
textBlockExamined += ceil(codePointLength(t) / 64)
```

Newly constructed Text charges `textBlockConstructed` by the same formula. Concatenation, join, split, prefix tests, slicing, pointer escaping, Text conversion, and Text comparison charge only blocks actually examined or constructed according to their specified algorithm.

For lexicographic comparison, each operand charges blocks containing code points read through the first difference or the end of the shorter operand.

### 12.7 Integer and decimal work (normative)

Integers use canonical unsigned base-`2^32` magnitude and separate sign. Let `L(x)` be at least 1 and otherwise the limb count.

| Operation | `integerLimbOperation` quantity |
|---|---:|
| equality or ordering | `L(a) + L(b)` |
| addition/subtraction | `max(L(a), L(b)) + 1` |
| multiplication | `L(a) * L(b)` |
| division/remainder | `L(a) * L(b)` |

Exact decimal operations use the same formulas over the unscaled Integer magnitudes plus one operation for scale alignment. This is a portable work formula, not a required host algorithm.

### 12.8 Objects, lists, and collection queries (normative)

- Reading a direct object member or list item charges its read counter.
- Enumerating an object charges `collectionItemVisited` per key in canonical key order, in addition to the direct read needed for a value when that value is read.
- Enumerating a list charges `collectionItemVisited` per visited position.
- Dynamic object construction charges `transientObjectMemberProduced` per retained field.
- Dynamic list construction charges `transientListItemProduced` per item.
- `$map`, `$filter`, `$flatMap`, `$reduce`, `$some`, `$find`, `$findEntry`, `$includes`, and `$forEach` charge one `collectionItemVisited` per evaluated input item. They do not add a second generic visit charge for the same iteration.
- Output-producing collection operators charge `collectionItemProduced` for each produced result item in addition to the dynamic aggregate production counter when a new container is built.
- Short-circuit operators stop all later item charges.

`$keys` and `$entries` visit each semantic key once. Physical reference wrappers do not add keys or visits.

### 12.9 Equality, matching, and type work (normative)

Semantic equality and deep matching charge `comparisonNodeVisited` once per compared semantic node occurrence.

- Known exact Node BlueIds may conclude exact-node identity after one visited comparison node.
- Text and numeric comparisons add §12.6 or §12.7 work.
- Objects visit keys in canonical order and stop at the first difference.
- Lists visit positions in order and stop at the first difference.
- `$is` additionally incurs the host's Contracts/Language type and validation counters; BEX MUST NOT duplicate them.

### 12.10 Canonical sorting (normative)

Whenever BEX sorts, the canonical trace is calculated as stable bottom-up merge sort:

1. runs begin at width 1;
2. adjacent runs merge left-to-right;
3. width doubles after each pass;
4. equal elements select the left element;
5. every comparator call charges `sortComparison`, `comparisonNodeVisited`, and scalar content work.

A physical implementation may use another algorithm but MUST report this canonical trace.

### 12.11 Changes, events, and updates (normative)

`$appendChange` charges `patchAppended` once after validating the operation and evaluating required operands. `remove` does not evaluate `val`. `$appendChanges` applies the same rule per entry.

`$appendEvent` charges `eventAppended` once after evaluating a non-`undefined` event. `$appendEvents` applies it per event.

Existing exact values are appended by identity. Newly constructed values already paid construction and later pay `blueOutputBoundary`; no recursive boundary-size term exists.

`$objectSet` and `$pointerSet` charge operation expressions, key/path work, and transient members actually produced. They do not recursively size or rehash the inserted value.

### 12.12 Blue output and identity (normative)

Every transient value crossing a Blue boundary charges `blueOutputBoundary` once. Exact Blue values also charge the boundary counter but no recursive construction.

`$nodeBlueId` charges `nodeIdentityRequested`. For a transient operand it also invokes the Blue output boundary and Contracts semantic identity establishment. The same identity work MUST be merged once, not repeated in BEX.

### 12.13 Intrinsics (normative)

`$intrinsic` charges `intrinsicCalled`, evaluated payload work, and a registered named child ledger from the exact intrinsic type. An intrinsic has no authority to return an arbitrary portable gas integer or to hide unnamed work.

### 12.14 Exhaustion and failure (normative)

Charges are admitted before work. The charge that would exceed the child budget is absent. Buffered BEX effects are discarded on runtime error or exhaustion. The admitted child trace is returned to Contracts, which applies its whole-invocation rollback rules.

Transient provider unavailability suspends outside completed execution and commits no BEX child ledger. Deterministic invalid evidence retains the admitted trace and fails.

### 12.15 Conformance trace (normative)

The fixture harness records ordered entries:

```text
BexGasCharge {
  sequence
  counter
  quantity
  weight
  gas
  sourcePath?
  operator?
  reason
}
```

`sequence` begins at zero. The sum of `quantity * weight` is the BEX child total. Every counter and operator family has a machine-readable microfixture.

## 13. Errors and Diagnostics

### 13.1 Error classes (normative)

BEX distinguishes at least these error classes for fixtures and host APIs:

| Class | Meaning |
|---|---|
| compile error | Source is syntactically or statically invalid as BEX. |
| runtime error | Execution fails after successful compilation. |
| parse error | Source text cannot be parsed as Blue/YAML/JSON input. |
| output-conversion error | A BEX result cannot be converted to a Blue node. |
| gas exhaustion | Runtime failure caused by gas limit. |

A fixture implementation MUST classify errors deterministically.

### 13.1.1 Diagnostic fields (normative)

Fixture and host diagnostics MUST expose at least:

- error class;
- message;
- source path when known;
- operator name when known;
- function name or call-frame path when known;
- pointer or path operand when the failure is pointer-related and safely reportable.

Exact message text is not normative unless a fixture asserts `errorContains`.

### 13.2 Runtime failures (normative)

Runtime failure MUST stop execution. Accumulated changes and events are not committed by BEX. The host decides whether failed partial accumulators are observable for diagnostics.

Runtime failures include:

- invalid dynamic pointer, key, op, binding name, or step name;
- invalid type conversion;
- non-list input to list-only operators;
- non-object input to object-only operators where conversion is not defined;
- non-exact integer arithmetic;
- division by zero;
- function argument mismatch;
- invalid patch or event append;
- `$fail`;
- `$failIf` when its condition is truthy;
- invalid collection input or invalid collection result shape;
- invalid `$objectFromEntries` entries or keys;
- unsupported or failing intrinsic processor invocation.

### 13.3 Metrics (normative)

An execution implementation SHOULD expose deterministic metrics. The Java-derived metric categories include:

- expression evaluations;
- statement executions;
- function calls;
- document reads;
- result-value reads;
- patch appends;
- event appends.

Metrics are diagnostic. The canonical gas ledger and conformance-fixture expectations are normative; additional host metrics are not.

---

## 14. Determinism and Security

### 14.1 Determinism (normative)

For a fixed:

- BEX source;
- optional definition node;
- execution context;
- immutable document views;
- bindings, event, steps, and current contract values;
- gas schedule and gas limit;
- Blue Language 1.0 type matcher;
- registered intrinsic processor set and deterministic intrinsic processor behavior;

BEX execution MUST be deterministic.

BEX execution is synchronous from the program's point of view. Hosts MAY run BEX inside asynchronous runtimes, but BEX exposes no async primitives and no BEX operator may observe scheduling, timing, or interleaving.

### 14.2 No implicit authority (normative)

Except for explicitly registered `$intrinsic` processors, BEX has no implicit authority to:

- mutate host documents;
- apply changesets;
- emit events externally;
- read clocks or randomness;
- access files, networks, databases, or environment variables;
- execute Blue contracts.

All host data visible to BEX MUST be explicitly supplied through the execution context or through an explicitly registered intrinsic processor. A portable intrinsic processor MUST define deterministic semantics, failure behavior, and gas accounting for fixed inputs.

### 14.3 Host validation (normative)

A host MUST validate BEX output under its own authorization and content rules before applying changes or emitting events.

BEX patch accumulation is not an authorization decision.

### 14.4 Resource limits (normative)

Hosts SHOULD enforce gas limits and MAY impose additional deterministic limits on:

- source size;
- compile depth;
- expression nesting;
- statement count;
- list and object sizes;
- output size;
- pointer depth.

If a limit affects execution, failure MUST be deterministic.

---

## 15. Operator Reference Summary

This section is normative unless otherwise marked.

### 15.1 Expression operators

```text
Reading:
  $document, $binding, $event, $processingEvent, $steps, $currentContract,
  $var, $const, $get, $changeset, $events, $resultValue

Type, identity, and conversion:
  $unwrap, $is, $kind, $isKind, $nodeBlueId,
  $text, $integer, $number, $boolean, $object, $list

Strings:
  $concat, $pointerJoin, $join, $split, $startsWith, $sliceAfter

Logic and comparison:
  $eq, $ne, $gt, $gte, $lt, $lte,
  $and, $or, $not, $truthy, $empty, $isEmpty,
  $exists, $coalesce, $default

Numeric:
  $add, $subtract, $multiply, $divide

Objects and lists:
  $keys, $entries, $size, $listGet, $listConcat,
  $merge, $objectSet, $pointerGet, $pointerSet,
  $map, $filter, $flatMap, $reduce,
  $some, $find, $findEntry,
  $includes, $hasKey, $objectFromEntries

Control and host boundary:
  $choose, $call, $intrinsic, $literal,
  $null, $emptyObject, $emptyList
```

### 15.2 Statement operators

```text
$let, $set, $if, $forEach,
$appendChange, $appendChanges,
$appendEvent, $appendEvents,
$call, $return, $returnIf, $fail, $failIf
```

### 15.3 Operand naming guidance (informative)

Operator bodies SHOULD use BEX operand names rather than Blue wrapper names where possible. For example:

```yaml
# Preferred
$join:
  list: [a, b]
  separator: ","

# Avoid using Blue payload keys as BEX operand names
# unless the operator explicitly defines them.
```

BEX operator operands commonly use:

```text
node, list, input, pattern, object, key, path, val,
cond, then, else, name, expr, where, in, item, index,
acc, init, order, vars, op, args, function, message
```

### 15.4 Future standard library candidates (informative)

The following names are candidates for later BEX revisions and are not normative BEX 2.0 operators:

```text
$length, $slice, $findIndex, $findKey, $every,
$pick, $omit, $entriesMap,
$range, $min, $max, $sort, $match, $break, $continue
```

In BEX 2.0, `$size` is a collection/cardinality helper. For scalar text it returns `1`, not text length. A dedicated text code-point length operator is deferred.

---

## 16. Machine-Readable Conformance Fixtures

### 16.1 Fixture root (normative)

A BEX fixture is a YAML object with:

```yaml
id: BEX-...
category: compiler | execution | statement | boundary | gas | representation
program: ...
context: ...
expected: ...
```

`expected` may contain result value, changes, events, error class, exact ordered gas trace, total gas, semantic demands, and equivalent representation variants.

### 16.2 Required context fields (normative)

The harness can provide exact Blue values for:

```text
rootDocument
event
processingEvent
currentContract
steps
bindings
documentScope
registered intrinsics
```

Exact values may be supplied inline or by pure reference with provider content. Equivalent variants MUST produce the same result and canonical counter trace.

### 16.3 Required fixture assertions (normative)

The fixture package MUST include:

- all compiler and execution vectors in §17;
- an exact microfixture for every gas counter;
- paired inline/reference and eager/lazy variants for every representation-sensitive operator family;
- `$processingEvent` under external and internal delivery;
- `$nodeBlueId` for exact and transient values;
- no-observable-reference-wrapper cases;
- constructed-versus-carried large values;
- short-circuit and exhaustion trace prefixes;
- output-boundary validation;
- runtime-ledger merge exactly once.

### 16.4 Manifest and package identity (normative)

The release publishes a manifest containing:

```text
specificationVersion
fixture schema version
fixture files and SHA-256 digests
vector coverage
counter coverage
gas schedule identity
fixture package SHA-256
```

A conforming BEX 2.0 implementation MUST report the exact runtime-registry, gas-manifest, and fixture-package identities it implements and passes.

This proposal intentionally claims no final fixture inventory or package identity.
After implementation, the release process MUST regenerate the vector coverage,
behavior-fixture count, gas-microfixture count, runtime-registry identity, fixture
package identity, and all dependent receipts. The normative operator set and gas
schedule may remain unchanged only when the generated audit proves that no
operator or counter semantics outside this explicit output-boundary change moved.

## 17. Conformance Vectors

Every vector is behavior-defining and has at least one machine-readable fixture.

### 17.1 Compiler vectors

- **BEX-C-01.** Exactly one `$` key denotes an expression operator; multiple keys denote an object expression.
- **BEX-C-02.** Unknown expression and statement operators fail compilation.
- **BEX-C-03.** `$literal` preserves nested unknown operators but cannot place expressions in static Blue fields.
- **BEX-C-04.** Unknown constants/functions, missing/extra arguments, recursive calls, and entry functions with arguments fail.
- **BEX-C-05.** Reserved names in plain name containers fail.
- **BEX-C-06.** Dynamic `$is.pattern` and `$intrinsic.type` fail.
- **BEX-C-07.** `$set` of an undeclared local and invalid `$let.order` fail.
- **BEX-C-08.** Compiler diagnostics include class, source path, and operator when known.
- **BEX-C-09.** Direct or mutual function recursion is rejected from the complete static call graph before execution.

### 17.2 Semantic execution vectors

- **BEX-E-01.** `$document` uses document-scope-relative and absolute pointers correctly.
- **BEX-E-02.** `$event`, `$processingEvent`, `$currentContract`, `$steps`, `$binding`, `$var`, and `$const` use value-local pointers.
- **BEX-E-03.** Dynamic null/undefined pointers and dynamic null/undefined key/name operands fail.
- **BEX-E-04.** `$pointerJoin` escapes `~` and `/`.
- **BEX-E-05.** `$exists` is false only for semantic `undefined`; incomplete access is not absence.
- **BEX-E-06.** Numeric zero is truthy; empty object/list and null are falsy.
- **BEX-E-06a.** BEX `undefined`, `null`, and `{}` remain distinct during execution. At the Blue boundary, `undefined` and `null` object members are omitted, while an explicit `{}` member remains present.
- **BEX-E-06b.** Root `undefined`, root `null`, a patch value `null`, an emitted-event root `null`, an exact-node intrinsic input `null`, and `$nodeBlueId(null)` fail Blue output admission; an explicit `{}` succeeds and has the exact empty-object BlueId.
- **BEX-E-06c.** A BEX list containing `null`, `{}`, and `[]` outputs three positions: `{ $empty: true }`, `{}`, and `[]`; all three positions and their distinct identities are preserved.
- **BEX-E-06d.** `{x: {y: null}}` crosses the boundary as `{x: {}}`; recursive null removal does not erase the explicitly constructed container.
- **BEX-E-06e.** `{type: null}` crosses the boundary as `{}`, while `{type: {}}` retains the explicit empty type and undergoes ordinary Language validation.
- **BEX-E-06f.** An Operation Request constructed with `request: null` has no `request` member after Blue output admission; it is distinct from one constructed with `request: {}`.
- **BEX-E-07.** `$and`, `$or`, `$coalesce`, `$choose`, `$if`, and collection search short-circuit.
- **BEX-E-08.** Numeric conversions, exact division, Text rendering, and finite Double conversion are deterministic.
- **BEX-E-09.** Object keys are exposed in Unicode code-point order independent of host maps and locale.
- **BEX-E-10.** `$kind` and `$isKind` report semantic kinds, never reference/cursor classes.
- **BEX-E-11.** Collection queries iterate in canonical order, restore bindings, and preserve short-circuiting.
- **BEX-E-12.** `$objectSet`/`$pointerSet` use value-local semantics and create only permitted transient intermediates.
- **BEX-E-13.** Function frames isolate locals and validate static argument patterns.
- **BEX-E-14.** BEX numeric equality remains distinct from Blue identity.

### 17.3 Statements and accumulators

- **BEX-S-01.** `$if` executes only one branch; `$forEach` binds item/key/index deterministically.
- **BEX-S-02.** `$appendChange(s)` validates operations, requires `val` for add/replace, and does not evaluate remove `val`.
- **BEX-S-03.** Duplicate patch paths are preserved in append order.
- **BEX-S-04.** `$appendEvent(s)` preserves order and multiplicity and rejects `undefined`.
- **BEX-S-05.** `$resultValue` applies accumulated patches in order, including parent/child replacement and sparse non-shifting list removal.
- **BEX-S-06.** `$return`, `$returnIf`, `$fail`, and `$failIf` have deterministic lazy exit behavior.
- **BEX-S-07.** Parallel and ordered `$let` semantics are distinct and deterministic.

### 17.4 Representation and identity vectors

- **BEX-R-01.** Inline, pure-reference, eagerly materialized, and lazily materialized forms of the same exact value produce the same result and gas.
- **BEX-R-02.** `$kind`, `$exists`, `$keys`, `$entries`, `$size`, truthiness, equality, matching, iteration, and pointer reads do not reveal reference state.
- **BEX-R-03.** A pure reference wrapper does not create a semantic child named `blueId`.
- **BEX-R-04.** `$nodeBlueId` returns exact identity without transitive expansion.
- **BEX-R-05.** `$nodeBlueId` on a transient value establishes one exact identity and merges semantic identity work once.
- **BEX-R-06.** Passing an existing large exact node through patches, events, constants, functions, and output has no recursive size charge.
- **BEX-R-07.** Constructing or scanning a large value pays member/Text/numeric work.
- **BEX-R-08.** Warm/cold cache, provider batching, and physical segmentation do not change result or gas.
- **BEX-R-09.** A final cyclic-set member is an exact opaque value: `$nodeBlueId` returns `MASTER#index` without provider demand or independent member hashing; structural reads require cyclic-set proof.

### 17.5 Host-boundary vectors

- **BEX-H-01.** Root `undefined`, list `undefined`, mixed `blueId`, mixed payload kinds, `properties`, invalid schema keys, computed `blue`, and unsupported list controls fail conversion.
- **BEX-H-02.** Existing exact nodes cross output by identity; transient aggregates convert deterministically.
- **BEX-H-03.** Computed Blue language fields retain their reserved meaning.
- **BEX-H-04.** Sparse overlay lists cannot cross as ordinary Blue lists.
- **BEX-H-05.** Blue Double input is source-token independent and output rounding is deterministic.
- **BEX-H-06.** `$processingEvent` remains the original external event in Document Update, Triggered, Lifecycle, and Embedded deliveries.

### 17.6 Gas vectors

- **BEX-G-01.** Every named counter has an exact microfixture and a weight bound by the current gas-manifest identity; final publication freezes the calibrated weight.
- **BEX-G-02.** Charges are admitted before work; the failing charge is absent on exhaustion.
- **BEX-G-03.** Skipped operands and post-short-circuit items produce no charges.
- **BEX-G-04.** Pointer reads/writes charge exact segment and member/item work.
- **BEX-G-05.** Text uses 64-code-point blocks, never UTF-16 units.
- **BEX-G-06.** Integer operations use the portable limb formulas.
- **BEX-G-07.** Collection visits and produced items are charged exactly once per semantic occurrence.
- **BEX-G-08.** Equality/matching charges comparison nodes and examined scalar work.
- **BEX-G-09.** Sorting reports canonical stable merge-sort comparisons.
- **BEX-G-10.** `$pointerSet`, `$objectSet`, `$appendChange(s)`, and `$appendEvent(s)` contain no recursive `estimatedSize` term.
- **BEX-G-11.** Exact values and transient values differ only by actual construction/identity-boundary work, not by serialization shape.
- **BEX-G-12.** The BEX child ledger is live-bounded and merged into Contracts exactly once.
- **BEX-G-13.** A BEX-local gas limit can reduce but never replenish the parent budget.
- **BEX-G-14.** Intrinsics return named deterministic child counters rather than opaque gas.
- **BEX-G-15.** A large bounded iteration is stopped by live gas admission at an exact trace prefix; buffered changes and events are discarded.

## 18. Worked Examples

### 18.1 Simple expression

```yaml
expr:
  $add:
    - 2
    - 3
```

The program returns integer `5`.

### 18.2 Statement program with changes and events

```yaml
do:
  - $appendChange:
      op: replace
      path: /status
      val: CONFIRMED
  - $appendEvent:
      type: StatusChanged
      status: CONFIRMED
  - $return:
      changeset:
        $changeset: true
      events:
        $events: true
```

The program accumulates one patch and one event, then returns them as data.

### 18.3 Constants

```yaml
constants:
  threshold: 400
expr:
  $gte:
    - $document: /amount
    - $const: threshold
```

A missing `threshold` constant would be a compile-time error.

### 18.4 Function with static argument pattern

```yaml
functions:
  isLarge:
    args:
      amount:
        type: Integer
    expr:
      $gte:
        - $var: amount
        - 400
expr:
  $call:
    function: isLarge
    args:
      amount:
        $document: /amount
```

The argument is evaluated and then matched against the static Blue pattern before the function body runs.

### 18.5 Reading host bindings

```yaml
expr:
  $binding: actor/id
```

This reads binding `actor` at value-local path `/id`.

Equivalent object form:

```yaml
expr:
  $binding:
    name: actor
    path: /id
```

### 18.6 Dynamic pointer construction

```yaml
expr:
  $document:
    $pointerJoin:
      - reservations
      - $event: /reservationId
      - status
```

If `reservationId` contains `/` or `~`, `$pointerJoin` escapes it correctly.

### 18.7 `$resultValue`

```yaml
do:
  - $appendChange:
      op: replace
      path: /status
      val: CONFIRMED
  - $return:
      statusAfterPatch:
        $resultValue: /status
```

The returned `statusAfterPatch` is `CONFIRMED`, even though the host document has not been mutated by BEX execution.

### 18.8 `$resultValue` non-shifting list removal

```yaml
context:
  rootDocumentSource:
    items: [A, B, C, D]
programSource:
  do:
    - $appendChange:
        op: remove
        path: /items/1
    - $return:
        removedExists:
          $exists:
            $resultValue: /items/1
        stillAt2:
          $resultValue: /items/2
        positionalSize:
          $size:
            $resultValue: /items
```

`removedExists` is `false`. `/items/2` still reads `C`. `positionalSize` is `4`. The overlay does not shift later indexes. Returning the whole sparse overlay list directly would fail Blue output conversion under the host boundary.

### 18.9 `$resultValue` parent and child replacement

```yaml
context:
  rootDocumentSource:
    order:
      status: DRAFT
      total: 10
programSource:
  do:
    - $appendChange:
        op: replace
        path: /order
        val:
          status: CONFIRMED
    - $appendChange:
        op: replace
        path: /order/total
        val: 20
    - $return:
        orderAfter:
          $resultValue: /order
```

The returned `orderAfter` is:

```yaml
status: CONFIRMED
total: 20
```

Patches are applied in append order, so the child replacement is applied after the parent replacement.

### 18.10 Guard-style return

```yaml
do:
  - $returnIf:
      cond:
        $not:
          $isKind:
            val:
              $event: /message/request/summary
            kind: text
      expr:
        changeset: []
        events:
          - type: Conversation/Proposed Change Invalid
            reason: summary is missing
  - $return:
      accepted: true
```

If the summary is missing or not text, the root function returns the invalid result and the later `$return` is not executed.

### 18.11 Collection projection

```yaml
expr:
  $map:
    in:
      $event: /message/request/changeset
    item: patch
    expr:
      op:
        $var:
          name: patch
          path: /op
      path:
        $var:
          name: patch
          path: /path
```

The expression projects each requested patch into a normalized patch-like object without mutable accumulator boilerplate.

### 18.12 Intrinsic signature verification

```yaml
expr:
  $intrinsic:
    type:
      blueId: CommonCryptoEd25519Verify
    publicKey:
      $const: trustedSignerPublicKey
    message:
      $event: /message/canonicalBytes
    signature:
      $event: /message/signature
```

The host must have a processor registered for the BlueId of `CommonCryptoEd25519Verify`. The intrinsic definition must specify the exact message bytes being verified.

### 18.13 Literal escape

```yaml
expr:
  $literal:
    $call:
      function: notExecuted
      args: {}
```

The result is an object containing the `$call` key as data. No function is invoked.

---

## Appendix A — BEX Value and Blue Node Boundary

This appendix is informative.

BEX values are optimized for execution, not identity. Blue nodes are optimized for content semantics and BlueId. A host should not assume that every BEX object is already a valid Blue node. The conversion boundary is deliberately strict so that invalid Blue output fails before it is stored or hashed.

Examples of invalid output:

```yaml
# Invalid: mixed pure reference and content
blueId: X
name: Something
```

```yaml
# Invalid: Blue has no properties wrapper
properties:
  a: 1
```

```yaml
# Invalid: mixed payload kinds
value: 1
items: [2]
```

---

## Appendix B — Gas Calculation Examples

This appendix is informative. Exact expected traces are machine-readable fixtures.

A trivial root expression:

```yaml
expr: 1
```

charges:

```text
functionCalled       1 × 2
expressionEvaluated  1 × 1
--------------------------------
total                        3
```

A short-circuit expression:

```yaml
expr:
  $and:
    - false
    - $fail: should not run
```

charges the root function, `$and`, and the first operand. The skipped `$fail` expression and message produce no charges.

Text construction uses 64-code-point blocks:

```text
""                 -> 0 text blocks
"hello"            -> 1 text block
64 code points     -> 1 text block
65 code points     -> 2 text blocks
"😀"               -> 1 code-point block
```

Passing an exact node containing a large Text value does not charge those blocks. Scanning or constructing that Text does.

Appending an existing exact event charges the evaluated expression, `eventAppended`, and the later Blue output boundary. It does not recursively size the event. Constructing a new object event additionally charges one transient-object-member counter per produced field and Text/numeric work actually performed.

## Appendix C — Common Implementer Mistakes

This appendix is informative.

### C.0 Do not use BEX `null` to mean a present empty Blue object

At the Blue boundary `{}` is present exact content. A BEX object member whose value is `null` is omitted, while a BEX list item whose value is `null` becomes `{ $empty: true }`. Root `null` is invalid. Use an explicit `{}`, `$emptyObject`, or `$object` when a present empty Blue object is intended.

### C.1 Do not treat every `$` key as an operator

Only an expression object with exactly one `$` key is an expression operator. Multi-field dollar objects are data.

### C.2 Do not allow executable BEX in Blue type fields

`type`, `itemType`, `keyType`, `valueType`, `blue`, and `schema` are static Blue definition positions.

### C.3 Do not use Blue reserved keys as user argument names

Function argument names and call argument names live in plain name containers. Reserved Blue keys such as `value`, `items`, `type`, and `schema` are invalid there.

### C.4 Do not apply changesets automatically

BEX accumulates patches. The host applies or rejects them.

### C.5 Do not delete or reorder patches

Patch order and duplicate patch paths are meaningful.

### C.6 Do not interpret dynamic null pointers as root

Omitted static paths can mean root/default. Dynamic `null` or `undefined` paths are errors.

### C.7 Do not forget root function gas

Every execution invokes the root function and charges `functionCalled`.

### C.8 Do not expose mutable host nodes

Host nodes visible to BEX must be immutable for the duration of execution or snapshotted.

### C.9 Do not treat BEX numeric equality as Blue identity

BEX `1` and `1.0` compare equal as execution numbers. Blue scalar identity still distinguishes Blue `Integer` from Blue `Double`.

### C.10 Do not expose object keys in host map iteration order

BEX object key exposure order is lexicographic by Unicode code point, independent of host map insertion or iteration order.

### C.11 Do not swallow runtime failures inside `$is.node`

If evaluating `$is.node` fails, that runtime failure propagates. Only successful non-conversion or non-match returns `false`.

### C.12 Do not treat `$size` as text length

`$size` is a collection/cardinality helper. For scalar text it returns `1`.

### C.13 Do not implement `$binding` with host-defined gas under the fixture harness

A `$binding` read charges `expressionEvaluated`, `bindingRead`, and demanded pointer/member work.

### C.14 Do not rely on host operand evaluation order

BEX defines operand evaluation order. Host map order and source parser map order are not execution semantics.

### C.15 Do not assume `$split` uses regex semantics or drops trailing empty parts

`$split` uses literal separators. Omitted or `-1` limit preserves trailing empty parts.

### C.16 Do not materialize sparse `$resultValue` list overlays as dense Blue lists

Non-shifting list removals create sparse overlay slots. Converting such an overlay list to Blue output fails while a removed slot is `undefined`.

### C.17 Do not emit `blue` under the strict host boundary

Computed `blue` is invalid portable BEX output.

### C.18 Do not use UTF-16 or recursive size gas

BEX 2.0 meters Text in Unicode code-point blocks and meters construction/inspection work. It never recursively estimates value size.

### C.19 Do not emit Java compatibility schema keys

BEX 2.0 output uses only the schema vocabulary of Blue Language 1.0.

### C.20 Do not use collection queries as leaking local scopes

`$map`, `$filter`, `$flatMap`, `$reduce`, `$some`, `$find`, and `$findEntry` temporarily bind item/key/index/acc slots. Implementations MUST restore previous local values after the expression.

### C.21 Do not split `$var` or `$const` names on `/`

Path-aware reads use object form with `name` and `path`. The scalar form is a variable or constant name, even when it contains `/`.

### C.22 Do not implement `$intrinsic` as arbitrary host callback dispatch

`$intrinsic.type` is static Blue data. The operation is allowed only when the active registry has a processor for the resolved BlueId. Unsupported BlueIds fail at compile time.

---

*End of Blue BEX Specification 2.0.*
