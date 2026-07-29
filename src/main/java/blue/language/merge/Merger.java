package blue.language.merge;

import blue.language.utils.Properties;

import blue.language.NodeProvider;
import blue.language.model.Node;
import blue.language.model.NodeDeserializer;
import blue.language.model.Schema;
import blue.language.snapshot.FrozenNode;
import blue.language.snapshot.ResolvedReferenceCache;
import blue.language.processor.registry.BlueRuntimeTypeRegistry;
import blue.language.utils.NodeProviderWrapper;
import blue.language.utils.JsonPointer;
import blue.language.utils.CanonicalIdentityInputBuilder;
import blue.language.utils.NodeToMapListOrValue;
import blue.language.utils.Types;
import blue.language.utils.limits.Limits;
import blue.language.utils.BlueIdCalculator;
import blue.language.utils.BlueIdReferenceValidator;
import blue.language.utils.BlueIds;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static blue.language.utils.limits.Limits.NO_LIMITS;
import static blue.language.utils.UncheckedObjectMapper.JSON_MAPPER;

import static blue.language.utils.Properties.LIST_MERGE_POLICY_APPEND_ONLY;
import static blue.language.utils.Properties.LIST_MERGE_POLICY_POSITIONAL;
import static blue.language.utils.Properties.LIST_CONTROL_REPLACE;
import static blue.language.utils.Properties.LIST_TYPE;
import static blue.language.utils.Properties.LIST_TYPE_BLUE_ID;
import static blue.language.utils.Properties.CORE_TYPE_BLUE_IDS;
import static blue.language.utils.Properties.CORE_TYPES;

/**
 * Concrete Blue Language merge engine.
 *
 * <p>Custom merge behavior should use {@link MergingProcessor}, which is the
 * supported extension point.</p>
 */
public final class Merger implements NodeResolver {

    private final MergingProcessor mergingProcessor;
    private final NodeProvider nodeProvider;
    private final ResolvedReferenceCache resolvedReferenceCache;
    private ResolutionState resolutionState;

    /**
     * Creates a merge engine without retained resolved-reference caching.
     *
     * @param mergingProcessor processor that applies language merge semantics
     * @param nodeProvider provider used to resolve referenced nodes
     */
    public Merger(MergingProcessor mergingProcessor, NodeProvider nodeProvider) {
        this(mergingProcessor, nodeProvider, null);
    }

    /**
     * Creates a merge engine that borrows an optional reference cache and
     * always verifies content obtained from the provider.
     *
     * @param mergingProcessor processor that applies language merge semantics
     * @param nodeProvider provider used to resolve referenced nodes
     * @param resolvedReferenceCache optional cache for verified resolved references
     */
    public Merger(MergingProcessor mergingProcessor, NodeProvider nodeProvider, ResolvedReferenceCache resolvedReferenceCache) {
        this.mergingProcessor = mergingProcessor;
        this.nodeProvider = NodeProviderWrapper.wrap(nodeProvider);
        this.resolvedReferenceCache = resolvedReferenceCache;
    }

    /**
     * Resolves one source and binds the exact strict canonical and completed
     * resolved representations produced by this resolver invocation.
     *
     * @param preprocessedSource source with preprocessing already applied
     * @param limits limits governing reference and path resolution
     * @return canonical and resolved roots from the same resolver invocation
     */
    public SnapshotResolution resolveSnapshot(Node preprocessedSource, Limits limits) {
        Objects.requireNonNull(preprocessedSource, "preprocessedSource");
        Objects.requireNonNull(limits, "limits");
        Node resolved = resolve(preprocessedSource.clone(), limits);
        Node canonical = new CanonicalIdentityInputBuilder().build(
                resolved.clone(), preprocessedSource);
        return snapshotResolution(FrozenNode.fromNode(canonical), resolved, limits);
    }

    /**
     * Resolves an already-canonical source without accepting a caller-supplied
     * resolved representation.
     *
     * @param canonicalRoot strict canonical source root
     * @param limits limits governing reference and path resolution
     * @return canonical and resolved roots from the same resolver invocation
     */
    public SnapshotResolution resolveSnapshot(FrozenNode canonicalRoot, Limits limits) {
        Objects.requireNonNull(canonicalRoot, "canonicalRoot");
        Objects.requireNonNull(limits, "limits");
        if (!canonicalRoot.isStrictCanonical()) {
            throw new IllegalArgumentException("Snapshot resolution requires a strict canonical root.");
        }
        Node resolved = resolve(canonicalRoot.toNode(), limits);
        return snapshotResolution(canonicalRoot, resolved, limits);
    }

    private SnapshotResolution snapshotResolution(FrozenNode canonicalRoot,
                                                  Node resolved,
                                                  Limits limits) {
        FrozenNode frozenResolved = freezeResolved(resolved);
        VerifiedReferenceResolution verification = null;
        if (limits == NO_LIMITS
                && canonicalRoot.isStrictBlueIdValidation()
                && !canonicalRoot.isReferenceOnly()
                && !frozenResolved.isReferenceOnly()) {
            verification = new VerifiedReferenceResolution(
                    canonicalRoot.blueId(), canonicalRoot, frozenResolved);
        }
        return new SnapshotResolution(canonicalRoot, frozenResolved, verification);
    }

    private FrozenNode freezeResolved(Node resolved) {
        return resolvedReferenceCache != null
                ? resolvedReferenceCache.freezeResolved(resolved)
                : FrozenNode.fromResolvedNode(resolved);
    }

    /**
     * Merges {@code source} into mutable {@code target} under the supplied
     * resolution limits and performs completed-value validation once at the
     * outermost call.
     *
     * @param target mutable target that receives the merged contribution
     * @param source source contribution to merge
     * @param limits limits governing reference and path resolution
     */
    public void merge(Node target, Node source, Limits limits) {
        ResolutionState state = resolutionState;
        boolean outermost = state == null;
        LabelProvenanceScope outermostLabelScope = null;
        boolean enteredOutermostLimit = false;
        if (outermost) {
            state = new ResolutionState();
            state.rootInlineTypeDeclaration = isInlineTypeDeclaration(source);
            state.rootSource = source;
            resolutionState = state;
        }
        try {
            if (outermost) {
                limits.enterPathSegment("", source);
                enteredOutermostLimit = true;
                outermostLabelScope = pushLabelProvenanceScope(source, limits, true);
                seedMaterializedTargetLabelProvenance(target, outermostLabelScope);
            }
            LabelMergeMode labelMergeMode = labelMergeMode(state.contribution);
            boolean inheritedDeclarationOnly = labelMergeMode == LabelMergeMode.AUTHORED_OVERLAY
                    && isDeclarationOnlyForLabels(target);
            if (labelMergeMode == LabelMergeMode.AUTHORED_OVERLAY) {
                validateExplicitInstanceLabels(target, source, inheritedDeclarationOnly);
            }
            mergeInternal(target, source, limits);
            if (labelMergeMode == LabelMergeMode.AUTHORED_OVERLAY) {
                applyExplicitInstanceLabels(target, source, inheritedDeclarationOnly);
            } else if (labelMergeMode == LabelMergeMode.REFERENCE_EXPANSION) {
                copyMaterializedReferenceLabels(target, source);
            }
            if (outermost) {
                validateCompletedCandidates(state);
            }
        } finally {
            if (outermost) {
                popLabelProvenanceScope(outermostLabelScope);
                if (enteredOutermostLimit) {
                    limits.exitPathSegment();
                }
                resolutionState = null;
            }
        }
    }

    private void mergeInternal(Node target, Node source, Limits limits) {
        if (source.getBlue() != null) {
            throw new IllegalArgumentException("Document contains \"blue\" attribute. Preprocess document before merging.");
        }

        TypeResolutionKey deferredTypeResolution = null;
        /*
         * A selectively preserved path is an exact authored subtree, not a
         * complete instance of its declared type. Keep its type metadata for
         * the eventual exact-path restoration, but do not expand the type or
         * validate its schema while walking the surrounding document.
         *
         * DeferredReferencePathLimits expresses that boundary by allowing the
         * path itself to merge while denying reference expansion below it.
         * Ordinary limited and unlimited resolution continue to enter merged
         * paths with reference expansion enabled.
         */
        if (source.getType() != null
                && resolutionState.referenceExpansionAllowed) {
            Node typeNode = source.getType();
            String typeBlueId = typeNode.getBlueId();
            LabelProvenanceScope labelScope = currentLabelProvenanceScope();
            LabelPath currentLabelPath = currentLabelPath(resolutionState);
            if (labelScope != null
                    && resolutionState.contribution != Contribution.TYPE_ROOT
                    && resolutionState.contribution != Contribution.TYPE_METADATA
                    && resolutionState.contribution != Contribution.TYPE_DECLARATION
                    && hasLabelPathAtOrBelow(labelScope.labelPaths, currentLabelPath)) {
                recordTypeDeclarationLabelPaths(
                        typeNode, currentLabelPath, labelScope.labelPaths);
            }
            boolean typeContributionApplied = hasAppliedDeclaredTypeContribution(target, typeBlueId);
            /*
             * Type ancestry reached through item/key/value metadata remains
             * declaration metadata at every depth. Ordinary instance type
             * expansion keeps the TYPE_ROOT boundary used by completed-value
             * validation and processor presence accounting.
             */
            Contribution typeExpansionContribution =
                    resolutionState.contribution == Contribution.TYPE_METADATA
                            ? Contribution.TYPE_METADATA
                            : Contribution.TYPE_ROOT;
            boolean materializedCyclicType = isMaterializedCyclicSetMemberType(typeNode);
            FrozenNode cachedResolvedType = cachedResolvedType(typeBlueId, limits);
            boolean trackedType = typeBlueId != null;
            TypeResolutionKey typeResolutionKey = trackedType
                    ? new TypeResolutionKey(typeBlueId, resolutionState.path.size())
                    : null;
            if (trackedType && isResolvingType(typeResolutionKey)) {
                throw new IllegalStateException("Cyclic type hierarchy at path "
                        + currentPath(resolutionState) + " for blueId: " + typeBlueId);
            }
            boolean recursiveTypeBoundary = trackedType && isMaterializingType(typeBlueId);
            boolean startedTypeResolution = trackedType && !recursiveTypeBoundary;
            if (startedTypeResolution) {
                beginResolvingType(typeResolutionKey);
            }
            try {
                if (!recursiveTypeBoundary) {
                    if (cachedResolvedType != null) {
                        Node resolvedType = cachedResolvedType.toNode();
                        if (resolvedType.getBlueId() == null) {
                            resolvedType.blueId(typeBlueId);
                        }
                        source.type(detachedResolvedTypeMetadata(resolvedType));
                        if (!typeContributionApplied) {
                            mergeObjectWithContribution(
                                    target, resolvedType, limits,
                                    typeExpansionContribution);
                            recordAppliedDeclaredTypeContribution(target, typeBlueId);
                        }
                    } else {
                        if (typeBlueId != null) {
                            extendTypeReference(typeNode, typeBlueId);
                        }

                        Node resolvedType = resolveWithContribution(
                                typeNode, limits, typeExpansionContribution);
                        cacheResolvedReference(typeBlueId, resolvedType, limits);
                        source.type(detachedResolvedTypeMetadata(resolvedType));
                        if (!typeContributionApplied) {
                            // Align cold and warm resolution only when the completed type is safe to reuse.
                            if (cachedResolvedType(typeBlueId, limits) != null) {
                                mergeObjectWithContribution(
                                        target, resolvedType, limits,
                                        typeExpansionContribution);
                            } else {
                                mergeWithContribution(
                                        target, typeNode, limits,
                                        typeExpansionContribution);
                            }
                            recordAppliedDeclaredTypeContribution(target, typeBlueId);
                        }
                    }
                }
                if (startedTypeResolution && materializedCyclicType) {
                    deferredTypeResolution = typeResolutionKey;
                }
            } finally {
                if (startedTypeResolution && deferredTypeResolution == null) {
                    finishResolvingType(typeResolutionKey);
                }
            }
        }
        try {
            mergeObject(target, source, limits);
        } finally {
            if (deferredTypeResolution != null) {
                finishResolvingType(deferredTypeResolution);
            }
        }
    }

