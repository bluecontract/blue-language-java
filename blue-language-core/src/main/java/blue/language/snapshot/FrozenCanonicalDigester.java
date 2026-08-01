package blue.language.snapshot;

import blue.language.model.Node;
import blue.language.model.Schema;
import blue.language.identity.Base58;
import blue.language.identity.CanonicalJsonValueWriter;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.utils.BlueIds;
import blue.language.model.value.BlueNumbers;
import blue.language.utils.SchemaEnumCanonicalizer;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static blue.language.identity.CanonicalIdentityConstants.LIST_CONS_ELEMENT_KEY;
import static blue.language.identity.CanonicalIdentityConstants.LIST_CONS_KEY;
import static blue.language.identity.CanonicalIdentityConstants.LIST_CONS_PREVIOUS_KEY;
import static blue.language.identity.CanonicalIdentityConstants.LIST_SEED_KEY;
import static blue.language.identity.CanonicalIdentityConstants.LIST_SEED_VALUE;
import static blue.language.model.wire.BlueLanguageConstants.*;
import static blue.language.model.wire.SchemaPropertyConstants.*;

/**
 * Exact frozen-native BlueId calculator. It preserves the existing recursive
 * BlueId protocol while streaming every JCS hash input into SHA-256.
 *
 * <p><strong>Parity invariant:</strong> every directly supported node must
 * produce exactly the same canonical JSON value, field ordering, list-chain
 * construction, and digest as
 * {@link FrozenNodeToBlueIdInput} followed by the generic
 * {@link DirectBlueIdCalculator}. Changes to either canonical projection must be
 * mirrored here. When parity cannot be proved for a shape, this implementation
 * must reject the direct path and use the generic projection rather than
 * introduce a second identity protocol.</p>
 */
final class FrozenCanonicalDigester {

    private static final ThreadLocal<MessageDigest> SHA_256 = new ThreadLocal<MessageDigest>() {
        @Override
        protected MessageDigest initialValue() {
            try {
                return MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException exception) {
                throw new AssertionError("SHA-256 is required", exception);
            }
        }
    };

    private static final Observer NO_OBSERVER = new Observer() {
    };
    private static final Comparator<HashField> FIELD_ORDER = new Comparator<HashField>() {
        @Override
        public int compare(HashField left, HashField right) {
            return left.key.compareTo(right.key);
        }
    };

    private FrozenCanonicalDigester() {
    }

    /** Optional per-call instrumentation; implementations must be thread-safe if shared. */
    interface Observer {
        default void canonicalDigest(long canonicalBytes) {
        }

        default void genericFallback() {
        }
    }

    static String calculateBlueId(FrozenNode node) {
        return calculateBlueId(node, NO_OBSERVER);
    }

    static String calculateBlueId(FrozenNode node, Observer observer) {
        Observer actualObserver = observer != null ? observer : NO_OBSERVER;
        if (!isDirectlySupported(node)) {
            actualObserver.genericFallback();
            return genericNodeBlueId(node);
        }
        Context context = node.isListElementContext() ? Context.LIST_ELEMENT : Context.ROOT;
        int listIndex = node.isListElementContext() ? 0 : -1;
        try {
            return calculateValidatedNode(node, context, listIndex, actualObserver);
        } catch (CanonicalJsonValueWriter.UnsupportedCanonicalValueException exception) {
            actualObserver.genericFallback();
            return genericNodeBlueId(node);
        } catch (IllegalArgumentException exception) {
            actualObserver.genericFallback();
            return genericNodeBlueId(node);
        }
    }

    static String calculateBlueId(List<FrozenNode> nodes) {
        return calculateBlueId(nodes, NO_OBSERVER);
    }

    static String calculateBlueId(List<FrozenNode> nodes, Observer observer) {
        List<FrozenNode> source = nodes == null ? Collections.<FrozenNode>emptyList() : nodes;
        Observer actualObserver = observer != null ? observer : NO_OBSERVER;
        if (!isDirectlySupportedList(source)) {
            actualObserver.genericFallback();
            return genericListBlueId(source);
        }
        try {
            return calculateValidatedList(source, actualObserver);
        } catch (CanonicalJsonValueWriter.UnsupportedCanonicalValueException exception) {
            actualObserver.genericFallback();
            return genericListBlueId(source);
        } catch (IllegalArgumentException exception) {
            actualObserver.genericFallback();
            return genericListBlueId(source);
        }
    }

