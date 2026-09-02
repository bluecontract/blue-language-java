package blue.language.conformance;

import blue.language.model.wire.BlueLanguageConstants;

import blue.language.provider.NodeProvider;
import blue.language.merge.Merger;
import blue.language.merge.MergingProcessor;
import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;
import blue.language.merge.ResolvedReferenceCache;
import blue.language.merge.TypeEvidenceResolution;
import blue.language.identity.CanonicalTypeIdentityLookup;
import blue.language.model.wire.JsonPointer;
import blue.language.resolve.MinimizedOverlayBuilder;
import blue.language.registry.NodeProviderWrapper;
import blue.language.resolve.ResolutionLimits;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Package-local planner that widens changed frozen nodes through declared type
 * ancestry until the document conforms again.
 *
 * <p>Planning is immutable: it structurally replaces the affected path and its
 * ancestors and records the corresponding canonical overlay changes.</p>
 */
final class FrozenConformancePlanner {

    /**
     * Keeps every reference below an independently planned merge root cold.
     * The root's own declared type remains available to conformance planning,
     * while references reached through its contributed or authored children do
     * not escape the enclosing document-level preservation boundary.
     */
    private static final ResolutionLimits
            DEFER_ALL_DESCENDANT_REFERENCES = new ResolutionLimits() {
                @Override
                public boolean shouldExpandPathSegment(
                        String pathSegment, Node currentNode) {
                    return currentNode == null
                            || !currentNode.isReferenceOnly();
                }

                @Override
                public boolean shouldMergePathSegment(
                        String pathSegment, Node currentNode) {
                    return true;
                }

                @Override
                public boolean retainsEveryAuthoredPath() {
                    return true;
                }

                @Override
                public void enterPathSegment(
                        String pathSegment, Node currentNode) {
                    // Stateless: every descendant has the same cold boundary.
                }

                @Override
                public void exitPathSegment() {
                    // Stateless: there is no traversal state to unwind.
                }
            };

    private final NodeProvider nodeProvider;
    private final MergingProcessor mergingProcessor;
    private final ResolvedReferenceCache resolvedReferenceCache;
    private final Set<String> deferredReferencePaths;
    private final CanonicalTypeIdentityLookup canonicalTypeIdentities;

    FrozenConformancePlanner(NodeProvider nodeProvider,
                             MergingProcessor mergingProcessor,
                             ResolvedReferenceCache resolvedReferenceCache,
                             CanonicalTypeIdentityLookup canonicalTypeIdentities) {
        this(nodeProvider,
                mergingProcessor,
                resolvedReferenceCache,
                canonicalTypeIdentities,
                Collections.emptySet());
    }

    FrozenConformancePlanner(NodeProvider nodeProvider,
                             MergingProcessor mergingProcessor,
                             ResolvedReferenceCache resolvedReferenceCache,
                             CanonicalTypeIdentityLookup canonicalTypeIdentities,
                             Collection<String> deferredReferencePaths) {
        this.nodeProvider = NodeProviderWrapper.wrap(nodeProvider);
        this.mergingProcessor = Objects.requireNonNull(mergingProcessor, "mergingProcessor");
        this.resolvedReferenceCache = resolvedReferenceCache;
        this.canonicalTypeIdentities = Objects.requireNonNull(
                canonicalTypeIdentities,
                "canonicalTypeIdentities");
        this.deferredReferencePaths = canonicalPaths(deferredReferencePaths);
    }

