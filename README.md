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

```java
import blue.language.codec.BlueFormat;
import blue.language.model.Node;
import blue.language.runtime.BlueLanguage;

import static blue.language.model.wire.BlueLanguageConstants.TEXT_TYPE_BLUE_ID;

try (BlueLanguage language = BlueLanguage.builder().build()) {
    String yaml = "type:\n  blueId: " + TEXT_TYPE_BLUE_ID
            + "\nvalue: hello\n";
    Node source = language.codec().parseSource(yaml, BlueFormat.YAML);

    String blueId = language.identity().sourceDocumentBlueId(source);
    Node canonical = language.identity().canonicalIdentityInput(source);

    assert blueId.equals(language.identity().directBlueId(canonical));
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

```java
import blue.language.model.Node;
import blue.language.provider.NodeProvider;
import blue.language.provider.NodeProviderResult;
import blue.language.runtime.BlueLanguage;

import java.util.Collections;
import java.util.Map;

Node child = new Node().value("child");
String childBlueId;
try (BlueLanguage identityRuntime = BlueLanguage.builder().build()) {
    childBlueId = identityRuntime.identity().directBlueId(child);
}
Map<String, Node> exactContent = Collections.singletonMap(childBlueId, child);
NodeProvider provider = new NodeProvider() {
    @Override
    public java.util.List<Node> fetchByBlueId(String blueId) {
        Node found = exactContent.get(blueId);
        return found == null ? Collections.emptyList()
                : Collections.singletonList(found.clone());
    }

    @Override
    public NodeProviderResult fetchResultByBlueId(String blueId) {
        Node found = exactContent.get(blueId);
        return found == null ? NodeProviderResult.notFound()
                : NodeProviderResult.found(
                        Collections.singletonList(found.clone()));
    }
};

try (BlueLanguage language = BlueLanguage.builder()
        .nodeProvider(provider)
        .build()) {
    Node expanded = language.graph().expand(
            new Node().blueId(childBlueId));
}
```

The runtime calculates the returned candidate’s exact identity before admitting
it. `FOUND`, `NOT_FOUND`, `UNAVAILABLE`, and `INVALID_EVIDENCE` remain distinct;
a transport outage never proves semantic absence. Read
[Providers and evidence](docs/guides/providers-and-evidence.md).

### 3. Process one Root and event

```java
import blue.language.model.Node;
import blue.language.processor.BlueContracts;
import blue.language.processor.DocumentProcessingResult;
import blue.language.runtime.BlueLanguage;

try (BlueLanguage language = BlueLanguage.builder().build();
     BlueContracts contracts = BlueContracts.builder(
             language.processing()).build()) {
    Node root = new Node().name("Root");
    Node event = new Node().name("Event");

    DocumentProcessingResult result = contracts.process(root, event);
    if (result.commits()) {
        Node nextRoot = result.document();
        java.util.List<Node> rootEvents = result.events();
    } else if (result.diagnostic() != null) {
        String stableCategory = result.diagnostic().category().name();
    }
}
```

Only `success` commits. `no-match`, `stale`, and `terminated` are normal
noncommitting outcomes without diagnostics. Deterministic failures carry a
stable status, category, details, and exact admitted-gas prefix. See
[Contracts processing](docs/guides/contracts-processing.md) and
[statuses and diagnostics](docs/reference/statuses-and-diagnostics.md).

### 4. Observe fragmented processing demand exactly

`processAttempt` makes resource suspension data, not an exception:

```java
ProcessAttemptResult attempt = contracts.processAttempt(root, event);
if (attempt.isComplete()) {
    DocumentProcessingResult completed = attempt.processResult();
} else {
    java.util.List<String> required = attempt.requiredExactBlueIds();
}
```

Fulfil the reported exact BlueIds through the configured provider, then retry
the exact same semantic inputs. The processor
loads participating headers first, selected executable bodies later, and does
not open unrelated branches. Read [Fragmented processing](docs/guides/fragmented-processing.md)
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
