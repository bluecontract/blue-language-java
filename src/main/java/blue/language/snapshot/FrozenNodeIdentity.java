package blue.language.snapshot;

import blue.language.model.wire.BlueLanguageConstants;

import blue.language.identity.CanonicalJsonHasher;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.identity.ListBlueIdFold;
import blue.language.model.Schema;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.utils.BlueIds;
import blue.language.model.value.BlueNumbers;
import blue.language.model.NodeWireForm;
import blue.language.model.SchemaWireForm;

import java.math.BigInteger;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import static blue.language.model.wire.BlueLanguageConstants.BOOLEAN_TYPE_BLUE_ID;
import static blue.language.model.wire.BlueLanguageConstants.DOUBLE_TYPE_BLUE_ID;
import static blue.language.model.wire.BlueLanguageConstants.INTEGER_TYPE_BLUE_ID;
import static blue.language.model.wire.BlueLanguageConstants.LIST_CONTROL_EMPTY;
import static blue.language.model.wire.BlueLanguageConstants.LIST_CONTROL_POS;
import static blue.language.model.wire.BlueLanguageConstants.LIST_CONTROL_PREVIOUS;
import static blue.language.model.wire.BlueLanguageConstants.LIST_CONTROL_REPLACE;
import static blue.language.model.wire.BlueLanguageConstants.OBJECT_BLUE;
import static blue.language.model.wire.BlueLanguageConstants.OBJECT_BLUE_ID;
import static blue.language.model.wire.BlueLanguageConstants.OBJECT_CONTRACTS;
import static blue.language.model.wire.BlueLanguageConstants.OBJECT_DESCRIPTION;
import static blue.language.model.wire.BlueLanguageConstants.OBJECT_ITEMS;
import static blue.language.model.wire.BlueLanguageConstants.OBJECT_ITEM_TYPE;
import static blue.language.model.wire.BlueLanguageConstants.OBJECT_KEY_TYPE;
import static blue.language.model.wire.BlueLanguageConstants.OBJECT_MERGE_POLICY;
import static blue.language.model.wire.BlueLanguageConstants.OBJECT_NAME;
import static blue.language.model.wire.BlueLanguageConstants.OBJECT_SCHEMA;
import static blue.language.model.wire.BlueLanguageConstants.OBJECT_TYPE;
import static blue.language.model.wire.BlueLanguageConstants.OBJECT_VALUE;
import static blue.language.model.wire.BlueLanguageConstants.OBJECT_VALUE_TYPE;
import static blue.language.model.wire.BlueLanguageConstants.TEXT_TYPE_BLUE_ID;

/**
 * Owns semantic identity and resolved-structure comparisons for immutable
 * nodes without materializing mutable graphs on normal hot paths.
 */
public final class FrozenNodeIdentity {

    private static final CanonicalJsonHasher CANONICAL_HASHER =
            new CanonicalJsonHasher();
    private static final DirectBlueIdCalculator DIRECT =
            DirectBlueIdCalculator.INSTANCE;
    private static final ListBlueIdFold LIST_FOLD =
            new ListBlueIdFold(CANONICAL_HASHER);

    /** Shared stateless identity service. */
    public static final FrozenNodeIdentity INSTANCE =
            new FrozenNodeIdentity();

    private FrozenNodeIdentity() {
    }

    /** Calculates the BlueId of one frozen node. */
    public String blueId(FrozenNode node) {
        if (node.strictCanonical) {
            return node.strictBlueIdValidation
                    ? FrozenCanonicalDigester.calculateBlueId(node)
                    : DirectBlueIdCalculator.calculateUncheckedBlueId(
                            FrozenNodeConverter.INSTANCE.toNode(node));
        }
        return resolvedBlueId(node);
    }

    /** Calculates the canonical BlueId of an ordered frozen sequence. */
    public String blueId(java.util.List<FrozenNode> nodes) {
        return FrozenCanonicalDigester.calculateBlueId(nodes);
    }

