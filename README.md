# Blue Language Java

Blue is a deterministic graph language for values, types, references, identity,
and change. This repository provides the Java 8 implementation of Blue
Language 1.0 plus the runtime-neutral Blue Contracts and Processor 1.0 kernel.
It does not contain BEX, Coordination, application persistence, or ecosystem-
specific policy.

Blue is a graph, not a tree. A YAML or JSON document is one transport slice;
pure `blueId` references connect exact content into the logical graph.

## The model in one minute

- There is one BlueId format and algorithm.
- Direct and Source Document calculation are two paths to a BlueId.
- Source Document BlueId uses canonicalization, not minimization.
- Expand preserves a node; specialize creates a new node.
- The `blue` directive supplies imports and ordered transformations.
- `PROCESS(document,event)` transforms one Root and returns Root emissions
  only.
- Provider evidence, gas, diagnostics, and output are deterministic across
  equivalent inline/reference, warm/cold, and whole/fragmented forms.

The complete 20–30 minute introduction is [Start here](docs/start-here.md).

## What is included

| Artifact | Purpose |
| --- | --- |
| `blue-language-model` | Blue values, annotations, and stable wire vocabulary |
| `blue-language-core` | codecs, preprocessing, graph operations, identity, resolution, immutable snapshots, matching, patching |
| `blue-language-mapping` | opt-in Java object mapping and type discovery |
| `blue-language-ipfs` | optional CID/IPFS provider adapter |
| `blue-contracts-core` | generic Channels, Handlers, processor phases, gas, diagnostics, lifecycle, checkpoints |
| `blue-conformance` | exact Language and Contracts fixture runners |
| `blue-language-java` | one-dependency aggregate and small convenience façade |

The generated [module graph](docs/architecture/modules-and-dependencies.md)
is the authority for dependency direction. Language never depends on Contracts;
the aggregate composes them through a public Language processing bridge.

## Installation

Use the aggregate when an application needs both Language and Contracts:

```groovy
dependencies {
    implementation 'blue.language:blue-language-java:3.1.0-rc.18'
}
```

Or select only the focused artifacts you use:

```groovy
dependencies {
    implementation 'blue.language:blue-language-core:3.1.0-rc.18'
    implementation 'blue.language:blue-contracts-core:3.1.0-rc.18'
}
```

Production classes target Java 8 bytecode. The checked-in Gradle wrapper may
run on a newer JVM and provisions the Java 8 toolchain used by release gates.

## Ten-minute quick start

### 1. Parse Source and calculate its BlueId

<!-- blue-example: examples/src/main/java/blue/language/examples/SourceDocumentBlueIdExample.java#source-document-blueid -->
```java
        try (BlueLanguage language = BlueLanguage.builder().build()) {
            Node source = language.codec().parseSource(
                    SOURCE_YAML, BlueFormat.YAML);
            Node canonical = language.identity()
                    .canonicalIdentityInput(source);
            String sourceBlueId = language.identity()
                    .sourceDocumentBlueId(source);
            String directBlueId = language.identity()
                    .directBlueId(canonical);

            ExampleSupport.require(canonical.getBlue() == null,
                    "Canonical input must not retain the Source blue directive");
            ExampleSupport.require(TEXT_TYPE_BLUE_ID.equals(
                            canonical.getType().getBlueId()),
                    "The imported alias must resolve to the exact Text type");
            ExampleSupport.require(sourceBlueId.equals(directBlueId),
                    "Source identity must finish on the direct identity path");
            return new Result(canonical, sourceBlueId, directBlueId);
        }
```

The Source path is exact:

```text
Source -> preprocess -> resolve -> canonicalize -> direct BlueId
```

See the tested
[Source Document example](examples/src/main/java/blue/language/examples/SourceDocumentBlueIdExample.java)
and [direct-input example](examples/src/main/java/blue/language/examples/DirectBlueIdExample.java).

### 2. Use verified provider content

