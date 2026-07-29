# Blue Language 1.0 fixture harness

## 1. Purpose

The harness executes the exact Language fixture set bound by `manifest.yaml`. Fixtures are normative executable cases, not examples. A conforming runner MUST implement every operation used by this package, verify provider evidence, preserve exact identity, and fail closed on unsupported fixture data.

## 2. General rules

- YAML is parsed under the Blue Language JSON-data-model restrictions.
- `input`, `source`, `parent`, `pattern`, `candidate`, `documents`, and provider nodes are Blue Source or BlueId-input values according to the named operation.
- Exact expected BlueIds are canonical Base58 encodings of 32-byte SHA-256 digests, except explicit cyclic member identities and fixtures whose purpose is invalid-BlueId rejection.
- `expectError: true` requires failure. `expectedErrorCategory` requires the exact category. Where only failure is asserted, the runner must still reject the input deterministically.
- Equivalent forms must produce the same semantic result or identity without being normalized through implementation-specific shortcuts.
- Unknown fixture fields or operations are runner failures.

## 3. BlueId operations

### `calculateBlueId`

Normalize valid direct BlueId input and calculate one Node BlueId. `alsoEquivalentTo` values must produce the same ID. `alsoDifferentFrom` values must produce different IDs.

### `calculateBlueIdPair`

Calculate both exact inputs independently and compare them using `expectedEqual`.

### `parseBlueIdInput`

Validate direct BlueId input without running Source preprocessing. Invalid numeric tokens, unresolved aliases, mixed reference shapes, list controls, or other prohibited content must fail.

## 4. Preprocessing, resolution, and validation

### `parseSource`

Parse a Source value while preserving the required token distinctions and exact Blue data model.

### `preprocess`

Apply the standard baseline environment and declared `blue.imports`, remove `blue`, normalize wrappers and list placeholders, and compare with `expectedPreprocessed`.

### `resolve`

Preprocess, resolve the effective type chain, merge overlays, validate schemas and fixed values, and compare `expectedResolved`, `expectedValue`, `expectedEffectiveType`, or the expected failure.

### `resolveVariants`

Apply the same parent, declaration, provider, or base inputs to every variant independently and check each variant's expected validity, result, or error.

### `resolveLimited`

Resolve only the demanded paths within the declared limits. Return an explicit established, absent, incomplete, or invalid conclusion; never turn missing evidence into absence. Every established path must equal complete resolution for value, effective type, accumulated constraints, and provenance required by canonicalization.

### `validate` and `validateVariants`

Run the specified schema, type, collection, or dictionary validation without changing identity semantics. Variant order is fixture order.

### `match`

Apply matcher-neutral label behavior and typed semantic matching, then compare `expectedMatch` and any identity assertion.

## 5. Canonicalization and minimization

### `canonicalize`

Resolve the Source value completely and derive the unique Canonical Identity Input. Compare `expectedCanonicalOverlay`, canonical items, control absence, and expected Content BlueId fields where supplied.

### `compareContentAndDirectResolvedBlueId`

Prove that Content BlueId is the Node BlueId of Canonical Identity Input and that directly hashing a noncanonical Resolved View need not yield it.

### `minimizeAndResolve`

Produce a valid author-facing minimized overlay, allow only the fixture-listed optional controls, resolve it again, and prove the expected round trip.

### `canonicalizeLimitedResult`

Reject canonicalization when the supplied limited result is incomplete.

## 6. Expansion, collapse, providers, and direct manifests

Provider entries identify a requested BlueId and one of:

```text
node or returnedNode
outcome: NotFound | Unavailable | InvalidEvidence
```

Every supplied node must verify under the operation's declared provider mode. A provider result cannot make unknown content absent.

### `expand`, `expandLimited`, `expandVariants`, `compareExpansionStrategies`

Expand only demanded references. Honor `limits`, `expectedRequestedBlueIds`, `expectedNotRequestedBlueIds`, expected descendant requests, and representation-equivalence assertions. Physical prefetch must not change semantic coverage.

### `collapse` and `expandThenCollapse`

Collapse only verified exact nodes to pure references, never mixed `blueId` forms, and preserve Node BlueId through the requested round trip.

### `verifyDirectNode` and `verifyDirectList`