    /** Compares exact resolved graph content without mutable conversion. */
    public boolean sameResolvedStructure(
            FrozenNode left,
            FrozenNode right) {
        if (left == right) {
            return true;
        }
        if (left == null
                || right == null
                || !Objects.equals(left.name, right.name)
                || !Objects.equals(left.description, right.description)
                || !sameResolvedStructure(left.type, right.type)
                || !sameResolvedStructure(left.itemType, right.itemType)
                || !sameResolvedStructure(left.keyType, right.keyType)
                || !sameResolvedStructure(left.valueType, right.valueType)
                || !Objects.equals(
                        FrozenNodeStructuralKey.valueKeyOf(left.value),
                        FrozenNodeStructuralKey.valueKeyOf(right.value))
                || !sameResolvedItems(left.items, right.items)
                || !sameResolvedProperties(
                        left.properties,
                        right.properties)
                || !sameResolvedStructure(left.contracts, right.contracts)
                || !Objects.equals(
                        left.referenceBlueId,
                        right.referenceBlueId)
                || !sameSchema(left.schema, right.schema)
                || !Objects.equals(left.mergePolicy, right.mergePolicy)
                || !Objects.equals(left.previousBlueId, right.previousBlueId)
                || !Objects.equals(left.position, right.position)
                || !sameResolvedStructure(left.blue, right.blue)) {
            return false;
        }
        return true;
    }

    static boolean containsCyclicSetReference(FrozenNode node) {
        if (BlueIds.hasCyclicMemberSeparator(node.referenceBlueId)
                || childContainsCyclicReference(node.type)
                || childContainsCyclicReference(node.itemType)
                || childContainsCyclicReference(node.keyType)
                || childContainsCyclicReference(node.valueType)
                || childContainsCyclicReference(node.contracts)
                || childContainsCyclicReference(node.blue)) {
            return true;
        }
        if (node.items != null) {
            for (FrozenNode item : node.items) {
                if (childContainsCyclicReference(item)) {
                    return true;
                }
            }
        }
        if (node.properties != null) {
            for (FrozenNode property : node.properties.values()) {
                if (childContainsCyclicReference(property)) {
                    return true;
                }
            }
        }
        return false;
    }

    static boolean containsSchema(FrozenNode node) {
        if (node.schema != null
                || childContainsSchema(node.type)
                || childContainsSchema(node.itemType)
                || childContainsSchema(node.keyType)
                || childContainsSchema(node.valueType)
                || childContainsSchema(node.contracts)
                || childContainsSchema(node.blue)) {
            return true;
        }
        if (node.items != null) {
            for (FrozenNode item : node.items) {
                if (childContainsSchema(item)) {
                    return true;
                }
            }
        }
        if (node.properties != null) {
            for (FrozenNode property : node.properties.values()) {
                if (childContainsSchema(property)) {
                    return true;
                }
            }
        }
        return false;
    }

    static boolean containsNestedTypedObjectPayload(FrozenNode node) {
        if (node.properties == null) {
            return false;
        }
        for (FrozenNode property : node.properties.values()) {
            if (property.type != null
                    && property.properties != null
                    && !property.properties.isEmpty()
                    || property.containsNestedTypedObjectPayload) {
                return true;
            }
        }
        return false;
    }

    static Map<String, Object> schemaObject(Schema schema) {
        return SchemaWireForm.get(
                schema,
                NodeWireForm::get);
    }

    private String resolvedBlueId(FrozenNode node) {
        if (node.isReferenceOnly()) {
            return node.referenceBlueId;
        }
        if (node.isPreviousOnly()) {
            Map<String, Object> previous = new TreeMap<>(String::compareTo);
            previous.put(
                    LIST_CONTROL_PREVIOUS,
                    reference(node.previousBlueId));
            return CANONICAL_HASHER.hash(previous);
        }
        return resolvedObjectBlueId(node, true);
    }