    private boolean hasAppliedDeclaredTypeContribution(Node target, String sourceTypeBlueId) {
        if (sourceTypeBlueId == null || resolutionState.appliedTypeContributions == null) {
            return false;
        }
        Set<String> applied = resolutionState.appliedTypeContributions.get(target);
        return applied != null && applied.contains(sourceTypeBlueId);
    }

    private void recordAppliedDeclaredTypeContribution(Node target, String sourceTypeBlueId) {
        if (sourceTypeBlueId == null) {
            return;
        }
        if (resolutionState.appliedTypeContributions == null) {
            resolutionState.appliedTypeContributions = new IdentityHashMap<>();
        }
        Set<String> applied = resolutionState.appliedTypeContributions.get(target);
        if (applied == null) {
            applied = new HashSet<>();
            resolutionState.appliedTypeContributions.put(target, applied);
        }
        applied.add(sourceTypeBlueId);
    }

    /**
     * Keeps completed type metadata independent from the mutable contribution traversal.
     * Merging processors may retain and further resolve nodes from the contribution graph;
     * sharing that graph with {@code source.type} makes an exposed resolved view depend on
     * traversal and cache history.
     */
    private Node detachedResolvedTypeMetadata(Node resolvedType) {
        return resolvedType.clone();
    }

    private void extendTypeReference(Node typeNode, String blueId) {
        if (CORE_TYPE_BLUE_IDS.contains(blueId)) {
            return;
        }
        CanonicalReference canonicalReference = typeCanonicalReference(blueId, resolutionState);
        if (canonicalReference.canonical.containsSchema()) {
            resolutionState.schemaRequiresTypeSourceProvenance = true;
        }
        typeNode.replaceWith(canonicalReference.canonical.toNode());
        typeNode.blueId(blueId);
    }

    private CanonicalReference typeCanonicalReference(String blueId, ResolutionState state) {
        CanonicalReference local = localCanonicalReference(state, blueId);
        if (local != null) {
            return local;
        }
        FrozenNode cached = resolvedReferenceCache != null
                ? resolvedReferenceCache.getVerifiedCanonical(blueId).orElse(null)
                : null;
        if (cached != null) {
            return rememberCanonical(state, blueId, cached, true);
        }
        FrozenNode canonical = canCacheDirectCanonical(blueId)
                ? resolvedReferenceCache.getOrLoadVerifiedCanonical(
                blueId,
                () -> FrozenNode.fromNode(
                        singleTypeProviderContent(blueId)))
                : FrozenNode.fromNode(
                singleTypeProviderContent(blueId));
        return rememberCanonical(state, blueId, canonical, true);
    }

    private boolean canCacheDirectCanonical(String blueId) {
        return resolvedReferenceCache != null
                && blueId != null
                && !BlueIds.hasCyclicMemberSeparator(blueId)
                && !BlueRuntimeTypeRegistry.getDefault().isProcessorManagedTypeBlueId(blueId);
    }

    private Node singleTypeProviderContent(String blueId) {
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
        Node canonical = typeNodes.get(0).clone();
        if (canonical.getBlueId() != null) {
            canonical.blueId(null);
        }
        return canonical;
    }

    private FrozenNode cachedResolvedReference(String blueId, Limits limits) {
        if (blueId == null || resolvedReferenceCache == null || limits != Limits.NO_LIMITS) {
            return null;
        }
        return resolvedReferenceCache.getVerifiedResolved(blueId).orElse(null);
    }

    private FrozenNode cachedResolvedType(String blueId, Limits limits) {
        FrozenNode cached = cachedResolvedReference(blueId, limits);
        if (cached == null) {
            return null;
        }
        ResolutionState state = resolutionState;
        if (cached.containsSchema()) {
            state.schemaRequiresTypeSourceProvenance = true;
        }
        if (!cached.containsNestedTypedObjectPayload()) {
            return cached;
        }
        if (state.schemaRequiresTypeSourceProvenance) {
            return null;
        }
        if (!state.rootSourceSchemaChecked) {
            state.rootSourceContainsSchema = containsSchema(state.rootSource);
            state.rootSourceSchemaChecked = true;
            if (state.rootSourceContainsSchema) {
                state.schemaRequiresTypeSourceProvenance = true;
            }
        }
        return state.schemaRequiresTypeSourceProvenance ? null : cached;
    }

    private boolean containsSchema(Node root) {
        if (root == null) {
            return false;
        }
        Set<Node> visited = Collections.newSetFromMap(new IdentityHashMap<Node, Boolean>());
        List<Node> pending = new ArrayList<>();
        pending.add(root);
        while (!pending.isEmpty()) {
            Node node = pending.remove(pending.size() - 1);
            if (node == null || !visited.add(node)) {
                continue;
            }
            if (node.getSchema() != null) {
                return true;
            }
            pending.add(node.getType());
            pending.add(node.getItemType());
            pending.add(node.getKeyType());
            pending.add(node.getValueType());
            pending.add(node.getContracts());
            pending.add(node.getBlue());
            if (node.getItems() != null) {
                pending.addAll(node.getItems());
            }
            if (node.getProperties() != null) {
                pending.addAll(node.getProperties().values());
            }
        }
        return false;
    }

    private boolean isResolvingType(TypeResolutionKey key) {
        return resolutionState.resolvingTypes != null
                && resolutionState.resolvingTypes.contains(key);
    }

    private boolean isMaterializingType(String blueId) {
        return resolutionState.materializingTypeBlueIds != null
                && resolutionState.materializingTypeBlueIds.contains(blueId);
    }

    private void beginResolvingType(TypeResolutionKey key) {
        ResolutionState state = resolutionState;
        if (state.resolvingTypes == null) {
            state.resolvingTypes = new HashSet<>();
        }
        if (state.materializingTypeBlueIds == null) {
            state.materializingTypeBlueIds = new HashSet<>();
        }
        state.resolvingTypes.add(key);
        state.materializingTypeBlueIds.add(key.blueId);
    }

    private void finishResolvingType(TypeResolutionKey key) {
        ResolutionState state = resolutionState;
        state.resolvingTypes.remove(key);
        state.materializingTypeBlueIds.remove(key.blueId);
    }

    private void cacheResolvedReference(String blueId, Node resolvedType, Limits limits) {
        if (blueId == null || resolvedReferenceCache == null || limits != Limits.NO_LIMITS) {
            return;
        }
        CanonicalReference local = localCanonicalReference(resolutionState, blueId);
        if (local == null || !local.directlyVerified) {
            return;
        }
        FrozenNode canonical = resolvedReferenceCache.getVerifiedCanonical(blueId).orElse(null);
        if (canonical != null) {
            FrozenNode frozenResolved = resolvedReferenceCache.freezeResolved(resolvedType);
            if (!frozenResolved.isReferenceOnly()) {
                resolvedReferenceCache.putVerifiedResolved(new VerifiedReferenceResolution(
                        blueId, canonical, frozenResolved));
            }
        }
    }

    private void mergeObject(Node target, Node source, Limits limits) {
        materializeReferenceBackedSchema(source);
        materializeReferenceBackedContracts(source);
        ResolutionState state = resolutionState;
        String path = currentPath(state);
        boolean tracksSemanticPresence = tracksSemanticPresence(state, target, source, path);
        ContributionFrame frame = null;
        if (tracksSemanticPresence) {
            frame = new ContributionFrame(
                    path, state.path.size(), isDirectSemanticContribution(source, state.contribution),
                    isInheritedReferenceContribution(target, source),
                    state.contribution != Contribution.CONTRACT_ROOT);
            state.contributionFrames.add(frame);
        }
        try {

            resolveTypeMetadata(source, limits);
            mergingProcessor.process(target, source, nodeProvider, this);

            List<Node> children = source.getItems();
            if (children != null) {
                mergeChildren(target, children, limits);
            }

            if (source.getContracts() != null && limits.shouldMergePathSegment(Properties.OBJECT_CONTRACTS, source.getContracts())) {
                boolean referenceExpansionAllowed = limits == Limits.NO_LIMITS
                        || limits.shouldExtendPathSegment(Properties.OBJECT_CONTRACTS, source.getContracts());
                limits.enterPathSegment(Properties.OBJECT_CONTRACTS, source.getContracts());
                enterValidationPath(Properties.OBJECT_CONTRACTS, referenceExpansionAllowed);
                try {
                    mergeContractsWithContribution(target, source.getContracts(), limits);
                } finally {
                    exitValidationPath();
                    limits.exitPathSegment();
                }
            } else if (source.getContracts() != null) {
                markIncomplete(Properties.OBJECT_CONTRACTS);
            }

            Map<String, Node> properties = source.getProperties();
            if (properties != null) {
                properties.forEach((key, value) -> {
                    if (limits.shouldMergePathSegment(key, value)) {
                        boolean referenceExpansionAllowed = limits == Limits.NO_LIMITS
                                || limits.shouldExtendPathSegment(key, value);
                        boolean trackValidationPath = shouldTrackValidationPath(target, key, value);
                        limits.enterPathSegment(key, value);
                        if (trackValidationPath) {
                            enterValidationPath(key, referenceExpansionAllowed);
                        }
                        try {
                            mergePropertyWithContribution(target, key, value, limits,
                                    childContribution(state.contribution));
                        } finally {
                            if (trackValidationPath) {
                                exitValidationPath();
                            }
                            limits.exitPathSegment();
                        }
                    } else {
                        markIncomplete(key);
                    }
                });
            }

            if (source.getBlueId() != null) {
                target.blueId(source.getBlueId());
            }

            mergingProcessor.postProcess(target, source, nodeProvider, this);
            if (target.getSchema() != null || source.getBlueId() != null) {
                observeCompletedPath(target, source, limits);
            }
        } finally {
            if (frame != null) {
                state.contributionFrames.remove(state.contributionFrames.size() - 1);
                boolean semanticContribution = frame.semanticContribution
                        || frame.inheritedSemanticContribution;
                if (semanticContribution) {
                    presenceGate(state, frame.path).present = true;
                }
                if (semanticContribution && frame.propagatesToParent
                        && !state.contributionFrames.isEmpty()) {
                    state.contributionFrames.get(state.contributionFrames.size() - 1).semanticContribution = true;
                }
            }
        }
    }




    private void materializeReferenceBackedSchema(Node source) {
        Schema schema = source.getSchema();
        if (schema == null || !schema.isReferenceOnly()) {
            return;
        }
        String blueId = schema.getBlueId();
        Node content = requiredProviderContent(blueId, resolutionState);
        Object schemaValue = NodeToMapListOrValue.get(content);
        Schema materialized = NodeDeserializer.parseSchema(
                JSON_MAPPER.valueToTree(schemaValue),
                JsonPointer.append(
                        currentPath(resolutionState),
                        Properties.OBJECT_SCHEMA));
        if (materialized.isReferenceOnly()) {
            throw new IllegalArgumentException(
                    "Provider returned reference-only schema content for required blueId: " + blueId);
        }
        source.schema(materialized);
    }

