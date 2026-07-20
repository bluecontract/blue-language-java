package blue.language.utils;

import blue.language.Blue;
import blue.language.BlueCachePolicy;
import blue.language.model.Node;
import blue.language.model.Schema;
import blue.language.snapshot.FrozenNode;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static blue.language.utils.Properties.*;

/**
 * Fast matcher for already-resolved immutable Blue nodes.
 *
 * <p>The matcher treats the second node as a resolved type/shape pattern. It
 * performs no full document resolve during matching; provider access is limited
 * to resolving type references that are not already embedded in the frozen
 * graph, and those lookups are cached for the lifetime of the matcher.</p>
 */
public final class FrozenTypeMatcher {

    private static final int CACHE_RESOLVED_REFERENCE = 1;
    private static final int CACHE_SUBTYPE = 2;
    private static final int CACHE_MATCH = 3;
    private static final int CACHE_TYPE_COMPATIBILITY = 4;
    private static final int CACHE_UNRESOLVED_REFERENCE = 5;
    private static final Object PRESENT = new Object();

    private final Blue blue;
    private final BoundedPlanCache planCache;
    private final boolean resolveCandidateReferences;

    public FrozenTypeMatcher(Blue blue) {
        this(blue, true);
    }

    FrozenTypeMatcher(Blue blue, boolean resolveCandidateReferences) {
        this(blue,
                resolveCandidateReferences,
                blue != null ? blue.cachePolicy() : BlueCachePolicy.boundedDefaults());
    }

    FrozenTypeMatcher(Blue blue,
                      boolean resolveCandidateReferences,
                      BlueCachePolicy cachePolicy) {
        this.blue = blue;
        this.resolveCandidateReferences = resolveCandidateReferences;
        this.planCache = new BoundedPlanCache(
                Objects.requireNonNull(cachePolicy, "cachePolicy"));
    }

    public boolean matchesType(FrozenNode resolvedNode, FrozenNode resolvedTargetType) {
        if (resolvedTargetType == null) {
            return true;
        }
        if (resolvedNode == null) {
            return !requiresPresence(resolvedTargetType);
        }
        return matches(resolvedNode, resolvedTargetType);
    }

    /** Releases every reloadable matching and type-resolution cache entry. */
    public void clearCaches() {
        planCache.clear();
    }

    /** Returns the number of entries retained across all five matcher cache regions. */
    public int cacheEntryCount() {
        return planCache.size();
    }

    /** Returns the approximate retained weight across all five matcher cache regions. */
    public long cacheWeightBytes() {
        return planCache.currentWeightBytes();
    }

    private boolean matches(FrozenNode node, FrozenNode target) {
        MatchKey key = new MatchKey(
                node.resolvedStructuralKey(),
                target.resolvedStructuralKey(),
                0L);
        Boolean cached = (Boolean) planCache.get(CACHE_MATCH, key);
        if (cached != null) {
            return cached;
        }

        boolean result = computeMatch(node, target);
        MatchKey retainedKey = new MatchKey(
                key.candidate,
                key.target,
                FrozenNode.approximateRetainedWeightBytesOf(node, target));
        planCache.put(CACHE_MATCH, retainedKey, result);
        return result;
    }

    private boolean computeMatch(FrozenNode node, FrozenNode target) {
        if (target.isReferenceOnly()) {
            return referenceMatches(node, target.getReferenceBlueId());
        }
        if (resolveCandidateReferences && node.isReferenceOnly()) {
            FrozenNode resolvedNode = resolveTypeReference(node);
            if (resolvedNode != null && !resolvedNode.isReferenceOnly()) {
                return computeMatch(resolvedNode, target);
            }
        }

        if (!matchesDeclaredType(node, target.getType())) {
            return false;
        }
        if (!valuesEqualWhenSpecified(node.getValue(), target.getValue())) {
            return false;
        }
        if (!matchesSchema(node, target.getSchema())) {
            return false;
        }
        if (!matchesItemType(node, target.getItemType())) {
            return false;
        }
        if (!matchesKeyType(node, target.getKeyType())) {
            return false;
        }
        if (!matchesValueType(node, target.getValueType())) {
            return false;
        }
        if (!matchesItems(node, target.getItems())) {
            return false;
        }
        return matchesProperties(node, target.getProperties());
    }

