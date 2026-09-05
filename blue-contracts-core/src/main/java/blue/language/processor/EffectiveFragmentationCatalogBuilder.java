package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.model.Contract;
import blue.language.processor.model.HandlerContract;
import blue.language.processor.util.PointerUtils;
import blue.language.processor.util.ProcessorContractConstants;
import blue.language.processor.util.ProcessorPointerConstants;
import blue.language.snapshot.FrozenNode;
import blue.language.merge.ResolvedSnapshot;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.identity.BlueIds;
import blue.language.model.wire.JsonPointer;
import blue.language.model.NodePathEditor;
import blue.language.model.Nodes;
import blue.language.mapping.TypeClassResolver;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Header-only catalog construction. This class deliberately stays outside the
 * semantic processor execution path and never owns a GasMeter.
 */
final class EffectiveFragmentationCatalogBuilder {

    private final ContractLoader contractLoader;
    private final ContractProcessorRegistry registry;
    private final TypeClassResolver typeResolver;
    private final ProcessingSnapshotManager snapshotManager;
    private final GasSchedule limits;

    EffectiveFragmentationCatalogBuilder(
            ContractLoader contractLoader,
            ContractProcessorRegistry registry,
            TypeClassResolver typeResolver,
            ProcessingSnapshotManager snapshotManager,
            GasSchedule limits) {
        this.contractLoader =
                Objects.requireNonNull(contractLoader, "contractLoader");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.typeResolver =
                Objects.requireNonNull(typeResolver, "typeResolver");
        this.snapshotManager =
                Objects.requireNonNull(snapshotManager, "snapshotManager");
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    EffectiveFragmentationCatalog build(Node suppliedRoot) {
        Objects.requireNonNull(suppliedRoot, "document");
        ProcessingSnapshotManager sequence =
                snapshotManager.transientSequence();
        try {
            ProcessingInputAdmission admission =
                    new ProcessingInputAdmission(sequence);
            ProcessingInputAdmission.AdmittedNode admitted =
                    admission.materializeTopLevel(
                            suppliedRoot,
                            "Fragmentation catalog Root");
            Set<String> participatingScopePaths =
                    new LinkedHashSet<>();
            participatingScopePaths.add(JsonPointer.ROOT);
            long maximumScopes =
                    limits.portableLimit(
                            GasScheduleConstants.PortableLimit.PARTICIPATING_SCOPES_PER_EVENT);
            while (true) {
                admitted = admission.materializeScopePaths(
                        admitted,
                        participatingScopePaths);
                HeaderDiscovery discovery =
                        new HeaderDiscovery(
                                sequence,
                                registry,
                                typeResolver,
                                limits);
                discovery.discover(
                        admitted.node(),
                        participatingScopePaths);
                ResolvedSnapshot snapshot =
                        sequence
                                .fromDocumentTransientPreservingPaths(
                                        admitted.node(),
                                        discovery
                                                .executableBodyPaths());
                CatalogPass pass = catalog(
                        sequence,
                        snapshot,
                        admitted.node(),
                        snapshot.blueId());
                if (pass.unmaterializedScopePaths.isEmpty()) {
                    return pass.catalog;
                }
                int before = participatingScopePaths.size();
                participatingScopePaths.addAll(
                        pass.unmaterializedScopePaths);
                requireLimit(
                        GasScheduleConstants.PortableLimit.PARTICIPATING_SCOPES_PER_EVENT,
                        participatingScopePaths.size());
                if (participatingScopePaths.size() == before
                        || participatingScopePaths.size()
                        > maximumScopes) {
                    throw new InvalidExecutionEvidenceException(
                            "Fragmentation catalog could not materialize "
                                    + "declared Process Embedded scopes");
                }
            }
        } finally {
            sequence.releaseTransientState();
        }
    }

    private CatalogPass catalog(
            ProcessingSnapshotManager sequence,
            ResolvedSnapshot snapshot,
            Node exactSelectedRoot,
            String rootBlueId) {
        Map<String, EmbeddedScopePlanView> plansByScope =
                new LinkedHashMap<>();
        Map<String, List<String>> pathsByScope =
                new LinkedHashMap<>();
        Map<String, List<EffectiveContractSnapshot>>
                contractsByScope = new LinkedHashMap<>();
        Deque<ScopeFrame> pending = new ArrayDeque<>();
        pending.addLast(
                new ScopeFrame(
                        JsonPointer.ROOT,
                        0,
                        Collections.<String>emptySet()));
        Set<String> scheduled = new LinkedHashSet<>();
        scheduled.add(JsonPointer.ROOT);
        Set<String> unmaterializedScopePaths =
                new LinkedHashSet<>();

        while (!pending.isEmpty()) {
            ScopeFrame frame = pending.removeFirst();
            requireLimit(
                    GasScheduleConstants.PortableLimit.PARTICIPATING_SCOPES_PER_EVENT,
                    contractsByScope.size() + 1L);
            requireLimit(GasScheduleConstants.PortableLimit.EMBEDDED_DEPTH, frame.depth);

            FrozenNode effective =
                    snapshot.resolvedAt(frame.scopePath);
            if (effective == null) {
                if (JsonPointer.ROOT.equals(frame.scopePath)) {
                    throw new InvalidExecutionEvidenceException(
                            "Fragmentation catalog Root is absent");
                }
                continue;
            }
            requireObjectScope(frame.scopePath, effective);

            Node selected = NodePathEditor.getOrNull(
                    exactSelectedRoot,
                    frame.scopePath);
            String exactScopeIdentity =
                    selected != null
                            ? snapshot.canonicalBlueIdAt(
                                    frame.scopePath)
                            : null;
            if (exactScopeIdentity != null
                    && frame.ancestorScopeBlueIds
                    .contains(exactScopeIdentity)) {
                throw new MustUnderstandFailureException(
                        "Declared embedded ancestry revisits exact node "
                                + exactScopeIdentity + " at "
                                + frame.scopePath,
                        ProcessorErrorCategory.PatchBoundaryViolation);
            }
            Set<String> childAncestors =
                    new LinkedHashSet<>(
                            frame.ancestorScopeBlueIds);
            if (exactScopeIdentity != null) {
                childAncestors.add(exactScopeIdentity);
            }

            ContractBundle bundle =
                    contractLoader.load(
                            selected,
                            effective,
                            frame.scopePath,
                            NoOpProcessingObserver.INSTANCE,
                            snapshot.canonicalTypeIdentities());
            List<EffectiveContractSnapshot> contracts =
                    new ArrayList<>(
                            bundle.effectiveContractSnapshots());
            contracts.sort(
                    (left, right) ->
                            ExternalOrderKey.compareTextCodePoints(
                                    left.key(), right.key()));
            requireLimit(
                    GasScheduleConstants.PortableLimit.EFFECTIVE_CONTRACTS_PER_SCOPE,
                    contracts.size());
            for (EffectiveContractSnapshot contract : contracts) {
                validateContractKey(
                        frame.scopePath,
                        contract.key());
                if (EffectiveContractSnapshotConstants
                        .Role.EXECUTABLE_EXTENSION.equals(
                        contract.role())) {
                    throw new MustUnderstandFailureException(
                            "Unsupported contract type: "
                                    + contract.effectiveTypeBlueId(),
                            ProcessorErrorCategory
                                    .UnsupportedRuntimeType);
                }
            }

            EmbeddedScopePlan embeddedPlan = null;
            if (bundle.hasProcessEmbedded()) {
                EmbeddedScopeDeclaration declaration =
                        bundle.embeddedScopeDeclaration();
                embeddedPlan = new EmbeddedScopePlanner(
                        sequence,
                        snapshot.canonicalTypeIdentities())
                        .plan(
                                effective,
                                frame.scopePath,
                                declaration.explicitPaths(),
                                declaration.collectionPaths(),
                                limits);
            }
            EmbeddedScopePlanView planView = embeddedPlan != null
                    ? EmbeddedScopePlanView.from(embeddedPlan)
                    : EmbeddedScopePlanView.empty(frame.scopePath);
            plansByScope.put(frame.scopePath, planView);
            List<String> embeddedPaths = new ArrayList<>();
            for (String childPath : planView.concreteChildPaths()) {
                embeddedPaths.add(PointerUtils.relativizePointer(
                        frame.scopePath, childPath));
            }
            embeddedPaths = Collections.unmodifiableList(embeddedPaths);
            requireLimit(
                    GasScheduleConstants.PortableLimit.PROCESS_EMBEDDED_PATHS_PER_SCOPE,
                    embeddedPaths.size());
            pathsByScope.put(
                    frame.scopePath,
                    embeddedPaths);
            contractsByScope.put(
                    frame.scopePath,
                    Collections.unmodifiableList(
                            contracts));

            Set<String> localChildren =
                    new LinkedHashSet<>();
            for (String childScope : planView.concreteChildPaths()) {
                if (childScope.equals(frame.scopePath)
                        || !localChildren.add(childScope)
                        || scheduled.contains(childScope)) {
                    throw new MustUnderstandFailureException(
                            "Duplicate or cyclic Process Embedded path: "
                                    + childScope,
                            ProcessorErrorCategory
                                    .PatchBoundaryViolation);
                }
                FrozenNode child =
                        snapshot.resolvedAt(childScope);
                if (child == null
                        || child.isReferenceOnly()) {
                    Node exactChild =
                            NodePathEditor.getOrNull(
                                    exactSelectedRoot,
                                    childScope);
                    if (exactChild != null
                            && exactChild.isReferenceOnly()) {
                        unmaterializedScopePaths.add(
                                childScope);
                    }
                    continue;
                }
                requireObjectScope(childScope, child);
                scheduled.add(childScope);
                pending.addLast(
                        new ScopeFrame(
                                childScope,
                                frame.depth + 1,
                                childAncestors));
            }
        }

        return new CatalogPass(
                new EffectiveFragmentationCatalog(
                        rootBlueId,
                        plansByScope,
                        pathsByScope,
                        contractsByScope),
                unmaterializedScopePaths);
    }

    private static final class CatalogPass {
        private final EffectiveFragmentationCatalog catalog;
        private final Set<String> unmaterializedScopePaths;

        private CatalogPass(
                EffectiveFragmentationCatalog catalog,
                Collection<String> unmaterializedScopePaths) {
            this.catalog = catalog;
            this.unmaterializedScopePaths =
                    Collections.unmodifiableSet(
                            new LinkedHashSet<>(
                                    unmaterializedScopePaths));
        }
    }

    private void validateContractKey(
            String scopePath,
            String key) {
        long codePoints =
                key.codePointCount(0, key.length());
        long utf8Bytes =
                key.getBytes(StandardCharsets.UTF_8).length;
        requireLimit(
                GasScheduleConstants.PortableLimit.CONTRACT_KEY_CODE_POINTS,
                codePoints);
        requireLimit(
                GasScheduleConstants.PortableLimit.CONTRACT_KEY_UTF8_BYTES,
                utf8Bytes);
        if (key.isEmpty()) {
            throw new MustUnderstandFailureException(
                    "Invalid empty contract key at " + scopePath,
                    ProcessorErrorCategory
                            .InvalidRuntimePointer);
        }
    }

    private void requireObjectScope(
            String scopePath,
            FrozenNode node) {
        if (node.isReferenceOnly()
                || node.hasItems()
                || (node.getValue() != null
                && node.getContracts() == null)) {
            throw new MustUnderstandFailureException(
                    "Process Embedded scope is not an object: "
                            + scopePath,
                    ProcessorErrorCategory
                            .PatchBoundaryViolation);
        }
    }

    private void requireLimit(
            String name,
            long observed) {
        long maximum = limits.portableLimit(name);
        if (observed > maximum) {
            throw new PortableLimitExceededException(
                    ProcessorErrorCategory
                            .DirectNodeLimitExceeded,
                    name,
                    observed,
                    maximum);
        }
    }

    private static final class ScopeFrame {
        private final String scopePath;
        private final int depth;
        private final Set<String> ancestorScopeBlueIds;

        private ScopeFrame(
                String scopePath,
                int depth,
                Collection<String> ancestorScopeBlueIds) {
            this.scopePath = scopePath;
            this.depth = depth;
            this.ancestorScopeBlueIds =
                    Collections.unmodifiableSet(
                            new LinkedHashSet<>(
                                    ancestorScopeBlueIds));
        }
    }

    /**
     * Conservatively finds every possible executable-body destination before
     * ordinary Language resolution. Over-approximating a preservation path is
     * safe; following a body reference is not.
     */
    private static final class HeaderDiscovery {

        private final ProcessingSnapshotManager manager;
        private final ContractProcessorRegistry registry;
        private final TypeClassResolver typeResolver;
        private final GasSchedule limits;
        private final Set<String> executableBodyPaths =
                new LinkedHashSet<>();
        private final Set<String> activeReferenceBlueIds =
                new LinkedHashSet<>();

        private HeaderDiscovery(
                ProcessingSnapshotManager manager,
                ContractProcessorRegistry registry,
                TypeClassResolver typeResolver,
                GasSchedule limits) {
            this.manager = manager;
            this.registry = registry;
            this.typeResolver = typeResolver;
            this.limits = limits;
        }

        private void discover(
                Node root,
                Collection<String> scopePaths) {
            List<String> ordered =
                    new ArrayList<>(scopePaths);
            ordered.sort((left, right) -> {
                int depth = Integer.compare(
                        JsonPointer.split(left).size(),
                        JsonPointer.split(right).size());
                return depth != 0
                        ? depth
                        : ExternalOrderKey
                        .compareTextCodePoints(
                                left, right);
            });
            for (String scopePath : ordered) {
                Node scope = NodePathEditor.getOrNull(
                        root, scopePath);
                if (scope != null) {
                    inspectScope(scope, scopePath);
                }
            }
        }

        private Set<String> executableBodyPaths() {
            return Collections.unmodifiableSet(
                    executableBodyPaths);
        }

        private void inspectScope(
                Node supplied,
                String path) {
            if (supplied == null) {
                return;
            }
            Node node = exactContent(
                    supplied,
                    "Fragmentation catalog participating scope "
                            + path);
            if (node == null
                    || node.getValue() != null
                    || node.getItems() != null) {
                return;
            }
            Map<String, String> contractTypes =
                    new LinkedHashMap<>();
            collectTypeContracts(
                    node.getType(),
                    contractTypes,
                    new LinkedHashSet<String>(),
                    0);
            collectContracts(
                    node.getContracts(),
                    contractTypes,
                    path);
            requireLimit(
                    GasScheduleConstants.PortableLimit.EFFECTIVE_CONTRACTS_PER_SCOPE,
                    contractTypes.size());
            for (Map.Entry<String, String> contract
                    : contractTypes.entrySet()) {
                String typeBlueId = contract.getValue();
                if (typeBlueId == null) {
                    continue;
                }
                List<String> deferredFields = registry.exactSourceFieldsByType()
                        .getOrDefault(typeBlueId, Collections.<String>emptyList());
                for (String field : deferredFields) {
                    executableBodyPaths.add(
                            JsonPointer.append(
                                    PointerUtils.resolvePointer(
                                            path,
                                            ProcessorPointerConstants
                                                    .relativeContractsEntry(
                                                            contract
                                                                    .getKey())),
                                    field));
                }
            }
        }

        private void collectTypeContracts(
                Node typeReference,
                Map<String, String> contractTypes,
                Set<String> activeTypes,
                int depth) {
            if (typeReference == null) {
                return;
            }
            requireLimit(GasScheduleConstants.PortableLimit.TYPE_CHAIN_EDGES, depth + 1L);
            Node type = exactContent(
                    typeReference,
                    "Fragmentation catalog type contribution");
            if (type == null) {
                return;
            }
            String identity =
                    referenceIdentity(
                            typeReference, type);
            if (!activeTypes.add(identity)) {
                throw new MustUnderstandFailureException(
                        "Cyclic type contribution while building "
                                + "fragmentation catalog",
                        ProcessorErrorCategory
                                .InvalidContractBinding);
            }
            try {
                collectTypeContracts(
                        type.getType(),
                        contractTypes,
                        activeTypes,
                        depth + 1);
                collectContracts(
                        type.getContracts(),
                        contractTypes,
                        "type " + identity);
            } finally {
                activeTypes.remove(identity);
            }
        }

        private void collectContracts(
                Node contractsReference,
                Map<String, String> contractTypes,
                String owner) {
            if (contractsReference == null) {
                return;
            }
            Node contracts = exactContent(
                    contractsReference,
                    "Fragmentation catalog contracts map at "
                            + owner);
            if (contracts.getProperties() == null) {
                if (Nodes.isEmptyNode(contracts)) {
                    return;
                }
                throw new MustUnderstandFailureException(
                        "Contracts must be an object map",
                        ProcessorErrorCategory
                                .InvalidProcessingDocument);
            }
            requireLimit(
                    GasScheduleConstants.PortableLimit.DIRECT_OBJECT_ENTRIES,
                    contracts.getProperties().size());
            for (Map.Entry<String, Node> entry :
                    contracts.getProperties().entrySet()) {
                String key = entry.getKey();
                if (isDirectProcessorStateKey(key)) {
                    continue;
                }
                String typeBlueId =
                        validateKnownContractHeader(
                                entry.getValue(), key);
                if (typeBlueId != null
                        || !contractTypes.containsKey(key)) {
                    contractTypes.put(
                            key, typeBlueId);
                }
            }
        }

        private String validateKnownContractHeader(
                Node supplied,
                String key) {
            Node contract = exactContent(
                    supplied,
                    "Fragmentation catalog contract '"
                            + key + "'");
            if (contract == null
                    || contract.getType() == null) {
                return null;
            }
            String typeBlueId =
                    CanonicalIdentityEvidence.sourceTypeBlueId(
                            contract.getType(),
                            manager,
                            "Fragmentation catalog contract type '"
                                    + key + "'");
            Class<?> type =
                    typeResolver.resolveClass(typeBlueId);
            if (type == null
                    || !Contract.class.isAssignableFrom(type)) {
                throw new MustUnderstandFailureException(
                        "Unsupported contract type: "
                                + typeBlueId,
                        ProcessorErrorCategory
                                .UnsupportedRuntimeType);
            }
            if (HandlerContract.class
                    .isAssignableFrom(type)
                    && !registry.lookupHandler(
                            typeBlueId).isPresent()) {
                throw new MustUnderstandFailureException(
                        "Unsupported contract type: "
                                + typeBlueId,
                        ProcessorErrorCategory
                                .UnsupportedRuntimeType);
            }
            return typeBlueId;
        }

        private Node exactContent(
                Node supplied,
                String label) {
            if (supplied == null
                    || !supplied.isReferenceOnly()) {
                return supplied;
            }
            String expected = supplied.getBlueId();
            boolean cyclicMember =
                    BlueIds.hasCyclicMemberSeparator(expected);
            if (!activeReferenceBlueIds.add(expected)) {
                throw new MustUnderstandFailureException(
                        "Cyclic exact-reference dependency at "
                                + label,
                        ProcessorErrorCategory
                                .InvalidContractBinding);
            }
            try {
                FrozenNode materialized =
                        manager
                                .materializeVerifiedExactReference(
                                        FrozenNode.fromNode(
                                                supplied));
                if (materialized == null
                        || materialized.isReferenceOnly()) {
                    throw new InvalidExecutionEvidenceException(
                            label
                                    + " is unavailable for "
                                    + expected);
                }
                Node exact = materialized.toNode();
                if (cyclicMember) {
                    /*
                     * The manager's cyclic-aware verified provider owns the
                     * set proof. A member must never be hashed independently.
                     */
                    return exact;
                }
                String actual =
                        DirectBlueIdCalculator.calculateBlueId(exact);
                if (!Objects.equals(expected, actual)) {
                    throw new InvalidExecutionEvidenceException(
                            label + " provider content BlueId "
                                    + actual
                                    + " does not match requested "
                                    + expected);
                }
                return exact;
            } finally {
                activeReferenceBlueIds.remove(expected);
            }
        }

        private String referenceIdentity(
                Node reference,
                Node exact) {
            if (reference != null
                    && reference.isReferenceOnly()
                    && reference.getBlueId() != null) {
                return reference.getBlueId();
            }
            return CanonicalIdentityEvidence.sourceTypeBlueId(
                    exact,
                    manager,
                    "Fragmentation catalog inline type contribution");
        }

        private void requireLimit(
                String name,
                long observed) {
            long maximum = limits.portableLimit(name);
            if (observed > maximum) {
                throw new PortableLimitExceededException(
                        ProcessorErrorCategory
                                .DirectNodeLimitExceeded,
                        name,
                        observed,
                        maximum);
            }
        }

        private boolean isDirectProcessorStateKey(
                String key) {
            return ProcessorContractConstants.KEY_INITIALIZED.equals(key)
                    || ProcessorContractConstants.KEY_TERMINATED.equals(key)
                    || ProcessorContractConstants.KEY_CHECKPOINT.equals(key);
        }
    }
}
