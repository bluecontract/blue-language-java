# Blue Language Java

Java implementation of the Blue language core:
https://github.com/bluecontract/blue-spec

Blue is a deterministic document language for describing data, types, and
identity. A Blue document can be parsed, resolved against its type graph,
reduced to canonical content, and addressed by a stable content hash called a
BlueId. Blue Contracts and Processor 1.0 is implemented as a separate runtime
target on top of the language layer for document processing, channels, handlers,
events, gas, checkpoints, embedded scopes, lifecycle, and termination.

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

Blue Language 1.0 and Blue Contracts and Processor 1.0 have separate
conformance suites and reports. 

## Installation

Gradle:

```groovy
repositories {
    mavenCentral()
}

dependencies {
    implementation "blue.language:blue-language-java:3.0.0"
}
```

Maven:

```xml
<dependency>
    <groupId>blue.language</groupId>
    <artifactId>blue-language-java</artifactId>
    <version>3.0.0</version>
</dependency>
```

## Core Concepts

### Nodes

A Blue document is a rooted graph slice. References and shared type nodes make
the complete Blue value a graph, even though one serialized document shows a
finite rooted slice. A node has one payload kind:

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

### Source, Resolved, Canonical, And Minimized Forms

Blue keeps four purposes distinct:

- Source Document: authored input, including aliases and list controls;
- Resolved Form: complete runtime meaning with inherited state available;
- Canonical Identity Input: unique direct BlueId input;
- Minimized Overlay: a smaller author-facing Source form that resolves to the
  same meaning.

Canonicalization, not minimization, produces identity input. Blue semantic
canonicalization is also separate from RFC 8785 canonical JSON serialization,
which determines the bytes of helper values inside the BlueId algorithm.

`ResolvedSnapshot` contains both views as immutable `FrozenNode` graphs:

```text
ResolvedSnapshot
  canonicalRoot  -> unique Canonical Identity Input
  resolvedRoot   -> runtime view
  blueId         -> canonicalRoot.blueId()
```

Use snapshots for hot processing paths. Use mutable `Node` values at the edges
where you parse, serialize, or build documents programmatically.

## Quick Start

New Language integrations should compose the focused `BlueLanguage` services.
The legacy `Blue` facade remains a compatibility entry point while physical
module decomposition is completed. Start with the complete Java 8 program in
[Language pipeline architecture](docs/architecture/language-pipeline.md), then
use the concept guides below for the identity, preprocessing, graph, and
resolution contracts.

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

### Calculate A BlueId Directly

Use `calculateBlueId` when the node is already valid exact BlueId Input.

```java
String blueId = blue.calculateBlueId(node);
System.out.println(blueId);
```

Direct calculation does not preprocess, resolve, canonicalize, or minimize the
input. Source-only content such as a root `blue` directive is rejected.

### Calculate A Source Document BlueId

Use `calculateSourceDocumentBlueId` for authored Source Documents. It executes
the complete identity path:

```text
Source -> preprocess -> complete resolve -> canonicalize -> direct BlueId
```

```java
String sourceDocumentBlueId = blue.calculateSourceDocumentBlueId(node);
System.out.println(sourceDocumentBlueId);
```

There is one BlueId format and algorithm. “Content BlueId” is only shorthand
for the BlueId reached through the Source Document path, not another identifier
kind or namespace.

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

Cache bounds are selected per `Blue` runtime:

```java
Blue serviceRuntime = Blue.withCachePolicy(BlueCachePolicy.lowMemoryDefaults());
Blue batchRuntime = Blue.withCachePolicy(BlueCachePolicy.highThroughputDefaults());
Blue noReloadableCaches = Blue.withCachePolicy(BlueCachePolicy.disabled());
```

`boundedDefaults()` is the conservative production default. `disabled()` turns
off reloadable acceleration caches while preserving snapshots explicitly pinned
with `cacheResolvedSnapshot(...)`.

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

## Working Documents

