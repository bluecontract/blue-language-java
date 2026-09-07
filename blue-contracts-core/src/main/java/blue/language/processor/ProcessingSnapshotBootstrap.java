package blue.language.processor;

import blue.language.identity.CanonicalTypeIdentityLookup;
import blue.language.model.Node;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.processor.util.ProcessorContractConstants;
import blue.language.snapshot.FrozenNode;
import blue.language.merge.ResolvedSnapshot;
import blue.language.model.wire.JsonPointer;
import blue.language.model.NodePathEditor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Establishes a processor snapshot without opening cold executable bodies. */
final class ProcessingSnapshotBootstrap {

    private ProcessingSnapshotBootstrap() {
    }

    static Map<String, List<String>> immutableExecutableBodyFields(
            Map<String, List<String>> fieldsByType) {
        if (fieldsByType == null || fieldsByType.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, List<String>> immutable = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : fieldsByType.entrySet()) {
            immutable.put(
                    entry.getKey(),
                    Collections.unmodifiableList(
                            new ArrayList<>(entry.getValue())));
        }
        return Collections.unmodifiableMap(immutable);
    }

    static ResolvedSnapshot prepare(
            ResolvedSnapshot snapshot,
            Map<String, List<String>> executableBodyFieldsByType,
            ProcessingObserver observer) {
        ProcessingObservations.record(
                observer,
                snapshot.hasCanonicalIdentity()
                        && snapshot.frozenCanonicalRoot()
                                .isStrictBlueIdValidation()
                        ? ProcessingMetricId.PROCESSOR_INPUT_STRICT_CANONICAL
                        : ProcessingMetricId.PROCESSOR_INPUT_UNCHECKED_CANONICAL,
                1L);
        if (snapshot.hasCanonicalIdentity()) {
            // Record the input's actual provenance above, then enforce strict
            // reference syntax before any executable body can be selected.
            snapshot = snapshot.toStrictBlueIdValidatedCanonical();
        }
        Map<String, FrozenNode> preservedBodies =
                initialExecutableBodyOverlays(
                        snapshot.frozenSourceRoot(),
                        snapshot.frozenResolvedRoot(),
                        executableBodyFieldsByType,
                        snapshot.canonicalTypeIdentities());
        if (preservedBodies.isEmpty()) {
            return snapshot;
        }
        Node deferredResolved = snapshot.resolvedRoot();
        for (Map.Entry<String, FrozenNode> preserved
                : preservedBodies.entrySet()) {
            NodePathEditor.put(
                    deferredResolved,
                    preserved.getKey(),
                    preserved.getValue().toNode());
        }
        FrozenNode deferred = FrozenNode.fromResolvedNode(deferredResolved);
        if (snapshot.isSourceBacked()) {
            return ResolvedSnapshot.withDeferredSource(
                    snapshot.frozenSourceRoot(),
                    deferred,
                    snapshot.canonicalTypeIdentities());
        }
        return ResolvedSnapshot.withDeferredResolution(
                snapshot.frozenCanonicalRoot(),
                deferred,
                snapshot.canonicalTypeIdentities());
    }

