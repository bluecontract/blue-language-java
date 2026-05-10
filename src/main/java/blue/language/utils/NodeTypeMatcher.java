package blue.language.utils;

import blue.language.Blue;
import blue.language.model.Node;
import blue.language.model.Schema;
import blue.language.snapshot.FrozenNode;
import blue.language.snapshot.ResolvedSnapshot;
import blue.language.utils.limits.CompositeLimits;
import blue.language.utils.limits.Limits;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Stack;

public class NodeTypeMatcher {

    private final Blue blue;
    private final FrozenTypeMatcher frozenMatcher;

    public NodeTypeMatcher(Blue blue) {
        this.blue = Objects.requireNonNull(blue, "blue");
        this.frozenMatcher = new FrozenTypeMatcher(blue);
    }

    public boolean matchesType(Node node, Node targetType) {
        return matchesType(node, targetType, Limits.NO_LIMITS);
    }

    public boolean matchesType(Node node, Node targetType, Limits globalLimits) {
        if (targetType == null) {
            return true;
        }
        if (node == null) {
            return false;
        }

        try {
            Node targetPatternNode = blue.preprocess(targetType.clone());
            Limits matchingLimits = matchingLimits(globalLimits, targetPatternNode);
            FrozenNode resolvedNode = FrozenNode.fromResolvedNode(resolveForMatching(node, matchingLimits));
            FrozenNode targetPattern = FrozenNode.fromResolvedNode(targetPatternNode);
            return matcherFor(globalLimits).matchesType(resolvedNode, targetPattern);
        } catch (RuntimeException ex) {
            return false;
        }
    }

    public boolean matchesResolvedType(FrozenNode resolvedNode, FrozenNode resolvedTargetType) {
        return frozenMatcher.matchesType(resolvedNode, resolvedTargetType);
    }

    public boolean matchesResolvedType(ResolvedSnapshot snapshot, String pointer, FrozenNode resolvedTargetType) {
        if (snapshot == null) {
            return false;
        }
        return matchesResolvedType(snapshot.resolvedAt(pointer), resolvedTargetType);
    }

    private Node resolveForMatching(Node node, Limits limits) {
        Node original = blue.preprocess(node.clone());
        Node extended = original.clone();
        blue.extend(extended, limits);
        Node resolved = blue.resolve(extended, limits);
        restoreMissingStructure(resolved, extended);
        return resolved;
    }

    private Limits matchingLimits(Limits globalLimits, Node targetPattern) {
        Limits effectiveGlobalLimits = globalLimits != null ? globalLimits : Limits.NO_LIMITS;
        return new CompositeLimits(effectiveGlobalLimits, new TargetPatternLimits(targetPattern));
    }

    private FrozenTypeMatcher matcherFor(Limits globalLimits) {
        if (globalLimits == null || globalLimits == Limits.NO_LIMITS) {
            return frozenMatcher;
        }
        return new FrozenTypeMatcher(blue, false);
    }

    private void restoreMissingStructure(Node target, Node source) {
        if (target == null || source == null) {
            return;
        }

        restoreItems(target, source);
        restoreProperties(target, source);

        if (target.getBlueId() == null && source.getBlueId() != null) {
            target.blueId(source.getBlueId());
        }
        if (target.getValue() == null && source.getValue() != null) {
            target.value(source.getValue());
        }
    }

    private void restoreItems(Node target, Node source) {
        List<Node> sourceItems = source.getItems();
        if (sourceItems == null) {
            return;
        }
        List<Node> targetItems = target.getItems();
        if (targetItems == null || targetItems.isEmpty()) {
            target.items(cloneItems(sourceItems));
            return;
        }
        int commonSize = Math.min(targetItems.size(), sourceItems.size());
        for (int i = 0; i < commonSize; i++) {
            restoreMissingStructure(targetItems.get(i), sourceItems.get(i));
        }
    }

    private List<Node> cloneItems(List<Node> items) {
        java.util.ArrayList<Node> cloned = new java.util.ArrayList<>(items.size());
        for (Node item : items) {
            cloned.add(item.clone());
        }
        return cloned;
    }

    private void restoreProperties(Node target, Node source) {
        Map<String, Node> sourceProperties = source.getProperties();
        if (sourceProperties == null) {
            return;
        }
        Map<String, Node> targetProperties = target.getProperties();
        if (targetProperties == null) {
            target.properties(cloneProperties(sourceProperties));
            return;
        }
        for (Map.Entry<String, Node> entry : sourceProperties.entrySet()) {
            Node targetChild = targetProperties.get(entry.getKey());
            if (targetChild == null) {
                targetProperties.put(entry.getKey(), entry.getValue().clone());
            } else {
                restoreMissingStructure(targetChild, entry.getValue());
            }
        }
    }

