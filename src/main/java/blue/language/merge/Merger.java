package blue.language.merge;

import blue.language.NodeProvider;
import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;
import blue.language.snapshot.ResolvedReferenceCache;
import blue.language.utils.NodeProviderWrapper;
import blue.language.utils.Types;
import blue.language.utils.limits.Limits;
import blue.language.utils.BlueIdCalculator;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static blue.language.utils.Properties.LIST_MERGE_POLICY_APPEND_ONLY;
import static blue.language.utils.Properties.LIST_MERGE_POLICY_POSITIONAL;
import static blue.language.utils.Properties.LIST_TYPE;
import static blue.language.utils.Properties.LIST_TYPE_BLUE_ID;
import static blue.language.utils.Properties.CORE_TYPE_BLUE_IDS;

public class Merger implements NodeResolver {

    private MergingProcessor mergingProcessor;
    private NodeProvider nodeProvider;
    private ResolvedReferenceCache resolvedReferenceCache;

    public Merger(MergingProcessor mergingProcessor, NodeProvider nodeProvider) {
        this(mergingProcessor, nodeProvider, null);
    }

    public Merger(MergingProcessor mergingProcessor, NodeProvider nodeProvider, ResolvedReferenceCache resolvedReferenceCache) {
        this.mergingProcessor = mergingProcessor;
        this.nodeProvider = NodeProviderWrapper.wrap(nodeProvider);
        this.resolvedReferenceCache = resolvedReferenceCache;
    }

    public void merge(Node target, Node source, Limits limits) {
        if (source.getBlue() != null) {
            throw new IllegalArgumentException("Document contains \"blue\" attribute. Preprocess document before merging.");
        }

        if (source.getType() != null) {
            Node typeNode = source.getType();
            String typeBlueId = typeNode.getBlueId();
            FrozenNode cachedResolvedType = cachedResolvedReference(typeBlueId, limits);
            if (cachedResolvedType != null) {
                Node resolvedType = cachedResolvedType.toNode();
                if (resolvedType.getBlueId() == null) {
                    resolvedType.blueId(typeBlueId);
                }
                source.type(resolvedType);
                mergeObject(target, resolvedType, limits);
            } else {
                if (typeBlueId != null) {
                    extendTypeReference(typeNode, typeBlueId);
                }

                Node resolvedType = resolve(typeNode, limits);
                cacheResolvedReference(typeBlueId, resolvedType, limits);
                source.type(resolvedType);
                merge(target, typeNode, limits);
            }
        }
        mergeObject(target, source, limits);
    }

    private void extendTypeReference(Node typeNode, String blueId) {
        if (CORE_TYPE_BLUE_IDS.contains(blueId)) {
            return;
        }
        List<Node> typeNodes = nodeProvider.fetchByBlueId(blueId);
        if (typeNodes == null || typeNodes.isEmpty()) {
            throw new IllegalArgumentException("No content found for blueId: " + blueId);
        }
        if (typeNodes.size() > 1) {
            throw new IllegalStateException(String.format(
                    "Expected a single node for type with blueId '%s', but found multiple.",
                    blueId
            ));
        }
        typeNode.replaceWith(typeNodes.get(0));
        typeNode.blueId(blueId);
    }

    private FrozenNode cachedResolvedReference(String blueId, Limits limits) {
        if (blueId == null || resolvedReferenceCache == null || limits != Limits.NO_LIMITS) {
            return null;
        }
        return resolvedReferenceCache.get(blueId).orElse(null);
    }

    private void cacheResolvedReference(String blueId, Node resolvedType, Limits limits) {
        if (blueId == null || resolvedReferenceCache == null || limits != Limits.NO_LIMITS) {
            return;
        }
        resolvedReferenceCache.putIfAbsent(blueId, resolvedReferenceCache.freezeResolved(resolvedType));
    }

    private void mergeObject(Node target, Node source, Limits limits) {

        resolveTypeMetadata(source, limits);
        mergingProcessor.process(target, source, nodeProvider, this);

        List<Node> children = source.getItems();
        if (children != null) {
            mergeChildren(target, children, limits);
        }

        Map<String, Node> properties = source.getProperties();
        if (properties != null) {
            properties.forEach((key, value) -> {
                if (limits.shouldMergePathSegment(key, value)) {
                    limits.enterPathSegment(key, value);
                    mergeProperty(target, key, value, limits);
                    limits.exitPathSegment();
                }
            });
        }

        if (source.getBlueId() != null) {
            target.blueId(source.getBlueId());
        }

        mergingProcessor.postProcess(target, source, nodeProvider, this);
    }

