package blue.language.snapshot;

import blue.language.model.Node;
import blue.language.model.Schema;
import blue.language.utils.Base58Sha256Provider;
import blue.language.utils.BlueNumbers;
import blue.language.utils.BlueIdCalculator;
import blue.language.utils.JsonPointer;
import blue.language.utils.NodeToBlueIdInput;
import blue.language.utils.NodeToMapListOrValue;
import blue.language.utils.SchemaToMapListOrValue;

import java.lang.reflect.Array;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import static blue.language.utils.Properties.*;

public final class FrozenNode {

    private static final Function<Object, String> HASH = new Base58Sha256Provider();

    private final String name;
    private final String description;
    private final FrozenNode type;
    private final FrozenNode itemType;
    private final FrozenNode keyType;
    private final FrozenNode valueType;
    private final Object value;
    private final List<FrozenNode> items;
    private final Map<String, FrozenNode> properties;
    private final FrozenNode contracts;
    private final String referenceBlueId;
    private final Schema schema;
    private final String mergePolicy;
    private final String previousBlueId;
    private final Integer position;
    private final FrozenNode blue;
    private final boolean inlineValue;
    private final boolean strictCanonical;
    private final boolean strictBlueIdValidation;
    private final boolean previousAnchorContext;
    private final boolean containsCyclicSetReference;
    private final boolean containsSchema;
    private final boolean containsNestedTypedObjectPayload;
    private final boolean constructionModeNormalized;
    private volatile String blueId;
    private volatile ResolvedStructuralKey resolvedStructuralKey;

    private FrozenNode(Builder builder) {
        this.name = builder.name;
        this.description = builder.description;
        this.type = builder.type;
        this.itemType = builder.itemType;
        this.keyType = builder.keyType;
        this.valueType = builder.valueType;
        this.value = builder.nodeValue;
        this.items = freezeList(builder.items, builder.strictCanonical);
        this.properties = freezeMap(builder.properties);
        this.contracts = builder.contracts;
        this.referenceBlueId = builder.referenceBlueId;
        this.schema = builder.schema;
        this.mergePolicy = builder.mergePolicy;
        this.previousBlueId = builder.previousBlueId;
        this.position = builder.position;
        this.blue = builder.blue;
        this.inlineValue = builder.inlineValue;
        this.strictCanonical = builder.strictCanonical;
        this.strictBlueIdValidation = builder.strictBlueIdValidation;
        this.previousAnchorContext = builder.previousAnchorContext;
        this.containsCyclicSetReference = computeContainsCyclicSetReference();
        this.containsSchema = computeContainsSchema();
        this.containsNestedTypedObjectPayload = computeContainsNestedTypedObjectPayload();
        this.constructionModeNormalized = computeConstructionModeNormalized();
        validatePayloadShape();
        this.blueId = strictCanonical && builder.eagerBlueId ? computeBlueId() : null;
    }

    public static FrozenNode empty() {
        return builder().build();
    }

    public static FrozenNode fromNode(Node node) {
        return fromNode(node, true);
    }

    public static FrozenNode fromResolvedNode(Node node) {
        return fromNode(node, false, null);
    }

    public static FrozenNode fromResolvedNode(Node node, ResolvedStructuralInterner interner) {
        return fromNode(node, false, interner, false);
    }

    /**
     * Freezes a resolved graph using the legacy BlueId-keyed interning contract.
     *
     * <p>New code should prefer {@link ResolvedReferenceCache#freezeResolved(Node)},
     * which interns by exact resolved structure and keeps provider verification
     * separate from graph sharing. This overload remains for binary compatibility
     * with clients compiled against the 3.0 API.</p>
     */
    @Deprecated
    public static FrozenNode fromResolvedNode(Node node, ResolvedReferenceInterner interner) {
        return fromLegacyResolvedNode(node, interner, false);
    }

    public static FrozenNode fromUncheckedCanonicalNode(Node node) {
        return fromNode(node, true, null, false);
    }

    /**
     * Reframes an authored canonical value for the construction mode of a target tree.
     *
     * <p>This is a structural immutable copy only: it does not resolve references,
     * inherit fields, or materialize an intermediate {@link Node}. It exists for
     * immutable patch values that must be applied to both canonical and resolved
     * snapshot trees.</p>
     *
     * @param authoredCanonicalValue a canonical authored value, never a resolved view
     * @param modeTemplate a node whose canonical/validation mode should be used
     */
    public static FrozenNode authoredValueInModeOf(FrozenNode authoredCanonicalValue,
                                                   FrozenNode modeTemplate) {
        FrozenNode source = Objects.requireNonNull(authoredCanonicalValue, "authoredCanonicalValue");
        FrozenNode template = Objects.requireNonNull(modeTemplate, "modeTemplate");
        if (!source.strictCanonical) {
            throw new IllegalArgumentException("Authored frozen values must be canonical");
        }
        if (source.strictCanonical == template.strictCanonical
                && source.strictBlueIdValidation == template.strictBlueIdValidation
                && source.constructionModeNormalized) {
            return source;
        }
        return source.copyInConstructionMode(template.strictCanonical,
                template.strictBlueIdValidation,
                false);
    }

    private static FrozenNode fromNode(Node node, boolean strictCanonical) {
        return fromNode(node, strictCanonical, null, true);
    }

    private FrozenNode copyInConstructionMode(boolean targetStrictCanonical,
                                              boolean targetStrictBlueIdValidation,
                                              boolean listElement) {
        List<FrozenNode> nextItems = null;
        if (items != null) {
            nextItems = new ArrayList<>(items.size());
            for (FrozenNode item : items) {
                nextItems.add(item.copyInConstructionMode(targetStrictCanonical,
                        targetStrictBlueIdValidation,
                        true));
            }
        }
        Map<String, FrozenNode> nextProperties = null;
        if (properties != null) {
            nextProperties = new LinkedHashMap<>();
            for (Map.Entry<String, FrozenNode> entry : properties.entrySet()) {
                nextProperties.put(entry.getKey(), entry.getValue().copyInConstructionMode(
                        targetStrictCanonical, targetStrictBlueIdValidation, false));
            }
        }
        return builder()
                .name(name)
                .description(description)
                .type(copyInConstructionMode(type, targetStrictCanonical, targetStrictBlueIdValidation, false))
                .itemType(copyInConstructionMode(itemType, targetStrictCanonical, targetStrictBlueIdValidation, false))
                .keyType(copyInConstructionMode(keyType, targetStrictCanonical, targetStrictBlueIdValidation, false))
                .valueType(copyInConstructionMode(valueType, targetStrictCanonical, targetStrictBlueIdValidation, false))
                .frozenValue(value)
                .items(nextItems)
                .properties(nextProperties)
                .contracts(copyInConstructionMode(contracts, targetStrictCanonical, targetStrictBlueIdValidation, false))
                .referenceBlueId(referenceBlueId)
                .schema(schema)
                .mergePolicy(mergePolicy)
                .previousBlueId(previousBlueId)
                .position(position)
                .blue(copyInConstructionMode(blue, targetStrictCanonical, targetStrictBlueIdValidation, false))
                .inlineValue(inlineValue)
                .strictCanonical(targetStrictCanonical)
                .strictBlueIdValidation(targetStrictBlueIdValidation)
                .previousAnchorContext(listElement)
                .build();
    }

    private static FrozenNode copyInConstructionMode(FrozenNode node,
                                                     boolean targetStrictCanonical,
                                                     boolean targetStrictBlueIdValidation,
                                                     boolean listElement) {
        return node == null ? null : node.copyInConstructionMode(
                targetStrictCanonical, targetStrictBlueIdValidation, listElement);
    }

    private static FrozenNode fromNode(Node node, boolean strictCanonical, ResolvedStructuralInterner interner) {
        return fromNode(node, strictCanonical, interner, strictCanonical);
    }

    private static FrozenNode fromNode(Node node, boolean strictCanonical, ResolvedStructuralInterner interner, boolean strictBlueIdValidation) {
        return fromNode(node, strictCanonical, interner, strictBlueIdValidation, false);
    }

