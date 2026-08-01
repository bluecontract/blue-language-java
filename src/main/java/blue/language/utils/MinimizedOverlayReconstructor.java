package blue.language.utils;

import blue.language.model.wire.BlueLanguageConstants;

import blue.language.model.Node;
import blue.language.model.Schema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Function;

/** Reconstructs a compact ordinary Source overlay from resolved meaning. */
final class MinimizedOverlayReconstructor {

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
            Node item = new Node();
            reconstructNode(
                    item,
                    resolved.getItems().get(index),
                    inheritedItems.get(index),
                    false);
            if (!Nodes.isEmptyNode(item)) {
                result.add(item.position(index));
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
                    BlueIdCalculator.calculateBlueId(inheritedItems)));
        }
    }

    private void reconstructProperties(
            Node minimized,
            Node resolved,
            Node inherited) {
        if (resolved.getProperties() == null) {
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
            return new Node().blueId(resolvedType.getBlueId());
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
        minimized.type(type.getBlueId() != null
                ? new Node().blueId(type.getBlueId())
                : type.clone());
    }

    private boolean sameSchema(Schema left, Schema right) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null) {
            return false;
        }
        return BlueIdCalculator.calculateBlueId(new Node().schema(left))
                .equals(BlueIdCalculator.calculateBlueId(
                        new Node().schema(right)));
    }

    private boolean sameNodeBlueId(Node left, Node right) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null) {
            return false;
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
        return BlueIdCalculator.INSTANCE.calculate(
                NodeToBlueIdInput.getWithResolvedBlueIdMetadata(node));
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
