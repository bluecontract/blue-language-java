package blue.language.identity;

import blue.language.model.Node;
import blue.language.model.NodeIdentities;
import blue.language.model.Nodes;
import blue.language.model.Schema;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;

import static blue.language.model.wire.BlueLanguageConstants.LIST_CONTROL_REPLACE;
import static blue.language.model.wire.BlueLanguageConstants.OBJECT_ITEM_TYPE;
import static blue.language.model.wire.BlueLanguageConstants.OBJECT_KEY_TYPE;
import static blue.language.model.wire.BlueLanguageConstants.OBJECT_TYPE;
import static blue.language.model.wire.BlueLanguageConstants.OBJECT_VALUE_TYPE;
import static blue.language.model.wire.BlueLanguageConstants.CORE_TYPE_BLUE_IDS;

/** Reconstructs unique direct identity input from resolution and provenance. */
final class CanonicalIdentityInputReconstructor {

    private final CanonicalTypeIdentityLookup typeIdentities;

    CanonicalIdentityInputReconstructor(
            CanonicalTypeIdentityLookup typeIdentities) {
        this.typeIdentities = Objects.requireNonNull(
                typeIdentities, "typeIdentities");
    }

    Node reconstruct(Node resolved, Node source) {
        Node canonical = new Node();
        reconstructNode(
                canonical,
                resolved,
                resolved.getType(),
                source,
                resolved.getType() != null);
        validateCanonicalTypeReferences(
                canonical,
                "",
                Collections.newSetFromMap(new IdentityHashMap<>()));
        return canonical;
    }

    private void reconstructNode(
            Node canonical,
            Node resolved,
            Node inherited,
            Node source,
            boolean ownTypeBaseline) {
        if (resolved.getBlueId() != null
                && inherited != null
                && resolved.getBlueId().equals(inherited.getBlueId())
                && !isSourceReference(source)) {
            return;
        }

        if (resolved.getValue() != null
                && (inherited == null
                || inherited.getValue() == null
                || !Objects.equals(
                resolved.getValue(), inherited.getValue()))) {
            canonical.value(resolved.getValue())
                    .inlineValue(source != null
                            ? source.isInlineValue()
                            : resolved.isInlineValue());
        }

        setTypeIfDifferent(
                resolved, inherited, source, canonical,
                Node::getType, Node::type, OBJECT_TYPE);
        setTypeIfDifferent(
                resolved, inherited, source, canonical,
                Node::getItemType, Node::itemType, OBJECT_ITEM_TYPE);
        setTypeIfDifferent(
                resolved, inherited, source, canonical,
                Node::getKeyType, Node::keyType, OBJECT_KEY_TYPE);
        setTypeIfDifferent(
                resolved, inherited, source, canonical,
                Node::getValueType, Node::valueType, OBJECT_VALUE_TYPE);
        preservePayloadTypeForMetadataOverride(
                resolved, source, canonical);

        if (source != null && source.getName() != null) {
            canonical.name(source.getName());
        } else if (resolved.getName() != null
                && (ownTypeBaseline
                || inherited == null
                || !resolved.getName().equals(inherited.getName()))) {
            canonical.name(resolved.getName());
        }
        if (source != null && source.getDescription() != null) {
            canonical.description(source.getDescription());
        } else if (resolved.getDescription() != null
                && (ownTypeBaseline
                || inherited == null
                || !resolved.getDescription().equals(
                inherited.getDescription()))) {
            canonical.description(resolved.getDescription());
        }

        if (resolved.isReferenceOnly()
                && (inherited == null
                || !resolved.getBlueId().equals(inherited.getBlueId()))) {
            canonical.blueId(resolved.getBlueId());
        }
        if (resolved.getMergePolicy() != null
                && (inherited == null
                || !resolved.getMergePolicy().equals(
                inherited.getMergePolicy()))) {
            canonical.mergePolicy(resolved.getMergePolicy());
        }
        reconstructSchema(canonical, resolved, inherited, source);

        reconstructContracts(canonical, resolved, inherited, source);
        reconstructItems(canonical, resolved, source);
        reconstructProperties(canonical, resolved, inherited, source);

        if (isSourceReference(source)) {
            canonical.replaceWith(
                    new Node().blueId(source.getBlueId()));
        }
    }