    /** Explicit compatibility oracle retained for differential testing. */
    static String calculateGenericOracle(FrozenNode node) {
        return genericNodeBlueId(node);
    }

    private enum Context {
        ROOT,
        OBJECT_FIELD,
        LIST_ELEMENT,
        METADATA
    }

    private static String calculateValidatedNode(FrozenNode node,
                                                 Context context,
                                                 int listIndex,
                                                 Observer observer) {
        if (hasReservedPropertyCollision(node)) {
            // FrozenNodeToBlueIdInput writes authored fields first and arbitrary
            // properties last. Reserved property names can therefore replace a
            // field before DirectBlueIdCalculator's empty-map cleaning, and list
            // controls such as $previous have context-sensitive semantics.
            // These builder-only representations are uncommon enough that the
            // full compatibility oracle is the safer path.
            throw new CanonicalJsonValueWriter.UnsupportedCanonicalValueException(FrozenNode.class);
        }
        if (context == Context.LIST_ELEMENT && isEmptyPlaceholder(node)) {
            // $empty's Boolean is a control marker rather than a scalar-node
            // payload, so the helper map refers to the raw Boolean digest.
            String marker = hashRawScalar(Boolean.TRUE, observer);
            return hashFields(Collections.singletonList(HashField.reference(LIST_CONTROL_EMPTY, marker)), observer);
        }
        if (node.isReferenceOnly()) {
            return node.getReferenceBlueId();
        }
        if (node.getPreviousBlueId() != null) {
            // A previous anchor is consumed by calculateValidatedList and does
            // not have an independently observable element identity there.
            return hashFields(Collections.singletonList(
                    HashField.reference(LIST_CONTROL_PREVIOUS, node.getPreviousBlueId())), observer);
        }
        if (node.getItems() != null && isPayloadOnlyList(node)) {
            return calculateValidatedList(node.getItems(), observer);
        }

        List<HashField> fields = new ArrayList<>();
        addRaw(fields, OBJECT_NAME, node.getName());
        addRaw(fields, OBJECT_DESCRIPTION, node.getDescription());

        String valueTypeBlueId = null;
        if (node.frozenValue() != null && node.getType() == null) {
            valueTypeBlueId = inferTypeBlueId(node.frozenValue());
            if (valueTypeBlueId != null) {
                addReference(fields, OBJECT_TYPE, valueTypeBlueId);
            }
        } else if (node.getType() != null) {
            valueTypeBlueId = node.getType().getReferenceBlueId();
            addNodeReference(fields, OBJECT_TYPE, node.getType());
        }
        addNodeReference(fields, OBJECT_ITEM_TYPE, node.getItemType());
        addNodeReference(fields, OBJECT_KEY_TYPE, node.getKeyType());
        addNodeReference(fields, OBJECT_VALUE_TYPE, node.getValueType());
        if (node.getMergePolicy() != null) {
            addReference(fields, OBJECT_MERGE_POLICY, hashScalar(node.getMergePolicy(), observer));
        }
        if (node.frozenValue() != null) {
            addRaw(fields, OBJECT_VALUE, handleValue(node.frozenValue(), valueTypeBlueId));
        }
        if (node.getItems() != null) {
            addReference(fields, OBJECT_ITEMS, calculateValidatedList(node.getItems(), observer));
        }
        if (node.frozenSchemaView() != null) {
            String schemaBlueId = calculateSchemaBlueId(node.frozenSchemaView(), observer);
            addReference(fields, OBJECT_SCHEMA, schemaBlueId);
        }
        addNodeReference(fields, OBJECT_CONTRACTS, node.getContracts());

        if (node.getProperties() != null) {
            for (Map.Entry<String, FrozenNode> entry : node.getProperties().entrySet()) {
                String key = entry.getKey();
                FrozenNode child = entry.getValue();
                if (isOfficialInputKey(key)) {
                    remove(fields, key);
                }
                if (isRawMapKey(key)) {
                    // This representation is legal but unusual: DirectBlueIdCalculator
                    // treats these three map keys as raw JCS values. Preserve it
                    // via the full oracle rather than inventing a composition.
                    throw new CanonicalJsonValueWriter.UnsupportedCanonicalValueException(FrozenNode.class);
                }
                if (!inputCleansToEmptyMap(child)) {
                    addReference(fields, key, child.blueId());
                }
            }
        }
        return hashFields(fields, observer);
    }

