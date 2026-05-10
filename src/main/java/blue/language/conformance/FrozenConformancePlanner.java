package blue.language.conformance;

import blue.language.NodeProvider;
import blue.language.merge.Merger;
import blue.language.merge.MergingProcessor;
import blue.language.model.Node;
import blue.language.processor.util.PointerUtils;
import blue.language.snapshot.FrozenNode;
import blue.language.snapshot.ResolvedReferenceCache;
import blue.language.utils.BlueIdCalculator;
import blue.language.utils.JsonPointer;
import blue.language.utils.MergeReverser;
import blue.language.utils.NodeProviderWrapper;
import blue.language.utils.limits.Limits;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

final class FrozenConformancePlanner {

    private final NodeProvider nodeProvider;
    private final MergingProcessor mergingProcessor;
    private final ResolvedReferenceCache resolvedReferenceCache;

    FrozenConformancePlanner(NodeProvider nodeProvider,
                             MergingProcessor mergingProcessor,
                             ResolvedReferenceCache resolvedReferenceCache) {
        this.nodeProvider = NodeProviderWrapper.wrap(nodeProvider);
        this.mergingProcessor = Objects.requireNonNull(mergingProcessor, "mergingProcessor");
        this.resolvedReferenceCache = resolvedReferenceCache;
    }

    ConformancePlan plan(FrozenNode canonicalRoot, FrozenNode resolvedRoot, String changedPath) {
        Objects.requireNonNull(resolvedRoot, "resolvedRoot");
        String normalized = PointerUtils.normalizePointer(changedPath);
        List<String> existingSegments = existingPathSegments(resolvedRoot, normalized);
        FrozenNode nextResolvedRoot = resolvedRoot;
        FrozenNode nextCanonicalRoot = canonicalRoot;
        List<CanonicalGeneralizationPatch> canonicalPatches = new ArrayList<>();
        Set<String> changedPaths = new LinkedHashSet<>();
        boolean generalized = false;

        for (int depth = existingSegments.size(); depth >= 0; depth--) {
            String path = pointer(existingSegments, depth);
            FrozenNode current = read(nextResolvedRoot, path);
            GeneralizedNode generalizedNode = generalizeNode(current);
            if (!generalizedNode.generalized()) {
                continue;
            }

            nextResolvedRoot = replaceAt(nextResolvedRoot, path, generalizedNode.resolved());
            changedPaths.add(path);
            for (String metadataField : generalizedNode.metadataFields()) {
                changedPaths.add(metadataPointer(path, metadataField));
            }
            generalized = true;

            if (nextCanonicalRoot != null) {
                FrozenNode before = read(nextCanonicalRoot, path);
                FrozenNode after = reuseUnchangedSubtrees(before, canonicalize(generalizedNode.resolved()));
                nextCanonicalRoot = replaceAt(nextCanonicalRoot, path, after);
                canonicalPatches.add(new CanonicalGeneralizationPatch(path, before, after));
            }
        }

        return new ConformancePlan(nextCanonicalRoot,
                nextResolvedRoot,
                generalized,
                canonicalPatches,
                new ArrayList<>(changedPaths),
                nextCanonicalRoot != null);
    }

    private GeneralizedNode generalizeNode(FrozenNode node) {
        if (node == null) {
            return GeneralizedNode.unchanged(node);
        }

        ConformanceResult result = check(node);
        FrozenNode current = node;
        List<String> metadataFields = new ArrayList<>();
        boolean generalized = false;
        while (!result.isConformant()) {
            GeneralizationStep step = nextGeneralizationStep(current);
            if (step == null) {
                throw new IllegalArgumentException("Node cannot be generalized to a conforming type: " + result.getMessage());
            }
            current = generalizedNode(current, step);
            metadataFields.add(step.metadataField());
            generalized = true;
            result = check(current);
        }
        return new GeneralizedNode(current, generalized, metadataFields);
    }

    private ConformanceResult check(FrozenNode node) {
        if (node == null) {
            return ConformanceResult.conformant();
        }
        try {
            new Merger(mergingProcessor, nodeProvider, resolvedReferenceCache).resolve(node.toNode(), Limits.NO_LIMITS);
            return ConformanceResult.conformant();
        } catch (RuntimeException ex) {
            return ConformanceResult.nonConformant(ex.getMessage());
        }
    }