`WorkingDocument` is a frozen preview state for processor-side read-your-writes
logic. It uses the same immutable patch transaction as the processor runtime,
including conformance checks, dynamic type generalization, and Type
Generalization Policy enforcement, but it does not commit to the active
processor runtime.

```java
import blue.language.processor.ProcessorExecutionContext;
import blue.language.processor.WorkingDocument;
import blue.language.processor.model.JsonPatch;

WorkingDocument working = context.newWorkingDocument();

working.applyPatch(JsonPatch.replace("/price/currency", new Node().value("USD")));

String currency = (String) working.resolvedAt("/price/currency").getValue();
```

Working previews do not emit Document Update cascades, charge gas, update
checkpoints, or write termination/marker state. Contract processors should
preview first and buffer actual effects only after preview succeeds:

```java
working.applyPatches(patches);
context.applyPatches(patches);
```

Use `materializeCanonicalRoot()`, `materializeResolvedRoot()`, `commitToNode()`,
or `commitSnapshot()` only at explicit integration boundaries. Normal processor
reads should stay on `FrozenNode` roots and pointer lookups.

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

- `ChannelProcessor<T>` performs complete acceptance for one feeder-preselected
  external occurrence and exposes immutable subscription functions;
- `HandlerProcessor<T>` decides whether a handler should run and executes it;
- `ContractProcessor<T>` is the base interface for marker-style contracts.

Minimal channel contract:

```java
import blue.language.processor.model.ChannelContract;

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
import blue.language.processor.ExternalChannelSubscriptionFunctions;

import java.util.Collections;
import java.util.List;

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

    @Override
    public ExternalChannelSubscriptionFunctions<ExampleChannel>
    externalSubscriptionFunctions() {
        return new ExternalChannelSubscriptionFunctions<ExampleChannel>() {
            @Override
            public List<String> channelKeys(ExampleChannel channel) {
                return Collections.singletonList(channel.getEventType());
            }

            @Override
            public String checkpointDomainDiscriminator(
                    ExampleChannel channel) {
                return "example-channel-v1";
            }
        };
    }
}
```

Minimal handler contract:

```java
import blue.language.processor.model.HandlerContract;

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
import blue.language.processor.DocumentProcessor;
import blue.language.processor.ExternalDeliveryPlanDeriver;
import blue.language.processor.ProcessingSnapshotManager;

ProcessingSnapshotManager hostSnapshotManager = /* exact-node store */ ...;
ExternalDeliveryPlanDeriver hostDeliveryPlanDeriver =
        /* revision-complete feeder snapshot */ ...;

Blue blue = new Blue().documentProcessor(
        DocumentProcessor.builder()
                .withSnapshotManager(hostSnapshotManager)
                .withExternalDeliveryPlanDeriver(hostDeliveryPlanDeriver)
                .build());

Node exampleChannelType = new Node().name("ExampleChannel");
String exampleChannelBlueId = blue.calculateBlueId(exampleChannelType);
Node setCounterType = new Node().name("SetCounter");
String setCounterBlueId = blue.calculateBlueId(setCounterType);

blue.registerExternalContractType(exampleChannelBlueId, exampleChannelType, new ExampleChannelProcessor())
        .registerExternalContractType(setCounterBlueId, setCounterType, new SetCounterProcessor());

Node document = blue.yamlToNode(
        "name: Counter\n" +
        "counter: 0\n" +
        "contracts:\n" +
        "  events:\n" +
        "    type:\n" +
        "      blueId: " + exampleChannelBlueId + "\n" +
        "    eventType: counter.set\n" +
        "  setCounter:\n" +
        "    type:\n" +
        "      blueId: " + setCounterBlueId + "\n" +
        "    channel: events\n" +
        "    value: 10\n");

Node event = blue.yamlToNode(
        "eventId: evt-1\n" +
        "eventType: counter.set\n");

DocumentProcessingResult result = blue.processDocument(document, event);

System.out.println(blue.calculateBlueId(result.document()));
System.out.println(result.totalGas());
System.out.println(blue.nodeToYaml(result.document()));
```