    private void materializeReferenceBackedContracts(Node source) {
        Node contracts = source.getContracts();
        if (contracts == null || !contracts.isReferenceOnly()) {
            return;
        }
        String blueId = contracts.getBlueId();
        Node materialized = requiredProviderContent(blueId, resolutionState);
        if (materialized.isReferenceOnly()) {
            throw new IllegalArgumentException(
                    "Provider returned reference-only contracts content for required blueId: "
                            + blueId);
        }
        source.contracts(materialized);
    }

    private boolean tracksSemanticPresence(ResolutionState state,
                                           Node target,
                                           Node source,
                                           String path) {
        return state.contribution == Contribution.TYPE_ROOT
                || state.contribution == Contribution.TYPE_DECLARATION
                || target.getSchema() != null
                || source.getSchema() != null
                || !state.contributionFrames.isEmpty()
                || (state.presenceGates != null && state.presenceGates.containsKey(path));
    }

    private Contribution childContribution(Contribution contribution) {
        if (contribution == Contribution.TYPE_ROOT) {
            return Contribution.TYPE_DECLARATION;
        }
        if (contribution == Contribution.CONTRACT_ROOT) {
            return Contribution.CONTRACT_CONTENT;
        }
        return contribution;
    }

    private void mergeChildren(Node target, List<Node> sourceChildren, Limits limits) {
        List<Node> targetChildren = target.getItems();
        String mergePolicy = effectiveMergePolicy(target);

        validateListControlScope(target, sourceChildren);
        validateListControls(sourceChildren, mergePolicy);

        if (targetChildren == null) {
            if (startsWithPrevious(sourceChildren)) {
                targetChildren = resolvePreviousAnchor(sourceChildren.get(0), limits, target.getItemType());
                target.items(targetChildren);
                validatePreviousAnchor(targetChildren, sourceChildren.get(0));
                if (LIST_MERGE_POLICY_APPEND_ONLY.equals(mergePolicy)) {
                    mergeAppendOnlyChildren(targetChildren, sourceChildren, limits, target.getItemType());
                } else {
                    mergePositionalChildren(targetChildren, sourceChildren, limits, target.getItemType());
                }
                return;
            }
            targetChildren = resolveInitialChildren(sourceChildren, limits, target.getItemType());
            target.items(targetChildren);
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

    private List<Node> resolveInitialChildren(List<Node> sourceChildren, Limits limits, Node itemType) {
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
            Node resolvedChild = resolveListChild(child, limits, String.valueOf(result.size()), itemType);
            if (resolvedChild != null) {
                result.add(resolvedChild);
            }
        }
        return result;
    }

    private void mergeAppendOnlyChildren(List<Node> targetChildren, List<Node> sourceChildren, Limits limits, Node itemType) {
        if (startsWithPrevious(sourceChildren)) {
            appendChildren(targetChildren, sourceChildren, 1, limits, itemType);
            return;
        }

        if (sourceChildren.size() < targetChildren.size())
            throw new IllegalArgumentException(String.format(
                    "Subtype of element must not have more items (%d) than the element itself (%d).",
                    targetChildren.size(), sourceChildren.size()
            ));

        for (int i = 0; i < sourceChildren.size(); i++) {
            if (i >= targetChildren.size()) {
                Node resolvedChild = resolveListChild(sourceChildren.get(i), limits, String.valueOf(i), itemType);
                if (resolvedChild != null) {
                    targetChildren.add(resolvedChild);
                }
                continue;
            }
            Node sourceChild = resolveListChild(sourceChildren.get(i), limits, String.valueOf(i), itemType);
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

    private void mergePositionalChildren(List<Node> targetChildren, List<Node> sourceChildren, Limits limits, Node itemType) {
        boolean hasPositionControls = sourceChildren.stream().anyMatch(child -> child.getPosition() != null);
        int start = startsWithPrevious(sourceChildren) ? 1 : 0;

        if (!hasPositionControls) {
            if (startsWithPrevious(sourceChildren)) {
                appendChildren(targetChildren, sourceChildren, start, limits, itemType);
                return;
            }
            mergePlainPositionalChildren(targetChildren, sourceChildren, start, limits, itemType);
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
                mergeOrReplacePosition(targetChildren, position, withoutPosition(sourceChild), limits, itemType);
            } else {
                Node resolvedChild = resolveListChild(sourceChild, limits, String.valueOf(targetChildren.size()), itemType);
                if (resolvedChild != null) {
                    targetChildren.add(resolvedChild);
                }
            }
        }
    }

    private void mergePlainPositionalChildren(List<Node> targetChildren, List<Node> sourceChildren, int start, Limits limits, Node itemType) {
        int sourceLength = sourceChildren.size() - start;
        if (sourceLength < targetChildren.size()) {
            throw new IllegalArgumentException(String.format(
                    "Positional list overlays cannot remove inherited items: inherited %d items but source supplied %d.",
                    targetChildren.size(), sourceLength
            ));
        }

        List<String> inheritedIdentities = new ArrayList<>(targetChildren.size());
        for (Node inherited : targetChildren) {
            inheritedIdentities.add(BlueIdCalculator.calculateBlueId(inherited));
        }

        for (int i = 0; i < sourceLength; i++) {
            Node sourceChild = sourceChildren.get(start + i);
            if (i >= targetChildren.size()) {
                Node resolvedChild = resolveListChild(sourceChild, limits, String.valueOf(i), itemType);
                if (resolvedChild != null) {
                    targetChildren.add(resolvedChild);
                }
            } else {
                String sourceIdentity = BlueIdCalculator.calculateBlueId(sourceChild);
                if (!sourceIdentity.equals(inheritedIdentities.get(i))
                        && inheritedIdentities.contains(sourceIdentity)) {
                    throw new IllegalArgumentException(
                            "Positional list overlays cannot reorder inherited items; "
                                    + "use a valid $pos replacement at index " + i + ".");
                }
                String segment = String.valueOf(i);
                if (!limits.shouldMergePathSegment(segment, sourceChild)) {
                    markIncomplete(segment);
                    continue;
                }
                boolean referenceExpansionAllowed = limits == Limits.NO_LIMITS
                        || limits.shouldExtendPathSegment(segment, sourceChild);
                limits.enterPathSegment(segment, sourceChild);
                enterValidationPath(segment, referenceExpansionAllowed);
                try {
                    merge(targetChildren.get(i), sourceChild, limits);
                } finally {
                    exitValidationPath();
                    limits.exitPathSegment();
                }
            }
        }
    }

    private void mergeOrReplacePosition(List<Node> targetChildren, int position, Node overlay, Limits limits, Node itemType) {
        Node effectiveItemType = targetChildren.get(position).getType() != null
                ? targetChildren.get(position).getType()
                : itemType;
        if (hasReplacement(overlay)) {
            Node replacement = overlay.getProperties().get(LIST_CONTROL_REPLACE);
            if (isEmptyPlaceholder(replacement)
                    && !isEmptyPlaceholder(targetChildren.get(position))) {
                throw new IllegalArgumentException(
                        "Fixed value conflict: replacement cannot remove inherited content.");
            }
            Node resolvedChild = resolveListChild(replacement, limits, String.valueOf(position), effectiveItemType);
            if (resolvedChild != null) {
                targetChildren.set(position, resolvedChild);
            }
            return;
        }
        if (isEmptyPlaceholder(targetChildren.get(position)) || overlay.getValue() != null || overlay.getItems() != null) {
            Node resolvedChild = resolveListChild(overlay, limits, String.valueOf(position), effectiveItemType);
            if (resolvedChild != null) {
                targetChildren.set(position, resolvedChild);
            }
            return;
        }
        if (overlay.getType() != null) {
            Node resolvedOverlay = resolveListChild(overlay, limits, String.valueOf(position), effectiveItemType);
            if (resolvedOverlay != null) {
                String segment = String.valueOf(position);
                boolean referenceExpansionAllowed = limits == Limits.NO_LIMITS
                        || limits.shouldExtendPathSegment(segment, resolvedOverlay);
                limits.enterPathSegment(segment, resolvedOverlay);
                enterValidationPath(segment, referenceExpansionAllowed);
                try {
                    mergeInstanceObject(targetChildren.get(position), resolvedOverlay, limits);
                } finally {
                    exitValidationPath();
                    limits.exitPathSegment();
                }
            }
            return;
        }
        if (isObjectOverlay(overlay) && !isObjectCompatibleListItem(targetChildren.get(position))) {
            throw new IllegalArgumentException("\"$pos\" object overlays require an object-compatible inherited list item.");
        }
        String segment = String.valueOf(position);
        if (!limits.shouldMergePathSegment(segment, overlay)) {
            markIncomplete(segment);
            return;
        }
        boolean referenceExpansionAllowed = limits == Limits.NO_LIMITS
                || limits.shouldExtendPathSegment(segment, overlay);
        limits.enterPathSegment(segment, overlay);
        enterValidationPath(segment, referenceExpansionAllowed);
        try {
            merge(targetChildren.get(position), overlay, limits);
        } finally {
            exitValidationPath();
            limits.exitPathSegment();
        }
    }

    private boolean isObjectOverlay(Node overlay) {
        return overlay.getProperties() != null && !overlay.getProperties().isEmpty();
    }

    private boolean shouldTrackValidationPath(Node target, String key, Node source) {
        if (!isUnconstrainedScalar(source)) {
            return true;
        }
        Node inherited = target.getProperties() != null ? target.getProperties().get(key) : null;
        return inherited != null && !isUnconstrainedScalar(inherited);
    }

    private boolean isUnconstrainedScalar(Node node) {
        return node != null
                && node.getValue() != null
                && node.getType() == null
                && node.getSchema() == null
                && node.getBlueId() == null
                && node.getContracts() == null;
    }

    private boolean isObjectCompatibleListItem(Node inherited) {
        return inherited != null
                && inherited.getValue() == null
                && inherited.getItems() == null
                && inherited.getBlueId() == null;
    }

    private void appendChildren(List<Node> targetChildren, List<Node> sourceChildren, int start, Limits limits, Node itemType) {
        for (int i = start; i < sourceChildren.size(); i++) {
            Node resolvedChild = resolveListChild(sourceChildren.get(i), limits, String.valueOf(targetChildren.size()), itemType);
            if (resolvedChild != null) {
                targetChildren.add(resolvedChild);
            }
        }
    }

    private List<Node> resolvePreviousAnchor(Node previousAnchor, Limits limits, Node itemType) {
        List<Node> fetched = nodeProvider.fetchByBlueId(previousAnchor.getPreviousBlueId());
        if (fetched == null || fetched.isEmpty()) {
            throw new IllegalArgumentException("No content found for $previous blueId: " + previousAnchor.getPreviousBlueId());
        }

        List<Node> previousChildren = fetched.size() == 1 && fetched.get(0).getItems() != null
                ? fetched.get(0).getItems()
                : fetched;
        List<Node> resolved = new ArrayList<>();
        for (int i = 0; i < previousChildren.size(); i++) {
            Node resolvedChild = resolveListChild(previousChildren.get(i), limits, String.valueOf(i), itemType);
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
        if (properties == null
                || properties.size() != 1
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

    private Node resolveListChild(Node child, Limits limits, String segment, Node itemType) {
        if (child.getPreviousBlueId() != null || child.getPosition() != null) {
            throw new IllegalArgumentException("List control items must be consumed before resolving list children.");
        }
        if (!limits.shouldMergePathSegment(segment, child)) {
            markIncomplete(segment);
            return null;
        }
        boolean referenceExpansionAllowed = limits == Limits.NO_LIMITS
                || limits.shouldExtendPathSegment(segment, child);
        limits.enterPathSegment(segment, child);
        enterValidationPath(segment, referenceExpansionAllowed);
        try {
            return resolve(applyItemType(child, itemType), limits);
        } finally {
            exitValidationPath();
            limits.exitPathSegment();
        }
    }

    private Node applyItemType(Node child, Node itemType) {
        if (child.getType() != null || child.getBlueId() != null || itemType == null) {
            return child;
        }
        return child.clone().type(itemTypeReference(itemType));
    }

    private Node itemTypeReference(Node itemType) {
        if (itemType.getBlueId() != null) {
            return new Node().blueId(itemType.getBlueId());
        }
        return itemType.clone();
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
        if (node.getItems() != null) {
            return true;
        }
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
            } else if (hasReplacement(child)) {
                throw new IllegalArgumentException("\"$replace\" is valid only inside a \"$pos\" list overlay.");
            }
            if (hasReplacement(child)) {
                validateReplacementOverlay(child);
            }
        }
    }

    private boolean hasReplacement(Node node) {
        return node.getProperties() != null && node.getProperties().containsKey(LIST_CONTROL_REPLACE);
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
            throw new IllegalArgumentException("\"$replace\" cannot be combined with sibling overlay fields other than \"$pos\".");
        }
    }

    private void mergeProperty(Node target, String sourceKey, Node sourceValue, Limits limits) {
        if (target.getProperties() == null)
            target.properties(new LinkedHashMap<>());
        Node targetValue = target.getProperties().get(sourceKey);
        if (targetValue == null) {
            Node node = resolve(sourceValue, limits);
            target.getProperties().put(sourceKey, node);
        } else {
            if (requiresCyclicTypeCompletion(targetValue, sourceValue)) {
                Node typedSource = sourceValue.clone()
                        .type(new Node().blueId(targetValue.getType().getBlueId()));
                merge(targetValue, typedSource, limits);
            } else if (hasListControls(sourceValue)) {
                merge(targetValue, sourceValue, limits);
            } else if (containsCyclicSetReference(sourceValue)) {
                merge(targetValue, sourceValue, limits);
            } else {
                Node node = resolve(sourceValue, limits);
                mergeInstanceObject(targetValue, node, limits);
            }
        }
    }

    private void mergeInstanceObject(Node target, Node source, Limits limits) {
        LabelMergeMode labelMergeMode = labelMergeMode(resolutionState.contribution);
        boolean inheritedDeclarationOnly = labelMergeMode == LabelMergeMode.AUTHORED_OVERLAY
                && isDeclarationOnlyForLabels(target);
        if (labelMergeMode == LabelMergeMode.AUTHORED_OVERLAY) {
            validateExplicitInstanceLabels(target, source, inheritedDeclarationOnly);
        }
        mergeObject(target, source, limits);
        if (labelMergeMode == LabelMergeMode.AUTHORED_OVERLAY) {
            applyExplicitInstanceLabels(target, source, inheritedDeclarationOnly);
        } else if (labelMergeMode == LabelMergeMode.REFERENCE_EXPANSION) {
            copyMaterializedReferenceLabels(target, source);
        }
    }

    private LabelMergeMode labelMergeMode(Contribution contribution) {
        if (contribution == Contribution.MATERIALIZED_REFERENCE) {
            return LabelMergeMode.REFERENCE_EXPANSION;
        }
        if (contribution == Contribution.TYPE_ROOT) {
            return LabelMergeMode.NONE;
        }
        if (contribution == Contribution.TYPE_METADATA) {
            /*
             * TYPE_METADATA must remain the semantic contribution throughout
             * metadata children: processor presence and completed-schema
             * validation depend on that boundary. Labels authored below the
             * metadata root are nevertheless declaration overlays and may
             * refine labels inherited from the metadata type hierarchy.
             */
            LabelProvenanceScope scope = currentLabelProvenanceScope();
            return scope != null
                    && !currentLabelPath(resolutionState).equals(scope.rootPath)
                    ? LabelMergeMode.AUTHORED_OVERLAY
                    : LabelMergeMode.NONE;
        }
        return LabelMergeMode.AUTHORED_OVERLAY;
    }

    /**
     * A declaration-only child inherits labels until an instance explicitly
     * overrides them. Fixed payload labels remain governed by fixed-value rules.
     */
    private boolean isDeclarationOnlyForLabels(Node node) {
        ResolutionState state = resolutionState;
        if (state != null) {
            LabelPath path = currentLabelPath(state);
            for (int index = state.labelProvenanceScopes.size() - 1; index >= 0; index--) {
                LabelProvenanceScope scope = state.labelProvenanceScopes.get(index);
                if (scope.fixedPaths.contains(path)) {
                    return false;
                }
                if (scope.declarationOnlyPaths.contains(path)) {
                    return true;
                }
            }
        }
        return !sourceContainsFixedContent(node);
    }

    private void recordTypeDeclarationLabelPaths(Node typeNode,
                                                 LabelPath basePath,
                                                 Set<LabelPath> relevantLabelPaths) {
        LabelProvenanceScope scope = currentLabelProvenanceScope();
        if (scope == null || !hasLabelPathAtOrBelow(relevantLabelPaths, basePath)) {
            return;
        }
        LabelScanState scan = new LabelScanState(scope, relevantLabelPaths);
        Deque<LabelScanTask> pending = new ArrayDeque<>();
        pending.push(LabelScanTask.type(typeNode, basePath));
        while (!pending.isEmpty()) {
            LabelScanTask task = pending.pop();
            switch (task.kind) {
                case TYPE:
                    scanTypeLabelTask(task, scan, pending);
                    break;
                case SOURCE:
                    scanSourceLabelTask(task.node, task.path, scan, pending);
                    break;
                case CHILDREN:
                    scanDirectChildLabelTasks(task.node, task.path, scan, pending);
                    break;
                case EXIT_TYPE:
                    scan.exitType(task.typeBlueId, task.node);
                    break;
                default:
                    throw new IllegalStateException("Unknown label scan task: " + task.kind);
            }
        }
    }

    private void scanTypeLabelTask(LabelScanTask task,
                                   LabelScanState scan,
                                   Deque<LabelScanTask> pending) {
        Node typeNode = task.node;
        if (typeNode == null || isBareCoreTypeAlias(typeNode)
                || !hasLabelPathAtOrBelow(scan.relevantLabelPaths, task.path)) {
            return;
        }
        String typeBlueId = typeNode.getBlueId();
        if (typeBlueId != null && CORE_TYPE_BLUE_IDS.contains(typeBlueId)) {
            return;
        }
        if (!scan.enterType(typeBlueId, typeNode)) {
            return;
        }
        Node canonicalType;
        try {
            canonicalType = canonicalTypeForLabelProvenance(typeNode);
        } catch (RuntimeException failure) {
            scan.exitType(typeBlueId, typeNode);
            throw failure;
        }
        if (canonicalType == null) {
            scan.exitType(typeBlueId, typeNode);
            return;
        }
        pending.push(LabelScanTask.exitType(typeBlueId, typeNode));
        pending.push(LabelScanTask.children(canonicalType, task.path));
        pending.push(LabelScanTask.type(canonicalType.getType(), task.path));
    }

    private Node canonicalTypeForLabelProvenance(Node typeNode) {
        String typeBlueId = typeNode.getBlueId();
        if (typeBlueId == null) {
            return typeNode;
        }
        if (CORE_TYPE_BLUE_IDS.contains(typeBlueId)) {
            return null;
        }
        return typeCanonicalReference(typeBlueId, resolutionState).canonical.toNode();
    }

    private void scanSourceLabelTask(Node source,
                                     LabelPath path,
                                     LabelScanState scan,
                                     Deque<LabelScanTask> pending) {
        if (source == null || !hasLabelPathAtOrBelow(scan.relevantLabelPaths, path)) {
            return;
        }
        if (scan.relevantLabelPaths.contains(path)) {
            setDeclarationOnlyLabelPath(
                    scan.scope, path,
                    !sourceContainsFixedContent(source));
        }
        pending.push(LabelScanTask.children(source, path));
        pending.push(LabelScanTask.type(source.getType(), path));
    }

    private void scanDirectChildLabelTasks(Node source,
                                           LabelPath basePath,
                                           LabelScanState scan,
                                           Deque<LabelScanTask> pending) {
        if (source == null || !hasLabelPathAtOrBelow(scan.relevantLabelPaths, basePath)) {
            return;
        }
        List<Map.Entry<String, Node>> properties = source.getProperties() == null
                ? Collections.<Map.Entry<String, Node>>emptyList()
                : new ArrayList<>(source.getProperties().entrySet());
        for (int index = properties.size() - 1; index >= 0; index--) {
            Map.Entry<String, Node> property = properties.get(index);
            LabelPath childPath = basePath.child(property.getKey());
            if (hasLabelPathAtOrBelow(scan.relevantLabelPaths, childPath)) {
                pending.push(LabelScanTask.source(property.getValue(), childPath));
            }
        }
        scanDirectListChildLabelTasks(source, basePath, scan, pending);
        LabelPath contractsPath = basePath.child(Properties.OBJECT_CONTRACTS);
        if (source.getContracts() != null
                && hasLabelPathAtOrBelow(scan.relevantLabelPaths, contractsPath)) {
            pending.push(LabelScanTask.source(source.getContracts(), contractsPath));
        }
    }

    private void scanDirectListChildLabelTasks(Node source,
                                               LabelPath basePath,
                                               LabelScanState scan,
                                               Deque<LabelScanTask> pending) {
        List<Node> children = source.getItems();
        Node effectiveItemType = source.getItemType() != null
                ? source.getItemType()
                : scan.effectiveItemTypes.get(basePath);
        if (source.getItemType() != null) {
            scan.effectiveItemTypes.put(basePath, source.getItemType());
        }
        if (children == null || !hasLabelPathAtOrBelow(scan.relevantLabelPaths, basePath)) {
            return;
        }

        int size = scan.listSizes.getOrDefault(basePath, 0);
        Map<Integer, Node> effectiveItems = scan.effectiveListItems.computeIfAbsent(
                basePath, ignored -> new HashMap<>());
        int start = startsWithPrevious(children) ? 1 : 0;
        List<PositionedLabelSource> effectiveChildren = new ArrayList<>();
        if (start > 0 && size == 0) {
            List<Node> previousChildren = previousLabelChildren(children.get(0));
            for (int index = 0; index < previousChildren.size(); index++) {
                Node effectiveChild = applyItemType(previousChildren.get(index), effectiveItemType);
                effectiveChildren.add(new PositionedLabelSource(index, effectiveChild));
                effectiveItems.put(index, effectiveChild);
            }
            size = previousChildren.size();
        }

        boolean hasPositionControls = children.stream()
                .anyMatch(child -> child.getPosition() != null);
        for (int index = start; index < children.size(); index++) {
            Node child = children.get(index);
            int position;
            Node effectiveChild;
            boolean replacement = false;
            if (child.getPosition() != null) {
                position = child.getPosition();
                Node overlay = withoutPosition(child);
                Node previousItem = effectiveItems.get(position);
                Node positionItemType = previousItem != null && previousItem.getType() != null
                        ? previousItem.getType()
                        : effectiveItemType;
                if (hasReplacement(overlay)) {
                    replacement = true;
                    overlay = overlay.getProperties().get(LIST_CONTROL_REPLACE);
                }
                replacement = replacement
                        || (previousItem != null && isEmptyPlaceholder(previousItem))
                        || overlay.getValue() != null
                        || overlay.getItems() != null;
                effectiveChild = applyItemType(overlay, positionItemType);
                if (position == size) {
                    size++;
                }
            } else if (hasPositionControls || start > 0) {
                position = size++;
                effectiveChild = applyItemType(child, effectiveItemType);
            } else {
                position = index - start;
                Node previousItem = effectiveItems.get(position);
                Node positionItemType = previousItem != null && previousItem.getType() != null
                        ? previousItem.getType()
                        : effectiveItemType;
                effectiveChild = applyItemType(child, positionItemType);
                size = Math.max(size, position + 1);
            }
            Node previousItem = effectiveItems.get(position);
            effectiveItems.put(position, replacement || previousItem == null
                    ? effectiveChild
                    : effectiveListItemAfterOverlay(previousItem, effectiveChild));
            effectiveChildren.add(new PositionedLabelSource(
                    position, effectiveChild, replacement));
        }
        scan.listSizes.put(basePath, size);

        for (int index = effectiveChildren.size() - 1; index >= 0; index--) {
            PositionedLabelSource child = effectiveChildren.get(index);
            LabelPath childPath = basePath.child(String.valueOf(child.position));
            if (hasLabelPathAtOrBelow(scan.relevantLabelPaths, childPath)) {
                if (child.replacement) {
                    clearLabelClassificationAtOrBelow(scan.scope, childPath);
                }
                pending.push(LabelScanTask.source(child.node, childPath));
            }
        }
    }

    private List<Node> previousLabelChildren(Node previousAnchor) {
        List<Node> fetched = nodeProvider.fetchByBlueId(previousAnchor.getPreviousBlueId());
        if (fetched == null || fetched.isEmpty()) {
            throw new IllegalArgumentException(
                    "No content found for $previous blueId: " + previousAnchor.getPreviousBlueId());
        }
        return fetched.size() == 1 && fetched.get(0).getItems() != null
                ? fetched.get(0).getItems()
                : fetched;
    }

    private Node effectiveListItemAfterOverlay(Node inherited, Node overlay) {
        if (overlay.getType() != null || overlay.getBlueId() != null) {
            return overlay;
        }
        if (inherited.getType() != null) {
            return overlay.clone().type(itemTypeReference(inherited.getType()));
        }
        return overlay;
    }

    private boolean sourceContainsFixedContent(Node source) {
        return sourceContainsFixedContent(source, false);
    }

    private boolean sourceContainsFixedContent(Node source, boolean typeRoot) {
        Deque<FixedContentTask> pending = new ArrayDeque<>();
        Set<Node> visitedNodes = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<Node> visitedTypeRoots = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<String> visitedTypeBlueIds = new HashSet<>();
        Set<Node> visitedInlineTypes = Collections.newSetFromMap(
                new IdentityHashMap<Node, Boolean>());
        pending.push(new FixedContentTask(source, typeRoot));
        while (!pending.isEmpty()) {
            FixedContentTask task = pending.pop();
            Node current = task.node;
            Set<Node> visited = task.typeRoot ? visitedTypeRoots : visitedNodes;
            if (current == null || !visited.add(current)) {
                continue;
            }
            if (current.getRawValue() != null
                    || current.isInlineValue()
                    || current.getItems() != null
                    || (!task.typeRoot && current.getBlueId() != null)
                    || current.getPreviousBlueId() != null
                    || current.getPosition() != null) {
                return true;
            }
            enqueueTypeForFixedContent(
                    current.getType(), pending, visitedTypeBlueIds, visitedInlineTypes);
            if (current.getContracts() != null) {
                pending.push(new FixedContentTask(current.getContracts(), false));
            }
            if (current.getProperties() != null) {
                for (Node child : current.getProperties().values()) {
                    if (child != null) {
                        pending.push(new FixedContentTask(child, false));
                    }
                }
            }
        }
        return false;
    }

    private void enqueueTypeForFixedContent(Node typeNode,
                                            Deque<FixedContentTask> pending,
                                            Set<String> visitedTypeBlueIds,
                                            Set<Node> visitedInlineTypes) {
        if (typeNode == null || isBareCoreTypeAlias(typeNode)) {
            return;
        }
        String typeBlueId = typeNode.getBlueId();
        if (typeBlueId != null) {
            if (CORE_TYPE_BLUE_IDS.contains(typeBlueId)
                    || !visitedTypeBlueIds.add(typeBlueId)) {
                return;
            }
        } else if (!visitedInlineTypes.add(typeNode)) {
            return;
        }
        Node canonicalType = canonicalTypeForLabelProvenance(typeNode);
        if (canonicalType != null) {
            pending.push(new FixedContentTask(canonicalType, true));
        }
    }

    private void setDeclarationOnlyLabelPath(LabelProvenanceScope scope,
                                             LabelPath path,
                                             boolean declarationOnly) {
        if (scope == null || !scope.labelPaths.contains(path)) {
            return;
        }
        if (declarationOnly) {
            if (!scope.fixedPaths.contains(path)) {
                scope.declarationOnlyPaths.add(path);
            }
        } else {
            scope.declarationOnlyPaths.remove(path);
            scope.fixedPaths.add(path);
        }
    }

    private void clearLabelClassificationAtOrBelow(LabelProvenanceScope scope,
                                                    LabelPath path) {
        scope.declarationOnlyPaths.removeIf(candidate -> candidate.isAtOrBelow(path));
        scope.fixedPaths.removeIf(candidate -> candidate.isAtOrBelow(path));
    }

    private LabelProvenanceScope pushLabelProvenanceScope(Node source,
                                                          Limits limits,
                                                          boolean includeRootLabel) {
        ResolutionState state = resolutionState;
        if (state == null) {
            return null;
        }
        Set<LabelPath> labelPaths = new HashSet<>();
        collectAuthoredLabelPaths(
                source, currentLabelPath(state), limits, includeRootLabel, labelPaths,
                Collections.newSetFromMap(new IdentityHashMap<Node, Boolean>()));
        LabelProvenanceScope scope = new LabelProvenanceScope(
                currentLabelPath(state), labelPaths);
        state.labelProvenanceScopes.add(scope);
        return scope;
    }

    private void popLabelProvenanceScope(LabelProvenanceScope expected) {
        if (expected == null || resolutionState == null) {
            return;
        }
        List<LabelProvenanceScope> scopes = resolutionState.labelProvenanceScopes;
        if (scopes.isEmpty() || scopes.remove(scopes.size() - 1) != expected) {
            throw new IllegalStateException("Label provenance scope stack is unbalanced.");
        }
    }

    private LabelProvenanceScope currentLabelProvenanceScope() {
        ResolutionState state = resolutionState;
        if (state == null || state.labelProvenanceScopes.isEmpty()) {
            return null;
        }
        return state.labelProvenanceScopes.get(state.labelProvenanceScopes.size() - 1);
    }

    private void collectAuthoredLabelPaths(Node source,
                                           LabelPath path,
                                           Limits limits,
                                           boolean includeRootLabel,
                                           Set<LabelPath> labelPaths,
                                           Set<Node> activeNodes) {
        if (source == null || !activeNodes.add(source)) {
            return;
        }
        try {
            if ((includeRootLabel || !path.isRoot())
                    && (source.getName() != null || source.getDescription() != null)) {
                labelPaths.add(path);
            }
            collectAuthoredLabelPath(
                    source.getContracts(), Properties.OBJECT_CONTRACTS, path,
                    limits, labelPaths, activeNodes);
            if (source.getItems() != null) {
                collectAuthoredListLabelPaths(
                        source.getItems(), path, limits, labelPaths, activeNodes);
            }
            if (source.getProperties() != null) {
                source.getProperties().forEach((key, child) -> collectAuthoredLabelPath(
                        child, key, path, limits, labelPaths, activeNodes));
            }
        } finally {
            activeNodes.remove(source);
        }
    }

    private void collectAuthoredListLabelPaths(List<Node> children,
                                               LabelPath parentPath,
                                               Limits limits,
                                               Set<LabelPath> labelPaths,
                                               Set<Node> activeNodes) {
        boolean hasPositionControls = children.stream()
                .anyMatch(child -> child.getPosition() != null);
        int start = startsWithPrevious(children) ? 1 : 0;
        if (hasPositionControls) {
            for (int index = start; index < children.size(); index++) {
                Node child = children.get(index);
                if (child.getPosition() == null) {
                    // Unpositioned children in a controlled list are appended, so they
                    // do not overlay an inherited label at a pre-existing path.
                    continue;
                }
                collectAuthoredLabelPath(
                        effectivePositionOverlay(child), String.valueOf(child.getPosition()), parentPath,
                        limits, labelPaths, activeNodes);
            }
            return;
        }
        if (start > 0) {
            // Children after a $previous anchor are appended. Their own nested
            // resolution creates a scope at the effective appended position.
            return;
        }
        for (int index = 0; index < children.size(); index++) {
            collectAuthoredLabelPath(
                    children.get(index), String.valueOf(index), parentPath,
                    limits, labelPaths, activeNodes);
        }
    }

    private void collectAuthoredLabelPath(Node child,
                                          String segment,
                                          LabelPath parentPath,
                                          Limits limits,
                                          Set<LabelPath> labelPaths,
                                          Set<Node> activeNodes) {
        if (child == null || !limits.shouldMergePathSegment(segment, child)) {
            return;
        }
        limits.enterPathSegment(segment, child);
        try {
            collectAuthoredLabelPaths(
                    child, parentPath.child(segment), limits, true,
                    labelPaths, activeNodes);
        } finally {
            limits.exitPathSegment();
        }
    }

    private Node effectivePositionOverlay(Node child) {
        Node overlay = withoutPosition(child);
        return hasReplacement(overlay)
                ? overlay.getProperties().get(LIST_CONTROL_REPLACE)
                : overlay;
    }

    private boolean hasLabelPathAtOrBelow(Set<LabelPath> labelPaths, LabelPath path) {
        if (labelPaths.contains(path)) {
            return true;
        }
        for (LabelPath labelPath : labelPaths) {
            if (labelPath.isAtOrBelow(path)) {
                return true;
            }
        }
        return false;
    }

    private void seedMaterializedTargetLabelProvenance(Node target,
                                                       LabelProvenanceScope scope) {
        if (target == null || scope == null
                || !hasLabelPathAtOrBelow(scope.labelPaths, LabelPath.root())) {
            return;
        }
        if (target.getType() != null) {
            recordTypeDeclarationLabelPaths(
                    target.getType(), LabelPath.root(), scope.labelPaths);
        }
        for (LabelPath labelPath : scope.labelPaths) {
            Node materialized = nodeAtPath(target, labelPath);
            if (materialized != null && sourceContainsFixedContent(materialized)) {
                setDeclarationOnlyLabelPath(scope, labelPath, false);
            }
        }
    }

    private Node nodeAtPath(Node root, LabelPath path) {
        Node current = root;
        for (String segment : path.segments) {
            if (current == null) {
                return null;
            }
            if (Properties.OBJECT_CONTRACTS.equals(segment) && current.getContracts() != null) {
                current = current.getContracts();
                continue;
            }
            if (current.getItems() != null && JsonPointer.isArrayIndexSegment(segment)) {
                if ("-".equals(segment)) {
                    return null;
                }
                int index;
                try {
                    index = Integer.parseInt(segment);
                } catch (NumberFormatException ex) {
                    return null;
                }
                if (index < 0 || index >= current.getItems().size()) {
                    return null;
                }
                current = current.getItems().get(index);
                continue;
            }
            current = current.getProperties() == null
                    ? null
                    : current.getProperties().get(segment);
        }
        return current;
    }

    private void validateExplicitInstanceLabels(Node inherited,
                                                Node source,
                                                boolean inheritedDeclarationOnly) {
        if (source.getName() == null && source.getDescription() == null) {
            return;
        }
        if (inherited.isReferenceOnly()) {
            throw new IllegalArgumentException(
                    "An inherited pure reference cannot carry name or description overlays. Path: "
                            + currentPath(resolutionState));
        }
        if (inheritedDeclarationOnly) {
            return;
        }
        validateFixedValueLabel(Properties.OBJECT_NAME, inherited.getName(), source.getName());
        validateFixedValueLabel(Properties.OBJECT_DESCRIPTION, inherited.getDescription(), source.getDescription());
    }

    private void validateFixedValueLabel(String label, String inherited, String source) {
        if (source != null && inherited != null && !inherited.equals(source)) {
            throw new IllegalArgumentException(
                    "Inherited fixed value " + label + " conflicts at path "
                            + currentPath(resolutionState) + ". Source label: " + source
                            + ", inherited label: " + inherited);
        }
    }

    private void applyExplicitInstanceLabels(Node target,
                                             Node source,
                                             boolean inheritedDeclarationOnly) {
        if (source.getName() != null
                && (inheritedDeclarationOnly || target.getName() == null)) {
            target.name(source.getName());
        }
        if (source.getDescription() != null
                && (inheritedDeclarationOnly || target.getDescription() == null)) {
            target.description(source.getDescription());
        }
    }

    private void mergePropertyWithContribution(Node target,
                                               String sourceKey,
                                               Node sourceValue,
                                               Limits limits,
                                               Contribution contribution) {
        ResolutionState state = resolutionState;
        Contribution previous = state.contribution;
        state.contribution = contribution;
        try {
            mergeProperty(target, sourceKey, sourceValue, limits);
        } finally {
            state.contribution = previous;
        }
    }

    private boolean requiresCyclicTypeCompletion(Node inherited, Node source) {
        if (source.getType() != null || inherited.getType() == null
                || !inherited.getType().isReferenceOnly()) {
            return false;
        }
        String inheritedTypeBlueId = inherited.getType().getBlueId();
        return BlueIds.hasCyclicMemberSeparator(inheritedTypeBlueId);
    }

    private boolean containsCyclicSetReference(Node root) {
        Set<Node> visited = Collections.newSetFromMap(new IdentityHashMap<Node, Boolean>());
        List<Node> pending = new ArrayList<>();
        pending.add(root);
        while (!pending.isEmpty()) {
            Node node = pending.remove(pending.size() - 1);
            if (node == null || !visited.add(node)) {
                continue;
            }
            String blueId = node.getBlueId();
            if (BlueIds.hasCyclicMemberSeparator(blueId)) {
                return true;
            }
            pending.add(node.getType());
            pending.add(node.getItemType());
            pending.add(node.getKeyType());
            pending.add(node.getValueType());
            pending.add(node.getContracts());
            pending.add(node.getBlue());
            if (node.getItems() != null) {
                pending.addAll(node.getItems());
            }
            if (node.getProperties() != null) {
                pending.addAll(node.getProperties().values());
            }
        }
        return false;
    }

    private boolean isMaterializedCyclicSetMemberType(Node type) {
        String blueId = type.getBlueId();
        return BlueIds.hasCyclicMemberSeparator(blueId)
                && !type.isReferenceOnly();
    }

    private void mergeContracts(Node target, Node sourceContracts, Limits limits) {
        if (target.getContracts() == null) {
            target.contracts(resolve(sourceContracts, limits));
            return;
        }
        Node resolved = resolve(sourceContracts, limits);
        mergeInstanceObject(target.getContracts(), resolved, limits);
    }

    private void mergeContractsWithContribution(Node target,
                                                Node sourceContracts,
                                                Limits limits) {
        ResolutionState state = resolutionState;
        Contribution previous = state.contribution;
        state.contribution = previous == Contribution.MATERIALIZED_REFERENCE
                ? previous
                : Contribution.CONTRACT_ROOT;
        try {
            mergeContracts(target, sourceContracts, limits);
        } finally {
            state.contribution = previous;
        }
    }

    private boolean hasListControls(Node node) {
        List<Node> items = node.getItems();
        return items != null && items.stream()
                .anyMatch(item -> item.getPreviousBlueId() != null || item.getPosition() != null);
    }

    private void mergeObjectWithContribution(Node target,
                                             Node source,
                                             Limits limits,
                                             Contribution contribution) {
        ResolutionState state = resolutionState;
        Contribution previous = state.contribution;
        state.contribution = contribution;
        try {
            mergeObject(target, source, limits);
        } finally {
            state.contribution = previous;
        }
    }

    private void mergeWithContribution(Node target,
                                       Node source,
                                       Limits limits,
                                       Contribution contribution) {
        ResolutionState state = resolutionState;
        Contribution previous = state.contribution;
        state.contribution = contribution;
        try {
            merge(target, source, limits);
        } finally {
            state.contribution = previous;
        }
    }

    private Node resolveWithContribution(Node node, Limits limits, Contribution contribution) {
        ResolutionState state = resolutionState;
        Contribution previous = state.contribution;
        state.contribution = contribution;
        try {
            return resolve(node, limits);
        } finally {
            state.contribution = previous;
        }
    }

    private void observeCompletedPath(Node target, Node source, Limits limits) {
        ResolutionState state = resolutionState;
        if (state == null || state.contribution == Contribution.TYPE_METADATA) {
            return;
        }

        boolean hasValidation = target.getSchema() != null
                && mergingProcessor.hasCompletedValidation(target);
        if (!hasValidation && source.getBlueId() == null) {
            return;
        }
        boolean pureReference = source.isReferenceOnly();
        boolean needsReferenceContent = pureReference && requiresReferenceContent(target);
        boolean referenceExpansionAllowed = state.referenceExpansionAllowed;
        if (!hasValidation) {
            if (needsReferenceContent && referenceExpansionAllowed
                    && state.contribution != Contribution.TYPE_DECLARATION) {
                materializeReferenceAtCurrentPath(target, source.getBlueId(), limits, state);
            }
            return;
        }

        if (isRootInlineSchemaDeclaration(state, source)) {
            return;
        }

        String path = currentPath(state);
        ValidationCandidate candidate = candidate(state, path);
        candidate.node = target;
        candidate.presence = presenceGate(state, path);
        bindAncestorPresenceGates(state, candidate);
        candidate.observed = true;
        if (needsReferenceContent) {
            if (!referenceExpansionAllowed) {
                candidate.complete = false;
            } else if (state.contribution == Contribution.TYPE_DECLARATION) {
                candidate.pendingReferenceBlueId = source.getBlueId();
                candidate.pendingReferenceLimits = limits;
            } else {
                materializeReferenceAtCurrentPath(target, source.getBlueId(), limits, state);
                candidate.pendingReferenceBlueId = null;
                candidate.pendingReferenceLimits = null;
            }
        }
        if (state.path.isEmpty()) {
            candidate.presence.present = true;
        }
        ContributionFrame frame = state.contributionFrames.get(state.contributionFrames.size() - 1);
        if (frame.semanticContribution || frame.inheritedSemanticContribution) {
            candidate.presence.present = true;
        }
        if (isIncomplete(state, path)) {
            candidate.complete = false;
        }
    }

    private boolean requiresReferenceContent(Node target) {
        return target.getType() != null
                || mergingProcessor.requiresReferenceMaterialization(target)
                || hasConcretePayload(target);
    }

    private void materializeReference(Node target,
                                      String blueId,
                                      Limits limits,
                                      ResolutionState state) {
        CanonicalReference canonicalReference = canonicalReference(blueId, state);
        if (canonicalReference.canonical.containsCyclicSetReference()) {
            materializeCyclicSetReference(target, blueId, limits, state, canonicalReference);
            return;
        }

        Node materialized = materializedReference(blueId, limits, state, canonicalReference);
        Node mergeable = materialized.clone();
        if (mergeable.getBlueId() != null && !mergeable.isReferenceOnly()) {
            mergeable.blueId(null);
        }
        mergeObjectWithContribution(target, mergeable, limits, Contribution.MATERIALIZED_REFERENCE);
        copyMaterializedReferenceLabels(target, materialized);
        target.blueId(blueId);
    }

    private void copyMaterializedReferenceLabels(Node target, Node materialized) {
        if (target.getName() == null && materialized.getName() != null) {
            target.name(materialized.getName());
        }
        if (target.getDescription() == null && materialized.getDescription() != null) {
            target.description(materialized.getDescription());
        }
    }

    private void materializeCyclicSetReference(Node target,
                                               String blueId,
                                               Limits limits,
                                               ResolutionState state,
                                               CanonicalReference canonicalReference) {
        if (state.materializingReferences == null) {
            state.materializingReferences = new HashSet<>();
        }
        if (!state.materializingReferences.add(blueId)) {
            throw new IllegalStateException("Cyclic reference materialization at path "
                    + currentPath(state) + " for blueId: " + blueId);
        }
        try {
            Node materialized = resolveWithContribution(
                    canonicalReference.canonical.toNode(), limits, Contribution.INSTANCE);
            Node mergeable = materialized.clone();
            if (mergeable.getBlueId() != null && !mergeable.isReferenceOnly()) {
                mergeable.blueId(null);
            }
            mergeObjectWithContribution(
                    target, mergeable, limits, Contribution.MATERIALIZED_REFERENCE);
            copyMaterializedReferenceLabels(target, materialized);
            target.blueId(blueId);
        } finally {
            state.materializingReferences.remove(blueId);
        }
    }

    private void materializeReferenceAtCurrentPath(Node target,
                                                   String blueId,
                                                   Limits limits,
                                                   ResolutionState state) {
        String path = currentPath(state);
        try {
            materializeReference(target, blueId, limits, state);
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("Reference materialization failed at path " + path
                    + " for blueId " + blueId + ": " + ex.getMessage(), ex);
        }
    }

    private Node materializedReference(String blueId,
                                       Limits limits,
                                       ResolutionState state,
                                       CanonicalReference canonicalReference) {
        if (limits == Limits.NO_LIMITS && state.fullyResolvedReferences != null) {
            Node existing = state.fullyResolvedReferences.get(blueId);
            if (existing != null) {
                return existing.clone();
            }
        }

        FrozenNode cached = resolvedReferenceCache != null && limits == Limits.NO_LIMITS
                ? resolvedReferenceCache.getVerifiedResolved(blueId).orElse(null)
                : null;
        if (cached != null) {
            Node materialized = cached.toNode();
            rememberFullyResolved(state, blueId, materialized);
            return materialized.clone();
        }

        FrozenNode canonical = canonicalReference.canonical;
        if (state.materializingReferences == null) {
            state.materializingReferences = new HashSet<>();
        }
        if (!state.materializingReferences.add(blueId)) {
            throw new IllegalStateException("Cyclic reference materialization at path "
                    + currentPath(state) + " for blueId: " + blueId);
        }

        try {
            Node resolved = resolveWithContribution(
                    canonical.toNode(), limits, Contribution.INSTANCE);
            resolved.blueId(blueId);
            if (canonicalReference.directlyVerified
                    && resolvedReferenceCache != null && limits == Limits.NO_LIMITS) {
                resolvedReferenceCache.putVerifiedResolved(new VerifiedReferenceResolution(
                        blueId, canonical, resolvedReferenceCache.freezeResolved(resolved)));
            }
            if (limits == Limits.NO_LIMITS) {
                rememberFullyResolved(state, blueId, resolved);
            }
            return resolved.clone();
        } finally {
            state.materializingReferences.remove(blueId);
        }
    }

    private CanonicalReference canonicalReference(String blueId, ResolutionState state) {
        CanonicalReference existing = localCanonicalReference(state, blueId);
        if (existing != null) {
            return existing;
        }

        FrozenNode cached = resolvedReferenceCache != null
                ? resolvedReferenceCache.getVerifiedCanonical(blueId).orElse(null)
                : null;
        if (cached != null) {
            return rememberCanonical(state, blueId, cached, true);
        }
        if (state.failedProviderReferences != null && state.failedProviderReferences.contains(blueId)) {
            throw new IllegalArgumentException("Unable to materialize required reference at path "
                    + currentPath(state) + ": " + blueId);
        }

        try {
            FrozenNode canonical = canCacheDirectCanonical(blueId)
                    ? resolvedReferenceCache.getOrLoadVerifiedCanonical(
                    blueId,
                    () -> FrozenNode.fromNode(
                            requiredProviderContent(
                                    blueId, state)))
                    : FrozenNode.fromNode(
                    requiredProviderContent(blueId, state));
            return rememberCanonical(
                    state, blueId, canonical, true);
        } catch (RuntimeException ex) {
            if (state.failedProviderReferences == null) {
                state.failedProviderReferences = new HashSet<>();
            }
            state.failedProviderReferences.add(blueId);
            throw ex;
        }
    }

    private Node requiredProviderContent(String blueId, ResolutionState state) {
        List<Node> nodes = nodeProvider.fetchByBlueId(blueId);
        if (nodes == null || nodes.isEmpty()) {
            throw new IllegalArgumentException("No content found for required blueId " + blueId
                    + " at path " + currentPath(state) + ".");
        }
        return providerContent(nodes, blueId);
    }

    private Node providerContent(List<Node> nodes, String blueId) {
        if (nodes.size() == 1) {
            Node content = nodes.get(0).clone();
            if (content.isReferenceOnly()) {
                throw new IllegalArgumentException("Provider returned reference-only content for required blueId: "
                        + blueId);
            }
            if (content.getBlueId() != null) {
                content.blueId(null);
            }
            return content;
        }
        List<Node> content = new ArrayList<>(nodes.size());
        for (Node node : nodes) {
            Node item = node.clone();
            if (item.getBlueId() != null && !item.isReferenceOnly()) {
                item.blueId(null);
            }
            content.add(item);
        }
        return new Node().items(content);
    }

    private CanonicalReference localCanonicalReference(ResolutionState state, String blueId) {
        return state.canonicalReferences != null ? state.canonicalReferences.get(blueId) : null;
    }

    private CanonicalReference rememberCanonical(ResolutionState state,
                                                 String blueId,
                                                 FrozenNode canonical,
                                                 boolean directlyVerified) {
        if (state.canonicalReferences == null) {
            state.canonicalReferences = new LinkedHashMap<>();
        }
        CanonicalReference reference = new CanonicalReference(canonical, directlyVerified);
        state.canonicalReferences.put(blueId, reference);
        return reference;
    }

    private void rememberFullyResolved(ResolutionState state, String blueId, Node materialized) {
        if (state.fullyResolvedReferences == null) {
            state.fullyResolvedReferences = new LinkedHashMap<>();
        }
        state.fullyResolvedReferences.put(blueId, materialized.clone());
    }

    private boolean isDirectSemanticContribution(Node node, Contribution contribution) {
        if (node == null || contribution == Contribution.TYPE_METADATA) {
            return false;
        }
        if (contribution == Contribution.TYPE_ROOT) {
            return node.getValue() != null || node.getItems() != null;
        }
        return node.isReferenceOnly()
                || node.getValue() != null
                || node.getItems() != null
                || (node.getProperties() != null && !node.getProperties().isEmpty());
    }

    private boolean isInheritedReferenceContribution(Node target, Node source) {
        if (!target.isReferenceOnly()) {
            return false;
        }
        Node sourceType = source.getType();
        return sourceType == null || !target.getBlueId().equals(sourceType.getBlueId());
    }

    private boolean hasConcretePayload(Node node) {
        if (node == null) {
            return false;
        }
        if (node.getValue() != null || node.getItems() != null) {
            return true;
        }
        return node.getProperties() != null && !node.getProperties().isEmpty();
    }

    private boolean isInlineTypeDeclaration(Node node) {
        return node != null
                && node.getType() != null
                && node.getType().getBlueId() == null
                && !isBareCoreTypeAlias(node.getType());
    }

    private boolean isBareCoreTypeAlias(Node type) {
        if (type.isInlineValue()
                && type.getValue() instanceof String
                && CORE_TYPES.contains(type.getValue())) {
            return true;
        }
        return type.getName() != null
                && CORE_TYPES.contains(type.getName())
                && type.getDescription() == null
                && type.getType() == null
                && type.getItemType() == null
                && type.getKeyType() == null
                && type.getValueType() == null
                && type.getValue() == null
                && type.getItems() == null
                && (type.getProperties() == null || type.getProperties().isEmpty())
                && type.getContracts() == null
                && type.getSchema() == null
                && type.getMergePolicy() == null
                && type.getPreviousBlueId() == null
                && type.getPosition() == null
                && type.getBlue() == null;
    }

    private boolean isRootInlineSchemaDeclaration(ResolutionState state, Node source) {
        return state.path.isEmpty()
                && state.rootInlineTypeDeclaration
                && !hasConcretePayload(source);
    }

    private ValidationCandidate candidate(ResolutionState state, String path) {
        if (state.candidates == null) {
            state.candidates = new LinkedHashMap<>();
        }
        ValidationCandidate candidate = state.candidates.get(path);
        if (candidate == null) {
            candidate = new ValidationCandidate();
            state.candidates.put(path, candidate);
        }
        return candidate;
    }

    private PresenceGate presenceGate(ResolutionState state, String path) {
        if (state.presenceGates == null) {
            state.presenceGates = new LinkedHashMap<>();
        }
        PresenceGate gate = state.presenceGates.get(path);
        if (gate == null) {
            gate = new PresenceGate();
            state.presenceGates.put(path, gate);
        }
        return gate;
    }

    private void bindAncestorPresenceGates(ResolutionState state, ValidationCandidate candidate) {
        int candidateDepth = state.path.size();
        for (ContributionFrame frame : state.contributionFrames) {
            if (frame.pathDepth == 0 || frame.pathDepth >= candidateDepth) {
                continue;
            }
            PresenceGate gate = presenceGate(state, frame.path);
            if (frame.semanticContribution || frame.inheritedSemanticContribution) {
                gate.present = true;
            }
            if (!candidate.ancestorPresence.contains(gate)) {
                candidate.ancestorPresence.add(gate);
            }
        }
    }

    private boolean ancestorsPresent(ValidationCandidate candidate) {
        for (PresenceGate gate : candidate.ancestorPresence) {
            if (!gate.present) {
                return false;
            }
        }
        return true;
    }

    private void validateCompletedCandidates(ResolutionState state) {
        if (state.candidates == null) {
            return;
        }
        List<Map.Entry<String, ValidationCandidate>> candidates = new ArrayList<>(state.candidates.entrySet());
        for (int index = 0; index < candidates.size(); index++) {
            Map.Entry<String, ValidationCandidate> entry = candidates.get(index);
            ValidationCandidate candidate = entry.getValue();
            if (!candidate.complete) {
                // Limited resolution deliberately returns a partial view. Skipped candidates
                // are never certified as completed values and must not be semantically hashed.
                continue;
            }
            if (!ancestorsPresent(candidate)) {
                continue;
            }
            if (candidate.pendingReferenceBlueId != null) {
                enterPath(state, entry.getKey());
                int enteredLimitSegments = enterLimitPath(candidate.pendingReferenceLimits,
                        entry.getKey(), candidate.node);
                try {
                    materializeReferenceAtCurrentPath(candidate.node,
                            candidate.pendingReferenceBlueId,
                            candidate.pendingReferenceLimits,
                            state);
                } finally {
                    exitLimitPath(candidate.pendingReferenceLimits, enteredLimitSegments);
                    state.path.clear();
                }
                candidate.pendingReferenceBlueId = null;
                candidate.pendingReferenceLimits = null;
                if (state.candidates.size() > candidates.size()) {
                    candidates = new ArrayList<>(state.candidates.entrySet());
                }
            }
            mergingProcessor.validateCompleted(candidate.node,
                    candidate.presence.present,
                    entry.getKey());
        }
    }

    private void enterPath(ResolutionState state, String pointer) {
        state.path.clear();
        state.path.addAll(JsonPointer.split(pointer));
    }

    private int enterLimitPath(Limits limits, String pointer, Node node) {
        List<String> segments = JsonPointer.split(pointer);
        for (int index = 0; index < segments.size(); index++) {
            Node current = index == segments.size() - 1 ? node : null;
            limits.enterPathSegment(segments.get(index), current);
        }
        return segments.size();
    }

    private void exitLimitPath(Limits limits, int enteredSegments) {
        for (int index = 0; index < enteredSegments; index++) {
            limits.exitPathSegment();
        }
    }

    private void enterValidationPath(String segment) {
        enterValidationPath(segment, true);
    }

    private void enterValidationPath(String segment, boolean referenceExpansionAllowed) {
        ResolutionState state = resolutionState;
        if (state != null) {
            state.path.add(segment);
            state.referenceExpansionStack.add(state.referenceExpansionAllowed);
            state.referenceExpansionAllowed = state.referenceExpansionAllowed && referenceExpansionAllowed;
        }
    }

    private void exitValidationPath() {
        ResolutionState state = resolutionState;
        if (state != null && !state.path.isEmpty()) {
            state.path.remove(state.path.size() - 1);
            state.referenceExpansionAllowed = state.referenceExpansionStack
                    .remove(state.referenceExpansionStack.size() - 1);
        }
    }

    private void markIncomplete(String segment) {
        ResolutionState state = resolutionState;
        if (state == null) {
            return;
        }
        List<String> path = new ArrayList<>(state.path);
        path.add(segment);
        String prefix = JsonPointer.toPointer(path);
        if (state.incompletePaths == null) {
            state.incompletePaths = new HashSet<>();
        }
        state.incompletePaths.add(prefix);
        if (state.candidates != null) {
            state.candidates.forEach((candidatePath, candidate) -> {
                if (candidatePath.equals(prefix)
                        || candidatePath.startsWith(prefix + "/")
                        || prefix.startsWith(candidatePath + "/")) {
                    candidate.complete = false;
                }
            });
        }
    }

    private boolean isIncomplete(ResolutionState state, String path) {
        if (state.incompletePaths == null) {
            return false;
        }
        for (String incomplete : state.incompletePaths) {
            if (path.equals(incomplete)
                    || path.startsWith(incomplete + "/")
                    || incomplete.startsWith(path + "/")) {
                return true;
            }
        }
        return false;
    }

    private String currentPath(ResolutionState state) {
        return JsonPointer.toPointer(state.path);
    }

    private LabelPath currentLabelPath(ResolutionState state) {
        return new LabelPath(state.path);
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
        String typeBlueId = metadataType.getBlueId();
        if (isMaterializingType(typeBlueId)) {
            return new Node().blueId(typeBlueId);
        }
        FrozenNode cached = cachedResolvedReference(typeBlueId, limits);
        if (cached != null) {
            Node resolved = cached.toNode();
            if (resolved.getBlueId() == null) {
                resolved.blueId(typeBlueId);
            }
            return resolved;
        }
        TypeResolutionKey key = new TypeResolutionKey(typeBlueId, resolutionState.path.size());
        beginResolvingType(key);
        try {
            extendTypeReference(metadataType, typeBlueId);
            Node resolved = resolveWithContribution(metadataType, limits, Contribution.TYPE_METADATA);
            cacheResolvedReference(typeBlueId, resolved, limits);
            return resolved;
        } finally {
            finishResolvingType(key);
        }
    }

    @Override
    public Node resolve(Node node, Limits limits) {
        ResolutionState state = resolutionState;
        boolean outermost = state == null;
        boolean enteredOutermostLimit = false;
        if (outermost) {
            BlueIdReferenceValidator.validate(node);
            state = new ResolutionState();
            state.rootInlineTypeDeclaration = isInlineTypeDeclaration(node);
            state.rootSource = node;
            resolutionState = state;
        }
        try {
            if (outermost) {
                limits.enterPathSegment("", node);
                enteredOutermostLimit = true;
            }
            Node result = resolveInternal(node, limits);
            if (outermost) {
                validateCompletedCandidates(state);
            }
            return result;
        } finally {
            if (outermost) {
                if (enteredOutermostLimit) {
                    limits.exitPathSegment();
                }
                resolutionState = null;
            }
        }
    }

    private Node resolveInternal(Node node, Limits limits) {
        LabelProvenanceScope labelScope = pushLabelProvenanceScope(node, limits, false);
        try {
            Node resultNode = new Node();
            merge(resultNode, node, limits);
            resultNode.name(node.getName());
            resultNode.description(node.getDescription());
            resultNode.blueId(node.getBlueId());
            return resultNode;
        } finally {
            popLabelProvenanceScope(labelScope);
        }
    }

    /**
     * Binds the canonical and resolved roots produced by one resolver invocation.
     */
    public static final class SnapshotResolution {
        private final FrozenNode canonicalRoot;
        private final FrozenNode resolvedRoot;
        private final VerifiedReferenceResolution verifiedReferenceResolution;

        private SnapshotResolution(FrozenNode canonicalRoot,
                                   FrozenNode resolvedRoot,
                                   VerifiedReferenceResolution verifiedReferenceResolution) {
            this.canonicalRoot = canonicalRoot;
            this.resolvedRoot = resolvedRoot;
            this.verifiedReferenceResolution = verifiedReferenceResolution;
        }

        /**
         * Returns the strict canonical root supplied to or derived by the resolver.
         *
         * @return immutable canonical root
         */
        public FrozenNode canonicalRoot() {
            return canonicalRoot;
        }

        /**
         * Returns the completed resolved root produced by the resolver.
         *
         * @return immutable resolved root
         */
        public FrozenNode resolvedRoot() {
            return resolvedRoot;
        }

        /**
         * Returns proof of an eligible unlimited verified reference resolution.
         *
         * @return verification proof, or {@code null} when the resolution was not eligible
         */
        public VerifiedReferenceResolution verifiedReferenceResolution() {
            return verifiedReferenceResolution;
        }
    }

    /**
     * Opaque proof that one unlimited resolver invocation completed for the
     * exact strict canonical root. Only {@link Merger} can construct it.
     */
    public static final class VerifiedReferenceResolution {
        private final String requestedBlueId;
        private final FrozenNode canonicalRoot;
        private final FrozenNode resolvedRoot;

        private VerifiedReferenceResolution(String requestedBlueId,
                                            FrozenNode canonicalRoot,
                                            FrozenNode resolvedRoot) {
            this.requestedBlueId = requestedBlueId;
            this.canonicalRoot = canonicalRoot;
            this.resolvedRoot = resolvedRoot;
        }

        /**
         * Returns the BlueId requested for the verified resolution.
         *
         * @return requested BlueId
         */
        public String requestedBlueId() {
            return requestedBlueId;
        }

        /**
         * Returns the exact strict canonical root covered by this proof.
         *
         * @return immutable canonical root
         */
        public FrozenNode canonicalRoot() {
            return canonicalRoot;
        }

        /**
         * Returns the completed resolved root covered by this proof.
         *
         * @return immutable resolved root
         */
        public FrozenNode resolvedRoot() {
            return resolvedRoot;
        }
    }

    private enum Contribution {
        INSTANCE,
        TYPE_ROOT,
        TYPE_DECLARATION,
        TYPE_METADATA,
        MATERIALIZED_REFERENCE,
        CONTRACT_ROOT,
        CONTRACT_CONTENT
    }

    private enum LabelMergeMode {
        AUTHORED_OVERLAY,
        REFERENCE_EXPANSION,
        NONE
    }

    private static final class LabelPath {
        private final List<String> segments;

        private LabelPath(List<String> segments) {
            this.segments = Collections.unmodifiableList(new ArrayList<>(segments));
        }

        private static LabelPath root() {
            return new LabelPath(Collections.<String>emptyList());
        }

        private LabelPath child(String segment) {
            List<String> childSegments = new ArrayList<>(segments);
            childSegments.add(segment);
            return new LabelPath(childSegments);
        }

        private boolean isRoot() {
            return segments.isEmpty();
        }

        private boolean isAtOrBelow(LabelPath ancestor) {
            if (segments.size() < ancestor.segments.size()) {
                return false;
            }
            for (int index = 0; index < ancestor.segments.size(); index++) {
                if (!Objects.equals(segments.get(index), ancestor.segments.get(index))) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public boolean equals(Object other) {
            return this == other
                    || other instanceof LabelPath
                    && segments.equals(((LabelPath) other).segments);
        }

        @Override
        public int hashCode() {
            return segments.hashCode();
        }
    }

    private static final class LabelProvenanceScope {
        private final LabelPath rootPath;
        private final Set<LabelPath> labelPaths;
        private final Set<LabelPath> declarationOnlyPaths = new HashSet<>();
        private final Set<LabelPath> fixedPaths = new HashSet<>();

        private LabelProvenanceScope(LabelPath rootPath,
                                     Set<LabelPath> labelPaths) {
            this.rootPath = rootPath;
            this.labelPaths = labelPaths;
        }
    }

    private enum LabelScanTaskKind {
        TYPE,
        SOURCE,
        CHILDREN,
        EXIT_TYPE
    }

    private static final class LabelScanTask {
        private final LabelScanTaskKind kind;
        private final Node node;
        private final LabelPath path;
        private final String typeBlueId;

        private LabelScanTask(LabelScanTaskKind kind,
                              Node node,
                              LabelPath path,
                              String typeBlueId) {
            this.kind = kind;
            this.node = node;
            this.path = path;
            this.typeBlueId = typeBlueId;
        }

        private static LabelScanTask type(Node node, LabelPath path) {
            return new LabelScanTask(LabelScanTaskKind.TYPE, node, path, null);
        }

        private static LabelScanTask source(Node node, LabelPath path) {
            return new LabelScanTask(LabelScanTaskKind.SOURCE, node, path, null);
        }

        private static LabelScanTask children(Node node, LabelPath path) {
            return new LabelScanTask(LabelScanTaskKind.CHILDREN, node, path, null);
        }

        private static LabelScanTask exitType(String typeBlueId, Node node) {
            return new LabelScanTask(LabelScanTaskKind.EXIT_TYPE, node, null, typeBlueId);
        }
    }

    private static final class LabelScanState {
        private final LabelProvenanceScope scope;
        private final Set<LabelPath> relevantLabelPaths;
        private final Set<String> activeTypeBlueIds = new HashSet<>();
        private final Set<Node> activeInlineTypes = Collections.newSetFromMap(new IdentityHashMap<>());
        private final Map<LabelPath, Integer> listSizes = new HashMap<>();
        private final Map<LabelPath, Node> effectiveItemTypes = new HashMap<>();
        private final Map<LabelPath, Map<Integer, Node>> effectiveListItems = new HashMap<>();

        private LabelScanState(LabelProvenanceScope scope,
                               Set<LabelPath> relevantLabelPaths) {
            this.scope = scope;
            this.relevantLabelPaths = relevantLabelPaths;
        }

        private boolean enterType(String typeBlueId, Node typeNode) {
            return typeBlueId != null
                    ? activeTypeBlueIds.add(typeBlueId)
                    : activeInlineTypes.add(typeNode);
        }

        private void exitType(String typeBlueId, Node typeNode) {
            if (typeBlueId != null) {
                activeTypeBlueIds.remove(typeBlueId);
            } else {
                activeInlineTypes.remove(typeNode);
            }
        }
    }

    private static final class PositionedLabelSource {
        private final int position;
        private final Node node;
        private final boolean replacement;

        private PositionedLabelSource(int position, Node node) {
            this(position, node, false);
        }

        private PositionedLabelSource(int position, Node node, boolean replacement) {
            this.position = position;
            this.node = node;
            this.replacement = replacement;
        }
    }

    private static final class FixedContentTask {
        private final Node node;
        private final boolean typeRoot;

        private FixedContentTask(Node node, boolean typeRoot) {
            this.node = node;
            this.typeRoot = typeRoot;
        }
    }

    private static final class ResolutionState {
        private final List<String> path = new ArrayList<>();
        private final List<Boolean> referenceExpansionStack = new ArrayList<>();
        private final List<ContributionFrame> contributionFrames = new ArrayList<>();
        private final List<LabelProvenanceScope> labelProvenanceScopes = new ArrayList<>();
        private boolean referenceExpansionAllowed = true;
        private Contribution contribution = Contribution.INSTANCE;
        private Map<String, ValidationCandidate> candidates;
        private Map<String, PresenceGate> presenceGates;
        private Set<String> incompletePaths;
        private Map<String, CanonicalReference> canonicalReferences;
        private Map<String, Node> fullyResolvedReferences;
        private Map<Node, Set<String>> appliedTypeContributions;
        private Set<String> materializingReferences;
        private Set<String> failedProviderReferences;
        private Set<TypeResolutionKey> resolvingTypes;
        private Set<String> materializingTypeBlueIds;
        private boolean rootInlineTypeDeclaration;
        private Node rootSource;
        private boolean rootSourceSchemaChecked;
        private boolean rootSourceContainsSchema;
        private boolean schemaRequiresTypeSourceProvenance;
    }

    private static final class CanonicalReference {
        private final FrozenNode canonical;
        private final boolean directlyVerified;

        private CanonicalReference(FrozenNode canonical, boolean directlyVerified) {
            this.canonical = canonical;
            this.directlyVerified = directlyVerified;
        }
    }

    private static final class ValidationCandidate {
        private Node node;
        private boolean observed;
        private PresenceGate presence;
        private final List<PresenceGate> ancestorPresence = new ArrayList<>();
        private boolean complete = true;
        private String pendingReferenceBlueId;
        private Limits pendingReferenceLimits;
    }

    private static final class ContributionFrame {
        private final String path;
        private final int pathDepth;
        private boolean semanticContribution;
        private final boolean inheritedSemanticContribution;
        private final boolean propagatesToParent;

        private ContributionFrame(String path,
                                  int pathDepth,
                                  boolean semanticContribution,
                                  boolean inheritedSemanticContribution,
                                  boolean propagatesToParent) {
            this.path = path;
            this.pathDepth = pathDepth;
            this.semanticContribution = semanticContribution;
            this.inheritedSemanticContribution = inheritedSemanticContribution;
            this.propagatesToParent = propagatesToParent;
        }
    }

    private static final class PresenceGate {
        private boolean present;
    }

    private static final class TypeResolutionKey {
        private final String blueId;
        private final int pathDepth;

        private TypeResolutionKey(String blueId, int pathDepth) {
            this.blueId = blueId;
            this.pathDepth = pathDepth;
        }

        @Override
        public boolean equals(Object object) {
            if (this == object) {
                return true;
            }
            if (!(object instanceof TypeResolutionKey)) {
                return false;
            }
            TypeResolutionKey other = (TypeResolutionKey) object;
            return blueId.equals(other.blueId) && pathDepth == other.pathDepth;
        }

        @Override
        public int hashCode() {
            return 31 * blueId.hashCode() + pathDepth;
        }
    }
}