    private boolean matchesDeclaredType(FrozenNode node, FrozenNode targetType) {
        if (targetType == null) {
            return true;
        }
        if (targetType.isReferenceOnly() && referenceMatches(node, targetType.getReferenceBlueId())) {
            return true;
        }
        if (matchesImplicitStructure(node, targetType)) {
            return true;
        }
        FrozenNode definition = resolveTypeReference(targetType);
        FrozenNode nodeType = node.getType();
        boolean declaredSubtype = nodeType != null && isSubtype(nodeType, targetType);
        boolean definitionConformance = hasTypeDefinitionConstraints(definition) && matches(node, definition);
        if (!declaredSubtype && !definitionConformance) {
            return false;
        }
        if (!matchesCorePayloadKind(node, targetType)) {
            return false;
        }
        return true;
    }

    private boolean matchesImplicitStructure(FrozenNode node, FrozenNode targetType) {
        if (node.getType() != null) {
            return false;
        }
        if (isTextType(targetType)
                || isIntegerType(targetType)
                || isDoubleType(targetType)
                || isBooleanType(targetType)) {
            return node.getValue() != null
                    && node.getItems() == null
                    && node.getProperties() == null
                    && matchesCorePayloadKind(node, targetType);
        }
        if (isListType(targetType)) {
            return node.getItems() != null && node.getValue() == null && node.getProperties() == null;
        }
        if (isDictionaryType(targetType)) {
            return node.getProperties() != null && node.getValue() == null && node.getItems() == null;
        }
        return false;
    }

    private boolean matchesCorePayloadKind(FrozenNode node, FrozenNode targetType) {
        if (isTextType(targetType)) {
            return node.getValue() == null || node.getValue() instanceof String;
        }
        if (isIntegerType(targetType)) {
            return node.getValue() == null || node.getValue() instanceof BigInteger;
        }
        if (isDoubleType(targetType)) {
            return node.getValue() == null
                    || node.getValue() instanceof BigDecimal
                    || node.getValue() instanceof BigInteger;
        }
        if (isBooleanType(targetType)) {
            return node.getValue() == null || node.getValue() instanceof Boolean;
        }
        if (isListType(targetType)) {
            return node.getValue() == null && node.getProperties() == null;
        }
        if (isDictionaryType(targetType)) {
            return node.getValue() == null && node.getItems() == null;
        }
        return true;
    }

    private boolean hasTypeDefinitionConstraints(FrozenNode definition) {
        if (definition == null || CORE_TYPE_BLUE_IDS.contains(typeIdentity(definition))) {
            return false;
        }
        return definition.getType() != null
                || definition.getItemType() != null
                || definition.getKeyType() != null
                || definition.getValueType() != null
                || definition.getValue() != null
                || definition.getItems() != null
                || definition.getProperties() != null
                || definition.getSchema() != null;
    }

    private boolean referenceMatches(FrozenNode node, String targetBlueId) {
        if (targetBlueId == null) {
            return true;
        }
        if (targetBlueId.equals(node.getReferenceBlueId())) {
            return true;
        }
        if (targetBlueId.equals(node.blueId())) {
            return true;
        }
        FrozenNode nodeType = node.getType();
        return nodeType != null && targetBlueId.equals(typeIdentity(nodeType));
    }

    private boolean valuesEqualWhenSpecified(Object nodeValue, Object targetValue) {
        if (targetValue == null) {
            return true;
        }
        if (nodeValue == null) {
            return false;
        }
        if (nodeValue instanceof Number && targetValue instanceof Number) {
            return numberValue(nodeValue).compareTo(numberValue(targetValue)) == 0;
        }
        return nodeValue.equals(targetValue);
    }

    private BigDecimal numberValue(Object value) {
        if (value instanceof BigDecimal) {
            return (BigDecimal) value;
        }
        if (value instanceof BigInteger) {
            return new BigDecimal((BigInteger) value);
        }
        return new BigDecimal(value.toString());
    }