    private static String calculateValidatedList(List<FrozenNode> nodes, Observer observer) {
        for (int index = 0; index < nodes.size(); index++) {
            FrozenNode node = nodes.get(index);
            if (node.getPreviousBlueId() != null && index != 0
                    || node.getProperties() != null
                    && node.getProperties().containsKey(LIST_CONTROL_PREVIOUS)
                    || inputCleansToEmptyMap(node)) {
                // Only the whole-list oracle can preserve both the positional
                // control/placeholder semantics and the original nested
                // diagnostic path.
                throw new CanonicalJsonValueWriter.UnsupportedCanonicalValueException(FrozenNode.class);
            }
        }
        String accumulator = hashListEmpty(observer);
        int start = 0;
        if (!nodes.isEmpty() && nodes.get(0).isPreviousOnly()) {
            accumulator = nodes.get(0).getPreviousBlueId();
            start = 1;
        }
        for (int index = start; index < nodes.size(); index++) {
            FrozenNode node = nodes.get(index);
            String elementBlueId = isEmptyPlaceholder(node)
                    ? calculateValidatedNode(node, Context.LIST_ELEMENT, index, observer)
                    : node.blueId();
            accumulator = hashListCons(elementBlueId, accumulator, observer);
        }
        return accumulator;
    }

    private static String calculateSchemaBlueId(Schema schema, Observer observer) {
        List<HashField> fields = new ArrayList<>();
        addSchemaScalar(fields, KEY_REQUIRED,
                schema.getRequired() == null ? null : schema.getRequiredValue(), observer);
        addSchemaScalar(fields, KEY_MIN_LENGTH, schemaValue(schema.getMinLength()), observer);
        addSchemaScalar(fields, KEY_MAX_LENGTH, schemaValue(schema.getMaxLength()), observer);
        addSchemaNumeric(fields, KEY_MINIMUM, schema.getMinimum(), observer);
        addSchemaNumeric(fields, KEY_MAXIMUM, schema.getMaximum(), observer);
        addSchemaNumeric(fields, KEY_EXCLUSIVE_MINIMUM, schema.getExclusiveMinimum(), observer);
        addSchemaNumeric(fields, KEY_EXCLUSIVE_MAXIMUM, schema.getExclusiveMaximum(), observer);
        addSchemaNumeric(fields, KEY_MULTIPLE_OF, schema.getMultipleOf(), observer);
        addSchemaScalar(fields, KEY_MIN_ITEMS, schemaValue(schema.getMinItems()), observer);
        addSchemaScalar(fields, KEY_MAX_ITEMS, schemaValue(schema.getMaxItems()), observer);
        addSchemaScalar(fields, KEY_UNIQUE_ITEMS,
                schema.getUniqueItems() == null ? null : schema.getUniqueItemsValue(), observer);
        addSchemaScalar(fields, KEY_MIN_FIELDS, schemaValue(schema.getMinFields()), observer);
        addSchemaScalar(fields, KEY_MAX_FIELDS, schemaValue(schema.getMaxFields()), observer);
        if (schema.getEnum() != null) {
            String accumulator = hashListEmpty(observer);
            for (Node value : SchemaEnumCanonicalizer.canonicalize(schema.getEnum())) {
                String elementBlueId;
                if (FrozenCanonicalWriter.isPlainScalar(value)) {
                    elementBlueId = hashScalar(value.getValue(), observer);
                } else {
                    FrozenNode frozen = FrozenNode.fromNode(value);
                    if (inputCleansToEmptyMap(frozen)) {
                        throw new CanonicalJsonValueWriter.UnsupportedCanonicalValueException(FrozenNode.class);
                    }
                    elementBlueId = frozen.blueId();
                }
                accumulator = hashListCons(elementBlueId, accumulator, observer);
            }
            addReference(fields, KEY_ENUM, accumulator);
        }
        return fields.isEmpty() ? null : hashFields(fields, observer);
    }