### Reading Processor Outcomes

`DocumentProcessingResult` has one committing status and several normal or
failure noncommitting statuses. In particular, `portable-limit-exceeded` means
one structural bound in the bound gas manifest was exceeded, while
`subscription-surface-invalid` means the Root cannot produce a finite,
canonical external-subscription index. See
[Processor Results, Diagnostics, And Recovery](docs/processor-results-diagnostics-and-recovery.md)
for the complete status matrix, diagnostic fields, rollback behavior, examples,
retry guidance, and cross-language comparison rules.

External contract processors must register the canonical type node for the
BlueId they handle. The runtime checks that every active contract in the
initial processing closure is understood; if not, processing fails before state
is mutated. `processDocument(document, event)` is the normative two-input
PROCESS API and initializes scopes as part of the run when needed. A configured
`ExternalDeliveryPlanDeriver` supplies revision-bound environmental evidence;
it is not a third semantic input. Without complete evidence, use
`DocumentProcessor.processAttempt(...)`, acquire the reported exact resources,
and retry from the original Root and event.

Composite External Channel runtime types use the context-aware overloads on
`ExternalChannelSubscriptionFunctions`. `ExternalChannelFunctionContext.member`
resolves one required same-scope channel and records its exact dependency.
`membersByEffectiveType(...)` returns a shallow, canonically ordered view of one
exact runtime-type family; family additions, removals, replacements, and
retyping invalidate the owning subscription without pulling unrelated channel
types into its checkpoint domain. The broader `members()` view resolves the
complete same-scope External Channel surface and should be reserved for runtime
types that intentionally depend on all of it. Captured dependencies travel with
the active `SubscriptionDelta.Entry`, participate in checkpoint-domain
derivation, and are rechecked by both subscription invalidation and sparse
feeder-evidence verification.

Event-evaluation functions can call
`ExternalChannelFunctionContext.matchesPattern(candidate, pattern)` to apply
the processor's frozen Blue matcher without reaching through ambient
`Blue`, repository, or provider state. Each deterministic evaluation pass opens
an independent matcher session bound to that pass's captured
`ProcessingSnapshotManager`; nested member evaluation reuses only that session.
The session closes at the pass boundary, clears its caches, and severs the
manager-backed materializer, so a retained context cannot match afterward.
Pure references are materialized through the manager's verified exact-reference
boundary, and missing, reference-only, or identity-mismatched content fails
closed. The matcher consumes that exact canonical definition directly; it does
not preprocess or merge a definition through the full Language resolver.
Subscription-header functions cannot use this operation directly or through a
member snapshot's event evaluator.

`checkpointSubject(...)` may return either the default pure event reference or
an exact inline node. A Timeline integration can return a minimal inline
`{timeline, timestamp}` subject. `ChannelCheckpointContext.currentSubject()`
then exposes that frozen current subject, while `lastEvent()` exposes the exact
prior subject and lazily verifies a stored pure reference only if needed.
`eventSignature()` and `lastEventSignature()` expose the corresponding subject
BlueIds. Per-channel newness belongs in `isNewerEvent(...)`; the feeder
`eventOrderKey` orders external occurrences and activation intervals and is not
a substitute for Timeline timestamp comparison. Composite and All channel
functions can delegate the selected member's checkpoint subject unchanged.

Named runtime child ledgers are live-bounded. Submitting one through
`submitRuntimeGasLedger(...)` merges it immediately into the invocation meter,
before buffered patches, events, or termination are applied. Those application
effects still roll back atomically on a later runtime failure, but already
admitted gas and its ordered named trace remain in the noncommitting result.

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
- `calculateSourceDocumentBlueId(Node)`
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
- `conformanceReport()`
- `runConformanceSuite()`
- `contractsConformanceReport()`
- `runContractsConformanceSuite()`
- `registerContractProcessor(...)`
- `registerExternalContractType(...)`
- `registerTypeDictionary(...)`

