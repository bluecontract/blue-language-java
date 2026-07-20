package blue.language.utils;

import blue.language.model.Node;
import blue.language.model.Schema;

import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Function;

import static blue.language.utils.Nodes.NodeField.*;
import static blue.language.utils.Nodes.hasFieldsAndMayHaveFields;
import static blue.language.utils.Properties.LIST_CONTROL_REPLACE;

public class MergeReverser {

    /**
     * @deprecated Use {@code Blue.canonicalize(source)} or
     * {@link #reverseToCanonicalOverlay(Node, Node)} for Content BlueId identity,
     * or {@link #reverseToMinimizedOverlay(Node)} for author-facing minimized output.
     */
    @Deprecated
    public Node reverse(Node mergedNode) {
        return reverseToMinimizedOverlay(mergedNode);
    }

    public Node reverseToMinimizedOverlay(Node mergedNode) {
        Node minimalNode = new Node();
        reverseNode(minimalNode, mergedNode, mergedNode.getType(), false);
        return minimalNode;
    }

    /**
     * Reconstructs a canonical overlay while retaining pure-reference provenance
     * from the preprocessed source document.
     *
     * @param mergedNode completed resolved view
     * @param sourceNode preprocessed source that produced the resolved view
     * @return strict canonical overlay
     */
    public Node reverseToCanonicalOverlay(Node mergedNode, Node sourceNode) {
        Node minimalNode = new Node();
        reverseNode(minimalNode, mergedNode, mergedNode.getType(), true, sourceNode);
        return minimalNode;
    }

    private void reverseNode(Node minimal, Node merged, Node fromType, boolean canonicalOverlay) {
        reverseNode(minimal, merged, fromType, canonicalOverlay, null);
    }