    private static Object schemaValue(Node node) {
        return node == null ? null : node.getValue();
    }

    private static void addSchemaScalar(List<HashField> fields,
                                        String key,
                                        Object value,
                                        Observer observer) {
        if (value != null) addReference(fields, key, hashScalar(value, observer));
    }

    private static void addSchemaNumeric(List<HashField> fields,
                                         String key,
                                         Node value,
                                         Observer observer) {
        if (value == null) return;
        if (FrozenCanonicalWriter.isPlainScalar(value)) {
            addReference(fields, key, hashScalar(value.getValue(), observer));
            return;
        }
        FrozenNode frozen = FrozenNode.fromNode(value);
        if (!inputCleansToEmptyMap(frozen)) {
            addReference(fields, key, frozen.blueId());
        }
    }

    /**
     * Hashes scalar-node sugar, not the bare JSON token.
     *
     * <p>Every scalar in a semantic child position is equivalent to an
     * explicitly typed scalar node. The only raw scalar map positions are
     * {@code name}, {@code description}, and {@code value}; callers add those
     * directly with {@link #addRaw(List, String, Object)}.</p>
     */
    private static String hashScalar(Object value, Observer observer) {
        String typeBlueId = inferScalarNodeTypeBlueId(value);
        List<HashField> fields = new ArrayList<>(2);
        addReference(fields, OBJECT_TYPE, typeBlueId);
        addRaw(fields, OBJECT_VALUE, canonicalScalarNodeValue(value, typeBlueId));
        return hashFields(fields, observer);
    }

    private static String hashRawScalar(final Object value, Observer observer) {
        return hash(new WriteAction() {
            @Override
            public void write(FrozenCanonicalWriter.CanonicalByteSink sink) {
                FrozenCanonicalWriter.writeCanonicalValue(value, sink);
            }
        }, observer);
    }

    private static String inferScalarNodeTypeBlueId(Object value) {
        if (value instanceof String) return TEXT_TYPE_BLUE_ID;
        if (value instanceof Boolean) return BOOLEAN_TYPE_BLUE_ID;
        if (value instanceof BigDecimal || value instanceof Float || value instanceof Double) {
            return DOUBLE_TYPE_BLUE_ID;
        }
        if (value instanceof Number) return INTEGER_TYPE_BLUE_ID;
        throw new IllegalArgumentException(
                "Blue scalar must be Text, Integer, Double, or Boolean.");
    }

    private static Object canonicalScalarNodeValue(Object value, String typeBlueId) {
        if (DOUBLE_TYPE_BLUE_ID.equals(typeBlueId)) {
            return BlueNumbers.toCanonicalDoubleValue(value);
        }
        if (!INTEGER_TYPE_BLUE_ID.equals(typeBlueId)) {
            return value;
        }
        BigInteger integer = value instanceof BigInteger
                ? (BigInteger) value
                : BigInteger.valueOf(((Number) value).longValue());
        return integer.compareTo(BlueNumbers.MIN_INTEROPERABLE_INTEGER) < 0
                || integer.compareTo(BlueNumbers.MAX_INTEROPERABLE_INTEGER) > 0
                ? integer.toString()
                : integer;
    }

    private static String hashFields(List<HashField> source, Observer observer) {
        final HashField[] fields = source.toArray(new HashField[0]);
        Arrays.sort(fields, FIELD_ORDER);
        return hash(new WriteAction() {
            @Override
            public void write(FrozenCanonicalWriter.CanonicalByteSink sink) {
                sink.writeByte('{');
                for (int index = 0; index < fields.length; index++) {
                    if (index > 0) sink.writeByte(',');
                    writeString(fields[index].key, sink);
                    sink.writeByte(':');
                    if (fields[index].reference) {
                        writeReference(fields[index].value, sink);
                    } else {
                        FrozenCanonicalWriter.writeCanonicalValue(fields[index].rawValue, sink);
                    }
                }
                sink.writeByte('}');
            }
        }, observer);
    }