### `Node`

Mutable Blue document graph slice. Best for parsing, authoring, compatibility, and
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
- exact Blue Language 1.0 registry and closed 153-fixture conformance package;
- deterministic integer and typed-Double handling;
- reference-only `blueId` semantics;
- payload-kind exclusivity;
- schema validation for deterministic core keywords;
- list control forms and author-facing minimization;
- circular self-reference ingestion;
- immutable snapshots with path indexes and resolved type cache reuse;
- canonical overlay patching and patch-time minimization;
- dynamic type generalization with rollback;
- fast frozen type/pattern matching;
- snapshot-backed document processing runtime;
- exact generic Blue Contracts and Processor 1.0 registry, manifest-driven gas
  schedule, and closed 140-fixture conformance package;
- processor-owned `RuntimeWorkSession` with live-bounded, namespaced runtime
  ledgers across deterministic processor phases and invocation-owned
  `RuntimeWorkBudget` caps shared by independently named ledgers;
- processor-owned `SemanticOutputBoundary` for exact hosted-runtime output
  identity and semantic construction gas;
- bounded subtype-compatible same-scope member catalogs;
- exact executable-body source descriptors and selected-body reference
  materialization capabilities;
- external channel/handler/marker processor SPI with explicit canonical type
  registration.

Known boundaries:

- provider ingestion stores strict canonical/preprocessed content and does not
  default to semantic resolve/minimize storage;
- provider integration is repository-independent: applications supply the
  generic `NodeProvider` contract, without a catalog implementation, artifact
  coordinate, or manifest assumption. `NodeProviderWrapper.wrap(...)`
  performs strict direct-node verification, and the legacy
  `NodeProviderWrapper.unverified(...)` signature delegates to that same
  verified path. Explicit source-document verification uses
  `ProviderEvidenceVerifier` with a fully bound `SourceProviderEnvironment`;
  no path is a trust bypass. Cyclic providers return a typed
  `CyclicSetProofResult`, so a definitive proof miss, temporary proof
  unavailability, and invalid evidence remain distinct;
- conformance/generalization is snapshot-safe at the boundary but still bridges
  through mutable resolver internals in some checks;
- concrete business contracts are supplied by applications through explicitly
  registered processors and canonical type nodes;
- Contracts 1.0 defaults to same-key dispatch, while immutable
  `handlerChannelKey(...)` and `logicalDeliveryKey(...)` functions can select
  a different frozen same-scope Handler channel and coalesce fresh accepted
  sources. Raw sources retain checkpoint ownership, and application-specific
  request parsing and authorization remain outside this module;
- event-scoped matching and `materializeExactReference(...)` provide
  inline/pure-reference parity. The default context-aware `eventKeys(...)`
  projects referenced `subscriptionKey` and `subscriptionKeys` fragments;
  application-specific registry projections remain downstream, and
  header-time materialization remains fail-closed;
- the generic named child-ledger API is the Language boundary used by BEX 2.0
  integrations. Runtimes that need a stricter local invocation cap create one
  `RuntimeWorkBudget` and attach each participating ledger to it. Release
  validation must bind a compatible downstream runtime before claiming
  Contracts 1.0 child-ledger traces;
- canonical-plus-bundle transport/webhook export is not part of this module yet.

## Documentation

Start with the
[developer process](docs/developer-process.md) before changing production
code, tests, fixtures, specifications, or release metadata. It describes local
setup, repository navigation, comment and constants conventions, the required
Given–When–Then test style, generic `NodeProvider` integration, and the
verification and release-evidence workflow.

The retained documents describe distinct parts of the final implementation:

Each of the eight focused Language pages contains one complete Java 8 program.
`LanguageDocumentationExamplesTest` compiles and executes those exact fenced
examples so documentation changes cannot silently drift from the public API.

