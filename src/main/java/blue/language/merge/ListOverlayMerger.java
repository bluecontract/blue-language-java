package blue.language.merge;

import blue.language.NodeProvider;
import blue.language.model.Node;
import blue.language.utils.BlueIdCalculator;
import blue.language.utils.Properties;
import blue.language.utils.Types;
import blue.language.utils.limits.Limits;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static blue.language.utils.Properties.LIST_CONTROL_REPLACE;
import static blue.language.utils.Properties.LIST_MERGE_POLICY_APPEND_ONLY;
import static blue.language.utils.Properties.LIST_MERGE_POLICY_POSITIONAL;
import static blue.language.utils.Properties.LIST_TYPE;
import static blue.language.utils.Properties.LIST_TYPE_BLUE_ID;

/**
 * Applies the positional, append-only, {@code $previous}, {@code $pos}, and
 * {@code $replace} rules for list overlays.
 *
 * <p>The collaborator is invocation-local through its owning
 * {@link ResolutionEngine}; it does not retain list state between calls.</p>
 */
final class ListOverlayMerger {

    private final ResolutionEngine engine;
    private final NodeProvider nodeProvider;

    ListOverlayMerger(ResolutionEngine engine, NodeProvider nodeProvider) {
        this.engine = engine;
        this.nodeProvider = nodeProvider;
    }

    void mergeChildren(Node target, List<Node> sourceChildren, Limits limits) {
        List<Node> targetChildren = target.getItems();
        String mergePolicy = effectiveMergePolicy(target);
        validateListControlScope(target, sourceChildren);
        validateListControls(sourceChildren, mergePolicy);

        if (targetChildren == null) {
            if (startsWithPrevious(sourceChildren)) {
                targetChildren = resolvePreviousAnchor(
                        sourceChildren.get(0), limits, target.getItemType());
                target.items(targetChildren);
                validatePreviousAnchor(targetChildren, sourceChildren.get(0));
                if (LIST_MERGE_POLICY_APPEND_ONLY.equals(mergePolicy)) {
                    mergeAppendOnlyChildren(targetChildren, sourceChildren, limits, target.getItemType());
                } else {
                    mergePositionalChildren(targetChildren, sourceChildren, limits, target.getItemType());
                }
                return;
            }
            target.items(resolveInitialChildren(sourceChildren, limits, target.getItemType()));
            return;
        }

        if (startsWithPrevious(sourceChildren)) {
            validatePreviousAnchor(targetChildren, sourceChildren.get(0));
        }
        if (LIST_MERGE_POLICY_APPEND_ONLY.equals(mergePolicy)) {
            mergeAppendOnlyChildren(targetChildren, sourceChildren, limits, target.getItemType());
        } else {
            mergePositionalChildren(targetChildren, sourceChildren, limits, target.getItemType());
        }
    }

    private List<Node> resolveInitialChildren(
            List<Node> sourceChildren, Limits limits, Node itemType) {
        List<Node> result = new ArrayList<>();
        int start = startsWithPrevious(sourceChildren) ? 1 : 0;
        for (int index = start; index < sourceChildren.size(); index++) {
            Node child = sourceChildren.get(index);
            if (child.getPosition() != null) {
                int position = child.getPosition();
                if (position != result.size()) {
                    throw new IllegalArgumentException(
                            "\"$pos\" is out of range for a list without inherited items.");
                }
                child = withoutPosition(child);
            }
            Node resolved = resolveListChild(
                    child, limits, String.valueOf(result.size()), itemType);
            if (resolved != null) {
                result.add(resolved);
            }
        }
        return result;
    }

    private void mergeAppendOnlyChildren(
            List<Node> targetChildren,
            List<Node> sourceChildren,
            Limits limits,
            Node itemType) {
        appendChildren(targetChildren, sourceChildren,
                startsWithPrevious(sourceChildren) ? 1 : 0, limits, itemType);
    }