    private static FrozenNode fromNode(Node node,
                                       boolean strictCanonical,
                                       ResolvedStructuralInterner interner,
                                       boolean strictBlueIdValidation,
                                       boolean previousAnchorContext) {
        Objects.requireNonNull(node, "node");
        FrozenNode frozen = builder()
                .name(node.getName())
                .description(node.getDescription())
                .type(node.getType() != null ? fromNode(node.getType(), strictCanonical, interner, strictBlueIdValidation) : null)
                .itemType(node.getItemType() != null ? fromNode(node.getItemType(), strictCanonical, interner, strictBlueIdValidation) : null)
                .keyType(node.getKeyType() != null ? fromNode(node.getKeyType(), strictCanonical, interner, strictBlueIdValidation) : null)
                .valueType(node.getValueType() != null ? fromNode(node.getValueType(), strictCanonical, interner, strictBlueIdValidation) : null)
                .value(node.getValue())
                .items(node.getItems() != null
                        ? freezeItems(node.getItems(), strictCanonical, interner, strictBlueIdValidation)
                        : null)
                .properties(freezeProperties(node.getProperties(), strictCanonical, interner, strictBlueIdValidation))
                .contracts(node.getContracts() != null ? fromNode(node.getContracts(), strictCanonical, interner, strictBlueIdValidation) : null)
                .referenceBlueId(node.getBlueId())
                .schema(node.getSchema())
                .mergePolicy(node.getMergePolicy())
                .previousBlueId(node.getPreviousBlueId())
                .position(node.getPosition())
                .blue(node.getBlue() != null ? fromNode(node.getBlue(), strictCanonical, interner, strictBlueIdValidation) : null)
                .inlineValue(node.isInlineValue())
                .strictCanonical(strictCanonical)
                .strictBlueIdValidation(strictBlueIdValidation)
                .previousAnchorContext(previousAnchorContext)
                .build();
        if (!strictCanonical && interner != null) {
            return interner.intern(frozen.resolvedStructuralKey(), frozen);
        }
        return frozen;
    }

    private static FrozenNode fromLegacyResolvedNode(Node node,
                                                     ResolvedReferenceInterner interner,
                                                     boolean previousAnchorContext) {
        Objects.requireNonNull(node, "node");
        if (interner != null && node.getBlueId() != null) {
            FrozenNode cached = interner.lookup(node.getBlueId());
            if (cached != null) {
                return cached;
            }
        }
        FrozenNode frozen = builder()
                .name(node.getName())
                .description(node.getDescription())
                .type(node.getType() != null
                        ? fromLegacyResolvedNode(node.getType(), interner, false)
                        : null)
                .itemType(node.getItemType() != null
                        ? fromLegacyResolvedNode(node.getItemType(), interner, false)
                        : null)
                .keyType(node.getKeyType() != null
                        ? fromLegacyResolvedNode(node.getKeyType(), interner, false)
                        : null)
                .valueType(node.getValueType() != null
                        ? fromLegacyResolvedNode(node.getValueType(), interner, false)
                        : null)
                .value(node.getValue())
                .items(freezeLegacyResolvedItems(node.getItems(), interner))
                .properties(freezeLegacyResolvedProperties(node.getProperties(), interner))
                .contracts(node.getContracts() != null
                        ? fromLegacyResolvedNode(node.getContracts(), interner, false)
                        : null)
                .referenceBlueId(node.getBlueId())
                .schema(node.getSchema())
                .mergePolicy(node.getMergePolicy())
                .previousBlueId(node.getPreviousBlueId())
                .position(node.getPosition())
                .blue(node.getBlue() != null
                        ? fromLegacyResolvedNode(node.getBlue(), interner, false)
                        : null)
                .inlineValue(node.isInlineValue())
                .strictCanonical(false)
                .strictBlueIdValidation(false)
                .previousAnchorContext(previousAnchorContext)
                .build();
        if (interner != null && node.getBlueId() != null && !node.isReferenceOnly()) {
            return interner.intern(node.getBlueId(), frozen);
        }
        return frozen;
    }

    private static List<FrozenNode> freezeLegacyResolvedItems(
            List<Node> source,
            ResolvedReferenceInterner interner) {
        if (source == null) {
            return null;
        }
        List<FrozenNode> result = new ArrayList<>(source.size());
        for (Node item : source) {
            result.add(fromLegacyResolvedNode(item, interner, true));
        }
        return result;
    }

    private static Map<String, FrozenNode> freezeLegacyResolvedProperties(
            Map<String, Node> source,
            ResolvedReferenceInterner interner) {
        if (source == null || source.isEmpty()) {
            return null;
        }
        Map<String, FrozenNode> result = new LinkedHashMap<>();
        for (Map.Entry<String, Node> entry : source.entrySet()) {
            result.put(entry.getKey(),
                    fromLegacyResolvedNode(entry.getValue(), interner, false));
        }
        return result;
    }

    public ResolvedStructuralKey resolvedStructuralKey() {
        ResolvedStructuralKey key = resolvedStructuralKey;
        if (key == null) {
            synchronized (this) {
                key = resolvedStructuralKey;
                if (key == null) {
                    key = new ResolvedStructuralKey(this);
                    resolvedStructuralKey = key;
                }
            }
        }
        return key;
    }

    private static List<FrozenNode> freezeItems(List<Node> source,
                                                boolean strictCanonical,
                                                ResolvedStructuralInterner interner,
                                                boolean strictBlueIdValidation) {
        List<FrozenNode> result = new ArrayList<>(source.size());
        for (Node item : source) {
            result.add(fromNode(item, strictCanonical, interner, strictBlueIdValidation, true));
        }
        return result;
    }

    public static List<FrozenNode> fromNodes(List<Node> nodes) {
        if (nodes == null) {
            return null;
        }
        return Collections.unmodifiableList(nodes.stream()
                .map(FrozenNode::fromNode)
                .collect(Collectors.toList()));
    }

    private static Map<String, FrozenNode> freezeProperties(Map<String, Node> source,
                                                            boolean strictCanonical,
                                                            ResolvedStructuralInterner interner,
                                                            boolean strictBlueIdValidation) {
        if (source == null || source.isEmpty()) {
            return null;
        }
        Map<String, FrozenNode> result = new LinkedHashMap<>();
        for (Map.Entry<String, Node> entry : source.entrySet()) {
            FrozenNode child = fromNode(entry.getValue(), strictCanonical, interner, strictBlueIdValidation);
            if (strictCanonical && child.isEmptyNode()) {
                continue;
            }
            result.put(entry.getKey(), child);
        }
        return result.isEmpty() ? null : result;
    }

    public static String calculateBlueId(List<FrozenNode> nodes) {
        return FrozenCanonicalDigester.calculateBlueId(nodes);
    }

    /**
     * Compares the exact resolved graph content of two frozen nodes without
     * materializing mutable {@link Node} graphs first.
     *
     * <p>Construction-mode fields and object-property insertion order are
     * intentionally ignored. Object payloads are keyed maps in the Language
     * model, while list-element order remains significant. This comparison is
     * therefore stricter than semantic BlueId equality but may be less strict
     * than {@link #resolvedStructuralKey()}, which preserves representation
     * details needed by the structural interner.</p>
     */
    public boolean sameResolvedStructure(FrozenNode other) {
        if (this == other) {
            return true;
        }
        if (other == null
                || !Objects.equals(name, other.name)
                || !Objects.equals(description, other.description)
                || !sameResolvedStructure(type, other.type)
                || !sameResolvedStructure(itemType, other.itemType)
                || !sameResolvedStructure(keyType, other.keyType)
                || !sameResolvedStructure(valueType, other.valueType)
                || !Objects.equals(ResolvedStructuralKey.valueKeyOf(value),
                ResolvedStructuralKey.valueKeyOf(other.value))
                || !sameResolvedItems(items, other.items)
                || !sameResolvedProperties(properties, other.properties)
                || !sameResolvedStructure(contracts, other.contracts)
                || !Objects.equals(referenceBlueId, other.referenceBlueId)
                || !sameSchema(schema, other.schema)
                || !Objects.equals(mergePolicy, other.mergePolicy)
                || !Objects.equals(previousBlueId, other.previousBlueId)
                || !Objects.equals(position, other.position)
                || !sameResolvedStructure(blue, other.blue)) {
            return false;
        }
        // inlineValue records construction/serialization form only. It is
        // normalized away by resolution and is not part of resolved semantic
        // structure (unlike list order and the keyed object content above).
        return true;
    }

    private static boolean sameResolvedStructure(FrozenNode left, FrozenNode right) {
        return left == right || left != null && left.sameResolvedStructure(right);
    }

