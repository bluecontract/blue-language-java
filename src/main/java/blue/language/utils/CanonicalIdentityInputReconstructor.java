package blue.language.utils;

import blue.language.model.wire.BlueLanguageConstants;

import blue.language.model.Node;
import blue.language.model.NodeIdentities;
import blue.language.model.Schema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Function;

import static blue.language.model.wire.BlueLanguageConstants.LIST_CONTROL_REPLACE;

/** Reconstructs unique direct identity input from resolution and provenance. */
final class CanonicalIdentityInputReconstructor {

    Node reconstruct(Node resolved, Node source) {
        Node canonical = new Node();
        reconstructNode(
                canonical,
                resolved,
                resolved.getType(),
                source,
                resolved.getType() != null);
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
                resolved, inherited, canonical, Node::getType, Node::type);
        setTypeIfDifferent(
                resolved, inherited, canonical,
                Node::getItemType, Node::itemType);
        setTypeIfDifferent(
                resolved, inherited, canonical,
                Node::getKeyType, Node::keyType);
        setTypeIfDifferent(
                resolved, inherited, canonical,
                Node::getValueType, Node::valueType);
        preservePayloadTypeForMetadataOverride(resolved, canonical);

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
        if (resolved.getSchema() != null
                && (inherited == null
                || !sameSchema(
                resolved.getSchema(), inherited.getSchema()))) {
            canonical.schema(resolved.getSchema().clone());
        }

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
            Node canonical,
            Function<Node, Node> getter,
            BiConsumer<Node, Node> setter) {
        Node resolvedType = getter.apply(resolved);
        Node inheritedType = inherited != null
                ? getter.apply(inherited)
                : null;
        if (resolvedType == null
                || inheritedType != null
                && inheritedType.getBlueId() != null
                && inheritedType.getBlueId().equals(
                resolvedType.getBlueId())) {
            return;
        }
        setter.accept(canonical,
                new Node().blueId(resolvedType.getBlueId()));
    }

    private void preservePayloadTypeForMetadataOverride(
            Node resolved,
            Node canonical) {
        if (canonical.getType() != null
                || resolved.getType() == null
                || canonical.getItemType() == null
                && canonical.getKeyType() == null
                && canonical.getValueType() == null) {
            return;
        }
        Node type = resolved.getType();
        canonical.type(type.getBlueId() != null
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
        return NodeIdentities.calculate(new Node().schema(left))
                .equals(NodeIdentities.calculate(
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

    private String comparisonBlueId(Node node) {
        return NodeIdentities.calculate(node);
    }

    private boolean isSourceReference(Node source) {
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
