package blue.language.snapshot;

import blue.language.model.Node;
import blue.language.model.Schema;
import blue.language.utils.Base58Sha256Provider;
import blue.language.utils.BlueNumbers;
import blue.language.utils.BlueIdCalculator;
import com.fasterxml.jackson.core.type.TypeReference;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import static blue.language.utils.Properties.*;
import static blue.language.utils.UncheckedObjectMapper.YAML_MAPPER;

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
    private final String referenceBlueId;
    private final Schema schema;
    private final String mergePolicy;
    private final String previousBlueId;
    private final Integer position;
    private final FrozenNode blue;
    private final boolean inlineValue;
    private final boolean strictCanonical;
    private final String blueId;

    private FrozenNode(Builder builder) {
        this.name = builder.name;
        this.description = builder.description;
        this.type = builder.type;
        this.itemType = builder.itemType;
        this.keyType = builder.keyType;
        this.valueType = builder.valueType;
        this.value = builder.nodeValue;
        this.items = freezeList(builder.items);
        this.properties = freezeMap(builder.properties);
        this.referenceBlueId = builder.referenceBlueId;
        this.schema = builder.schema != null ? builder.schema.clone() : null;
        this.mergePolicy = builder.mergePolicy;
        this.previousBlueId = builder.previousBlueId;
        this.position = builder.position;
        this.blue = builder.blue;
        this.inlineValue = builder.inlineValue;
        this.strictCanonical = builder.strictCanonical;
        validatePayloadShape();
        this.blueId = computeBlueId();
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

    public static FrozenNode fromResolvedNode(Node node, ResolvedReferenceInterner interner) {
        return fromNode(node, false, interner);
    }

    private static FrozenNode fromNode(Node node, boolean strictCanonical) {
        return fromNode(node, strictCanonical, null);
    }

    private static FrozenNode fromNode(Node node, boolean strictCanonical, ResolvedReferenceInterner interner) {
        Objects.requireNonNull(node, "node");
        if (!strictCanonical && interner != null && node.getBlueId() != null) {
            FrozenNode cached = interner.lookup(node.getBlueId());
            if (cached != null) {
                return cached;
            }
        }
        FrozenNode frozen = builder()
                .name(node.getName())
                .description(node.getDescription())
                .type(node.getType() != null ? fromNode(node.getType(), strictCanonical, interner) : null)
                .itemType(node.getItemType() != null ? fromNode(node.getItemType(), strictCanonical, interner) : null)
                .keyType(node.getKeyType() != null ? fromNode(node.getKeyType(), strictCanonical, interner) : null)
                .valueType(node.getValueType() != null ? fromNode(node.getValueType(), strictCanonical, interner) : null)
                .value(node.getValue())
                .items(node.getItems() != null
                        ? node.getItems().stream().map(item -> fromNode(item, strictCanonical, interner)).collect(Collectors.toList())
                        : null)
                .properties(freezeProperties(node.getProperties(), strictCanonical, interner))
                .referenceBlueId(node.getBlueId())
                .schema(node.getSchema())
                .mergePolicy(node.getMergePolicy())
                .previousBlueId(node.getPreviousBlueId())
                .position(node.getPosition())
                .blue(node.getBlue() != null ? fromNode(node.getBlue(), strictCanonical, interner) : null)
                .inlineValue(node.isInlineValue())
                .strictCanonical(strictCanonical)
                .build();
        if (!strictCanonical && interner != null && node.getBlueId() != null && !node.isReferenceOnly()) {
            return interner.intern(node.getBlueId(), frozen);
        }
        return frozen;
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
                                                            ResolvedReferenceInterner interner) {
        if (source == null || source.isEmpty()) {
            return null;
        }
        Map<String, FrozenNode> result = new LinkedHashMap<>();
        for (Map.Entry<String, Node> entry : source.entrySet()) {
            FrozenNode child = fromNode(entry.getValue(), strictCanonical, interner);
            if (strictCanonical && child.isEmptyNode()) {
                continue;
            }
            result.put(entry.getKey(), child);
        }
        return result.isEmpty() ? null : result;
    }

    public static String calculateBlueId(List<FrozenNode> nodes) {
        return computeListHash(nodes == null ? Collections.emptyList() : nodes);
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
        return blueId;
    }

    public String getName() {
        return name;
    }

    public Object getValue() {
        return value;
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

    public List<FrozenNode> getItems() {
        return items;
    }

    public Map<String, FrozenNode> getProperties() {
        return properties;
    }

    public FrozenNode property(String key) {
        return properties != null ? properties.get(key) : null;
    }

    public FrozenNode item(int index) {
        if (items == null || index < 0 || index >= items.size()) {
            return null;
        }
        return items.get(index);
    }

    public FrozenNode at(String pointer) {
        String normalized = normalizePointer(pointer);
        if ("/".equals(normalized)) {
            return this;
        }
        FrozenNode current = this;
        String[] segments = normalized.substring(1).split("/", -1);
        for (String segment : segments) {
            if (current == null) {
                return null;
            }
            if (current.items != null) {
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
                && schema == null
                && mergePolicy == null
                && position == null
                && blue == null
                && referenceBlueId == null;
    }

    public boolean isStrictCanonical() {
        return strictCanonical;
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
                && referenceBlueId == null
                && schema == null
                && mergePolicy == null
                && previousBlueId == null
                && position == null
                && blue == null;
    }

    public FrozenNode withProperty(String key, FrozenNode child) {
        Map<String, FrozenNode> next = properties != null
                ? new LinkedHashMap<>(properties)
                : new LinkedHashMap<>();
        if (child == null || (strictCanonical && child.isEmptyNode())) {
            next.remove(key);
        } else {
            next.put(key, child);
        }
        return toBuilder().properties(next.isEmpty() ? null : next).build();
    }

    public FrozenNode withItems(List<FrozenNode> nextItems) {
        return toBuilder().items(nextItems).build();
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
        if (strictCanonical && previousBlueId != null && !isPreviousOnly()) {
            throw new IllegalArgumentException("\"$previous\" list anchors must be single-key list items.");
        }
        if (position != null
                && payloadKinds == 0
                && name == null
                && description == null
                && type == null
                && itemType == null
                && keyType == null
                && valueType == null
                && schema == null
                && mergePolicy == null
                && blue == null
                && referenceBlueId == null) {
            throw new IllegalArgumentException("\"$pos\" items must contain an overlay.");
        }
    }

    private void indexPaths(String path, Map<String, FrozenNode> index) {
        index.put(path, this);
        if (items != null) {
            for (int i = 0; i < items.size(); i++) {
                items.get(i).indexPaths(childPointer(path, String.valueOf(i)), index);
            }
        }
        if (properties != null) {
            properties.forEach((key, child) -> child.indexPaths(childPointer(path, key), index));
        }
    }

    private String childPointer(String parent, String child) {
        if ("/".equals(parent)) {
            return "/" + child;
        }
        return parent + "/" + child;
    }

    private String normalizePointer(String pointer) {
        if (pointer == null || pointer.isEmpty()) {
            return "/";
        }
        return pointer.charAt(0) == '/' ? pointer : "/" + pointer;
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
                .referenceBlueId(referenceBlueId)
                .schema(schema)
                .mergePolicy(mergePolicy)
                .previousBlueId(previousBlueId)
                .position(position)
                .blue(blue)
                .inlineValue(inlineValue)
                .strictCanonical(strictCanonical);
    }

    private String computeBlueId() {
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
        putBlueId(hashes, OBJECT_BLUE, blue);
        if (properties != null) {
            properties.forEach((key, child) -> putBlueId(hashes, key, child));
        }
        return HASH.apply(hashes);
    }

    private static String computeListHash(List<FrozenNode> list) {
        if (list == null) {
            return HASH.apply(Collections.singletonMap("$list", "empty"));
        }

        String accumulator = HASH.apply(Collections.singletonMap("$list", "empty"));
        int start = 0;
        if (!list.isEmpty() && list.get(0).isPreviousOnly()) {
            accumulator = list.get(0).previousBlueId;
            start = 1;
        }

        List<FrozenNode> normalized = normalizeListControls(list, start);
        for (FrozenNode element : normalized) {
            Map<String, Object> cons = new TreeMap<>(String::compareTo);
            cons.put("elem", reference(element.blueId()));
            cons.put("prev", reference(accumulator));
            accumulator = HASH.apply(Collections.singletonMap("$listCons", cons));
        }
        return accumulator;
    }

    private static List<FrozenNode> normalizeListControls(List<FrozenNode> list, int start) {
        Map<Integer, FrozenNode> positioned = new TreeMap<>();
        List<FrozenNode> appended = new ArrayList<>();
        boolean hasPositions = false;
        for (int i = start; i < list.size(); i++) {
            FrozenNode item = list.get(i);
            if (item.isPreviousOnly()) {
                throw new IllegalArgumentException("\"$previous\" must appear only as the first list item.");
            }
            if (item.position != null) {
                hasPositions = true;
                if (positioned.put(item.position, item.withoutPosition()) != null) {
                    throw new IllegalArgumentException("Duplicate \"$pos\" value in list: " + item.position);
                }
            } else {
                appended.add(item);
            }
        }
        if (!hasPositions) {
            return list.subList(start, list.size());
        }
        List<FrozenNode> normalized = new ArrayList<>(positioned.values());
        normalized.addAll(appended);
        return normalized;
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
        return YAML_MAPPER.convertValue(schema, new TypeReference<Map<String, Object>>() {});
    }

    private static List<FrozenNode> freezeList(List<FrozenNode> source) {
        if (source == null) {
            return null;
        }
        return Collections.unmodifiableList(new ArrayList<>(source));
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
        private String referenceBlueId;
        private Schema schema;
        private String mergePolicy;
        private String previousBlueId;
        private Integer position;
        private FrozenNode blue;
        private boolean inlineValue;
        private boolean strictCanonical = true;

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

        FrozenNode build() {
            return new FrozenNode(this);
        }
    }

    public interface ResolvedReferenceInterner {
        FrozenNode lookup(String blueId);

        FrozenNode intern(String blueId, FrozenNode node);
    }
}
