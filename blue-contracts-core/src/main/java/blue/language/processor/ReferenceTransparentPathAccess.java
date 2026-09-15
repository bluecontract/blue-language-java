package blue.language.processor;

import blue.language.api.BlueViewPath;
import blue.language.identity.BlueIds;
import blue.language.identity.CanonicalTypeIdentityLookup;
import blue.language.model.Node;
import blue.language.model.wire.BlueLanguageConstants;
import blue.language.model.wire.JsonPointer;
import blue.language.model.wire.ParsedJsonPointer;
import blue.language.processor.util.PointerUtils;
import blue.language.snapshot.BluePatchOperation;
import blue.language.snapshot.CanonicalOverlayPatchEngine;
import blue.language.snapshot.FrozenNode;
import blue.language.merge.ResolvedSnapshot;

import java.util.Collections;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Invocation-local semantic access through verified pure-reference ancestors.
 *
 * <p>This helper never performs I/O of its own. It can only borrow the active
 * {@link ProcessingSnapshotManager}, whose provider and cyclic-proof domain is
 * already bound to the logical PROCESS invocation. Exact nodes are immutable,
 * so successful materializations may be memoized within that invocation.</p>
 */
final class ReferenceTransparentPathAccess {

    private static final String READ_PURPOSE =
            "Semantic document path";
    private static final String PATCH_PURPOSE =
            "Patch reference ancestor";

    private final ProcessingSnapshotManager manager;
    private final boolean strictPlatformInvocation;
    private final Map<String, List<String>> executableBodyFieldsByType;
    private final Map<String, ExactView> exactViewsByBlueId =
            new LinkedHashMap<String, ExactView>();
    private final Map<String, ExactView> managedExactViewsByBlueId =
            new LinkedHashMap<String, ExactView>();
    private final Map<String, FrozenNode> sourceValuesByBlueId = new LinkedHashMap<>();

    ReferenceTransparentPathAccess(
            ProcessingSnapshotManager manager,
            boolean strictPlatformInvocation,
            Map<String, List<String>> executableBodyFieldsByType) {
        this.manager = manager;
        this.strictPlatformInvocation = strictPlatformInvocation;
        this.executableBodyFieldsByType =
                ProcessingSnapshotBootstrap.immutableExecutableBodyFields(
                        executableBodyFieldsByType);
    }

    /** Returns a representation-blind resolved read, or {@code null}. */
    FrozenNode resolvedAt(
            FrozenNode canonicalRoot,
            FrozenNode resolvedRoot,
            String absolutePointer) {
        ResolvedScopeView view = scopeAt(
                canonicalRoot,
                resolvedRoot,
                CanonicalTypeIdentityLookup.incomplete(),
                absolutePointer);
        return view != null ? view.resolved() : null;
    }

    /** Exact provenance only: no resolution or merged Source reconstruction. */
    List<FrozenNode> sourceContributionsAt(FrozenNode root, String pointer) {
        List<FrozenNode> contributions = Collections.singletonList(root);
        FrozenNode retainedSlotType = null;
        String sourcePath = JsonPointer.ROOT;
        List<String> segments = JsonPointer.split(pointer);
        for (int depth = 0; depth < segments.size(); depth++) {
            String segment = segments.get(depth);
            List<FrozenNode> ancestors = new ArrayList<>();
            if (retainedSlotType != null) {
                collectSourceTypes(retainedSlotType, sourcePath, true,
                        new LinkedHashSet<String>(), ancestors);
            }
            for (FrozenNode source : contributions) {
                collectSourceTypes(source, sourcePath, false,
                        new LinkedHashSet<String>(), ancestors);
            }
            boolean list = ancestors.stream().anyMatch(FrozenNode::hasItems);
            List<FrozenNode> children = new ArrayList<>();
            Integer itemIndex = list ? listIndex(segment) : null;
            FrozenNode nextRetainedSlotType = null;
            if (itemIndex != null) {
                ListSlotContributions slot = listItemContributions(
                        ancestors, itemIndex, sourcePath, depth + 1 < segments.size());
                children = slot.selected;
                nextRetainedSlotType = slot.retainedType;
            } else {
                for (FrozenNode source : ancestors) {
                    FrozenNode child = semanticChild(source, segment,
                            JsonPointer.toPointer(Collections.singletonList(segment)));
                    if (child != null) children.add(child);
                }
                if (retainedSlotType != null && BlueLanguageConstants.OBJECT_TYPE.equals(segment)) {
                    children.add(retainedSlotType);
                }
            }
            // List resolution applies itemType to each element. Dictionary
            // valueType only validates compatibility; it supplies no content.
            // An item's type defaults contribute deeper paths, not the final
            // occurrence (the same rule as its explicitly selected type).
            if (itemIndex != null && !children.isEmpty() && depth + 1 < segments.size()) {
                List<FrozenNode> typedChildren = new ArrayList<>();
                for (FrozenNode source : ancestors) {
                    FrozenNode constraint = source.getItemType();
                    if (constraint != null) typedChildren.add(constraint);
                }
                typedChildren.addAll(children);
                children = typedChildren;
            }
            contributions = children;
            retainedSlotType = nextRetainedSlotType;
            sourcePath = JsonPointer.append(sourcePath, segment);
        }
        List<FrozenNode> result = new ArrayList<>();
        for (FrozenNode source : contributions) {
            result.add(source.isReferenceOnly()
                    ? exactSourceValue(source, sourcePath, false) : source);
        }
        return Collections.unmodifiableList(result);
    }

