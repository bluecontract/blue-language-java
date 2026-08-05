# Blue Contracts 1.0 fixture harness

## 1. Purpose and authority

The harness executes the normative fixture envelope `blue-contracts-fixture/1.0`.

These files are executable conformance data, not scenario sketches. A conforming runner MUST implement every operation, control, preparation rule, projection, and assertion defined by:

```text
fixture-schema.yaml
CONTROL-LANGUAGE.md
TRACE-SCHEMA.md
projection-catalog.yaml
```

Unknown data fails closed. A fixture runner MUST NOT invent semantics for a field, treat a label as a transformation, or mutate the expected result directly.

## 2. Exact inputs

After deterministic builders are applied, `input.root` and `input.event` are exact Blue Language 1.0 values. Every pure reference uses a canonical plain BlueId. Every provider node is validated as BlueId Input and MUST hash to its map key.

The semantic operation remains:

```text
PROCESS(root, event) -> ProcessResult
```

Feeder evidence, provider evidence, runtime registration, cache state, and fixture controls are execution-environment data. They are not a hidden third semantic input and cannot select behavior inconsistent with the exact Root, event, and registered runtime laws.

## 3. Operations

### 3.1 `process`

Execute one atomic `PROCESS(root, event)`. Its public projection is exactly:

```text
result.status
result.document
result.events
result.totalGas
result.diagnostic?
```

`result.document` is the resulting authoritative Root. `result.events` is an out-of-band ordered sequence of exact events emitted by Root only. It is not itself a Blue List node. Internal descendant events and Document Update payloads appear only in conformance traces.

### 3.2 `process-attempt`

Execute:

```text
PROCESS_ATTEMPT(root, event, verifiedEvidence)
    -> Complete(ProcessResult)
     | NeedsResources(sortedExactBlueIds)
```

A `NeedsResources` result is represented under `attempt.*`. It has no `ProcessResult`, no committed state, no Root events, no progress, and no portable gas. A fixture MUST NOT encode `needs-resources` as `result.status`.

### 3.3 `platform`

Execute the explicitly declared managing-feeder behavior around two-input `PROCESS`: revision barrier, canonical preselection, activation intervals, event ordering, revision-bound terminal progress, compare-and-swap, quarantine, subscription delta, and Root outbox commit.

A platform fixture cannot create an alternative processor result. Whenever semantic processing occurs it invokes the same `PROCESS(root,event)` operation.

### 3.4 `gas-micro`

Evaluate one exact named counter or one formula declared by the bound Contracts gas manifest. No document processing is implied unless Root/event/runtime fields are also supplied. Trace entries and arithmetic are exact.

## 4. Fixture controls

`CONTROL-LANGUAGE.md` defines every builder, provider, runtime, feeder, and variant control. Important constraints are:

- scripted runtime results are returned only by an actually selected runtime contract;
- cascades and cut-off scenarios are installed as ordinary fixture handlers, never host mutations;
- `input.feeder.deliverySnapshot` is compact fixture shorthand and MUST be expanded and verified as the complete normative `ExternalDelivery` snapshot;
- representation variants transform exact preparation only and cannot alter semantic values;
- every variant is an object that explicitly names its transformation; a bare variant label is invalid.

## 5. Canonical delivery derivation

For each delivery hint, the runner MUST derive and retain:

```text
scopePath
channelKey
orderedSourceContributionNodeBlueIds
effectiveTypeBlueId
order
checkpointDomainBlueId
```

It MUST verify:

1. every non-root path is transitively declared through either an exact `Process Embedded.paths` entry or a concrete direct member generated from `Process Embedded.collectionPaths`;
2. the selected scope exists as an object and is not under a direct terminated scope;
3. the effective contract at `channelKey` is an External Channel;
4. any asserted `order` and activation frontier agree with the derived state;
5. the complete ordered hint set equals the canonical preselected occurrence set for the fixture.

The compact hints do not substitute for missing identity fields and are never passed to application contracts.

