package blue.language.merge;

import blue.language.provider.NodeProvider;
import blue.language.model.Node;
import blue.language.model.NodeDeserializer;
import blue.language.model.Schema;
import blue.language.resolve.ReferenceCacheAdmissionPolicy;
import blue.language.snapshot.FrozenNode;
import blue.language.snapshot.ResolvedReferenceCache;
import blue.language.utils.BlueIds;
import blue.language.model.wire.JsonPointer;
import blue.language.model.NodeWireForm;
import blue.language.model.wire.BlueLanguageConstants;
import blue.language.utils.limits.Limits;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static blue.language.model.wire.BlueLanguageConstants.CORE_TYPE_BLUE_IDS;
import static blue.language.utils.UncheckedObjectMapper.JSON_MAPPER;

/**
 * Resolves exact provider content and owns the invocation-local canonical and
 * completed-reference memoization used during a merge.
 */
final class ReferenceResolver {

    private final ResolutionEngine engine;
    private final MergingProcessor mergingProcessor;
    private final NodeProvider nodeProvider;
    private final ResolvedReferenceCache resolvedReferenceCache;
    private final ReferenceCacheAdmissionPolicy cacheAdmissionPolicy;

    private Map<String, CanonicalReference> canonicalReferences;
    private Map<String, Node> fullyResolvedReferences;
    private Set<String> materializingReferences;
    private Set<String> failedProviderReferences;
    private boolean rootSourceSchemaChecked;
    private boolean rootSourceContainsSchema;
    private boolean schemaRequiresTypeSourceProvenance;

    ReferenceResolver(
            ResolutionEngine engine,
            MergingProcessor mergingProcessor,
            NodeProvider nodeProvider,
            ResolvedReferenceCache resolvedReferenceCache,
            ReferenceCacheAdmissionPolicy cacheAdmissionPolicy) {
        this.engine = engine;
        this.mergingProcessor = mergingProcessor;
        this.nodeProvider = nodeProvider;
        this.resolvedReferenceCache = resolvedReferenceCache;
        this.cacheAdmissionPolicy = cacheAdmissionPolicy;
    }

    void expandTypeReference(Node typeNode, String blueId) {
        if (CORE_TYPE_BLUE_IDS.contains(blueId)) {
            return;
        }
        CanonicalReference canonicalReference = typeCanonicalReference(
                blueId, engine.activeResolutionState());
        if (canonicalReference.canonical.containsSchema()) {
            schemaRequiresTypeSourceProvenance = true;
        }
        typeNode.replaceWith(canonicalReference.canonical.toNode());
        typeNode.blueId(blueId);
    }

