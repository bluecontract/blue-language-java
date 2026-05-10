package blue.language.utils;

import blue.language.Blue;
import blue.language.model.Node;
import blue.language.model.Schema;
import blue.language.snapshot.FrozenNode;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

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

    private final Blue blue;
    private final Map<String, FrozenNode> resolvedReferenceCache = new HashMap<>();
    private final Map<String, Boolean> subtypeCache = new HashMap<>();
    private final Map<String, Boolean> matchCache = new HashMap<>();
    private final Map<String, String> typeCompatibilityIdentityCache = new HashMap<>();
    private final Set<String> unresolvedReferenceCache = new HashSet<>();
    private final boolean resolveCandidateReferences;

    public FrozenTypeMatcher(Blue blue) {
        this(blue, true);
    }

    FrozenTypeMatcher(Blue blue, boolean resolveCandidateReferences) {
        this.blue = blue;
        this.resolveCandidateReferences = resolveCandidateReferences;
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

    private boolean matches(FrozenNode node, FrozenNode target) {
        String key = node.blueId() + "->" + target.blueId();
        Boolean cached = matchCache.get(key);
        if (cached != null) {
            return cached;
        }

        boolean result = computeMatch(node, target);
        matchCache.put(key, result);
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
                    && verifyAllowMultiple(schema, node)
                    && verifyMinLength(schema, node)
                    && verifyMaxLength(schema, node)
                    && verifyPattern(schema, node)
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
        verifyNonNegative(schema.getMinLengthValue());
        verifyNonNegative(schema.getMaxLengthValue());
        verifyMinLessThanOrEqualMax(schema.getMinLengthValue(), schema.getMaxLengthValue());
        verifyNonNegative(schema.getMinItemsValue());
        verifyNonNegative(schema.getMaxItemsValue());
        verifyMinLessThanOrEqualMax(schema.getMinItemsValue(), schema.getMaxItemsValue());
        verifyNonNegative(schema.getMinFieldsValue());
        verifyNonNegative(schema.getMaxFieldsValue());
        verifyMinLessThanOrEqualMax(schema.getMinFieldsValue(), schema.getMaxFieldsValue());
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

    private void verifyNonNegative(Integer value) {
        if (value != null && value < 0) {
            throw new IllegalArgumentException("schema value must be non-negative");
        }
    }

    private void verifyMinLessThanOrEqualMax(Integer min, Integer max) {
        if (min != null && max != null && min > max) {
            throw new IllegalArgumentException("schema min must be <= max");
        }
    }

    private boolean verifyRequired(Schema schema, FrozenNode node) {
        return !Boolean.TRUE.equals(schema.getRequiredValue()) || hasPayload(node);
    }

    private boolean verifyAllowMultiple(Schema schema, FrozenNode node) {
        List<FrozenNode> items = node.getItems();
        return Boolean.TRUE.equals(schema.getAllowMultipleValue()) || items == null || items.size() <= 1;
    }

    private boolean verifyMinLength(Schema schema, FrozenNode node) {
        Integer minLength = schema.getMinLengthValue();
        Object value = node.getValue();
        return minLength == null || !(value instanceof String)
                || ((String) value).codePointCount(0, ((String) value).length()) >= minLength;
    }

    private boolean verifyMaxLength(Schema schema, FrozenNode node) {
        Integer maxLength = schema.getMaxLengthValue();
        Object value = node.getValue();
        return maxLength == null || !(value instanceof String)
                || ((String) value).codePointCount(0, ((String) value).length()) <= maxLength;
    }

    private boolean verifyPattern(Schema schema, FrozenNode node) {
        List<String> patterns = schema.getPatternValue();
        Object value = node.getValue();
        if (patterns == null || !(value instanceof String)) {
            return true;
        }
        for (String pattern : patterns) {
            if (!Pattern.matches(pattern, (String) value)) {
                return false;
            }
        }
        return true;
    }

    private boolean verifyMinimum(Schema schema, FrozenNode node) {
        return compareNumber(node.getValue(), schema.getMinimumValue()) >= 0;
    }

    private boolean verifyMaximum(Schema schema, FrozenNode node) {
        return compareNumber(node.getValue(), schema.getMaximumValue()) <= 0;
    }

    private boolean verifyExclusiveMinimum(Schema schema, FrozenNode node) {
        return schema.getExclusiveMinimumValue() == null
                || compareNumber(node.getValue(), schema.getExclusiveMinimumValue()) > 0;
    }

    private boolean verifyExclusiveMaximum(Schema schema, FrozenNode node) {
        return schema.getExclusiveMaximumValue() == null
                || compareNumber(node.getValue(), schema.getExclusiveMaximumValue()) < 0;
    }

    private boolean verifyMultipleOf(Schema schema, FrozenNode node) {
        BigDecimal multipleOf = schema.getMultipleOfValue();
        Object value = node.getValue();
        if (multipleOf == null || !(value instanceof Number)) {
            return true;
        }
        return numberValue(value).remainder(multipleOf).compareTo(BigDecimal.ZERO) == 0;
    }

    private int compareNumber(Object value, BigDecimal bound) {
        if (bound == null || !(value instanceof Number)) {
            return 0;
        }
        return numberValue(value).compareTo(bound);
    }

    private boolean verifyMinItems(Schema schema, FrozenNode node) {
        Integer minItems = schema.getMinItemsValue();
        int size = node.getItems() != null ? node.getItems().size() : 0;
        return minItems == null || size >= minItems;
    }

    private boolean verifyMaxItems(Schema schema, FrozenNode node) {
        Integer maxItems = schema.getMaxItemsValue();
        int size = node.getItems() != null ? node.getItems().size() : 0;
        return maxItems == null || size <= maxItems;
    }

    private boolean verifyUniqueItems(Schema schema, FrozenNode node) {
        if (!Boolean.TRUE.equals(schema.getUniqueItemsValue()) || node.getItems() == null) {
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
        Integer minFields = schema.getMinFieldsValue();
        int size = node.getProperties() != null ? node.getProperties().size() : 0;
        return minFields == null || size >= minFields;
    }

    private boolean verifyMaxFields(Schema schema, FrozenNode node) {
        Integer maxFields = schema.getMaxFieldsValue();
        int size = node.getProperties() != null ? node.getProperties().size() : 0;
        return maxFields == null || size <= maxFields;
    }

    private boolean verifyEnum(Schema schema, FrozenNode node) {
        List<Node> enumValues = schema.getEnum();
        if (enumValues == null) {
            return true;
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
        Boolean cached = subtypeCache.get(key);
        if (cached != null) {
            return cached;
        }

        boolean result = computeSubtype(candidateType, targetType);
        subtypeCache.put(key, result);
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
        if (unresolvedReferenceCache.contains(blueId)) {
            return null;
        }
        FrozenNode cached = resolvedReferenceCache.get(blueId);
        if (cached != null) {
            return cached;
        }
        FrozenNode resolved;
        try {
            resolved = blue.loadSnapshot(blueId).frozenResolvedRoot();
        } catch (RuntimeException ex) {
            resolved = rawTypeDefinition(blueId);
            if (resolved == null) {
                unresolvedReferenceCache.add(blueId);
                return null;
            }
        }
        resolvedReferenceCache.put(blueId, resolved);
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
        FrozenNode cached = resolvedReferenceCache.get(blueId);
        if (cached != null) {
            return cached;
        }
        FrozenNode core = FrozenNode.fromResolvedNode(new Node()
                .name(CORE_TYPE_BLUE_ID_TO_NAME_MAP.get(blueId))
                .blueId(blueId));
        resolvedReferenceCache.put(blueId, core);
        return core;
    }

    private boolean sameType(FrozenNode left, FrozenNode right) {
        if (typeIdentity(left).equals(typeIdentity(right))) {
            return true;
        }
        return typeCompatibilityIdentity(left).equals(typeCompatibilityIdentity(right));
    }

    private String typeIdentity(FrozenNode type) {
        return type.getReferenceBlueId() != null ? type.getReferenceBlueId() : type.blueId();
    }

    private String typeCompatibilityIdentity(FrozenNode type) {
        FrozenNode resolved = type.isReferenceOnly() ? resolveTypeReference(type) : type;
        if (resolved == null) {
            return typeIdentity(type);
        }
        String cacheKey = typeIdentity(resolved) + "|" + resolved.blueId();
        String cached = typeCompatibilityIdentityCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        String identity = BlueIdCalculator.calculateBlueId(labelNeutralNode(resolved.toNode()));
        typeCompatibilityIdentityCache.put(cacheKey, identity);
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
        stripLabels(schema.getAllowMultiple());
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
        if (schema.getPattern() != null) {
            schema.getPattern().forEach(this::stripLabels);
        }
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
}
