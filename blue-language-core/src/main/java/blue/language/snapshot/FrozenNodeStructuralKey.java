package blue.language.snapshot;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Exact immutable key for one frozen representation.
 *
 * <p>This key includes construction-mode and representation fields that a
 * semantic BlueId omits. It is suitable for structural interning only.</p>
 */
public final class FrozenNodeStructuralKey {

    static final int ITEMS_FIELD_INDEX = 7;
    static final int PROPERTIES_FIELD_INDEX = 8;

    private final List<Object> fields;
    private final int hashCode;

    FrozenNodeStructuralKey(FrozenNode node) {
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
                ? valueKeyOf(FrozenNodeIdentity.schemaObject(node.schema))
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

    List<Object> fields() {
        return fields;
    }

    static Object valueKeyOf(Object value) {
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
                keys.put(
                        (String) entry.getKey(),
                        valueKeyOf(entry.getValue()));
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
        return this == other
                || other instanceof FrozenNodeStructuralKey
                && fields.equals(((FrozenNodeStructuralKey) other).fields);
    }

    @Override
    public int hashCode() {
        return hashCode;
    }

    private static FrozenNode.ResolvedStructuralKey keyOf(FrozenNode node) {
        return node != null ? node.resolvedStructuralKey() : null;
    }

    private static List<FrozenNode.ResolvedStructuralKey> keysOf(
            List<FrozenNode> nodes) {
        if (nodes == null) {
            return null;
        }
        List<FrozenNode.ResolvedStructuralKey> keys = new ArrayList<>(
                nodes.size());
        for (FrozenNode node : nodes) {
            keys.add(keyOf(node));
        }
        return Collections.unmodifiableList(keys);
    }

    private static List<PropertyKey> propertyKeysOf(
            Map<String, FrozenNode> properties) {
        if (properties == null) {
            return null;
        }
        List<PropertyKey> keys = new ArrayList<>(properties.size());
        for (Map.Entry<String, FrozenNode> entry : properties.entrySet()) {
            keys.add(new PropertyKey(entry.getKey(), keyOf(entry.getValue())));
        }
        return Collections.unmodifiableList(keys);
    }

    static final class PropertyKey {
        private final String name;
        private final FrozenNode.ResolvedStructuralKey value;

        private PropertyKey(
                String name,
                FrozenNode.ResolvedStructuralKey value) {
            this.name = name;
            this.value = value;
        }

        String name() {
            return name;
        }

        FrozenNode.ResolvedStructuralKey value() {
            return value;
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
            return Objects.equals(name, that.name)
                    && Objects.equals(value, that.value);
        }

        @Override
        public int hashCode() {
            return Objects.hash(name, value);
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
            return arrayType.equals(that.arrayType)
                    && elements.equals(that.elements);
        }

        @Override
        public int hashCode() {
            return Objects.hash(arrayType, elements);
        }
    }
}
