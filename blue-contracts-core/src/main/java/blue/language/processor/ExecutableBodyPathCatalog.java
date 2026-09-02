package blue.language.processor;

import blue.language.model.Node;
import blue.language.model.wire.BlueLanguageConstants;
import blue.language.processor.util.PointerUtils;
import blue.language.processor.util.ProcessorContractConstants;
import blue.language.snapshot.FrozenNode;
import blue.language.merge.ResolvedSnapshot;
import blue.language.identity.CanonicalTypeIdentityLookup;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.identity.BlueIds;
import blue.language.model.wire.JsonPointer;
import blue.language.model.NodePathEditor;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Finds cold executable-body and opaque cyclic-edge paths without opening them. */
final class ExecutableBodyPathCatalog {

    private ExecutableBodyPathCatalog() {
    }

    static Set<String> fromNode(
            Node document,
            Iterable<String> openedScopePaths,
            Map<String, List<String>> executableBodyFieldsByType,
            ProcessingSnapshotManager exactMaterializer) {
        return fromNodeDirectContracts(
                document,
                openedScopePaths,
                executableBodyFieldsByType,
                exactMaterializer);
    }

    /**
     * Finds executable fields contributed by direct contracts and exact
     * scope-type ancestry for strict evidence-selected processing.
     */
    static Set<String> fromNodeIncludingTypeContracts(
            Node document,
            Iterable<String> openedScopePaths,
            Map<String, List<String>> executableBodyFieldsByType,
            ProcessingSnapshotManager exactMaterializer) {
        return fromNodeIncludingTypeContracts(
                document,
                openedScopePaths,
                executableBodyFieldsByType,
                exactMaterializer,
                false);
    }

    /**
     * Identity-only counterpart that retains pure referenced contract headers
     * as exact values instead of demanding their content for recognition.
     */
    static Set<String> fromNodeIncludingTypeContractsForSourceIdentity(
            Node document,
            Iterable<String> openedScopePaths,
            Map<String, List<String>> exactSourceFieldsByType,
            ProcessingSnapshotManager exactMaterializer) {
        return fromNodeIncludingTypeContracts(
                document,
                openedScopePaths,
                exactSourceFieldsByType,
                exactMaterializer,
                true);
    }

    private static Set<String> fromNodeIncludingTypeContracts(
            Node document,
            Iterable<String> openedScopePaths,
            Map<String, List<String>> executableBodyFieldsByType,
            ProcessingSnapshotManager exactMaterializer,
            boolean retainPureContractReferences) {
        Set<String> result = new LinkedHashSet<>();
        Set<String> selectedScopes = openedScopes(openedScopePaths);
        for (String scopePath : selectedScopes) {
            Node scope = JsonPointer.ROOT.equals(scopePath)
                    ? document
                    : NodePathEditor.getOrNull(document, scopePath);
            collectIncludingTypeContracts(
                    scope,
                    JsonPointer.split(scopePath),
                    executableBodyFieldsByType,
                    result,
                    exactMaterializer,
                    selectedScopes,
                    retainPureContractReferences);
        }
        return result;
    }

    /** Finds only executable fields declared directly on opened scopes. */
    static Set<String> fromNodeDirectContracts(
            Node document,
            Iterable<String> openedScopePaths,
            Map<String, List<String>> executableBodyFieldsByType,
            ProcessingSnapshotManager exactMaterializer) {
        return fromNodeDirectContracts(
                document,
                openedScopePaths,
                executableBodyFieldsByType,
                exactMaterializer,
                false);
    }

    /** Identity-only direct-contract catalog with exact reference retention. */
    static Set<String> fromNodeDirectContractsForSourceIdentity(
            Node document,
            Iterable<String> openedScopePaths,
            Map<String, List<String>> exactSourceFieldsByType,
            ProcessingSnapshotManager exactMaterializer) {
        return fromNodeDirectContracts(
                document,
                openedScopePaths,
                exactSourceFieldsByType,
                exactMaterializer,
                true);
    }

