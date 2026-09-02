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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
