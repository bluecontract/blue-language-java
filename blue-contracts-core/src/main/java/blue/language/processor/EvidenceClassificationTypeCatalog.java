package blue.language.processor;

import blue.language.model.Node;
import blue.language.model.wire.BlueLanguageConstants;
import blue.language.model.wire.JsonPointer;
import blue.language.processor.util.PointerUtils;
import blue.language.processor.util.ProcessorContractConstants;
import blue.language.snapshot.FrozenNode;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Catalogs nominal-type paths that Phase-B classification must preserve.
 *
 * <p>The catalog opens only exact type headers and selected descendant spines.
 * It records unselected inherited branches as cold physical paths so the
 * classification snapshot can retain source identity without traversing
 * unrelated provider content.</p>
 */
final class EvidenceClassificationTypeCatalog {

    private final ProcessorInvocationServices owner;

    EvidenceClassificationTypeCatalog(ProcessorInvocationServices owner) {
        this.owner = owner;
    }

    /**
     * Returns whether a nominal type supplies the next segment of an
     * evidence-selected descendant that is absent from authored syntax.
     */
    boolean retainsSelectedDescendantSpine(
            Node declaredType,
            String scopePath,
            Set<String> selectedScopes) {
        Set<String> requiredSegments = immediateSelectedDescendantSegments(
                scopePath, selectedScopes);
        return !requiredSegments.isEmpty()
                && typeProvidesAnySegment(
                        declaredType,
                        requiredSegments,
                        new LinkedHashSet<String>(),
                        0);
    }

    /**
     * Records inherited contract entries that must remain authored and cold
     * while the nominal scope type stays intact for source binding.
     */
    void collectInheritedColdContractPaths(
            Node scope,
            String scopePath,
            Map<String, Set<String>> selectedKeys,
            Set<String> preserved,
            Set<String> activeTypes) {
        if (scope == null || scope.isReferenceOnly()) {
            return;
        }
        collectTypeColdContractPaths(
                scope.getType(),
                scopePath,
                PointerUtils.appendPointer(
                        scopePath,
                        BlueLanguageConstants.OBJECT_TYPE),
                selectedKeys,
                preserved,
                activeTypes,
                0);
        if (scope.getProperties() != null) {
            for (Map.Entry<String, Node> entry
                    : scope.getProperties().entrySet()) {
                String childPath = PointerUtils.appendPointer(
                        scopePath, entry.getKey());
                if (participatesInSelectedClosure(
                        childPath, selectedKeys.keySet())) {
                    collectInheritedColdContractPaths(
                            entry.getValue(),
                            childPath,
                            selectedKeys,
                            preserved,
                            activeTypes);
                }
            }
        }
        if (scope.getItems() != null) {
            for (int index = 0; index < scope.getItems().size(); index++) {
                String childPath = PointerUtils.appendPointer(
                        scopePath, Integer.toString(index));
                if (participatesInSelectedClosure(
                        childPath, selectedKeys.keySet())) {
                    collectInheritedColdContractPaths(
                            scope.getItems().get(index),
                            childPath,
                            selectedKeys,
                            preserved,
                            activeTypes);
                }
            }
        }
    }

    /** Returns first relative segments of strict selected descendants. */
    private Set<String> immediateSelectedDescendantSegments(
            String scopePath,
            Set<String> selectedScopes) {
        List<String> scopeSegments = JsonPointer.split(
                ProcessorEngine.normalizeScope(scopePath));
        Set<String> result = new LinkedHashSet<>();
        for (String selectedScope : selectedScopes) {
            List<String> selectedSegments = JsonPointer.split(
                    ProcessorEngine.normalizeScope(selectedScope));
            if (selectedSegments.size() <= scopeSegments.size()
                    || !selectedSegments.subList(
                    0, scopeSegments.size()).equals(scopeSegments)) {
                continue;
            }
            result.add(selectedSegments.get(scopeSegments.size()));
        }
        return result;
    }

    /** Walks exact type ancestry until one required child is contributed. */
    private boolean typeProvidesAnySegment(
            Node declaredType,
            Set<String> requiredSegments,
            Set<String> activeTypes,
            int depth) {
        if (declaredType == null) {
            return false;
        }
        checkTypeDepth(depth);
        ProcessingSnapshotManager manager = owner.snapshotManager();
        if (declaredType.isReferenceOnly() && manager == null) {
            return true;
        }
        Node exactType = exactContent(declaredType, manager);
        String identity = typeIdentity(declaredType);
        enterType(identity, activeTypes);
        try {
            if (providesAnyDirectSegment(exactType, requiredSegments)) {
                return true;
            }
            return typeProvidesAnySegment(
                    exactType.getType(),
                    requiredSegments,
                    activeTypes,
                    depth + 1);
        } finally {
            activeTypes.remove(identity);
        }
    }