    private void mergeChildren(Node target, List<Node> sourceChildren, Limits limits) {
        List<Node> targetChildren = target.getItems();
        String mergePolicy = effectiveMergePolicy(target);

        validateListControlScope(target, sourceChildren);
        validateListControls(sourceChildren, mergePolicy);

        if (targetChildren == null) {
            if (startsWithPrevious(sourceChildren)) {
                targetChildren = resolvePreviousAnchor(sourceChildren.get(0), limits);
                target.items(targetChildren);
                validatePreviousAnchor(targetChildren, sourceChildren.get(0));
                if (LIST_MERGE_POLICY_APPEND_ONLY.equals(mergePolicy)) {
                    mergeAppendOnlyChildren(targetChildren, sourceChildren, limits);
                } else {
                    mergePositionalChildren(targetChildren, sourceChildren, limits);
                }
                return;
            }
            targetChildren = resolveInitialChildren(sourceChildren, limits);
            target.items(targetChildren);
            return;
        }

        if (startsWithPrevious(sourceChildren)) {
            validatePreviousAnchor(targetChildren, sourceChildren.get(0));
        }

        if (LIST_MERGE_POLICY_APPEND_ONLY.equals(mergePolicy)) {
            mergeAppendOnlyChildren(targetChildren, sourceChildren, limits);
        } else {
            mergePositionalChildren(targetChildren, sourceChildren, limits);
        }
    }

    private List<Node> resolveInitialChildren(List<Node> sourceChildren, Limits limits) {
        List<Node> result = new ArrayList<>();
        int start = startsWithPrevious(sourceChildren) ? 1 : 0;
        for (int i = start; i < sourceChildren.size(); i++) {
            Node child = sourceChildren.get(i);
            if (child.getPosition() != null) {
                int position = child.getPosition();
                if (position != result.size()) {
                    throw new IllegalArgumentException("\"$pos\" is out of range for a list without inherited items.");
                }
                child = withoutPosition(child);
            }
            Node resolvedChild = resolveListChild(child, limits, String.valueOf(result.size()));
            if (resolvedChild != null) {
                result.add(resolvedChild);
            }
        }
        return result;
    }

    private void mergeAppendOnlyChildren(List<Node> targetChildren, List<Node> sourceChildren, Limits limits) {
        if (startsWithPrevious(sourceChildren)) {
            appendChildren(targetChildren, sourceChildren, 1, limits);
            return;
        }

        if (sourceChildren.size() < targetChildren.size())
            throw new IllegalArgumentException(String.format(
                    "Subtype of element must not have more items (%d) than the element itself (%d).",
                    targetChildren.size(), sourceChildren.size()
            ));

        for (int i = 0; i < sourceChildren.size(); i++) {
            if (i >= targetChildren.size()) {
                Node resolvedChild = resolveListChild(sourceChildren.get(i), limits, String.valueOf(i));
                if (resolvedChild != null) {
                    targetChildren.add(resolvedChild);
                }
                continue;
            }
            Node sourceChild = resolveListChild(sourceChildren.get(i), limits, String.valueOf(i));
            if (sourceChild == null) {
                continue;
            }
            String sourceBlueId = BlueIdCalculator.calculateBlueId(sourceChild);
            String targetBlueId = BlueIdCalculator.calculateBlueId(targetChildren.get(i));
            if (!sourceBlueId.equals(targetBlueId))
                throw new IllegalArgumentException(String.format(
                        "Append-only list cannot modify inherited item at index %d: source item has blueId '%s', but target item has blueId '%s'.",
                        i, sourceBlueId, targetBlueId
                ));
        }
    }