    private CanonicalReference typeCanonicalReference(String blueId, ResolutionEngine.ResolutionState state) {
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

    Node canonicalTypeForLabelProvenance(Node typeNode) {
        String typeBlueId = typeNode.getBlueId();
        if (typeBlueId == null) {
            return typeNode;
        }
        if (CORE_TYPE_BLUE_IDS.contains(typeBlueId)) {
            return null;
        }
        return typeCanonicalReference(
                typeBlueId, engine.activeResolutionState()).canonical.toNode();
    }

    private boolean canCacheDirectCanonical(String blueId) {
        return resolvedReferenceCache != null
                && blueId != null
                && !BlueIds.hasCyclicMemberSeparator(blueId)
                && cacheAdmissionPolicy
                .mayCacheCanonical(blueId);
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

    FrozenNode cachedResolvedReference(String blueId, Limits limits) {
        if (blueId == null || resolvedReferenceCache == null || limits != Limits.NO_LIMITS) {
            return null;
        }
        return resolvedReferenceCache.getVerifiedResolved(blueId).orElse(null);
    }

    FrozenNode cachedResolvedType(String blueId, Limits limits) {
        FrozenNode cached = cachedResolvedReference(blueId, limits);
        if (cached == null) {
            return null;
        }
        ResolutionEngine.ResolutionState state = engine.activeResolutionState();
        if (cached.containsSchema()) {
            schemaRequiresTypeSourceProvenance = true;
        }
        if (!cached.containsNestedTypedObjectPayload()) {
            return cached;
        }
        if (schemaRequiresTypeSourceProvenance) {
            return null;
        }
        if (!rootSourceSchemaChecked) {
            rootSourceContainsSchema = containsSchema(state.rootSource);
            rootSourceSchemaChecked = true;
            if (rootSourceContainsSchema) {
                schemaRequiresTypeSourceProvenance = true;
            }
        }
        return schemaRequiresTypeSourceProvenance ? null : cached;
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


    void cacheResolvedReference(String blueId, Node resolvedType, Limits limits) {
        if (blueId == null || resolvedReferenceCache == null || limits != Limits.NO_LIMITS) {
            return;
        }
        CanonicalReference local = localCanonicalReference(
                engine.activeResolutionState(), blueId);
        if (local == null || !local.directlyVerified) {
            return;
        }
        FrozenNode canonical = resolvedReferenceCache.getVerifiedCanonical(blueId).orElse(null);
        if (canonical != null) {
            FrozenNode frozenResolved = resolvedReferenceCache.freezeResolved(resolvedType);
            if (!frozenResolved.isReferenceOnly()) {
                resolvedReferenceCache.putVerifiedResolved(
                        new blue.language.merge.VerifiedReferenceResolution(
                                blueId, canonical, frozenResolved));
            }
        }
    }


    void materializeReferenceBackedSchema(Node source) {
        Schema schema = source.getSchema();
        if (schema == null || !schema.isReferenceOnly()) {
            return;
        }
        String blueId = schema.getBlueId();
        Node content = requiredProviderContent(
                blueId, engine.activeResolutionState());
        Object schemaValue = NodeWireForm.get(content);
        Schema materialized = NodeDeserializer.parseSchema(
                JSON_MAPPER.valueToTree(schemaValue),
                JsonPointer.append(
                        engine.currentPath(engine.activeResolutionState()),
                        BlueLanguageConstants.OBJECT_SCHEMA));
        if (materialized.isReferenceOnly()) {
            throw new IllegalArgumentException(
                    "Provider returned reference-only schema content for required blueId: " + blueId);
        }
        source.schema(materialized);
    }

    void materializeReferenceBackedContracts(Node source) {
        Node contracts = source.getContracts();
        if (contracts == null || !contracts.isReferenceOnly()) {
            return;
        }
        String blueId = contracts.getBlueId();
        Node materialized = requiredProviderContent(
                blueId, engine.activeResolutionState());
        if (materialized.isReferenceOnly()) {
            throw new IllegalArgumentException(
                    "Provider returned reference-only contracts content for required blueId: "
                            + blueId);
        }
        source.contracts(materialized);
    }


    boolean requiresCyclicTypeCompletion(Node inherited, Node source) {
        if (source.getType() != null || inherited.getType() == null
                || !inherited.getType().isReferenceOnly()) {
            return false;
        }
        String inheritedTypeBlueId = inherited.getType().getBlueId();
        return BlueIds.hasCyclicMemberSeparator(inheritedTypeBlueId);
    }

    boolean containsCyclicSetReference(Node root) {
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

    boolean isMaterializedCyclicSetMemberType(Node type) {
        String blueId = type.getBlueId();
        return BlueIds.hasCyclicMemberSeparator(blueId)
                && !type.isReferenceOnly();
    }


    boolean requiresReferenceContent(Node target) {
        return target.getType() != null
                || mergingProcessor.requiresReferenceMaterialization(target)
                || hasConcretePayload(target);
    }

    private boolean hasConcretePayload(Node node) {
        if (node == null) {
            return false;
        }
        if (node.getValue() != null || node.getItems() != null) {
            return true;
        }
        return node.getProperties() != null
                && !node.getProperties().isEmpty();
    }

    private void materializeReference(Node target,
                                      String blueId,
                                      Limits limits,
                                      ResolutionEngine.ResolutionState state) {
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
        engine.mergeObjectWithContribution(target, mergeable, limits, ResolutionEngine.Contribution.MATERIALIZED_REFERENCE);
        engine.copyMaterializedReferenceLabels(target, materialized);
        target.blueId(blueId);
    }

    private void materializeCyclicSetReference(Node target,
                                               String blueId,
                                               Limits limits,
                                               ResolutionEngine.ResolutionState state,
                                               CanonicalReference canonicalReference) {
        if (materializingReferences == null) {
            materializingReferences = new HashSet<>();
        }
        if (!materializingReferences.add(blueId)) {
            throw new IllegalStateException("Cyclic reference materialization at path "
                    + engine.currentPath(state) + " for blueId: " + blueId);
        }
        try {
            Node materialized = engine.resolveWithContribution(
                    canonicalReference.canonical.toNode(), limits, ResolutionEngine.Contribution.INSTANCE);
            Node mergeable = materialized.clone();
            if (mergeable.getBlueId() != null && !mergeable.isReferenceOnly()) {
                mergeable.blueId(null);
            }
            engine.mergeObjectWithContribution(
                    target, mergeable, limits, ResolutionEngine.Contribution.MATERIALIZED_REFERENCE);
            engine.copyMaterializedReferenceLabels(target, materialized);
            target.blueId(blueId);
        } finally {
            materializingReferences.remove(blueId);
        }
    }

    void materializeReferenceAtCurrentPath(Node target,
                                                   String blueId,
                                                   Limits limits,
                                                   ResolutionEngine.ResolutionState state) {
        String path = engine.currentPath(state);
        try {
            materializeReference(target, blueId, limits, state);
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("Reference materialization failed at path " + path
                    + " for blueId " + blueId + ": " + ex.getMessage(), ex);
        }
    }

    private Node materializedReference(String blueId,
                                       Limits limits,
                                       ResolutionEngine.ResolutionState state,
                                       CanonicalReference canonicalReference) {
        if (limits == Limits.NO_LIMITS && fullyResolvedReferences != null) {
            Node existing = fullyResolvedReferences.get(blueId);
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
        if (materializingReferences == null) {
            materializingReferences = new HashSet<>();
        }
        if (!materializingReferences.add(blueId)) {
            throw new IllegalStateException("Cyclic reference materialization at path "
                    + engine.currentPath(state) + " for blueId: " + blueId);
        }

        try {
            Node resolved = engine.resolveWithContribution(
                    canonical.toNode(), limits, ResolutionEngine.Contribution.INSTANCE);
            resolved.blueId(blueId);
            if (canonicalReference.directlyVerified
                    && resolvedReferenceCache != null && limits == Limits.NO_LIMITS) {
                resolvedReferenceCache.putVerifiedResolved(
                        new blue.language.merge.VerifiedReferenceResolution(
                                blueId, canonical,
                                resolvedReferenceCache.freezeResolved(resolved)));
            }
            if (limits == Limits.NO_LIMITS) {
                rememberFullyResolved(state, blueId, resolved);
            }
            return resolved.clone();
        } finally {
            materializingReferences.remove(blueId);
        }
    }

    private CanonicalReference canonicalReference(String blueId, ResolutionEngine.ResolutionState state) {
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
        if (failedProviderReferences != null
                && failedProviderReferences.contains(blueId)) {
            throw new IllegalArgumentException("Unable to materialize required reference at path "
                    + engine.currentPath(state) + ": " + blueId);
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
            if (failedProviderReferences == null) {
                failedProviderReferences = new HashSet<>();
            }
            failedProviderReferences.add(blueId);
            throw ex;
        }
    }

    private Node requiredProviderContent(String blueId, ResolutionEngine.ResolutionState state) {
        List<Node> nodes = nodeProvider.fetchByBlueId(blueId);
        if (nodes == null || nodes.isEmpty()) {
            throw new IllegalArgumentException("No content found for required blueId " + blueId
                    + " at path " + engine.currentPath(state) + ".");
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

    private CanonicalReference localCanonicalReference(ResolutionEngine.ResolutionState state, String blueId) {
        return canonicalReferences != null ? canonicalReferences.get(blueId) : null;
    }

    private CanonicalReference rememberCanonical(ResolutionEngine.ResolutionState state,
                                                 String blueId,
                                                 FrozenNode canonical,
                                                 boolean directlyVerified) {
        if (canonicalReferences == null) {
            canonicalReferences = new LinkedHashMap<>();
        }
        CanonicalReference reference = new CanonicalReference(canonical, directlyVerified);
        canonicalReferences.put(blueId, reference);
        return reference;
    }

    private void rememberFullyResolved(ResolutionEngine.ResolutionState state, String blueId, Node materialized) {
        if (fullyResolvedReferences == null) {
            fullyResolvedReferences = new LinkedHashMap<>();
        }
        fullyResolvedReferences.put(blueId, materialized.clone());
    }


    static final class CanonicalReference {
        final FrozenNode canonical;
        final boolean directlyVerified;

        private CanonicalReference(
                FrozenNode canonical, boolean directlyVerified) {
            this.canonical = canonical;
            this.directlyVerified = directlyVerified;
        }
    }
}
