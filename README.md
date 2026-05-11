# Blue Language Java

Java implementation of the Blue language core.

Blue is a deterministic document language for describing data, types, identity,
and contract-driven document processing. The central idea is that a document can
be resolved into a type-sound snapshot and addressed by a stable content hash
called a BlueId.

This repository is the Java foundation:

- parse and serialize Blue YAML/JSON;
- compute deterministic BlueIds;
- resolve `type` chains and `{ blueId: ... }` references;
- validate `schema` constraints;
- handle list overlays such as `$previous`, `$pos`, and `$empty`;
- build immutable `FrozenNode` / `ResolvedSnapshot` views;
- apply canonical patches;
- provide the generic document-processor runtime used by contract libraries.

It intentionally does not implement every business contract from repo.blue. For
real repository contract types and executable processors, see the ecosystem
split below.

## Ecosystem

| Project | Purpose |
| --- | --- |
| `blue-language-java` | Language core, BlueId, resolution, snapshots, matching, patching, generic processor runtime. |
| `blue-repository-java` | Generated Java classes and resources for types published at [repo.blue](https://repo.blue). |
| `blue-contract-java` | Executable processors for repo.blue contracts such as Conversation workflows and concrete timeline providers. |

If you want to build a product workflow such as a Counter, PayNote, or MyOS
session processor, you normally use all three:

```text
blue-language-java    -> core language/runtime
blue-repository-java  -> generated type catalog
blue-contract-java    -> executable contract behavior
```

## Blue In Two Minutes

A Blue node is one of:

- a scalar;
- a list;
- an object.

Nodes may also have language metadata such as `name`, `description`, `type`,
`schema`, and `blueId`.

Example:

```yaml
name: Counter
description: Small document with one integer field
counter:
  type: Integer
  value: 0
```

`type` is not a separate schema language. Any Blue node can be used as a type.
When a document is resolved, inherited fields, fixed values, and schema
constraints are merged and validated.

`blueId` is a content address:

```yaml
type:
  blueId: 4th6...abc
```

In canonical Blue, `{ blueId: X }` is a pure reference. It cannot be mixed with
other sibling content.

## Installation

Gradle:

```groovy
repositories {
    mavenCentral()
}

dependencies {
    implementation "blue.language:blue-language-java:0.8.0"
}
```

Maven:

```xml
<dependency>
  <groupId>blue.language</groupId>
  <artifactId>blue-language-java</artifactId>
  <version>0.8.0</version>
</dependency>
```

For local development from this checkout:

```bash
./gradlew publishToMavenLocal
```

Non-CI local builds publish as `0.8.0-SNAPSHOT`.

## Quick Start

### Parse YAML And Compute A BlueId

```java
import blue.language.Blue;
import blue.language.model.Node;

public class Example {
    public static void main(String[] args) {
        Blue blue = new Blue();

        String yaml =
                "name: Counter\n" +
                "counter: 0\n";

        Node node = blue.yamlToNode(yaml);
        String blueId = blue.calculateBlueId(node);

        System.out.println(blueId);
        System.out.println(blue.nodeToJson(node));
    }
}
```

Use `calculateBlueId(node)` when the node itself is the content you want to
address.

Use `calculateSemanticBlueId(node)` when you want to resolve and minimize first,
so authoring noise and inherited redundant fields do not change identity.

```java
String semantic = blue.calculateSemanticBlueId(node);
```

### Resolve A Document Against A Type

```java
import blue.language.Blue;
import blue.language.model.Node;
import blue.language.provider.BasicNodeProvider;

Node priceType = new Blue().yamlToNode(
        "name: Price\n" +
        "amount:\n" +
        "  type: Integer\n" +
        "currency:\n" +
        "  type: Text\n");

BasicNodeProvider provider = new BasicNodeProvider(priceType);
String priceTypeBlueId = provider.getBlueIdByName("Price");

Blue blue = new Blue(provider);

Node price = blue.yamlToNode(
        "type:\n" +
        "  blueId: " + priceTypeBlueId + "\n" +
        "amount: 150\n" +
        "currency: EUR\n");

Node resolved = blue.resolve(price);

System.out.println(blue.nodeToYaml(resolved));
```

The provider is responsible for fetching referenced nodes by BlueId. In tests
and local tools, `BasicNodeProvider` is enough. In production, implement
`NodeProvider` against your storage layer.

### Work With Immutable Snapshots

```java
import blue.language.snapshot.ResolvedSnapshot;

ResolvedSnapshot snapshot = blue.resolveToSnapshot(price);

System.out.println(snapshot.blueId());
System.out.println(snapshot.canonicalRoot());
System.out.println(snapshot.resolvedRoot());
```

A `ResolvedSnapshot` contains:

- canonical root: minimized content used for identity;
- resolved root: runtime view with inherited fields available;
- path indexes for fast reads;
- cached BlueIds.

This is the preferred representation for processor/runtime work.

### Apply A Canonical Patch

```java
import blue.language.processor.model.JsonPatch;
import blue.language.snapshot.ResolvedSnapshot;

ResolvedSnapshot before = blue.resolveToSnapshot(price);

JsonPatch patch = JsonPatch.replace("/amount", new Node().value(200));
ResolvedSnapshot after = blue.applyCanonicalPatch(before, patch);

System.out.println(after.blueId());
```

Patch application keeps canonical content minimal where possible. If a patch
writes a value equal to inherited resolved state, the redundant override can be
removed from the canonical root.

## Document Processing

This repository provides the generic processor runtime, not every concrete
contract.

The runtime understands concepts such as:

- channels;
- handlers;
- markers;
- checkpoints;
- lifecycle events;
- triggered events;
- embedded scopes;
- document-update cascades;
- gas accounting;
- patch boundaries;
- dynamic type generalization.

To execute real repo.blue Conversation contracts, add `blue-repository-java` and
`blue-contract-java`.

Example document shape:

```yaml
name: Counter
counter: 0
contracts:
  ownerChannel:
    type: MyOS/MyOS Timeline Channel
    timelineId: bb13b2d9-3df9-5fea-9fdf-dd4f0ae74486
    accountId: bbe140c4-7625-41cd-9381-1f677014e996
  increment:
    type: Conversation/Operation
    channel: ownerChannel
    request:
      type: Integer
  incrementImpl:
    type: Conversation/Sequential Workflow Operation
    operation: increment
    steps:
      - type: Conversation/Update Document
        changeset:
          - op: replace
            path: /counter
            val: ${event.message.request + document('/counter')}
```

That specific contract behavior belongs in `blue-contract-java`. This project
provides the runtime APIs those processors use.

## Main APIs

### `Blue`

Primary facade for most users.

Common methods:

- `yamlToNode(String)`
- `jsonToNode(String)`
- `nodeToYaml(Node)`
- `nodeToJson(Node)`
- `calculateBlueId(Node)`
- `calculateSemanticBlueId(Node)`
- `resolve(Node)`
- `canonicalize(Node)`
- `resolveToSnapshot(Node)`
- `loadSnapshot(String blueId)`
- `applyCanonicalPatch(ResolvedSnapshot, JsonPatch)`
- `initializeDocument(Node)`
- `processDocument(Node, Node)`

### `Node`

Mutable Java representation of a Blue node. Useful for authoring, parsing,
serialization, and compatibility with existing APIs.

### `FrozenNode`

Immutable node representation with cached BlueId and copy-on-write helpers.
Prefer this for runtime internals and repeated reads.

### `ResolvedSnapshot`

Immutable pair of canonical and resolved roots. Preferred runtime state for
processing.

### `NodeProvider`

Interface for resolving `{ blueId: ... }` references.

```java
public interface NodeProvider {
    List<Node> fetchByBlueId(String blueId);
}
```

Included providers:

- `BasicNodeProvider`
- `CachingNodeProvider`
- `ClasspathBasedNodeProvider`
- `DirectoryBasedNodeProvider`
- `SequentialNodeProvider`

## Canonical Rules Implemented

This implementation enforces the strict canonical core:

- `schema` is the canonical constraint field;
- legacy `constraints` input is migrated to `schema`;
- `schema` plus `constraints` together is rejected;
- `{ blueId: X }` is reference-only;
- a node cannot mix payload kinds (`value`, `items`, and object fields);
- empty lists are preserved for hashing;
- list hashing is domain-separated;
- `$previous`, `$pos`, and `$empty` list-control forms are supported;
- large integers and typed doubles are canonicalized for deterministic hashing;
- circular `this` / `this#i` placeholder flows are supported in provider
  ingestion.

## What Is Not Finished Yet

The current implementation is strong, but not a final cross-language
certification suite.

Known important gaps:

- `schema.pattern` uses Java regex, while the spec targets ECMA-262 regex
  semantics.
- Cross-language golden fixtures are still needed for Java/JS/Ruby parity.
- Provider ingestion currently stores strict canonical/preprocessed content; it
  does not default to semantic resolve/minimize storage.
- Conformance/generalization is immutable at the snapshot boundary, but some
  internals still bridge through mutable `Node`.
- The processor runtime foundation exists here, but concrete repo.blue contract
  behavior lives in `blue-contract-java`.

For a detailed status report, see:

- [Specification Implementation Gaps](docs/specification-implementation-gaps.md)
- [Current Implementation Coverage Report](docs/current-implementation-coverage-report.md)
- [Conversation Types Implementation Coverage](docs/conversation-types-implementation-coverage.md)

## Build And Test

```bash
./gradlew test
```

Build jars:

```bash
./gradlew build
```

Publish to local Maven:

```bash
./gradlew publishToMavenLocal
```

The project currently compiles for Java 8 source/target compatibility and uses
JUnit 5 for tests.

## Project Layout

```text
src/main/java/blue/language
  Blue.java                         primary facade
  model/                            Node, Schema, serializers, annotations
  merge/                            type resolution and merge pipeline
  preprocess/                       alias/default-blue preprocessing
  provider/                         BlueId content providers
  snapshot/                         FrozenNode and ResolvedSnapshot
  processor/                        generic document processor runtime
  conformance/                      type conformance and generalization
  utils/                            BlueId, matching, JSON pointer, helpers

docs/
  canonical-language-core.md
  snapshots-patching-and-generalization.md
  frozen-type-matching.md
  specification-implementation-gaps.md
```

## When To Use Which Repo

Use only `blue-language-java` when you need:

- parse/serialize Blue;
- compute BlueIds;
- resolve types;
- validate schema;
- build snapshots;
- implement your own contract processors.

Add `blue-repository-java` when you need:

- generated Java classes for repo.blue packages;
- type aliases and packaged Blue definitions.

Add `blue-contract-java` when you need:

- executable Conversation contracts;
- MyOS timeline provider support;
- workflow steps such as Update Document, Trigger Event, and JavaScript Code.

## Links

- Blue language spec: <https://language.blue/docs/reference/specification>
- Timeline white paper: <https://language.blue/docs/reference/timelines-white-paper>
- Repository types: <https://repo.blue>
- Source repository: <https://github.com/bluecontract/blue-language-java>