    private static Set<String> fromNodeDirectContracts(
            Node document,
            Iterable<String> openedScopePaths,
            Map<String, List<String>> executableBodyFieldsByType,
            ProcessingSnapshotManager exactMaterializer,
            boolean retainPureContractReferences) {
        Set<String> result = new LinkedHashSet<>();
        for (String scopePath : openedScopes(openedScopePaths)) {
            Node scope = JsonPointer.ROOT.equals(scopePath)
                    ? document
                    : NodePathEditor.getOrNull(document, scopePath);
            collectLegacyDirectContracts(
                    scope,
                    JsonPointer.split(scopePath),
                    executableBodyFieldsByType,
                    result,
                    exactMaterializer,
                    retainPureContractReferences);
        }
        return result;
    }

    /**
     * Preserves the established manager semantics for configured processor
     * calls, while retaining exact BlueId verification for any reference-
     * backed contracts map or contract header that recognition must open.
     */
    private static void collectLegacyDirectContracts(
            Node node,
            List<String> path,
            Map<String, List<String>> executableBodyFieldsByType,
            Set<String> result,
            ProcessingSnapshotManager materializer,
            boolean retainPureContractReferences) {
        if (node == null
                || executableBodyFieldsByType == null
                || executableBodyFieldsByType.isEmpty()) {
            return;
        }
        Node contracts = node.getContracts();
        if (contracts != null && contracts.isReferenceOnly()
                && materializer != null) {
            contracts = materializeVerifiedExact(
                    materializer,
                    FrozenNode.fromNode(contracts),
                    "Contracts-map recognition").toNode();
        }
        if (contracts == null || contracts.getProperties() == null) {
            return;
        }
        for (Map.Entry<String, Node> entry
                : contracts.getProperties().entrySet()) {
            Node contract = entry.getValue();
            if (contract != null && contract.isReferenceOnly()
                    && materializer != null) {
                if (retainPureContractReferences) {
                    addContractPath(path, entry.getKey(), result);
                    continue;
                }
                contract = materializeVerifiedExact(
                        materializer,
                        FrozenNode.fromNode(contract),
                        "Contract-header recognition").toNode();
            }
            List<String> fields = executableBodyFieldsByType.get(
                    ExecutableBodyTypeIdentity.fromSource(
                            contract, materializer));
            if (fields != null) {
                // Event is an exact header matcher, not an executable body.
                // Identity callers include it through the exact-field map.
                if (!retainPureContractReferences) {
                    addEventMatcherPath(
                            contract, path, entry.getKey(), result);
                }
                for (String field : fields) {
                    addBodyPath(path, entry.getKey(), field, result);
                }
            }
        }
    }

    static Set<String> fromFrozen(
            FrozenNode document,
            Iterable<String> openedScopePaths,
            Map<String, List<String>> executableBodyFieldsByType,
            CanonicalTypeIdentityLookup canonicalTypeIdentities) {
        Set<String> result = new LinkedHashSet<>();
        for (String scopePath : openedScopes(openedScopePaths)) {
            FrozenNode scope = document != null
                    ? document.at(scopePath)
                    : null;
            collect(
                    scope,
                    JsonPointer.split(scopePath),
                    executableBodyFieldsByType,
                    result,
                    canonicalTypeIdentities);
        }
        return result;
    }

    static ResolvedSnapshot resolveCanonicalTransient(
            ProcessingSnapshotManager manager,
            FrozenNode canonicalRoot,
            Iterable<String> openedScopePaths,
            Map<String, List<String>> executableBodyFieldsByType) {
        ProcessingSnapshotManager checkedManager = Objects.requireNonNull(
                manager, "snapshotManager");
        FrozenNode checkedRoot = Objects.requireNonNull(
                canonicalRoot, "canonicalRoot");
        Node document = checkedRoot.toNode();
        Set<String> preserved = fromNodeDirectContracts(
                document,
                openedScopePaths,
                executableBodyFieldsByType,
                checkedManager);
        if (!preserved.isEmpty()) {
            preserved.addAll(processorStateReferencePaths(
                    document, openedScopePaths));
        }
        preserved.addAll(opaqueCyclicMemberPaths(document));
        if (preserved.isEmpty()) {
            return checkedManager.fromDocumentTransient(document);
        }
        return forceDeferredResolution(
                checkedManager.fromDocumentTransientPreservingPaths(
                        document, preserved));
    }