    private void mergePositionalChildren(List<Node> targetChildren, List<Node> sourceChildren, Limits limits) {
        boolean hasPositionControls = sourceChildren.stream().anyMatch(child -> child.getPosition() != null);
        int start = startsWithPrevious(sourceChildren) ? 1 : 0;

        if (!hasPositionControls) {
            if (startsWithPrevious(sourceChildren)) {
                appendChildren(targetChildren, sourceChildren, start, limits);
                return;
            }
            mergeLegacyPositionalChildren(targetChildren, sourceChildren, start, limits);
            return;
        }

        Set<Integer> positions = new HashSet<>();
        for (int i = start; i < sourceChildren.size(); i++) {
            Node sourceChild = sourceChildren.get(i);
            if (sourceChild.getPosition() != null) {
                int position = sourceChild.getPosition();
                if (position >= targetChildren.size()) {
                    throw new IllegalArgumentException("\"$pos\" is out of range: " + position);
                }
                if (!positions.add(position)) {
                    throw new IllegalArgumentException("Duplicate \"$pos\" value in list: " + position);
                }
                mergeOrReplacePosition(targetChildren, position, withoutPosition(sourceChild), limits);
            } else {
                Node resolvedChild = resolveListChild(sourceChild, limits, String.valueOf(targetChildren.size()));
                if (resolvedChild != null) {
                    targetChildren.add(resolvedChild);
                }
            }
        }
    }

    private void mergeLegacyPositionalChildren(List<Node> targetChildren, List<Node> sourceChildren, int start, Limits limits) {
        int sourceLength = sourceChildren.size() - start;
        if (sourceLength < targetChildren.size()) {
            throw new IllegalArgumentException(String.format(
                    "Subtype of element must not have more items (%d) than the element itself (%d).",
                    targetChildren.size(), sourceLength
            ));
        }

        for (int i = 0; i < sourceLength; i++) {
            Node sourceChild = sourceChildren.get(start + i);
            if (i >= targetChildren.size()) {
                Node resolvedChild = resolveListChild(sourceChild, limits, String.valueOf(i));
                if (resolvedChild != null) {
                    targetChildren.add(resolvedChild);
                }
            } else {
                merge(targetChildren.get(i), sourceChild, limits);
            }
        }
    }

    private void mergeOrReplacePosition(List<Node> targetChildren, int position, Node overlay, Limits limits) {
        if (isEmptyPlaceholder(targetChildren.get(position)) || overlay.getValue() != null || overlay.getItems() != null) {
            Node resolvedChild = resolveListChild(overlay, limits, String.valueOf(position));
            if (resolvedChild != null) {
                targetChildren.set(position, resolvedChild);
            }
            return;
        }
        if (overlay.getType() != null) {
            Node resolvedOverlay = resolveListChild(overlay, limits, String.valueOf(position));
            if (resolvedOverlay != null) {
                mergeObject(targetChildren.get(position), resolvedOverlay, limits);
            }
            return;
        }
        merge(targetChildren.get(position), overlay, limits);
    }

    private void appendChildren(List<Node> targetChildren, List<Node> sourceChildren, int start, Limits limits) {
        for (int i = start; i < sourceChildren.size(); i++) {
            Node resolvedChild = resolveListChild(sourceChildren.get(i), limits, String.valueOf(targetChildren.size()));
            if (resolvedChild != null) {
                targetChildren.add(resolvedChild);
            }
        }
    }

    private List<Node> resolvePreviousAnchor(Node previousAnchor, Limits limits) {
        List<Node> fetched = nodeProvider.fetchByBlueId(previousAnchor.getPreviousBlueId());
        if (fetched == null || fetched.isEmpty()) {
            throw new IllegalArgumentException("No content found for $previous blueId: " + previousAnchor.getPreviousBlueId());
        }

        List<Node> previousChildren = fetched.size() == 1 && fetched.get(0).getItems() != null
                ? fetched.get(0).getItems()
                : fetched;
        List<Node> resolved = new ArrayList<>();
        for (int i = 0; i < previousChildren.size(); i++) {
            Node resolvedChild = resolveListChild(previousChildren.get(i), limits, String.valueOf(i));
            if (resolvedChild != null) {
                resolved.add(resolvedChild);
            }
        }
        return resolved;
    }

    private void validatePreviousAnchor(List<Node> targetChildren, Node previousAnchor) {
        String actualBlueId = BlueIdCalculator.calculateBlueId(targetChildren);
        if (!actualBlueId.equals(previousAnchor.getPreviousBlueId())) {
            throw new IllegalArgumentException("\"$previous\" blueId does not match the inherited list. Expected "
                    + actualBlueId + " but found " + previousAnchor.getPreviousBlueId() + ".");
        }
    }

    private boolean isEmptyPlaceholder(Node node) {
        Map<String, Node> properties = node.getProperties();
        if (properties == null || properties.size() != 1 || !properties.containsKey("$empty")) {
            return false;
        }
        Node marker = properties.get("$empty");
        return Boolean.TRUE.equals(marker.getValue())
                && node.getValue() == null
                && node.getItems() == null
                && node.getType() == null
                && node.getItemType() == null
                && node.getKeyType() == null
                && node.getValueType() == null;
    }

