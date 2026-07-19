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

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
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

    public static FrozenNode fromUncheckedCanonicalNode(Node node) {
        return fromNode(node, true, null, false);
    }

    private static FrozenNode fromNode(Node node, boolean strictCanonical) {
        return fromNode(node, strictCanonical, null, true);
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
        List<FrozenNode> source = nodes == null ? Collections.emptyList() : nodes;
        if (canFoldCachedListBlueIds(source)) {
            return foldCachedListBlueIds(source);
        }
        List<Object> objects = new ArrayList<>(source.size());
        for (int i = 0; i < source.size(); i++) {
            objects.add(FrozenNodeToBlueIdInput.getListElement(source.get(i), i));
        }
        return BlueIdCalculator.INSTANCE.calculate(objects);
    }

    /**
     * Compares the exact resolved representation of two frozen nodes without
     * materializing mutable {@link Node} graphs first.
     *
     * <p>The construction-mode fields are intentionally ignored. This matches
     * converting both inputs through {@code toNode()} and
     * {@code fromResolvedNode(...)} before comparing their structural keys.</p>
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
                || !Objects.equals(value, other.value)
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
        return inlineValue == other.inlineValue;
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
        Iterator<Map.Entry<String, FrozenNode>> leftEntries = left.entrySet().iterator();
        Iterator<Map.Entry<String, FrozenNode>> rightEntries = right.entrySet().iterator();
        while (leftEntries.hasNext()) {
            Map.Entry<String, FrozenNode> leftEntry = leftEntries.next();
            Map.Entry<String, FrozenNode> rightEntry = rightEntries.next();
            if (!Objects.equals(leftEntry.getKey(), rightEntry.getKey())
                    || !sameResolvedStructure(leftEntry.getValue(), rightEntry.getValue())) {
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
                && schemaObject(left).equals(schemaObject(right));
    }

    public Node toNode() {
        Node node = new Node()
                .name(name)
                .description(description)
                .type(type != null ? type.toNode() : null)
                .itemType(itemType != null ? itemType.toNode() : null)
                .keyType(keyType != null ? keyType.toNode() : null)
                .valueType(valueType != null ? valueType.toNode() : null)
                .value(value)
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
                .value(value)
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
            if (isPayloadOnlyList()) {
                return calculateBlueId(items);
            }
            return BlueIdCalculator.INSTANCE.calculate(FrozenNodeToBlueIdInput.get(this));
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

    public interface ResolvedStructuralInterner {
        FrozenNode intern(ResolvedStructuralKey structuralKey, FrozenNode node);
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
            exact.add(node.value);
            exact.add(keysOf(node.items));
            exact.add(propertyKeysOf(node.properties));
            exact.add(keyOf(node.contracts));
            exact.add(node.referenceBlueId);
            exact.add(node.schema != null ? schemaObject(node.schema) : null);
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
