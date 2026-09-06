package blue.language.resolve;

import blue.language.identity.CanonicalTypeIdentityLookup;
import blue.language.model.wire.BlueLanguageConstants;

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
import java.util.function.BiConsumer;
import java.util.function.Function;

/** Reconstructs a compact ordinary Source overlay from resolved meaning. */
final class MinimizedOverlayReconstructor {

    private final CanonicalTypeIdentityLookup typeIdentities;

    MinimizedOverlayReconstructor(
            CanonicalTypeIdentityLookup typeIdentities) {
        this.typeIdentities = Objects.requireNonNull(
                typeIdentities, "typeIdentities");
    }

    Node reconstruct(Node resolved) {
        Node minimized = new Node();
        reconstructNode(
                minimized,
                resolved,
                resolved.getType(),
                resolved.getType() != null);
        return minimized;
    }

    private void reconstructNode(
            Node minimized,
            Node resolved,
            Node inherited,
            boolean ownTypeBaseline) {
        if (resolved.getBlueId() != null
                && inherited != null
                && resolved.getBlueId().equals(inherited.getBlueId())) {
            return;
        }
        if (resolved.getValue() != null
                && (inherited == null
                || inherited.getValue() == null
                || !Objects.equals(
                resolved.getValue(), inherited.getValue()))) {
            minimized.value(resolved.getValue())
                    .inlineValue(resolved.isInlineValue());
        }

        setTypeIfDifferent(
                resolved, inherited, minimized, Node::getType, Node::type);
        setTypeIfDifferent(
                resolved, inherited, minimized,
                Node::getItemType, Node::itemType);
        setTypeIfDifferent(
                resolved, inherited, minimized,
                Node::getKeyType, Node::keyType);
        setTypeIfDifferent(
                resolved, inherited, minimized,
                Node::getValueType, Node::valueType);
        preservePayloadTypeForMetadataOverride(resolved, minimized);

        if (resolved.getName() != null
                && (ownTypeBaseline
                || inherited == null
                || !resolved.getName().equals(inherited.getName()))) {
            minimized.name(resolved.getName());
        }
        if (resolved.getDescription() != null
                && (ownTypeBaseline
                || inherited == null
                || !resolved.getDescription().equals(
                inherited.getDescription()))) {
            minimized.description(resolved.getDescription());
        }
        if (resolved.isReferenceOnly()
                && (inherited == null
                || !resolved.getBlueId().equals(inherited.getBlueId()))) {
            minimized.blueId(resolved.getBlueId());
        }
        if (resolved.getMergePolicy() != null
                && (inherited == null
                || !resolved.getMergePolicy().equals(
                inherited.getMergePolicy()))) {
            minimized.mergePolicy(resolved.getMergePolicy());
        }
        if (resolved.getSchema() != null
                && (inherited == null
                || !sameSchema(
                resolved.getSchema(), inherited.getSchema()))) {
            minimized.schema(resolved.getSchema().clone());
        }

        reconstructContracts(minimized, resolved, inherited);
        reconstructItems(minimized, resolved, inherited);
        reconstructProperties(minimized, resolved, inherited);
    }

    private void reconstructContracts(
            Node minimized,
            Node resolved,
            Node inherited) {
        if (resolved.getContracts() == null) {
            return;
        }
        Node inheritedContracts = inherited != null
                ? inherited.getContracts()
                : null;
        if (sameNodeBlueId(
                resolved.getContracts(), inheritedContracts)) {
            return;
        }
        Node result = new Node();
        Node baseline = derivationBaseline(
                inheritedContracts, resolved.getContracts());
        reconstructNode(
                result,
                resolved.getContracts(),
                baseline,
                usesOwnTypeBaseline(
                        inheritedContracts,
                        resolved.getContracts()));
        if (!Nodes.isEmptyNode(result)) {
            minimized.contracts(result);
        }
    }

    private void reconstructItems(
            Node minimized,
            Node resolved,
            Node inherited) {
        if (resolved.getItems() == null) {
            return;
        }
        List<Node> result = new ArrayList<>();
        if (inherited != null && inherited.getItems() != null) {
            minimizeInheritedItems(result, resolved, inherited);
        } else {
            for (Node item : resolved.getItems()) {
                Node minimizedItem = new Node();
                Node baseline = derivationBaseline(null, item);
                reconstructNode(
                        minimizedItem,
                        item,
                        baseline,
                        usesOwnTypeBaseline(null, item));
                result.add(minimizedItem);
            }
        }
        if (!result.isEmpty()
                || inherited == null
                || inherited.getItems() == null) {
            minimized.items(result);
        }
    }

