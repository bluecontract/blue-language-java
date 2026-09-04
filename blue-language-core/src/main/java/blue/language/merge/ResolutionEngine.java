package blue.language.merge;

import blue.language.model.wire.BlueLanguageConstants;

import blue.language.provider.NodeProvider;
import blue.language.model.Node;
import blue.language.model.Nodes;
import blue.language.snapshot.FrozenNode;
import blue.language.merge.ResolvedReferenceCache;
import blue.language.resolve.ReferenceCacheAdmissionPolicy;
import blue.language.registry.NodeProviderWrapper;
import blue.language.provider.Types;
import blue.language.resolve.ResolutionLimits;
import blue.language.identity.BlueIdReferenceValidator;
import blue.language.identity.BlueIds;
import blue.language.identity.CanonicalTypeIdentityLookup;

import java.util.ArrayList;
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
    private final SchemaValueTypeResolver schemaValueTypeResolver;
    private final CanonicalTypeIdentityRecorder typeIdentityRecorder;
    private final TypeMetadataResolver typeMetadataResolver;
    private final DeclaredTypeContributionResolver
            declaredTypeContributionResolver;

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
        this.schemaValueTypeResolver = new SchemaValueTypeResolver(this);
        this.typeIdentityRecorder = new CanonicalTypeIdentityRecorder();
        this.activeTypeStack = new ActiveTypeStack();
        this.typeMetadataResolver = new TypeMetadataResolver(
                this,
                referenceResolver,
                activeTypeStack,
                typeIdentityRecorder);
        this.labelProvenanceTracker = new LabelProvenanceTracker(
                this, wrappedNodeProvider, listOverlayMerger);
        this.declaredTypeContributionResolver =
                new DeclaredTypeContributionResolver(
                        this,
                        referenceResolver,
                        labelProvenanceTracker,
                        activeTypeStack,
                        typeIdentityRecorder);
    }

    private ResolutionEngine invocationMerger() {
        return new ResolutionEngine(mergingProcessor, nodeProvider,
                resolvedReferenceCache,
                referenceCacheAdmissionPolicy,
                new ResolutionSession());
    }

    /**
     * Resolves one authored contribution with an independent invocation and
     * returns its immutable canonical/resolved evidence pair.
     *
     * <p>This hook deliberately bypasses the active mutable session. It is
     * used only when merge-time validation needs a complete semantic view of
     * authored input; sharing the current session would let partially merged
     * target state or incomplete type evidence influence that validation.</p>
     */
    SnapshotResolution resolveDetachedContributionSnapshot(
            Node authoredContribution) {
        Objects.requireNonNull(
                authoredContribution, "authoredContribution");
        return invocationMerger().resolveSnapshot(
                authoredContribution.clone(),
                ResolutionLimits.NO_LIMITS);
    }

    private boolean requiresFreshInvocation() {
        return resolutionSession == null
                || !resolutionSession.acceptsCurrentThread();
    }

    ResolutionState activeResolutionState() {
        return resolutionSession != null ? resolutionSession.state() : null;
    }

    CanonicalTypeIdentityLookup canonicalTypeIdentities() {
        ResolutionState state = activeResolutionState();
        if (state == null) {
            throw new IllegalStateException(
                    "Canonical type identities are available only during "
                            + "the owning resolution invocation");
        }
        return state.canonicalTypeIdentityIndex;
    }

    CanonicalTypeIdentityIndex.EvidenceSnapshot
    completedTypeIdentityEvidence() {
        return resolutionSession != null
                ? resolutionSession.completedTypeIdentityEvidence()
                : CanonicalTypeIdentityIndex.EvidenceSnapshot
                .incompleteEmpty();
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

    @Override
    public TypeEvidenceResolution resolveTypeEvidence(
            Node source,
            ResolutionLimits limits) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(limits, "limits");
        if (requiresFreshInvocation() || activeResolutionState() != null) {
            return invocationMerger().resolveTypeEvidence(source, limits);
        }
        Node resolved = resolve(source, limits);
        FrozenNode frozenResolved = resolvedReferenceCache != null
                ? resolvedReferenceCache.freezeResolved(resolved)
                : FrozenNode.fromResolvedNode(resolved);
        return new TypeEvidenceResolution(
                frozenResolved,
                completedTypeIdentityEvidence());
    }

    TypeEvidenceResolution resolveTypeDeclarationEvidence(
            Node declaration,
            ResolutionLimits limits) {
        Objects.requireNonNull(declaration, "declaration");
        Objects.requireNonNull(limits, "limits");
        if (requiresFreshInvocation() || activeResolutionState() != null) {
            return invocationMerger().resolveTypeDeclarationEvidence(
                    declaration, limits);
        }
        Node wrapper = new Node().type(declaration.clone());
        Node resolved = resolveRoot(
                wrapper,
                limits,
                Contribution.TYPE_METADATA);
        FrozenNode frozenResolved = resolvedReferenceCache != null
                ? resolvedReferenceCache.freezeResolved(resolved)
                : FrozenNode.fromResolvedNode(resolved);
        FrozenNode completedType = frozenResolved.getType();
        if (completedType == null) {
            throw new IllegalStateException(
                    "Type declaration resolution produced no effective type");
        }
        return new TypeEvidenceResolution(
                completedType,
                completedTypeIdentityEvidence());
    }

    TypeEvidenceResolution materializeTypeReferenceEvidence(
            FrozenNode reference,
            ResolutionLimits limits) {
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(limits, "limits");
        if (!reference.isReferenceOnly()
                || reference.getReferenceBlueId() == null) {
            throw new IllegalArgumentException(
                    "Type materialization requires a pure reference");
        }
        if (requiresFreshInvocation()) {
            return invocationMerger().materializeTypeReferenceEvidence(
                    reference, limits);
        }
        Node wrapper = new Node().type(reference.toNode());
        Node resolved = resolveRoot(
                wrapper,
                limits,
                Contribution.TYPE_METADATA);
        FrozenNode frozenResolved = resolvedReferenceCache != null
                ? resolvedReferenceCache.freezeResolved(resolved)
                : FrozenNode.fromResolvedNode(resolved);
        return new TypeEvidenceResolution(
                frozenResolved,
                completedTypeIdentityEvidence());
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
            InlineTypeCycleValidator.validate(target);
            InlineTypeCycleValidator.validate(source);
            state = new ResolutionState(
                    resolutionSession.canonicalTypeIdentityIndex());
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
            declaredTypeContributionResolver.merge(target, source, limits);
            if (labelMergeMode == LabelProvenanceTracker.MergeMode.AUTHORED_OVERLAY) {
                labelProvenanceTracker.applyExplicitInstanceLabels(
                        target, source, inheritedDeclarationOnly);
            } else if (labelMergeMode
                    == LabelProvenanceTracker.MergeMode.REFERENCE_EXPANSION) {
                labelProvenanceTracker.copyMaterializedReferenceLabels(target, source);
            }
            if (outermost) {
                completedValueValidator.validateCompletedCandidates(state);
                /*
                 * A public merge may start from a materialized target created
                 * by another invocation. That API has no paired sidecar, so
                 * the result is valid mutable semantic state but cannot claim
                 * complete whole-graph canonical evidence. Preserve that
                 * distinction instead of either hashing the completed target
                 * or rejecting a merge that does not canonicalize it.
                 */
                state.canonicalTypeIdentityIndex
                        .noteCoverageGapIfTypeEvidenceMissing(target);
                if (limits.retainsEveryAuthoredPath()) {
                    state.canonicalTypeIdentityIndex.markCompleteCoverage(
                            target);
                } else {
                    state.canonicalTypeIdentityIndex.noteCoverageGap();
                }
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

    Node canonicalTypeForLabelProvenance(Node typeNode) {
        return referenceResolver.canonicalTypeForLabelProvenance(typeNode);
    }

    void mergeObject(Node target, Node source, ResolutionLimits limits) {
        ResolutionState state = activeResolutionState();
        if (state.referenceExpansionAllowed) {
            referenceResolver.materializeReferenceBackedSchema(source);
            referenceResolver.materializeReferenceBackedContracts(source);
        }
        String path = currentPath(state);
        CompletedValueValidator.ContributionFrame frame =
                completedValueValidator.beginContribution(
                        state, target, source, path);
        try {

            if (state.referenceExpansionAllowed) {
                resolveTypeMetadata(source, limits);
                schemaValueTypeResolver.resolve(source.getSchema(), limits);
            }
            mergingProcessor.process(
                    target,
                    source,
                    nodeProvider,
                    this,
                    state.canonicalTypeIdentityIndex);

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

            mergingProcessor.postProcess(
                    target,
                    source,
                    nodeProvider,
                    this,
                    state.canonicalTypeIdentityIndex);
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
        if (!activeResolutionState().referenceExpansionAllowed) {
            activeResolutionState().canonicalTypeIdentityIndex
                    .noteCoverageGapIfTypeEvidenceMissing(sourceValue);
            target.getProperties().put(
                    sourceKey, sourceValue.clone());
            return;
        }
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

    void mergeWithContribution(Node target,
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
        ResolutionState state = activeResolutionState();
        state.incompleteTraversalEpoch++;
        state.canonicalTypeIdentityIndex.noteCoverageGap();
        completedValueValidator.markIncomplete(segment);
    }

    String currentPath(ResolutionState state) {
        return completedValueValidator.currentPath(state);
    }

    private void resolveTypeMetadata(Node source, ResolutionLimits limits) {
        typeMetadataResolver.resolve(source, limits);
    }

    @Override
    public Node resolve(Node node, ResolutionLimits limits) {
        return resolveRoot(node, limits, Contribution.INSTANCE);
    }

    private Node resolveRoot(
            Node node,
            ResolutionLimits limits,
            Contribution rootContribution) {
        Objects.requireNonNull(rootContribution, "rootContribution");
        if (requiresFreshInvocation()) {
            return invocationMerger().resolveRoot(
                    node, limits, rootContribution);
        }
        ResolutionState state = activeResolutionState();
        boolean outermost = state == null;
        boolean enteredOutermostLimit = false;
        if (outermost) {
            InlineTypeCycleValidator.validate(node);
            BlueIdReferenceValidator.validate(node);
            state = new ResolutionState(
                    resolutionSession.canonicalTypeIdentityIndex());
            state.rootInlineTypeDeclaration = completedValueValidator
                    .isInlineTypeDeclaration(node);
            state.rootSource = node;
            state.contribution = rootContribution;
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
                if (limits.retainsEveryAuthoredPath()) {
                    state.canonicalTypeIdentityIndex.markCompleteCoverage(
                            result);
                } else {
                    state.canonicalTypeIdentityIndex.noteCoverageGap();
                }
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
        /*
         * A source-preserved path is an authored executable/matcher subtree,
         * not a value that may be classified by its declared type. Returning
         * the exact subtree here is what makes the reference-expansion gate a
         * real cold boundary: downstream List/Dictionary/basic-type stages
         * must not consult the provider merely to classify a retained pure
         * type reference. Canonical coverage is certified later only when all
         * unexpanded type terminals are exact pure references.
         */
        if (!activeResolutionState().referenceExpansionAllowed) {
            activeResolutionState().canonicalTypeIdentityIndex
                    .noteCoverageGapIfTypeEvidenceMissing(node);
            return node.clone();
        }
        LabelProvenanceTracker.LabelProvenanceScope labelScope =
                labelProvenanceTracker.pushLabelProvenanceScope(
                        node, limits, false);
        try {
            Node resultNode = node.getProperties() != null
                    ? Nodes.emptyObject()
                    : new Node();
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
        final CanonicalTypeIdentityIndex canonicalTypeIdentityIndex;
        final List<String> path = new ArrayList<>();
        boolean referenceExpansionAllowed = true;
        Contribution contribution = Contribution.INSTANCE;
        Map<Node, Set<String>> appliedTypeContributions;
        final Map<Node, String> completedTypeMaterializations =
                new IdentityHashMap<>();
        long incompleteTraversalEpoch;
        boolean rootInlineTypeDeclaration;
        Node rootSource;

        ResolutionState(
                CanonicalTypeIdentityIndex canonicalTypeIdentityIndex) {
            this.canonicalTypeIdentityIndex = Objects.requireNonNull(
                    canonicalTypeIdentityIndex,
                    "canonicalTypeIdentityIndex");
        }
    }

}