    private void reconstructContracts(
            Node canonical,
            Node resolved,
            Node inherited,
            Node source) {
        if (resolved.getContracts() == null) {
            return;
        }
        Node inheritedContracts = inherited != null
                ? inherited.getContracts()
                : null;
        Node sourceContracts = source != null
                ? source.getContracts()
                : null;
        if (sameNodeBlueId(
                resolved.getContracts(), inheritedContracts)
                && !isSourceReference(sourceContracts)) {
            return;
        }
        Node result = new Node();
        Node baseline = derivationBaseline(
                inheritedContracts, resolved.getContracts());
        reconstructNode(
                result,
                resolved.getContracts(),
                baseline,
                sourceContracts,
                usesOwnTypeBaseline(
                        inheritedContracts,
                        resolved.getContracts()));
        if (!Nodes.isEmptyNode(result)) {
            canonical.contracts(result);
        }
    }

    private void reconstructSchema(
            Node canonical,
            Node resolved,
            Node inherited,
            Node source) {
        Schema resolvedSchema = resolved.getSchema();
        if (resolvedSchema == null) {
            return;
        }
        Schema inheritedSchema = inherited != null
                ? inherited.getSchema()
                : null;
        Schema sourceSchema = source != null
                ? source.getSchema()
                : null;
        boolean sourceReference = isSourceReference(sourceSchema);
        if (!sourceReference
                && sameSchema(resolvedSchema, inheritedSchema)) {
            return;
        }
        /*
         * Resolution materializes schema references through the verified
         * provider boundary on a clone. The untouched Source lane is the exact
         * provenance needed to restore that pure reference here.
         */
        canonical.schema(sourceReference
                ? new Schema().blueId(sourceSchema.getBlueId())
                : canonicalSchema(resolvedSchema));
    }

    private void reconstructItems(
            Node canonical,
            Node resolved,
            Node source) {
        if (resolved.getItems() == null) {
            return;
        }
        List<Node> items = new ArrayList<>();
        for (int index = 0;
             index < resolved.getItems().size();
             index++) {
            Node item = resolved.getItems().get(index);
            Node result = new Node();
            Node baseline = derivationBaseline(null, item);
            reconstructNode(
                    result,
                    item,
                    baseline,
                    sourceItem(source, index, resolved.getItems().size()),
                    usesOwnTypeBaseline(null, item));
            items.add(Nodes.isEmptyNode(result)
                    ? Nodes.emptyPlaceholder()
                    : result);
        }
        canonical.items(items);
    }

    private void reconstructProperties(
            Node canonical,
            Node resolved,
            Node inherited,
            Node source) {
        if (resolved.getProperties() == null) {
            return;
        }
        if (resolved.getProperties().isEmpty()) {
            if (source != null
                    && source.getProperties() != null
                    && source.getProperties().isEmpty()
                    || source == null
                    && (inherited == null
                    || inherited.getProperties() == null)) {
                canonical.properties(new LinkedHashMap<>());
            }
            return;
        }
        Map<String, Node> properties = new LinkedHashMap<>();
        for (Map.Entry<String, Node> entry
                : resolved.getProperties().entrySet()) {
            String key = entry.getKey();
            Node resolvedProperty = entry.getValue();
            Node inheritedProperty = inherited != null
                    && inherited.getProperties() != null
                    ? inherited.getProperties().get(key)
                    : null;
            Node sourceProperty = source != null
                    && source.getProperties() != null
                    ? source.getProperties().get(key)
                    : null;
            if (sameNodeBlueId(resolvedProperty, inheritedProperty)
                    && !isSourceReference(sourceProperty)) {
                continue;
            }
            Node result = new Node();
            Node baseline = derivationBaseline(
                    inheritedProperty, resolvedProperty);
            reconstructNode(
                    result,
                    resolvedProperty,
                    baseline,
                    sourceProperty,
                    usesOwnTypeBaseline(
                            inheritedProperty, resolvedProperty));
            if (!Nodes.isEmptyNode(result)) {
                properties.put(key, result);
            }
        }
        if (!properties.isEmpty()) {
            canonical.properties(properties);
        }
    }

