package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.processor.util.PointerUtils;
import blue.language.processor.util.ProcessorContractConstants;
import blue.language.snapshot.FrozenNode;
import blue.language.snapshot.ResolvedSnapshot;
import blue.language.utils.JsonPointer;
import blue.language.utils.NodePathEditor;

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
                snapshot.frozenCanonicalRoot().isStrictBlueIdValidation()
                        ? ProcessingMetricId.PROCESSOR_INPUT_STRICT_CANONICAL
                        : ProcessingMetricId.PROCESSOR_INPUT_UNCHECKED_CANONICAL,
                1L);
        Map<String, FrozenNode> preservedBodies =
                initialExecutableBodyOverlays(
                        snapshot.frozenCanonicalRoot(),
                        snapshot.frozenResolvedRoot(),
                        executableBodyFieldsByType);
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
        return ResolvedSnapshot.withDeferredResolution(
                snapshot.frozenCanonicalRoot(),
                FrozenNode.fromResolvedNode(deferredResolved));
    }

    private static Map<String, FrozenNode> initialExecutableBodyOverlays(
            FrozenNode canonicalRoot,
            FrozenNode resolvedRoot,
            Map<String, List<String>> executableBodyFieldsByType) {
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
                    result);
            collectEmbeddedScopes(
                    scopePath, effectiveScope, pending, visited);
        }
        return result;
    }

    private static void collectExecutableBodies(
            String scopePath,
            FrozenNode selectedScope,
            FrozenNode effectiveScope,
            Map<String, List<String>> executableBodyFieldsByType,
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
                    exactTypeBlueId(selectedContract));
            if (fields == null) {
                fields = executableBodyFieldsByType.get(
                        exactTypeBlueId(effectiveContract));
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
            Set<String> visited) {
        FrozenNode contracts = effectiveScope != null
                ? effectiveScope.getContracts()
                : null;
        Map<String, FrozenNode> entries = contracts != null
                ? contracts.getProperties()
                : null;
        if (entries == null) {
            return;
        }
        for (FrozenNode contract : entries.values()) {
            if (!RuntimeBlueIds.PROCESS_EMBEDDED.equals(
                    exactTypeBlueId(contract))) {
                continue;
            }
            FrozenNode paths = contract != null
                    ? contract.property(ProcessorContractConstants.KEY_PATHS)
                    : null;
            List<FrozenNode> items = paths != null ? paths.getItems() : null;
            if (items == null) {
                continue;
            }
            for (FrozenNode item : items) {
                Object value = item != null ? item.getValue() : null;
                if (!(value instanceof String)) {
                    continue;
                }
                try {
                    String child = PointerUtils.resolvePointer(
                            scopePath,
                            PointerUtils.assertValidRuntimePointer(
                                    (String) value));
                    if (!child.equals(scopePath)
                            && !visited.contains(child)) {
                        pending.addLast(child);
                    }
                } catch (IllegalArgumentException ignored) {
                    // Runtime preflight owns malformed embedded-path diagnostics.
                }
            }
        }
    }

    private static String contractPath(String scopePath, String contractKey) {
        List<String> path = new ArrayList<>(JsonPointer.split(scopePath));
        path.add(ProcessorContractConstants.KEY_CONTRACTS);
        path.add(contractKey);
        return JsonPointer.toPointer(path);
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
