package blue.language.merge;

import blue.language.model.Node;
import blue.language.resolve.ResolutionLimits;
import blue.language.snapshot.FrozenNode;

import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Applies one node's declared type before its direct instance contribution. */
final class DeclaredTypeContributionResolver {

    private final ResolutionEngine engine;
    private final ReferenceResolver referenceResolver;
    private final LabelProvenanceTracker labelProvenanceTracker;
    private final ActiveTypeStack activeTypeStack;
    private final CanonicalTypeIdentityRecorder typeIdentityRecorder;

    DeclaredTypeContributionResolver(
            ResolutionEngine engine,
            ReferenceResolver referenceResolver,
            LabelProvenanceTracker labelProvenanceTracker,
            ActiveTypeStack activeTypeStack,
            CanonicalTypeIdentityRecorder typeIdentityRecorder) {
        this.engine = engine;
        this.referenceResolver = referenceResolver;
        this.labelProvenanceTracker = labelProvenanceTracker;
        this.activeTypeStack = activeTypeStack;
        this.typeIdentityRecorder = typeIdentityRecorder;
    }

    void merge(Node target, Node source, ResolutionLimits limits) {
        if (source.getBlue() != null) {
            throw new IllegalArgumentException(
                    "Document contains \"blue\" attribute. Preprocess "
                            + "document before merging.");
        }

        ActiveTypeStack.Token deferredTypeResolution = null;
        /*
         * A selectively preserved path is an exact authored subtree, not a
         * complete instance of its declared type. Keep its type metadata for
         * the eventual exact-path restoration, but do not expand the type or
         * validate its schema while walking the surrounding document.
         */
        if (source.getType() != null
                && state().referenceExpansionAllowed) {
            deferredTypeResolution = mergeDeclaredType(
                    target, source, limits);
        }
        try {
            engine.mergeObject(target, source, limits);
        } finally {
            if (deferredTypeResolution != null) {
                activeTypeStack.finish(deferredTypeResolution);
            }
        }
    }

    private ActiveTypeStack.Token mergeDeclaredType(
            Node target,
            Node source,
            ResolutionLimits limits) {
        Node typeNode = source.getType();
        CanonicalTypeIdentityIndex identityIndex =
                state().canonicalTypeIdentityIndex;
        boolean pureTypeReference = typeNode.isReferenceOnly();
        Optional<String> completedTypeBlueId = pureTypeReference
                ? Optional.<String>empty()
                : Optional.ofNullable(
                        state().completedTypeMaterializations.get(typeNode));
        if (!pureTypeReference && !completedTypeBlueId.isPresent()) {
            /* Structural evidence is authoritative across contribution clones. */
            completedTypeBlueId = identityIndex
                    .findCanonicalTypeBlueId(typeNode);
        }
        boolean completedTypeFromInvocation =
                completedTypeBlueId.isPresent();
        String typeBlueId = completedTypeFromInvocation
                ? completedTypeBlueId.get()
                : pureTypeReference ? typeNode.getBlueId() : null;
        Node authoredInlineType = !completedTypeFromInvocation
                && !pureTypeReference
                ? typeNode.clone()
                : null;
        recordTypeDeclarationLabels(typeNode);
        boolean contributionApplied = hasAppliedContribution(
                target, typeBlueId);
        ResolutionEngine.Contribution contribution =
                state().contribution
                        == ResolutionEngine.Contribution.TYPE_METADATA
                        ? ResolutionEngine.Contribution.TYPE_METADATA
                        : ResolutionEngine.Contribution.TYPE_ROOT;
        if (completedTypeFromInvocation) {
            Node completedTypeCopy = typeNode.clone();
            identityIndex.bindEquivalentGraph(typeNode, completedTypeCopy);
            mergeCompletedType(
                    target,
                    source,
                    completedTypeCopy,
                    typeBlueId,
                    limits,
                    contributionApplied,
                    contribution);
            return null;
        }
        return resolveAndMergeType(
                target,
                source,
                typeNode,
                typeBlueId,
                authoredInlineType,
                limits,
                contributionApplied,
                contribution);
    }

