package blue.language.merge;

import blue.language.model.Node;
import blue.language.resolve.ResolutionLimits;
import blue.language.snapshot.FrozenNode;

/** Resolves item/key/value declarations with invocation-owned identity proof. */
final class TypeMetadataResolver {

    private final ResolutionEngine engine;
    private final ReferenceResolver referenceResolver;
    private final ActiveTypeStack activeTypeStack;
    private final CanonicalTypeIdentityRecorder identityRecorder;

    TypeMetadataResolver(
            ResolutionEngine engine,
            ReferenceResolver referenceResolver,
            ActiveTypeStack activeTypeStack,
            CanonicalTypeIdentityRecorder identityRecorder) {
        this.engine = engine;
        this.referenceResolver = referenceResolver;
        this.activeTypeStack = activeTypeStack;
        this.identityRecorder = identityRecorder;
    }

    void resolve(Node source, ResolutionLimits limits) {
        source.itemType(resolveNode(source.getItemType(), limits));
        source.keyType(resolveNode(source.getKeyType(), limits));
        source.valueType(resolveNode(source.getValueType(), limits));
    }

    private Node resolveNode(Node metadataType, ResolutionLimits limits) {
        if (metadataType == null) {
            return null;
        }
        CanonicalTypeIdentityIndex identityIndex = engine
                .activeResolutionState().canonicalTypeIdentityIndex;
        if (!metadataType.isReferenceOnly()) {
            /*
             * A completed enclosing type can be merged more than once while
             * its contribution is applied to the instance. Its materialized
             * item/key/value declarations are resolved values, not new inline
             * Source declarations. Resolver-issued evidence is the only safe
             * way to recognize that state: a root BlueId on a materialized
             * node is not sufficient proof, while structural lookup remains
             * fail-closed when equal shapes carry distinct provenance.
             */
            if (identityIndex.findCanonicalTypeBlueId(metadataType)
                    .isPresent()) {
                return metadataType;
            }
            Node authoredInlineType = metadataType.clone();
            long incompleteEpoch = incompleteTraversalEpoch();
            Node resolved = engine.resolveWithContribution(
                    metadataType,
                    limits,
                    ResolutionEngine.Contribution.TYPE_METADATA);
            if (incompleteEpoch != incompleteTraversalEpoch()) {
                return null;
            }
            identityRecorder.recordCompleted(
                    identityIndex, resolved, authoredInlineType, null);
            return resolved;
        }
        String typeBlueId = metadataType.getBlueId();
        if (activeTypeStack.isMaterializing(typeBlueId)) {
            return new Node().blueId(typeBlueId);
        }
        // A resolved cache entry proves identity, not this invocation's
        // definition validation. Replay the exact canonical declaration (which
        // can still come from the canonical cache) so fixed-content candidates
        // and their deferred reference obligations cannot disappear on a hit.
        ActiveTypeStack.Token key = activeTypeStack.token(
                typeBlueId, engine.activeResolutionState().path.size());
        activeTypeStack.begin(key);
        try {
            long incompleteEpoch = incompleteTraversalEpoch();
            referenceResolver.expandTypeReference(metadataType, typeBlueId);
            Node resolved = engine.resolveCanonicalWithContribution(
                    metadataType,
                    limits,
                    ResolutionEngine.Contribution.TYPE_METADATA);
            if (incompleteEpoch != incompleteTraversalEpoch()) {
                return new Node().blueId(typeBlueId);
            }
            identityRecorder.recordCompleted(
                    identityIndex, resolved, null, typeBlueId);
            referenceResolver.cacheResolvedReference(
                    typeBlueId, resolved, limits);
            return resolved;
        } finally {
            activeTypeStack.finish(key);
        }
    }

    private long incompleteTraversalEpoch() {
        return engine.activeResolutionState().incompleteTraversalEpoch;
    }
}