<!-- blue-example: examples/src/main/java/blue/language/examples/ExpandCollapseProviderExample.java#verified-provider -->
```java
        Node exactContent = new Node().value(CONTENT_VALUE);
        String exactBlueId =
                DirectBlueIdCalculator.calculateBlueId(exactContent);
        Map<String, Node> contentByBlueId = new LinkedHashMap<>();
        contentByBlueId.put(exactBlueId, exactContent.clone());
        Map<String, Node> providerState = Collections.unmodifiableMap(
                contentByBlueId);
        NodeProvider provider = requestedBlueId ->
                ExampleSupport.lookup(providerState, requestedBlueId);
        Node reference = ExampleSupport.reference(exactBlueId);

        try (BlueLanguage language = BlueLanguage.builder()
                .nodeProvider(provider)
                .build()) {
            Node expanded = language.graph().expand(reference);
            Node collapsed = language.graph().collapse(expanded);
            String expandedBlueId = language.identity()
                    .directBlueId(expanded);

            ExampleSupport.require(exactBlueId.equals(expandedBlueId),
                    "Expansion must preserve the referenced identity");
            ExampleSupport.require(exactBlueId.equals(collapsed.getBlueId()),
                    "Collapse must restore the same pure reference");
            ExampleSupport.require(CONTENT_VALUE.equals(
                            providerState.get(exactBlueId).getValue()),
                    "Graph operations must not mutate provider-owned content");
            ExampleSupport.require(reference.isReferenceOnly(),
                    "Expansion must not mutate the caller's reference");
            return new Result(exactBlueId, expanded, collapsed);
        }
```

The runtime calculates the returned candidate’s exact identity before admitting
it. `FOUND`, `NOT_FOUND`, `UNAVAILABLE`, and `INVALID_EVIDENCE` remain distinct;
a transport outage never proves semantic absence. Read
[Providers and evidence](docs/guides/providers-and-evidence.md).

### 3. Process one Root and event

<!-- blue-example: examples/src/main/java/blue/language/examples/CustomExternalChannelExample.java#custom-external-channel-handler -->
```java
        ContractsExampleSupport.RuntimeWorkProcessor unusedRuntimeWork =
                new ContractsExampleSupport.RuntimeWorkProcessor();
        Node root = ContractsExampleSupport.initializedCounterRoot();
        Node event = ContractsExampleSupport.amountEvent(7L);

        ExampleSupport.require(
                !ContractsExampleSupport.SOURCE_CHANNEL_KEY.equals(
                        ContractsExampleSupport.TARGET_CHANNEL_KEY),
                "The accepting source and Handler target must be distinct");

        try (BlueRuntime runtime = ContractsExampleSupport.runtime(
                unusedRuntimeWork)) {
            DocumentProcessingResult processed =
                    runtime.contracts().process(root, event);

            ExampleSupport.require(
                    processed.status() == ProcessorStatus.SUCCESS,
                    "The custom External Channel delivery must commit: "
                            + ContractsExampleSupport.diagnostic(processed));
            BigInteger counter = (BigInteger) processed.document()
                    .getProperties()
                    .get(ContractsExampleSupport.COUNTER_KEY)
                    .getValue();
            ExampleSupport.require(
                    BigInteger.valueOf(7L).equals(counter),
                    "The custom Handler must apply its buffered patch");
            return new Result(
                    counter,
                    processed.status(),
                    processed.totalGas(),
                    ContractsExampleSupport.SOURCE_CHANNEL_KEY,
                    ContractsExampleSupport.TARGET_CHANNEL_KEY);
        }
```

Only `success` commits. `no-match`, `stale`, and `terminated` are normal
noncommitting outcomes without diagnostics. Deterministic failures carry a
stable status, category, details, and exact admitted-gas prefix. See
[Contracts processing](docs/guides/contracts-processing.md) and
[statuses and diagnostics](docs/reference/statuses-and-diagnostics.md).

### 4. Observe fragmented processing demand exactly