    /** Checks object-property and concrete list-item contributions. */
    private boolean providesAnyDirectSegment(
            Node typeContribution,
            Set<String> requiredSegments) {
        if (typeContribution.getProperties() != null) {
            for (String segment : requiredSegments) {
                if (typeContribution.getProperties().containsKey(segment)) {
                    return true;
                }
            }
        }
        if (typeContribution.getItems() == null) {
            return false;
        }
        for (String segment : requiredSegments) {
            try {
                int index = Integer.parseInt(segment);
                if (index >= 0
                        && index < typeContribution.getItems().size()) {
                    return true;
                }
            } catch (NumberFormatException ignored) {
                // An object key cannot select a concrete list contribution.
            }
        }
        return false;
    }

    /** Walks exact type headers without opening contract-entry references. */
    private void collectTypeColdContractPaths(
            Node declaredType,
            String scopePath,
            String declaredTypePath,
            Map<String, Set<String>> selectedKeys,
            Set<String> preserved,
            Set<String> activeTypes,
            int depth) {
        if (declaredType == null) {
            return;
        }
        checkTypeDepth(depth);
        ProcessingSnapshotManager manager = owner.snapshotManager();
        if (declaredType.isReferenceOnly() && manager == null) {
            return;
        }
        Node exactType = exactContent(declaredType, manager);
        String identity = typeIdentity(declaredType);
        enterType(identity, activeTypes);
        try {
            collectTypeColdContractPaths(
                    exactType.getType(),
                    scopePath,
                    PointerUtils.appendPointer(
                            declaredTypePath,
                            BlueLanguageConstants.OBJECT_TYPE),
                    selectedKeys,
                    preserved,
                    activeTypes,
                    depth + 1);
            addUnselectedContractPaths(
                    exactType.getContracts(),
                    scopePath,
                    declaredTypePath,
                    selectedKeys,
                    preserved);
            collectTypeProvidedDescendantPaths(
                    exactType,
                    scopePath,
                    selectedKeys,
                    preserved);
        } finally {
            activeTypes.remove(identity);
        }
    }

    /** Traverses only type-provided branches on the selected scope spine. */
    private void collectTypeProvidedDescendantPaths(
            Node typeContribution,
            String scopePath,
            Map<String, Set<String>> selectedKeys,
            Set<String> preserved) {
        if (typeContribution.getProperties() != null) {
            for (Map.Entry<String, Node> entry
                    : typeContribution.getProperties().entrySet()) {
                collectTypeProvidedProperty(
                        entry.getValue(),
                        PointerUtils.appendPointer(
                                scopePath, entry.getKey()),
                        selectedKeys,
                        preserved);
            }
        }
        if (typeContribution.getItems() != null) {
            for (int index = 0;
                    index < typeContribution.getItems().size();
                    index++) {
                collectTypeProvidedProperty(
                        typeContribution.getItems().get(index),
                        PointerUtils.appendPointer(
                                scopePath, Integer.toString(index)),
                        selectedKeys,
                        preserved);
            }
        }
    }

    /** Preserves a cold branch or continues through a selected branch. */
    private void collectTypeProvidedProperty(
            Node value,
            String childPath,
            Map<String, Set<String>> selectedKeys,
            Set<String> preserved) {
        if (participatesInSelectedClosure(
                childPath, selectedKeys.keySet())) {
            collectTypeProvidedScopePaths(
                    value,
                    childPath,
                    selectedKeys,
                    preserved);
        } else {
            preserved.add(childPath);
        }
    }

    /** Catalogs one selected descendant authored by a type contribution. */
    private void collectTypeProvidedScopePaths(
            Node selectedScope,
            String scopePath,
            Map<String, Set<String>> selectedKeys,
            Set<String> preserved) {
        if (selectedScope == null) {
            return;
        }
        ProcessingSnapshotManager manager = owner.snapshotManager();
        if (selectedScope.isReferenceOnly() && manager == null) {
            return;
        }
        Node exactScope = exactContent(selectedScope, manager);
        addUnselectedContractPaths(
                exactScope.getContracts(),
                scopePath,
                null,
                selectedKeys,
                preserved);
        collectTypeColdContractPaths(
                exactScope.getType(),
                scopePath,
                PointerUtils.appendPointer(
                        scopePath,
                        BlueLanguageConstants.OBJECT_TYPE),
                selectedKeys,
                preserved,
                new LinkedHashSet<String>(),
                0);
        collectTypeProvidedDescendantPaths(
                exactScope,
                scopePath,
                selectedKeys,
                preserved);
    }

