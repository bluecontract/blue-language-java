# Blue Language Java

Java implementation of the Blue language core:
https://language.blue/docs/reference/specification

Blue is a deterministic document language for describing data, types, identity,
and document-processing behavior. A Blue document can be parsed, resolved
against its type graph, reduced to canonical content, and addressed by a stable
content hash called a BlueId.

This library gives Java applications the foundations needed to work with Blue:

- parse and serialize Blue YAML/JSON;
- compute deterministic BlueIds;
- resolve `type` chains and `{ blueId: ... }` references;
- validate deterministic `schema` constraints;
- support list control forms such as `$previous`, `$pos`, and `$empty`;
- build immutable `FrozenNode` and `ResolvedSnapshot` runtime views;
- match nodes against type/shape patterns efficiently;
- apply canonical patches;
- run the generic snapshot-backed document processor;
- register custom channel, handler, and marker processors.

## Core Concepts

### Nodes

A Blue document is a tree of nodes. A node has one payload kind:

- scalar value;
- list items;
- object fields.

Nodes can also carry language metadata such as `name`, `description`, `type`,
`schema`, `itemType`, `keyType`, `valueType`, and `blueId`.

```yaml
name: Counter
description: Small document with one integer field
counter:
  type: Integer
  value: 0
```

The Java representation is `blue.language.model.Node`. It is mutable and useful
for parsing, authoring, serialization, and compatibility APIs.

### Types

In Blue, a type is also a Blue node. A document with `type` inherits and must
conform to that type.

```yaml
name: Price
amount:
  type: Integer
currency:
  type: Text
```

An instance can point to the type by BlueId:

```yaml
type:
  blueId: <PriceBlueId>
amount: 150
currency: EUR
```

Resolving the instance makes inherited fields, type metadata, and constraints
available in the runtime view.

### BlueIds

A BlueId is a deterministic content address. It is calculated from canonical
Blue content using RFC 8785-style canonical JSON input and SHA-256/Base58
output.

In canonical Blue, `{ blueId: X }` is a pure reference. It cannot be mixed with
sibling content:

```yaml
# valid
type:
  blueId: 4th6...

# invalid
type:
  blueId: 4th6...
  name: Price
```

This keeps reference identity unambiguous.

### Canonical Versus Resolved

Blue distinguishes two useful views:

- canonical content: minimized content used for identity and storage;
- resolved content: runtime view with inherited type state available.

`ResolvedSnapshot` contains both views as immutable `FrozenNode` graphs:

```text
ResolvedSnapshot
  canonicalRoot  -> minimized identity source
  resolvedRoot   -> runtime view
  blueId         -> canonicalRoot.blueId()
```

Use snapshots for hot processing paths. Use mutable `Node` values at the edges
where you parse, serialize, or build documents programmatically.

## Quick Start

### Parse YAML And Serialize It Back

```java
import blue.language.Blue;
import blue.language.model.Node;

Blue blue = new Blue();

Node node = blue.yamlToNode(
        "name: Counter\n" +
        "counter: 0\n");

String json = blue.nodeToJson(node);
String yaml = blue.nodeToYaml(node);

System.out.println(json);
System.out.println(yaml);
```

### Compute A Structural BlueId

Use `calculateBlueId` when the node itself is the content you want to address.

```java
String blueId = blue.calculateBlueId(node);
System.out.println(blueId);
```

Structural BlueIds are sensitive to authored content. If a document contains a
redundant inherited override, that override is part of the structural input.

### Compute A Semantic BlueId

Use `calculateSemanticBlueId` when you want identity after preprocess, resolve,
and minimization.

```java
String semanticBlueId = blue.calculateSemanticBlueId(node);
System.out.println(semanticBlueId);
```

Semantic identity is useful when different authored forms should be treated as
the same document because they resolve to the same minimized meaning.

## Reference Providers

Blue resolves `{ blueId: ... }` references through a `NodeProvider`.

For tests and local tools, `BasicNodeProvider` is often enough:

```java
import blue.language.Blue;
import blue.language.model.Node;
import blue.language.provider.BasicNodeProvider;

Blue bootstrap = new Blue();

Node priceType = bootstrap.yamlToNode(
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

For production storage, implement `NodeProvider`:

```java
import blue.language.NodeProvider;
import blue.language.model.Node;