    private void mergePositionalChildren(
            List<Node> targetChildren,
            List<Node> sourceChildren,
            Limits limits,
            Node itemType) {
        boolean hasPositionControls = sourceChildren.stream()
                .anyMatch(child -> child.getPosition() != null);
        int start = startsWithPrevious(sourceChildren) ? 1 : 0;
        if (!hasPositionControls) {
            if (start > 0) {
                appendChildren(targetChildren, sourceChildren, start, limits, itemType);
            } else {
                mergePlainPositionalChildren(
                        targetChildren, sourceChildren, start, limits, itemType);
            }
            return;
        }

        Set<Integer> positions = new HashSet<>();
        for (int index = start; index < sourceChildren.size(); index++) {
            Node sourceChild = sourceChildren.get(index);
            if (sourceChild.getPosition() != null) {
                int position = sourceChild.getPosition();
                if (position >= targetChildren.size()) {
                    throw new IllegalArgumentException(
                            "\"$pos\" is out of range: " + position);
                }
                if (!positions.add(position)) {
                    throw new IllegalArgumentException(
                            "Duplicate \"$pos\" value in list: " + position);
                }
                mergeOrReplacePosition(targetChildren, position,
                        withoutPosition(sourceChild), limits, itemType);
            } else {
                Node resolved = resolveListChild(sourceChild, limits,
                        String.valueOf(targetChildren.size()), itemType);
                if (resolved != null) {
                    targetChildren.add(resolved);
                }
            }
        }
    }

    private void mergePlainPositionalChildren(
            List<Node> targetChildren,
            List<Node> sourceChildren,
            int start,
            Limits limits,
            Node itemType) {
        int sourceLength = sourceChildren.size() - start;
        if (sourceLength < targetChildren.size()) {
            throw new IllegalArgumentException(String.format(
                    "Positional list overlays cannot remove inherited items: inherited %d items but source supplied %d.",
                    targetChildren.size(), sourceLength));
        }
        List<String> inheritedIdentities = new ArrayList<>(targetChildren.size());
        for (Node inherited : targetChildren) {
            inheritedIdentities.add(BlueIdCalculator.calculateBlueId(inherited));
        }
        for (int index = 0; index < sourceLength; index++) {
            Node sourceChild = sourceChildren.get(start + index);
            if (index >= targetChildren.size()) {
                Node resolved = resolveListChild(
                        sourceChild, limits, String.valueOf(index), itemType);
                if (resolved != null) {
                    targetChildren.add(resolved);
                }
                continue;
            }
            String sourceIdentity = BlueIdCalculator.calculateBlueId(sourceChild);
            if (!sourceIdentity.equals(inheritedIdentities.get(index))
                    && inheritedIdentities.contains(sourceIdentity)) {
                throw new IllegalArgumentException(
                        "Positional list overlays cannot reorder inherited items; "
                                + "use a valid $pos replacement at index " + index + ".");
            }
            mergeExistingPosition(
                    targetChildren.get(index), sourceChild,
                    String.valueOf(index), limits);
        }
    }

    private void mergeExistingPosition(
            Node target, Node source, String segment, Limits limits) {
        if (!limits.shouldMergePathSegment(segment, source)) {
            engine.markIncomplete(segment);
            return;
        }
        boolean expansionAllowed = limits == Limits.NO_LIMITS
                || limits.shouldExpandPathSegment(segment, source);
        limits.enterPathSegment(segment, source);
        engine.enterValidationPath(segment, expansionAllowed);
        try {
            engine.merge(target, source, limits);
        } finally {
            engine.exitValidationPath();
            limits.exitPathSegment();
        }
    }

    private void mergeOrReplacePosition(
            List<Node> targetChildren,
            int position,
            Node overlay,
            Limits limits,
            Node itemType) {
        Node inherited = targetChildren.get(position);
        Node effectiveItemType = inherited.getType() != null
                ? inherited.getType() : itemType;
        if (hasReplacement(overlay)) {
            Node replacement = overlay.getProperties().get(LIST_CONTROL_REPLACE);
            if (isEmptyPlaceholder(replacement) && !isEmptyPlaceholder(inherited)) {
                throw new IllegalArgumentException(
                        "Fixed value conflict: replacement cannot remove inherited content.");
            }
            replacePosition(targetChildren, position, replacement, limits, effectiveItemType);
            return;
        }
        if (isEmptyPlaceholder(inherited)
                || overlay.getValue() != null
                || overlay.getItems() != null) {
            replacePosition(targetChildren, position, overlay, limits, effectiveItemType);
            return;
        }
        if (overlay.getType() != null) {
            Node resolved = resolveListChild(
                    overlay, limits, String.valueOf(position), effectiveItemType);
            if (resolved != null) {
                mergeTypedPosition(inherited, resolved, position, limits);
            }
            return;
        }
        if (isObjectOverlay(overlay) && !isObjectCompatibleListItem(inherited)) {
            throw new IllegalArgumentException(
                    "\"$pos\" object overlays require an object-compatible inherited list item.");
        }
        mergeExistingPosition(inherited, overlay, String.valueOf(position), limits);
    }