    private boolean matchesItemType(FrozenNode node, FrozenNode targetItemType) {
        if (targetItemType == null) {
            return true;
        }
        if (!isListShaped(node)) {
            return false;
        }
        FrozenNode nodeItemType = node.getItemType();
        boolean declaredCompatible = nodeItemType == null || isSubtype(nodeItemType, targetItemType);
        List<FrozenNode> items = node.getItems();
        if (items == null) {
            return declaredCompatible;
        }
        for (FrozenNode item : items) {
            if (!matchesDeclaredType(item, targetItemType)) {
                return false;
            }
        }
        return true;
    }

    private boolean matchesKeyType(FrozenNode node, FrozenNode targetKeyType) {
        if (targetKeyType == null) {
            return true;
        }
        if (!isDictionaryShaped(node)) {
            return false;
        }
        FrozenNode nodeKeyType = node.getKeyType();
        boolean declaredCompatible = nodeKeyType == null || isSubtype(nodeKeyType, targetKeyType);
        Map<String, FrozenNode> properties = node.getProperties();
        if (properties == null) {
            return declaredCompatible;
        }
        for (String key : properties.keySet()) {
            if (!keyMatchesType(key, targetKeyType)) {
                return false;
            }
        }
        return true;
    }

    private boolean matchesValueType(FrozenNode node, FrozenNode targetValueType) {
        if (targetValueType == null) {
            return true;
        }
        if (!isDictionaryShaped(node)) {
            return false;
        }
        FrozenNode nodeValueType = node.getValueType();
        boolean declaredCompatible = nodeValueType == null || isSubtype(nodeValueType, targetValueType);
        Map<String, FrozenNode> properties = node.getProperties();
        if (properties == null) {
            return declaredCompatible;
        }
        for (FrozenNode value : properties.values()) {
            if (!matchesDeclaredType(value, targetValueType)) {
                return false;
            }
        }
        return true;
    }

    private boolean matchesItems(FrozenNode node, List<FrozenNode> targetItems) {
        if (targetItems == null) {
            return true;
        }
        if (!isListShaped(node)) {
            return false;
        }
        List<FrozenNode> nodeItems = node.getItems() != null ? node.getItems() : Collections.<FrozenNode>emptyList();
        for (int i = 0; i < targetItems.size(); i++) {
            FrozenNode targetItem = targetItems.get(i);
            if (i < nodeItems.size()) {
                if (!matches(nodeItems.get(i), targetItem)) {
                    return false;
                }
            } else if (requiresPresence(targetItem)) {
                return false;
            }
        }
        return true;
    }

    private boolean matchesProperties(FrozenNode node, Map<String, FrozenNode> targetProperties) {
        if (targetProperties == null) {
            return true;
        }
        if (!isDictionaryShaped(node)) {
            return false;
        }
        Map<String, FrozenNode> nodeProperties = node.getProperties() != null
                ? node.getProperties()
                : Collections.<String, FrozenNode>emptyMap();
        for (Map.Entry<String, FrozenNode> entry : targetProperties.entrySet()) {
            FrozenNode nodeProperty = nodeProperties.get(entry.getKey());
            FrozenNode targetProperty = entry.getValue();
            if (nodeProperty != null) {
                if (!matches(nodeProperty, targetProperty)) {
                    return false;
                }
            } else if (requiresPresence(targetProperty)) {
                return false;
            }
        }
        return true;
    }

    private boolean requiresPresence(FrozenNode target) {
        Schema schema = target.getSchema();
        if (schema != null && Boolean.TRUE.equals(schema.getRequiredValue())) {
            return true;
        }
        return hasValueInNestedStructure(target);
    }