import java.util.Collections;
import java.util.List;

public final class DatabaseNodeProvider implements NodeProvider {
    private final BlueDocumentStore store;

    public DatabaseNodeProvider(BlueDocumentStore store) {
        this.store = store;
    }

    @Override
    public List<Node> fetchByBlueId(String blueId) {
        Node content = store.fetchCanonicalNode(blueId);
        return content == null ? Collections.emptyList() : Collections.singletonList(content);
    }
}
```

The provider should return canonical Blue content for a requested BlueId. The
library wraps providers internally to support single-document and multi-document
reference forms.

## Schema

`schema` provides deterministic core validation. Supported keywords include:

- `required`
- `allowMultiple`
- `minLength`
- `maxLength`
- `minimum`
- `maximum`
- `exclusiveMinimum`
- `exclusiveMaximum`
- `multipleOf`
- `minItems`
- `maxItems`
- `uniqueItems`
- `minFields`
- `maxFields`
- `enum`

Example:

```yaml
name: Product
sku:
  type: Text
  schema:
    required: true
    minLength: 3
    maxLength: 32
quantity:
  type: Integer
  schema:
    minimum: 0
```

## Lists

Blue list resolution supports overlays over inherited lists.

### Positional Overlay

```yaml
type:
  blueId: <BaseListType>
items:
  - $previous:
      blueId: <InheritedItemsBlueId>
  - $pos: 1
    value: replacement
  - value: appended
```

`$previous` anchors the inherited list. `$pos` replaces a specific inherited
position. Normal items after the overlay append to the result.

### Empty List Placeholder

```yaml
items:
  - $empty: true
```

`$empty: true` is content. It is not the same as an absent list.

### Merge Policies

```yaml
type: List
mergePolicy: append-only
items:
  - value: first
```

Supported list policies:

- `positional`
- `append-only`

The resolver, minimizer, and BlueId calculator all understand these list-control
forms.

## Immutable Snapshots

`ResolvedSnapshot` is the preferred runtime representation.

```java
import blue.language.snapshot.ResolvedSnapshot;

ResolvedSnapshot snapshot = blue.resolveToSnapshot(price);

System.out.println(snapshot.blueId());
System.out.println(snapshot.frozenCanonicalRoot().blueId());
System.out.println(snapshot.frozenResolvedRoot().blueId());
```

Snapshots provide:

- immutable canonical root;
- immutable resolved root;
- cached per-node BlueIds;
- path indexes for fast reads;
- structural sharing for resolved references and type graphs.

Read a node by JSON Pointer:

```java
import blue.language.snapshot.FrozenNode;

FrozenNode amount = snapshot.resolvedAt("/amount");
System.out.println(amount.getValue());
```

Use JSON Pointer escaping for literal `/` and `~` in field names:

```java
FrozenNode value = snapshot.resolvedAt("/a~1b/c~0d");
```

This addresses the object path:

```yaml
a/b:
  c~d: value
```

## Snapshot Caches

`Blue` keeps a resolved snapshot cache and a resolved reference cache.

```java
ResolvedSnapshot first = blue.resolveToSnapshot(price);
ResolvedSnapshot second = blue.loadSnapshot(first.blueId());

System.out.println(first == second); // true when loaded from the in-memory cache
System.out.println(blue.resolvedSnapshotCacheSize());
System.out.println(blue.resolvedReferenceCacheSize());
```

You can preload snapshots at startup:

```java
blue.cacheResolvedSnapshot(first);
```

Cache hits improve performance but do not change document identity or processor
gas accounting.

## Dictionary-Aware Export

A dictionary is a named collection of known Blue type definitions. When you send
a document to another system, that system may tell you which dictionaries it
understands. The exporter can then keep supported types as compact BlueId
references and inline unsupported type definitions so the receiver still gets a
self-describing document.

Register dictionaries through the generic `TypeDictionary` SPI:

```java
import blue.language.dictionary.TypeDictionary;

blue.registerTypeDictionary(myDictionary);
```

Export for a receiver that supports one dictionary version:

```java
import blue.language.dictionary.ExportContext;