| Document | Purpose |
| --- | --- |
| [Nodes and BlueIds](docs/concepts/nodes-and-blueids.md) | Mutable authoring nodes, immutable runtime values, and the one BlueId representation |
| [Direct versus Source Document BlueId](docs/concepts/direct-vs-source-blueid.md) | Exact direct input versus preprocess/resolve/canonicalize Source identity |
| [Preprocessing](docs/concepts/preprocessing.md) | Directive resolution, frozen imports, transformation preflight/order, and mandatory baseline |
| [Expansion, Collapse, and Specialization](docs/concepts/expansion-collapse-specialization.md) | Same-identity graph revelation versus creation of a new typed node |
| [Resolution, Canonicalization, and Minimization](docs/concepts/resolution-canonicalization-minimization.md) | Complete meaning, unique identity input, and author-facing overlays |
| [Lists and Incremental BlueId](docs/concepts/lists-and-incremental-blueid.md) | Normative recursive-prefix fold, append, and suffix recomputation |
| [Building a NodeProvider](docs/guides/building-a-node-provider.md) | Typed outcomes, defensive values, environment binding, and evidence boundaries |
| [Language pipeline architecture](docs/architecture/language-pipeline.md) | Focused `BlueLanguage` services, ownership, immutability, and dependency direction |
| [Developer process](docs/developer-process.md) | Step-by-step setup, implementation, test, fixture, verification, review, and contribution workflow |
| [Canonical Language Core](docs/canonical-language-core.md) | Canonical node rules, BlueId calculation, strict references, schemas, and provider ingestion |
| [Blue Language 1.0 Final Clarifications](docs/blue-language-1.0-final-clarifications.md) | Final preprocessing directive, specialization terminology, identity pipeline, canonicalization/minimization, and conformance bindings |
| [List Controls And Circular BlueIds](docs/list-controls-and-circular-references.md) | List merge controls and single/multi-document cyclic reference behavior |
| [Snapshots, Patching, And Generalization](docs/snapshots-patching-and-generalization.md) | Immutable snapshots, patch planning, minimization, and type generalization |
| [Frozen Type Matching](docs/frozen-type-matching.md) | Mutable/frozen matching paths, limits, references, schemas, and performance boundaries |
| [Processor Contract Matching](docs/processor-contract-matching.md) | External evidence, channel and handler SPI, execution order, checkpointing, and atomic failure |
| [Processor Results, Diagnostics, And Recovery](docs/processor-results-diagnostics-and-recovery.md) | Completed statuses, diagnostics, portable limits, subscription surfaces, rollback, retry, and cross-language handling |
| [Fragmented PROCESS Inputs And Logical Delivery](docs/fragmented-processing-and-logical-delivery.md) | Exact fragments, locality, selected bodies, Phase-B dependencies, and coalesced logical delivery |
| [`Blue` Facade Method Reference](docs/blue-facade-method-reference.md) | Complete facade inventory, operational distinctions, caching, and lifecycle behavior |
| [Language 1.0 And Contracts Kernel 1.0 Migration](docs/language-1.0-contracts-kernel-1.0-migration.md) | Migration from preview APIs to the final generic hosted-runtime boundary |
| [Language 1.0 And Contracts Kernel 1.0 JVM API Report](docs/language-1.0-contracts-kernel-1.0-api-report.md) | Historical cleanup ledger and current binary-compatibility evidence |

The migration and API report intentionally retain historical decisions needed
by downstream maintainers. Generated files under `build/reports/` are evidence
for the exact current source input and should not replace these maintained
design documents.

## Build And Test

The project publishes Java 8-compatible bytecode, uses the checksum-pinned
Gradle 9.6.0 wrapper, and executes tests on a Java 8 toolchain. The JVM that
runs Gradle is recorded in generated release evidence rather than fixed by
repository policy. If Java 8 is not installed locally, Gradle can provision it
through the configured Foojay toolchain resolver.