    private ActiveTypeStack.Token resolveAndMergeType(
            Node target,
            Node source,
            Node typeNode,
            String typeBlueId,
            Node authoredInlineType,
            ResolutionLimits limits,
            boolean contributionApplied,
            ResolutionEngine.Contribution contribution) {
        boolean materializedCyclicType = referenceResolver
                .isMaterializedCyclicSetMemberType(typeNode);
        FrozenNode cachedResolvedType = referenceResolver
                .cachedResolvedType(typeBlueId, limits);
        String recursionBoundaryBlueId = typeBlueId != null
                ? typeBlueId
                : materializedCyclicType ? typeNode.getBlueId() : null;
        boolean trackedType = recursionBoundaryBlueId != null;
        ActiveTypeStack.Token resolutionKey = trackedType
                ? activeTypeStack.token(
                        recursionBoundaryBlueId, state().path.size())
                : null;
        if (trackedType && activeTypeStack.isResolving(resolutionKey)) {
            throw new IllegalStateException(
                    "Cyclic type hierarchy at path "
                            + engine.currentPath(state())
                            + " for blueId: " + typeBlueId);
        }
        boolean recursiveBoundary = trackedType
                && activeTypeStack.isMaterializing(recursionBoundaryBlueId);
        boolean startedResolution = trackedType && !recursiveBoundary;
        if (startedResolution) {
            activeTypeStack.begin(resolutionKey);
        }
        ActiveTypeStack.Token deferred = null;
        try {
            if (!recursiveBoundary) {
                long incompleteEpoch = state().incompleteTraversalEpoch;
                Node resolvedType = cachedResolvedType != null
                        ? cachedType(cachedResolvedType, typeBlueId)
                        : resolveType(typeNode, typeBlueId, limits,
                                contribution);
                boolean completeTypeMaterialization = incompleteEpoch
                        == state().incompleteTraversalEpoch;
                if (completeTypeMaterialization) {
                    String canonicalBlueId = recordCompletedTypeIdentity(
                            resolvedType, authoredInlineType, typeBlueId);
                    source.type(detachedTypeMetadata(
                            resolvedType, canonicalBlueId));
                } else {
                    source.type(detachedUnprovenTypeMetadata(
                            resolvedType, typeBlueId));
                }
                if (!contributionApplied) {
                    mergeResolvedType(
                            target,
                            typeNode,
                            resolvedType,
                            typeBlueId,
                            limits,
                            contribution,
                            cachedResolvedType != null);
                    recordAppliedContribution(target, typeBlueId);
                }
            }
            if (startedResolution && materializedCyclicType) {
                deferred = resolutionKey;
            }
            return deferred;
        } finally {
            if (startedResolution && deferred == null) {
                activeTypeStack.finish(resolutionKey);
            }
        }
    }

    private Node resolveType(
            Node typeNode,
            String typeBlueId,
            ResolutionLimits limits,
            ResolutionEngine.Contribution contribution) {
        if (typeBlueId != null) {
            referenceResolver.expandTypeReference(typeNode, typeBlueId);
        }
        Node resolvedType = typeBlueId != null
                ? engine.resolveCanonicalWithContribution(typeNode, limits, contribution)
                : engine.resolveWithContribution(typeNode, limits, contribution);
        referenceResolver.cacheResolvedReference(
                typeBlueId, resolvedType, limits);
        return resolvedType;
    }

    private void mergeResolvedType(
            Node target,
            Node typeNode,
            Node resolvedType,
            String typeBlueId,
            ResolutionLimits limits,
            ResolutionEngine.Contribution contribution,
            boolean cacheHit) {
        if (cacheHit || referenceResolver.cachedResolvedType(
                typeBlueId, limits) != null) {
            engine.mergeCanonicalObjectWithContribution(
                    target, resolvedType, limits, contribution);
        } else if (typeBlueId != null) {
            engine.mergeCanonicalWithContribution(target, typeNode, limits, contribution);
        } else {
            engine.mergeWithContribution(target, typeNode, limits, contribution);
        }
    }