    private void reverseNode(Node minimal,
                             Node merged,
                             Node fromType,
                             boolean canonicalOverlay,
                             Node source) {

        if (merged.getBlueId() != null
                && fromType != null
                && merged.getBlueId().equals(fromType.getBlueId())
                && !isCanonicalSourceReference(canonicalOverlay, source)) {
            return;
        }

        if (merged.getValue() != null
                && (fromType == null
                || fromType.getValue() == null
                || !Objects.equals(merged.getValue(), fromType.getValue()))) {
            minimal.value(merged.getValue());
        }

        setTypeIfDifferent(merged, fromType, minimal, canonicalOverlay, Node::getType, Node::type);
        setTypeIfDifferent(merged, fromType, minimal, canonicalOverlay, Node::getItemType, Node::itemType);
        setTypeIfDifferent(merged, fromType, minimal, canonicalOverlay, Node::getKeyType, Node::keyType);
        setTypeIfDifferent(merged, fromType, minimal, canonicalOverlay, Node::getValueType, Node::valueType);
        preservePayloadTypeForMetadataOverride(merged, minimal);

        if (merged.getName() != null && (fromType == null || !merged.getName().equals(fromType.getName()))) {
            minimal.name(merged.getName());
        }
        if (merged.getDescription() != null && (fromType == null || !merged.getDescription().equals(fromType.getDescription()))) {
            minimal.description(merged.getDescription());
        }

        if (merged.isReferenceOnly() && (fromType == null || !merged.getBlueId().equals(fromType.getBlueId()))) {
            minimal.blueId(merged.getBlueId());
        }
        if (merged.getMergePolicy() != null && (fromType == null || !merged.getMergePolicy().equals(fromType.getMergePolicy()))) {
            minimal.mergePolicy(merged.getMergePolicy());
        }
        if (merged.getSchema() != null && (fromType == null || !sameSchema(merged.getSchema(), fromType.getSchema()))) {
            minimal.schema(merged.getSchema().clone());
        }
        if (merged.getContracts() != null) {
            Node fromTypeContracts = fromType != null ? fromType.getContracts() : null;
            Node sourceContracts = source != null ? source.getContracts() : null;
            if (!sameNodeBlueId(merged.getContracts(), fromTypeContracts)
                    || isCanonicalSourceReference(canonicalOverlay, sourceContracts)) {
                Node minimalContracts = new Node();
                reverseNode(minimalContracts, merged.getContracts(), fromTypeContracts,
                        canonicalOverlay, sourceContracts);
                if (!Nodes.isEmptyNode(minimalContracts)) {
                    minimal.contracts(minimalContracts);
                }
            }
        }

        if (merged.getItems() != null) {
            List<Node> minimalItems = new ArrayList<>();
            if (canonicalOverlay) {
                for (int index = 0; index < merged.getItems().size(); index++) {
                    Node item = merged.getItems().get(index);
                    Node minimalItem = new Node();
                    reverseNode(minimalItem, item, null, true,
                            sourceItem(source, index, merged.getItems().size()));
                    if (Nodes.isEmptyNode(minimalItem)) {
                        minimalItems.add(Nodes.emptyPlaceholder());
                    } else {
                        minimalItems.add(minimalItem);
                    }
                }
                minimal.items(minimalItems);
            } else if (fromType != null && fromType.getItems() != null) {
                List<Node> inheritedItems = fromType.getItems();
                int inheritedSize = inheritedItems.size();
                if (merged.getItems().size() < inheritedSize) {
                    throw new IllegalStateException("Cannot reverse-minimize a list shorter than its inherited list without an explicit list-deletion control.");
                }
                int commonSize = Math.min(merged.getItems().size(), inheritedSize);

                for (int i = 0; i < commonSize; i++) {
                    if (sameNodeBlueId(merged.getItems().get(i), inheritedItems.get(i))) {
                        continue;
                    }
                    Node minimalItem = new Node();
                    reverseNode(minimalItem, merged.getItems().get(i), inheritedItems.get(i), false, null);
                    if (!Nodes.isEmptyNode(minimalItem)) {
                        minimalItem.position(i);
                        minimalItems.add(minimalItem);
                    }
                }

                for (int i = inheritedSize; i < merged.getItems().size(); i++) {
                    Node minimalItem = new Node();
                    reverseNode(minimalItem, merged.getItems().get(i), null, false, null);
                    minimalItems.add(minimalItem);
                }

                if (!minimalItems.isEmpty()) {
                    String itemsBlueId = BlueIdCalculator.calculateBlueId(inheritedItems);
                    minimalItems.add(0, new Node().previousBlueId(itemsBlueId));
                    minimal.items(minimalItems);
                }
            } else {
                for (Node item : merged.getItems()) {
                    Node minimalItem = new Node();
                    reverseNode(minimalItem, item, null, false, null);
                    minimalItems.add(minimalItem);
                }
                minimal.items(minimalItems);
            }
        }

        if (merged.getProperties() != null) {
            Map<String, Node> minimalProperties = new LinkedHashMap<>();
            for (Map.Entry<String, Node> entry : merged.getProperties().entrySet()) {
                String key = entry.getKey();
                Node mergedProperty = entry.getValue();
                Node fromTypeProperty = null;
                if (fromType != null && fromType.getProperties() != null) {
                    fromTypeProperty = fromType.getProperties().get(key);
                }
                Node sourceProperty = source != null && source.getProperties() != null
                        ? source.getProperties().get(key)
                        : null;
                if (isNonDerivableMaterializedReference(
                        mergedProperty, fromTypeProperty, canonicalOverlay)) {
                    minimalProperties.put(key, new Node().blueId(mergedProperty.getBlueId()));
                    continue;
                }
                if (sameNodeBlueId(mergedProperty, fromTypeProperty)
                        && !isCanonicalSourceReference(canonicalOverlay, sourceProperty)) {
                    continue;
                }
                Node minimalProperty = new Node();
                reverseNode(minimalProperty, mergedProperty, fromTypeProperty,
                        canonicalOverlay, sourceProperty);
                if (!Nodes.isEmptyNode(minimalProperty)) {
                    minimalProperties.put(key, minimalProperty);
                }
            }
            if (!minimalProperties.isEmpty()) {
                minimal.properties(minimalProperties);
            }
        }

        if (canonicalOverlay && source != null && source.isReferenceOnly()) {
            minimal.replaceWith(new Node().blueId(source.getBlueId()));
        }

    }