Run the full CI-style verification command:

```bash
./gradlew clean test
```

Run the test suite without cleaning:

```bash
./gradlew test
```

Run only the Blue Language 1.0 conformance fixtures:

```bash
./gradlew test --tests '*BlueLanguageConformanceFixtureTest'
```

Run only the Blue Contracts and Processor 1.0 conformance fixtures:

```bash
./gradlew test --tests '*BlueContractsConformanceFixtureTest'
```

At runtime, `new Blue().conformanceReport()` returns static Blue Language 1.0
metadata: language version, core registry BlueIds, fixture package identity,
fixture IDs, and fixture categories. `new Blue().runConformanceSuite()` executes
the manifest-driven fixture suite and returns passed fixture IDs plus detailed
failures with fixture ID, category, operation, exception class, and message.
The fixture package under `src/test/resources/blue-language-1.0/fixtures` is an
exact vendored copy of the canonical Blue Language 1.0 package. It contains 153
fixtures and has identity
`sha256:44465973c5c5a8c1e60712fc7970236015d9500e2e9e3fc904e364552ec74a55`.
The registry package identity is
`sha256:b705171a6ca62c990792bcb78db9d921caf5b0ed06370648b9a81769d69dd71e`.
Verify the fixture contents with
`BlueConformanceReport.fixturePackageIdentityMatchesFixtureFiles()`.

At runtime, `new Blue().contractsConformanceReport()` returns static Blue
Contracts and Processor 1.0 metadata: fixture package identity, required fixture
IDs, fixture IDs, categories, and coverage checks.
`new Blue().runContractsConformanceSuite()` executes the separate contracts
fixture suite. The contracts fixture package under
`src/test/resources/blue-contracts-1.0/fixtures` is an exact vendored copy of
the release package. It contains 82 behavior and 58 gas fixtures and has
identity
`sha256:d8231b77e196af8ff268432cf5867466151e16f2d1aec5e493c8a16c3f2e8b18`.
The runtime registry package identity is
`sha256:67ce3101449c5bca9e6093b081da239d5d699fdc02182a058d3ad795c6c6120b`,
and the gas manifest package identity is
`sha256:88c7bbe77d531c9e973cae13002c3464a2c14568833adf5d804d13b7b3d26af5`.
Verify fixture content with
`BlueContractsConformanceReport.fixturePackageIdentityMatchesFixtureFiles()`
and `contractsConformanceReport().isOfficialContracts10FixturePackage()`.
`new Blue().runReleaseConformanceSuites()` emits one machine-readable record
for each of the 293 manifest-listed fixtures and has no skip outcome. The exact
bound release records 153/153 Language passes and 140/140 Contracts passes:
293 pass, zero fail, and zero skipped overall.

The bound final implementation baseline is
`blue-language-1.0-contracts-1.0-bex-2.0-coordination-1.0-final-implementation-baseline`,
with release package identity
`sha256:f6165c10ab07ddd15fb99392753de43fa3afbd79d303a3cd6e300279f09b2cfa`.
The vendored Language and Contracts specifications have SHA-256 digests
`41291e52f520870bd3cc0665cdb085df8f10238853531a9e99d4409b6b63c92e`
and
`d2efc2a5df8cd7e81b17b8c0d5f7ad73c5dbcb91344a7e5714c60605732676c1`,
respectively.

Run the hard release gate:

```bash
./gradlew releaseConformanceTest
```

The task runs the project tests, rejects deprecated or ambiguous preview API
surface, validates every manifest/package identity, executes all 293 fixtures,
and writes:

```text
build/reports/conformance/release-conformance.json
build/reports/conformance/release-conformance.txt
```

Run the complete project-owned release checks from a clean output directory:

```bash
BLUE_RELEASE_EPOCH="$(git show -s --format=%ct HEAD)"
SOURCE_DATE_EPOCH="$BLUE_RELEASE_EPOCH" ./gradlew clean build
SOURCE_DATE_EPOCH="$BLUE_RELEASE_EPOCH" ./gradlew rcVerify
```