ExportContext context = ExportContext.builder()
        .dictionary("example.types", "ExampleDictionaryBlueId")
        .build();

String yaml = blue.nodeToYaml(document, context);
String json = blue.nodeToJson(document, context);
```

If a referenced type belongs to `example.types` and is representable by
`ExampleDictionaryBlueId`, the exported document keeps the compact reference:

```yaml
request:
  type:
    blueId: <SupportedRequestTypeBlueId>
```

If a referenced type is known locally but not supported by the receiver, the
exporter inlines the current type definition:

```yaml
request:
  type:
    name: Custom Request
    amount:
      type:
        blueId: <IntegerBlueId>
    memo:
      type:
        blueId: <TextBlueId>
```

Inlining is recursive and cycle-checked. The exporter transforms only type
metadata fields: `type`, `itemType`, `keyType`, and `valueType`. Ordinary data
references remain ordinary data references.

Disable fallback in strict integrations:

```java
ExportContext strictContext = ExportContext.builder()
        .dictionary("example.types", "ExampleDictionaryBlueId")
        .inlineUnsupportedTypes(false)
        .build();
```

With fallback disabled, export fails if any known type cannot be represented by
the requested dictionary context.

## Matching

Matching answers: does this candidate node conform to this target type or
pattern?

```java
Node event = blue.yamlToNode(
        "message:\n" +
        "  request:\n" +
        "    amount: 10\n" +
        "    currency: USD\n" +
        "  ignored:\n" +
        "    deeply: nested\n");

Node pattern = blue.yamlToNode(
        "message:\n" +
        "  request:\n" +
        "    currency: USD\n");

boolean matches = blue.nodeMatchesType(event, pattern);
```

For hot loops, match resolved immutable nodes:

```java
ResolvedSnapshot eventSnapshot = blue.resolveToSnapshot(event);
ResolvedSnapshot patternSnapshot = blue.resolveToSnapshot(pattern);

boolean fast = blue.nodeMatchesType(
        eventSnapshot,
        "/message/request",
        patternSnapshot.resolvedAt("/message/request"));
```

The mutable compatibility matcher resolves only paths observed by the target
pattern. The frozen matcher avoids mutable traversal entirely and reuses
provider-backed references through local caches.

Important matching rules:

- `name` and `description` are labels, not type-compatibility constraints;
- pure reference pattern leaves are exact identity checks;
- extra candidate fields are allowed unless the pattern/schema forbids them;
- list and dictionary payload kinds are checked explicitly;
- missing optional target fields are allowed unless they carry meaningful
  requirements such as `schema.required: true`.

## Canonical Patching

Canonical patches operate on immutable roots and return new snapshots.

```java
import blue.language.processor.model.JsonPatch;
import blue.language.snapshot.ResolvedSnapshot;

ResolvedSnapshot before = blue.resolveToSnapshot(price);

JsonPatch patch = JsonPatch.replace("/amount", new Node().value(200));
ResolvedSnapshot after = blue.applyCanonicalPatch(before, patch);

System.out.println(after.blueId());
```

Supported patch operations:

- `JsonPatch.add(path, value)`
- `JsonPatch.replace(path, value)`
- `JsonPatch.remove(path)`

Patch paths are JSON Pointers. Object keys containing `/` or `~` must be
escaped as `~1` and `~0`.

Patch-time minimization removes redundant overrides where possible. If a patch
writes a value equal to inherited resolved state, the canonical override can be
removed rather than preserved.

## Conformance And Generalization

Document processing must never commit an illegal snapshot. If a patch violates
the current declared type, the processor can generalize the affected node upward
through the type hierarchy.

Example:

```yaml
type: Price in EUR
amount: 150
currency: EUR
```

If a processor changes `currency` to `USD`, the node can no longer honestly
claim to be `Price in EUR`. It may generalize to the parent type `Price`, then
ancestors are checked up to the root.

The generalization flow is transactional:

1. plan the immutable patch;
2. check conformance from changed paths upward;
3. add canonical type/generalization patches where needed;
4. commit the new snapshot only if the whole plan succeeds;
5. roll back on failure.

## Object Mapping

Java objects can be converted to and from Blue nodes.

```java
import blue.language.Blue;
import blue.language.model.Node;
import blue.language.model.TypeBlueId;