    private static Map<String, FrozenNode> initialExecutableBodyOverlays(
            FrozenNode canonicalRoot,
            FrozenNode resolvedRoot,
            Map<String, List<String>> executableBodyFieldsByType,
            CanonicalTypeIdentityLookup typeIdentities) {
        if (canonicalRoot == null
                || resolvedRoot == null
                || executableBodyFieldsByType == null
                || executableBodyFieldsByType.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, FrozenNode> result = new LinkedHashMap<>();
        Deque<String> pending = new ArrayDeque<>();
        Set<String> visited = new LinkedHashSet<>();
        pending.add(JsonPointer.ROOT);
        while (!pending.isEmpty()) {
            String scopePath = pending.removeFirst();
            if (!visited.add(scopePath)) {
                continue;
            }
            try {
                ImmutablePatchPlanner.forFrozen(canonicalRoot)
                        .validateProcessEmbeddedTraversalPath(scopePath);
            } catch (ProcessorFailureException opaqueBoundary) {
                continue;
            }
            FrozenNode selectedScope = canonicalRoot.at(scopePath);
            FrozenNode effectiveScope = resolvedRoot.at(scopePath);
            collectExecutableBodies(
                    scopePath,
                    selectedScope,
                    effectiveScope,
                    executableBodyFieldsByType,
                    typeIdentities,
                    result);
            collectEmbeddedScopes(
                    scopePath,
                    effectiveScope,
                    pending,
                    visited,
                    typeIdentities);
        }
        return result;
    }

    private static void collectExecutableBodies(
            String scopePath,
            FrozenNode selectedScope,
            FrozenNode effectiveScope,
            Map<String, List<String>> executableBodyFieldsByType,
            CanonicalTypeIdentityLookup typeIdentities,
            Map<String, FrozenNode> result) {
        FrozenNode selectedContracts = selectedScope != null
                ? selectedScope.getContracts()
                : null;
        FrozenNode effectiveContracts = effectiveScope != null
                ? effectiveScope.getContracts()
                : null;
        Map<String, FrozenNode> entries = effectiveContracts != null
                ? effectiveContracts.getProperties()
                : null;
        if (entries == null) {
            return;
        }
        for (Map.Entry<String, FrozenNode> entry : entries.entrySet()) {
            FrozenNode effectiveContract = entry.getValue();
            FrozenNode selectedContract = selectedContracts != null
                    ? selectedContracts.property(entry.getKey())
                    : null;
            List<String> fields = executableBodyFieldsByType.get(
                    exactTypeBlueId(selectedContract, typeIdentities));
            if (fields == null) {
                fields = executableBodyFieldsByType.get(
                        exactTypeBlueId(effectiveContract, typeIdentities));
            }
            if (fields == null || fields.isEmpty()) {
                continue;
            }
            String contractPath = contractPath(scopePath, entry.getKey());
            if (selectedContract != null
                    && selectedContract.isReferenceOnly()) {
                result.put(contractPath, selectedContract);
                continue;
            }
            for (String field : fields) {
                String bodyPath = contractPath + "/"
                        + JsonPointer.escape(field);
                FrozenNode exactBody = selectedContract != null
                        ? selectedContract.property(field)
                        : null;
                if (exactBody != null) {
                    result.put(bodyPath, exactBody);
                    continue;
                }
                FrozenNode effectiveBody = effectiveContract != null
                        ? effectiveContract.property(field)
                        : null;
                String retainedReference = effectiveBody != null
                        ? effectiveBody.getReferenceBlueId()
                        : null;
                if (retainedReference != null) {
                    result.put(
                            bodyPath,
                            FrozenNode.fromNode(
                                    new Node().blueId(retainedReference)));
                }
            }
        }
    }

    private static void collectEmbeddedScopes(
            String scopePath,
            FrozenNode effectiveScope,
            Deque<String> pending,
            Set<String> visited,
            CanonicalTypeIdentityLookup typeIdentities) {
        EmbeddedScopePlan plan = embeddedScopePlanIfAvailable(
                effectiveScope, scopePath, typeIdentities);
        if (plan == null) {
            return;
        }
        for (String childPath : plan.concreteChildPaths()) {
            if (!childPath.equals(scopePath)
                    && !visited.contains(childPath)) {
                pending.addLast(childPath);
            }
        }
    }

    static EmbeddedScopePlan embeddedScopePlan(
            FrozenNode effectiveScope,
            String scopePath,
            ProcessingSnapshotManager snapshotManager,
            CanonicalTypeIdentityLookup typeIdentities) {
        FrozenNode embedded = processEmbeddedContract(
                effectiveScope, typeIdentities);
        if (embedded == null) {
            return null;
        }
        List<String> explicit = embeddedDeclarations(
                embedded,
                ProcessorContractConstants.KEY_PATHS,
                scopePath,
                ProcessorErrorCategory.InvalidRuntimePointer);
        List<String> collections = embeddedDeclarations(
                embedded,
                ProcessorContractConstants.KEY_COLLECTION_PATHS,
                scopePath,
                ProcessorErrorCategory.InvalidEmbeddedCollectionPath);
        EmbeddedScopePlanner planner = snapshotManager != null
                ? new EmbeddedScopePlanner(
                        snapshotManager,
                        typeIdentities)
                : new EmbeddedScopePlanner();
        return planner.plan(
                effectiveScope,
                scopePath,
                explicit,
                collections,
                GasSchedule.contracts10());
    }

    static EmbeddedScopePlan embeddedScopePlanIfAvailable(
            FrozenNode effectiveScope,
            String scopePath,
            CanonicalTypeIdentityLookup typeIdentities) {
        try {
            return embeddedScopePlan(
                    effectiveScope,
                    scopePath,
                    null,
                    typeIdentities);
        } catch (SubscriptionSurfaceInvalidException
                | PortableLimitExceededException
                | ExecutionEvidenceUnavailableException
                | InvalidExecutionEvidenceException unavailablePlan) {
            /*
             * Admission and boundary preflight own these diagnostics. Snapshot
             * bootstrapping and protected-state comparison must not change the
             * public failure selected for the same malformed input.
             */
            return null;
        }
    }

    private static FrozenNode processEmbeddedContract(
            FrozenNode scope,
            CanonicalTypeIdentityLookup typeIdentities) {
        FrozenNode contracts = scope != null ? scope.getContracts() : null;
        FrozenNode embedded = contracts != null
                ? contracts.property(
                        ProcessorContractConstants.KEY_EMBEDDED)
                : null;
        return isProcessEmbeddedContract(embedded, typeIdentities)
                ? embedded
                : null;
    }

    static boolean isProcessEmbeddedContract(
            FrozenNode contract,
            CanonicalTypeIdentityLookup typeIdentities) {
        return RuntimeBlueIds.PROCESS_EMBEDDED.equals(
                exactTypeBlueId(contract, typeIdentities));
    }

    private static List<String> embeddedDeclarations(
            FrozenNode embedded,
            String field,
            String scopePath,
            ProcessorErrorCategory category) {
        FrozenNode declarations = embedded.property(field);
        if (declarations == null || declarations.isEmptyNode()) {
            return Collections.emptyList();
        }
        List<FrozenNode> items = declarations.getItems();
        if (items == null) {
            if (isUnpopulatedDeclarationDefinition(declarations)) {
                return Collections.emptyList();
            }
            throw invalidEmbeddedDeclaration(
                    field + " must be a List", scopePath, category);
        }
        List<String> result = new ArrayList<>(items.size());
        for (FrozenNode item : items) {
            Object value = item != null ? item.getValue() : null;
            if (!(value instanceof String)) {
                throw invalidEmbeddedDeclaration(
                        field + " entries must be Text",
                        scopePath,
                        category);
            }
            result.add((String) value);
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * Distinguishes an optional field inherited from the Process Embedded
     * type definition from an authored value. Resolution retains the field's
     * List schema even when the instance omits that optional declaration.
     */
    private static boolean isUnpopulatedDeclarationDefinition(
            FrozenNode declarations) {
        /*
         * Language 1.0 §4.1 and §9.2.3 define type/schema/name/
         * description-only nodes as metadata-only, not semantically present.
         * Those fields therefore do not distinguish an inherited optional
         * declaration from an authored metadata refinement.
         */
        return declarations.getValue() == null
                && declarations.getProperties() == null
                && declarations.getContracts() == null
                && declarations.getReferenceBlueId() == null
                && declarations.getBlue() == null
                && declarations.getPreviousBlueId() == null
                && declarations.getPosition() == null;
    }

    private static SubscriptionSurfaceInvalidException invalidEmbeddedDeclaration(
            String message,
            String scopePath,
            ProcessorErrorCategory category) {
        return new SubscriptionSurfaceInvalidException(
                "Process Embedded " + message,
                scopePath,
                ProcessorContractConstants.KEY_EMBEDDED,
                category);
    }

    private static String contractPath(String scopePath, String contractKey) {
        List<String> path = new ArrayList<>(JsonPointer.split(scopePath));
        path.add(ProcessorContractConstants.KEY_CONTRACTS);
        path.add(contractKey);
        return JsonPointer.toPointer(path);
    }

    private static String exactTypeBlueId(
            FrozenNode contract,
            CanonicalTypeIdentityLookup typeIdentities) {
        if (contract == null || contract.getType() == null) {
            return null;
        }
        return CanonicalIdentityEvidence.resolvedTypeBlueId(
                contract.getType(),
                typeIdentities,
                "Processing snapshot contract type");
    }
}
