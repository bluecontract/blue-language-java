package blue.language.merge;

import blue.language.model.wire.BlueLanguageConstants;

import blue.language.provider.NodeProvider;
import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;
import blue.language.merge.ResolvedReferenceCache;
import blue.language.resolve.ReferenceCacheAdmissionPolicy;
import blue.language.registry.NodeProviderWrapper;
import blue.language.provider.Types;
import blue.language.resolve.ResolutionLimits;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.identity.BlueIdReferenceValidator;
import blue.language.identity.BlueIds;

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

import static blue.language.codec.jackson.UncheckedObjectMapper.JSON_MAPPER;

import static blue.language.model.wire.BlueLanguageConstants.CORE_TYPE_BLUE_IDS;

/**
 * Concrete Blue Language merge engine.
 *
 * <p>Custom merge behavior should use {@link MergingProcessor}, which is the
 * supported extension point.</p>
 */
final class ResolutionEngine implements NodeResolver {

    private final MergingProcessor mergingProcessor;
    private final NodeProvider nodeProvider;
    private final ResolvedReferenceCache resolvedReferenceCache;
    private final ReferenceCacheAdmissionPolicy referenceCacheAdmissionPolicy;
    private final ResolutionSession resolutionSession;
    private final ListOverlayMerger listOverlayMerger;
    private final LabelProvenanceTracker labelProvenanceTracker;
    private final ActiveTypeStack activeTypeStack;
    private final ReferenceResolver referenceResolver;
    private final CompletedValueValidator completedValueValidator;
    private final ResolutionSnapshotFactory snapshotFactory;