    /**
     * Resolves an evidence-selected platform scope while keeping inherited
     * bodies and ordinary reference values physically deferred.
     */
    static ResolvedSnapshot resolveCanonicalTransientIncludingTypeContracts(
            ProcessingSnapshotManager manager,
            FrozenNode canonicalRoot,
            Iterable<String> openedScopePaths,
            Map<String, List<String>> executableBodyFieldsByType) {
        ProcessingSnapshotManager checkedManager = Objects.requireNonNull(
                manager, "snapshotManager");
        FrozenNode checkedRoot = Objects.requireNonNull(
                canonicalRoot, "canonicalRoot");
        Node document = checkedRoot.toNode();
        Set<String> preserved = fromNodeIncludingTypeContracts(
                document,
                openedScopePaths,
                executableBodyFieldsByType,
                checkedManager);
        preserved.addAll(ordinaryReferencePaths(
                document, openedScopePaths));
        if (!preserved.isEmpty()) {
            preserved.addAll(processorStateReferencePaths(
                    document, openedScopePaths));
        }
        preserved.addAll(opaqueCyclicMemberPaths(document));
        if (preserved.isEmpty()) {
            return checkedManager.fromDocumentTransient(document);
        }
        return forceDeferredResolution(
                checkedManager.fromDocumentTransientPreservingPaths(
                        document, preserved));
    }

    static Set<String> opaqueCyclicMemberPaths(Node document) {
        Set<String> result = new LinkedHashSet<>();
        collectOpaqueCyclicMemberPaths(
                document,
                JsonPointer.ROOT,
                result,
                new IdentityHashMap<Node, Boolean>());
        return result;
    }

    static Set<String> ordinaryReferencePaths(Node document) {
        return ordinaryReferencePaths(document, null);
    }

    /**
     * Finds exact processor-state witnesses that remain semantic references.
     *
     * <p>An initialized marker deliberately records its pre-initialization
     * document as either verified content or an equivalent pure BlueId
     * reference. Resolving that reference would reinterpret arbitrary document
     * content through the marker field's required-node schema, so snapshot and
     * conformance resolution keep the exact witness collapsed.</p>
     */
    static Set<String> processorStateReferencePaths(
            Node document,
            Iterable<String> openedScopePaths) {
        return ProcessorStateReferencePathCatalog.find(
                document, openedScopePaths);
    }

    /** Finds exact processor-state witnesses carried by unapplied patches. */
    static Set<String> processorStatePatchEffectPaths(Node source) {
        return ProcessorStateReferencePathCatalog.findInPatchEffects(source);
    }

    /**
     * Finds references that are ordinary relative to the selected scope
     * closure. Type, contracts-map, and list-replacement references are
     * structural only on a selected scope or one of its ancestors; the same
     * references on an unopened sibling must remain physically cold.
     */
    static Set<String> ordinaryReferencePaths(
            Node document,
            Iterable<String> openedScopePaths) {
        Set<String> result = new LinkedHashSet<>();
        Set<String> selectedClosure = openedScopePaths != null
                ? openedScopes(openedScopePaths)
                : null;
        collectOrdinaryReferencePaths(
                document,
                JsonPointer.ROOT,
                false,
                selectedClosure,
                result,
                new IdentityHashMap<Node, Boolean>());
        return result;
    }