    private void replacePosition(
            List<Node> targetChildren,
            int position,
            Node source,
            Limits limits,
            Node itemType) {
        Node resolved = resolveListChild(
                source, limits, String.valueOf(position), itemType);
        if (resolved != null) {
            targetChildren.set(position, resolved);
        }
    }

    private void mergeTypedPosition(
            Node inherited, Node resolved, int position, Limits limits) {
        String segment = String.valueOf(position);
        boolean expansionAllowed = limits == Limits.NO_LIMITS
                || limits.shouldExpandPathSegment(segment, resolved);
        limits.enterPathSegment(segment, resolved);
        engine.enterValidationPath(segment, expansionAllowed);
        try {
            engine.mergeInstanceObject(inherited, resolved, limits);
        } finally {
            engine.exitValidationPath();
            limits.exitPathSegment();
        }
    }

    private boolean isObjectOverlay(Node overlay) {
        return overlay.getProperties() != null
                && !overlay.getProperties().isEmpty();
    }

    private boolean isObjectCompatibleListItem(Node inherited) {
        return inherited != null
                && inherited.getValue() == null
                && inherited.getItems() == null
                && inherited.getBlueId() == null;
    }

    private void appendChildren(
            List<Node> targetChildren,
            List<Node> sourceChildren,
            int start,
            Limits limits,
            Node itemType) {
        for (int index = start; index < sourceChildren.size(); index++) {
            Node resolved = resolveListChild(sourceChildren.get(index), limits,
                    String.valueOf(targetChildren.size()), itemType);
            if (resolved != null) {
                targetChildren.add(resolved);
            }
        }
    }

    private List<Node> resolvePreviousAnchor(
            Node previousAnchor, Limits limits, Node itemType) {
        List<Node> fetched = nodeProvider.fetchByBlueId(
                previousAnchor.getPreviousBlueId());
        if (fetched == null || fetched.isEmpty()) {
            throw new IllegalArgumentException(
                    "No content found for $previous blueId: "
                            + previousAnchor.getPreviousBlueId());
        }
        List<Node> previousChildren = fetched.size() == 1
                && fetched.get(0).getItems() != null
                ? fetched.get(0).getItems() : fetched;
        List<Node> resolved = new ArrayList<>();
        for (int index = 0; index < previousChildren.size(); index++) {
            Node child = resolveListChild(previousChildren.get(index), limits,
                    String.valueOf(index), itemType);
            if (child != null) {
                resolved.add(child);
            }
        }
        return resolved;
    }

    private void validatePreviousAnchor(
            List<Node> targetChildren, Node previousAnchor) {
        String actualBlueId = BlueIdCalculator.calculateBlueId(targetChildren);
        if (!actualBlueId.equals(previousAnchor.getPreviousBlueId())) {
            throw new IllegalArgumentException(
                    "\"$previous\" blueId does not match the inherited list. Expected "
                            + actualBlueId + " but found "
                            + previousAnchor.getPreviousBlueId() + ".");
        }
    }

    boolean isEmptyPlaceholder(Node node) {
        Map<String, Node> properties = node.getProperties();
        if (properties == null || properties.size() != 1
                || !properties.containsKey(Properties.LIST_CONTROL_EMPTY)) {
            return false;
        }
        Node marker = properties.get(Properties.LIST_CONTROL_EMPTY);
        return Boolean.TRUE.equals(marker.getValue())
                && node.getValue() == null
                && node.getItems() == null
                && node.getType() == null
                && node.getItemType() == null
                && node.getKeyType() == null
                && node.getValueType() == null;
    }

    private Node resolveListChild(
            Node child, Limits limits, String segment, Node itemType) {
        if (child.getPreviousBlueId() != null || child.getPosition() != null) {
            throw new IllegalArgumentException(
                    "List control items must be consumed before resolving list children.");
        }
        if (!limits.shouldMergePathSegment(segment, child)) {
            engine.markIncomplete(segment);
            return null;
        }
        boolean expansionAllowed = limits == Limits.NO_LIMITS
                || limits.shouldExpandPathSegment(segment, child);
        limits.enterPathSegment(segment, child);
        engine.enterValidationPath(segment, expansionAllowed);
        try {
            return engine.resolve(applyItemType(child, itemType), limits);
        } finally {
            engine.exitValidationPath();
            limits.exitPathSegment();
        }
    }