Verify a complete direct object manifest or ordered direct list-element identities without demanding transitive child bodies. Direct completeness is required for semantic absence.

### `retrieveDirectList`

A list-prefix optimization may accelerate a fold but does not replace the complete ordered direct element-identity manifest returned to the semantic caller.

### `semanticExists`

Return `Established`, `Absent`, `Incomplete`, or `Invalid` as the fixture requests. Missing direct evidence, limits, and provider unavailability never prove absence.

### `compareGraphEquivalentInputs`

Run every representation against the same semantic demand and compare outcome, value, and exact root identity.

### `compareLimitedAndCompleteResolution`

The limited operation must equal complete resolution on every established path, including value, effective type, and constraints.

## 7. Circular-set operations

### `calculateCircularSetBlueIds`

Execute the complete ZERO_BLUEID, preliminary-ID ordering, `this#i`, MASTER, and final member-ID algorithm. Duplicate preliminary members follow the exact rejection/disambiguation rule.

### `expandCyclicMember`

Reject isolated member verification and succeed only with a verified complete cyclic-set context.

## 8. Registry, suite, path, and lint operations

### `registryNodeHashesToPublishedBlueId`

Load the exact registry file named by `registryKey`, calculate its Node BlueId, and compare the published ID. Do not recreate the node from Java constants.

### `changingRegistryDescriptionChangesBlueId`

Apply the exact identity-bearing mutation and prove the BlueId changes.

### `suiteAssertion`

Evaluate the meta-condition over the complete fixture inventory. It cannot be satisfied by a hard-coded `pass` result.

### `assertViewPath`

Apply RFC 6901 over the abstract Blue node model, including empty-string root and `/` empty-key behavior.

### `lintPublishableDocumentation`

Join every forbidden token sequence exactly as declared, inspect every declared publishable file, and reject any forbidden occurrence or missing required heading.

## 9. Expected fields

Expected fields are exact and operation-specific. Common forms include:

```text
expectedNodeBlueId
expectedPublishedBlueId
expectedBlueIds
expectedPreprocessed
expectedResolved
expectedCanonicalOverlay
expectedValue
expectedValid
expectedOutcome
expectedRequestedBlueIds
expectedNotRequestedBlueIds
expectedErrorCategory
```

Lists preserve order unless the Language rule explicitly defines a set. A fixture runner MUST compare complete expected structures, not selected convenient fields.

## 10. Package integrity

`manifest.yaml` is the authoritative inventory for this fixture package. It lists every behavior fixture and every support file with its relative path, role, LF-normalized byte length, and SHA-256 digest. It also binds the exact Language core-registry package identity and the exact vector-coverage map.

The fixture-package identity is calculated as:

```text
sha256(
  UTF-8 canonical JSON of manifest.yaml
  with packageIdentity set to null
  and object keys sorted lexicographically
)
```

The manifest's `files` list is itself identity-bearing and is sorted by relative path. A fixture or support file that is added, removed, renamed, or changed requires a new manifest and fixture-package identity. The registry manifest binds this fixture package informationally; its own package identity deliberately excludes that reverse binding to avoid an identity cycle.

## 11. Exact graph fragment operations

### `splitExactGraphFragments`

Admit the exact Root, apply every RFC 6901 cut in `cuts`, and produce ordinary Blue fragments. A cut materializes its selected node and replaces complete cut children by pure references to their exact Node BlueIds. The harness MUST:

- calculate and verify every fragment identity;
- preserve canonical direct-child order;
- expose original, direct-fragment, and pure-reference Root representations;
- prove all Root representations have the same exact Root Node BlueId;
- expand the fragment graph back to the original exact Root;
- serve defensive copies from the local exact-node provider;
- return `NotFound` for every identity not admitted by that provider.

This is a conformance utility over ordinary expansion and collapse. It does not define a new node form or partial identity.

### `verifyOpaqueCyclicFragment`

Admit an ordinary exact Root containing one or more finalized cyclic member references of the form `MASTER#index`. The fragmenter MUST preserve each member identity as an opaque edge, MUST NOT hash a member body independently, and MUST return `NotFound` from the ordinary local fragment provider for the member identity. Expansion may succeed only when a composed cyclic-aware provider supplies complete owning-set proof.