    private Node sourceItem(
            Node source,
            int resolvedIndex,
            int resolvedSize) {
        if (source == null || source.getItems() == null) {
            return null;
        }
        List<Node> appended = new ArrayList<>();
        for (Node item : source.getItems()) {
            if (item.getPreviousBlueId() != null) {
                continue;
            }
            if (item.getPosition() != null) {
                if (item.getPosition() == resolvedIndex) {
                    Node positioned = item.clone().position(null);
                    if (positioned.getProperties() != null
                            && positioned.getProperties().containsKey(
                            LIST_CONTROL_REPLACE)) {
                        return positioned.getProperties().get(
                                LIST_CONTROL_REPLACE);
                    }
                    return positioned;
                }
                continue;
            }
            appended.add(item);
        }
        int appendedIndex = resolvedIndex
                - (resolvedSize - appended.size());
        return appendedIndex >= 0 && appendedIndex < appended.size()
                ? appended.get(appendedIndex)
                : null;
    }

    private void setTypeIfDifferent(
            Node resolved,
            Node inherited,
            Node source,
            Node canonical,
            Function<Node, Node> getter,
            BiConsumer<Node, Node> setter,
            String fieldName) {
        Node resolvedType = getter.apply(resolved);
        Node inheritedType = inherited != null
                ? getter.apply(inherited)
                : null;
        if (resolvedType == null) {
            return;
        }
        String inheritedTypeBlueId = inheritedType != null
                ? effectiveTypeBlueId(inheritedType, "inherited " + fieldName)
                : null;
        Node authoredType = source != null ? getter.apply(source) : null;
        if (inheritedTypeBlueId != null
                && isInheritedPrimitiveContribution(
                        resolvedType, authoredType, inheritedTypeBlueId)) {
            return;
        }
        String resolvedTypeBlueId = effectiveTypeBlueId(
                resolvedType,
                authoredType,
                fieldName);
        if (inheritedTypeBlueId != null) {
            if (resolvedTypeBlueId.equals(inheritedTypeBlueId)) {
                return;
            }
        }
        setter.accept(canonical, pureTypeReference(
                resolvedTypeBlueId, fieldName));
    }

    private boolean isInheritedPrimitiveContribution(
            Node resolvedType, Node authoredType, String inheritedTypeBlueId) {
        if (authoredType == null || !authoredType.isReferenceOnly()
                || !CORE_TYPE_BLUE_IDS.contains(authoredType.getBlueId())
                || !typeIdentities.findCanonicalTypeIdentityEvidence(
                        resolvedType, new Node().blueId(inheritedTypeBlueId)).isPresent()) {
            return false;
        }
        // Primitive inference contributes a payload to the retained inherited
        // type. Its core reference is not the authored source of that custom
        // type; select the proven inherited identity only when ancestry agrees.
        Set<Node> visited = Collections.newSetFromMap(new IdentityHashMap<Node, Boolean>());
        Node current = resolvedType;
        String currentBlueId = inheritedTypeBlueId;
        while (current != null && visited.add(current)) {
            if (authoredType.getBlueId().equals(currentBlueId)) {
                return true;
            }
            current = current.getType();
            if (current != null) {
                currentBlueId = effectiveTypeBlueId(current, "primitive contribution ancestor");
            }
        }
        return false;
    }