    private Node sourceItem(Node source, int resolvedIndex, int resolvedSize) {
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
                    Node positioned = item.clone();
                    positioned.position(null);
                    if (positioned.getProperties() != null
                            && positioned.getProperties().containsKey(LIST_CONTROL_REPLACE)) {
                        return positioned.getProperties().get(LIST_CONTROL_REPLACE);
                    }
                    return positioned;
                }
                continue;
            }
            appended.add(item);
        }
        int appendedStart = resolvedSize - appended.size();
        int appendedIndex = resolvedIndex - appendedStart;
        if (appendedIndex >= 0 && appendedIndex < appended.size()) {
            return appended.get(appendedIndex);
        }
        return null;
    }

    private boolean sameSchema(Schema left, Schema right) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null) {
            return false;
        }
        return BlueIdCalculator.calculateBlueId(new Node().schema(left))
                .equals(BlueIdCalculator.calculateBlueId(new Node().schema(right)));
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

    private boolean isCanonicalSourceReference(boolean canonicalOverlay, Node source) {
        return canonicalOverlay && source != null && source.isReferenceOnly();
    }

    private boolean isNonDerivableMaterializedReference(Node mergedProperty,
                                                        Node fromTypeProperty,
                                                        boolean canonicalOverlay) {
        return !canonicalOverlay
                && mergedProperty.getBlueId() != null
                && !mergedProperty.isReferenceOnly()
                && (fromTypeProperty == null
                || !Objects.equals(mergedProperty.getBlueId(), fromTypeProperty.getBlueId()));
    }

    private String comparisonBlueId(Node node) {
        return BlueIdCalculator.INSTANCE.calculate(NodeToBlueIdInput.getWithResolvedBlueIdMetadata(node));
    }

    private void setTypeIfDifferent(Node merged, Node fromType, Node minimal, boolean canonicalOverlay,
                                    Function<Node, Node> typeGetter,
                                    BiConsumer<Node, Node> typeSetter) {
        Node mergedType = typeGetter.apply(merged);
        Node inheritedType = fromType != null ? typeGetter.apply(fromType) : null;
        if (mergedType == null || sameOverlayType(mergedType, inheritedType, canonicalOverlay)) {
            return;
        }

        typeSetter.accept(minimal, overlayTypeNode(mergedType, canonicalOverlay));
    }

    private Node overlayTypeNode(Node mergedType, boolean canonicalOverlay) {
        if (canonicalOverlay || mergedType.getBlueId() != null) {
            return new Node().blueId(mergedType.getBlueId());
        }

        Node minimalType = new Node();
        reverseNode(minimalType, mergedType, mergedType.getType(), false);
        return minimalType;
    }

    private boolean sameOverlayType(Node mergedType, Node inheritedType, boolean canonicalOverlay) {
        if (inheritedType == null) {
            return false;
        }
        if (canonicalOverlay) {
            return inheritedType.getBlueId() != null
                    && inheritedType.getBlueId().equals(mergedType.getBlueId());
        }
        return sameNodeBlueId(mergedType, inheritedType);
    }

    private void preservePayloadTypeForMetadataOverride(Node merged, Node minimal) {
        if (minimal.getType() != null || merged.getType() == null) {
            return;
        }
        if (minimal.getItemType() == null && minimal.getKeyType() == null && minimal.getValueType() == null) {
            return;
        }

        Node mergedType = merged.getType();
        Node typeNode = mergedType.getBlueId() != null
                ? new Node().blueId(mergedType.getBlueId())
                : mergedType.clone();
        minimal.type(typeNode);
    }
}