    private Map<String, Node> cloneProperties(Map<String, Node> properties) {
        java.util.LinkedHashMap<String, Node> cloned = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Node> entry : properties.entrySet()) {
            cloned.put(entry.getKey(), entry.getValue().clone());
        }
        return cloned;
    }

    private static final class TargetPatternLimits implements Limits {
        private final Node targetPattern;
        private final Stack<String> currentPath = new Stack<>();
        private final Stack<Boolean> enteredPathSegment = new Stack<>();

        private TargetPatternLimits(Node targetPattern) {
            this.targetPattern = targetPattern;
        }

        @Override
        public boolean shouldExtendPathSegment(String pathSegment, Node currentNode) {
            TargetLookup targetAtPath = targetAtForExtend(candidatePath(pathSegment));
            if (targetAtPath == null) {
                return false;
            }
            if (!targetAtPath.node.isReferenceOnly()) {
                return true;
            }
            return targetAtPath.fromCollectionType
                    && currentNode != null
                    && currentNode.getBlueId() != null
                    && !currentNode.getBlueId().equals(targetAtPath.node.getBlueId());
        }

        @Override
        public boolean shouldMergePathSegment(String pathSegment, Node currentNode) {
            return targetAtForMerge(candidatePath(pathSegment)) != null;
        }

        @Override
        public boolean shouldReconstructList(Node currentNode, List<Node> items) {
            TargetLookup targetAtCurrentPath = targetAtForMerge(new java.util.ArrayList<>(currentPath));
            if (targetAtCurrentPath == null) {
                return false;
            }
            List<Node> targetItems = targetAtCurrentPath.node.getItems();
            return targetItems != null
                    && targetItems.size() > items.size()
                    && shouldAttemptBundleReconstruction(items, targetItems);
        }

        @Override
        public void enterPathSegment(String pathSegment, Node node) {
            boolean realSegment = pathSegment != null && !pathSegment.isEmpty();
            enteredPathSegment.push(realSegment);
            if (realSegment) {
                currentPath.push(pathSegment);
            }
        }

        @Override
        public void exitPathSegment() {
            if (enteredPathSegment.isEmpty()) {
                return;
            }
            if (enteredPathSegment.pop() && !currentPath.isEmpty()) {
                currentPath.pop();
            }
        }

        private List<String> candidatePath(String pathSegment) {
            java.util.ArrayList<String> path = new java.util.ArrayList<>(currentPath);
            if (pathSegment != null && !pathSegment.isEmpty()) {
                path.add(pathSegment);
            }
            return path;
        }

        private TargetLookup targetAtForExtend(List<String> path) {
            return targetAt(targetPattern, path, 0, true, false);
        }

        private TargetLookup targetAtForMerge(List<String> path) {
            return targetAt(targetPattern, path, 0, false, false);
        }

        private TargetLookup targetAt(Node current, List<String> path, int offset, boolean forExtension, boolean fromCollectionType) {
            if (current == null) {
                return null;
            }
            if (offset == path.size()) {
                return new TargetLookup(current, fromCollectionType);
            }

            String segment = path.get(offset);
            Map<String, Node> properties = current.getProperties();
            if (properties != null && properties.containsKey(segment)) {
                return targetAt(properties.get(segment), path, offset + 1, forExtension, false);
            }

            Integer index = integerSegment(segment);
            List<Node> items = current.getItems();
            if (index != null && items != null && index >= 0 && index < items.size()) {
                return targetAt(items.get(index), path, offset + 1, forExtension, false);
            }

            if (index != null && current.getItemType() != null) {
                return targetAt(current.getItemType(), path, offset + 1, forExtension, true);
            }
            if (index != null && !forExtension && schemaNeedsItems(current.getSchema())) {
                return new TargetLookup(new Node(), false);
            }

            if (current.getValueType() != null) {
                return targetAt(current.getValueType(), path, offset + 1, forExtension, true);
            }
            if (!forExtension && current.getKeyType() != null) {
                return new TargetLookup(new Node(), false);
            }
            if (!forExtension && schemaNeedsFields(current.getSchema())) {
                return new TargetLookup(new Node(), false);
            }

            return null;
        }

        private Integer integerSegment(String segment) {
            try {
                return Integer.valueOf(segment);
            } catch (NumberFormatException ex) {
                return null;
            }
        }

        private boolean schemaNeedsItems(Schema schema) {
            return schema != null
                    && (schema.getAllowMultipleValue() != null
                    || schema.getMinItemsValue() != null
                    || schema.getMaxItemsValue() != null
                    || schema.getUniqueItemsValue() != null);
        }

        private boolean schemaNeedsFields(Schema schema) {
            return schema != null
                    && (schema.getMinFieldsValue() != null
                    || schema.getMaxFieldsValue() != null);
        }

        private boolean shouldAttemptBundleReconstruction(List<Node> candidateItems, List<Node> targetItems) {
            if (candidateItems.isEmpty()) {
                return false;
            }
            Node firstCandidate = candidateItems.get(0);
            String firstCandidateBlueId = firstCandidate.getBlueId();
            if (firstCandidateBlueId == null) {
                return false;
            }
            if (!targetItems.isEmpty()) {
                Node firstTarget = targetItems.get(0);
                if (firstTarget.isReferenceOnly() && firstCandidateBlueId.equals(firstTarget.getBlueId())) {
                    return false;
                }
            }
            return true;
        }

        private static final class TargetLookup {
            private final Node node;
            private final boolean fromCollectionType;

            private TargetLookup(Node node, boolean fromCollectionType) {
                this.node = node;
                this.fromCollectionType = fromCollectionType;
            }
        }
    }
}