    Node applyItemType(Node child, Node itemType) {
        if (child.getType() != null || child.getBlueId() != null || itemType == null) {
            return child;
        }
        return child.clone().type(itemTypeReference(itemType));
    }

    Node itemTypeReference(Node itemType) {
        return itemType.getBlueId() != null
                ? new Node().blueId(itemType.getBlueId()) : itemType.clone();
    }

    Node withoutPosition(Node node) {
        Node clone = node.clone();
        clone.position(null);
        return clone;
    }

    boolean startsWithPrevious(List<Node> children) {
        return !children.isEmpty()
                && children.get(0).getPreviousBlueId() != null;
    }

    private String effectiveMergePolicy(Node node) {
        return node.getMergePolicy() == null
                ? LIST_MERGE_POLICY_POSITIONAL : node.getMergePolicy();
    }

    private void validateListControlScope(
            Node target, List<Node> sourceChildren) {
        boolean hasControls = sourceChildren.stream().anyMatch(
                child -> child.getPreviousBlueId() != null
                        || child.getPosition() != null);
        if (hasControls && !isListTyped(target)) {
            throw new IllegalArgumentException(
                    "List control forms require a node of type List.");
        }
    }

    private boolean isListTyped(Node node) {
        if (node.getItems() != null) {
            return true;
        }
        Node type = node.getType();
        if (type == null) {
            return false;
        }
        if (LIST_TYPE_BLUE_ID.equals(type.getBlueId())
                || LIST_TYPE.equals(type.getName())) {
            return true;
        }
        Object value = type.getValue();
        return LIST_TYPE.equals(value) || Types.isListType(type, nodeProvider);
    }

    private void validateListControls(
            List<Node> sourceChildren, String mergePolicy) {
        boolean previousSeen = false;
        Set<Integer> positions = new HashSet<>();
        for (int index = 0; index < sourceChildren.size(); index++) {
            Node child = sourceChildren.get(index);
            if (child.getPreviousBlueId() != null) {
                if (index != 0 || previousSeen) {
                    throw new IllegalArgumentException(
                            "\"$previous\" must appear only as the first list item.");
                }
                previousSeen = true;
            }
            if (child.getPosition() != null) {
                if (LIST_MERGE_POLICY_APPEND_ONLY.equals(mergePolicy)) {
                    throw new IllegalArgumentException(
                            "\"$pos\" is not allowed for append-only lists.");
                }
                if (!positions.add(child.getPosition())) {
                    throw new IllegalArgumentException(
                            "Duplicate \"$pos\" value in list: " + child.getPosition());
                }
            } else if (hasReplacement(child)) {
                throw new IllegalArgumentException(
                        "\"$replace\" is valid only inside a \"$pos\" list overlay.");
            }
            if (hasReplacement(child)) {
                validateReplacementOverlay(child);
            }
        }
    }

    boolean hasReplacement(Node node) {
        return node.getProperties() != null
                && node.getProperties().containsKey(LIST_CONTROL_REPLACE);
    }

    private void validateReplacementOverlay(Node node) {
        boolean onlyReplaceProperty = node.getProperties() != null
                && node.getProperties().size() == 1
                && node.getProperties().containsKey(LIST_CONTROL_REPLACE);
        if (!onlyReplaceProperty
                || node.getValue() != null
                || node.getItems() != null
                || node.getType() != null
                || node.getItemType() != null
                || node.getKeyType() != null
                || node.getValueType() != null
                || node.getSchema() != null
                || node.getMergePolicy() != null
                || node.getBlueId() != null
                || node.getPreviousBlueId() != null
                || node.getName() != null
                || node.getDescription() != null) {
            throw new IllegalArgumentException(
                    "\"$replace\" cannot be combined with sibling overlay fields other than \"$pos\".");
        }
    }

    boolean hasListControls(Node node) {
        List<Node> items = node.getItems();
        return items != null && items.stream().anyMatch(
                item -> item.getPreviousBlueId() != null
                        || item.getPosition() != null);
    }
}