    /**
     * Calculates an element contribution after resolved-reference metadata is
     * conceptually removed. Payload-only lists retain list identity here,
     * exactly as strict direct projection requires.
     */
    private String resolvedElementBlueId(FrozenNode node) {
        if (node.blue != null) {
            throw new IllegalArgumentException(
                    "\"blue\" is a preprocessing directive and must not be present in BlueId input.");
        }
        if (node.position != null) {
            throw new IllegalArgumentException(
                    "\"$pos\" overlays are not valid direct BlueId input.");
        }
        if (node.properties != null
                && node.properties.containsKey(LIST_CONTROL_REPLACE)) {
            throw new IllegalArgumentException(
                    "\"$replace\" overlays are not valid direct BlueId input.");
        }
        if (node.isReferenceOnly()) {
            return node.referenceBlueId;
        }
        if (isPayloadOnlyList(node)) {
            return resolvedListBlueId(node.items);
        }
        return resolvedObjectBlueId(node, false);
    }

    private String resolvedObjectBlueId(
            FrozenNode node,
            boolean includeResolvedControls) {
        Map<String, Object> hashes = new TreeMap<>(String::compareTo);
        putRaw(hashes, OBJECT_NAME, node.name);
        putRaw(hashes, OBJECT_DESCRIPTION, node.description);

        String valueTypeBlueId = null;
        if (node.value != null && node.type == null) {
            valueTypeBlueId = inferTypeBlueId(node.value);
            putBlueId(hashes, OBJECT_TYPE, valueTypeBlueId);
        } else if (node.type != null) {
            valueTypeBlueId = node.type.referenceBlueId;
            putBlueId(hashes, OBJECT_TYPE, node.type.blueId());
        }

        putBlueId(hashes, OBJECT_ITEM_TYPE, node.itemType);
        putBlueId(hashes, OBJECT_KEY_TYPE, node.keyType);
        putBlueId(hashes, OBJECT_VALUE_TYPE, node.valueType);
        putHashedScalar(hashes, OBJECT_MERGE_POLICY, node.mergePolicy);
        if (includeResolvedControls) {
            putHashedScalar(
                    hashes,
                    LIST_CONTROL_POS,
                    node.position != null
                            ? BigInteger.valueOf(node.position)
                            : null);
        }
        putRaw(
                hashes,
                OBJECT_VALUE,
                handleValue(node.value, valueTypeBlueId));
        if (node.items != null) {
            putBlueId(hashes, OBJECT_ITEMS, resolvedListBlueId(node.items));
        }
        if (node.schema != null) {
            putBlueId(
                    hashes,
                    OBJECT_SCHEMA,
                    DIRECT.directBlueIdFromCanonicalInput(
                            schemaObject(node.schema)));
        }
        putBlueId(hashes, OBJECT_CONTRACTS, node.contracts);
        if (includeResolvedControls) {
            putBlueId(hashes, OBJECT_BLUE, node.blue);
        }
        if (node.properties != null) {
            for (Map.Entry<String, FrozenNode> entry
                    : node.properties.entrySet()) {
                putBlueId(hashes, entry.getKey(), entry.getValue());
            }
        }
        return CANONICAL_HASHER.hash(hashes);
    }

    private String resolvedListBlueId(java.util.List<FrozenNode> nodes) {
        String accumulator;
        int start;
        if (!nodes.isEmpty() && nodes.get(0).isPreviousOnly()) {
            accumulator = nodes.get(0).previousBlueId;
            start = 1;
        } else {
            accumulator = LIST_FOLD.seedBlueId();
            start = 0;
        }
        for (int index = start; index < nodes.size(); index++) {
            FrozenNode item = nodes.get(index);
            if (item.isEmptyNode()) {
                throw new IllegalArgumentException(
                        "Direct BlueId input must use { \"$empty\": true } for empty list placeholders.");
            }
            if (item.isPreviousOnly()) {
                throw new IllegalArgumentException(
                        "\"$previous\" must appear only as the first list item.");
            }
            if (item.properties != null
                    && item.properties.containsKey(LIST_CONTROL_EMPTY)
                    && !isEmptyPlaceholder(item)) {
                throw new IllegalArgumentException(
                        "\"$empty\" list placeholder must have exact shape { \"$empty\": true }.");
            }
            String itemBlueId = isEmptyPlaceholder(item)
                    ? LIST_FOLD.emptyPlaceholderBlueId()
                    : resolvedElementBlueId(item);
            accumulator = LIST_FOLD.appendBlueId(
                    accumulator,
                    itemBlueId);
        }
        return accumulator;
    }