    private static String hashListEmpty(Observer observer) {
        return hash(new WriteAction() {
            @Override
            public void write(FrozenCanonicalWriter.CanonicalByteSink sink) {
                sink.writeByte('{');
                writeString(LIST_SEED_KEY, sink);
                sink.writeByte(':');
                writeString(LIST_SEED_VALUE, sink);
                sink.writeByte('}');
            }
        }, observer);
    }

    private static String hashListCons(final String element,
                                       final String previous,
                                       Observer observer) {
        return hash(new WriteAction() {
            @Override
            public void write(FrozenCanonicalWriter.CanonicalByteSink sink) {
                sink.writeByte('{');
                writeString(LIST_CONS_KEY, sink);
                sink.writeByte(':');
                sink.writeByte('{');
                writeString(LIST_CONS_ELEMENT_KEY, sink);
                sink.writeByte(':');
                writeReference(element, sink);
                sink.writeByte(',');
                writeString(LIST_CONS_PREVIOUS_KEY, sink);
                sink.writeByte(':');
                writeReference(previous, sink);
                sink.writeByte('}');
                sink.writeByte('}');
            }
        }, observer);
    }

    private interface WriteAction {
        void write(FrozenCanonicalWriter.CanonicalByteSink sink);
    }

    private static String hash(WriteAction action, Observer observer) {
        MessageDigest digest = SHA_256.get();
        digest.reset();
        DigestSink sink = new DigestSink(digest);
        try {
            action.write(sink);
            String result = Base58.encode(digest.digest());
            observer.canonicalDigest(sink.bytes);
            return result;
        } finally {
            digest.reset();
        }
    }

    private static final class DigestSink implements FrozenCanonicalWriter.CanonicalByteSink {
        private final MessageDigest digest;
        private long bytes;

        private DigestSink(MessageDigest digest) {
            this.digest = digest;
        }

        @Override
        public void writeByte(int value) {
            digest.update((byte) value);
            bytes++;
        }

        @Override
        public void write(byte[] values, int offset, int length) {
            digest.update(values, offset, length);
            bytes += length;
        }
    }

    private static final class HashField {
        private final String key;
        private final boolean reference;
        private final String value;
        private final Object rawValue;

        private HashField(String key, boolean reference, String value, Object rawValue) {
            this.key = key;
            this.reference = reference;
            this.value = value;
            this.rawValue = rawValue;
        }

        private static HashField reference(String key, String value) {
            return new HashField(key, true, value, null);
        }

        private static HashField raw(String key, Object value) {
            return new HashField(key, false, null, value);
        }
    }

    private static void addRaw(List<HashField> fields, String key, Object value) {
        if (value != null) fields.add(HashField.raw(key, value));
    }

    private static void addReference(List<HashField> fields, String key, String blueId) {
        if (blueId != null) fields.add(HashField.reference(key, blueId));
    }

    private static void addNodeReference(List<HashField> fields, String key, FrozenNode node) {
        if (node != null && !inputCleansToEmptyMap(node)) {
            fields.add(HashField.reference(key, node.blueId()));
        }
    }

    private static void remove(List<HashField> fields, String key) {
        for (int index = fields.size() - 1; index >= 0; index--) {
            if (fields.get(index).key.equals(key)) fields.remove(index);
        }
    }

    private static boolean isRawMapKey(String key) {
        return OBJECT_NAME.equals(key) || OBJECT_VALUE.equals(key) || OBJECT_DESCRIPTION.equals(key);
    }

    private static boolean isOfficialInputKey(String key) {
        return isRawMapKey(key) || OBJECT_TYPE.equals(key) || OBJECT_ITEM_TYPE.equals(key)
                || OBJECT_KEY_TYPE.equals(key) || OBJECT_VALUE_TYPE.equals(key)
                || OBJECT_MERGE_POLICY.equals(key) || OBJECT_ITEMS.equals(key)
                || OBJECT_SCHEMA.equals(key) || OBJECT_CONTRACTS.equals(key);
    }

    private static String genericNodeBlueId(FrozenNode node) {
        if (node == null) {
            return DirectBlueIdCalculator.INSTANCE.directBlueIdFromCanonicalInput(FrozenNodeToBlueIdInput.get(null));
        }
        if (node.isStrictCanonical() && node.isStrictBlueIdValidation()) {
            return DirectBlueIdCalculator.INSTANCE.directBlueIdFromCanonicalInput(FrozenNodeToBlueIdInput.get(node));
        }
        return DirectBlueIdCalculator.calculateUncheckedBlueId(node.toNode());
    }