@TypeBlueId("Person")
public class Person {
    private String name;
    private Integer age;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Integer getAge() {
        return age;
    }

    public void setAge(Integer age) {
        this.age = age;
    }
}

Blue blue = new Blue();

Person alice = new Person();
alice.setName("Alice");
alice.setAge(34);

Node node = blue.objectToNode(alice);
Person copy = blue.nodeToObject(node, Person.class);
```

`@TypeBlueId` declares the Blue type identity used by the mapper.

## Document Processing Runtime

The library includes a generic document processor. It does not hard-code a
business workflow language; instead, applications register processors for the
contract types they understand.

Processor roles:

- `ChannelProcessor<T>` decides whether an external event belongs to a channel;
- `HandlerProcessor<T>` decides whether a handler should run and executes it;
- `ContractProcessor<T>` is the base interface for marker-style contracts.

Minimal channel contract:

```java
import blue.language.model.TypeBlueId;
import blue.language.processor.model.ChannelContract;

@TypeBlueId("ExampleChannel")
public class ExampleChannel extends ChannelContract {
    private String eventType;

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }
}
```

Minimal channel processor:

```java
import blue.language.model.Node;
import blue.language.processor.ChannelEvaluationContext;
import blue.language.processor.ChannelProcessor;

public final class ExampleChannelProcessor implements ChannelProcessor<ExampleChannel> {
    @Override
    public Class<ExampleChannel> contractType() {
        return ExampleChannel.class;
    }

    @Override
    public boolean matches(ExampleChannel contract, ChannelEvaluationContext context) {
        Object eventType = context.event().getProperties().get("eventType").getValue();
        return contract.getEventType().equals(eventType);
    }

    @Override
    public String eventId(ExampleChannel contract, ChannelEvaluationContext context) {
        Node id = context.event().getProperties().get("eventId");
        return id == null ? null : String.valueOf(id.getValue());
    }
}
```

Minimal handler contract:

```java
import blue.language.model.TypeBlueId;
import blue.language.processor.model.HandlerContract;

@TypeBlueId("SetCounter")
public class SetCounter extends HandlerContract {
    private int value;

    public int getValue() {
        return value;
    }

    public void setValue(int value) {
        this.value = value;
    }
}
```

Minimal handler processor:

```java
import blue.language.model.Node;
import blue.language.processor.HandlerProcessor;
import blue.language.processor.ProcessorExecutionContext;
import blue.language.processor.model.JsonPatch;

public final class SetCounterProcessor implements HandlerProcessor<SetCounter> {
    @Override
    public Class<SetCounter> contractType() {
        return SetCounter.class;
    }

    @Override
    public void execute(SetCounter contract, ProcessorExecutionContext context) {
        context.applyPatch(JsonPatch.replace(
                context.resolvePointer("/counter"),
                new Node().value(contract.getValue())));
    }
}
```

Register processors and run a document:

```java
import blue.language.Blue;
import blue.language.model.Node;
import blue.language.processor.DocumentProcessingResult;

Blue blue = new Blue()
        .registerContractProcessor(new ExampleChannelProcessor())
        .registerContractProcessor(new SetCounterProcessor());

Node document = blue.yamlToNode(
        "name: Counter\n" +
        "counter: 0\n" +
        "contracts:\n" +
        "  events:\n" +
        "    type:\n" +
        "      blueId: ExampleChannel\n" +
        "    eventType: counter.set\n" +
        "  setCounter:\n" +
        "    type:\n" +
        "      blueId: SetCounter\n" +
        "    channel: events\n" +
        "    value: 10\n");

Node event = blue.yamlToNode(
        "eventId: evt-1\n" +
        "eventType: counter.set\n");

DocumentProcessingResult initialized = blue.initializeDocument(document);
DocumentProcessingResult result = blue.processDocument(initialized.snapshot(), event);

