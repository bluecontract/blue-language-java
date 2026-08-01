package blue.language.snapshot;

import blue.language.model.Node;
import blue.language.model.Schema;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Allocation-light retained-weight estimates used for bounded snapshot caches.
 * Identity and structural keys are observed only when already cached.
 */
final class FrozenNodeRetainedWeight {

    private static final long FROZEN_NODE_BYTES = 112L;
    private static final long MUTABLE_NODE_BYTES = 104L;
    private static final long SCHEMA_BYTES = 80L;
    private static final long STRING_BYTES = 48L;
    private static final long LIST_BYTES = 32L;
    private static final long MAP_BYTES = 64L;
    private static final long MAP_ENTRY_BYTES = 40L;
    private static final long REFERENCE_BYTES = 8L;

    private FrozenNodeRetainedWeight() {
    }

    static long graph(FrozenNode... roots) {
        IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
        long weight = 0L;
        if (roots != null) {
            for (FrozenNode root : roots) {
                weight += retainedNode(root, seen);
            }
        }
        return weight;
    }

    static long shallow(FrozenNode node) {
        IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
        seen.put(node, Boolean.TRUE);
        long weight = FROZEN_NODE_BYTES;
        weight += retainedString(node.name, seen);
        weight += retainedString(node.description, seen);
        weight += retainedValue(node.value, seen);
        weight += retainedString(node.referenceBlueId, seen);
        weight += retainedString(node.mergePolicy, seen);
        weight += retainedString(node.previousBlueId, seen);
        weight += retainedString(node.cachedBlueId(), seen);
        if (node.items != null) {
            weight += LIST_BYTES + REFERENCE_BYTES * node.items.size();
        }
        if (node.properties != null) {
            weight += MAP_BYTES + MAP_ENTRY_BYTES * node.properties.size();
            for (String key : node.properties.keySet()) {
                weight += retainedString(key, seen);
            }
        }
        weight += retainedSchema(node.schema, seen);
        weight += retainedShallowStructuralKey(
                node.cachedStructuralKey(),
                seen);
        return weight;
    }

    private static long retainedNode(
            FrozenNode node,
            IdentityHashMap<Object, Boolean> seen) {
        if (node == null || seen.put(node, Boolean.TRUE) != null) {
            return 0L;
        }
        long weight = FROZEN_NODE_BYTES;
        weight += retainedString(node.name, seen);
        weight += retainedString(node.description, seen);
        weight += retainedValue(node.value, seen);
        weight += retainedString(node.referenceBlueId, seen);
        weight += retainedString(node.mergePolicy, seen);
        weight += retainedString(node.previousBlueId, seen);
        weight += retainedNode(node.type, seen);
        weight += retainedNode(node.itemType, seen);
        weight += retainedNode(node.keyType, seen);
        weight += retainedNode(node.valueType, seen);
        weight += retainedNode(node.contracts, seen);
        weight += retainedNode(node.blue, seen);
        if (node.items != null && seen.put(node.items, Boolean.TRUE) == null) {
            weight += LIST_BYTES + REFERENCE_BYTES * node.items.size();
            for (FrozenNode item : node.items) {
                weight += retainedNode(item, seen);
            }
        }
        if (node.properties != null
                && seen.put(node.properties, Boolean.TRUE) == null) {
            weight += MAP_BYTES + MAP_ENTRY_BYTES * node.properties.size();
            for (Map.Entry<String, FrozenNode> entry
                    : node.properties.entrySet()) {
                weight += retainedString(entry.getKey(), seen);
                weight += retainedNode(entry.getValue(), seen);
            }
        }
        weight += retainedSchema(node.schema, seen);
        weight += retainedString(node.cachedBlueId(), seen);
        weight += retainedStructuralObject(
                node.cachedStructuralKey(),
                seen);
        return weight;
    }

    private static long retainedSchema(
            Schema schema,
            IdentityHashMap<Object, Boolean> seen) {
        if (schema == null || seen.put(schema, Boolean.TRUE) != null) {
            return 0L;
        }
        long weight = SCHEMA_BYTES;
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
        if (schema.getEnum() != null
                && seen.put(schema.getEnum(), Boolean.TRUE) == null) {
            weight += LIST_BYTES
                    + REFERENCE_BYTES * schema.getEnum().size();
            for (Node value : schema.getEnum()) {
                weight += retainedMutableNode(value, seen);
            }
        }
        return weight;
    }

    private static long retainedMutableNode(
            Node node,
            IdentityHashMap<Object, Boolean> seen) {
        if (node == null || seen.put(node, Boolean.TRUE) != null) {
            return 0L;
        }
        long weight = MUTABLE_NODE_BYTES;
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
        if (node.getItems() != null
                && seen.put(node.getItems(), Boolean.TRUE) == null) {
            weight += LIST_BYTES
                    + REFERENCE_BYTES * node.getItems().size();
            for (Node item : node.getItems()) {
                weight += retainedMutableNode(item, seen);
            }
        }
        if (node.getProperties() != null
                && seen.put(node.getProperties(), Boolean.TRUE) == null) {
            weight += MAP_BYTES
                    + MAP_ENTRY_BYTES * node.getProperties().size();
            for (Map.Entry<String, Node> entry
                    : node.getProperties().entrySet()) {
                weight += retainedString(entry.getKey(), seen);
                weight += retainedMutableNode(entry.getValue(), seen);
            }
        }
        weight += retainedSchema(node.getSchema(), seen);
        return weight;
    }

