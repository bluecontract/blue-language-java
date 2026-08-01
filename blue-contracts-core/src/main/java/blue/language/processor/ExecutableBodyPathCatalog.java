package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.util.PointerUtils;
import blue.language.processor.util.ProcessorContractConstants;
import blue.language.snapshot.FrozenNode;
import blue.language.merge.ResolvedSnapshot;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.utils.BlueIds;
import blue.language.model.wire.JsonPointer;
import blue.language.utils.NodePathEditor;

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
        Set<String> result = new LinkedHashSet<>();
        for (String scopePath : openedScopes(openedScopePaths)) {
            Node scope = JsonPointer.ROOT.equals(scopePath)
                    ? document
                    : NodePathEditor.getOrNull(document, scopePath);
            collect(
                    scope,
                    JsonPointer.split(scopePath),
                    executableBodyFieldsByType,
                    result,
                    exactMaterializer);
        }
        return result;
    }

    static Set<String> fromFrozen(
            FrozenNode document,
            Iterable<String> openedScopePaths,
            Map<String, List<String>> executableBodyFieldsByType) {
        Set<String> result = new LinkedHashSet<>();
        for (String scopePath : openedScopes(openedScopePaths)) {
            FrozenNode scope = document != null
                    ? document.at(scopePath)
                    : null;
            collect(
                    scope,
                    JsonPointer.split(scopePath),
                    executableBodyFieldsByType,
                    result);
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
        Set<String> preserved = fromNode(
                document,
                openedScopePaths,
                executableBodyFieldsByType,
                checkedManager);
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

    static ResolvedSnapshot forceDeferredResolution(
            ResolvedSnapshot snapshot) {
        ResolvedSnapshot checked = Objects.requireNonNull(
                snapshot, "preservedSnapshot");
        if (!checked.isResolutionComplete()) {
            return checked;
        }
        return ResolvedSnapshot.withDeferredResolution(
                checked.frozenCanonicalRoot(),
                checked.frozenResolvedRoot());
    }

    static FrozenNode materializeVerifiedExact(
            ProcessingSnapshotManager manager,
            FrozenNode reference,
            String purpose) {
        FrozenNode materialized = manager.materializeVerifiedExactReference(
                reference);
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

    private static void collect(
            Node node,
            List<String> path,
            Map<String, List<String>> executableBodyFieldsByType,
            Set<String> result,
            ProcessingSnapshotManager exactMaterializer) {
        if (node == null
                || executableBodyFieldsByType == null
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
                contract = materializeVerifiedExact(
                        exactMaterializer,
                        FrozenNode.fromNode(contract),
                        "Contract-header recognition").toNode();
            }
            List<String> fields = executableBodyFieldsByType.get(
                    exactTypeBlueId(contract));
            if (fields != null) {
                addEventMatcherPath(contract, path, entry.getKey(), result);
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
            Set<String> result) {
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
                    exactTypeBlueId(contract));
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
        if (node == null || visited.put(node, Boolean.TRUE) != null) {
            return;
        }
        if (node.isReferenceOnly()) {
            if (BlueIds.hasCyclicMemberSeparator(node.getBlueId())) {
                result.add(path);
            }
            return;
        }
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

    private static String exactTypeBlueId(Node contract) {
        if (contract == null || contract.getType() == null) {
            return null;
        }
        Node type = contract.getType();
        return type.getBlueId() != null
                ? type.getBlueId()
                : DirectBlueIdCalculator.calculateBlueId(type);
    }

    private static String exactTypeBlueId(FrozenNode contract) {
        if (contract == null || contract.getType() == null) {
            return null;
        }
        FrozenNode type = contract.getType();
        return type.getReferenceBlueId() != null
                ? type.getReferenceBlueId()
                : type.blueId();
    }
}