    static ResolvedSnapshot forceDeferredResolution(
            ResolvedSnapshot snapshot) {
        ResolvedSnapshot checked = Objects.requireNonNull(
                snapshot, "preservedSnapshot");
        if (!checked.isResolutionComplete()) {
            return checked;
        }
        return checked.isSourceBacked()
                ? ResolvedSnapshot.withSource(
                        checked.frozenSourceRoot(),
                        checked.frozenResolvedRoot(),
                        checked.canonicalTypeIdentities(),
                        false)
                : ResolvedSnapshot.withDeferredResolution(
                        checked.frozenCanonicalRoot(),
                        checked.frozenResolvedRoot(),
                        checked.canonicalTypeIdentities());
    }

    static FrozenNode materializeVerifiedExact(
            ProcessingSnapshotManager manager,
            FrozenNode reference,
            String purpose) {
        return validateMaterializedExact(
                reference,
                manager.materializeVerifiedExactReference(reference),
                purpose);
    }

    /** Validates already-acquired invocation-local exact content. */
    static FrozenNode validateMaterializedExact(
            FrozenNode reference,
            FrozenNode materialized,
            String purpose) {
        if (materialized == null) {
            throw new InvalidExecutionEvidenceException(
                    purpose + " provider returned no content for "
                            + reference.getReferenceBlueId());
        }
        if (materialized.isReferenceOnly()) {
            throw new ProcessorFailureException(
                    ProcessorErrorCategory.InvalidProcessingDocument,
                    purpose
                            + " provider returned a reference instead of exact content for "
                            + reference.getReferenceBlueId());
        }
        if (BlueIds.hasCyclicMemberSeparator(
                reference.getReferenceBlueId())) {
            return materialized;
        }
        Node exact = materialized.toNode();
        final String actualBlueId;
        try {
            actualBlueId = DirectBlueIdCalculator.calculateBlueId(exact);
        } catch (RuntimeException invalidContent) {
            throw new ProcessorFailureException(
                    ProcessorErrorCategory.InvalidProcessingDocument,
                    purpose
                            + " provider content is not exact canonical content for "
                            + reference.getReferenceBlueId(),
                    invalidContent);
        }
        if (!reference.getReferenceBlueId().equals(actualBlueId)) {
            throw new ProcessorFailureException(
                    ProcessorErrorCategory.InvalidProcessingDocument,
                    purpose + " provider content BlueId mismatch: expected "
                            + reference.getReferenceBlueId()
                            + " but calculated " + actualBlueId);
        }
        return FrozenNode.fromNode(exact);
    }

    static Set<String> openedScopes(
            Iterable<String> openedScopePaths) {
        Set<String> scopes = new LinkedHashSet<>();
        scopes.add(JsonPointer.ROOT);
        if (openedScopePaths != null) {
            for (String scopePath : openedScopePaths) {
                scopes.add(PointerUtils.normalizeScope(scopePath));
            }
        }
        return scopes;
    }

    /**
     * Enumerates authored ordinary-node paths without crossing type,
     * contracts, schema, or pure-reference boundaries. Complete subscription
     * projection uses this physical catalog to defer executable fields at all
     * directly present scope candidates before resolving the Root.
     */
    static Set<String> authoredNodePaths(Node document) {
        Set<String> paths = new LinkedHashSet<>();
        collectAuthoredNodePaths(
                document,
                JsonPointer.ROOT,
                paths,
                new IdentityHashMap<Node, Boolean>());
        return paths;
    }

    private static void collectAuthoredNodePaths(
            Node node,
            String path,
            Set<String> result,
            IdentityHashMap<Node, Boolean> visited) {
        if (node == null || visited.put(node, Boolean.TRUE) != null) {
            return;
        }
        try {
            result.add(path);
            if (node.isReferenceOnly()) {
                return;
            }
            if (node.getProperties() != null) {
                for (Map.Entry<String, Node> entry
                        : node.getProperties().entrySet()) {
                    collectAuthoredNodePaths(
                            entry.getValue(),
                            JsonPointer.append(path, entry.getKey()),
                            result,
                            visited);
                }
            }
            if (node.getItems() != null) {
                for (int index = 0; index < node.getItems().size(); index++) {
                    collectAuthoredNodePaths(
                            node.getItems().get(index),
                            JsonPointer.append(path, Integer.toString(index)),
                            result,
                            visited);
                }
            }
        } finally {
            visited.remove(node);
        }
    }