    private static long retainedValue(
            Object value,
            IdentityHashMap<Object, Boolean> seen) {
        if (value == null) return 0L;
        if (value instanceof String) {
            return retainedString((String) value, seen);
        }
        if (seen.put(value, Boolean.TRUE) != null) return 0L;
        if (value instanceof BigInteger) {
            return 48L + 4L
                    * ((((BigInteger) value).abs().bitLength() + 31L) / 32L);
        }
        if (value instanceof BigDecimal) {
            return 64L + retainedValue(
                    ((BigDecimal) value).unscaledValue(),
                    seen);
        }
        if (value instanceof Boolean) return 16L;
        if (value instanceof Number) return 24L;
        if (value instanceof List) {
            List<?> values = (List<?>) value;
            long weight = LIST_BYTES + REFERENCE_BYTES * values.size();
            for (Object item : values) {
                weight += retainedValue(item, seen);
            }
            return weight;
        }
        if (value instanceof Map) {
            Map<?, ?> values = (Map<?, ?>) value;
            long weight = MAP_BYTES + MAP_ENTRY_BYTES * values.size();
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
            long weight = 24L + REFERENCE_BYTES * length;
            for (int index = 0; index < length; index++) {
                weight += retainedValue(Array.get(value, index), seen);
            }
            return weight;
        }
        return 48L;
    }

    private static long retainedString(
            String value,
            IdentityHashMap<Object, Boolean> seen) {
        if (value == null || seen.put(value, Boolean.TRUE) != null) {
            return 0L;
        }
        return STRING_BYTES + 2L * value.length();
    }

    private static long retainedStructuralObject(
            Object value,
            IdentityHashMap<Object, Boolean> seen) {
        if (value == null) return 0L;
        if (value instanceof String) {
            return retainedString((String) value, seen);
        }
        if (value instanceof Number || value instanceof Boolean) {
            return retainedValue(value, seen);
        }
        if (seen.put(value, Boolean.TRUE) != null) return 0L;
        if (value instanceof FrozenNode.ResolvedStructuralKey) {
            FrozenNodeStructuralKey key =
                    ((FrozenNode.ResolvedStructuralKey) value).delegate();
            return 32L + retainedStructuralObject(key.fields(), seen);
        }
        if (value instanceof FrozenNodeStructuralKey.PropertyKey) {
            FrozenNodeStructuralKey.PropertyKey key =
                    (FrozenNodeStructuralKey.PropertyKey) value;
            return 24L
                    + retainedString(key.name(), seen)
                    + retainedStructuralObject(key.value(), seen);
        }
        if (value instanceof List) {
            List<?> values = (List<?>) value;
            long weight = LIST_BYTES + REFERENCE_BYTES * values.size();
            for (Object item : values) {
                weight += retainedStructuralObject(item, seen);
            }
            return weight;
        }
        if (value instanceof Map) {
            Map<?, ?> values = (Map<?, ?>) value;
            long weight = MAP_BYTES + MAP_ENTRY_BYTES * values.size();
            for (Map.Entry<?, ?> entry : values.entrySet()) {
                weight += retainedStructuralObject(entry.getKey(), seen);
                weight += retainedStructuralObject(entry.getValue(), seen);
            }
            return weight;
        }
        return 48L;
    }

    private static long retainedShallowStructuralKey(
            FrozenNode.ResolvedStructuralKey compatibilityKey,
            IdentityHashMap<Object, Boolean> seen) {
        if (compatibilityKey == null
                || seen.put(compatibilityKey, Boolean.TRUE) != null) {
            return 0L;
        }
        FrozenNodeStructuralKey key = compatibilityKey.delegate();
        List<Object> fields = key.fields();
        long weight = 32L;
        if (seen.put(fields, Boolean.TRUE) != null) {
            return weight;
        }
        weight += LIST_BYTES + REFERENCE_BYTES * fields.size();
        for (int index = 0; index < fields.size(); index++) {
            Object field = fields.get(index);
            if (field instanceof FrozenNode.ResolvedStructuralKey) {
                continue;
            }
            if (index == FrozenNodeStructuralKey.ITEMS_FIELD_INDEX) {
                weight += retainedChildKeyList(field, seen);
            } else if (index
                    == FrozenNodeStructuralKey.PROPERTIES_FIELD_INDEX) {
                weight += retainedPropertyKeyList(field, seen);
            } else {
                weight += retainedStructuralObject(field, seen);
            }
        }
        return weight;
    }

    private static long retainedChildKeyList(
            Object field,
            IdentityHashMap<Object, Boolean> seen) {
        if (!(field instanceof List)
                || seen.put(field, Boolean.TRUE) != null) {
            return 0L;
        }
        return LIST_BYTES + REFERENCE_BYTES * ((List<?>) field).size();
    }

    private static long retainedPropertyKeyList(
            Object field,
            IdentityHashMap<Object, Boolean> seen) {
        if (!(field instanceof List)
                || seen.put(field, Boolean.TRUE) != null) {
            return 0L;
        }
        List<?> properties = (List<?>) field;
        long weight = LIST_BYTES + REFERENCE_BYTES * properties.size();
        for (Object value : properties) {
            if (!(value instanceof FrozenNodeStructuralKey.PropertyKey)
                    || seen.put(value, Boolean.TRUE) != null) {
                continue;
            }
            FrozenNodeStructuralKey.PropertyKey property =
                    (FrozenNodeStructuralKey.PropertyKey) value;
            weight += 24L + retainedString(property.name(), seen);
        }
        return weight;
    }
}