    private void collectSourceTypes(FrozenNode source, String sourcePath,
            boolean typeAncestor, Set<String> activeReferences, List<FrozenNode> result) {
        if (source == null) return;
        String activeKey = source.isReferenceOnly() ? source.getReferenceBlueId() : null;
        if (activeKey != null && !activeReferences.add(activeKey)) {
            throw new IllegalArgumentException("Cyclic Source type ancestry at " + sourcePath);
        }
        try {
            if (source.isReferenceOnly()) source = exactSourceValue(source, sourcePath, typeAncestor);
            collectSourceTypes(source.getType(), sourcePath, true, activeReferences, result);
            result.add(source);
        } finally {
            if (activeKey != null) activeReferences.remove(activeKey);
        }
    }

    private Integer listIndex(String segment) {
        try {
            int index = Integer.parseInt(segment);
            return index >= 0 ? index : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /** Tracks one effective list slot, not the raw Source overlay's item offset. */
    private ListSlotContributions listItemContributions(
            List<FrozenNode> ancestors, int selectedIndex, String sourcePath, boolean descendantsRequired) {
        List<FrozenNode> selected = new ArrayList<>();
        FrozenNode retainedType = null;
        String slotPath = JsonPointer.append(sourcePath, String.valueOf(selectedIndex));
        int length = 0;
        for (FrozenNode source : ancestors) {
            if (!source.hasItems()) continue;
            List<FrozenNode> items = source.getItems();
            // A canonical list carries its final payload, not append instructions.
            if (source.isStrictCanonical()
                    && (items.isEmpty() || items.get(0).getPreviousBlueId() == null)) {
                selected.clear();
                retainedType = null;
                if (selectedIndex < items.size()) selected.add(items.get(selectedIndex));
                length = items.size();
                continue;
            }
            for (FrozenNode item : items) {
                if (item.getPreviousBlueId() != null) {
                    if (length == 0) {
                        FrozenNode prefix = exactSourceValue(FrozenNode.fromNode(
                                new Node().blueId(item.getPreviousBlueId())), sourcePath, true);
                        if (!prefix.hasItems()) {
                            throw new InvalidExecutionEvidenceException("List prefix is not a list at " + sourcePath);
                        }
                        length = prefix.getItems().size();
                        if (selectedIndex < length) selected.add(prefix.item(selectedIndex));
                    }
                } else if (item.getPosition() != null) {
                    if (item.getPosition() == selectedIndex) {
                        FrozenNode replacement = item.property(BlueLanguageConstants.LIST_CONTROL_REPLACE);
                        if (replacement != null || item.getValue() != null) {
                            // Replacement discards ordinary inherited fields, not
                            // the slot's type constraint. Keep only that context;
                            // never reconstruct a synthetic combined Source node.
                            if (descendantsRequired) {
                                retainedType = selectedSlotType(selected, slotPath, retainedType);
                            }
                            selected.clear();
                            selected.add(replacement != null ? replacement : item);
                        } else {
                            selected.add(item);
                        }
                    }
                } else {
                    if (length == selectedIndex) selected.add(item);
                    length++;
                }
            }
        }
        // An explicit replacement/overlay type supplies its own ancestry. Do
        // not count the same inherited type twice or expose it as the slot body.
        if (retainedType != null && selectedSlotType(selected, slotPath, null) != null) {
            retainedType = null;
        }
        return new ListSlotContributions(selected, retainedType);
    }

    private FrozenNode selectedSlotType(List<FrozenNode> selected, String slotPath, FrozenNode fallback) {
        for (int index = selected.size() - 1; index >= 0; index--) {
            FrozenNode source = selected.get(index);
            if (source.isReferenceOnly()) source = exactSourceValue(source, slotPath, false);
            if (source.getType() != null) return source.getType();
        }
        return fallback;
    }

    private static final class ListSlotContributions {
        private final List<FrozenNode> selected;
        private final FrozenNode retainedType;

        private ListSlotContributions(List<FrozenNode> selected, FrozenNode retainedType) {
            this.selected = selected;
            this.retainedType = retainedType;
        }
    }

    private FrozenNode exactSourceValue(FrozenNode reference, String sourcePath, boolean typeAncestor) {
        if (!typeAncestor && isOpaqueManagedPath(sourcePath)) {
            return ((ManagedDocumentOverlaySnapshotManager) manager)
                    .materializeVerifiedManagedRead(sourcePath, reference);
        }
        String blueId = reference.getReferenceBlueId();
        FrozenNode retained = sourceValuesByBlueId.get(blueId);
        if (retained == null) {
            retained = ExecutableBodyPathCatalog.materializeVerifiedExact(
                    manager, reference, "Working occurrence Source contribution");
            sourceValuesByBlueId.put(blueId, retained);
        }
        return retained;
    }

    /**
     * Returns a reference-transparent scope without separating a freshly
     * resolved node from the lookup issued by that same resolver invocation.
     */
    ResolvedScopeView scopeAt(
            FrozenNode canonicalRoot,
            FrozenNode resolvedRoot,
            CanonicalTypeIdentityLookup canonicalTypeIdentities,
            String absolutePointer) {
        String normalized = PointerUtils.normalizePointer(absolutePointer);
        FrozenNode canonical = Objects.requireNonNull(
                canonicalRoot, "canonicalRoot");
        FrozenNode resolved = Objects.requireNonNull(
                resolvedRoot, "resolvedRoot");
        CanonicalTypeIdentityLookup identities = Objects.requireNonNull(
                canonicalTypeIdentities, "canonicalTypeIdentities");

        String currentPointer = JsonPointer.ROOT;
        NodePair current = openIfRequired(
                new NodePair(canonical, resolved, identities),
                READ_PURPOSE,
                currentPointer);
        for (String segment : JsonPointer.split(normalized)) {
            current = child(current, segment);
            if (current == null) {
                return null;
            }
            currentPointer = JsonPointer.append(currentPointer, segment);
            current = openIfRequired(
                    current, READ_PURPOSE, currentPointer);
        }
        FrozenNode effective = current.resolved != null
                ? current.resolved
                : current.canonical;
        return new ResolvedScopeView(
                current.canonical,
                effective,
                current.canonicalTypeIdentities);
    }

    /**
     * Opens only reference-only ancestors of the supplied patch paths.
     * Returned content is a tentative canonical representation and does not
     * mutate or publish the invocation state.
     */
    FrozenNode materializePatchAncestors(
            FrozenNode canonicalRoot,
            Iterable<String> patchPaths) {
        Objects.requireNonNull(canonicalRoot, "canonicalRoot");
        Objects.requireNonNull(patchPaths, "patchPaths");
        if (manager == null) {
            return canonicalRoot;
        }
        FrozenNode working = canonicalRoot;

        for (String patchPath : patchPaths) {
            String normalized = PointerUtils.normalizePointer(patchPath);
            List<String> segments = JsonPointer.split(normalized);
            for (int depth = 0; depth < segments.size(); depth++) {
                String prefix = JsonPointer.toPointer(
                        segments.subList(0, depth));
                FrozenNode ancestor = frozenAt(working, prefix);
                if (ancestor == null) {
                    break;
                }
                if (!ancestor.isReferenceOnly()) {
                    continue;
                }
                if (isOpaqueManagedPath(prefix)) {
                    continue;
                }
                if (BlueIds.hasCyclicMemberSeparator(
                        ancestor.getReferenceBlueId())) {
                    throw new ProcessorFailureException(
                            ProcessorErrorCategory
                                    .CyclicSetMutationUnsupported,
                            "Mutation below cyclic-set member reference is "
                                    + "unsupported at " + prefix + ": "
                                    + normalized);
                }
                ExactView exact = exactView(ancestor, PATCH_PURPOSE);
                if (JsonPointer.ROOT.equals(prefix)) {
                    working = exact.canonical;
                } else {
                    working = new CanonicalOverlayPatchEngine(working)
                            .apply(
                                    BluePatchOperation.REPLACE,
                                    ParsedJsonPointer.parse(prefix),
                                    exact.canonical)
                            .root();
                }
            }
        }
        if (working == canonicalRoot) {
            return canonicalRoot;
        }

        // A Source lane is an exact patching representation, not Canonical
        // Identity Input. Its direct hash must never be treated as semantic
        // identity; provider verification above is the proof for each opened
        // reference ancestor. Strict canonical roots retain the stronger
        // whole-document invariant check.
        if (!canonicalRoot.isStrictCanonical()) {
            return working;
        }

        String expected = canonicalRoot.blueId();
        String actual = working.blueId();
        if (!expected.equals(actual)) {
            throw new InvalidExecutionEvidenceException(
                    "Reference-ancestor materialization changed exact "
                            + "document identity: expected " + expected
                            + " but calculated " + actual);
        }
        return working;
    }

    private FrozenNode frozenAt(FrozenNode root, String pointer) {
        FrozenNode current = root;
        for (String segment : JsonPointer.split(pointer)) {
            if (current == null) {
                return null;
            }
            if (BlueLanguageConstants.OBJECT_TYPE.equals(segment)) {
                current = current.getType();
            } else if (BlueLanguageConstants.OBJECT_ITEM_TYPE
                    .equals(segment)) {
                current = current.getItemType();
            } else if (BlueLanguageConstants.OBJECT_KEY_TYPE
                    .equals(segment)) {
                current = current.getKeyType();
            } else if (BlueLanguageConstants.OBJECT_VALUE_TYPE
                    .equals(segment)) {
                current = current.getValueType();
            } else if (BlueLanguageConstants.OBJECT_BLUE
                    .equals(segment)) {
                current = current.getBlue();
            } else if (BlueLanguageConstants.OBJECT_CONTRACTS
                    .equals(segment)) {
                current = current.getContracts();
            } else if (current.hasItems()) {
                try {
                    current = current.item(Integer.parseInt(segment));
                } catch (NumberFormatException ignored) {
                    return null;
                }
            } else {
                current = current.property(segment);
            }
        }
        return current;
    }

    private NodePair child(NodePair parent, String segment) {
        String relative = JsonPointer.toPointer(
                Collections.singletonList(segment));
        FrozenNode canonical = semanticChild(
                parent.canonical, segment, relative);
        FrozenNode resolved = semanticChild(
                parent.resolved, segment, relative);
        if (canonical == null && resolved == null) {
            return null;
        }
        return new NodePair(
                canonical,
                resolved,
                parent.canonicalTypeIdentities);
    }

    private FrozenNode semanticChild(
            FrozenNode parent,
            String segment,
            String relativePointer) {
        if (parent == null) {
            return null;
        }
        FrozenNode structural = frozenSemanticChild(
                parent, segment);
        if (structural != null) {
            return structural;
        }
        if (parent.hasItems()) {
            try {
                int index = Integer.parseInt(segment);
                return index >= 0 ? parent.item(index) : null;
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        if (BlueLanguageConstants.OBJECT_BLUE_ID.equals(segment)) {
            return null;
        }
        Node selected = BlueLanguageConstants.OBJECT_SCHEMA
                .equals(segment)
                ? BlueViewPath.select(
                        new Node().schema(parent.getSchema()),
                        relativePointer)
                : null;
        return selected != null
                ? FrozenNode.fromResolvedNode(selected)
                : null;
    }

    private FrozenNode frozenSemanticChild(
            FrozenNode parent,
            String segment) {
        if (BlueLanguageConstants.OBJECT_NAME.equals(segment)) {
            return scalar(parent.getName());
        }
        if (BlueLanguageConstants.OBJECT_DESCRIPTION.equals(segment)) {
            return scalar(parent.getDescription());
        }
        if (BlueLanguageConstants.OBJECT_VALUE.equals(segment)) {
            return scalar(parent.getValue());
        }
        if (BlueLanguageConstants.OBJECT_TYPE.equals(segment)) {
            return parent.getType();
        }
        if (BlueLanguageConstants.OBJECT_ITEM_TYPE.equals(segment)) {
            return parent.getItemType();
        }
        if (BlueLanguageConstants.OBJECT_KEY_TYPE.equals(segment)) {
            return parent.getKeyType();
        }
        if (BlueLanguageConstants.OBJECT_VALUE_TYPE.equals(segment)) {
            return parent.getValueType();
        }
        if (BlueLanguageConstants.OBJECT_BLUE.equals(segment)) {
            return parent.getBlue();
        }
        if (BlueLanguageConstants.OBJECT_CONTRACTS.equals(segment)) {
            return parent.getContracts();
        }
        return parent.property(segment);
    }

    private FrozenNode scalar(Object value) {
        return value != null
                ? FrozenNode.fromResolvedNode(new Node().value(value))
                : null;
    }

    private NodePair openIfRequired(
            NodePair current,
            String purpose,
            String absolutePointer) {
        if (current.resolved != null
                && !current.resolved.isReferenceOnly()) {
            return current;
        }
        FrozenNode reference = current.canonical != null
                && current.canonical.isReferenceOnly()
                ? current.canonical
                : current.resolved != null
                        && current.resolved.isReferenceOnly()
                        ? current.resolved
                        : null;
        if (reference == null || manager == null) {
            return current;
        }
        if (isOpaqueManagedPath(absolutePointer)) {
            ExactView managed = managedExactView(
                    reference, absolutePointer);
            return new NodePair(
                    managed.canonical,
                    managed.resolved,
                    managed.canonicalTypeIdentities);
        }
        ExactView exact = exactView(reference, purpose);
        return new NodePair(
                exact.canonical,
                exact.resolved,
                exact.canonicalTypeIdentities);
    }

    private boolean isOpaqueManagedPath(String absolutePointer) {
        return manager instanceof ManagedDocumentOverlaySnapshotManager
                && ((ManagedDocumentOverlaySnapshotManager) manager)
                        .isOpaqueManagedPath(absolutePointer);
    }

    private ExactView exactView(FrozenNode reference, String purpose) {
        String blueId = reference.getReferenceBlueId();
        ExactView cached = exactViewsByBlueId.get(blueId);
        if (cached != null) {
            return cached;
        }
        FrozenNode exact = ExecutableBodyPathCatalog.materializeVerifiedExact(
                manager, reference, purpose);
        ExactView view = resolveExactView(reference, exact);
        exactViewsByBlueId.put(blueId, view);
        return view;
    }

    private ExactView managedExactView(
            FrozenNode reference,
            String absolutePointer) {
        ManagedDocumentOverlaySnapshotManager managed =
                (ManagedDocumentOverlaySnapshotManager) manager;
        FrozenNode exact = managed.materializeVerifiedManagedRead(
                absolutePointer, reference);
        String blueId = reference.getReferenceBlueId();
        ExactView cached = managedExactViewsByBlueId.get(blueId);
        if (cached != null) {
            return cached;
        }
        ExactView view = resolveExactView(reference, exact);
        managedExactViewsByBlueId.put(blueId, view);
        return view;
    }

    private ExactView resolveExactView(
            FrozenNode reference,
            FrozenNode exact) {
        String blueId = reference.getReferenceBlueId();
        ResolvedSnapshot resolved = strictPlatformInvocation
                ? DocumentProcessingRuntime
                        .resolveCanonicalTransientIncludingTypeContracts(
                                manager,
                                exact,
                                Collections.singleton(JsonPointer.ROOT),
                                executableBodyFieldsByType)
                : DocumentProcessingRuntime.resolveCanonicalTransient(
                        manager,
                        exact,
                        Collections.singleton(JsonPointer.ROOT),
                        executableBodyFieldsByType);
        FrozenNode canonical = resolved.hasCanonicalIdentity()
                ? resolved.frozenCanonicalRoot()
                : exact;
        // Source normalization supplies the inline gas representation only
        // while it remains the same exact value. Typed overlays such as a
        // FINOS Money body may normalize to a different direct identity; in
        // that case patching must start from the provider-verified body.
        if (!BlueIds.hasCyclicMemberSeparator(blueId)
                && !blueId.equals(canonical.blueId())) {
            canonical = exact;
        }
        return new ExactView(
                canonical,
                resolved.frozenResolvedRoot(),
                resolved.canonicalTypeIdentities());
    }

    private static final class NodePair {
        private final FrozenNode canonical;
        private final FrozenNode resolved;
        private final CanonicalTypeIdentityLookup canonicalTypeIdentities;

        private NodePair(
                FrozenNode canonical,
                FrozenNode resolved,
                CanonicalTypeIdentityLookup canonicalTypeIdentities) {
            this.canonical = canonical;
            this.resolved = resolved;
            this.canonicalTypeIdentities = Objects.requireNonNull(
                    canonicalTypeIdentities, "canonicalTypeIdentities");
        }
    }

    private static final class ExactView {
        private final FrozenNode canonical;
        private final FrozenNode resolved;
        private final CanonicalTypeIdentityLookup canonicalTypeIdentities;

        private ExactView(
                FrozenNode canonical,
                FrozenNode resolved,
                CanonicalTypeIdentityLookup canonicalTypeIdentities) {
            this.canonical = Objects.requireNonNull(canonical, "canonical");
            this.resolved = Objects.requireNonNull(resolved, "resolved");
            this.canonicalTypeIdentities = Objects.requireNonNull(
                    canonicalTypeIdentities, "canonicalTypeIdentities");
        }
    }
}