    /** Adds effective paths for inherited entries outside the retained set. */
    private void addUnselectedContractPaths(
            Node contracts,
            String scopePath,
            String declaredTypePath,
            Map<String, Set<String>> selectedKeys,
            Set<String> preserved) {
        if (contracts == null) {
            return;
        }
        ProcessingSnapshotManager manager = owner.snapshotManager();
        if (contracts.isReferenceOnly() && manager == null) {
            return;
        }
        Node exactContracts = exactContent(contracts, manager);
        if (exactContracts.getProperties() == null) {
            return;
        }
        Set<String> selected = selectedKeys.getOrDefault(
                ProcessorEngine.normalizeScope(scopePath),
                Collections.emptySet());
        boolean includeProcessEmbedded = requiresEmbeddedRouting(
                scopePath, selectedKeys.keySet());
        String contractsPath = PointerUtils.appendPointer(
                scopePath, ProcessorContractConstants.KEY_CONTRACTS);
        String declaredContractsPath = declaredTypePath != null
                ? PointerUtils.appendPointer(
                        declaredTypePath,
                        ProcessorContractConstants.KEY_CONTRACTS)
                : null;
        for (String key : exactContracts.getProperties().keySet()) {
            if (!selected.contains(key)
                    && !(includeProcessEmbedded
                    && ProcessorContractConstants.KEY_EMBEDDED.equals(key))) {
                preserved.add(PointerUtils.appendPointer(
                        contractsPath, key));
                if (declaredContractsPath != null) {
                    preserved.add(PointerUtils.appendPointer(
                            declaredContractsPath, key));
                }
            }
        }
    }

    /** Materializes a reference exactly or reuses its authored content. */
    private Node exactContent(
            Node value,
            ProcessingSnapshotManager manager) {
        if (!value.isReferenceOnly()) {
            return value;
        }
        FrozenNode materialized = manager.materializeVerifiedExactReference(
                FrozenNode.fromNode(value));
        if (materialized != null) {
            return materialized.toNode();
        }
        throw new InvalidExecutionEvidenceException(
                "Exact Phase-B classification content was not found for "
                        + value.getBlueId());
    }

    /** Enforces the portable type-ancestry edge budget. */
    private void checkTypeDepth(int depth) {
        long maximumTypeEdges = GasSchedule.contracts10().portableLimit(
                GasScheduleConstants.PortableLimit.TYPE_CHAIN_EDGES);
        if (depth >= maximumTypeEdges) {
            throw new PortableLimitExceededException(
                    ProcessorErrorCategory.DirectNodeLimitExceeded,
                    GasScheduleConstants.PortableLimit.TYPE_CHAIN_EDGES,
                    depth + 1L,
                    maximumTypeEdges);
        }
    }

    /** Returns the stable identity used by the active-ancestry guard. */
    private String typeIdentity(Node declaredType) {
        return CanonicalIdentityEvidence.sourceTypeBlueId(
                declaredType,
                owner.snapshotManager(),
                "Phase-B type ancestry");
    }

    /** Rejects a type already active in the current ancestry. */
    private void enterType(String identity, Set<String> activeTypes) {
        if (!activeTypes.add(identity)) {
            throw new InvalidExecutionEvidenceException(
                    "Cyclic scope type hierarchy in Phase-B classification: "
                            + identity);
        }
    }

    /** Returns whether a path is selected or an ancestor of a selection. */
    private boolean participatesInSelectedClosure(
            String path,
            Set<String> selectedScopes) {
        String normalizedPath = ProcessorEngine.normalizeScope(path);
        for (String selectedScope : selectedScopes) {
            if (PointerUtils.descendantOrEqual(
                    ProcessorEngine.normalizeScope(selectedScope),
                    normalizedPath)) {
                return true;
            }
        }
        return false;
    }

    /** Returns whether a descendant selection needs embedded routing. */
    private boolean requiresEmbeddedRouting(
            String scopePath,
            Set<String> selectedScopes) {
        String normalized = ProcessorEngine.normalizeScope(scopePath);
        for (String selectedScope : selectedScopes) {
            String selected = ProcessorEngine.normalizeScope(selectedScope);
            if (!selected.equals(normalized)
                    && PointerUtils.descendantOrEqual(selected, normalized)) {
                return true;
            }
        }
        return false;
    }
}