    private static void collectIncludingTypeContracts(
            Node node,
            List<String> path,
            Map<String, List<String>> executableBodyFieldsByType,
            Set<String> result,
            ProcessingSnapshotManager exactMaterializer,
            Set<String> selectedScopes,
            boolean retainPureContractReferences) {
        if (node == null) {
            return;
        }
        collectTypeContracts(
                node.getType(),
                path,
                executableBodyFieldsByType,
                result,
                exactMaterializer,
                new LinkedHashSet<String>(),
                new IdentityHashMap<Node, Boolean>(),
                0,
                selectedScopes,
                retainPureContractReferences);
        collectDirectContracts(
                node,
                path,
                executableBodyFieldsByType,
                result,
                exactMaterializer,
                retainPureContractReferences);
    }

    /**
     * Catalogs executable fields contributed through exact scope-type
     * ancestry. Contract headers may be opened for Phase-C recognition, but
     * declared body fields are only recorded at their effective scope paths.
     */
    private static void collectTypeContracts(
            Node declaredType,
            List<String> path,
            Map<String, List<String>> executableBodyFieldsByType,
            Set<String> result,
            ProcessingSnapshotManager exactMaterializer,
            Set<String> activeReferenceTypes,
            IdentityHashMap<Node, Boolean> activeInlineTypes,
            int depth,
            Set<String> selectedScopes,
            boolean retainPureContractReferences) {
        if (declaredType == null) {
            return;
        }
        long maxTypeEdges = GasSchedule.contracts10().portableLimit(
                GasScheduleConstants.PortableLimit.TYPE_CHAIN_EDGES);
        if (depth >= maxTypeEdges) {
            throw new PortableLimitExceededException(
                    ProcessorErrorCategory.DirectNodeLimitExceeded,
                    GasScheduleConstants.PortableLimit.TYPE_CHAIN_EDGES,
                    depth + 1L,
                    maxTypeEdges);
        }
        if (declaredType.isReferenceOnly()
                && exactMaterializer == null) {
            return;
        }
        Node exactType = declaredType.isReferenceOnly()
                ? materializeVerifiedExact(
                        exactMaterializer,
                        FrozenNode.fromNode(declaredType),
                        "Scope-type executable-header recognition")
                        .toNode()
                : declaredType;
        String referenceIdentity = declaredType.isReferenceOnly()
                ? declaredType.getBlueId()
                : null;
        boolean entered = referenceIdentity != null
                ? activeReferenceTypes.add(referenceIdentity)
                : activeInlineTypes.put(
                        declaredType, Boolean.TRUE) == null;
        if (!entered) {
            throw new MustUnderstandFailureException(
                    "Cyclic type contribution while cataloging executable fields",
                    ProcessorErrorCategory.InvalidContractBinding);
        }
        try {
            collectTypeContracts(
                    exactType.getType(),
                    path,
                    executableBodyFieldsByType,
                    result,
                    exactMaterializer,
                    activeReferenceTypes,
                    activeInlineTypes,
                    depth + 1,
                    selectedScopes,
                    retainPureContractReferences);
            collectDirectContracts(
                    exactType,
                    path,
                    executableBodyFieldsByType,
                    result,
                    exactMaterializer,
                    retainPureContractReferences);
            collectTypeProvidedScopes(
                    exactType,
                    path,
                    executableBodyFieldsByType,
                    result,
                    exactMaterializer,
                    selectedScopes,
                    retainPureContractReferences);
        } finally {
            if (referenceIdentity != null) {
                activeReferenceTypes.remove(referenceIdentity);
            } else {
                activeInlineTypes.remove(declaredType);
            }
        }
    }