    private boolean hasValueInNestedStructure(FrozenNode node) {
        if (node.isReferenceOnly()) {
            return true;
        }
        if (node.getValue() != null) {
            return true;
        }
        if (node.getItems() != null) {
            for (FrozenNode item : node.getItems()) {
                if (hasValueInNestedStructure(item)) {
                    return true;
                }
            }
        }
        if (node.getProperties() != null) {
            for (FrozenNode property : node.getProperties().values()) {
                if (hasValueInNestedStructure(property)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isListShaped(FrozenNode node) {
        return node.getItems() != null
                || node.getItemType() != null
                || (node.getType() != null && isListType(node.getType()));
    }

    private boolean isDictionaryShaped(FrozenNode node) {
        return node.getProperties() != null
                || node.getKeyType() != null
                || node.getValueType() != null
                || (node.getType() != null && isDictionaryType(node.getType()));
    }

    private boolean keyMatchesType(String key, FrozenNode targetKeyType) {
        if (isTextType(targetKeyType)) {
            return true;
        }
        if (isIntegerType(targetKeyType)) {
            try {
                new BigInteger(key);
                return true;
            } catch (NumberFormatException ex) {
                return false;
            }
        }
        if (isDoubleType(targetKeyType)) {
            try {
                double value = Double.parseDouble(key);
                return Double.isFinite(value);
            } catch (NumberFormatException ex) {
                return false;
            }
        }
        if (isBooleanType(targetKeyType)) {
            return "true".equalsIgnoreCase(key) || "false".equalsIgnoreCase(key);
        }
        return false;
    }

    private boolean matchesSchema(FrozenNode node, Schema schema) {
        if (schema == null) {
            return true;
        }
        try {
            verifyWellFormed(schema);
            return verifyRequired(schema, node)
                    && verifyMinLength(schema, node)
                    && verifyMaxLength(schema, node)
                    && verifyMinimum(schema, node)
                    && verifyMaximum(schema, node)
                    && verifyExclusiveMinimum(schema, node)
                    && verifyExclusiveMaximum(schema, node)
                    && verifyMultipleOf(schema, node)
                    && verifyMinItems(schema, node)
                    && verifyMaxItems(schema, node)
                    && verifyUniqueItems(schema, node)
                    && verifyMinFields(schema, node)
                    && verifyMaxFields(schema, node)
                    && verifyEnum(schema, node);
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private void verifyWellFormed(Schema schema) {
        verifyNonNegative(schema.getMinLengthExact());
        verifyNonNegative(schema.getMaxLengthExact());
        verifyMinLessThanOrEqualMax(schema.getMinLengthExact(), schema.getMaxLengthExact());
        verifyNonNegative(schema.getMinItemsExact());
        verifyNonNegative(schema.getMaxItemsExact());
        verifyMinLessThanOrEqualMax(schema.getMinItemsExact(), schema.getMaxItemsExact());
        verifyNonNegative(schema.getMinFieldsExact());
        verifyNonNegative(schema.getMaxFieldsExact());
        verifyMinLessThanOrEqualMax(schema.getMinFieldsExact(), schema.getMaxFieldsExact());
        if (schema.getMinimumValue() != null
                && schema.getMaximumValue() != null
                && schema.getMinimumValue().compareTo(schema.getMaximumValue()) > 0) {
            throw new IllegalArgumentException("minimum must be <= maximum");
        }
        if (schema.getExclusiveMinimumValue() != null
                && schema.getExclusiveMaximumValue() != null
                && schema.getExclusiveMinimumValue().compareTo(schema.getExclusiveMaximumValue()) >= 0) {
            throw new IllegalArgumentException("exclusiveMinimum must be < exclusiveMaximum");
        }
        if (schema.getMultipleOfValue() != null
                && schema.getMultipleOfValue().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("multipleOf must be > 0");
        }
    }

    private void verifyNonNegative(BigInteger value) {
        if (value != null && value.signum() < 0) {
            throw new IllegalArgumentException("schema value must be non-negative");
        }
    }

    private void verifyMinLessThanOrEqualMax(BigInteger min, BigInteger max) {
        if (min != null && max != null && min.compareTo(max) > 0) {
            throw new IllegalArgumentException("schema min must be <= max");
        }
    }

    private boolean verifyRequired(Schema schema, FrozenNode node) {
        return !Boolean.TRUE.equals(schema.getRequiredValue()) || hasPayload(node);
    }

    private boolean verifyMinLength(Schema schema, FrozenNode node) {
        BigInteger minLength = schema.getMinLengthExact();
        Object value = node.getValue();
        if (minLength == null || !hasPayload(node)) {
            return true;
        }
        return value instanceof String
                && BigInteger.valueOf(((String) value).codePointCount(0, ((String) value).length())).compareTo(minLength) >= 0;
    }

    private boolean verifyMaxLength(Schema schema, FrozenNode node) {
        BigInteger maxLength = schema.getMaxLengthExact();
        Object value = node.getValue();
        if (maxLength == null || !hasPayload(node)) {
            return true;
        }
        return value instanceof String
                && BigInteger.valueOf(((String) value).codePointCount(0, ((String) value).length())).compareTo(maxLength) <= 0;
    }

    private boolean verifyMinimum(Schema schema, FrozenNode node) {
        return compareNumber(node, schema.getMinimumValue()) >= 0;
    }

    private boolean verifyMaximum(Schema schema, FrozenNode node) {
        return compareNumber(node, schema.getMaximumValue()) <= 0;
    }

    private boolean verifyExclusiveMinimum(Schema schema, FrozenNode node) {
        return schema.getExclusiveMinimumValue() == null
                || compareNumber(node, schema.getExclusiveMinimumValue()) > 0;
    }

    private boolean verifyExclusiveMaximum(Schema schema, FrozenNode node) {
        return schema.getExclusiveMaximumValue() == null
                || compareNumber(node, schema.getExclusiveMaximumValue()) < 0;
    }

    private boolean verifyMultipleOf(Schema schema, FrozenNode node) {
        BigDecimal multipleOf = schema.getMultipleOfValue();
        Object value = node.getValue();
        if (multipleOf == null || !hasPayload(node)) {
            return true;
        }
        return value instanceof Number && BlueNumbers.isExactBinary64Multiple(value, multipleOf);
    }

    private int compareNumber(FrozenNode node, BigDecimal bound) {
        Object value = node.getValue();
        if (bound == null || !hasPayload(node)) {
            return 0;
        }
        if (!(value instanceof Number)) {
            throw new IllegalArgumentException("numeric schema keyword applies to wrong kind");
        }
        return numberValue(value).compareTo(bound);
    }

    private boolean verifyMinItems(Schema schema, FrozenNode node) {
        BigInteger minItems = schema.getMinItemsExact();
        if (minItems == null || !hasPayload(node)) {
            return true;
        }
        if (node.getValue() != null || (node.getProperties() != null && !node.getProperties().isEmpty())) {
            return false;
        }
        int size = node.getItems() != null ? node.getItems().size() : 0;
        return BigInteger.valueOf(size).compareTo(minItems) >= 0;
    }

    private boolean verifyMaxItems(Schema schema, FrozenNode node) {
        BigInteger maxItems = schema.getMaxItemsExact();
        if (maxItems == null || !hasPayload(node)) {
            return true;
        }
        if (node.getValue() != null || (node.getProperties() != null && !node.getProperties().isEmpty())) {
            return false;
        }
        int size = node.getItems() != null ? node.getItems().size() : 0;
        return BigInteger.valueOf(size).compareTo(maxItems) <= 0;
    }

    private boolean verifyUniqueItems(Schema schema, FrozenNode node) {
        if (!Boolean.TRUE.equals(schema.getUniqueItemsValue()) || !hasPayload(node)) {
            return true;
        }
        if (node.getValue() != null || (node.getProperties() != null && !node.getProperties().isEmpty())) {
            return false;
        }
        if (node.getItems() == null) {
            return true;
        }
        Set<String> itemIds = new HashSet<>();
        for (FrozenNode item : node.getItems()) {
            if (!itemIds.add(item.blueId())) {
                return false;
            }
        }
        return true;
    }

    private boolean verifyMinFields(Schema schema, FrozenNode node) {
        BigInteger minFields = schema.getMinFieldsExact();
        if (minFields == null || !hasPayload(node)) {
            return true;
        }
        if (node.getValue() != null || node.getItems() != null) {
            return false;
        }
        int size = node.getProperties() != null ? node.getProperties().size() : 0;
        return BigInteger.valueOf(size).compareTo(minFields) >= 0;
    }

    private boolean verifyMaxFields(Schema schema, FrozenNode node) {
        BigInteger maxFields = schema.getMaxFieldsExact();
        if (maxFields == null || !hasPayload(node)) {
            return true;
        }
        if (node.getValue() != null || node.getItems() != null) {
            return false;
        }
        int size = node.getProperties() != null ? node.getProperties().size() : 0;
        return BigInteger.valueOf(size).compareTo(maxFields) <= 0;
    }

    private boolean verifyEnum(Schema schema, FrozenNode node) {
        List<Node> enumValues = schema.getEnum();
        if (enumValues == null) {
            return true;
        }
        if (node.getValue() == null) {
            return !hasPayload(node);
        }
        String nodeBlueId = comparableBlueId(node);
        for (Node enumValue : enumValues) {
            Node comparable = enumValue.clone();
            comparable.schema(null);
            if (nodeBlueId.equals(BlueIdCalculator.calculateBlueId(comparable))) {
                return true;
            }
        }
        return false;
    }

    private String comparableBlueId(FrozenNode node) {
        Node comparable = node.toNode();
        comparable.schema(null);
        return BlueIdCalculator.calculateBlueId(comparable);
    }

    private boolean hasPayload(FrozenNode node) {
        return node.isReferenceOnly()
                || node.getValue() != null
                || node.getItems() != null
                || (node.getProperties() != null && !node.getProperties().isEmpty());
    }

    private boolean isSubtype(FrozenNode candidateType, FrozenNode targetType) {
        if (candidateType == null || targetType == null) {
            return false;
        }
        String key = typeIdentity(candidateType) + "->" + typeIdentity(targetType);
        Boolean cached = (Boolean) planCache.get(CACHE_SUBTYPE, key);
        if (cached != null) {
            return cached;
        }

        boolean result = computeSubtype(candidateType, targetType);
        planCache.put(CACHE_SUBTYPE, key, result);
        return result;
    }

    private boolean computeSubtype(FrozenNode candidateType, FrozenNode targetType) {
        FrozenNode current = resolveTypeReference(candidateType);
        Set<String> visited = new HashSet<>();
        while (current != null) {
            String identity = typeIdentity(current);
            if (!visited.add(identity)) {
                return false;
            }
            if (sameType(current, targetType)) {
                return true;
            }
            current = parentType(current);
        }
        return false;
    }

    private FrozenNode parentType(FrozenNode type) {
        FrozenNode resolved = resolveTypeReference(type);
        if (resolved == null) {
            return null;
        }
        return resolved.getType();
    }

    private FrozenNode resolveTypeReference(FrozenNode type) {
        if (type == null) {
            return null;
        }
        if (!type.isReferenceOnly()) {
            return type;
        }
        String blueId = type.getReferenceBlueId();
        if (CORE_TYPE_BLUE_IDS.contains(blueId)) {
            return coreType(blueId);
        }
        if (planCache.get(CACHE_UNRESOLVED_REFERENCE, blueId) != null) {
            return null;
        }
        FrozenNode cached = (FrozenNode) planCache.get(CACHE_RESOLVED_REFERENCE, blueId);
        if (cached != null) {
            return cached;
        }
        FrozenNode resolved;
        try {
            resolved = blue.loadSnapshot(blueId).frozenResolvedRoot();
        } catch (RuntimeException ex) {
            resolved = rawTypeDefinition(blueId);
            if (resolved == null) {
                planCache.put(CACHE_UNRESOLVED_REFERENCE, blueId, PRESENT);
                return null;
            }
        }
        planCache.put(CACHE_RESOLVED_REFERENCE, blueId, resolved);
        return resolved;
    }

    private FrozenNode rawTypeDefinition(String blueId) {
        if (blue == null) {
            return null;
        }
        try {
            List<Node> nodes = blue.getNodeProvider().fetchByBlueId(blueId);
            if (nodes == null || nodes.size() != 1) {
                return null;
            }
            return FrozenNode.fromResolvedNode(blue.preprocess(nodes.get(0).clone()));
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private FrozenNode coreType(String blueId) {
        FrozenNode cached = (FrozenNode) planCache.get(CACHE_RESOLVED_REFERENCE, blueId);
        if (cached != null) {
            return cached;
        }
        FrozenNode core = FrozenNode.fromResolvedNode(new Node().blueId(blueId));
        planCache.put(CACHE_RESOLVED_REFERENCE, blueId, core);
        return core;
    }

    private boolean sameType(FrozenNode left, FrozenNode right) {
        String leftIdentity = typeIdentity(left);
        String rightIdentity = typeIdentity(right);
        if (leftIdentity.equals(rightIdentity)) {
            return true;
        }
        String leftCompatibility = typeCompatibilityIdentity(left);
        String rightCompatibility = typeCompatibilityIdentity(right);
        if (CORE_TYPE_BLUE_IDS.contains(leftCompatibility) || CORE_TYPE_BLUE_IDS.contains(rightCompatibility)) {
            return leftCompatibility.equals(rightCompatibility);
        }
        return leftCompatibility.equals(rightCompatibility);
    }

    private String typeIdentity(FrozenNode type) {
        return type.getReferenceBlueId() != null ? type.getReferenceBlueId() : type.blueId();
    }

    private String typeCompatibilityIdentity(FrozenNode type) {
        FrozenNode resolved = type.isReferenceOnly() ? resolveTypeReference(type) : type;
        if (resolved == null) {
            return typeIdentity(type);
        }
        String identityBlueId = typeIdentity(resolved);
        if (CORE_TYPE_BLUE_IDS.contains(identityBlueId)) {
            return identityBlueId;
        }
        String cacheKey = typeIdentity(resolved) + "|" + resolved.blueId();
        String cached = (String) planCache.get(CACHE_TYPE_COMPATIBILITY, cacheKey);
        if (cached != null) {
            return cached;
        }
        String identity = BlueIdCalculator.calculateBlueId(labelNeutralNode(resolved.toNode()));
        planCache.put(CACHE_TYPE_COMPATIBILITY, cacheKey, identity);
        return identity;
    }

    private Node labelNeutralNode(Node node) {
        Node clone = node.clone();
        stripLabels(clone);
        return clone;
    }

    private void stripLabels(Node node) {
        if (node == null) {
            return;
        }
        node.name(null);
        node.description(null);
        if (node.getBlueId() != null && !node.isReferenceOnly()) {
            node.blueId(null);
        }
        stripLabels(node.getType());
        stripLabels(node.getItemType());
        stripLabels(node.getKeyType());
        stripLabels(node.getValueType());
        stripLabels(node.getBlue());
        stripLabels(node.getContracts());
        if (node.getItems() != null) {
            node.getItems().forEach(this::stripLabels);
        }
        if (node.getProperties() != null) {
            node.getProperties().values().forEach(this::stripLabels);
        }
        stripSchemaLabels(node.getSchema());
    }

    private void stripSchemaLabels(Schema schema) {
        if (schema == null) {
            return;
        }
        stripLabels(schema.getRequired());
        stripLabels(schema.getMinLength());
        stripLabels(schema.getMaxLength());
        stripLabels(schema.getMinimum());
        stripLabels(schema.getMaximum());
        stripLabels(schema.getExclusiveMinimum());
        stripLabels(schema.getExclusiveMaximum());
        stripLabels(schema.getMultipleOf());
        stripLabels(schema.getMinItems());
        stripLabels(schema.getMaxItems());
        stripLabels(schema.getUniqueItems());
        stripLabels(schema.getMinFields());
        stripLabels(schema.getMaxFields());
        if (schema.getEnum() != null) {
            schema.getEnum().forEach(this::stripLabels);
        }
    }

    private boolean isTextType(FrozenNode type) {
        return isSubtype(type, coreType(TEXT_TYPE_BLUE_ID));
    }

    private boolean isIntegerType(FrozenNode type) {
        return isSubtype(type, coreType(INTEGER_TYPE_BLUE_ID));
    }

    private boolean isDoubleType(FrozenNode type) {
        return isSubtype(type, coreType(DOUBLE_TYPE_BLUE_ID));
    }

    private boolean isBooleanType(FrozenNode type) {
        return isSubtype(type, coreType(BOOLEAN_TYPE_BLUE_ID));
    }

    private boolean isListType(FrozenNode type) {
        return isSubtype(type, coreType(LIST_TYPE_BLUE_ID));
    }

    private boolean isDictionaryType(FrozenNode type) {
        return isSubtype(type, coreType(DICTIONARY_TYPE_BLUE_ID));
    }

    private static final class BoundedPlanCache {
        private final int maximumEntries;
        private final long maximumWeightBytes;
        private final long maximumEntryWeightBytes;
        private final LinkedHashMap<PlanCacheKey, CacheEntry> entries =
                new LinkedHashMap<PlanCacheKey, CacheEntry>(16, 0.75f, true);
        private long currentWeightBytes;

        private BoundedPlanCache(BlueCachePolicy policy) {
            this.maximumEntries = policy.conformancePlanMaxEntries();
            this.maximumWeightBytes = policy.conformancePlanMaxWeightBytes();
            this.maximumEntryWeightBytes = Math.min(
                    policy.maximumDerivedEntryWeightBytes(), maximumWeightBytes);
        }

        private synchronized Object get(int region, Object key) {
            CacheEntry entry = entries.get(new PlanCacheKey(region, key));
            return entry != null ? entry.value : null;
        }

        private synchronized void put(int region, Object key, Object value) {
            PlanCacheKey cacheKey = new PlanCacheKey(region, key);
            long weight = estimateWeight(cacheKey, value);
            if (weight > maximumEntryWeightBytes || weight > maximumWeightBytes) {
                return;
            }
            CacheEntry previous = entries.remove(cacheKey);
            if (previous != null) {
                currentWeightBytes -= previous.weightBytes;
            }
            entries.put(cacheKey, new CacheEntry(value, weight));
            currentWeightBytes = saturatedAdd(currentWeightBytes, weight);
            evictToBounds();
        }

        private synchronized void clear() {
            entries.clear();
            currentWeightBytes = 0L;
        }

        private synchronized int size() {
            return entries.size();
        }

        private synchronized long currentWeightBytes() {
            return currentWeightBytes;
        }

        private void evictToBounds() {
            Iterator<Map.Entry<PlanCacheKey, CacheEntry>> iterator = entries.entrySet().iterator();
            while ((entries.size() > maximumEntries
                    || currentWeightBytes > maximumWeightBytes) && iterator.hasNext()) {
                CacheEntry eldest = iterator.next().getValue();
                currentWeightBytes -= eldest.weightBytes;
                iterator.remove();
            }
        }

        private long estimateWeight(PlanCacheKey key, Object value) {
            long weight = 80L + retainedWeight(key.key);
            return saturatedAdd(weight, retainedWeight(value));
        }

        private long retainedWeight(Object value) {
            if (value == null || value == PRESENT || value instanceof Boolean) {
                return 16L;
            }
            if (value instanceof String) {
                return 48L + 2L * ((String) value).length();
            }
            if (value instanceof FrozenNode) {
                return ((FrozenNode) value).approximateRetainedWeightBytes();
            }
            if (value instanceof MatchKey) {
                return ((MatchKey) value).retainedWeightBytes;
            }
            return 128L;
        }

        private long saturatedAdd(long left, long right) {
            return Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right;
        }
    }

    private static final class PlanCacheKey {
        private final int region;
        private final Object key;

        private PlanCacheKey(int region, Object key) {
            this.region = region;
            this.key = Objects.requireNonNull(key, "key");
        }

        @Override
        public boolean equals(Object other) {
            return this == other || other instanceof PlanCacheKey
                    && region == ((PlanCacheKey) other).region
                    && key.equals(((PlanCacheKey) other).key);
        }

        @Override
        public int hashCode() {
            return 31 * region + key.hashCode();
        }
    }

    private static final class CacheEntry {
        private final Object value;
        private final long weightBytes;

        private CacheEntry(Object value, long weightBytes) {
            this.value = Objects.requireNonNull(value, "value");
            this.weightBytes = weightBytes;
        }
    }

    private static final class MatchKey {
        private final FrozenNode.ResolvedStructuralKey candidate;
        private final FrozenNode.ResolvedStructuralKey target;
        private final long retainedWeightBytes;

        private MatchKey(FrozenNode.ResolvedStructuralKey candidate,
                         FrozenNode.ResolvedStructuralKey target,
                         long retainedWeightBytes) {
            this.candidate = candidate;
            this.target = target;
            this.retainedWeightBytes = retainedWeightBytes;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof MatchKey)) {
                return false;
            }
            MatchKey that = (MatchKey) other;
            return candidate.equals(that.candidate) && target.equals(that.target);
        }

        @Override
        public int hashCode() {
            return 31 * candidate.hashCode() + target.hashCode();
        }
    }
}