    private GeneralizationStep nextGeneralizationStep(FrozenNode node) {
        GeneralizationStep type = generalizationStep("type", node.getType());
        if (type != null) {
            return type;
        }
        GeneralizationStep itemType = generalizationStep("itemType", node.getItemType());
        if (itemType != null) {
            return itemType;
        }
        GeneralizationStep keyType = generalizationStep("keyType", node.getKeyType());
        if (keyType != null) {
            return keyType;
        }
        return generalizationStep("valueType", node.getValueType());
    }

    private GeneralizationStep generalizationStep(String metadataField, FrozenNode typeNode) {
        FrozenNode parentType = parentType(typeNode);
        return parentType != null ? new GeneralizationStep(metadataField, parentType) : null;
    }

    private FrozenNode generalizedNode(FrozenNode node, GeneralizationStep step) {
        Node canonical = new MergeReverser().reverse(node.toNode());
        Node parentType = new Node().blueId(typeReferenceBlueId(step.parentType()));
        switch (step.metadataField()) {
            case "type":
                canonical.type(parentType);
                break;
            case "itemType":
                canonical.itemType(parentType);
                break;
            case "keyType":
                canonical.keyType(parentType);
                break;
            case "valueType":
                canonical.valueType(parentType);
                break;
            default:
                throw new IllegalStateException("Unsupported metadata field for generalization: " + step.metadataField());
        }
        Node resolved = new Merger(mergingProcessor, nodeProvider, resolvedReferenceCache)
                .resolve(canonical, Limits.NO_LIMITS);
        return reuseUnchangedSubtrees(node, FrozenNode.fromResolvedNode(resolved, resolvedReferenceCache));
    }

    private FrozenNode parentType(FrozenNode type) {
        if (type == null) {
            return null;
        }
        if (type.getType() != null) {
            return type.getType();
        }

        Node resolvedType = new Merger(mergingProcessor, nodeProvider, resolvedReferenceCache)
                .resolve(type.toNode(), Limits.NO_LIMITS);
        Node parentType = resolvedType.getType();
        return parentType != null ? FrozenNode.fromResolvedNode(parentType, resolvedReferenceCache) : null;
    }

    private String typeReferenceBlueId(FrozenNode type) {
        return type.getReferenceBlueId() != null
                ? type.getReferenceBlueId()
                : BlueIdCalculator.calculateBlueId(new MergeReverser().reverse(type.toNode()));
    }

    private FrozenNode canonicalize(FrozenNode resolvedNode) {
        return FrozenNode.fromNode(new MergeReverser().reverse(resolvedNode.toNode()));
    }

    private List<String> existingPathSegments(FrozenNode root, String pointer) {
        if ("/".equals(pointer)) {
            return Collections.emptyList();
        }
        List<String> requested = JsonPointer.split(pointer);
        List<String> existing = new ArrayList<>(requested.size());
        FrozenNode current = root;
        for (String segment : requested) {
            if (current == null) {
                break;
            }
            String actualSegment = actualSegment(current, segment);
            FrozenNode child = child(current, actualSegment);
            if (child == null) {
                break;
            }
            existing.add(actualSegment);
            current = child;
        }
        return existing;
    }

    private String actualSegment(FrozenNode node, String segment) {
        if (!"-".equals(segment) || !node.hasItems()) {
            return segment;
        }
        List<FrozenNode> items = node.getItems();
        return items == null || items.isEmpty() ? segment : String.valueOf(items.size() - 1);
    }

    private FrozenNode child(FrozenNode node, String segment) {
        if (node == null) {
            return null;
        }
        if (node.hasItems()) {
            return node.item(parseArrayIndex(segment));
        }
        return node.property(segment);
    }