    /**
     * Catalogs selected descendants supplied only by a scope type. Effective
     * paths are rebased onto the instance path because resolver limits track
     * the merged document, not the physical path inside the type fragment.
     * Whole unopened descendants are preserved so their structural metadata
     * cannot trigger an unrelated provider read.
     */
    private static void collectTypeProvidedScopes(
            Node typeContribution,
            List<String> scopePath,
            Map<String, List<String>> executableBodyFieldsByType,
            Set<String> result,
            ProcessingSnapshotManager exactMaterializer,
            Set<String> selectedScopes,
            boolean retainPureContractReferences) {
        if (typeContribution == null) {
            return;
        }
        if (typeContribution.getProperties() != null) {
            for (Map.Entry<String, Node> entry
                    : typeContribution.getProperties().entrySet()) {
                List<String> childPath = new ArrayList<>(scopePath);
                childPath.add(entry.getKey());
                collectTypeProvidedScope(
                        entry.getValue(),
                        childPath,
                        executableBodyFieldsByType,
                        result,
                        exactMaterializer,
                        selectedScopes,
                        retainPureContractReferences);
            }
        }
        if (typeContribution.getItems() != null) {
            for (int index = 0;
                    index < typeContribution.getItems().size();
                    index++) {
                List<String> childPath = new ArrayList<>(scopePath);
                childPath.add(Integer.toString(index));
                collectTypeProvidedScope(
                        typeContribution.getItems().get(index),
                        childPath,
                        executableBodyFieldsByType,
                        result,
                        exactMaterializer,
                        selectedScopes,
                        retainPureContractReferences);
            }
        }
    }

    /** Handles one rebased child contributed by an exact scope type. */
    private static void collectTypeProvidedScope(
            Node child,
            List<String> childPath,
            Map<String, List<String>> executableBodyFieldsByType,
            Set<String> result,
            ProcessingSnapshotManager exactMaterializer,
            Set<String> selectedScopes,
            boolean retainPureContractReferences) {
        String effectivePath = JsonPointer.toPointer(childPath);
        if (!participatesInOpenedClosure(
                effectivePath, selectedScopes)) {
            result.add(effectivePath);
            return;
        }
        Node exactChild = child;
        if (child != null && child.isReferenceOnly()
                && exactMaterializer != null) {
            exactChild = materializeVerifiedExact(
                    exactMaterializer,
                    FrozenNode.fromNode(child),
                    "Type-provided selected-scope recognition")
                    .toNode();
        }
        collectIncludingTypeContracts(
                exactChild,
                childPath,
                executableBodyFieldsByType,
                result,
                exactMaterializer,
                selectedScopes,
                retainPureContractReferences);
        collectTypeProvidedScopes(
                exactChild,
                childPath,
                executableBodyFieldsByType,
                result,
                exactMaterializer,
                selectedScopes,
                retainPureContractReferences);
    }

    /** Adds executable paths declared by one exact scope contribution. */
    private static void collectDirectContracts(
            Node node,
            List<String> path,
            Map<String, List<String>> executableBodyFieldsByType,
            Set<String> result,
            ProcessingSnapshotManager exactMaterializer,
            boolean retainPureContractReferences) {
        if (executableBodyFieldsByType == null
                || executableBodyFieldsByType.isEmpty()) {
            return;
        }
        Node contracts = node.getContracts();
        if (contracts != null
                && contracts.isReferenceOnly()
                && exactMaterializer != null) {
            contracts = materializeVerifiedExact(
                    exactMaterializer,
                    FrozenNode.fromNode(contracts),
                    "Contracts-map recognition").toNode();
        }
        if (contracts == null || contracts.getProperties() == null) {
            return;
        }
        for (Map.Entry<String, Node> entry
                : contracts.getProperties().entrySet()) {
            Node contract = entry.getValue();
            if (contract != null
                    && contract.isReferenceOnly()
                    && exactMaterializer != null) {
                if (retainPureContractReferences) {
                    addContractPath(path, entry.getKey(), result);
                    continue;
                }
                contract = materializeVerifiedExact(
                        exactMaterializer,
                        FrozenNode.fromNode(contract),
                        "Contract-header recognition").toNode();
            }
            List<String> fields = executableBodyFieldsByType.get(
                    ExecutableBodyTypeIdentity.fromSource(
                            contract,
                            exactMaterializer));
            if (fields != null) {
                // Event is an exact header matcher, not an executable body.
                // Identity callers include it through the exact-field map.
                if (!retainPureContractReferences) {
                    addEventMatcherPath(
                            contract, path, entry.getKey(), result);
                }
                for (String field : fields) {
                    addBodyPath(path, entry.getKey(), field, result);
                }
            }
        }
    }