    private boolean sameResolvedItems(
            java.util.List<FrozenNode> left,
            java.util.List<FrozenNode> right) {
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

    private boolean sameResolvedProperties(
            Map<String, FrozenNode> left,
            Map<String, FrozenNode> right) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null || left.size() != right.size()) {
            return false;
        }
        for (Map.Entry<String, FrozenNode> entry : left.entrySet()) {
            if (!right.containsKey(entry.getKey())
                    || !sameResolvedStructure(
                            entry.getValue(),
                            right.get(entry.getKey()))) {
                return false;
            }
        }
        return true;
    }

    private boolean sameSchema(Schema left, Schema right) {
        return left == right
                || left != null
                && right != null
                && Objects.equals(
                        FrozenNodeStructuralKey.valueKeyOf(
                                schemaObject(left)),
                        FrozenNodeStructuralKey.valueKeyOf(
                                schemaObject(right)));
    }

    private static boolean childContainsCyclicReference(FrozenNode child) {
        return child != null && child.containsCyclicSetReference;
    }

    private static boolean childContainsSchema(FrozenNode child) {
        return child != null && child.containsSchema;
    }

    private boolean isEmptyPlaceholder(FrozenNode node) {
        if (node == null
                || node.properties == null
                || node.properties.size() != 1) {
            return false;
        }
        FrozenNode marker = node.properties.get(LIST_CONTROL_EMPTY);
        return marker != null
                && Boolean.TRUE.equals(marker.value)
                && marker.isValueOnly()
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

    private boolean isPayloadOnlyList(FrozenNode node) {
        return node.items != null
                && node.name == null
                && node.description == null
                && node.type == null
                && node.itemType == null
                && node.keyType == null
                && node.valueType == null
                && node.value == null
                && node.properties == null
                && node.contracts == null
                && node.referenceBlueId == null
                && node.schema == null
                && node.mergePolicy == null
                && node.previousBlueId == null
                && node.position == null
                && node.blue == null;
    }

    private void putRaw(
            Map<String, Object> target,
            String key,
            Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    private void putBlueId(
            Map<String, Object> target,
            String key,
            FrozenNode node) {
        if (node != null) {
            putBlueId(target, key, node.blueId());
        }
    }

    private void putBlueId(
            Map<String, Object> target,
            String key,
            String blueId) {
        if (blueId != null) {
            target.put(key, reference(blueId));
        }
    }

    private void putHashedScalar(
            Map<String, Object> target,
            String key,
            Object value) {
        if (value != null) {
            putBlueId(
                    target,
                    key,
                    DIRECT.directBlueIdFromCanonicalInput(value));
        }
    }

    private Map<String, Object> reference(String blueId) {
        return Collections.singletonMap(OBJECT_BLUE_ID, blueId);
    }

    private Object handleValue(Object value, String valueTypeBlueId) {
        if (value == null) {
            return null;
        }
        if (DOUBLE_TYPE_BLUE_ID.equals(valueTypeBlueId)) {
            return BlueNumbers.toCanonicalDoubleValue(value);
        }
        if (value instanceof BigInteger) {
            BigInteger integer = (BigInteger) value;
            if (integer.compareTo(BlueNumbers.MIN_INTEROPERABLE_INTEGER) < 0
                    || integer.compareTo(
                    BlueNumbers.MAX_INTEROPERABLE_INTEGER) > 0) {
                return integer.toString();
            }
        }
        return value;
    }

    private String inferTypeBlueId(Object value) {
        if (value instanceof String) return TEXT_TYPE_BLUE_ID;
        if (value instanceof BigInteger) return INTEGER_TYPE_BLUE_ID;
        if (value instanceof java.math.BigDecimal) return DOUBLE_TYPE_BLUE_ID;
        if (value instanceof Boolean) return BOOLEAN_TYPE_BLUE_ID;
        return null;
    }
}