The first invocation records successful clean-build evidence only after
`build` completes over the same source fingerprint and `SOURCE_DATE_EPOCH`
recorded by `clean`. Task exclusions such as `-x test` deliberately suppress
that evidence.
Keep `clean build` separate from `rcVerify`: deleting outputs in the task graph
that consumes them is unsafe. The RC gate covers the
project tests, all 293 fixtures, binary-API verification, independently
repeated archive assembly, source-release verification, and the observed
runtime-trace and fragmented-processing scenarios. It writes JAR repeatability evidence to
`build/reports/reproducibility/jar-repeatability.json`, and independently
assembled source-JAR/source-release evidence to
`build/reports/reproducibility/source-archive-repeatability.json`. The focused
runtime report is:

```text
build/reports/runtime-trace/runtime-work-session.json
```

The runtime report records the observed eight-scenario result, including the
exact retained or discarded prefixes and the maximum actual ordered trace
size. The bounded 1,024-member scenario currently observes 4,096 entries; this
value is read from the completed runtime trace rather than copied from a test
expectation. Provider behavior is covered through generic `NodeProvider`
contract tests: found content must verify against the requested identity,
absence and temporary unavailability stay distinct, and invalid evidence
fails closed. No project-owned release check requires a particular external
repository implementation or catalog.

Production archives use reproducible entry ordering and fixed entry
timestamps. `blue/language/build.properties` uses `SOURCE_DATE_EPOCH`; when
that variable is absent, local builds use Unix epoch zero as an explicit
deterministic fallback.

Build jars:

```bash
./gradlew build
```

Publish to local Maven:

```bash
./gradlew publishToMavenLocal
```

The Gradle wrapper uses the distribution declared in
`gradle/wrapper/gradle-wrapper.properties`: Gradle 9.6.0 with SHA-256
`bbaeb2fef8710818cf0e261201dab964c572f92b942812df0c3620d62a529a01`.
Local and CI environments need either network access for that first wrapper
download or a cached Gradle distribution; offline verification works once the
wrapper distribution and normal dependency cache are already present.

The checked-in `api/blue-language-java-1.0.json` file is the final
Language 1.0 and Contracts kernel 1.0 JVM descriptor baseline. Verify a
candidate against it with:

```bash
./gradlew verifyFinalApiBaseline
```

## Project Layout

```text
src/main/java/blue/language
  Blue.java                         primary facade
  model/                            Node, Schema, serializers, annotations
  merge/                            type resolution and merge pipeline
  preprocess/                       directive and mandatory baseline preprocessing
  provider/                         BlueId content providers
  snapshot/                         FrozenNode and ResolvedSnapshot
  processor/                        generic document processor runtime
  conformance/                      type conformance and generalization
  utils/                            BlueId, matching, JSON pointer, helpers

docs/
  developer-process.md              contribution and release workflow
  canonical-language-core.md        identity and canonical language rules
  blue-language-1.0-final-clarifications.md
  list-controls-and-circular-references.md
  snapshots-patching-and-generalization.md
  frozen-type-matching.md
  processor-contract-matching.md
  processor-results-diagnostics-and-recovery.md
  fragmented-processing-and-logical-delivery.md
  blue-facade-method-reference.md
  language-1.0-contracts-kernel-1.0-migration.md
  language-1.0-contracts-kernel-1.0-api-report.md

src/main/resources/
  registry/                         Language and Contracts registries
  specifications/                   vendored normative specifications
  release/                          identity-bound release manifest

src/test/resources/
  blue-language-1.0/fixtures/       closed Language conformance package
  blue-contracts-1.0/fixtures/      closed Contracts and gas package
```

## Links

- Blue language specification: <https://language.blue/docs/reference/specification>
- Source repository: <https://github.com/bluecontract/blue-language-java>