## 6. Projections

The only legal assertion paths are listed in `projection-catalog.yaml`.

Projection families are:

- `result.*`: public `ProcessResult`;
- `attempt.*`: alternate `PROCESS_ATTEMPT` result;
- `trace.*`: canonical semantic and gas trace;
- `demands.*`: logical semantic demands;
- `feeder.*`: canonical feeder derivations;
- `commit.*`: revision-bound persistence decision;
- `platform.*`: platform terminal state;
- `variants.*`: exact named variant outputs.

A dot path selects an object field. Decimal segments select list positions. Braced paths such as `result.{status,document,events,totalGas}` select the fields in the written order. A missing declared projection fails the fixture unless the assertion operator is `absent`.

## 7. Assertions

Supported operators are:

- `equals`, `notEquals`: exact semantic equality or inequality;
- `equalsProjection`: compare `actual` with the projection named by `expectedProjection`;
- `absent`, `present`: exact projection absence or presence;
- `sequenceEquals`: ordered equality preserving duplicates;
- `contains`, `notContains`: containment in the selected value;
- `lessThan`, `greaterThan`: exact numeric comparison;
- `sameAcrossVariants`: exact equality across every declared variant;
- `failsWith`: exact deterministic diagnostic category;
- `all`, `none`: universal or empty predicate over the selected projection.

A string written in `expected` is always a literal string. Projection comparison MUST use `expectedProjection`; implicit strings such as `input.root` are forbidden.

`ordered: true` requires the expected elements to occur in the listed relative order. `variant` restricts an assertion to the named exact variant.

## 8. Trace model

`TRACE-SCHEMA.md` defines the canonical named entry, logical demand record, and every derived trace. `trace.namedEntries` is the authoritative gas trace. The weighted sum MUST equal `result.totalGas`.

A runner MAY retain richer implementation diagnostics, but fixtures cannot observe them unless they are normalized into a catalogued projection. Host stack traces, object identities, thread schedules, cache hits, and physical provider details are nonportable.

## 9. Failure and rollback

Unknown fields, invalid exact Blue nodes, provider mismatch, malformed delivery hints, unsupported controls, unsupported operations, undeclared projections, invalid assertion shapes, or disagreement among prose, registry, gas manifest, and fixture package are harness failures.

A Contracts deterministic failure follows the specification’s atomic rollback rules. A fixture control never authorizes partial host-side result construction.

## 10. Package integrity

`manifest.yaml` is the authoritative inventory for this fixture package. It lists every behavior fixture, gas fixture, and support file with its relative path, role, LF-normalized byte length, and SHA-256 digest. It binds the exact Contracts runtime-registry package identity, gas-manifest package identity and file digest, and vector-coverage map.

The fixture-package identity is calculated as:

```text
sha256(
  UTF-8 canonical JSON of manifest.yaml
  with packageIdentity set to null
  and object keys sorted lexicographically
)
```

The package validator MUST check:

- JSON-Schema closure of every fixture;
- exact Blue-node validity before BlueId calculation;
- delivery-hint derivability for statically available fixture Roots;
- projection-catalog and control-language closure;
- completed status vocabulary and attempt-result separation;
- vector coverage;
- runtime-registry and gas-manifest identities;
- fixture-package and release-manifest identities.

A fixture, support file, gas schedule, registry dependency, or coverage-map change requires a new fixture-package identity. The registry manifest's reverse fixture binding is excluded from the registry package identity to avoid an identity cycle.


### Collection-derived embedded scopes

For `Process Embedded.collectionPaths`, the harness MUST enumerate the complete direct ordinary key set of each present object-compatible collection in Unicode code-point order. It MUST derive one concrete scope path per direct key using Runtime Pointer escaping. Lists are not collection targets, wildcard syntax is invalid, and no path may traverse `contracts` or another reserved Language field.

The compact delivery hints always name concrete scope paths such as `/lessons/lesson-17`; they never name a wildcard or collection selector.