    private void preservePayloadTypeForMetadataOverride(
            Node resolved,
            Node source,
            Node canonical) {
        if (canonical.getType() != null
                || resolved.getType() == null
                || canonical.getItemType() == null
                && canonical.getKeyType() == null
                && canonical.getValueType() == null) {
            return;
        }
        Node type = resolved.getType();
        canonical.type(pureTypeReference(
                effectiveTypeBlueId(
                        type,
                        source != null ? source.getType() : null,
                        OBJECT_TYPE),
                OBJECT_TYPE));
    }

    /**
     * Returns the canonical identity of one completed effective type. An
     * exact pure reference is already canonical identity input; every
     * materialized body requires resolver-issued sidecar evidence.
     */
    private String effectiveTypeBlueId(
            Node resolvedType,
            String fieldName) {
        return effectiveTypeBlueId(resolvedType, null, fieldName);
    }

    private String effectiveTypeBlueId(
            Node resolvedType,
            Node authoredTypeSource,
            String fieldName) {
        if (resolvedType.isReferenceOnly()) {
            return requireTypeBlueId(
                    resolvedType.getBlueId(), fieldName);
        }
        try {
            return requireTypeBlueId(
                    typeIdentities.requireCanonicalTypeBlueId(
                            resolvedType, authoredTypeSource),
                    fieldName);
        } catch (IllegalStateException missingEvidence) {
            throw new IllegalStateException(
                    "Canonical identity requires resolver-issued evidence for "
                            + fieldName,
                    missingEvidence);
        }
    }