    ConformancePlan plan(FrozenNode canonicalRoot, FrozenNode resolvedRoot, String changedPath) {
        Objects.requireNonNull(resolvedRoot, "resolvedRoot");
        String normalized = JsonPointer.canonicalize(changedPath);
        List<String> existingSegments = existingPathSegments(resolvedRoot, normalized);
        FrozenNode nextResolvedRoot = resolvedRoot;
        FrozenNode nextCanonicalRoot = canonicalRoot;
        List<CanonicalGeneralizationPatch> canonicalPatches = new ArrayList<>();
        Set<String> changedPaths = new LinkedHashSet<>();
        boolean generalized = false;

        for (int depth = existingSegments.size(); depth >= 0; depth--) {
            String path = pointer(existingSegments, depth);
            FrozenNode current = read(nextResolvedRoot, path);
            GeneralizedNode generalizedNode = generalizeNode(current, path);
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
                FrozenNode after = reuseUnchangedSubtrees(
                        before,
                        generalizedNode.canonical());
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

    private GeneralizedNode generalizeNode(
            FrozenNode node,
            String nodePath) {
        if (node == null) {
            return GeneralizedNode.unchanged(node);
        }
        if (!hasTypeMetadata(node)) {
            return GeneralizedNode.unchanged(node);
        }

        ResolutionLimits resolutionLimits =
                resolutionLimitsAt(nodePath);
        Node source = new MinimizedOverlayBuilder().build(
                node,
                canonicalTypeIdentities);
        Node canonical = source.clone();
        ConformanceResult result = checkCanonical(
                canonical, resolutionLimits);
        FrozenNode type = node.getType();
        FrozenNode itemType = node.getItemType();
        FrozenNode keyType = node.getKeyType();
        FrozenNode valueType = node.getValueType();
        List<String> metadataFields = new ArrayList<>();
        boolean generalized = false;
        while (!result.isConformant()) {
            GeneralizationStep step = nextGeneralizationStep(
                    type,
                    itemType,
                    keyType,
                    valueType,
                    resolutionLimits);
            if (step == null) {
                throw new IllegalArgumentException("Node cannot be generalized to a conforming type: " + result.getMessage());
            }
            applyGeneralizationStep(canonical, step);
            switch (step.metadataField()) {
                case BlueLanguageConstants.OBJECT_TYPE:
                    type = step.parentType();
                    break;
                case BlueLanguageConstants.OBJECT_ITEM_TYPE:
                    itemType = step.parentType();
                    break;
                case BlueLanguageConstants.OBJECT_KEY_TYPE:
                    keyType = step.parentType();
                    break;
                case BlueLanguageConstants.OBJECT_VALUE_TYPE:
                    valueType = step.parentType();
                    break;
                default:
                    throw new IllegalStateException("Unsupported metadata field for generalization: " + step.metadataField());
            }
            metadataFields.add(step.metadataField());
            generalized = true;
            result = checkCanonical(canonical, resolutionLimits);
        }
        if (!generalized) {
            return GeneralizedNode.unchanged(node);
        }
        FrozenNode canonicalInput = FrozenNode.fromNode(canonical);
        Node resolved = new Merger(
                mergingProcessor,
                nodeProvider,
                resolvedReferenceCache).resolve(
                canonicalInput.toNode(),
                resolutionLimits);
        return new GeneralizedNode(
                reuseUnchangedSubtrees(
                        node,
                        FrozenNode.fromResolvedNode(resolved)),
                true,
                metadataFields,
                canonicalInput);
    }

    private boolean hasTypeMetadata(FrozenNode node) {
        return node.getType() != null
                || node.getItemType() != null
                || node.getKeyType() != null
                || node.getValueType() != null;
    }

    private ConformanceResult checkCanonical(
            Node canonical,
            ResolutionLimits resolutionLimits) {
        try {
            new Merger(mergingProcessor, nodeProvider, resolvedReferenceCache)
                    .resolve(canonical.clone(), resolutionLimits);
            return ConformanceResult.conformant();
        } catch (RuntimeException ex) {
            return ConformanceResult.nonConformant(ex.getMessage());
        }
    }

    private GeneralizationStep nextGeneralizationStep(FrozenNode typeNode,
                                                      FrozenNode itemTypeNode,
                                                      FrozenNode keyTypeNode,
                                                      FrozenNode valueTypeNode,
                                                      ResolutionLimits resolutionLimits) {
        GeneralizationStep type = generalizationStep(
                BlueLanguageConstants.OBJECT_TYPE,
                typeNode,
                resolutionLimits);
        if (type != null) {
            return type;
        }
        GeneralizationStep itemType = generalizationStep(
                BlueLanguageConstants.OBJECT_ITEM_TYPE,
                itemTypeNode,
                resolutionLimits);
        if (itemType != null) {
            return itemType;
        }
        GeneralizationStep keyType = generalizationStep(
                BlueLanguageConstants.OBJECT_KEY_TYPE,
                keyTypeNode,
                resolutionLimits);
        if (keyType != null) {
            return keyType;
        }
        return generalizationStep(
                BlueLanguageConstants.OBJECT_VALUE_TYPE,
                valueTypeNode,
                resolutionLimits);
    }

    private GeneralizationStep generalizationStep(
            String metadataField,
            FrozenNode typeNode,
            ResolutionLimits resolutionLimits) {
        ParentTypeEvidence parentType = parentType(
                typeNode, resolutionLimits);
        return parentType != null
                ? new GeneralizationStep(
                        metadataField,
                        parentType.type(),
                        parentType.canonicalBlueId())
                : null;
    }

    private void applyGeneralizationStep(Node canonical, GeneralizationStep step) {
        Node parentType = new Node().blueId(step.parentTypeBlueId());
        switch (step.metadataField()) {
            case BlueLanguageConstants.OBJECT_TYPE:
                canonical.type(parentType);
                return;
            case BlueLanguageConstants.OBJECT_ITEM_TYPE:
                canonical.itemType(parentType);
                return;
            case BlueLanguageConstants.OBJECT_KEY_TYPE:
                canonical.keyType(parentType);
                return;
            case BlueLanguageConstants.OBJECT_VALUE_TYPE:
                canonical.valueType(parentType);
                return;
            default:
                throw new IllegalStateException("Unsupported metadata field for generalization: " + step.metadataField());
        }
    }

    private ParentTypeEvidence parentType(
            FrozenNode type,
            ResolutionLimits resolutionLimits) {
        if (type == null) {
            return null;
        }
        if (type.getType() != null) {
            FrozenNode parent = type.getType();
            return new ParentTypeEvidence(
                    parent,
                    typeReferenceBlueId(
                            parent,
                            canonicalTypeIdentities));
        }

        Merger merger = new Merger(
                mergingProcessor,
                nodeProvider,
                resolvedReferenceCache);
        TypeEvidenceResolution resolution;
        FrozenNode parent;
        if (type.isReferenceOnly()) {
            resolution = merger.materializeTypeReferenceEvidence(
                    type,
                    resolutionLimits);
            FrozenNode materializedType = resolution.resolvedRoot().getType();
            parent = materializedType != null
                    ? materializedType.getType()
                    : null;
        } else {
            resolution = merger.resolveTypeEvidence(
                    type.toNode(),
                    resolutionLimits);
            parent = resolution.resolvedRoot().getType();
        }
        return parent != null
                ? new ParentTypeEvidence(
                        parent,
                        typeReferenceBlueId(
                                parent,
                                resolution.canonicalTypeIdentities()))
                : null;
    }

    /**
     * Relativizes document-root preservation paths for the subtree currently
     * being checked. Conformance evaluates every typed ancestor as an
     * independent merge root, so absolute paths would otherwise stop matching
     * as soon as planning moved below the document root.
     */
    private ResolutionLimits resolutionLimitsAt(String nodePath) {
        if (deferredReferencePaths.isEmpty()) {
            return ResolutionLimits.NO_LIMITS;
        }
        List<String> base = JsonPointer.split(
                JsonPointer.canonicalize(nodePath));
        Set<String> relative = new LinkedHashSet<>();
        for (String deferredPath : deferredReferencePaths) {
            List<String> candidate = JsonPointer.split(deferredPath);
            if (startsWith(base, candidate)) {
                return DEFER_ALL_DESCENDANT_REFERENCES;
            }
            if (startsWith(candidate, base)) {
                relative.add(JsonPointer.toPointer(
                        candidate.subList(base.size(), candidate.size())));
            }
        }
        return relative.isEmpty()
                ? ResolutionLimits.NO_LIMITS
                : ResolutionLimits.deferringReferencesAt(relative);
    }

    private static Set<String> canonicalPaths(
            Collection<String> paths) {
        if (paths == null || paths.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> canonical = new LinkedHashSet<>();
        for (String path : paths) {
            canonical.add(JsonPointer.canonicalize(path));
        }
        return Collections.unmodifiableSet(canonical);
    }

    private static boolean startsWith(
            List<String> candidate,
            List<String> prefix) {
        if (candidate.size() < prefix.size()) {
            return false;
        }
        for (int index = 0; index < prefix.size(); index++) {
            if (!candidate.get(index).equals(prefix.get(index))) {
                return false;
            }
        }
        return true;
    }

    private String typeReferenceBlueId(
            FrozenNode type,
            CanonicalTypeIdentityLookup typeIdentities) {
        if (type.isReferenceOnly()) {
            return type.getReferenceBlueId();
        }
        if (typeIdentities == null) {
            throw new IllegalStateException(
                    "Canonical identity evidence is required to publish a "
                            + "completed inline parent type");
        }
        String verified = typeIdentities.requireCanonicalTypeBlueId(
                type.toNode());
        if (type.getReferenceBlueId() != null
                && !type.getReferenceBlueId().equals(verified)) {
            throw new IllegalStateException(
                    "Retained type identity conflicts with resolver-issued "
                            + "canonical evidence");
        }
        return verified;
    }

    private List<String> existingPathSegments(FrozenNode root, String pointer) {
        if (JsonPointer.ROOT.equals(pointer)) {
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
        if (JsonPointer.ROOT.equals(pointer)) {
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
        if (JsonPointer.ROOT.equals(pointer)) {
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
        private final FrozenNode canonical;

        private GeneralizedNode(FrozenNode resolved, boolean generalized) {
            this(resolved, generalized, Collections.emptyList(), null);
        }

        private GeneralizedNode(FrozenNode resolved,
                                boolean generalized,
                                List<String> metadataFields,
                                FrozenNode canonical) {
            this.resolved = resolved;
            this.generalized = generalized;
            this.metadataFields = metadataFields;
            this.canonical = canonical;
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

        private FrozenNode canonical() {
            return canonical;
        }
    }

    private static final class GeneralizationStep {
        private final String metadataField;
        private final FrozenNode parentType;
        private final String parentTypeBlueId;

        private GeneralizationStep(
                String metadataField,
                FrozenNode parentType,
                String parentTypeBlueId) {
            this.metadataField = metadataField;
            this.parentType = parentType;
            this.parentTypeBlueId = parentTypeBlueId;
        }

        private String metadataField() {
            return metadataField;
        }

        private FrozenNode parentType() {
            return parentType;
        }

        private String parentTypeBlueId() {
            return parentTypeBlueId;
        }
    }

    private static final class ParentTypeEvidence {
        private final FrozenNode type;
        private final String canonicalBlueId;

        private ParentTypeEvidence(
                FrozenNode type,
                String canonicalBlueId) {
            this.type = Objects.requireNonNull(type, "parent type");
            this.canonicalBlueId = Objects.requireNonNull(
                    canonicalBlueId,
                    "canonicalBlueId");
        }

        private FrozenNode type() {
            return type;
        }

        private String canonicalBlueId() {
            return canonicalBlueId;
        }
    }
}