System.out.println(result.blueId());
System.out.println(result.totalGas());
System.out.println(blue.nodeToYaml(result.document()));
```

The runtime checks that every contract in the document is understood. If a
contract type has no registered processor, processing fails before state is
mutated.

## Serialization Helpers

```java
String yaml = blue.nodeToYaml(node);
String simpleYaml = blue.nodeToSimpleYaml(node);
String json = blue.nodeToJson(node);
String simpleJson = blue.nodeToSimpleJson(node);
```

The normal serializers preserve Blue metadata. The simple serializers are useful
when you want a simpler projection for display or application-facing output.

## Main API Surface

### `Blue`

Primary facade:

- `yamlToNode(String)`
- `jsonToNode(String)`
- `nodeToYaml(Node)`
- `nodeToJson(Node)`
- `objectToNode(Object)`
- `nodeToObject(Node, Class<T>)`
- `calculateBlueId(Node)`
- `calculateSemanticBlueId(Node)`
- `exportNode(Node, ExportContext)`
- `resolve(Node)`
- `canonicalize(Node)`
- `resolveToSnapshot(Node)`
- `loadSnapshot(String blueId)`
- `applyCanonicalPatch(ResolvedSnapshot, JsonPatch)`
- `nodeToJson(Node, ExportContext)`
- `nodeToYaml(Node, ExportContext)`
- `nodeMatchesType(Node, Node)`
- `nodeMatchesType(FrozenNode, FrozenNode)`
- `nodeMatchesType(ResolvedSnapshot, String, FrozenNode)`
- `initializeDocument(Node)`
- `processDocument(Node, Node)`
- `processDocument(ResolvedSnapshot, Node)`
- `registerContractProcessor(...)`
- `registerTypeDictionary(...)`

### `Node`

Mutable Blue document tree. Best for parsing, authoring, compatibility, and
serialization boundaries.

### `FrozenNode`

Immutable Blue node with cached BlueId and path-index helpers. Best for runtime
internals and repeated reads.

### `ResolvedSnapshot`

Immutable canonical/resolved pair. Best for document-processing state.

### `NodeProvider`

Reference lookup boundary for `{ blueId: ... }` nodes.

Included providers:

- `BasicNodeProvider`
- `CachingNodeProvider`
- `ClasspathBasedNodeProvider`
- `DirectoryBasedNodeProvider`
- `SequentialNodeProvider`

### `NodeTypeMatcher` And `FrozenTypeMatcher`

Shared type/shape matcher. `NodeTypeMatcher` is the mutable compatibility
adapter. `FrozenTypeMatcher` is the fast path for resolved immutable graphs.

## Implementation Status

Implemented and covered by tests:

- strict canonical language core;
- RFC 8785-style canonical BlueId hashing for supported scalar/list/object
  cases;
- deterministic integer and typed-Double handling;
- reference-only `blueId` semantics;
- payload-kind exclusivity;
- schema validation for deterministic core keywords;
- list control forms and reverse minimization;
- circular self-reference ingestion;
- immutable snapshots with path indexes and resolved type cache reuse;
- canonical overlay patching and patch-time minimization;
- dynamic type generalization with rollback;
- fast frozen type/pattern matching;
- snapshot-backed document processing runtime;
- external channel/handler/marker processor SPI.

Known boundaries:

- cross-language golden fixtures are still needed for independent
  implementation certification;
- provider ingestion stores strict canonical/preprocessed content and does not
  default to semantic resolve/minimize storage;
- conformance/generalization is snapshot-safe at the boundary but still bridges
  through mutable resolver internals in some checks;
- concrete business contracts are supplied by applications through registered
  processors;
- canonical-plus-bundle transport/webhook export is not part of this module yet.

For deeper design notes, see:

- [Canonical Language Core](docs/canonical-language-core.md)
- [Frozen Type Matching](docs/frozen-type-matching.md)
- [Processor Contract Matching](docs/processor-contract-matching.md)
- [Snapshots, Patching, And Generalization](docs/snapshots-patching-and-generalization.md)
- [Specification Implementation Gaps](docs/specification-implementation-gaps.md)

## Build And Test

Run the test suite:

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
  frozen-type-matching.md
  processor-contract-matching.md
  snapshots-patching-and-generalization.md
  specification-implementation-gaps.md
```

## Links

- Blue language specification: <https://language.blue/docs/reference/specification>
- Source repository: <https://github.com/bluecontract/blue-language-java>