    private static String genericListBlueId(List<FrozenNode> nodes) {
        List<Object> objects = new ArrayList<>(nodes.size());
        for (int index = 0; index < nodes.size(); index++) {
            objects.add(FrozenNodeToBlueIdInput.getListElement(nodes.get(index), index));
        }
        return DirectBlueIdCalculator.INSTANCE.directBlueIdFromCanonicalInput(objects);
    }

    private static boolean isDirectlySupported(FrozenNode node) {
        try {
            Context context = node != null && node.isListElementContext() ? Context.LIST_ELEMENT : Context.ROOT;
            int index = node != null && node.isListElementContext() ? 0 : -1;
            return node != null
                    && node.isStrictCanonical()
                    && node.isStrictBlueIdValidation()
                    && validate(node, context, index, false);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static boolean isDirectlySupportedList(List<FrozenNode> nodes) {
        try {
            for (int index = 0; index < nodes.size(); index++) {
                FrozenNode node = nodes.get(index);
                if (node == null || !node.isStrictCanonical() || !node.isStrictBlueIdValidation()
                        || !validate(node, Context.LIST_ELEMENT, index, false)
                        || inputCleansToEmptyMap(node)) {
                    return false;
                }
            }
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static boolean validate(FrozenNode node,
                                    Context context,
                                    int listIndex,
                                    boolean typePosition) {
        if (node == null || context == Context.METADATA && typePosition && node.isInlineValue()
                || node.getBlue() != null || node.getPosition() != null
                || node.getProperties() != null && node.getProperties().containsKey(LIST_CONTROL_REPLACE)) {
            return false;
        }
        if (context == Context.LIST_ELEMENT) {
            if (node.isEmptyNode()
                    || node.getProperties() != null && node.getProperties().containsKey(LIST_CONTROL_EMPTY)
                    && !isEmptyPlaceholder(node)
                    || node.getPreviousBlueId() != null && listIndex != 0) {
                return false;
            }
        } else if (node.getPreviousBlueId() != null) {
            return false;
        }
        int payloadKinds = (node.frozenValue() != null ? 1 : 0)
                + (node.getItems() != null ? 1 : 0)
                + (node.getProperties() != null && !node.getProperties().isEmpty() ? 1 : 0);
        if (payloadKinds > 1 || node.getReferenceBlueId() != null && !node.isReferenceOnly()
                || node.getPreviousBlueId() != null && !node.isPreviousOnly()) {
            return false;
        }
        if (node.isReferenceOnly()
                && !BlueIds.isPotentialBlueId(node.getReferenceBlueId())) {
            return false;
        }
        if (node.getPreviousBlueId() != null
                && (BlueIds.hasCyclicMemberSeparator(
                        node.getPreviousBlueId())
                || !BlueIds.isPotentialBlueId(node.getPreviousBlueId()))) {
            return false;
        }
        if (node.frozenValue() != null) {
            String type = node.getType() != null
                    ? node.getType().getReferenceBlueId()
                    : inferTypeBlueId(node.frozenValue());
            Object canonical = handleValue(node.frozenValue(), type);
            // Raw containers pass through the legacy recursive Blue cleaning
            // rules (empty/null removal and list controls).  The direct writer
            // deliberately handles only scalar values until it can prove that
            // full cleaning equivalence, so containers use the generic oracle.
            if (canonical instanceof Map || canonical instanceof List
                    || canonical != null && canonical.getClass().isArray()) return false;
            if (!FrozenCanonicalWriter.supportsCanonicalValue(canonical)) return false;
        }
        if (node.getType() != null && node.getType().isInlineValue()
                || node.getItemType() != null && node.getItemType().isInlineValue()
                || node.getKeyType() != null && node.getKeyType().isInlineValue()
                || node.getValueType() != null && node.getValueType().isInlineValue()) {
            return false;
        }
        if (node.getProperties() != null) {
            if (hasReservedPropertyCollision(node)) {
                return false;
            }
            for (Map.Entry<String, FrozenNode> entry : node.getProperties().entrySet()) {
                if (isRawMapKey(entry.getKey())) {
                    return false;
                }
            }
        }
        if (node.frozenSchemaView() != null
                && !hasDirectSchemaValidationParity(node.frozenSchemaView())) {
            return false;
        }
        return true;
    }

    private static boolean hasDirectSchemaValidationParity(Schema schema) {
        return isPlainSchemaScalarOrNull(schema.getRequired())
                && isPlainSchemaScalarOrNull(schema.getMinLength())
                && isPlainSchemaScalarOrNull(schema.getMaxLength())
                && isPlainSchemaScalarOrNull(schema.getMinItems())
                && isPlainSchemaScalarOrNull(schema.getMaxItems())
                && isPlainSchemaScalarOrNull(schema.getUniqueItems())
                && isPlainSchemaScalarOrNull(schema.getMinFields())
                && isPlainSchemaScalarOrNull(schema.getMaxFields())
                && hasCompatiblePlainSchemaValue(schema.getMinimum())
                && hasCompatiblePlainSchemaValue(schema.getMaximum())
                && hasCompatiblePlainSchemaValue(schema.getExclusiveMinimum())
                && hasCompatiblePlainSchemaValue(schema.getExclusiveMaximum())
                && hasCompatiblePlainSchemaValue(schema.getMultipleOf())
                && hasCompatibleSchemaEnum(schema.getEnum());
    }

    private static boolean isPlainSchemaScalarOrNull(Node node) {
        return node == null || FrozenCanonicalWriter.isPlainScalar(node)
                && !isRawContainer(node.getValue());
    }

    private static boolean hasCompatiblePlainSchemaValue(Node node) {
        return node == null || !FrozenCanonicalWriter.isPlainScalar(node)
                || !isRawContainer(node.getValue());
    }

    private static boolean hasCompatibleSchemaEnum(List<Node> values) {
        if (values == null) {
            return true;
        }
        for (Node value : values) {
            if (!hasCompatiblePlainSchemaValue(value)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isRawContainer(Object value) {
        return value instanceof Map || value instanceof List
                || value != null && value.getClass().isArray();
    }

    static boolean isPayloadOnlyList(FrozenNode node) {
        return node.getItems() != null
                && node.getName() == null && node.getDescription() == null
                && node.getType() == null && node.getItemType() == null
                && node.getKeyType() == null && node.getValueType() == null
                && node.frozenValue() == null && node.getProperties() == null
                && node.getContracts() == null && node.getReferenceBlueId() == null
                && node.frozenSchemaView() == null && node.getMergePolicy() == null
                && node.getPreviousBlueId() == null && node.getPosition() == null
                && node.getBlue() == null;
    }

    static boolean isEmptyPlaceholder(FrozenNode node) {
        if (node == null || node.getProperties() == null || node.getProperties().size() != 1) return false;
        FrozenNode marker = node.getProperties().get(LIST_CONTROL_EMPTY);
        return marker != null && Boolean.TRUE.equals(marker.getValue())
                && marker.getName() == null && marker.getDescription() == null
                && marker.getType() == null && marker.getItemType() == null
                && marker.getKeyType() == null && marker.getValueType() == null
                && marker.getItems() == null && marker.getProperties() == null
                && marker.getContracts() == null && marker.getReferenceBlueId() == null
                && marker.frozenSchemaView() == null && marker.getMergePolicy() == null
                && marker.getPreviousBlueId() == null && marker.getPosition() == null
                && marker.getBlue() == null && node.getName() == null
                && node.getDescription() == null && node.getType() == null
                && node.getItemType() == null && node.getKeyType() == null
                && node.getValueType() == null && node.frozenValue() == null
                && node.getItems() == null && node.getContracts() == null
                && node.getReferenceBlueId() == null && node.frozenSchemaView() == null
                && node.getMergePolicy() == null && node.getPreviousBlueId() == null
                && node.getPosition() == null && node.getBlue() == null;
    }

    private static boolean inputCleansToEmptyMap(FrozenNode node) {
        if (node == null || node.isReferenceOnly() || node.getPreviousBlueId() != null
                || isPayloadOnlyList(node)) return false;
        if (hasReservedPropertyCollision(node)) {
            throw new CanonicalJsonValueWriter.UnsupportedCanonicalValueException(FrozenNode.class);
        }
        if (node.getName() != null || node.getDescription() != null || node.frozenValue() != null
                || node.getItems() != null || node.getMergePolicy() != null) return false;
        if (node.getType() != null && !inputCleansToEmptyMap(node.getType())) return false;
        if (node.getItemType() != null && !inputCleansToEmptyMap(node.getItemType())) return false;
        if (node.getKeyType() != null && !inputCleansToEmptyMap(node.getKeyType())) return false;
        if (node.getValueType() != null && !inputCleansToEmptyMap(node.getValueType())) return false;
        if (node.getContracts() != null && !inputCleansToEmptyMap(node.getContracts())) return false;
        if (node.frozenSchemaView() != null && schemaHasInput(node.frozenSchemaView())) return false;
        if (node.getProperties() != null) {
            for (FrozenNode child : node.getProperties().values()) {
                if (!inputCleansToEmptyMap(child)) return false;
            }
        }
        return true;
    }

    private static boolean schemaHasInput(Schema schema) {
        return schema.getRequired() != null && schema.getRequiredValue() != null
                || schemaValue(schema.getMinLength()) != null
                || schemaValue(schema.getMaxLength()) != null
                || schemaNumericHasInput(schema.getMinimum())
                || schemaNumericHasInput(schema.getMaximum())
                || schemaNumericHasInput(schema.getExclusiveMinimum())
                || schemaNumericHasInput(schema.getExclusiveMaximum())
                || schemaNumericHasInput(schema.getMultipleOf())
                || schemaValue(schema.getMinItems()) != null
                || schemaValue(schema.getMaxItems()) != null
                || schema.getUniqueItems() != null && schema.getUniqueItemsValue() != null
                || schemaValue(schema.getMinFields()) != null
                || schemaValue(schema.getMaxFields()) != null
                || schema.getEnum() != null;
    }

    private static boolean schemaNumericHasInput(Node value) {
        if (value == null) return false;
        if (FrozenCanonicalWriter.isPlainScalar(value)) return true;
        return !inputCleansToEmptyMap(FrozenNode.fromNode(value));
    }

    private static boolean hasReservedPropertyCollision(FrozenNode node) {
        if (node == null || node.getProperties() == null) return false;
        for (String key : node.getProperties().keySet()) {
            if (isOfficialInputKey(key) || OBJECT_BLUE_ID.equals(key)
                    || OBJECT_BLUE.equals(key) || LIST_CONTROL_POS.equals(key)
                    || LIST_CONTROL_PREVIOUS.equals(key) || LIST_CONTROL_REPLACE.equals(key)) {
                return true;
            }
        }
        return false;
    }

    static Object handleValue(Object value, String valueTypeBlueId) {
        if (DOUBLE_TYPE_BLUE_ID.equals(valueTypeBlueId)) return BlueNumbers.toCanonicalDoubleValue(value);
        if (value instanceof BigInteger) {
            BigInteger integer = (BigInteger) value;
            if (integer.compareTo(BlueNumbers.MIN_INTEROPERABLE_INTEGER) < 0
                    || integer.compareTo(BlueNumbers.MAX_INTEROPERABLE_INTEGER) > 0) {
                return integer.toString();
            }
        }
        return value;
    }

    static String inferTypeBlueId(Object value) {
        if (value instanceof String) return TEXT_TYPE_BLUE_ID;
        if (value instanceof BigInteger) return INTEGER_TYPE_BLUE_ID;
        if (value instanceof BigDecimal) return DOUBLE_TYPE_BLUE_ID;
        if (value instanceof Boolean) return BOOLEAN_TYPE_BLUE_ID;
        return null;
    }

    private static void writeReference(String blueId, FrozenCanonicalWriter.CanonicalByteSink sink) {
        sink.writeByte('{');
        writeString(OBJECT_BLUE_ID, sink);
        sink.writeByte(':');
        writeString(blueId, sink);
        sink.writeByte('}');
    }

    private static void writeString(String value, FrozenCanonicalWriter.CanonicalByteSink sink) {
        FrozenCanonicalWriter.writeCanonicalValue(value, sink);
    }
}