    /**
     * Creates a merge engine without retained resolved-reference caching.
     *
     * @param mergingProcessor processor that applies language merge semantics
     * @param nodeProvider provider used to resolve referenced nodes
     */
    ResolutionEngine(MergingProcessor mergingProcessor, NodeProvider nodeProvider) {
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
    ResolutionEngine(MergingProcessor mergingProcessor, NodeProvider nodeProvider, ResolvedReferenceCache resolvedReferenceCache) {
        this(mergingProcessor, NodeProviderWrapper.wrap(nodeProvider),
                resolvedReferenceCache,
                ReferenceCacheAdmissionPolicy.ALLOW_ALL,
                null);
    }

    /**
     * Creates a merge engine with an explicit host cache-admission policy.
     *
     * @param mergingProcessor Language merge strategy
     * @param nodeProvider exact content provider
     * @param resolvedReferenceCache optional verified reference cache
     * @param referenceCacheAdmissionPolicy host cache-safety policy
     */
    ResolutionEngine(
            MergingProcessor mergingProcessor,
            NodeProvider nodeProvider,
            ResolvedReferenceCache resolvedReferenceCache,
            ReferenceCacheAdmissionPolicy referenceCacheAdmissionPolicy) {
        this(mergingProcessor,
                NodeProviderWrapper.wrap(nodeProvider),
                resolvedReferenceCache,
                referenceCacheAdmissionPolicy,
                null);
    }

    private ResolutionEngine(MergingProcessor mergingProcessor,
                   NodeProvider wrappedNodeProvider,
                   ResolvedReferenceCache resolvedReferenceCache,
                   ReferenceCacheAdmissionPolicy referenceCacheAdmissionPolicy,
                   ResolutionSession resolutionSession) {
        this.mergingProcessor = mergingProcessor;
        this.nodeProvider = wrappedNodeProvider;
        this.resolvedReferenceCache = resolvedReferenceCache;
        this.referenceCacheAdmissionPolicy = Objects.requireNonNull(
                referenceCacheAdmissionPolicy,
                "referenceCacheAdmissionPolicy");
        this.resolutionSession = resolutionSession;
        this.listOverlayMerger = new ListOverlayMerger(this, wrappedNodeProvider);
        this.referenceResolver = new ReferenceResolver(
                this,
                mergingProcessor,
                wrappedNodeProvider,
                resolvedReferenceCache,
                referenceCacheAdmissionPolicy);
        this.completedValueValidator = new CompletedValueValidator(
                this, mergingProcessor, referenceResolver);
        this.snapshotFactory = new ResolutionSnapshotFactory(
                this, resolvedReferenceCache);
        this.labelProvenanceTracker = new LabelProvenanceTracker(
                this, wrappedNodeProvider, listOverlayMerger);
        this.activeTypeStack = new ActiveTypeStack();
    }

    private ResolutionEngine invocationMerger() {
        return new ResolutionEngine(mergingProcessor, nodeProvider,
                resolvedReferenceCache,
                referenceCacheAdmissionPolicy,
                new ResolutionSession());
    }

    private boolean requiresFreshInvocation() {
        return resolutionSession == null
                || !resolutionSession.acceptsCurrentThread();
    }

    ResolutionState activeResolutionState() {
        return resolutionSession != null ? resolutionSession.state() : null;
    }

    blue.language.merge.SnapshotResolution resolveSnapshot(
            Node preprocessedSource, ResolutionLimits limits) {
        if (requiresFreshInvocation()) {
            return invocationMerger().resolveSnapshot(
                    preprocessedSource, limits);
        }
        return snapshotFactory.resolve(preprocessedSource, limits);
    }

    blue.language.merge.SnapshotResolution resolveSnapshot(
            FrozenNode canonicalRoot, ResolutionLimits limits) {
        if (requiresFreshInvocation()) {
            return invocationMerger().resolveSnapshot(canonicalRoot, limits);
        }
        return snapshotFactory.resolve(canonicalRoot, limits);
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
    public void merge(Node target, Node source, ResolutionLimits limits) {
        if (requiresFreshInvocation()) {
            invocationMerger().merge(target, source, limits);
            return;
        }
        ResolutionState state = activeResolutionState();
        boolean outermost = state == null;
        LabelProvenanceTracker.LabelProvenanceScope outermostLabelScope = null;
        boolean enteredOutermostLimit = false;
        if (outermost) {
            state = new ResolutionState();
            state.rootInlineTypeDeclaration = completedValueValidator
                    .isInlineTypeDeclaration(source);
            state.rootSource = source;
            resolutionSession.begin(state);
        }
        try {
            if (outermost) {
                limits.enterPathSegment("", source);
                enteredOutermostLimit = true;
                outermostLabelScope = labelProvenanceTracker
                        .pushLabelProvenanceScope(source, limits, true);
                labelProvenanceTracker.seedMaterializedTargetLabelProvenance(
                        target, outermostLabelScope);
            }
            LabelProvenanceTracker.MergeMode labelMergeMode =
                    labelProvenanceTracker.mergeMode(state.contribution);
            boolean inheritedDeclarationOnly = labelMergeMode
                    == LabelProvenanceTracker.MergeMode.AUTHORED_OVERLAY
                    && labelProvenanceTracker.isDeclarationOnlyForLabels(target);
            if (labelMergeMode == LabelProvenanceTracker.MergeMode.AUTHORED_OVERLAY) {
                labelProvenanceTracker.validateExplicitInstanceLabels(
                        target, source, inheritedDeclarationOnly);
            }
            mergeInternal(target, source, limits);
            if (labelMergeMode == LabelProvenanceTracker.MergeMode.AUTHORED_OVERLAY) {
                labelProvenanceTracker.applyExplicitInstanceLabels(
                        target, source, inheritedDeclarationOnly);
            } else if (labelMergeMode
                    == LabelProvenanceTracker.MergeMode.REFERENCE_EXPANSION) {
                labelProvenanceTracker.copyMaterializedReferenceLabels(target, source);
            }
            if (outermost) {
                completedValueValidator.validateCompletedCandidates(state);
            }
        } finally {
            if (outermost) {
                labelProvenanceTracker.popLabelProvenanceScope(outermostLabelScope);
                if (enteredOutermostLimit) {
                    limits.exitPathSegment();
                }
                resolutionSession.complete(state);
            }
        }
    }

    private void mergeInternal(Node target, Node source, ResolutionLimits limits) {
        if (source.getBlue() != null) {
            throw new IllegalArgumentException("Document contains \"blue\" attribute. Preprocess document before merging.");
        }

        ActiveTypeStack.Token deferredTypeResolution = null;
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
                && activeResolutionState().referenceExpansionAllowed) {
            Node typeNode = source.getType();
            String typeBlueId = typeNode.getBlueId();
            LabelProvenanceTracker.LabelProvenanceScope labelScope =
                    labelProvenanceTracker.currentLabelProvenanceScope();
            LabelPath currentLabelPath =
                    labelProvenanceTracker.currentLabelPath();
            if (labelScope != null
                    && activeResolutionState().contribution != Contribution.TYPE_ROOT
                    && activeResolutionState().contribution != Contribution.TYPE_METADATA
                    && activeResolutionState().contribution != Contribution.TYPE_DECLARATION
                    && labelProvenanceTracker.hasLabelPathAtOrBelow(
                    labelScope.labelPaths, currentLabelPath)) {
                labelProvenanceTracker.recordTypeDeclarationLabelPaths(
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
                    activeResolutionState().contribution == Contribution.TYPE_METADATA
                            ? Contribution.TYPE_METADATA
                            : Contribution.TYPE_ROOT;
            boolean materializedCyclicType = referenceResolver
                    .isMaterializedCyclicSetMemberType(typeNode);
            FrozenNode cachedResolvedType = referenceResolver
                    .cachedResolvedType(typeBlueId, limits);
            boolean trackedType = typeBlueId != null;
            ActiveTypeStack.Token typeResolutionKey = trackedType
                    ? activeTypeStack.token(
                    typeBlueId, activeResolutionState().path.size())
                    : null;
            if (trackedType && isResolvingType(typeResolutionKey)) {
                throw new IllegalStateException("Cyclic type hierarchy at path "
                        + currentPath(activeResolutionState()) + " for blueId: " + typeBlueId);
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
                            referenceResolver.expandTypeReference(typeNode, typeBlueId);
                        }

                        Node resolvedType = resolveWithContribution(
                                typeNode, limits, typeExpansionContribution);
                        referenceResolver.cacheResolvedReference(
                                typeBlueId, resolvedType, limits);
                        source.type(detachedResolvedTypeMetadata(resolvedType));
                        if (!typeContributionApplied) {
                            // Align cold and warm resolution only when the completed type is safe to reuse.
                            if (referenceResolver.cachedResolvedType(
                                    typeBlueId, limits) != null) {
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
        if (sourceTypeBlueId == null || activeResolutionState().appliedTypeContributions == null) {
            return false;
        }
        Set<String> applied = activeResolutionState().appliedTypeContributions.get(target);
        return applied != null && applied.contains(sourceTypeBlueId);
    }

    private void recordAppliedDeclaredTypeContribution(Node target, String sourceTypeBlueId) {
        if (sourceTypeBlueId == null) {
            return;
        }
        if (activeResolutionState().appliedTypeContributions == null) {
            activeResolutionState().appliedTypeContributions = new IdentityHashMap<>();
        }
        Set<String> applied = activeResolutionState().appliedTypeContributions.get(target);
        if (applied == null) {
            applied = new HashSet<>();
            activeResolutionState().appliedTypeContributions.put(target, applied);
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

    Node canonicalTypeForLabelProvenance(Node typeNode) {
        return referenceResolver.canonicalTypeForLabelProvenance(typeNode);
    }

    private boolean isResolvingType(ActiveTypeStack.Token key) {
        return activeTypeStack.isResolving(key);
    }

    private boolean isMaterializingType(String blueId) {
        return activeTypeStack.isMaterializing(blueId);
    }

    private void beginResolvingType(ActiveTypeStack.Token key) {
        activeTypeStack.begin(key);
    }

    private void finishResolvingType(ActiveTypeStack.Token key) {
        activeTypeStack.finish(key);
    }

    private void mergeObject(Node target, Node source, ResolutionLimits limits) {
        referenceResolver.materializeReferenceBackedSchema(source);
        referenceResolver.materializeReferenceBackedContracts(source);
        ResolutionState state = activeResolutionState();
        String path = currentPath(state);
        CompletedValueValidator.ContributionFrame frame =
                completedValueValidator.beginContribution(
                        state, target, source, path);
        try {

            resolveTypeMetadata(source, limits);
            mergingProcessor.process(target, source, nodeProvider, this);

            List<Node> children = source.getItems();
            if (children != null) {
                mergeChildren(target, children, limits);
            }

            if (source.getContracts() != null && limits.shouldMergePathSegment(BlueLanguageConstants.OBJECT_CONTRACTS, source.getContracts())) {
                boolean referenceExpansionAllowed = limits == ResolutionLimits.NO_LIMITS
                        || limits.shouldExpandPathSegment(
                                BlueLanguageConstants.OBJECT_CONTRACTS, source.getContracts());
                limits.enterPathSegment(BlueLanguageConstants.OBJECT_CONTRACTS, source.getContracts());
                enterValidationPath(BlueLanguageConstants.OBJECT_CONTRACTS, referenceExpansionAllowed);
                try {
                    mergeContractsWithContribution(target, source.getContracts(), limits);
                } finally {
                    exitValidationPath();
                    limits.exitPathSegment();
                }
            } else if (source.getContracts() != null) {
                markIncomplete(BlueLanguageConstants.OBJECT_CONTRACTS);
            }

            Map<String, Node> properties = source.getProperties();
            if (properties != null) {
                properties.forEach((key, value) -> {
                    if (limits.shouldMergePathSegment(key, value)) {
                        boolean referenceExpansionAllowed = limits == ResolutionLimits.NO_LIMITS
                                || limits.shouldExpandPathSegment(key, value);
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
                completedValueValidator.observeCompletedPath(
                        target, source, limits);
            }
        } finally {
            completedValueValidator.completeContribution(state, frame);
        }
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

    private void mergeChildren(Node target, List<Node> sourceChildren, ResolutionLimits limits) {
        listOverlayMerger.mergeChildren(target, sourceChildren, limits);
    }

    private boolean shouldTrackValidationPath(Node target, String key, Node source) {
        if (!isUnconstrainedScalar(source)) {
            return true;
        }
        Node inherited = target.getProperties() != null
                ? target.getProperties().get(key) : null;
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

    private Node applyItemType(Node child, Node itemType) {
        return listOverlayMerger.applyItemType(child, itemType);
    }

    private Node itemTypeReference(Node itemType) {
        return listOverlayMerger.itemTypeReference(itemType);
    }

    private Node withoutPosition(Node node) {
        return listOverlayMerger.withoutPosition(node);
    }

    private boolean startsWithPrevious(List<Node> children) {
        return listOverlayMerger.startsWithPrevious(children);
    }

    private boolean hasReplacement(Node node) {
        return listOverlayMerger.hasReplacement(node);
    }

    private void mergeProperty(Node target, String sourceKey, Node sourceValue, ResolutionLimits limits) {
        if (target.getProperties() == null)
            target.properties(new LinkedHashMap<>());
        Node targetValue = target.getProperties().get(sourceKey);
        if (targetValue == null) {
            Node node = resolve(sourceValue, limits);
            target.getProperties().put(sourceKey, node);
        } else {
            if (referenceResolver.requiresCyclicTypeCompletion(
                    targetValue, sourceValue)) {
                Node typedSource = sourceValue.clone()
                        .type(new Node().blueId(targetValue.getType().getBlueId()));
                merge(targetValue, typedSource, limits);
            } else if (hasListControls(sourceValue)) {
                merge(targetValue, sourceValue, limits);
            } else if (referenceResolver.containsCyclicSetReference(sourceValue)) {
                merge(targetValue, sourceValue, limits);
            } else {
                Node node = resolve(sourceValue, limits);
                mergeInstanceObject(targetValue, node, limits);
            }
        }
    }

    void mergeInstanceObject(Node target, Node source, ResolutionLimits limits) {
        LabelProvenanceTracker.MergeMode labelMergeMode =
                labelProvenanceTracker.mergeMode(
                        activeResolutionState().contribution);
        boolean inheritedDeclarationOnly = labelMergeMode
                == LabelProvenanceTracker.MergeMode.AUTHORED_OVERLAY
                && labelProvenanceTracker.isDeclarationOnlyForLabels(target);
        if (labelMergeMode == LabelProvenanceTracker.MergeMode.AUTHORED_OVERLAY) {
            labelProvenanceTracker.validateExplicitInstanceLabels(
                    target, source, inheritedDeclarationOnly);
        }
        mergeObject(target, source, limits);
        if (labelMergeMode == LabelProvenanceTracker.MergeMode.AUTHORED_OVERLAY) {
            labelProvenanceTracker.applyExplicitInstanceLabels(
                    target, source, inheritedDeclarationOnly);
        } else if (labelMergeMode
                == LabelProvenanceTracker.MergeMode.REFERENCE_EXPANSION) {
            labelProvenanceTracker.copyMaterializedReferenceLabels(target, source);
        }
    }

    private void mergePropertyWithContribution(Node target,
                                               String sourceKey,
                                               Node sourceValue,
                                               ResolutionLimits limits,
                                               Contribution contribution) {
        ResolutionState state = activeResolutionState();
        Contribution previous = state.contribution;
        state.contribution = contribution;
        try {
            mergeProperty(target, sourceKey, sourceValue, limits);
        } finally {
            state.contribution = previous;
        }
    }

    private void mergeContracts(Node target, Node sourceContracts, ResolutionLimits limits) {
        if (target.getContracts() == null) {
            target.contracts(resolve(sourceContracts, limits));
            return;
        }
        Node resolved = resolve(sourceContracts, limits);
        mergeInstanceObject(target.getContracts(), resolved, limits);
    }

    private void mergeContractsWithContribution(Node target,
                                                Node sourceContracts,
                                                ResolutionLimits limits) {
        ResolutionState state = activeResolutionState();
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
        return listOverlayMerger.hasListControls(node);
    }

    void mergeObjectWithContribution(Node target,
                                             Node source,
                                             ResolutionLimits limits,
                                             Contribution contribution) {
        ResolutionState state = activeResolutionState();
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
                                       ResolutionLimits limits,
                                       Contribution contribution) {
        ResolutionState state = activeResolutionState();
        Contribution previous = state.contribution;
        state.contribution = contribution;
        try {
            merge(target, source, limits);
        } finally {
            state.contribution = previous;
        }
    }

    Node resolveWithContribution(Node node, ResolutionLimits limits, Contribution contribution) {
        ResolutionState state = activeResolutionState();
        Contribution previous = state.contribution;
        state.contribution = contribution;
        try {
            return resolve(node, limits);
        } finally {
            state.contribution = previous;
        }
    }

    void copyMaterializedReferenceLabels(Node target, Node materialized) {
        labelProvenanceTracker.copyMaterializedReferenceLabels(
                target, materialized);
    }

    void enterValidationPath(String segment) {
        completedValueValidator.enterValidationPath(segment);
    }

    void enterValidationPath(
            String segment, boolean referenceExpansionAllowed) {
        completedValueValidator.enterValidationPath(
                segment, referenceExpansionAllowed);
    }

    void exitValidationPath() {
        completedValueValidator.exitValidationPath();
    }

    void markIncomplete(String segment) {
        completedValueValidator.markIncomplete(segment);
    }

    String currentPath(ResolutionState state) {
        return completedValueValidator.currentPath(state);
    }

    private void resolveTypeMetadata(Node source, ResolutionLimits limits) {
        source.itemType(resolveTypeMetadataNode(source.getItemType(), limits));
        source.keyType(resolveTypeMetadataNode(source.getKeyType(), limits));
        source.valueType(resolveTypeMetadataNode(source.getValueType(), limits));
    }

    private Node resolveTypeMetadataNode(Node metadataType, ResolutionLimits limits) {
        if (metadataType == null || metadataType.getBlueId() == null) {
            return metadataType;
        }
        String typeBlueId = metadataType.getBlueId();
        if (isMaterializingType(typeBlueId)) {
            return new Node().blueId(typeBlueId);
        }
        FrozenNode cached = referenceResolver.cachedResolvedReference(
                typeBlueId, limits);
        if (cached != null) {
            Node resolved = cached.toNode();
            if (resolved.getBlueId() == null) {
                resolved.blueId(typeBlueId);
            }
            return resolved;
        }
        ActiveTypeStack.Token key = activeTypeStack.token(
                typeBlueId, activeResolutionState().path.size());
        beginResolvingType(key);
        try {
            referenceResolver.expandTypeReference(metadataType, typeBlueId);
            Node resolved = resolveWithContribution(metadataType, limits, Contribution.TYPE_METADATA);
            referenceResolver.cacheResolvedReference(
                    typeBlueId, resolved, limits);
            return resolved;
        } finally {
            finishResolvingType(key);
        }
    }

    @Override
    public Node resolve(Node node, ResolutionLimits limits) {
        if (requiresFreshInvocation()) {
            return invocationMerger().resolve(node, limits);
        }
        ResolutionState state = activeResolutionState();
        boolean outermost = state == null;
        boolean enteredOutermostLimit = false;
        if (outermost) {
            BlueIdReferenceValidator.validate(node);
            state = new ResolutionState();
            state.rootInlineTypeDeclaration = completedValueValidator
                    .isInlineTypeDeclaration(node);
            state.rootSource = node;
            resolutionSession.begin(state);
        }
        try {
            if (outermost) {
                limits.enterPathSegment("", node);
                enteredOutermostLimit = true;
            }
            Node result = resolveInternal(node, limits);
            if (outermost) {
                completedValueValidator.validateCompletedCandidates(state);
            }
            return result;
        } finally {
            if (outermost) {
                if (enteredOutermostLimit) {
                    limits.exitPathSegment();
                }
                resolutionSession.complete(state);
            }
        }
    }

    private Node resolveInternal(Node node, ResolutionLimits limits) {
        LabelProvenanceTracker.LabelProvenanceScope labelScope =
                labelProvenanceTracker.pushLabelProvenanceScope(
                        node, limits, false);
        try {
            Node resultNode = new Node();
            merge(resultNode, node, limits);
            resultNode.name(node.getName());
            resultNode.description(node.getDescription());
            resultNode.blueId(node.getBlueId());
            return resultNode;
        } finally {
            labelProvenanceTracker.popLabelProvenanceScope(labelScope);
        }
    }

    enum Contribution {
        INSTANCE,
        TYPE_ROOT,
        TYPE_DECLARATION,
        TYPE_METADATA,
        MATERIALIZED_REFERENCE,
        CONTRACT_ROOT,
        CONTRACT_CONTENT
    }

    static final class ResolutionState {
        final List<String> path = new ArrayList<>();
        boolean referenceExpansionAllowed = true;
        Contribution contribution = Contribution.INSTANCE;
        private Map<Node, Set<String>> appliedTypeContributions;
        boolean rootInlineTypeDeclaration;
        Node rootSource;
    }

}