    private FrozenNode read(FrozenNode root, String pointer) {
        if (root == null) {
            return null;
        }
        if ("/".equals(pointer)) {
            return root;
        }
        FrozenNode current = root;
        for (String segment : JsonPointer.split(pointer)) {
            current = child(current, segment);
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    private FrozenNode replaceAt(FrozenNode root, String pointer, FrozenNode replacement) {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(replacement, "replacement");
        if ("/".equals(pointer)) {
            return replacement;
        }
        List<String> segments = JsonPointer.split(pointer);
        return replaceAt(root, segments, 0, replacement, pointer);
    }

    private FrozenNode replaceAt(FrozenNode node,
                                 List<String> segments,
                                 int depth,
                                 FrozenNode replacement,
                                 String pointer) {
        String segment = segments.get(depth);
        boolean leaf = depth == segments.size() - 1;
        if (node.hasItems()) {
            int index = parseArrayIndex(segment);
            List<FrozenNode> items = node.getItems();
            if (index < 0 || index >= items.size()) {
                throw new IllegalStateException("Array index out of bounds while replacing conformance path: " + pointer);
            }
            List<FrozenNode> nextItems = new ArrayList<>(items);
            nextItems.set(index, leaf ? replacement : replaceAt(items.get(index), segments, depth + 1, replacement, pointer));
            return node.withItems(nextItems);
        }

        FrozenNode child = node.property(segment);
        if (child == null && !leaf) {
            child = FrozenNode.empty();
        }
        if (child == null && leaf) {
            return node.withProperty(segment, replacement);
        }
        FrozenNode nextChild = leaf ? replacement : replaceAt(child, segments, depth + 1, replacement, pointer);
        return node.withProperty(segment, nextChild);
    }

    private String pointer(List<String> segments, int length) {
        return JsonPointer.toPointer(segments.subList(0, length));
    }

    private FrozenNode reuseUnchangedSubtrees(FrozenNode previous, FrozenNode candidate) {
        if (previous == null || candidate == null) {
            return candidate;
        }
        if (previous.blueId().equals(candidate.blueId())) {
            return previous;
        }

        FrozenNode result = candidate;
        if (previous.hasItems() && candidate.hasItems()) {
            List<FrozenNode> previousItems = previous.getItems();
            List<FrozenNode> candidateItems = candidate.getItems();
            List<FrozenNode> nextItems = new ArrayList<>(candidateItems);
            boolean changed = false;
            int commonSize = Math.min(previousItems.size(), candidateItems.size());
            for (int i = 0; i < commonSize; i++) {
                FrozenNode reused = reuseUnchangedSubtrees(previousItems.get(i), candidateItems.get(i));
                if (reused != candidateItems.get(i)) {
                    nextItems.set(i, reused);
                    changed = true;
                }
            }
            if (changed) {
                result = result.withItems(nextItems);
            }
        }

        if (previous.hasProperties() && candidate.hasProperties()) {
            for (String key : candidate.getProperties().keySet()) {
                FrozenNode previousChild = previous.property(key);
                FrozenNode candidateChild = result.property(key);
                FrozenNode reused = reuseUnchangedSubtrees(previousChild, candidateChild);
                if (reused != candidateChild) {
                    result = result.withProperty(key, reused);
                }
            }
        }
        return result;
    }

    private String metadataPointer(String nodePath, String metadataField) {
        return JsonPointer.append(nodePath, metadataField);
    }

    private int parseArrayIndex(String segment) {
        try {
            int index = Integer.parseInt(segment);
            return index >= 0 ? index : -1;
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    private static final class GeneralizedNode {
        private final FrozenNode resolved;
        private final boolean generalized;
        private final List<String> metadataFields;

        private GeneralizedNode(FrozenNode resolved, boolean generalized) {
            this(resolved, generalized, Collections.emptyList());
        }

        private GeneralizedNode(FrozenNode resolved, boolean generalized, List<String> metadataFields) {
            this.resolved = resolved;
            this.generalized = generalized;
            this.metadataFields = metadataFields;
        }

        private static GeneralizedNode unchanged(FrozenNode resolved) {
            return new GeneralizedNode(resolved, false);
        }

        private FrozenNode resolved() {
            return resolved;
        }

        private boolean generalized() {
            return generalized;
        }

        private List<String> metadataFields() {
            return metadataFields;
        }
    }

    private static final class GeneralizationStep {
        private final String metadataField;
        private final FrozenNode parentType;

        private GeneralizationStep(String metadataField, FrozenNode parentType) {
            this.metadataField = metadataField;
            this.parentType = parentType;
        }

        private String metadataField() {
            return metadataField;
        }

        private FrozenNode parentType() {
            return parentType;
        }
    }
}