<!-- blue-example: examples/src/main/java/blue/language/examples/PureReferenceFragmentsExample.java#pure-reference-fragments -->
```java
        Node fragmentedRoot = ContractsExampleSupport
                .initializedCounterRoot();
        Node handler = fragmentedRoot.getContracts()
                .getProperties().get(
                        ContractsExampleSupport.ADD_HANDLER_KEY);
        String handlerBlueId = ContractsExampleSupport.blueId(handler);
        fragmentedRoot.getContracts().getProperties().put(
                ContractsExampleSupport.ADD_HANDLER_KEY,
                ContractsExampleSupport.reference(handlerBlueId));

        Node fragmentedEvent = ContractsExampleSupport.amountEvent(5L);
        String rootBlueId = ContractsExampleSupport.blueId(fragmentedRoot);
        String eventBlueId = ContractsExampleSupport.blueId(fragmentedEvent);
        Map<String, Node> exactFragments = new LinkedHashMap<>();
        exactFragments.put(rootBlueId, fragmentedRoot);
        exactFragments.put(eventBlueId, fragmentedEvent);
        exactFragments.put(handlerBlueId, handler);
        List<String> requestedBlueIds = new ArrayList<>();
        NodeProvider provider = blueId -> {
            requestedBlueIds.add(blueId);
            Node exact = exactFragments.get(blueId);
            return exact != null
                    ? Collections.singletonList(exact.clone())
                    : null;
        };

        try (BlueRuntime runtime = ContractsExampleSupport.runtime(
                provider,
                new ContractsExampleSupport.RuntimeWorkProcessor())) {
            DocumentProcessingResult processed =
                    runtime.contracts().process(
                            ContractsExampleSupport.reference(rootBlueId),
                            ContractsExampleSupport.reference(eventBlueId));

            ExampleSupport.require(
                    processed.status() == ProcessorStatus.SUCCESS,
                    "Pure-reference processing must commit: "
                            + ContractsExampleSupport.diagnostic(processed));
            BigInteger counter = (BigInteger) processed.document()
                    .getProperties()
                    .get(ContractsExampleSupport.COUNTER_KEY)
                    .getValue();
            ExampleSupport.require(
                    BigInteger.valueOf(5L).equals(counter),
                    "The selected Handler fragment must update Root");
            ExampleSupport.require(
                    requestedBlueIds.contains(handlerBlueId),
                    "The selected Handler fragment must be fetched");
            return new Result(
                    rootBlueId,
                    eventBlueId,
                    counter,
                    requestedBlueIds);
        }
```

The processor loads participating headers first, selected executable bodies
later, and does not open unrelated branches. A temporarily missing demanded
BlueId is surfaced by `processAttempt` as resumable data, never reclassified as
semantic absence. Read [Fragmented processing](docs/guides/fragmented-processing.md)
and run the tested exact-reference example in `:examples`.

## Determinism across languages

Java does not define the semantics; the bundled specifications and exact
fixture packages do. Implementations in JavaScript or another language agree
when they use the same:

1. normalized Blue value model and wire constants;
2. direct identity algorithm and Source preparation stages;
3. provider outcome/evidence rules;
4. ordered Contracts phases, diagnostics, and gas manifest;
5. exact Language and Contracts conformance fixtures.

Caches, thread schedules, provider call counts, timings, transport layout, and
fragment boundaries are deliberately non-semantic. The release suite compares
identity, result Root, Root events, status, diagnostic data, logical demands,
and gas traces across equivalent representations.

## Learn and extend

- [Start here](docs/start-here.md): complete mental model.
- [Architecture](ARCHITECTURE.md): module, ownership, and decision map.
- [Public API](docs/reference/public-api.md): generated binary signatures.
- [Packages](docs/reference/packages.md): generated public package/type map.
- [Runtime SPI](docs/reference/runtime-spi.md): generated extension registry.
- [Custom runtime types](docs/guides/custom-runtime-types.md): add a runtime-
  neutral Channel or Handler.
- [Developer process](docs/developer-process.md): fixtures, identity-bearing
  registries, API baselines, benchmarks, and RC workflow.
- [Contributing](CONTRIBUTING.md): review contract and checklist.

Every program under [`examples/src/main/java`](examples/src/main/java) has a
`main()` method, a deterministic `run()` result, and an automated test.

## Build, conformance, and release status

```bash
./gradlew build
./gradlew releaseConformanceTest
./gradlew documentationVerify
./gradlew finalQualityVerify
```

The release package binds **153 Language fixtures** and **140 Contracts
fixtures**, exact specification/package identities, Java 8 bytecode, API
baselines, Javadocs, runnable examples, benchmark smoke runs, package/module
cycles, fragmented/locality assertions, and reproducible binary/source
artifacts. Generated [fixture coverage](docs/reference/conformance-fixtures.md)
contains exact categories and identities; the machine-readable final-quality
report decides release eligibility.

For a candidate, commit first and run the SOURCE_DATE_EPOCH-bound clean build
and verification as two uncontended Gradle invocations. The exact commands and
evidence checklist are in [Developer process: Cut an RC](docs/developer-process.md#cut-an-rc).

## License

[MIT](LICENSE)