    private static void collect(
            FrozenNode node,
            List<String> path,
            Map<String, List<String>> executableBodyFieldsByType,
            Set<String> result,
            CanonicalTypeIdentityLookup canonicalTypeIdentities) {
        if (node == null
                || executableBodyFieldsByType == null
                || executableBodyFieldsByType.isEmpty()) {
            return;
        }
        FrozenNode contracts = node.getContracts();
        if (contracts == null || contracts.getProperties() == null) {
            return;
        }
        for (Map.Entry<String, FrozenNode> entry
                : contracts.getProperties().entrySet()) {
            FrozenNode contract = entry.getValue();
            List<String> fields = executableBodyFieldsByType.get(
                    ExecutableBodyTypeIdentity.fromResolved(
                            contract,
                            canonicalTypeIdentities));
            if (fields != null) {
                addEventMatcherPath(contract, path, entry.getKey(), result);
                for (String field : fields) {
                    addBodyPath(path, entry.getKey(), field, result);
                }
            }
        }
    }

    private static void collectOpaqueCyclicMemberPaths(
            Node node,
            String path,
            Set<String> result,
            IdentityHashMap<Node, Boolean> visited) {
        if (node == null) {
            return;
        }
        if (node.isReferenceOnly()) {
            if (BlueIds.hasCyclicMemberSeparator(node.getBlueId())) {
                result.add(path);
            }
            return;
        }
        if (visited.put(node, Boolean.TRUE) != null) {
            return;
        }
        try {
            if (node.getItems() != null) {
                for (int index = 0; index < node.getItems().size(); index++) {
                    collectOpaqueCyclicMemberPaths(
                            node.getItems().get(index),
                            JsonPointer.append(path, String.valueOf(index)),
                            result,
                            visited);
                }
            }
            if (node.getProperties() != null) {
                for (Map.Entry<String, Node> entry
                        : node.getProperties().entrySet()) {
                    collectOpaqueCyclicMemberPaths(
                            entry.getValue(),
                            JsonPointer.append(path, entry.getKey()),
                            result,
                            visited);
                }
            }
            collectOpaqueCyclicMemberPaths(
                    node.getContracts(),
                    JsonPointer.append(
                            path, ProcessorContractConstants.KEY_CONTRACTS),
                    result,
                    visited);
        } finally {
            visited.remove(node);
        }
    }

    /**
     * Keeps non-structural references physically cold while resolving the
     * selected scope closure's type and contracts-map structure. Structural
     * references outside that closure are cold as well. Once selected
     * structural references have been opened, contract entries and nested
     * header/body values remain deferred for the contract loader to admit on
     * demand.
     */
    private static void collectOrdinaryReferencePaths(
            Node node,
            String path,
            boolean structuralReference,
            Set<String> openedScopePaths,
            Set<String> result,
            IdentityHashMap<Node, Boolean> visited) {
        if (node == null) {
            return;
        }
        if (node.isReferenceOnly()) {
            if (!structuralReference && !JsonPointer.ROOT.equals(path)) {
                result.add(path);
            }
            return;
        }
        if (visited.put(node, Boolean.TRUE) != null) {
            return;
        }
        try {
            boolean selectedStructure = participatesInOpenedClosure(
                    path, openedScopePaths);
            if (!selectedStructure
                    && hasResolutionSensitiveStructure(node)) {
                result.add(path);
                return;
            }
            collectOrdinaryReferencePaths(
                    node.getType(),
                    JsonPointer.append(
                            path, BlueLanguageConstants.OBJECT_TYPE),
                    selectedStructure,
                    openedScopePaths,
                    result,
                    visited);
            collectOrdinaryReferencePaths(
                    node.getContracts(),
                    JsonPointer.append(
                            path, ProcessorContractConstants.KEY_CONTRACTS),
                    selectedStructure,
                    openedScopePaths,
                    result,
                    visited);
            if (node.getProperties() != null) {
                for (Map.Entry<String, Node> entry
                        : node.getProperties().entrySet()) {
                    collectOrdinaryReferencePaths(
                            entry.getValue(),
                            JsonPointer.append(path, entry.getKey()),
                            selectedStructure
                                    && BlueLanguageConstants
                                    .LIST_CONTROL_REPLACE.equals(
                                            entry.getKey()),
                            openedScopePaths,
                            result,
                            visited);
                }
            }
            if (node.getItems() != null) {
                for (int index = 0; index < node.getItems().size(); index++) {
                    collectOrdinaryReferencePaths(
                            node.getItems().get(index),
                            JsonPointer.append(path, Integer.toString(index)),
                            false,
                            openedScopePaths,
                            result,
                            visited);
                }
            }
        } finally {
            visited.remove(node);
        }
    }