    private void minimizeInheritedItems(
            List<Node> result,
            Node resolved,
            Node inherited) {
        List<Node> inheritedItems = inherited.getItems();
        int inheritedSize = inheritedItems.size();
        boolean appendOnly = BlueLanguageConstants.LIST_MERGE_POLICY_APPEND_ONLY.equals(
                resolved.getMergePolicy() != null
                        ? resolved.getMergePolicy()
                        : inherited.getMergePolicy());
        if (resolved.getItems().size() < inheritedSize) {
            throw new IllegalStateException(
                    "Cannot minimize a list shorter than its inherited list without an explicit list-deletion control.");
        }
        int commonSize = Math.min(
                resolved.getItems().size(), inheritedSize);
        for (int index = 0; index < commonSize; index++) {
            if (sameNodeBlueId(
                    resolved.getItems().get(index),
                    inheritedItems.get(index))) {
                continue;
            }
            if (appendOnly) {
                throw new IllegalStateException(
                        "Cannot minimize a modified inherited item in an append-only list.");
            }
            Node resolvedItem = resolved.getItems().get(index);
            Node inheritedItem = inheritedItems.get(index);
            if (requiresWholeItemReplacement(resolvedItem, inheritedItem)) {
                Node replacement = new Node();
                reconstructNode(replacement, resolvedItem,
                        derivationBaseline(null, resolvedItem),
                        usesOwnTypeBaseline(null, resolvedItem));
                result.add(new Node().position(index)
                        .properties("$replace", replacement));
            } else {
                Node item = new Node();
                reconstructNode(item, resolvedItem, inheritedItem, false);
                if (!Nodes.isEmptyNode(item)) {
                    result.add(item.position(index));
                }
            }
        }
        for (int index = inheritedSize;
             index < resolved.getItems().size();
             index++) {
            Node resolvedItem = resolved.getItems().get(index);
            Node item = new Node();
            Node baseline = derivationBaseline(null, resolvedItem);
            reconstructNode(
                    item,
                    resolvedItem,
                    baseline,
                    usesOwnTypeBaseline(null, resolvedItem));
            result.add(item);
        }
        if (result.isEmpty()) {
            return;
        }
        boolean positional = result.stream()
                .anyMatch(item -> item.getPosition() != null);
        if (appendOnly || !positional) {
            result.add(0, new Node().previousBlueId(
                    comparisonBlueId(inheritedItems)));
        }
    }

    private boolean requiresWholeItemReplacement(Node resolved, Node inherited) {
        if (resolved.getItems() != null || inherited.getItems() != null
                || resolved.isReferenceOnly() || inherited.isReferenceOnly()) {
            return true;
        }
        if (inherited.getProperties() != null) {
            if (resolved.getProperties() == null) {
                return true;
            }
            for (Map.Entry<String, Node> field : inherited.getProperties().entrySet()) {
                if (!sameNodeBlueId(field.getValue(),
                        resolved.getProperties().get(field.getKey()))) {
                    return true;
                }
            }
        }
        return false;
    }