    private String requireTypeBlueId(String blueId, String fieldName) {
        if (blueId == null || blueId.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "Canonical identity requires a nonempty BlueId for "
                            + fieldName);
        }
        BlueIdReferenceValidator.validate(new Node().blueId(blueId));
        return blueId;
    }

    private Node pureTypeReference(String blueId, String fieldName) {
        return new Node().blueId(requireTypeBlueId(blueId, fieldName));
    }


    private void validateCanonicalTypeReferences(
            Node node,
            String path,
            Set<Node> visited) {
        if (node == null || !visited.add(node)) {
            return;
        }
        requireCanonicalTypeReference(node.getType(), path + "/type");
        requireCanonicalTypeReference(
                node.getItemType(), path + "/itemType");
        requireCanonicalTypeReference(
                node.getKeyType(), path + "/keyType");
        requireCanonicalTypeReference(
                node.getValueType(), path + "/valueType");
        validateCanonicalTypeReferences(
                node.getContracts(), path + "/contracts", visited);
        if (node.getItems() != null) {
            for (int index = 0; index < node.getItems().size(); index++) {
                validateCanonicalTypeReferences(
                        node.getItems().get(index),
                        path + "/items/" + index,
                        visited);
            }
        }
        if (node.getProperties() != null) {
            for (Map.Entry<String, Node> entry
                    : node.getProperties().entrySet()) {
                validateCanonicalTypeReferences(
                        entry.getValue(),
                        path + "/" + entry.getKey(),
                        visited);
            }
        }
        validateCanonicalSchemaTypeReferences(
                node.getSchema(), path + "/schema", visited);
    }

    private Schema canonicalSchema(Schema resolvedSchema) {
        if (resolvedSchema.isReferenceOnly()) {
            return new Schema().blueId(resolvedSchema.getBlueId());
        }
        Schema canonical = new Schema()
                .required(canonicalSchemaValue(resolvedSchema.getRequired()))
                .minLength(canonicalSchemaValue(resolvedSchema.getMinLength()))
                .maxLength(canonicalSchemaValue(resolvedSchema.getMaxLength()))
                .minimum(canonicalSchemaValue(resolvedSchema.getMinimum()))
                .maximum(canonicalSchemaValue(resolvedSchema.getMaximum()))
                .exclusiveMinimum(canonicalSchemaValue(
                        resolvedSchema.getExclusiveMinimum()))
                .exclusiveMaximum(canonicalSchemaValue(
                        resolvedSchema.getExclusiveMaximum()))
                .multipleOf(canonicalSchemaValue(
                        resolvedSchema.getMultipleOf()))
                .minItems(canonicalSchemaValue(resolvedSchema.getMinItems()))
                .maxItems(canonicalSchemaValue(resolvedSchema.getMaxItems()))
                .uniqueItems(canonicalSchemaValue(
                        resolvedSchema.getUniqueItems()))
                .minFields(canonicalSchemaValue(
                        resolvedSchema.getMinFields()))
                .maxFields(canonicalSchemaValue(
                        resolvedSchema.getMaxFields()));
        if (resolvedSchema.getEnum() != null) {
            canonical.enumValues(
                    SchemaEnumCanonicalizer.canonicalizeResolved(
                            resolvedSchema.getEnum(), typeIdentities));
        }
        return canonical;
    }

    private Node canonicalSchemaValue(Node value) {
        if (value == null) {
            return null;
        }
        if (value.isReferenceOnly()) {
            return new Node().blueId(value.getBlueId());
        }
        return ScalarNodeIdentity.normalizedResolved(
                value, typeIdentities);
    }

    private void validateCanonicalSchemaTypeReferences(
            Schema schema,
            String path,
            Set<Node> visited) {
        if (schema == null) {
            return;
        }
        validateCanonicalTypeReferences(
                schema.getRequired(), path + "/required", visited);
        validateCanonicalTypeReferences(
                schema.getMinLength(), path + "/minLength", visited);
        validateCanonicalTypeReferences(
                schema.getMaxLength(), path + "/maxLength", visited);
        validateCanonicalTypeReferences(
                schema.getMinimum(), path + "/minimum", visited);
        validateCanonicalTypeReferences(
                schema.getMaximum(), path + "/maximum", visited);
        validateCanonicalTypeReferences(
                schema.getExclusiveMinimum(),
                path + "/exclusiveMinimum", visited);
        validateCanonicalTypeReferences(
                schema.getExclusiveMaximum(),
                path + "/exclusiveMaximum", visited);
        validateCanonicalTypeReferences(
                schema.getMultipleOf(), path + "/multipleOf", visited);
        validateCanonicalTypeReferences(
                schema.getMinItems(), path + "/minItems", visited);
        validateCanonicalTypeReferences(
                schema.getMaxItems(), path + "/maxItems", visited);
        validateCanonicalTypeReferences(
                schema.getUniqueItems(), path + "/uniqueItems", visited);
        validateCanonicalTypeReferences(
                schema.getMinFields(), path + "/minFields", visited);
        validateCanonicalTypeReferences(
                schema.getMaxFields(), path + "/maxFields", visited);
        if (schema.getEnum() != null) {
            for (int index = 0; index < schema.getEnum().size(); index++) {
                validateCanonicalTypeReferences(
                        schema.getEnum().get(index),
                        path + "/enum/" + index,
                        visited);
            }
        }
    }

    private void requireCanonicalTypeReference(Node type, String path) {
        if (type == null) {
            return;
        }
        if (!type.isReferenceOnly() || type.getBlueId() == null) {
            throw new IllegalStateException(
                    "Canonical identity output requires a pure type "
                            + "reference at " + path);
        }
        try {
            requireTypeBlueId(type.getBlueId(), path);
        } catch (IllegalArgumentException invalidBlueId) {
            throw new IllegalStateException(
                    "Canonical identity output contains an invalid type "
                            + "reference at " + path,
                    invalidBlueId);
        }
    }

    private boolean sameSchema(Schema left, Schema right) {
        if (left == null || right == null) {
            return left == null && right == null;
        }
        return comparisonBlueId(new Node().schema(left))
                .equals(comparisonBlueId(new Node().schema(right)));
    }

    private boolean sameNodeBlueId(Node left, Node right) {
        if (left == null || right == null) {
            return left == null && right == null;
        }
        return comparisonBlueId(left).equals(comparisonBlueId(right));
    }

    private String comparisonBlueId(Node node) {
        Node normalized = node.clone();
        normalizeCompletedTypeRepresentations(
                normalized,
                Collections.newSetFromMap(new IdentityHashMap<>()));
        return NodeIdentities.calculate(normalized);
    }

    private void normalizeCompletedTypeRepresentations(
            Node node, Set<Node> visited) {
        if (node == null || !visited.add(node)) {
            return;
        }
        node.type(normalizedTypeReference(node.getType(), OBJECT_TYPE));
        node.itemType(normalizedTypeReference(
                node.getItemType(), OBJECT_ITEM_TYPE));
        node.keyType(normalizedTypeReference(
                node.getKeyType(), OBJECT_KEY_TYPE));
        node.valueType(normalizedTypeReference(
                node.getValueType(), OBJECT_VALUE_TYPE));
        normalizeCompletedTypeRepresentations(node.getContracts(), visited);
        normalizeCompletedTypeRepresentations(node.getBlue(), visited);
        if (node.getItems() != null) {
            for (Node item : node.getItems()) {
                normalizeCompletedTypeRepresentations(item, visited);
            }
        }
        if (node.getProperties() != null) {
            for (Node property : node.getProperties().values()) {
                normalizeCompletedTypeRepresentations(property, visited);
            }
        }
        normalizeSchemaTypeRepresentations(node.getSchema(), visited);
    }

    private Node normalizedTypeReference(Node type, String fieldName) {
        if (type == null) {
            return null;
        }
        if (type.isReferenceOnly()) {
            return pureTypeReference(type.getBlueId(), fieldName);
        }
        return pureTypeReference(
                effectiveTypeBlueId(type, fieldName), fieldName);
    }

    private void normalizeSchemaTypeRepresentations(
            Schema schema, Set<Node> visited) {
        if (schema == null) {
            return;
        }
        normalizeCompletedTypeRepresentations(schema.getRequired(), visited);
        normalizeCompletedTypeRepresentations(schema.getMinLength(), visited);
        normalizeCompletedTypeRepresentations(schema.getMaxLength(), visited);
        normalizeCompletedTypeRepresentations(schema.getMinimum(), visited);
        normalizeCompletedTypeRepresentations(schema.getMaximum(), visited);
        normalizeCompletedTypeRepresentations(
                schema.getExclusiveMinimum(), visited);
        normalizeCompletedTypeRepresentations(
                schema.getExclusiveMaximum(), visited);
        normalizeCompletedTypeRepresentations(schema.getMultipleOf(), visited);
        normalizeCompletedTypeRepresentations(schema.getMinItems(), visited);
        normalizeCompletedTypeRepresentations(schema.getMaxItems(), visited);
        normalizeCompletedTypeRepresentations(schema.getUniqueItems(), visited);
        normalizeCompletedTypeRepresentations(schema.getMinFields(), visited);
        normalizeCompletedTypeRepresentations(schema.getMaxFields(), visited);
        if (schema.getEnum() != null) {
            for (Node value : schema.getEnum()) {
                normalizeCompletedTypeRepresentations(value, visited);
            }
        }
    }

    private boolean isSourceReference(Node source) {
        return source != null && source.isReferenceOnly();
    }

    private boolean isSourceReference(Schema source) {
        return source != null && source.isReferenceOnly();
    }

    private Node derivationBaseline(Node inherited, Node resolved) {
        return inherited != null
                ? inherited
                : resolved != null ? resolved.getType() : null;
    }

    private boolean usesOwnTypeBaseline(Node inherited, Node resolved) {
        return inherited == null
                && resolved != null
                && resolved.getType() != null;
    }
}