    /** Returns whether resolving this node can open structural evidence. */
    private static boolean hasResolutionSensitiveStructure(Node node) {
        if (node.getType() != null
                || node.getItemType() != null
                || node.getKeyType() != null
                || node.getValueType() != null
                || node.getSchema() != null
                || isReference(node.getContracts())) {
            return true;
        }
        Node replacement = node.getProperties() != null
                ? node.getProperties().get(
                        BlueLanguageConstants.LIST_CONTROL_REPLACE)
                : null;
        return isReference(replacement);
    }

    private static boolean isReference(Node node) {
        return node != null && node.isReferenceOnly();
    }

    /** Returns whether a path is selected or is an ancestor of a selection. */
    private static boolean participatesInOpenedClosure(
            String path,
            Set<String> openedScopePaths) {
        if (openedScopePaths == null) {
            return true;
        }
        String normalizedPath = PointerUtils.normalizeScope(path);
        for (String openedScopePath : openedScopePaths) {
            if (PointerUtils.descendantOrEqual(
                    PointerUtils.normalizeScope(openedScopePath),
                    normalizedPath)) {
                return true;
            }
        }
        return false;
    }

    private static void addEventMatcherPath(
            Node contract,
            List<String> scopePath,
            String contractKey,
            Set<String> result) {
        if (contract != null
                && contract.getProperties() != null
                && contract.getProperties().containsKey(
                EffectiveContractSnapshotConstants.DispatchField.EVENT)) {
            addBodyPath(
                    scopePath,
                    contractKey,
                    EffectiveContractSnapshotConstants.DispatchField.EVENT,
                    result);
        }
    }

    private static void addEventMatcherPath(
            FrozenNode contract,
            List<String> scopePath,
            String contractKey,
            Set<String> result) {
        if (contract != null
                && contract.getProperties() != null
                && contract.getProperties().containsKey(
                EffectiveContractSnapshotConstants.DispatchField.EVENT)) {
            addBodyPath(
                    scopePath,
                    contractKey,
                    EffectiveContractSnapshotConstants.DispatchField.EVENT,
                    result);
        }
    }

    private static void addBodyPath(
            List<String> scopePath,
            String contractKey,
            String field,
            Set<String> result) {
        List<String> bodyPath = new ArrayList<>(scopePath);
        bodyPath.add(ProcessorContractConstants.KEY_CONTRACTS);
        bodyPath.add(contractKey);
        bodyPath.add(field);
        result.add(JsonPointer.toPointer(bodyPath));
    }

    private static void addContractPath(
            List<String> scopePath,
            String contractKey,
            Set<String> result) {
        List<String> contractPath = new ArrayList<>(scopePath);
        contractPath.add(ProcessorContractConstants.KEY_CONTRACTS);
        contractPath.add(contractKey);
        result.add(JsonPointer.toPointer(contractPath));
    }

}