    private Node resolveListChild(Node child, Limits limits, String segment) {
        if (child.getPreviousBlueId() != null || child.getPosition() != null) {
            throw new IllegalArgumentException("List control items must be consumed before resolving list children.");
        }
        if (!limits.shouldMergePathSegment(segment, child)) {
            return null;
        }
        limits.enterPathSegment(segment, child);
        try {
            return resolve(child, limits);
        } finally {
            limits.exitPathSegment();
        }
    }

    private Node withoutPosition(Node node) {
        Node clone = node.clone();
        clone.position(null);
        return clone;
    }

    private boolean startsWithPrevious(List<Node> children) {
        return !children.isEmpty() && children.get(0).getPreviousBlueId() != null;
    }

    private String effectiveMergePolicy(Node node) {
        return node.getMergePolicy() == null ? LIST_MERGE_POLICY_POSITIONAL : node.getMergePolicy();
    }

    private void validateListControlScope(Node target, List<Node> sourceChildren) {
        boolean hasControls = sourceChildren.stream()
                .anyMatch(child -> child.getPreviousBlueId() != null || child.getPosition() != null);
        if (hasControls && !isListTyped(target)) {
            throw new IllegalArgumentException("List control forms require a node of type List.");
        }
    }

    private boolean isListTyped(Node node) {
        Node type = node.getType();
        if (type == null) {
            return false;
        }
        if (LIST_TYPE_BLUE_ID.equals(type.getBlueId())) {
            return true;
        }
        if (LIST_TYPE.equals(type.getName())) {
            return true;
        }
        Object typeValue = type.getValue();
        return LIST_TYPE.equals(typeValue) || Types.isListType(type, nodeProvider);
    }

    private void validateListControls(List<Node> sourceChildren, String mergePolicy) {
        boolean previousSeen = false;
        Set<Integer> positions = new HashSet<>();
        for (int i = 0; i < sourceChildren.size(); i++) {
            Node child = sourceChildren.get(i);
            if (child.getPreviousBlueId() != null) {
                if (i != 0 || previousSeen) {
                    throw new IllegalArgumentException("\"$previous\" must appear only as the first list item.");
                }
                previousSeen = true;
            }
            if (child.getPosition() != null) {
                if (LIST_MERGE_POLICY_APPEND_ONLY.equals(mergePolicy)) {
                    throw new IllegalArgumentException("\"$pos\" is not allowed for append-only lists.");
                }
                if (!positions.add(child.getPosition())) {
                    throw new IllegalArgumentException("Duplicate \"$pos\" value in list: " + child.getPosition());
                }
            }
        }
    }

    private void mergeProperty(Node target, String sourceKey, Node sourceValue, Limits limits) {
        Node node = resolve(sourceValue, limits);

        if (target.getProperties() == null)
            target.properties(new HashMap<>());
        Node targetValue = target.getProperties().get(sourceKey);
        if (targetValue == null)
            target.getProperties().put(sourceKey, node);
        else
            mergeObject(targetValue, node, limits);
    }

    private void resolveTypeMetadata(Node source, Limits limits) {
        source.itemType(resolveTypeMetadataNode(source.getItemType(), limits));
        source.keyType(resolveTypeMetadataNode(source.getKeyType(), limits));
        source.valueType(resolveTypeMetadataNode(source.getValueType(), limits));
    }

    private Node resolveTypeMetadataNode(Node metadataType, Limits limits) {
        if (metadataType == null || metadataType.getBlueId() == null) {
            return metadataType;
        }
        FrozenNode cached = cachedResolvedReference(metadataType.getBlueId(), limits);
        if (cached != null) {
            Node resolved = cached.toNode();
            if (resolved.getBlueId() == null) {
                resolved.blueId(metadataType.getBlueId());
            }
            return resolved;
        }
        extendTypeReference(metadataType, metadataType.getBlueId());
        Node resolved = resolve(metadataType, limits);
        cacheResolvedReference(metadataType.getBlueId(), resolved, limits);
        return resolved;
    }

    @Override
    public Node resolve(Node node, Limits limits) {
        Node resultNode = new Node();
        merge(resultNode, node, limits);
        resultNode.name(node.getName());
        resultNode.description(node.getDescription());
        resultNode.blueId(node.getBlueId());
        return resultNode;
    }
}