    private void reconstructProperties(
            Node minimized,
            Node resolved,
            Node inherited) {
        if (resolved.getProperties() == null) {
            return;
        }
        if (resolved.getProperties().isEmpty()) {
            if (inherited == null || inherited.getProperties() == null) {
                minimized.properties(new LinkedHashMap<>());
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
            if (isNonDerivableMaterializedReference(
                    resolvedProperty, inheritedProperty)) {
                properties.put(key,
                        new Node().blueId(
                                resolvedProperty.getBlueId()));
                continue;
            }
            if (sameNodeBlueId(resolvedProperty, inheritedProperty)) {
                continue;
            }
            Node result = new Node();
            Node baseline = derivationBaseline(
                    inheritedProperty, resolvedProperty);
            reconstructNode(
                    result,
                    resolvedProperty,
                    baseline,
                    usesOwnTypeBaseline(
                            inheritedProperty, resolvedProperty));
            if (!Nodes.isEmptyNode(result)) {
                properties.put(key, result);
            }
        }
        if (!properties.isEmpty()) {
            minimized.properties(properties);
        }
    }

    private void setTypeIfDifferent(
            Node resolved,
            Node inherited,
            Node minimized,
            Function<Node, Node> getter,
            BiConsumer<Node, Node> setter) {
        Node resolvedType = getter.apply(resolved);
        Node inheritedType = inherited != null
                ? getter.apply(inherited)
                : null;
        if (resolvedType == null
                || sameNodeBlueId(resolvedType, inheritedType)) {
            return;
        }
        setter.accept(minimized, overlayTypeNode(resolvedType));
    }

    private Node overlayTypeNode(Node resolvedType) {
        if (resolvedType.getBlueId() != null) {
            return canonicalTypeReference(resolvedType);
        }
        Node minimizedType = new Node();
        reconstructNode(
                minimizedType,
                resolvedType,
                resolvedType.getType(),
                false);
        return minimizedType;
    }

    private void preservePayloadTypeForMetadataOverride(
            Node resolved,
            Node minimized) {
        if (minimized.getType() != null
                || resolved.getType() == null
                || minimized.getItemType() == null
                && minimized.getKeyType() == null
                && minimized.getValueType() == null) {
            return;
        }
        Node type = resolved.getType();
        minimized.type(overlayTypeNode(type));
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

    private boolean isNonDerivableMaterializedReference(
            Node resolved,
            Node inherited) {
        return resolved.getBlueId() != null
                && !resolved.isReferenceOnly()
                && (inherited == null
                || !Objects.equals(
                resolved.getBlueId(), inherited.getBlueId()));
    }

    private String comparisonBlueId(Node node) {
        return NodeIdentities.calculate(canonicalComparisonNode(node));
    }

    private String comparisonBlueId(List<Node> nodes) {
        List<Node> canonical = new ArrayList<>(nodes.size());
        for (Node node : nodes) {
            canonical.add(canonicalComparisonNode(node));
        }
        return NodeIdentities.calculate(canonical);
    }

    private Node canonicalComparisonNode(Node node) {
        Node canonical = node.clone();
        canonicalizeTypePositions(
                canonical,
                Collections.newSetFromMap(
                        new IdentityHashMap<Node, Boolean>()));
        return canonical;
    }

    private void canonicalizeTypePositions(
            Node node,
            java.util.Set<Node> visited) {
        if (node == null || !visited.add(node)) {
            return;
        }
        if (node.getType() != null) {
            node.type(canonicalTypeReference(node.getType()));
        }
        if (node.getItemType() != null) {
            node.itemType(canonicalTypeReference(node.getItemType()));
        }
        if (node.getKeyType() != null) {
            node.keyType(canonicalTypeReference(node.getKeyType()));
        }
        if (node.getValueType() != null) {
            node.valueType(canonicalTypeReference(node.getValueType()));
        }
        canonicalizeTypePositions(node.getBlue(), visited);
        canonicalizeTypePositions(node.getContracts(), visited);
        if (node.getItems() != null) {
            for (Node item : node.getItems()) {
                canonicalizeTypePositions(item, visited);
            }
        }
        if (node.getProperties() != null) {
            for (Node property : node.getProperties().values()) {
                canonicalizeTypePositions(property, visited);
            }
        }
        canonicalizeSchemaTypePositions(node.getSchema(), visited);
    }

    private void canonicalizeSchemaTypePositions(
            Schema schema,
            java.util.Set<Node> visited) {
        if (schema == null) {
            return;
        }
        canonicalizeTypePositions(schema.getRequired(), visited);
        canonicalizeTypePositions(schema.getMinLength(), visited);
        canonicalizeTypePositions(schema.getMaxLength(), visited);
        canonicalizeTypePositions(schema.getMinimum(), visited);
        canonicalizeTypePositions(schema.getMaximum(), visited);
        canonicalizeTypePositions(schema.getExclusiveMinimum(), visited);
        canonicalizeTypePositions(schema.getExclusiveMaximum(), visited);
        canonicalizeTypePositions(schema.getMultipleOf(), visited);
        canonicalizeTypePositions(schema.getMinItems(), visited);
        canonicalizeTypePositions(schema.getMaxItems(), visited);
        canonicalizeTypePositions(schema.getUniqueItems(), visited);
        canonicalizeTypePositions(schema.getMinFields(), visited);
        canonicalizeTypePositions(schema.getMaxFields(), visited);
        if (schema.getEnum() != null) {
            for (Node value : schema.getEnum()) {
                canonicalizeTypePositions(value, visited);
            }
        }
    }

    private Node canonicalTypeReference(Node completedType) {
        if (completedType.isReferenceOnly()) {
            return new Node().blueId(completedType.getBlueId());
        }
        return new Node().blueId(
                typeIdentities.requireCanonicalTypeBlueId(completedType));
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