    private void mergeCompletedType(
            Node target,
            Node source,
            Node resolvedType,
            String typeBlueId,
            ResolutionLimits limits,
            boolean contributionApplied,
            ResolutionEngine.Contribution contribution) {
        source.type(detachedTypeMetadata(resolvedType, typeBlueId));
        if (!contributionApplied) {
            engine.mergeCanonicalObjectWithContribution(
                    target, resolvedType, limits, contribution);
            recordAppliedContribution(target, typeBlueId);
        }
    }

    private void recordTypeDeclarationLabels(Node typeNode) {
        LabelProvenanceTracker.LabelProvenanceScope labelScope =
                labelProvenanceTracker.currentLabelProvenanceScope();
        LabelPath currentPath = labelProvenanceTracker.currentLabelPath();
        ResolutionEngine.Contribution contribution = state().contribution;
        if (labelScope != null
                && contribution != ResolutionEngine.Contribution.TYPE_ROOT
                && contribution
                != ResolutionEngine.Contribution.TYPE_METADATA
                && contribution
                != ResolutionEngine.Contribution.TYPE_DECLARATION
                && labelProvenanceTracker.hasLabelPathAtOrBelow(
                        labelScope.labelPaths, currentPath)) {
            labelProvenanceTracker.recordTypeDeclarationLabelPaths(
                    typeNode, currentPath, labelScope.labelPaths);
        }
    }

    private boolean hasAppliedContribution(
            Node target,
            String typeBlueId) {
        if (typeBlueId == null || state().appliedTypeContributions == null) {
            return false;
        }
        Set<String> applied = state().appliedTypeContributions.get(target);
        return applied != null && applied.contains(typeBlueId);
    }

    private void recordAppliedContribution(Node target, String typeBlueId) {
        if (typeBlueId == null) {
            return;
        }
        if (state().appliedTypeContributions == null) {
            state().appliedTypeContributions = new IdentityHashMap<>();
        }
        Set<String> applied = state().appliedTypeContributions.get(target);
        if (applied == null) {
            applied = new HashSet<>();
            state().appliedTypeContributions.put(target, applied);
        }
        applied.add(typeBlueId);
    }

    private Node detachedTypeMetadata(
            Node resolvedType,
            String canonicalTypeBlueId) {
        Node detached = resolvedType.clone();
        state().canonicalTypeIdentityIndex.bindEquivalentGraph(
                resolvedType, detached);
        state().canonicalTypeIdentityIndex.bindCanonicalIdentity(
                detached, canonicalTypeBlueId);
        state().completedTypeMaterializations.put(
                detached,
                Objects.requireNonNull(
                        canonicalTypeBlueId,
                        "canonicalTypeBlueId"));
        return detached;
    }

    private Node detachedUnprovenTypeMetadata(
            Node resolvedType,
            String requestedBlueId) {
        if (requestedBlueId != null) {
            return new Node().blueId(requestedBlueId);
        }
        return null;
    }

    private Node cachedType(FrozenNode cached, String typeBlueId) {
        Node resolved = cached.toNode();
        if (resolved.getBlueId() == null) {
            resolved.blueId(typeBlueId);
        }
        return resolved;
    }

    private String recordCompletedTypeIdentity(
            Node completedType,
            Node authoredInlineType,
            String requestedBlueId) {
        return typeIdentityRecorder.recordCompleted(
                state().canonicalTypeIdentityIndex,
                completedType,
                authoredInlineType,
                requestedBlueId);
    }

    private ResolutionEngine.ResolutionState state() {
        return engine.activeResolutionState();
    }
}