    private static boolean sameResolvedItems(List<FrozenNode> left, List<FrozenNode> right) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null || left.size() != right.size()) {
            return false;
        }
        for (int index = 0; index < left.size(); index++) {
            if (!sameResolvedStructure(left.get(index), right.get(index))) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameResolvedProperties(Map<String, FrozenNode> left,
                                                  Map<String, FrozenNode> right) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null || left.size() != right.size()) {
            return false;
        }
        for (Map.Entry<String, FrozenNode> leftEntry : left.entrySet()) {
            if (!right.containsKey(leftEntry.getKey())
                    || !sameResolvedStructure(
                    leftEntry.getValue(), right.get(leftEntry.getKey()))) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameSchema(Schema left, Schema right) {
        if (left == right) {
            return true;
        }
        return left != null && right != null
                && Objects.equals(
                ResolvedStructuralKey.valueKeyOf(schemaObject(left)),
                ResolvedStructuralKey.valueKeyOf(schemaObject(right)));
    }

    public Node toNode() {
        Node node = new Node()
                .name(name)
                .description(description)
                .type(type != null ? type.toNode() : null)
                .itemType(itemType != null ? itemType.toNode() : null)
                .keyType(keyType != null ? keyType.toNode() : null)
                .valueType(valueType != null ? valueType.toNode() : null)
                .value(mutableValueCopy(value))
                .blueId(referenceBlueId)
                .schema(schema != null ? schema.clone() : null)
                .mergePolicy(mergePolicy)
                .previousBlueId(previousBlueId)
                .position(position)
                .blue(blue != null ? blue.toNode() : null)
                .contracts(contracts != null ? contracts.toNode() : null)
                .inlineValue(inlineValue);
        if (items != null) {
            node.items(items.stream().map(FrozenNode::toNode).collect(Collectors.toList()));
        }
        if (properties != null) {
            node.properties(properties.entrySet().stream()
                    .collect(Collectors.toMap(
                            Map.Entry::getKey,
                            entry -> entry.getValue().toNode(),
                            (left, right) -> left,
                            LinkedHashMap::new)));
        }
        return node;
    }

    public String blueId() {
        String identity = blueId;
        if (identity == null) {
            synchronized (this) {
                identity = blueId;
                if (identity == null) {
                    identity = computeBlueId();
                    blueId = identity;
                }
            }
        }
        return identity;
    }

    public String getName() {
        return name;
    }

    public Object getValue() {
        return publicValueView(value);
    }

    /** Internal immutable value graph without compatibility-boundary copies. */
    Object frozenValue() {
        return value;
    }

    public String getDescription() {
        return description;
    }

    public FrozenNode getType() {
        return type;
    }

    public FrozenNode getItemType() {
        return itemType;
    }

    public FrozenNode getKeyType() {
        return keyType;
    }

    public FrozenNode getValueType() {
        return valueType;
    }

    public String getReferenceBlueId() {
        return referenceBlueId;
    }

    public FrozenNode getBlue() {
        return blue;
    }

    public Schema getSchema() {
        return schema != null ? schema.clone() : null;
    }

    /**
     * Read-only package view used by frozen-native algorithms. The stored
     * schema is an owned clone and callers in this package must never mutate it.
     */
    Schema frozenSchemaView() {
        return schema;
    }

    /**
     * Returns a conservative allocation-light retained-weight estimate for
     * this immutable graph. The estimate is intended for cache admission and
     * eviction, not heap-accounting assertions; it never materializes a
     * {@link Node} or computes an identity.
     */
    public long approximateRetainedWeightBytes() {
        return approximateRetainedWeightBytesOf(this);
    }

    /**
     * Estimates only this node and its directly owned containers/keys. Child
     * nodes are deliberately excluded so caches that weigh each interned node
     * independently do not multiply-count shared descendants.
     */
    public long approximateShallowRetainedWeightBytes() {
        IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
        seen.put(this, Boolean.TRUE);
        long weight = 112L;
        weight += retainedString(name, seen);
        weight += retainedString(description, seen);
        weight += retainedValue(value, seen);
        weight += retainedString(referenceBlueId, seen);
        weight += retainedString(mergePolicy, seen);
        weight += retainedString(previousBlueId, seen);
        weight += retainedString(blueId, seen);
        if (items != null) weight += 32L + 8L * items.size();
        if (properties != null) {
            weight += 64L + 40L * properties.size();
            for (String key : properties.keySet()) weight += retainedString(key, seen);
        }
        weight += retainedSchema(schema, seen);
        weight += retainedShallowStructuralKey(resolvedStructuralKey, seen);
        return weight;
    }

    /**
     * Estimates multiple roots as one graph, deduplicating structurally shared
     * frozen nodes and other shared objects by reference identity.
     */
    public static long approximateRetainedWeightBytesOf(FrozenNode... roots) {
        IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
        long weight = 0L;
        if (roots != null) {
            for (FrozenNode root : roots) {
                weight += retainedWeight(root, seen);
            }
        }
        return weight;
    }

    private static long retainedWeight(FrozenNode node,
                                       IdentityHashMap<Object, Boolean> seen) {
        if (node == null || seen.put(node, Boolean.TRUE) != null) {
            return 0L;
        }
        // Object header plus references/booleans, rounded conservatively for
        // the Java 8 compressed-oops layout used by supported runtimes.
        long weight = 112L;
        weight += retainedString(node.name, seen);
        weight += retainedString(node.description, seen);
        weight += retainedValue(node.value, seen);
        weight += retainedString(node.referenceBlueId, seen);
        weight += retainedString(node.mergePolicy, seen);
        weight += retainedString(node.previousBlueId, seen);
        weight += retainedWeight(node.type, seen);
        weight += retainedWeight(node.itemType, seen);
        weight += retainedWeight(node.keyType, seen);
        weight += retainedWeight(node.valueType, seen);
        weight += retainedWeight(node.contracts, seen);
        weight += retainedWeight(node.blue, seen);
        if (node.items != null && seen.put(node.items, Boolean.TRUE) == null) {
            weight += 32L + 8L * node.items.size();
            for (FrozenNode item : node.items) weight += retainedWeight(item, seen);
        }
        if (node.properties != null && seen.put(node.properties, Boolean.TRUE) == null) {
            weight += 64L + 40L * node.properties.size();
            for (Map.Entry<String, FrozenNode> entry : node.properties.entrySet()) {
                weight += retainedString(entry.getKey(), seen);
                weight += retainedWeight(entry.getValue(), seen);
            }
        }
        weight += retainedSchema(node.schema, seen);
        weight += retainedString(node.blueId, seen);
        weight += retainedStructuralObject(node.resolvedStructuralKey, seen);
        return weight;
    }

    private static long retainedSchema(Schema schema,
                                       IdentityHashMap<Object, Boolean> seen) {
        if (schema == null || seen.put(schema, Boolean.TRUE) != null) {
            return 0L;
        }
        long weight = 80L;
        weight += retainedMutableNode(schema.getRequired(), seen);
        weight += retainedMutableNode(schema.getMinLength(), seen);
        weight += retainedMutableNode(schema.getMaxLength(), seen);
        weight += retainedMutableNode(schema.getMinimum(), seen);
        weight += retainedMutableNode(schema.getMaximum(), seen);
        weight += retainedMutableNode(schema.getExclusiveMinimum(), seen);
        weight += retainedMutableNode(schema.getExclusiveMaximum(), seen);
        weight += retainedMutableNode(schema.getMultipleOf(), seen);
        weight += retainedMutableNode(schema.getMinItems(), seen);
        weight += retainedMutableNode(schema.getMaxItems(), seen);
        weight += retainedMutableNode(schema.getUniqueItems(), seen);
        weight += retainedMutableNode(schema.getMinFields(), seen);
        weight += retainedMutableNode(schema.getMaxFields(), seen);
        if (schema.getEnum() != null && seen.put(schema.getEnum(), Boolean.TRUE) == null) {
            weight += 32L + 8L * schema.getEnum().size();
            for (Node value : schema.getEnum()) weight += retainedMutableNode(value, seen);
        }
        return weight;
    }

    private static long retainedMutableNode(Node node,
                                            IdentityHashMap<Object, Boolean> seen) {
        if (node == null || seen.put(node, Boolean.TRUE) != null) {
            return 0L;
        }
        long weight = 104L;
        weight += retainedString(node.getName(), seen);
        weight += retainedString(node.getDescription(), seen);
        weight += retainedValue(node.getRawValue(), seen);
        weight += retainedString(node.getBlueId(), seen);
        weight += retainedString(node.getMergePolicy(), seen);
        weight += retainedString(node.getPreviousBlueId(), seen);
        weight += retainedMutableNode(node.getType(), seen);
        weight += retainedMutableNode(node.getItemType(), seen);
        weight += retainedMutableNode(node.getKeyType(), seen);
        weight += retainedMutableNode(node.getValueType(), seen);
        weight += retainedMutableNode(node.getContracts(), seen);
        weight += retainedMutableNode(node.getBlue(), seen);
        if (node.getItems() != null && seen.put(node.getItems(), Boolean.TRUE) == null) {
            weight += 32L + 8L * node.getItems().size();
            for (Node item : node.getItems()) weight += retainedMutableNode(item, seen);
        }
        if (node.getProperties() != null
                && seen.put(node.getProperties(), Boolean.TRUE) == null) {
            weight += 64L + 40L * node.getProperties().size();
            for (Map.Entry<String, Node> entry : node.getProperties().entrySet()) {
                weight += retainedString(entry.getKey(), seen);
                weight += retainedMutableNode(entry.getValue(), seen);
            }
        }
        weight += retainedSchema(node.getSchema(), seen);
        return weight;
    }

    private static long retainedValue(Object value,
                                      IdentityHashMap<Object, Boolean> seen) {
        if (value == null) return 0L;
        if (value instanceof String) return retainedString((String) value, seen);
        if (seen.put(value, Boolean.TRUE) != null) return 0L;
        if (value instanceof BigInteger) {
            return 48L + 4L * ((((BigInteger) value).abs().bitLength() + 31L) / 32L);
        }
        if (value instanceof java.math.BigDecimal) {
            java.math.BigDecimal decimal = (java.math.BigDecimal) value;
            return 64L + retainedValue(decimal.unscaledValue(), seen);
        }
        if (value instanceof Boolean) return 16L;
        if (value instanceof Number) return 24L;
        if (value instanceof List) {
            List<?> values = (List<?>) value;
            long weight = 32L + 8L * values.size();
            for (Object item : values) weight += retainedValue(item, seen);
            return weight;
        }
        if (value instanceof Map) {
            Map<?, ?> values = (Map<?, ?>) value;
            long weight = 64L + 40L * values.size();
            for (Map.Entry<?, ?> entry : values.entrySet()) {
                weight += entry.getKey() instanceof String
                        ? retainedString((String) entry.getKey(), seen)
                        : retainedStructuralObject(entry.getKey(), seen);
                weight += retainedValue(entry.getValue(), seen);
            }
            return weight;
        }
        if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            long weight = 24L + 8L * length;
            for (int index = 0; index < length; index++) {
                weight += retainedValue(Array.get(value, index), seen);
            }
            return weight;
        }
        return 48L;
    }

    private static long retainedString(String value,
                                       IdentityHashMap<Object, Boolean> seen) {
        if (value == null || seen.put(value, Boolean.TRUE) != null) return 0L;
        return 48L + 2L * value.length();
    }

    private static long retainedStructuralObject(Object value,
                                                 IdentityHashMap<Object, Boolean> seen) {
        if (value == null) return 0L;
        if (value instanceof String) return retainedString((String) value, seen);
        if (value instanceof Number || value instanceof Boolean) {
            return retainedValue(value, seen);
        }
        if (seen.put(value, Boolean.TRUE) != null) return 0L;
        if (value instanceof ResolvedStructuralKey) {
            ResolvedStructuralKey key = (ResolvedStructuralKey) value;
            return 32L + retainedStructuralObject(key.fields, seen);
        }
        if (value instanceof PropertyKey) {
            PropertyKey key = (PropertyKey) value;
            return 24L
                    + retainedString(key.name, seen)
                    + retainedStructuralObject(key.value, seen);
        }
        if (value instanceof List) {
            List<?> values = (List<?>) value;
            long weight = 32L + 8L * values.size();
            for (Object item : values) {
                weight += retainedStructuralObject(item, seen);
            }
            return weight;
        }
        if (value instanceof Map) {
            Map<?, ?> values = (Map<?, ?>) value;
            long weight = 64L + 40L * values.size();
            for (Map.Entry<?, ?> entry : values.entrySet()) {
                weight += retainedStructuralObject(entry.getKey(), seen);
                weight += retainedStructuralObject(entry.getValue(), seen);
            }
            return weight;
        }
        return 48L;
    }

    /**
     * Weighs only the containers owned directly by this node's structural key.
     * Child structural keys are references to separately interned entries and
     * must not be recursively charged once per ancestor.
     */
    private static long retainedShallowStructuralKey(
            ResolvedStructuralKey key,
            IdentityHashMap<Object, Boolean> seen) {
        if (key == null || seen.put(key, Boolean.TRUE) != null) {
            return 0L;
        }
        long weight = 32L;
        if (seen.put(key.fields, Boolean.TRUE) != null) {
            return weight;
        }
        weight += 32L + 8L * key.fields.size();
        for (int index = 0; index < key.fields.size(); index++) {
            Object field = key.fields.get(index);
            if (field instanceof ResolvedStructuralKey) {
                continue;
            }
            if (index == 7) {
                weight += retainedChildKeyList(field, seen);
            } else if (index == 8) {
                weight += retainedPropertyKeyList(field, seen);
            } else {
                weight += retainedStructuralObject(field, seen);
            }
        }
        return weight;
    }

    private static long retainedChildKeyList(Object field,
                                             IdentityHashMap<Object, Boolean> seen) {
        if (!(field instanceof List) || seen.put(field, Boolean.TRUE) != null) {
            return 0L;
        }
        return 32L + 8L * ((List<?>) field).size();
    }

    private static long retainedPropertyKeyList(Object field,
                                                IdentityHashMap<Object, Boolean> seen) {
        if (!(field instanceof List) || seen.put(field, Boolean.TRUE) != null) {
            return 0L;
        }
        List<?> properties = (List<?>) field;
        long weight = 32L + 8L * properties.size();
        for (Object value : properties) {
            if (!(value instanceof PropertyKey) || seen.put(value, Boolean.TRUE) != null) {
                continue;
            }
            PropertyKey property = (PropertyKey) value;
            weight += 24L + retainedString(property.name, seen);
        }
        return weight;
    }

    public String getMergePolicy() {
        return mergePolicy;
    }

    public String getPreviousBlueId() {
        return previousBlueId;
    }

    public Integer getPosition() {
        return position;
    }

    public boolean isInlineValue() {
        return inlineValue;
    }

    public List<FrozenNode> getItems() {
        return items;
    }

    public Map<String, FrozenNode> getProperties() {
        return properties;
    }

    public FrozenNode getContracts() {
        return contracts;
    }

    public FrozenNode property(String key) {
        if (OBJECT_CONTRACTS.equals(key)) {
            return contracts;
        }
        return properties != null ? properties.get(key) : null;
    }

    public FrozenNode item(int index) {
        if (items == null || index < 0 || index >= items.size()) {
            return null;
        }
        return items.get(index);
    }

    public FrozenNode at(String pointer) {
        List<String> segments = JsonPointer.split(pointer);
        return at(segments);
    }

    public FrozenNode at(List<String> pointerSegments) {
        List<String> segments = pointerSegments != null ? pointerSegments : Collections.emptyList();
        if (segments.isEmpty()) {
            return this;
        }
        FrozenNode current = this;
        for (String segment : segments) {
            if (current == null) {
                return null;
            }
            if (current.items != null && !OBJECT_CONTRACTS.equals(segment)) {
                current = current.item(parseArrayIndex(segment));
            } else {
                current = current.property(segment);
            }
        }
        return current;
    }

    public Map<String, FrozenNode> pathIndex() {
        Map<String, FrozenNode> index = new LinkedHashMap<>();
        indexPaths("/", index);
        return Collections.unmodifiableMap(index);
    }

    public boolean hasItems() {
        return items != null;
    }

    public boolean hasProperties() {
        return properties != null;
    }

    public boolean isReferenceOnly() {
        return referenceBlueId != null
                && name == null
                && description == null
                && type == null
                && itemType == null
                && keyType == null
                && valueType == null
                && value == null
                && items == null
                && properties == null
                && contracts == null
                && schema == null
                && mergePolicy == null
                && previousBlueId == null
                && position == null
                && blue == null;
    }

    public boolean isPreviousOnly() {
        return previousBlueId != null
                && name == null
                && description == null
                && type == null
                && itemType == null
                && keyType == null
                && valueType == null
                && value == null
                && items == null
                && properties == null
                && contracts == null
                && schema == null
                && mergePolicy == null
                && position == null
                && blue == null
                && referenceBlueId == null;
    }

    public boolean isStrictCanonical() {
        return strictCanonical;
    }

    public boolean isStrictBlueIdValidation() {
        return strictBlueIdValidation;
    }

    public boolean containsCyclicSetReference() {
        return containsCyclicSetReference;
    }

    public boolean containsSchema() {
        return containsSchema;
    }

    public boolean containsNestedTypedObjectPayload() {
        return containsNestedTypedObjectPayload;
    }

    boolean isListElementContext() {
        return previousAnchorContext;
    }

    boolean isConstructionModeNormalized() {
        return constructionModeNormalized;
    }

    public boolean isEmptyNode() {
        return name == null
                && description == null
                && type == null
                && itemType == null
                && keyType == null
                && valueType == null
                && value == null
                && items == null
                && properties == null
                && contracts == null
                && referenceBlueId == null
                && schema == null
                && mergePolicy == null
                && previousBlueId == null
                && position == null
                && blue == null;
    }

    public FrozenNode withProperty(String key, FrozenNode child) {
        return withProperty(key, child, false);
    }

    FrozenNode withPropertyForPatch(String key, FrozenNode child) {
        return withProperty(key, child, true);
    }

    private FrozenNode withProperty(String key, FrozenNode child, boolean deferBlueId) {
        if (OBJECT_CONTRACTS.equals(key)) {
            Builder next = toBuilder()
                    .contracts(child == null || (strictCanonical && child.isEmptyNode()) ? null : child);
            return (deferBlueId ? next.deferBlueId() : next).build();
        }
        Map<String, FrozenNode> next = properties != null
                ? new LinkedHashMap<>(properties)
                : new LinkedHashMap<>();
        if (child == null || (strictCanonical && child.isEmptyNode())) {
            next.remove(key);
        } else {
            next.put(key, child);
        }
        Builder builder = toBuilder().properties(next.isEmpty() ? null : next);
        return (deferBlueId ? builder.deferBlueId() : builder).build();
    }

    public FrozenNode withItems(List<FrozenNode> nextItems) {
        return toBuilder().items(nextItems).build();
    }

    FrozenNode withItemsForPatch(List<FrozenNode> nextItems) {
        return toBuilder().items(nextItems).deferBlueId().build();
    }

    /**
     * Applies a non-null object overlay while retaining unchanged frozen
     * children. Non-object replacements are returned unchanged.
     */
    public FrozenNode overlayObject(FrozenNode overlay) {
        return overlayObject(overlay, false);
    }

    FrozenNode overlayObjectForPatch(FrozenNode overlay) {
        return overlayObject(overlay, true);
    }

    private FrozenNode overlayObject(FrozenNode overlay, boolean deferBlueId) {
        if (!isMergeableObject(this) || !isMergeableObject(overlay)) {
            return overlay;
        }

        Builder merged = toBuilder();
        if (overlay.properties != null) {
            Map<String, FrozenNode> nextProperties = properties != null
                    ? new LinkedHashMap<>(properties)
                    : new LinkedHashMap<>();
            nextProperties.putAll(overlay.properties);
            merged.properties(nextProperties);
        }
        if (overlay.contracts != null) merged.contracts(overlay.contracts);
        if (overlay.type != null) merged.type(overlay.type);
        if (overlay.itemType != null) merged.itemType(overlay.itemType);
        if (overlay.keyType != null) merged.keyType(overlay.keyType);
        if (overlay.valueType != null) merged.valueType(overlay.valueType);
        if (overlay.blue != null) merged.blue(overlay.blue);
        if (overlay.schema != null) merged.schema(overlay.schema);
        if (overlay.name != null) merged.name(overlay.name);
        if (overlay.description != null) merged.description(overlay.description);
        if (overlay.mergePolicy != null) merged.mergePolicy(overlay.mergePolicy);
        if (overlay.previousBlueId != null) merged.previousBlueId(overlay.previousBlueId);
        if (overlay.position != null) merged.position(overlay.position);
        return (deferBlueId ? merged.deferBlueId() : merged).build();
    }

    public FrozenNode withoutPosition() {
        if (position == null) {
            return this;
        }
        return toBuilder().position(null).build();
    }

    private void validatePayloadShape() {
        int payloadKinds = 0;
        if (value != null) payloadKinds++;
        if (items != null) payloadKinds++;
        if (properties != null && !properties.isEmpty()) payloadKinds++;
        if (payloadKinds > 1) {
            throw new IllegalArgumentException("A Blue node may contain only one payload kind: value, items, or object fields.");
        }
        if (strictCanonical && referenceBlueId != null && !isReferenceOnly()) {
            throw new IllegalArgumentException("\"blueId\" nodes must be reference-only and cannot contain sibling fields.");
        }
        if (strictCanonical && previousBlueId != null) {
            if (!isPreviousOnly()) {
                throw new IllegalArgumentException("\"$previous\" list anchors must be single-key list items.");
            }
            if (!previousAnchorContext) {
                throw new IllegalArgumentException("\"$previous\" is valid only as the first list item in direct BlueId input.");
            }
        }
        if (strictCanonical && blue != null) {
            throw new IllegalArgumentException("\"blue\" is a preprocessing directive and must not appear in canonical BlueId input.");
        }
        if (strictCanonical && position != null) {
            throw new IllegalArgumentException("\"$pos\" overlays are not valid direct BlueId input.");
        }
    }

    private boolean computeConstructionModeNormalized() {
        if (!hasNormalizedChild(type, false)
                || !hasNormalizedChild(itemType, false)
                || !hasNormalizedChild(keyType, false)
                || !hasNormalizedChild(valueType, false)
                || !hasNormalizedChild(contracts, false)
                || !hasNormalizedChild(blue, false)) {
            return false;
        }
        if (items != null) {
            for (FrozenNode item : items) {
                if (!hasNormalizedChild(item, true)) {
                    return false;
                }
            }
        }
        if (properties != null) {
            for (FrozenNode property : properties.values()) {
                if (!hasNormalizedChild(property, false)) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean hasNormalizedChild(FrozenNode child, boolean listElement) {
        return child == null
                || child.strictCanonical == strictCanonical
                && child.strictBlueIdValidation == strictBlueIdValidation
                && child.previousAnchorContext == listElement
                && child.constructionModeNormalized;
    }

    private void indexPaths(String path, Map<String, FrozenNode> index) {
        index.put(path, this);
        if (items != null) {
            for (int i = 0; i < items.size(); i++) {
                items.get(i).indexPaths(JsonPointer.append(path, String.valueOf(i)), index);
            }
        }
        if (properties != null) {
            properties.forEach((key, child) -> child.indexPaths(JsonPointer.append(path, key), index));
        }
        if (contracts != null) {
            contracts.indexPaths(JsonPointer.append(path, OBJECT_CONTRACTS), index);
        }
    }

    private int parseArrayIndex(String segment) {
        try {
            int index = Integer.parseInt(segment);
            return index >= 0 ? index : -1;
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    private Builder toBuilder() {
        return builder()
                .name(name)
                .description(description)
                .type(type)
                .itemType(itemType)
                .keyType(keyType)
                .valueType(valueType)
                .frozenValue(value)
                .items(items)
                .properties(properties)
                .contracts(contracts)
                .referenceBlueId(referenceBlueId)
                .schema(schema)
                .mergePolicy(mergePolicy)
                .previousBlueId(previousBlueId)
                .position(position)
                .blue(blue)
                .inlineValue(inlineValue)
                .strictCanonical(strictCanonical)
                .strictBlueIdValidation(strictBlueIdValidation)
                .previousAnchorContext(previousAnchorContext);
    }

    private String computeBlueId() {
        if (strictCanonical) {
            if (!strictBlueIdValidation) {
                return BlueIdCalculator.calculateUncheckedBlueId(toNode());
            }
            return FrozenCanonicalDigester.calculateBlueId(this);
        }
        return computeResolvedStructuralBlueId();
    }

    private boolean isPayloadOnlyList() {
        return items != null
                && name == null
                && description == null
                && type == null
                && itemType == null
                && keyType == null
                && valueType == null
                && value == null
                && properties == null
                && contracts == null
                && referenceBlueId == null
                && schema == null
                && mergePolicy == null
                && previousBlueId == null
                && position == null
                && blue == null;
    }

    private static boolean canFoldCachedListBlueIds(List<FrozenNode> nodes) {
        for (int index = 0; index < nodes.size(); index++) {
            FrozenNode node = nodes.get(index);
            if (node == null
                    || !node.strictCanonical
                    || !node.strictBlueIdValidation
                    || node.isEmptyNode()) {
                return false;
            }
            if (node.properties != null && node.properties.containsKey(LIST_CONTROL_EMPTY)
                    && !isEmptyPlaceholder(node)) {
                return false;
            }
            if (node.previousBlueId != null
                    && (index != 0 || !node.isPreviousOnly())) {
                return false;
            }
        }
        return true;
    }

    private static String foldCachedListBlueIds(List<FrozenNode> nodes) {
        String accumulator = HASH.apply(Collections.singletonMap("$list", "empty"));
        int start = 0;
        if (!nodes.isEmpty() && nodes.get(0).isPreviousOnly()) {
            accumulator = nodes.get(0).previousBlueId;
            start = 1;
        }
        for (int index = start; index < nodes.size(); index++) {
            FrozenNode node = nodes.get(index);
            String elementBlueId = isEmptyPlaceholder(node)
                    ? BlueIdCalculator.INSTANCE.calculate(
                            Collections.<String, Object>singletonMap(LIST_CONTROL_EMPTY, true))
                    : node.blueId();
            Map<String, Object> cons = new TreeMap<>(String::compareTo);
            cons.put("elem", reference(elementBlueId));
            cons.put("prev", reference(accumulator));
            accumulator = HASH.apply(Collections.singletonMap("$listCons", cons));
        }
        return accumulator;
    }

    private static boolean isEmptyPlaceholder(FrozenNode node) {
        if (node == null || node.properties == null || node.properties.size() != 1) {
            return false;
        }
        FrozenNode marker = node.properties.get(LIST_CONTROL_EMPTY);
        return marker != null
                && Boolean.TRUE.equals(marker.value)
                && marker.name == null
                && marker.description == null
                && marker.type == null
                && marker.itemType == null
                && marker.keyType == null
                && marker.valueType == null
                && marker.items == null
                && marker.properties == null
                && marker.contracts == null
                && marker.referenceBlueId == null
                && marker.schema == null
                && marker.mergePolicy == null
                && marker.previousBlueId == null
                && marker.position == null
                && marker.blue == null
                && node.name == null
                && node.description == null
                && node.type == null
                && node.itemType == null
                && node.keyType == null
                && node.valueType == null
                && node.value == null
                && node.items == null
                && node.contracts == null
                && node.referenceBlueId == null
                && node.schema == null
                && node.mergePolicy == null
                && node.previousBlueId == null
                && node.position == null
                && node.blue == null;
    }

    private static boolean isMergeableObject(FrozenNode node) {
        return node != null
                && node.value == null
                && node.items == null
                && !node.isReferenceOnly()
                && node.previousBlueId == null;
    }

    private boolean computeContainsCyclicSetReference() {
        if (referenceBlueId != null && referenceBlueId.indexOf('#') >= 0) {
            return true;
        }
        if (containsCyclicSetReference(type)
                || containsCyclicSetReference(itemType)
                || containsCyclicSetReference(keyType)
                || containsCyclicSetReference(valueType)
                || containsCyclicSetReference(contracts)
                || containsCyclicSetReference(blue)) {
            return true;
        }
        if (items != null) {
            for (FrozenNode item : items) {
                if (containsCyclicSetReference(item)) {
                    return true;
                }
            }
        }
        if (properties != null) {
            for (FrozenNode property : properties.values()) {
                if (containsCyclicSetReference(property)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean containsCyclicSetReference(FrozenNode node) {
        return node != null && node.containsCyclicSetReference;
    }

    private boolean computeContainsSchema() {
        if (schema != null
                || containsSchema(type)
                || containsSchema(itemType)
                || containsSchema(keyType)
                || containsSchema(valueType)
                || containsSchema(contracts)
                || containsSchema(blue)) {
            return true;
        }
        if (items != null) {
            for (FrozenNode item : items) {
                if (containsSchema(item)) {
                    return true;
                }
            }
        }
        if (properties != null) {
            for (FrozenNode property : properties.values()) {
                if (containsSchema(property)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean containsSchema(FrozenNode node) {
        return node != null && node.containsSchema;
    }

    private boolean computeContainsNestedTypedObjectPayload() {
        if (properties == null) {
            return false;
        }
        for (FrozenNode property : properties.values()) {
            if ((property.type != null
                    && property.properties != null
                    && !property.properties.isEmpty())
                    || property.containsNestedTypedObjectPayload) {
                return true;
            }
        }
        return false;
    }

    private String computeResolvedStructuralBlueId() {
        if (isReferenceOnly()) {
            return referenceBlueId;
        }
        if (isPreviousOnly()) {
            Map<String, Object> previous = new TreeMap<>(String::compareTo);
            previous.put(LIST_CONTROL_PREVIOUS, reference(previousBlueId));
            return HASH.apply(previous);
        }

        Map<String, Object> hashes = new TreeMap<>(String::compareTo);
        putRaw(hashes, OBJECT_NAME, name);
        putRaw(hashes, OBJECT_DESCRIPTION, description);

        String valueTypeBlueId = null;
        if (value != null && type == null) {
            String inferredTypeBlueId = inferTypeBlueId(value);
            if (inferredTypeBlueId != null) {
                valueTypeBlueId = inferredTypeBlueId;
                putBlueId(hashes, OBJECT_TYPE, inferredTypeBlueId);
            }
        } else if (type != null) {
            valueTypeBlueId = type.referenceBlueId;
            putBlueId(hashes, OBJECT_TYPE, type.blueId());
        }

        putBlueId(hashes, OBJECT_ITEM_TYPE, itemType);
        putBlueId(hashes, OBJECT_KEY_TYPE, keyType);
        putBlueId(hashes, OBJECT_VALUE_TYPE, valueType);
        putHashedScalar(hashes, OBJECT_MERGE_POLICY, mergePolicy);
        putHashedScalar(hashes, LIST_CONTROL_POS, position != null ? BigInteger.valueOf(position) : null);
        putRaw(hashes, OBJECT_VALUE, handleValue(value, valueTypeBlueId));
        if (items != null) {
            putBlueId(hashes, OBJECT_ITEMS, computeListHash(items));
        }
        if (schema != null) {
            putBlueId(hashes, OBJECT_SCHEMA, BlueIdCalculator.INSTANCE.calculate(schemaObject(schema)));
        }
        putBlueId(hashes, OBJECT_CONTRACTS, contracts);
        putBlueId(hashes, OBJECT_BLUE, blue);
        if (properties != null) {
            properties.forEach((key, child) -> putBlueId(hashes, key, child));
        }
        return HASH.apply(hashes);
    }

    private static String computeListHash(List<FrozenNode> list) {
        return BlueIdCalculator.calculateBlueId(toBlueIdInputNodes(list));
    }

    private static List<Node> toBlueIdInputNodes(List<FrozenNode> list) {
        return (list == null ? Collections.<FrozenNode>emptyList() : list).stream()
                .map(FrozenNode::toNode)
                .map(NodeToBlueIdInput::stripResolvedBlueIdMetadata)
                .collect(Collectors.toList());
    }

    private static void putRaw(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    private static void putBlueId(Map<String, Object> target, String key, FrozenNode node) {
        if (node != null) {
            putBlueId(target, key, node.blueId());
        }
    }

    private static void putBlueId(Map<String, Object> target, String key, String blueId) {
        if (blueId != null) {
            target.put(key, reference(blueId));
        }
    }

    private static void putHashedScalar(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            putBlueId(target, key, HASH.apply(value));
        }
    }

    private static Map<String, Object> reference(String blueId) {
        return Collections.singletonMap(OBJECT_BLUE_ID, blueId);
    }

    private static Object handleValue(Object value, String valueTypeBlueId) {
        if (value == null) {
            return null;
        }
        if (DOUBLE_TYPE_BLUE_ID.equals(valueTypeBlueId)) {
            return BlueNumbers.toCanonicalDoubleValue(value);
        }
        if (value instanceof BigInteger) {
            BigInteger bigIntValue = (BigInteger) value;
            BigInteger lowerBound = BigInteger.valueOf(-9007199254740991L);
            BigInteger upperBound = BigInteger.valueOf(9007199254740991L);
            if (bigIntValue.compareTo(lowerBound) < 0 || bigIntValue.compareTo(upperBound) > 0) {
                return bigIntValue.toString();
            }
        }
        return value;
    }

    private static String inferTypeBlueId(Object value) {
        if (value instanceof String) {
            return TEXT_TYPE_BLUE_ID;
        } else if (value instanceof BigInteger) {
            return INTEGER_TYPE_BLUE_ID;
        } else if (value instanceof java.math.BigDecimal) {
            return DOUBLE_TYPE_BLUE_ID;
        } else if (value instanceof Boolean) {
            return BOOLEAN_TYPE_BLUE_ID;
        }
        return null;
    }

    private static Map<String, Object> schemaObject(Schema schema) {
        return SchemaToMapListOrValue.get(schema, NodeToMapListOrValue::get);
    }

    private static List<FrozenNode> freezeList(List<FrozenNode> source, boolean strictCanonical) {
        if (source == null) {
            return null;
        }
        List<FrozenNode> result = new ArrayList<>(source.size());
        for (int i = 0; i < source.size(); i++) {
            FrozenNode node = source.get(i);
            if (strictCanonical && node.isEmptyNode()) {
                throw new IllegalArgumentException("Direct BlueId input must use { \"$empty\": true } for empty list placeholders.");
            }
            if (strictCanonical && node.isPreviousOnly() && i != 0) {
                throw new IllegalArgumentException("\"$previous\" must appear only as the first list item.");
            }
            result.add(node);
        }
        return Collections.unmodifiableList(result);
    }

    private static Map<String, FrozenNode> freezeMap(Map<String, FrozenNode> source) {
        if (source == null || source.isEmpty()) {
            return null;
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    /**
     * Takes an owned immutable snapshot of a JSON value payload. Blue values are
     * JSON values, so accepting an arbitrary mutable Java object here would make
     * the frozen node's memoized identity and structural key stale after mutation.
     */
    private static Object freezeValue(Object source) {
        return freezeValue(source, new IdentityHashMap<Object, Boolean>());
    }

    private static Object freezeValue(Object source,
                                      IdentityHashMap<Object, Boolean> activeContainers) {
        if (source instanceof Float && !Float.isFinite((Float) source)
                || source instanceof Double && !Double.isFinite((Double) source)) {
            throw new IllegalArgumentException(
                    "Frozen node values must not contain non-finite numbers");
        }
        if (source == null || source instanceof String || source instanceof Boolean
                || source instanceof Character
                || source instanceof Enum
                || source instanceof BigInteger || source instanceof java.math.BigDecimal
                || source instanceof Byte || source instanceof Short
                || source instanceof Integer || source instanceof Long
                || source instanceof Float || source instanceof Double) {
            return source;
        }
        if (source instanceof List) {
            enterValueContainer(source, activeContainers);
            try {
                List<?> values = (List<?>) source;
                List<Object> snapshot = new ArrayList<>(values.size());
                for (Object value : values) {
                    snapshot.add(freezeValue(value, activeContainers));
                }
                return Collections.unmodifiableList(snapshot);
            } finally {
                activeContainers.remove(source);
            }
        }
        if (source instanceof Map) {
            enterValueContainer(source, activeContainers);
            try {
                Map<?, ?> values = (Map<?, ?>) source;
                Map<String, Object> snapshot = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : values.entrySet()) {
                    if (!(entry.getKey() instanceof String)) {
                        throw unsupportedValue(entry.getKey());
                    }
                    snapshot.put((String) entry.getKey(),
                            freezeValue(entry.getValue(), activeContainers));
                }
                return Collections.unmodifiableMap(snapshot);
            } finally {
                activeContainers.remove(source);
            }
        }
        if (source.getClass().isArray()) {
            enterValueContainer(source, activeContainers);
            try {
                int length = Array.getLength(source);
                Class<?> componentType = source.getClass().getComponentType();
                Object snapshot = Array.newInstance(componentType, length);
                if (componentType.isPrimitive()) {
                    if (componentType == float.class || componentType == double.class) {
                        for (int index = 0; index < length; index++) {
                            freezeValue(Array.get(source, index), activeContainers);
                        }
                    }
                    System.arraycopy(source, 0, snapshot, 0, length);
                    return snapshot;
                }
                for (int index = 0; index < length; index++) {
                    Object element = Array.get(source, index);
                    Object frozenElement = freezeValue(element, activeContainers);
                    if (frozenElement != null && !componentType.isInstance(frozenElement)) {
                        Object concreteElement = freezeConcreteArrayElement(
                                element, componentType, activeContainers);
                        if (concreteElement == null) {
                            Object[] fallback = new Object[length];
                            for (int copiedIndex = 0; copiedIndex < index; copiedIndex++) {
                                fallback[copiedIndex] = Array.get(snapshot, copiedIndex);
                            }
                            fallback[index] = frozenElement;
                            for (int remainingIndex = index + 1;
                                 remainingIndex < length;
                                 remainingIndex++) {
                                fallback[remainingIndex] = freezeValue(
                                        Array.get(source, remainingIndex), activeContainers);
                            }
                            return fallback;
                        }
                        frozenElement = concreteElement;
                    }
                    Array.set(snapshot, index, frozenElement);
                }
                return snapshot;
            } finally {
                activeContainers.remove(source);
            }
        }
        throw unsupportedValue(source);
    }

    private static Object freezeConcreteArrayElement(
            Object source,
            Class<?> componentType,
            IdentityHashMap<Object, Boolean> activeContainers) {
        if (source instanceof List) {
            List<?> values = (List<?>) source;
            List<Object> snapshot = mutableListLike(values);
            if (!componentType.isInstance(snapshot)) {
                return null;
            }
            enterValueContainer(source, activeContainers);
            try {
                for (Object value : values) {
                    snapshot.add(freezeValue(value, activeContainers));
                }
                return snapshot;
            } finally {
                activeContainers.remove(source);
            }
        }
        if (source instanceof Map) {
            Map<?, ?> values = (Map<?, ?>) source;
            Map<String, Object> snapshot = mutableMapLike(values);
            if (!componentType.isInstance(snapshot)) {
                return null;
            }
            enterValueContainer(source, activeContainers);
            try {
                for (Map.Entry<?, ?> entry : values.entrySet()) {
                    if (!(entry.getKey() instanceof String)) {
                        throw unsupportedValue(entry.getKey());
                    }
                    snapshot.put((String) entry.getKey(),
                            freezeValue(entry.getValue(), activeContainers));
                }
                return snapshot;
            } finally {
                activeContainers.remove(source);
            }
        }
        return null;
    }

    private static void enterValueContainer(Object source,
                                            IdentityHashMap<Object, Boolean> activeContainers) {
        if (activeContainers.put(source, Boolean.TRUE) != null) {
            throw new IllegalArgumentException("Frozen node values must not contain cycles");
        }
    }

    private static IllegalArgumentException unsupportedValue(Object value) {
        String type = value == null ? "null" : value.getClass().getName();
        return new IllegalArgumentException(
                "Frozen node values must contain only JSON-compatible values; found " + type);
    }

    /** Returns a detached mutable JSON graph for the mutable Node compatibility boundary. */
    private static Object mutableValueCopy(Object source) {
        if (source instanceof List) {
            List<?> values = (List<?>) source;
            List<Object> copy = mutableListLike(values);
            for (Object value : values) {
                copy.add(mutableValueCopy(value));
            }
            return copy;
        }
        if (source instanceof Map) {
            Map<?, ?> values = (Map<?, ?>) source;
            Map<String, Object> copy = mutableMapLike(values);
            for (Map.Entry<?, ?> entry : values.entrySet()) {
                copy.put((String) entry.getKey(), mutableValueCopy(entry.getValue()));
            }
            return copy;
        }
        if (source != null && source.getClass().isArray()) {
            int length = Array.getLength(source);
            Class<?> componentType = source.getClass().getComponentType();
            Object copy = Array.newInstance(componentType, length);
            if (componentType.isPrimitive()) {
                System.arraycopy(source, 0, copy, 0, length);
                return copy;
            }
            for (int index = 0; index < length; index++) {
                Array.set(copy, index, mutableValueCopy(Array.get(source, index)));
            }
            return copy;
        }
        return source;
    }

    private static List<Object> mutableListLike(List<?> source) {
        if (source instanceof LinkedList) {
            return new LinkedList<>();
        }
        return new ArrayList<>(source.size());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Map<String, Object> mutableMapLike(Map<?, ?> source) {
        if (source instanceof TreeMap) {
            return new TreeMap(((TreeMap) source).comparator());
        }
        if (source instanceof LinkedHashMap) {
            return new LinkedHashMap<>();
        }
        if (source instanceof HashMap) {
            return new HashMap<>();
        }
        return new LinkedHashMap<>();
    }

    private static Object publicValueView(Object source) {
        if (source instanceof List) {
            List<?> values = (List<?>) source;
            List<Object> copy = new ArrayList<>(values.size());
            for (Object value : values) {
                copy.add(publicValueView(value));
            }
            return Collections.unmodifiableList(copy);
        }
        if (source instanceof Map) {
            Map<?, ?> values = (Map<?, ?>) source;
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : values.entrySet()) {
                copy.put((String) entry.getKey(), publicValueView(entry.getValue()));
            }
            return Collections.unmodifiableMap(copy);
        }
        if (source != null && source.getClass().isArray()) {
            // Arrays cannot be made immutable while retaining their runtime type.
            // Return a fully detached mutable graph instead; mutations are harmless.
            return mutableValueCopy(source);
        }
        return source;
    }

    private static Builder builder() {
        return new Builder();
    }

    private static final class Builder {
        private String name;
        private String description;
        private FrozenNode type;
        private FrozenNode itemType;
        private FrozenNode keyType;
        private FrozenNode valueType;
        private Object nodeValue;
        private List<FrozenNode> items;
        private Map<String, FrozenNode> properties;
        private FrozenNode contracts;
        private String referenceBlueId;
        private Schema schema;
        private String mergePolicy;
        private String previousBlueId;
        private Integer position;
        private FrozenNode blue;
        private boolean inlineValue;
        private boolean strictCanonical = true;
        private boolean strictBlueIdValidation = true;
        private boolean previousAnchorContext;
        private boolean eagerBlueId = true;

        Builder name(String name) {
            this.name = name;
            return this;
        }

        Builder description(String description) {
            this.description = description;
            return this;
        }

        Builder type(FrozenNode type) {
            this.type = type;
            return this;
        }

        Builder itemType(FrozenNode itemType) {
            this.itemType = itemType;
            return this;
        }

        Builder keyType(FrozenNode keyType) {
            this.keyType = keyType;
            return this;
        }

        Builder valueType(FrozenNode valueType) {
            this.valueType = valueType;
            return this;
        }

        Builder value(Object value) {
            this.nodeValue = freezeValue(value);
            return this;
        }

        Builder frozenValue(Object value) {
            this.nodeValue = value;
            return this;
        }

        Builder items(List<FrozenNode> items) {
            this.items = items;
            return this;
        }

        Builder properties(Map<String, FrozenNode> properties) {
            this.properties = properties;
            return this;
        }

        Builder contracts(FrozenNode contracts) {
            this.contracts = contracts;
            return this;
        }

        Builder referenceBlueId(String referenceBlueId) {
            this.referenceBlueId = referenceBlueId;
            return this;
        }

        Builder schema(Schema schema) {
            this.schema = schema != null ? schema.clone() : null;
            return this;
        }

        Builder mergePolicy(String mergePolicy) {
            this.mergePolicy = mergePolicy;
            return this;
        }

        Builder previousBlueId(String previousBlueId) {
            this.previousBlueId = previousBlueId;
            return this;
        }

        Builder position(Integer position) {
            this.position = position;
            return this;
        }

        Builder blue(FrozenNode blue) {
            this.blue = blue;
            return this;
        }

        Builder inlineValue(boolean inlineValue) {
            this.inlineValue = inlineValue;
            return this;
        }

        Builder strictCanonical(boolean strictCanonical) {
            this.strictCanonical = strictCanonical;
            return this;
        }

        Builder strictBlueIdValidation(boolean strictBlueIdValidation) {
            this.strictBlueIdValidation = strictBlueIdValidation;
            return this;
        }

        Builder previousAnchorContext(boolean previousAnchorContext) {
            this.previousAnchorContext = previousAnchorContext;
            return this;
        }

        Builder deferBlueId() {
            this.eagerBlueId = false;
            return this;
        }

        FrozenNode build() {
            return new FrozenNode(this);
        }
    }

    /**
     * Legacy BlueId-keyed resolved-reference interner.
     *
     * @deprecated BlueId-keyed graph interning cannot establish that a
     * materialized resolved view is the verified standalone content for that
     * BlueId. Use {@link ResolvedReferenceCache} and structural interning.
     */
    @Deprecated
    public interface ResolvedReferenceInterner {
        FrozenNode lookup(String blueId);

        FrozenNode intern(String blueId, FrozenNode node);
    }

    public interface ResolvedStructuralInterner extends ResolvedReferenceInterner {
        FrozenNode intern(ResolvedStructuralKey structuralKey, FrozenNode node);

        @Override
        default FrozenNode lookup(String blueId) {
            return null;
        }

        @Override
        default FrozenNode intern(String blueId, FrozenNode node) {
            return node;
        }
    }

    /**
     * Exact immutable identity for one frozen representation.
     *
     * <p>This deliberately includes exact representation fields that
     * semantic Content BlueIds omit. It is therefore suitable only for object
     * interning, never for language identity.</p>
     */
    public static final class ResolvedStructuralKey {
        private final List<Object> fields;
        private final int hashCode;

        private ResolvedStructuralKey(FrozenNode node) {
            List<Object> exact = new ArrayList<>();
            exact.add(node.name);
            exact.add(node.description);
            exact.add(keyOf(node.type));
            exact.add(keyOf(node.itemType));
            exact.add(keyOf(node.keyType));
            exact.add(keyOf(node.valueType));
            exact.add(valueKeyOf(node.value));
            exact.add(keysOf(node.items));
            exact.add(propertyKeysOf(node.properties));
            exact.add(keyOf(node.contracts));
            exact.add(node.referenceBlueId);
            exact.add(node.schema != null
                    ? valueKeyOf(schemaObject(node.schema))
                    : null);
            exact.add(node.mergePolicy);
            exact.add(node.previousBlueId);
            exact.add(node.position);
            exact.add(keyOf(node.blue));
            exact.add(node.inlineValue);
            exact.add(node.strictCanonical);
            exact.add(node.strictBlueIdValidation);
            exact.add(node.previousAnchorContext);
            this.fields = Collections.unmodifiableList(exact);
            this.hashCode = fields.hashCode();
        }

        private static ResolvedStructuralKey keyOf(FrozenNode node) {
            return node != null ? node.resolvedStructuralKey() : null;
        }

        private static List<ResolvedStructuralKey> keysOf(List<FrozenNode> nodes) {
            if (nodes == null) {
                return null;
            }
            List<ResolvedStructuralKey> keys = new ArrayList<>(nodes.size());
            for (FrozenNode node : nodes) {
                keys.add(keyOf(node));
            }
            return Collections.unmodifiableList(keys);
        }

        private static List<PropertyKey> propertyKeysOf(Map<String, FrozenNode> properties) {
            if (properties == null) {
                return null;
            }
            List<PropertyKey> keys = new ArrayList<>(properties.size());
            for (Map.Entry<String, FrozenNode> entry : properties.entrySet()) {
                keys.add(new PropertyKey(entry.getKey(), keyOf(entry.getValue())));
            }
            return Collections.unmodifiableList(keys);
        }

        private static Object valueKeyOf(Object value) {
            if (value instanceof List) {
                List<?> source = (List<?>) value;
                List<Object> keys = new ArrayList<>(source.size());
                for (Object item : source) {
                    keys.add(valueKeyOf(item));
                }
                return Collections.unmodifiableList(keys);
            }
            if (value instanceof Map) {
                Map<?, ?> source = (Map<?, ?>) value;
                Map<String, Object> keys = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : source.entrySet()) {
                    keys.put((String) entry.getKey(), valueKeyOf(entry.getValue()));
                }
                return Collections.unmodifiableMap(keys);
            }
            if (value != null && value.getClass().isArray()) {
                List<Object> elements = new ArrayList<>(Array.getLength(value));
                for (int index = 0; index < Array.getLength(value); index++) {
                    elements.add(valueKeyOf(Array.get(value, index)));
                }
                return new RawArrayKey(value.getClass(), elements);
            }
            return value;
        }

        @Override
        public boolean equals(Object other) {
            return this == other || other instanceof ResolvedStructuralKey
                    && fields.equals(((ResolvedStructuralKey) other).fields);
        }

        @Override
        public int hashCode() {
            return hashCode;
        }
    }

    private static final class RawArrayKey {
        private final Class<?> arrayType;
        private final List<Object> elements;

        private RawArrayKey(Class<?> arrayType, List<Object> elements) {
            this.arrayType = arrayType;
            this.elements = Collections.unmodifiableList(elements);
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof RawArrayKey)) {
                return false;
            }
            RawArrayKey that = (RawArrayKey) other;
            return arrayType.equals(that.arrayType) && elements.equals(that.elements);
        }

        @Override
        public int hashCode() {
            return Objects.hash(arrayType, elements);
        }
    }

    private static final class PropertyKey {
        private final String name;
        private final ResolvedStructuralKey value;

        private PropertyKey(String name, ResolvedStructuralKey value) {
            this.name = name;
            this.value = value;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof PropertyKey)) {
                return false;
            }
            PropertyKey that = (PropertyKey) other;
            return Objects.equals(name, that.name) && Objects.equals(value, that.value);
        }

        @Override
        public int hashCode() {
            return Objects.hash(name, value);
        }
    }
}
