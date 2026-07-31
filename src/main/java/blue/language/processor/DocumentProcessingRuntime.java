package blue.language.processor;

import blue.language.utils.Properties;

import blue.language.Blue;
import blue.language.conformance.ConformanceEngine;
import blue.language.model.Node;
import blue.language.processor.model.FrozenJsonPatch;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.processor.util.PointerUtils;
import blue.language.processor.util.ProcessorContractConstants;
import blue.language.processor.util.ProcessorPointerConstants;
import blue.language.processor.util.NodeCanonicalizer;
import blue.language.snapshot.FrozenNode;
import blue.language.snapshot.ResolvedSnapshot;
import blue.language.utils.BlueIdCalculator;
import blue.language.utils.BlueIds;
import blue.language.utils.JsonPointer;
import blue.language.utils.NodePathEditor;
import blue.language.utils.ParsedJsonPointer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.IdentityHashMap;
import java.util.function.Supplier;

/**
 * Mutable state owner for exactly one document-processing invocation.
 *
 * <p>Canonical document publication, resolved snapshots, gas, conformance
 * trace, emissions, and patch commits share this lifetime. Mutation entry
 * points are atomic: candidate roots and their snapshot metadata are promoted
 * together or the prior runtime state remains active.</p>
 */
public final class DocumentProcessingRuntime {

    private static final String DIRECT_WRITE_ANCESTOR_PURPOSE =
            "Direct-write ancestor";

    private final MaterializedDocumentView materializedView;
    private final EmissionRegistry emissionRegistry;
    private final GasMeter gasMeter;
    private final SemanticOutputBoundary.AdmissionMemo
            semanticOutputAdmissionMemo =
            new SemanticOutputBoundary.AdmissionMemo();
    private final Map<String, List<String>> executableBodyFieldsByType;
    private final ProcessingConformanceTrace.Builder conformanceTrace =
            new ProcessingConformanceTrace.Builder();
    private final ConformanceEngine conformanceEngine;
    private final ConformancePlannerOverride conformancePlannerOverride;
    private final ProcessingSnapshotManager snapshotManager;
    private final ProcessingMetricsSink metrics;
    private final boolean lazyMaterializedCommits;
    private final boolean selectedDocumentBacked;
    private ResolvedSnapshot snapshot;
    private ProcessingSnapshotManager activeSequenceSnapshotManager;
    private boolean materializedViewStale;
    private boolean runTerminated;
    private long batchPatchCalls;
    private long batchPatchEntries;
    private long batchPatchPlanningNanos;
    private long batchPatchConformanceNanos;
    private long batchPatchBuildUpdatesNanos;
    private long batchPatchCommitNanos;
    private long batchPatchRollbackCopies;
    private long documentUpdateBeforeNodeMaterializations;
    private long documentUpdateAfterNodeMaterializations;
    private long stateVersion;
    private long sharedSnapshotVersion;
    private long patchSequencesPrepared;
    private long singletonPatchTransactions;
    private long sequenceIntermediateSnapshotAdvances;
    private long sequenceSharedSnapshotCacheInserts;
    private long sequenceFinalSnapshotCacheInserts;
    private long sequenceSuffixRebases;
    private long sequenceStalePreviewFallbacks;
    private long sequenceFallbackPatches;
    private final Set<String> changedPaths = new LinkedHashSet<>();

    /**
     * Creates a runtime over a caller-owned mutable selected document.
     *
     * <p>The supplied root is retained. Successful commits mutate that same
     * root object, while failed atomic operations restore its prior contents.
     * This overload has no configured snapshot or conformance service.</p>
     *
     * @param document non-null selected document retained for this invocation
     * @throws NullPointerException if {@code document} is {@code null}
     */
    public DocumentProcessingRuntime(Node document) {
        this(document, null, null);
    }

    /**
     * Creates a node-backed runtime with an optional conformance engine.
     *
     * @param document non-null selected document retained and mutated on
     *        successful commits
     * @param conformanceEngine conformance engine, or {@code null}
     * @throws NullPointerException if {@code document} is {@code null}
     */
    public DocumentProcessingRuntime(Node document, ConformanceEngine conformanceEngine) {
        this(document, conformanceEngine, null);
    }

    /**
     * Creates a node-backed runtime with optional conformance and snapshot
     * services.
     *
     * @param document non-null selected document retained and mutated on
     *        successful commits
     * @param conformanceEngine conformance engine, or {@code null}
     * @param snapshotManager invocation snapshot manager used for resolution
     *        and cache publication, or {@code null}
     * @throws NullPointerException if {@code document} is {@code null}
     */
    public DocumentProcessingRuntime(Node document,
                                     ConformanceEngine conformanceEngine,
                                     ProcessingSnapshotManager snapshotManager) {
        this(document, conformanceEngine, snapshotManager, null);
    }

    /**
     * Creates a node-backed runtime with optional instrumentation.
     *
     * <p>A {@code null} metrics sink selects
     * {@link ProcessingMetricsSink#NOOP}. The runtime creates and owns one gas
     * meter for the invocation.</p>
     *
     * @param document non-null selected document retained and mutated on
     *        successful commits
     * @param conformanceEngine conformance engine, or {@code null}
     * @param snapshotManager invocation snapshot manager, or {@code null}
     * @param metrics borrowed thread-safe metrics sink, or {@code null}
     * @throws NullPointerException if {@code document} is {@code null}
     */
    public DocumentProcessingRuntime(Node document,
                                     ConformanceEngine conformanceEngine,
                                     ProcessingSnapshotManager snapshotManager,
                                     ProcessingMetricsSink metrics) {
        this(document, conformanceEngine, null, snapshotManager, metrics);
    }

    /**
     * Creates a fully configured node-backed runtime.
     *
     * @param document non-null selected document retained and mutated on
     *        successful commits
     * @param conformanceEngine conformance engine, or {@code null}
     * @param conformancePlannerOverride optional borrowed planning override
     * @param snapshotManager invocation snapshot manager, or {@code null}
     * @param metrics borrowed thread-safe metrics sink, or {@code null}
     * @throws NullPointerException if {@code document} is {@code null}
     */
    public DocumentProcessingRuntime(Node document,
                                     ConformanceEngine conformanceEngine,
                                     ConformancePlannerOverride conformancePlannerOverride,
                                     ProcessingSnapshotManager snapshotManager,
                                     ProcessingMetricsSink metrics) {
        this(document,
                conformanceEngine,
                conformancePlannerOverride,
                snapshotManager,
                metrics,
                new GasMeter(),
                Collections.emptyMap());
    }

    DocumentProcessingRuntime(Node document,
                              ConformanceEngine conformanceEngine,
                              ConformancePlannerOverride conformancePlannerOverride,
                              ProcessingSnapshotManager snapshotManager,
                              ProcessingMetricsSink metrics,
                              GasMeter gasMeter) {
        this(document,
                conformanceEngine,
                conformancePlannerOverride,
                snapshotManager,
                metrics,
                gasMeter,
                Collections.emptyMap());
    }

    DocumentProcessingRuntime(
            Node document,
            ConformanceEngine conformanceEngine,
            ConformancePlannerOverride conformancePlannerOverride,
            ProcessingSnapshotManager snapshotManager,
            ProcessingMetricsSink metrics,
            GasMeter gasMeter,
            Map<String, List<String>> executableBodyFieldsByType) {
        this.materializedView = new MaterializedDocumentView(Objects.requireNonNull(document, "document"));
        this.emissionRegistry = new EmissionRegistry();
        this.gasMeter = Objects.requireNonNull(gasMeter, "gasMeter");
        this.executableBodyFieldsByType =
                immutableExecutableBodyFields(executableBodyFieldsByType);
        this.conformanceEngine = conformanceEngine;
        this.conformancePlannerOverride = conformancePlannerOverride;
        this.snapshotManager = snapshotManager;
        this.metrics = metrics != null ? metrics : ProcessingMetricsSink.NOOP;
        this.lazyMaterializedCommits = false;
        this.selectedDocumentBacked = true;
    }

    /**
     * Creates a runtime from an immutable canonical/resolved snapshot.
     *
     * <p>The supplied snapshot is not mutated. Frozen lanes are retained and
     * mutable copies are materialized only when required.</p>
     *
     * @param snapshot non-null immutable starting snapshot
     * @param conformanceEngine conformance engine, or {@code null}
     * @param snapshotManager invocation snapshot manager, or {@code null}
     * @throws NullPointerException if {@code snapshot} is {@code null}
     */
    public DocumentProcessingRuntime(ResolvedSnapshot snapshot,
                                     ConformanceEngine conformanceEngine,
                                     ProcessingSnapshotManager snapshotManager) {
        this(snapshot, conformanceEngine, snapshotManager, null);
    }

    /**
     * Creates a snapshot-backed runtime with optional instrumentation.
     *
     * @param snapshot non-null immutable starting snapshot
     * @param conformanceEngine conformance engine, or {@code null}
     * @param snapshotManager invocation snapshot manager, or {@code null}
     * @param metrics borrowed thread-safe metrics sink, or {@code null} to use
     *        {@link ProcessingMetricsSink#NOOP}
     * @throws NullPointerException if {@code snapshot} is {@code null}
     */
    public DocumentProcessingRuntime(ResolvedSnapshot snapshot,
                                     ConformanceEngine conformanceEngine,
                                     ProcessingSnapshotManager snapshotManager,
                                     ProcessingMetricsSink metrics) {
        this(snapshot, conformanceEngine, null, snapshotManager, metrics);
    }

    /**
     * Creates a fully configured snapshot-backed runtime.
     *
     * <p>Successful commits replace the runtime's current immutable snapshot;
     * they never mutate the supplied snapshot instance.</p>
     *
     * @param snapshot non-null immutable starting snapshot
     * @param conformanceEngine conformance engine, or {@code null}
     * @param conformancePlannerOverride optional borrowed planning override
     * @param snapshotManager invocation snapshot manager, or {@code null}
     * @param metrics borrowed thread-safe metrics sink, or {@code null}
     * @throws NullPointerException if {@code snapshot} is {@code null}
     */
    public DocumentProcessingRuntime(ResolvedSnapshot snapshot,
                                     ConformanceEngine conformanceEngine,
                                     ConformancePlannerOverride conformancePlannerOverride,
                                     ProcessingSnapshotManager snapshotManager,
                                     ProcessingMetricsSink metrics) {
        this(snapshot,
                conformanceEngine,
                conformancePlannerOverride,
                snapshotManager,
                metrics,
                new GasMeter(),
                Collections.emptyMap());
    }

    DocumentProcessingRuntime(ResolvedSnapshot snapshot,
                              ConformanceEngine conformanceEngine,
                              ConformancePlannerOverride conformancePlannerOverride,
                              ProcessingSnapshotManager snapshotManager,
                              ProcessingMetricsSink metrics,
                              GasMeter gasMeter) {
        this(snapshot,
                conformanceEngine,
                conformancePlannerOverride,
                snapshotManager,
                metrics,
                gasMeter,
                Collections.emptyMap());
    }

    DocumentProcessingRuntime(
            ResolvedSnapshot snapshot,
            ConformanceEngine conformanceEngine,
            ConformancePlannerOverride conformancePlannerOverride,
            ProcessingSnapshotManager snapshotManager,
            ProcessingMetricsSink metrics,
            GasMeter gasMeter,
            Map<String, List<String>> executableBodyFieldsByType) {
        this.metrics = metrics != null ? metrics : ProcessingMetricsSink.NOOP;
        this.gasMeter = Objects.requireNonNull(gasMeter, "gasMeter");
        this.executableBodyFieldsByType =
                immutableExecutableBodyFields(executableBodyFieldsByType);
        this.conformanceEngine = conformanceEngine;
        this.conformancePlannerOverride = conformancePlannerOverride;
        this.snapshotManager = snapshotManager;
        ResolvedSnapshot processorSnapshot =
                processorSnapshot(
                        Objects.requireNonNull(
                                snapshot, "snapshot"));
        this.materializedView =
                new MaterializedDocumentView(
                        processorSnapshot.canonicalRoot());
        this.emissionRegistry = new EmissionRegistry();
        this.snapshot = processorSnapshot;
        this.lazyMaterializedCommits = true;
        this.selectedDocumentBacked = false;
    }

    private static Map<String, List<String>> immutableExecutableBodyFields(
            Map<String, List<String>> fieldsByType) {
        if (fieldsByType == null || fieldsByType.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, List<String>> immutable = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry
                : fieldsByType.entrySet()) {
            immutable.put(entry.getKey(),
                    Collections.unmodifiableList(
                            new ArrayList<>(entry.getValue())));
        }
        return Collections.unmodifiableMap(immutable);
    }

    private ResolvedSnapshot processorSnapshot(ResolvedSnapshot snapshot) {
        if (snapshot.frozenCanonicalRoot().isStrictBlueIdValidation()) {
            metrics.incrementProcessorInputStrictCanonical();
        } else {
            metrics.incrementProcessorInputUncheckedCanonical();
        }
        Map<String, FrozenNode> preservedBodies =
                initialExecutableBodyOverlays(
                        snapshot.frozenCanonicalRoot(),
                        snapshot.frozenResolvedRoot(),
                        executableBodyFieldsByType);
        if (preservedBodies.isEmpty()) {
            return snapshot;
        }

        /*
         * A caller may legitimately supply a fully resolved snapshot. Contract
         * execution still must not observe an eagerly expanded executable body
         * before its Handler matches. Reuse the already-verified resolved lane
         * and restore only the registry-declared body subtrees from the exact
         * canonical lane; this avoids a second provider read and leaves the
         * canonical Root and its BlueId unchanged.
         */
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
                FrozenNode.fromResolvedNode(
                        deferredResolved));
    }

    /**
     * Finds executable bodies on the actual Process Embedded closure without
     * resolving anything. Exact canonical subtrees are preferred. When an
     * inherited body or whole contract was authored as a reference, the
     * requested reference identity retained in the resolved lane is collapsed
     * back to that pure reference; no identity is derived from expanded
     * content.
     */
    private static Map<String, FrozenNode>
    initialExecutableBodyOverlays(
            FrozenNode canonicalRoot,
            FrozenNode resolvedRoot,
            Map<String, List<String>>
                    executableBodyFieldsByType) {
        if (canonicalRoot == null
                || resolvedRoot == null
                || executableBodyFieldsByType == null
                || executableBodyFieldsByType.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, FrozenNode> result =
                new LinkedHashMap<>();
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
                        .validateProcessEmbeddedTraversalPath(
                                scopePath);
            } catch (ProcessorFailureException opaqueBoundary) {
                /*
                 * Runtime preflight owns the deterministic diagnostic.
                 * Snapshot admission must not inspect executable bodies
                 * beyond an opaque finalized cyclic-member edge first.
                 */
                continue;
            }
            FrozenNode selectedScope =
                    canonicalRoot.at(scopePath);
            FrozenNode effectiveScope =
                    resolvedRoot.at(scopePath);
            collectInitialExecutableBodyOverlays(
                    scopePath,
                    selectedScope,
                    effectiveScope,
                    executableBodyFieldsByType,
                    result);
            collectInitialEmbeddedScopes(
                    scopePath,
                    effectiveScope,
                    pending,
                    visited);
        }
        return result;
    }

    private static void collectInitialExecutableBodyOverlays(
            String scopePath,
            FrozenNode selectedScope,
            FrozenNode effectiveScope,
            Map<String, List<String>>
                    executableBodyFieldsByType,
            Map<String, FrozenNode> result) {
        FrozenNode selectedContracts =
                selectedScope != null
                        ? selectedScope.getContracts()
                        : null;
        FrozenNode effectiveContracts =
                effectiveScope != null
                        ? effectiveScope.getContracts()
                        : null;
        Map<String, FrozenNode> effectiveEntries =
                effectiveContracts != null
                        ? effectiveContracts.getProperties()
                        : null;
        if (effectiveEntries == null) {
            return;
        }
        for (Map.Entry<String, FrozenNode> entry
                : effectiveEntries.entrySet()) {
            FrozenNode effectiveContract =
                    entry.getValue();
            FrozenNode selectedContract =
                    selectedContracts != null
                            ? selectedContracts.property(
                            entry.getKey())
                            : null;
            String typeBlueId =
                    exactTypeBlueId(selectedContract);
            List<String> fields =
                    executableBodyFieldsByType.get(
                            typeBlueId);
            if (fields == null) {
                typeBlueId =
                        exactTypeBlueId(
                                effectiveContract);
                fields = executableBodyFieldsByType.get(
                        typeBlueId);
            }
            if (fields == null || fields.isEmpty()) {
                continue;
            }
            String contractPath =
                    contractPath(
                            scopePath,
                            entry.getKey());
            if (selectedContract != null
                    && selectedContract.isReferenceOnly()) {
                result.put(
                        contractPath,
                        selectedContract);
                continue;
            }
            for (String field : fields) {
                String bodyPath =
                        contractPath + "/"
                                + JsonPointer.escape(field);
                FrozenNode exactBody =
                        selectedContract != null
                                ? selectedContract.property(
                                field)
                                : null;
                if (exactBody != null) {
                    result.put(bodyPath, exactBody);
                    continue;
                }
                FrozenNode effectiveBody =
                        effectiveContract != null
                                ? effectiveContract.property(
                                field)
                                : null;
                String retainedReference =
                        effectiveBody != null
                                ? effectiveBody
                                .getReferenceBlueId()
                                : null;
                if (retainedReference != null) {
                    result.put(
                            bodyPath,
                            FrozenNode.fromNode(
                                    new Node().blueId(
                                            retainedReference)));
                }
            }
        }
    }

    private static void collectInitialEmbeddedScopes(
            String scopePath,
            FrozenNode effectiveScope,
            Deque<String> pending,
            Set<String> visited) {
        FrozenNode contracts =
                effectiveScope != null
                        ? effectiveScope.getContracts()
                        : null;
        Map<String, FrozenNode> entries =
                contracts != null
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
            FrozenNode paths =
                    contract != null
                            ? contract.property(
                            ProcessorContractConstants.KEY_PATHS)
                            : null;
            List<FrozenNode> items =
                    paths != null ? paths.getItems() : null;
            if (items == null) {
                continue;
            }
            for (FrozenNode item : items) {
                Object value =
                        item != null ? item.getValue() : null;
                if (!(value instanceof String)) {
                    continue;
                }
                try {
                    String relative =
                            PointerUtils
                                    .assertValidRuntimePointer(
                                            (String) value);
                    String child =
                            PointerUtils.resolvePointer(
                                    scopePath, relative);
                    if (!child.equals(scopePath)
                            && !visited.contains(child)) {
                        pending.addLast(child);
                    }
                } catch (IllegalArgumentException ignored) {
                    /*
                     * Runtime preflight owns the deterministic diagnostic for
                     * malformed Process Embedded paths.
                     */
                }
            }
        }
    }

    private static String contractPath(
            String scopePath,
            String contractKey) {
        List<String> path =
                new ArrayList<>(
                        JsonPointer.split(scopePath));
        path.add(ProcessorContractConstants.KEY_CONTRACTS);
        path.add(contractKey);
        return JsonPointer.toPointer(path);
    }

    /**
     * Returns the current authoritative runtime representation.
     *
     * <p>Snapshot-backed invocations return the resolved root; selected-node
     * invocations synchronize pending materialized state first. Snapshot
     * results are fresh mutable copies; a node-backed result is the live
     * caller-supplied root and must not be mutated outside runtime
     * operations.</p>
     *
     * @return current resolved document representation
     */
    public Node document() {
        if (!selectedDocumentBacked && snapshot != null) {
            return snapshot.resolvedRoot();
        }
        syncMaterializedView();
        return materializedView.root();
    }

    Node selectedDocument() {
        if (snapshot != null) {
            return snapshot.canonicalRoot();
        }
        syncMaterializedView();
        return materializedView.root();
    }

    /**
     * Returns the live invocation-owned scope registry. It must not escape the
     * invocation or be used as durable document state.
     *
     * @return mutable live map keyed by absolute scope path
     */
    public Map<String, ScopeRuntimeContext> scopes() {
        return emissionRegistry.scopes();
    }

    /**
     * Returns or creates invocation state for an absolute scope path.
     *
     * <p>Callers own path normalization; the supplied spelling is the registry
     * key. Root-equivalent paths initialize embedded depth to zero.</p>
     *
     * @param scopePath absolute processing scope path
     * @return live invocation-owned scope context
     * @throws NullPointerException if a new context is requested with a
     *         {@code null} path
     */
    public ScopeRuntimeContext scope(String scopePath) {
        ScopeRuntimeContext context = emissionRegistry.scope(scopePath);
        if (JsonPointer.ROOT.equals(
                PointerUtils.normalizeScope(scopePath))) {
            context.setEmbeddedDepth(0);
        }
        return context;
    }

    /**
     * Looks up already-created invocation state without creating it.
     *
     * @param scopePath exact registry scope key
     * @return live scope context, or {@code null} when absent
     */
    public ScopeRuntimeContext existingScope(String scopePath) {
        return emissionRegistry.existingScope(scopePath);
    }

    /**
     * Returns root emissions in their public FIFO output order.
     *
     * @return live invocation-owned mutable list
     */
    public List<Node> rootEmissions() {
        return emissionRegistry.rootEmissions();
    }

    /**
     * Admits a root emission after enforcing the published output limit.
     *
     * <p>The node is retained by reference after successful admission.</p>
     *
     * @param emission non-null root emission
     * @throws NullPointerException if {@code emission} is {@code null}
     * @throws PortableLimitExceededException if admitting the emission would
     *         exceed the portable root-output limit
     */
    public void recordRootEmission(Node emission) {
        long observed = emissionRegistry.rootEmissions().size() + 1L;
        enforcePortableLimit(
                ProcessorErrorCategory.InternalEventLimitExceeded,
                GasScheduleConstants.PortableLimit.ROOT_EVENTS_RETURNED,
                observed);
        emissionRegistry.recordRootEmission(emission);
    }

    void attachScopeOccurrence(String parentScopePath,
                               String childScopePath) {
        ScopeRuntimeContext parent = scope(
                PointerUtils.normalizeScope(parentScopePath));
        ScopeRuntimeContext child = scope(
                PointerUtils.normalizeScope(childScopePath));
        child.attachToParentOccurrence(parent);
    }

    void enqueueEventOccurrence(EventOccurrence occurrence) {
        long observed =
                emissionRegistry.enqueuedOccurrenceCount() + 1L;
        enforcePortableLimit(
                ProcessorErrorCategory.InternalEventLimitExceeded,
                GasScheduleConstants.PortableLimit.INTERNAL_EVENT_OCCURRENCES,
                observed);
        emissionRegistry.enqueue(occurrence);
    }

    EventOccurrence pollEventOccurrence() {
        return emissionRegistry.poll();
    }

    boolean hasPendingEventOccurrences() {
        return emissionRegistry.hasPendingOccurrences();
    }

    int pendingEventOccurrenceCount() {
        return emissionRegistry.pendingOccurrenceCount();
    }

    /**
     * Opens a legacy detached child ledger.
     *
     * <p>Hosted processor phases should prefer
     * {@link RuntimeWorkSession#openLedger(String, Map)}, which also enforces
     * ownership and canonical multi-ledger merge semantics. This detached
     * ledger snapshots the currently remaining parent budget and copies its
     * counter catalog. It must later be merged exactly once.</p>
     *
     * @param namespace non-empty runtime namespace disjoint from core
     *        namespaces
     * @param counterWeights complete counter-to-weight catalog copied by the
     *        child ledger
     * @return detached invocation child ledger
     * @throws NullPointerException if {@code namespace},
     *         {@code counterWeights}, a counter, or a weight is {@code null}
     * @throws IllegalArgumentException if the namespace or a counter/weight is
     *         invalid
     * @throws PortableLimitExceededException if the counter catalog exceeds
     *         the portable runtime-ledger kind limit
     */
    public GasMeter.ChildGasLedger newRuntimeGasLedger(
            String namespace,
            Map<String, Long> counterWeights) {
        long kindLimit = gasMeter.schedule()
                .portableLimit(GasScheduleConstants.PortableLimit.RUNTIME_CHILD_LEDGER_COUNTER_KINDS);
        if (counterWeights != null && counterWeights.size() > kindLimit) {
            throw new PortableLimitExceededException(
                    ProcessorErrorCategory.RuntimeLedgerLimitExceeded,
                    GasScheduleConstants.PortableLimit.RUNTIME_CHILD_LEDGER_COUNTER_KINDS,
                    counterWeights.size(),
                    kindLimit);
        }
        return gasMeter.childLedger(namespace, counterWeights);
    }

    RuntimeWorkSession newRuntimeWorkSession(Blue blue) {
        RuntimeWorkSession session =
                new RuntimeWorkSession(
                        gasMeter,
                        RuntimeWorkSession.Mode.PROCESSING);
        if (blue != null) {
            session.attachSemanticOutputBoundary(
                    new SemanticOutputBoundary(
                            session,
                            blue,
                            currentSnapshotManager(),
                            gasMeter.semantic(),
                            semanticOutputAdmissionMemo));
        }
        return session;
    }

    void mergeRuntimeGasLedger(GasMeter.ChildGasLedger ledger) {
        gasMeter.merge(ledger);
    }

    /**
     * Returns the live gas meter owned by this invocation.
     *
     * <p>Charges, semantic gas, child-ledger merges, and the trace share this
     * single lifecycle. The meter must not be reused by another invocation.</p>
     *
     * @return invocation-owned mutable gas meter
     */
    public GasMeter gasMeter() {
        return gasMeter;
    }

    /**
     * Returns paths changed by committed writes and patches.
     *
     * @return immutable defensive snapshot in first-change order
     */
    public Set<String> changedPaths() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(changedPaths));
    }

    /**
     * Builds the current conformance trace including admitted gas entries.
     *
     * @return immutable trace snapshot at call time
     */
    public ProcessingConformanceTrace conformanceTrace() {
        return conformanceTrace.build(gasMeter.trace());
    }

    /**
     * Records a semantic demand in first-observation order.
     *
     * @param demand stable path or BlueId demand; {@code null} and empty
     *        values are ignored
     */
    public void recordSemanticDemand(String demand) {
        conformanceTrace.semanticDemand(demand);
    }

    /**
     * Records the exact semantic demand for one executable-body field after
     * its matcher has succeeded.
     *
     * <p>The exact body identity is representation-independent: a pure
     * reference already carries it, while inline resolved-view content is
     * calculated with the canonical Language identity algorithm. The
     * resolved structural cache identity is deliberately not used as the
     * semantic Node BlueId. The body identity was pre-admitted and bound in
     * the immutable run snapshot, so carrying it into execution is zero
     * generic kernel work. Runtime-specific body inspections, if any, belong
     * in the registered runtime child ledger.</p>
     */
    void recordSelectedExecutableBodyDemand(
            FrozenNode body,
            String scopePath,
            String contractKey,
            String logicalPath) {
        if (body == null) {
            return;
        }
        String bodyBlueId = body.isReferenceOnly()
                ? body.getReferenceBlueId()
                : BlueIdCalculator.calculateBlueId(body.toNode());
        recordSemanticDemand(bodyBlueId);
    }

    void recordPatchSemanticDemands(String patchPath) {
        List<String> segments = JsonPointer.split(
                PointerUtils.normalizePointer(patchPath));
        /*
         * Rebuilding /x/a semantically opens the direct manifests on the
         * strict ancestor path (/x), but never the bodies of unchanged
         * siblings. Root is already an invocation demand.
         */
        for (int count = 1; count < segments.size(); count++) {
            recordSemanticDemand(JsonPointer.toPointer(
                    segments.subList(0, count)));
        }
    }

    void recordContractSnapshot(EffectiveContractSnapshot snapshot) {
        conformanceTrace.contractSnapshot(snapshot);
    }

    void recordTrace(ProcessingTraceRecord.Kind kind,
                     String scopePath,
                     String contractKey,
                     String logicalPath,
                     Map<String, ?> details,
                     Node node) {
        conformanceTrace.record(kind,
                scopePath,
                contractKey,
                logicalPath,
                details,
                node);
    }

    void recordTrace(ProcessingTraceRecord.Kind kind,
                     String scopePath,
                     String contractKey,
                     String logicalPath) {
        conformanceTrace.record(kind, scopePath, contractKey, logicalPath);
    }

    /**
     * Returns gas admitted to this invocation's parent ledger.
     *
     * @return exact admitted gas total
     */
    public long totalGas() {
        return gasMeter.totalGas();
    }

    /**
     * Charges the fixed processing-invocation counter.
     *
     * @throws GasLimitExceededException if the remaining budget is
     *         insufficient
     */
    public void chargeProcessInvocation() {
        gasMeter.chargeProcessInvocation();
    }

    /**
     * Returns the semantic meter sharing this invocation's gas budget.
     *
     * @return invocation-owned semantic gas meter
     */
    public SemanticGasMeter semanticGas() {
        return gasMeter.semantic();
    }

    /**
     * Charges one delivery-snapshot entry.
     *
     * @param scopePath absolute scope attributed to the charge
     * @param contractKey scope-local contract key
     * @throws GasLimitExceededException if the remaining budget is
     *         insufficient
     */
    public void chargeDeliverySnapshotEntry(String scopePath, String contractKey) {
        gasMeter.chargeDeliverySnapshotEntry(scopePath, contractKey);
    }

    /**
     * Charges entry into one participating scope.
     *
     * @param scopePath absolute scope attributed to the charge
     * @throws GasLimitExceededException if the remaining budget is
     *         insufficient
     */
    public void chargeScopeEntry(String scopePath) {
        gasMeter.chargeScopeEntry(scopePath);
    }

    /**
     * Charges the admitted participating-scope closure.
     *
     * @param quantity non-negative number of scopes
     * @throws IllegalArgumentException if {@code quantity} is negative
     * @throws GasLimitExceededException if the remaining budget is
     *         insufficient
     */
    public void chargeParticipatingClosure(long quantity) {
        gasMeter.chargeParticipatingClosure(quantity);
    }

    /**
     * Charges recognition of one contract header.
     *
     * @param scopePath absolute containing scope
     * @param contractKey scope-local contract key
     * @param reason stable trace reason
     * @throws GasLimitExceededException if the remaining budget is
     *         insufficient
     */
    public void chargeContractHeaderRecognized(String scopePath,
                                               String contractKey,
                                               String reason) {
        gasMeter.chargeContractHeaderRecognized(scopePath, contractKey, reason);
    }

    /**
     * Charges a batch of recognized contract headers.
     *
     * @param quantity non-negative number of headers
     * @param reason stable trace reason
     * @throws IllegalArgumentException if {@code quantity} is negative
     * @throws GasLimitExceededException if the remaining budget is
     *         insufficient
     */
    public void chargeContractHeadersRecognized(long quantity, String reason) {
        gasMeter.chargeContractHeadersRecognized(quantity, reason);
    }

    /**
     * Charges reading one Process Embedded path entry.
     *
     * @param scopePath absolute containing scope
     * @param logicalPath logical embedded path attributed to the charge
     * @throws GasLimitExceededException if the remaining budget is
     *         insufficient
     */
    public void chargeEmbeddedPathEntryRead(String scopePath, String logicalPath) {
        gasMeter.chargeEmbeddedPathEntryRead(scopePath, logicalPath);
    }

    /**
     * Charges validated segments of a Process Embedded path.
     *
     * @param scopePath absolute containing scope
     * @param logicalPath logical embedded path
     * @param quantity non-negative validated segment count
     * @throws IllegalArgumentException if {@code quantity} is negative
     * @throws GasLimitExceededException if the remaining budget is
     *         insufficient
     */
    public void chargeEmbeddedPathSegmentsValidated(String scopePath,
                                                    String logicalPath,
                                                    long quantity) {
        gasMeter.chargeEmbeddedPathSegmentsValidated(scopePath, logicalPath, quantity);
    }

    /**
     * Retains the minimum observed embedded depth for a scope occurrence.
     *
     * @param scopePath absolute scope path
     * @param depth non-negative embedded depth
     * @throws IllegalArgumentException if {@code depth} is negative
     */
    public void setScopeEmbeddedDepth(String scopePath, int depth) {
        scope(scopePath).setEmbeddedDepth(depth);
    }

    /**
     * Returns the retained embedded depth for a scope occurrence.
     *
     * <p>The scope context is created if it does not yet exist.</p>
     *
     * @param scopePath absolute scope path
     * @return minimum embedded depth recorded for the occurrence
     */
    public int scopeEmbeddedDepth(String scopePath) {
        return scope(scopePath).embeddedDepth();
    }

    /**
     * Charges initialization of one scope.
     *
     * @param scopePath absolute initialized scope
     * @throws GasLimitExceededException if the remaining budget is
     *         insufficient
     */
    public void chargeInitialization(String scopePath) {
        gasMeter.chargeInitialization(scopePath);
    }

    /**
     * Charges one channel-match attempt.
     *
     * @param scopePath absolute containing scope
     * @param contractKey channel contract key
     * @throws GasLimitExceededException if the remaining budget is
     *         insufficient
     */
    public void chargeChannelMatchAttempt(String scopePath, String contractKey) {
        gasMeter.chargeChannelMatchAttempt(scopePath, contractKey);
    }

    /**
     * Charges one accepted channel.
     *
     * @param scopePath absolute containing scope
     * @param contractKey channel contract key
     * @throws GasLimitExceededException if the remaining budget is
     *         insufficient
     */
    public void chargeChannelAccepted(String scopePath, String contractKey) {
        gasMeter.chargeChannelAccepted(scopePath, contractKey);
    }

    /**
     * Charges testing one handler candidate.
     *
     * @param scopePath absolute containing scope
     * @param contractKey handler contract key
     * @throws GasLimitExceededException if the remaining budget is
     *         insufficient
     */
    public void chargeHandlerCandidateTested(String scopePath, String contractKey) {
        gasMeter.chargeHandlerCandidateTested(scopePath, contractKey);
    }

    /**
     * Charges one handler call overhead.
     *
     * @param scopePath absolute containing scope
     * @param contractKey handler contract key
     * @throws GasLimitExceededException if the remaining budget is
     *         insufficient
     */
    public void chargeHandlerOverhead(String scopePath, String contractKey) {
        gasMeter.chargeHandlerOverhead(scopePath, contractKey);
    }

    /**
     * Charges one patch-boundary check.
     *
     * @throws GasLimitExceededException if the remaining budget is
     *         insufficient
     */
    public void chargeBoundaryCheck() {
        gasMeter.chargeBoundaryCheck();
    }

    /**
     * Charges one mutable add-or-replace patch operation.
     *
     * @param value authored patch value used only for operation attribution
     * @throws GasLimitExceededException if the remaining budget is
     *         insufficient
     */
    public void chargePatchAddOrReplace(Node value) {
        gasMeter.chargePatchAddOrReplace(value);
    }

    /**
     * Charges one frozen add-or-replace patch operation.
     *
     * @param value immutable authored patch value used for attribution
     * @throws GasLimitExceededException if the remaining budget is
     *         insufficient
     */
    public void chargeFrozenPatchAddOrReplace(FrozenNode value) {
        gasMeter.chargeFrozenPatchAddOrReplace(value);
    }

    /**
     * Charges one frozen add-or-replace patch with a precomputed authored
     * size.
     *
     * @param authoredCanonicalSizeBytes non-negative canonical byte size
     * @throws IllegalArgumentException if the supplied size is negative
     * @throws GasLimitExceededException if the remaining budget is
     *         insufficient
     */
    public void chargeFrozenPatchAddOrReplace(long authoredCanonicalSizeBytes) {
        gasMeter.chargeFrozenPatchAddOrReplace(authoredCanonicalSizeBytes);
    }

    /**
     * Charges one remove patch operation.
     *
     * @throws GasLimitExceededException if the remaining budget is
     *         insufficient
     */
    public void chargePatchRemove() {
        gasMeter.chargePatchRemove();
    }

    /**
     * Charges delivery of a Document Update to matching scopes.
     *
     * @param scopeCount matching delivery count; non-positive values incur no
     *        charge
     * @throws GasLimitExceededException if the remaining budget is
     *         insufficient
     */
    public void chargeCascadeRouting(int scopeCount) {
        gasMeter.chargeCascadeRouting(scopeCount);
    }

    /**
     * Charges admission of one internal event.
     *
     * @param event event used only for operation attribution
     * @throws GasLimitExceededException if the remaining budget is
     *         insufficient
     */
    public void chargeEmitEvent(Node event) {
        gasMeter.chargeEmitEvent(event);
    }

    /**
     * Charges recording one public root event.
     *
     * @throws GasLimitExceededException if the remaining budget is
     *         insufficient
     */
    public void chargeRootEventRecorded() {
        gasMeter.chargeRootEventRecorded();
    }

    /**
     * Charges one embedded-event bridge delivery.
     *
     * @param event event used only for operation attribution
     * @throws GasLimitExceededException if the remaining budget is
     *         insufficient
     */
    public void chargeBridge(Node event) {
        gasMeter.chargeBridge(event);
    }

    /**
     * Charges one triggered-event delivery.
     *
     * @throws GasLimitExceededException if the remaining budget is
     *         insufficient
     */
    public void chargeTriggeredDelivery() {
        gasMeter.chargeTriggeredDelivery();
    }

    /**
     * Charges draining one internal event occurrence.
     *
     * @throws GasLimitExceededException if the remaining budget is
     *         insufficient
     */
    public void chargeDrainEvent() {
        gasMeter.chargeDrainEvent();
    }

    /**
     * Charges writing one checkpoint.
     *
     * @throws GasLimitExceededException if the remaining budget is
     *         insufficient
     */
    public void chargeCheckpointUpdate() {
        gasMeter.chargeCheckpointUpdate();
    }

    /**
     * Charges one checkpoint comparison.
     *
     * @throws GasLimitExceededException if the remaining budget is
     *         insufficient
     */
    public void chargeCheckpointCompared() {
        gasMeter.chargeCheckpointCompared();
    }

    /**
     * Charges one processor-owned marker write.
     *
     * @param reason stable trace reason
     * @throws GasLimitExceededException if the remaining budget is
     *         insufficient
     */
    public void chargeProcessorMarkerWritten(String reason) {
        gasMeter.chargeProcessorMarkerWritten(reason);
    }

    /**
     * Charges one termination request.
     *
     * @throws GasLimitExceededException if the remaining budget is
     *         insufficient
     */
    public void chargeTerminationRequest() {
        gasMeter.chargeTerminationRequest();
    }

    /**
     * Charges writing one termination marker.
     *
     * @throws GasLimitExceededException if the remaining budget is
     *         insufficient
     */
    public void chargeTerminationMarker() {
        gasMeter.chargeTerminationMarker();
    }

    /**
     * Charges one lifecycle delivery.
     *
     * @throws GasLimitExceededException if the remaining budget is
     *         insufficient
     */
    public void chargeLifecycleDelivery() {
        gasMeter.chargeLifecycleDelivery();
    }

    /**
     * Returns whether processing has been terminated for the whole run.
     *
     * @return {@code true} after run termination is marked
     */
    public boolean isRunTerminated() {
        return runTerminated;
    }

    /** Monotonically marks the whole processing run as terminated. */
    public void markRunTerminated() {
        runTerminated = true;
    }

    /**
     * Returns whether an existing scope occurrence is finally terminated.
     *
     * @param scopePath exact scope registry key
     * @return {@code true} only for an existing terminated scope
     */
    public boolean isScopeTerminated(String scopePath) {
        return emissionRegistry.isScopeTerminated(scopePath);
    }

    /**
     * Lazily establishes the current immutable snapshot, if a snapshot manager
     * is configured. Intermediate creation does not itself commit a patch.
     *
     * @return current invocation snapshot, or {@code null} when no snapshot
     *         exists and no snapshot manager is configured
     * @throws RuntimeException if provider resolution or snapshot validation
     *         fails; no patch is committed
     */
    public ResolvedSnapshot snapshot() {
        if (snapshot == null && snapshotManager != null) {
            snapshot = snapshotFromDocument(materializedView.root());
            if (!selectedDocumentBacked) {
                materializedView.replaceWithSnapshot(snapshot);
            }
        }
        return snapshot;
    }

    /**
     * Returns the effective resolved node at an absolute pointer.
     *
     * @param path absolute or root-equivalent pointer to normalize
     * @return fresh mutable node copy, or {@code null} when absent
     * @throws RuntimeException if lazy snapshot resolution fails
     */
    public Node resolvedNodeAt(String path) {
        String normalized = PointerUtils.normalizePointer(path);
        ResolvedSnapshot current = snapshot();
        if (current != null) {
            return current.resolvedNodeAt(normalized);
        }
        return materializedView.nodeAt(normalized);
    }

    /**
     * Immutable counterpart of {@link #resolvedNodeAt(String)}.
     *
     * @param path absolute or root-equivalent pointer to normalize
     * @return immutable resolved node, or {@code null} when absent
     * @throws RuntimeException if lazy snapshot resolution fails
     */
    public FrozenNode resolvedFrozenAt(String path) {
        String normalized = PointerUtils.normalizePointer(path);
        ResolvedSnapshot current = snapshot();
        if (current != null) {
            return current.resolvedAt(normalized);
        }
        Node node = materializedView.nodeAt(normalized);
        return node != null ? FrozenNode.fromResolvedNode(node) : null;
    }

    FrozenNode selectedFrozenAt(String path) {
        String normalized = PointerUtils.normalizePointer(path);
        if (!selectedDocumentBacked) {
            ResolvedSnapshot current = snapshot();
            if (current != null) {
                return current.canonicalAt(normalized);
            }
        }
        Node node = materializedView.nodeAt(normalized);
        return node != null ? FrozenNode.fromResolvedNode(node) : null;
    }

    /**
     * Builds the resolved scope view required for contract recognition without
     * mutating the selected document or replacing its canonical references.
     */
    FrozenNode contractRecognitionScope(FrozenNode selectedScope,
                                        FrozenNode resolvedScope) {
        if (selectedScope == null || resolvedScope == null
                || selectedScope.getContracts() == null
                || selectedScope.getContracts().getProperties() == null
                || resolvedScope.getContracts() == null
                || resolvedScope.getContracts().getProperties() == null) {
            return resolvedScope;
        }
        ProcessingSnapshotManager manager = currentSnapshotManager();
        Node recognitionScope = null;
        FrozenNode refreshedEffectiveScope = null;
        for (String key : selectedScope.getContracts().getProperties().keySet()) {
            FrozenNode effectiveContract = resolvedScope.getContracts().property(key);
            if (effectiveContract == null || !effectiveContract.isReferenceOnly()) {
                continue;
            }
            if (manager == null) {
                throw new IllegalStateException(
                        "Contract Recognition Resolution requires provider content for contract '"
                                + key + "' at scope without a ProcessingSnapshotManager");
            }
            FrozenNode materialized =
                    manager.materializeVerifiedReference(
                            effectiveContract);
            if (materialized.getType() == null) {
                /*
                 * A preserved canonical contract reference can point at a
                 * direct typeless overlay. Materializing that reference alone
                 * drops the type and constraints inherited from the selected
                 * scope's type. Refresh the current selected scope once and
                 * use its effective contract instead. Ordinary typed
                 * references retain the prior path-local materialization.
                 */
                if (refreshedEffectiveScope == null) {
                    refreshedEffectiveScope =
                            resolveCanonicalTransient(
                                    manager,
                                    selectedScope,
                                    Collections.singleton(
                                            JsonPointer.ROOT),
                                    executableBodyFieldsByType)
                                    .frozenResolvedRoot();
                }
                FrozenNode refreshedContract =
                        refreshedEffectiveScope.getContracts() != null
                                ? refreshedEffectiveScope
                                .getContracts()
                                .property(key)
                                : null;
                if (refreshedContract != null
                        && !refreshedContract.isReferenceOnly()) {
                    materialized =
                            refreshedContract;
                }
            }
            if (recognitionScope == null) {
                recognitionScope = resolvedScope.toNode();
            }
            recognitionScope.getContracts().properties(key, materialized.toNode());
        }
        return recognitionScope != null
                ? FrozenNode.fromResolvedNode(recognitionScope)
                : resolvedScope;
    }

    /**
     * Returns the authored canonical node before effective type expansion.
     *
     * @param path absolute or root-equivalent pointer to normalize
     * @return fresh mutable canonical node copy, or {@code null} when absent
     * @throws RuntimeException if lazy snapshot creation fails
     */
    public Node canonicalNodeAt(String path) {
        String normalized = PointerUtils.normalizePointer(path);
        ResolvedSnapshot current = snapshot();
        if (current != null) {
            return current.canonicalNodeAt(normalized);
        }
        return materializedView.nodeAt(normalized);
    }

    /**
     * Immutable counterpart of {@link #canonicalNodeAt(String)}.
     *
     * @param path absolute or root-equivalent pointer to normalize
     * @return immutable canonical node, or {@code null} when absent
     * @throws RuntimeException if lazy snapshot creation fails
     */
    public FrozenNode canonicalFrozenAt(String path) {
        String normalized = PointerUtils.normalizePointer(path);
        ResolvedSnapshot current = snapshot();
        if (current != null) {
            return current.canonicalAt(normalized);
        }
        Node node = materializedView.nodeAt(normalized);
        return node != null ? FrozenNode.fromResolvedNode(node) : null;
    }

    /**
     * Freezes the exact selected scope at the initialization protocol capture
     * point.
     *
     * <p>Contracts 1.0 requires the marker and initiation lifecycle event to
     * carry the exact scope document as it exists immediately before
     * initialization effects. This is an identity-preserving Blue node, not a
     * derived Content BlueId. No provider demand is introduced solely for this
     * capture: a selected pure reference remains a valid exact
     * representation.</p>
     *
     * @param scopePath absolute processing scope to capture
     * @return immutable exact canonical scope representation
     * @throws IllegalStateException if the selected scope is absent
     * @throws RuntimeException if snapshot establishment fails
     */
    public FrozenNode capturePreInitializationScopeDocument(
            String scopePath) {
        String normalized = PointerUtils.normalizeScope(scopePath);
        syncMaterializedView();
        ResolvedSnapshot current = snapshot();
        FrozenNode exactScope = current != null
                ? current.canonicalAt(normalized)
                : null;
        if (exactScope != null) {
            return exactScope;
        }
        Node selectedScope = materializedView.nodeAt(normalized);
        if (selectedScope == null) {
            throw new IllegalStateException(
                    "Exact selected scope is absent at " + normalized);
        }
        return FrozenNode.fromUncheckedCanonicalNode(selectedScope.clone());
    }

    /**
     * Binary-compatible identity view of the exact initialization capture.
     *
     * <p>The Contracts 1.0 marker carries the exact document; this method
     * derives its ordinary BlueId without restoring the former identifier-only
     * marker representation.</p>
     *
     * @param scopePath absolute processing scope to identify
     * @return ordinary BlueId of the exact pre-initialization scope
     * @throws IllegalStateException if the selected scope is absent
     * @throws RuntimeException if snapshot establishment or identity
     *         calculation fails
     */
    public String calculatePreInitializationScopeNodeBlueId(
            String scopePath) {
        String normalized =
                PointerUtils.normalizeScope(
                        scopePath);
        metrics.incrementInitializationDocumentIdContentBlueIdCalculations();
        syncMaterializedView();
        ResolvedSnapshot current = snapshot();
        FrozenNode exactScope =
                current != null
                        ? current.canonicalAt(
                                normalized)
                        : null;
        if (exactScope != null) {
            return exactScope.blueId();
        }
        Node selectedScope =
                materializedView.nodeAt(
                        normalized);
        if (selectedScope == null) {
            throw new IllegalStateException(
                    "Exact selected scope is absent at "
                            + normalized);
        }
        return BlueIdCalculator.calculateBlueId(
                selectedScope);
    }

    /**
     * Opens a closeable working copy rooted at the supplied origin scope.
     * Changes remain private until explicitly committed.
     *
     * <p>The returned working document owns its mutable copies. Closing it
     * without a commit discards those changes and does not alter this runtime.
     * The origin is normalized as an absolute processing scope.</p>
     *
     * @param originScopePath scope against which relative patches are resolved
     * @return invocation-bound closeable working copy
     * @throws RuntimeException if the initial snapshot cannot be established
     */
    public WorkingDocument workingDocument(String originScopePath) {
        return workingDocument(originScopePath, PatchSource.LEGACY_PUBLIC_API);
    }

    WorkingDocument workingDocument(String originScopePath, PatchSource mutablePatchSource) {
        String normalizedScope = PointerUtils.normalizeScope(originScopePath);
        ResolvedSnapshot current = snapshot;
        boolean materializedFallback = false;
        if (current == null && snapshotManager != null) {
            syncMaterializedView();
            current = snapshotFromDocument(materializedView.copyRoot());
            snapshot = current;
            sharedSnapshotVersion = stateVersion;
            materializedFallback = true;
        }
        if (current != null) {
            return new WorkingDocument(normalizedScope,
                    current.frozenCanonicalRoot(),
                    current.frozenResolvedRoot(),
                    conformanceEngine,
                    conformancePlannerOverride,
                    currentSnapshotManager(),
                    current,
                    materializedFallback,
                    !selectedDocumentBacked,
                    mutablePatchSource,
                    metrics,
                    scopes().keySet(),
                    executableBodyFieldsByType,
                    current.isResolutionComplete());
        }

        Node root = materializedView.copyRoot();
        FrozenNode canonical = FrozenNode.fromUncheckedCanonicalNode(root.clone());
        FrozenNode resolved = FrozenNode.fromResolvedNode(root.clone());
        return new WorkingDocument(normalizedScope,
                canonical,
                resolved,
                conformanceEngine,
                conformancePlannerOverride,
                currentSnapshotManager(),
                null,
                true,
                false,
                mutablePatchSource,
                metrics,
                scopes().keySet(),
                executableBodyFieldsByType,
                true);
    }

    /**
     * Returns the current effective node at a pointer without forcing a new
     * snapshot.
     *
     * @param path absolute or root-equivalent pointer to normalize
     * @return fresh mutable node copy, or {@code null} when absent
     */
    public Node nodeAt(String path) {
        String normalized = PointerUtils.normalizePointer(path);
        if (snapshot != null) {
            return snapshot.resolvedNodeAt(normalized);
        }
        return materializedView.nodeAt(normalized);
    }

    /**
     * Tests whether the current effective document contains a node.
     *
     * @param path absolute or root-equivalent pointer
     * @return {@code true} when a node exists at the normalized pointer
     */
    public boolean contains(String path) {
        return nodeAt(path) != null;
    }

    /**
     * Validates and reports the processor-owned initialization marker.
     *
     * @param scopePath absolute processing scope
     * @return {@code true} when a valid initialization marker exists
     * @throws ProcessorFailureException if a present marker has an invalid
     *         wire shape
     */
    public boolean hasInitializationMarker(String scopePath) {
        String pointer = PointerUtils.resolvePointer(scopePath, ProcessorPointerConstants.RELATIVE_INITIALIZED);
        FrozenNode selected = selectedFrozenAt(pointer);
        Node marker = selected != null ? selected.toNode() : null;
        if (marker == null) {
            return false;
        }
        ProcessorEngine.validateInitializationMarker(marker, pointer);
        return true;
    }

    /**
     * Reads and validates the processor-owned termination marker.
     *
     * @param scopePath absolute processing scope
     * @return validated marker projection, or {@code null} when absent
     * @throws ProcessorFailureException if a present marker has an invalid
     *         wire shape
     */
    public ProcessorEngine.TerminationMarker terminationMarker(String scopePath) {
        String pointer = PointerUtils.resolvePointer(scopePath, ProcessorPointerConstants.RELATIVE_TERMINATED);
        FrozenNode selected = selectedFrozenAt(pointer);
        Node marker = selected != null ? selected.toNode() : null;
        if (marker == null) {
            return null;
        }
        return ProcessorEngine.validateTerminationMarker(marker, pointer);
    }

    /**
     * Tests for a valid processor-owned termination marker.
     *
     * @param scopePath absolute processing scope
     * @return {@code true} when a valid marker exists
     * @throws ProcessorFailureException if a present marker has an invalid
     *         wire shape
     */
    public boolean hasTerminationMarker(String scopePath) {
        return terminationMarker(scopePath) != null;
    }

    /**
     * Finalizes invocation scope state from its persisted marker, if present.
     *
     * @param scopePath absolute processing scope
     * @throws ProcessorFailureException if a present marker has an invalid
     *         wire shape
     */
    public void markScopeTerminatedFromMarker(String scopePath) {
        ProcessorEngine.TerminationMarker marker = terminationMarker(scopePath);
        if (marker == null) {
            return;
        }
        scope(scopePath).finalizeTermination(marker.reason);
    }

    /**
     * Atomically writes processor-managed state without emitting an
     * application Document Update.
     *
     * <p>Semantic identity work and mutation-path validation occur before
     * publication; snapshot and materialized views roll back together on
     * failure. A non-null value is cloned before publication; {@code null}
     * removes the addressed node. Gas admitted before a later failure remains
     * in the invocation ledger.</p>
     *
     * @param path absolute processor-managed mutation path
     * @param value replacement value, or {@code null} to remove the path
     * @throws ProcessorFailureException if the path crosses a forbidden
     *         mutation boundary
     * @throws GasLimitExceededException if semantic or operation gas exceeds
     *         the remaining invocation budget
     * @throws RuntimeException if conformance, identity, or snapshot
     *         resolution fails; document and snapshot state are rolled back
     */
    public void directWrite(String path, Node value) {
        validateMutationPathWithoutResolution(path);
        chargeSemanticIdentityWork(
                PointerUtils.normalizePointer(path),
                value == null ? JsonPatch.Op.REMOVE : JsonPatch.Op.REPLACE,
                value,
                null,
                false);
        if (usesAuthoritativeSelectedSnapshot()) {
            directWriteSelected(path, value);
            changedPaths.add(PointerUtils.normalizePointer(path));
            return;
        }
        if (snapshotManager != null && snapshot != null) {
            directWriteSnapshot(path, value);
            changedPaths.add(PointerUtils.normalizePointer(path));
            return;
        }
        Node rollback = materializedView.copyRoot();
        ResolvedSnapshot snapshotRollback = snapshot;
        try {
            PlanningContext planning = planningContext(rollback);
            FrozenNode before = planning.canonicalPlanner.read(path);
            Node beforeNode = before != null ? before.toNode() : null;
            JsonPatch snapshotPatch = directWritePatch(path, beforeNode, value);
            if (snapshotPatch == null) {
                return;
            }
            planning.canonicalPlanner.plan(
                    JsonPointer.ROOT, snapshotPatch);
            ImmutablePatchPlanner.PatchPlan resolvedPlan =
                    planning.resolvedPlanner.plan(
                            JsonPointer.ROOT, snapshotPatch);
            SnapshotPatchPlan snapshotPatchPlan = prepareSnapshotPatch(planning.baseSnapshot, snapshotPatch);
            commitSnapshotPatch(snapshotPatchPlan, resolvedPlan.root());
            changedPaths.add(PointerUtils.normalizePointer(path));
        } catch (RuntimeException ex) {
            materializedView.replaceWith(rollback);
            snapshot = snapshotRollback;
            materializedViewStale = false;
            throw ex;
        }
    }

    private void directWriteSelected(String path, Node value) {
        Node selectedRollback = materializedView.copyRoot();
        ResolvedSnapshot snapshotRollback = snapshot;
        try {
            Node tentativeSelected = selectedRollback.clone();
            materializeDirectWriteReferenceAncestors(
                    tentativeSelected, path);
            Node before = ImmutablePatchPlanner.readNode(
                    tentativeSelected, path);
            JsonPatch patch = directWritePatch(path, before, value);
            if (patch == null) {
                return;
            }
            applyMaterializedDirectWrite(tentativeSelected, path, value);
            ResolvedSnapshot authoritative = snapshotFromDocument(tentativeSelected);
            boolean published =
                    authoritative.isResolutionComplete();
            ResolvedSnapshot cached = cacheSnapshotIfComplete(
                    currentSnapshotManager(), authoritative);
            materializedView.replaceWith(tentativeSelected);
            snapshot = cached;
            materializedViewStale = false;
            markStateAdvanced(published);
        } catch (RuntimeException ex) {
            materializedView.replaceWith(selectedRollback);
            snapshot = snapshotRollback;
            materializedViewStale = false;
            throw ex;
        }
    }

    /**
     * Opens every proper reference ancestor of a processor-owned write through
     * the invocation's verified exact-materialization boundary.
     *
     * <p>Writing below a pure reference without opening it would create a
     * forbidden mixed {@code blueId + payload} Source node. Exact
     * materialization also makes the pre-write value visible so add, replace,
     * remove, and no-op classification remain correct.</p>
     */
    private void materializeDirectWriteReferenceAncestors(
            Node root,
            String path) {
        List<String> segments = JsonPointer.split(path);
        ProcessingSnapshotManager manager = currentSnapshotManager();
        for (int depth = 0; depth < segments.size(); depth++) {
            String prefix = JsonPointer.toPointer(
                    segments.subList(0, depth));
            Node ancestor = NodePathEditor.getOrNull(root, prefix);
            if (ancestor == null) {
                return;
            }
            if (!ancestor.isReferenceOnly()) {
                continue;
            }
            if (manager == null) {
                throw new IllegalStateException(
                        "Direct-write ancestor materialization requires the active "
                                + "ProcessingSnapshotManager");
            }
            Node exact = verifiedExactMaterialization(
                    manager,
                    FrozenNode.fromNode(ancestor),
                    DIRECT_WRITE_ANCESTOR_PURPOSE)
                    .toNode();
            NodePathEditor.put(root, prefix, exact);
        }
    }

    private void directWriteSnapshot(String path, Node value) {
        ResolvedSnapshot snapshotRollback = snapshot;
        try {
            PlanningContext planning = planningContext(materializedView.root());
            FrozenNode before = planning.canonicalPlanner.read(path);
            Node beforeNode = before != null ? before.toNode() : null;
            JsonPatch snapshotPatch = directWritePatch(path, beforeNode, value);
            if (snapshotPatch == null) {
                return;
            }
            ImmutablePatchPlanner.PatchPlan canonicalPlan =
                    planning.canonicalPlanner.planWithExactReplacement(
                            JsonPointer.ROOT, snapshotPatch);
            ResolvedSnapshot next;
            try {
                next = planning.resolveCanonical(canonicalPlan.root());
            } catch (RuntimeException resolutionFailure) {
                if (!isTerminationMarkerProviderFailure(path, value, resolutionFailure)) {
                    throw resolutionFailure;
                }
                // A fatal provider error must remain reportable even though the
                // unavailable reference is still present elsewhere in the
                // document. The base snapshot already contains its verified
                // resolved lane, so splice only the processor-owned marker into
                // both immutable lanes without attempting provider resolution a
                // second time.
                ImmutablePatchPlanner.PatchPlan resolvedPlan =
                        planning.resolvedPlanner
                                .planWithExactReplacement(
                                        JsonPointer.ROOT,
                                        snapshotPatch);
                next = snapshotWithCompleteness(
                        canonicalPlan.root(),
                        resolvedPlan.root(),
                        planning.isResolutionComplete(),
                        false);
            }
            boolean published =
                    next.isResolutionComplete();
            snapshot = cacheSnapshotIfComplete(
                    currentSnapshotManager(), next);
            commitMaterializedSnapshot(snapshot);
            markStateAdvanced(published);
        } catch (RuntimeException ex) {
            snapshot = snapshotRollback;
            throw ex;
        }
    }

    private boolean isTerminationMarkerProviderFailure(String path,
                                                       Node value,
                                                       RuntimeException failure) {
        String normalizedPath = PointerUtils.canonicalizePointer(path);
        Node type = value != null ? value.getType() : null;
        if (!normalizedPath.endsWith(ProcessorPointerConstants.RELATIVE_TERMINATED)
                || type == null
                || !RuntimeBlueIds.PROCESSING_TERMINATED_MARKER.equals(type.getBlueId())) {
            return false;
        }
        return ScopeIdentityErrorMapper.isProviderIdentityFailure(failure);
    }

    private void applyMaterializedDirectWrite(Node root, String path, Node value) {
        if (value == null) {
            removeMaterializedPath(root, path);
        } else {
            NodePathEditor.put(root, path, value.clone());
        }
    }

    private void removeMaterializedPath(Node root, String path) {
        List<String> segments = JsonPointer.split(path);
        if (segments.isEmpty()) {
            root.replaceWith(new Node());
            return;
        }
        List<String> parentSegments = new ArrayList<>(segments.subList(0, segments.size() - 1));
        Node parent = NodePathEditor.getOrNull(root, JsonPointer.toPointer(parentSegments));
        if (parent == null) {
            return;
        }
        String leaf = segments.get(segments.size() - 1);
        if (Properties.OBJECT_TYPE.equals(leaf)) {
            parent.type((Node) null);
        } else if (Properties.OBJECT_ITEM_TYPE.equals(leaf)) {
            parent.itemType((Node) null);
        } else if (Properties.OBJECT_KEY_TYPE.equals(leaf)) {
            parent.keyType((Node) null);
        } else if (Properties.OBJECT_VALUE_TYPE.equals(leaf)) {
            parent.valueType((Node) null);
        } else if (Properties.OBJECT_BLUE.equals(leaf)) {
            parent.blue(null);
        } else if (ProcessorContractConstants.KEY_CONTRACTS.equals(leaf)) {
            parent.contracts(null);
        } else if (JsonPointer.isArrayIndexSegment(leaf) && parent.getItems() != null && !"-".equals(leaf)) {
            int index = Integer.parseInt(leaf);
            if (index >= 0 && index < parent.getItems().size()) {
                parent.getItems().remove(index);
            }
        } else if (parent.getProperties() != null) {
            parent.getProperties().remove(leaf);
        }
    }

    /**
     * Applies one application patch atomically and returns its exact update
     * projection, or {@code null} for a null/no-op input.
     *
     * <p>The mutable patch value is defensively frozen before planning.
     * Document and snapshot state roll back together on failure; already
     * admitted gas remains in the invocation ledger.</p>
     *
     * @param originScopePath scope against which the patch path is resolved
     * @param patch authored mutable patch, or {@code null}
     * @return committed update projection, or {@code null} for no input or no
     *         resulting update
     * @throws ProcessorFailureException if validation or conformance rejects
     *         the patch
     * @throws GasLimitExceededException if the remaining budget is
     *         insufficient
     * @throws RuntimeException if snapshot resolution or commit preparation
     *         fails; document and snapshot state are rolled back
     */
    public DocumentUpdateData applyPatch(String originScopePath, JsonPatch patch) {
        return applyPatch(originScopePath, patch, PatchSource.LEGACY_PUBLIC_API);
    }

    /**
     * Applies one mutable patch atomically with explicit source attribution.
     *
     * @param originScopePath scope against which the patch path is resolved
     * @param patch authored mutable patch, or {@code null}
     * @param source trace source category; {@code null} becomes the unknown
     *        internal source
     * @return committed update projection, or {@code null} for no input or no
     *         resulting update
     * @throws ProcessorFailureException if validation or conformance rejects
     *         the patch
     * @throws GasLimitExceededException if the remaining budget is
     *         insufficient
     * @throws RuntimeException if snapshot resolution or commit preparation
     *         fails; document and snapshot state are rolled back
     */
    public DocumentUpdateData applyPatch(String originScopePath, JsonPatch patch, PatchSource source) {
        if (patch == null) {
            return null;
        }
        List<DocumentUpdateData> updates = applyPatches(originScopePath, Collections.singletonList(patch), source);
        return updates.isEmpty() ? null : updates.get(0);
    }

    /**
     * Applies an ordered patch list as one rollback-all transaction.
     *
     * <p>Mutable values are defensively captured before planning. A
     * {@code null} or empty list is a no-op.</p>
     *
     * @param originScopePath scope against which patch paths are resolved
     * @param patches ordered mutable patches
     * @return ordered committed update projections, or an empty list
     * @throws ProcessorFailureException if any patch fails validation or
     *         conformance
     * @throws GasLimitExceededException if the remaining budget is
     *         insufficient
     * @throws RuntimeException if planning, resolution, or commit preparation
     *         fails; the whole document/snapshot transaction is rolled back
     */
    public List<DocumentUpdateData> applyPatches(String originScopePath, List<JsonPatch> patches) {
        return applyPatches(originScopePath, patches, PatchSource.LEGACY_PUBLIC_API);
    }

    /**
     * Applies an ordered mutable patch list atomically with source attribution.
     *
     * @param originScopePath scope against which patch paths are resolved
     * @param patches ordered mutable patches, or {@code null}
     * @param source trace source category; {@code null} becomes the unknown
     *        internal source
     * @return ordered committed update projections, or an empty list
     * @throws ProcessorFailureException if any patch fails validation or
     *         conformance
     * @throws GasLimitExceededException if the remaining budget is
     *         insufficient
     * @throws RuntimeException if planning, resolution, or commit preparation
     *         fails; the whole document/snapshot transaction is rolled back
     */
    public List<DocumentUpdateData> applyPatches(String originScopePath,
                                                 List<JsonPatch> patches,
                                                 PatchSource source) {
        if (patches == null || patches.isEmpty()) {
            return Collections.emptyList();
        }
        return applyPatchInputs(originScopePath, PatchInput.mutableList(patches, source));
    }

    /**
     * Frozen-value counterpart of {@link #applyPatch(String, JsonPatch)}.
     *
     * @param originScopePath scope against which the patch path is resolved
     * @param patch immutable authored patch, or {@code null}
     * @return committed update projection, or {@code null} for no input or no
     *         resulting update
     * @throws ProcessorFailureException if validation or conformance rejects
     *         the patch
     * @throws GasLimitExceededException if the remaining budget is
     *         insufficient
     * @throws RuntimeException if planning, resolution, or commit preparation
     *         fails; document and snapshot state are rolled back
     */
    public DocumentUpdateData applyFrozenPatch(String originScopePath, FrozenJsonPatch patch) {
        if (patch == null) {
            return null;
        }
        List<DocumentUpdateData> updates = applyFrozenPatches(
                originScopePath, Collections.singletonList(patch));
        return updates.isEmpty() ? null : updates.get(0);
    }

    /**
     * Applies frozen patches as one rollback-all atomic transaction.
     *
     * <p>Immutable patch objects may be retained during planning; their values
     * require no additional defensive copy.</p>
     *
     * @param originScopePath scope against which patch paths are resolved
     * @param patches ordered immutable patches, or {@code null}
     * @return ordered committed update projections, or an empty list
     * @throws ProcessorFailureException if any patch fails validation or
     *         conformance
     * @throws GasLimitExceededException if the remaining budget is
     *         insufficient
     * @throws RuntimeException if planning, resolution, or commit preparation
     *         fails; the whole document/snapshot transaction is rolled back
     */
    public List<DocumentUpdateData> applyFrozenPatches(String originScopePath,
                                                       List<FrozenJsonPatch> patches) {
        if (patches == null || patches.isEmpty()) {
            return Collections.emptyList();
        }
        return applyPatchInputs(originScopePath, PatchInput.frozenList(patches));
    }

    private List<DocumentUpdateData> applyPatchInputs(String originScopePath,
                                                      List<PatchInput> patches) {
        Node selectedRollback = selectedDocumentBacked ? materializedView.copyRoot() : null;
        ResolvedSnapshot snapshotRollback = snapshot;
        batchPatchCalls++;
        batchPatchEntries += patches.size();
        if (patches.size() == 1) {
            singletonPatchTransactions++;
            metrics.incrementSingletonPatchTransactions();
        }
        try {
            preflightPatchInputsWithoutResolution(patches);
            PlanningContext planning = planningContext(materializedView.root());
            chargeSemanticIdentityWork(patches);
            BatchPatchTransaction transaction = BatchPatchTransaction.fromInputs(originScopePath,
                    patches,
                    planning,
                    currentConformanceEngine(),
                    conformancePlannerOverride,
                    updateMaterializationMetrics(),
                    !usesAuthoritativeSelectedSnapshot(),
                    metrics);
            BatchPatchResult result = transaction.apply();
            batchPatchPlanningNanos += result.patchPlanningNanos();
            batchPatchConformanceNanos += result.conformanceNanos();
            batchPatchBuildUpdatesNanos += result.buildUpdatesNanos();
            metrics.addBatchPatchPlanningNanos(result.patchPlanningNanos());
            metrics.addBatchPatchConformanceNanos(result.conformanceNanos());
            metrics.addBatchPatchBuildUpdatesNanos(result.buildUpdatesNanos());
            long commitStart = System.nanoTime();
            List<DocumentUpdateData> updates;
            try {
                updates = commitBatchPatchResult(result);
            } finally {
                long commitNanos = System.nanoTime() - commitStart;
                batchPatchCommitNanos += commitNanos;
                metrics.addBatchPatchCommitNanos(commitNanos);
                metrics.addSnapshotCommitNanos(commitNanos);
            }
            for (DocumentUpdateData update : updates) {
                changedPaths.add(PointerUtils.normalizePointer(update.path()));
            }
            return updates;
        } catch (RuntimeException ex) {
            snapshot = snapshotRollback;
            if (selectedRollback != null) {
                materializedView.replaceWith(selectedRollback);
                materializedViewStale = false;
            } else if (snapshotRollback != null) {
                materializedView.replaceWithSnapshot(snapshotRollback);
                materializedViewStale = false;
            }
            throw ex;
        }
    }

    /**
     * Admits identity establishment/rebuild work before patch planning performs
     * any of it. The physical identity cache is intentionally irrelevant.
     */
    private void chargeSemanticIdentityWork(List<PatchInput> patches) {
        for (PatchInput patch : patches) {
            if (patch == null) {
                continue;
            }
            chargeSemanticIdentityWork(
                    PointerUtils.normalizePointer(patch.authoredPath()),
                    patch.op(),
                    patch.mutableValue(),
                    patch.frozenValue(),
                    patch.exactValue() != null);
        }
    }

    void validateMutationPathWithoutResolution(PatchInput patch) {
        if (patch != null) {
            validateMutationPathWithoutResolution(patch.authoredPath());
        }
    }

    void validateProcessEmbeddedTraversalWithoutResolution(
            String path) {
        ImmutablePatchPlanner.forFrozen(
                canonicalRootWithoutResolution())
                .validateProcessEmbeddedTraversalPath(path);
    }

    private void validateMutationPathWithoutResolution(String path) {
        ImmutablePatchPlanner.forFrozen(canonicalRootWithoutResolution())
                .validateMutationPath(path);
    }

    private void preflightPatchInputsWithoutResolution(List<PatchInput> patches) {
        FrozenNode workingRoot = canonicalRootWithoutResolution();
        boolean exactReplacement = !selectedDocumentBacked;
        for (PatchInput input : patches) {
            if (input == null) {
                continue;
            }
            ImmutablePatchPlanner planner = ImmutablePatchPlanner.forFrozen(workingRoot);
            workingRoot = planner.applyMutationPreflight(
                    input.op(),
                    ParsedJsonPointer.parse(input.authoredPath()),
                    preflightValue(input, workingRoot),
                    exactReplacement);
        }
    }

    private FrozenNode preflightValue(PatchInput input,
                                      FrozenNode modeRoot) {
        if (input.op() == JsonPatch.Op.REMOVE) {
            return null;
        }
        FrozenNode frozen = input.frozenValue();
        if (frozen != null) {
            return FrozenNode.authoredValueInModeOf(frozen, modeRoot);
        }
        Node value = Objects.requireNonNull(
                input.mutableValue(), "patch value");
        if (!modeRoot.isStrictCanonical()) {
            return FrozenNode.fromResolvedNode(value);
        }
        return modeRoot.isStrictBlueIdValidation()
                ? FrozenNode.fromNode(value)
                : FrozenNode.fromUncheckedCanonicalNode(value);
    }

    private FrozenNode canonicalRootWithoutResolution() {
        ResolvedSnapshot current = snapshot;
        return current != null
                ? current.frozenCanonicalRoot()
                : FrozenNode.fromResolvedNode(materializedView.root());
    }

    private void chargeSemanticIdentityWork(String path,
                                            JsonPatch.Op operation,
                                            Node mutableValue,
                                            FrozenNode frozenValue,
                                            boolean valueAlreadyAdmitted) {
        SemanticGasMeter semantic = gasMeter.semantic();
        GasChargeContext context = GasChargeContext.of(
                null, null, path, "identity-rebuild");
        if (!valueAlreadyAdmitted
                && mutableValue != null) {
            chargeMutableIdentitySubtree(
                    mutableValue,
                    semantic,
                    context,
                    new IdentityHashMap<Node, Boolean>());
        } else if (!valueAlreadyAdmitted
                && frozenValue != null) {
            chargeFrozenIdentitySubtree(
                    frozenValue,
                    semantic,
                    context,
                    new IdentityHashMap<FrozenNode, Boolean>());
        }

        FrozenNode root = snapshot != null
                ? snapshot.frozenCanonicalRoot()
                : FrozenNode.fromNode(materializedView.copyRoot());
        List<String> segments = JsonPointer.split(path);
        if (!segments.isEmpty()) {
            String parentPointer = JsonPointer.toPointer(
                    segments.subList(0, segments.size() - 1));
            FrozenNode parent = root.at(parentPointer);
            chargeListPatchFold(
                    parent,
                    segments.get(segments.size() - 1),
                    mutableValue != null || frozenValue != null,
                    context);
        }

        for (int count = Math.max(0, segments.size() - 1);
             count >= 0;
             count--) {
            String ancestorPath = JsonPointer.toPointer(
                    segments.subList(0, count));
            FrozenNode ancestor = root.at(ancestorPath);
            if (ancestor == null) {
                continue;
            }
            enforceRebuiltContainerLimit(
                    ancestor,
                    ancestorPath,
                    path,
                    operation);
            semantic.nodeIdentitiesEstablished(1L, context);
            if (!ancestor.hasItems()) {
                long members = directMemberCount(ancestor);
                semantic.objectMembersRebuilt(members, context);
                semantic.directIdentityInput(
                        NodeCanonicalizer.directIdentityCanonicalSize(
                                ancestor.toNode()),
                        context);
            }
        }
    }

    private void chargeListPatchFold(FrozenNode parent,
                                     String finalSegment,
                                     boolean resultContainsWrittenValue,
                                     GasChargeContext context) {
        if (parent == null || !parent.hasItems()) {
            return;
        }
        long beforeLength = parent.getItems().size();
        long index;
        if ("-".equals(finalSegment)) {
            index = beforeLength;
        } else {
            try {
                index = Long.parseLong(finalSegment);
            } catch (NumberFormatException ignored) {
                return;
            }
        }
        SemanticGasMeter semantic = gasMeter.semantic();
        if (!resultContainsWrittenValue) {
            long resultLength = Math.max(0L, beforeLength - 1L);
            semantic.listRemoveAt(resultLength, index, context);
        } else if (index >= beforeLength) {
            semantic.verifiedListAppend(beforeLength, 1L, context);
        } else {
            semantic.listReplaceAt(beforeLength, index, context);
        }
    }

    private void chargeMutableIdentitySubtree(
            Node node,
            SemanticGasMeter semantic,
            GasChargeContext context,
            IdentityHashMap<Node, Boolean> visited) {
        if (node == null
                || node.isReferenceOnly()
                || visited.put(node, Boolean.TRUE) != null) {
            return;
        }
        enforceMaterializedContainerLimit(node);
        semantic.nodeIdentitiesEstablished(1L, context);
        if (node.getItems() != null) {
            for (Node item : node.getItems()) {
                chargeMutableIdentitySubtree(
                        item, semantic, context, visited);
            }
            semantic.fullListIdentity(node.getItems().size(), context);
        } else {
            semantic.objectMembersRebuilt(
                    directMemberCount(node), context);
            semantic.directIdentityInput(
                    NodeCanonicalizer.directIdentityCanonicalSize(node),
                    context);
        }
        chargeMutableIdentitySubtree(
                node.getType(), semantic, context, visited);
        chargeMutableIdentitySubtree(
                node.getItemType(), semantic, context, visited);
        chargeMutableIdentitySubtree(
                node.getKeyType(), semantic, context, visited);
        chargeMutableIdentitySubtree(
                node.getValueType(), semantic, context, visited);
        chargeMutableIdentitySubtree(
                node.getContracts(), semantic, context, visited);
        chargeMutableIdentitySubtree(
                node.getBlue(), semantic, context, visited);
        if (node.getProperties() != null) {
            for (Node child : node.getProperties().values()) {
                chargeMutableIdentitySubtree(
                        child, semantic, context, visited);
            }
        }
    }

    private void chargeFrozenIdentitySubtree(
            FrozenNode node,
            SemanticGasMeter semantic,
            GasChargeContext context,
            IdentityHashMap<FrozenNode, Boolean> visited) {
        if (node == null
                || node.isReferenceOnly()
                || visited.put(node, Boolean.TRUE) != null) {
            return;
        }
        enforceMaterializedContainerLimit(node);
        semantic.nodeIdentitiesEstablished(1L, context);
        if (node.hasItems()) {
            for (FrozenNode item : node.getItems()) {
                chargeFrozenIdentitySubtree(
                        item, semantic, context, visited);
            }
            semantic.fullListIdentity(node.getItems().size(), context);
        } else {
            semantic.objectMembersRebuilt(
                    directMemberCount(node), context);
            semantic.directIdentityInput(
                    NodeCanonicalizer.directIdentityCanonicalSize(
                            node.toNode()),
                    context);
        }
        chargeFrozenIdentitySubtree(
                node.getType(), semantic, context, visited);
        chargeFrozenIdentitySubtree(
                node.getItemType(), semantic, context, visited);
        chargeFrozenIdentitySubtree(
                node.getKeyType(), semantic, context, visited);
        chargeFrozenIdentitySubtree(
                node.getValueType(), semantic, context, visited);
        chargeFrozenIdentitySubtree(
                node.getContracts(), semantic, context, visited);
        chargeFrozenIdentitySubtree(
                node.getBlue(), semantic, context, visited);
        if (node.getProperties() != null) {
            for (FrozenNode child : node.getProperties().values()) {
                chargeFrozenIdentitySubtree(
                        child, semantic, context, visited);
            }
        }
    }

    private void enforceRebuiltContainerLimit(FrozenNode container,
                                              String containerPath,
                                              String patchPath,
                                              JsonPatch.Op operation) {
        long observed;
        String limitName;
        if (container.hasItems()) {
            observed = container.getItems().size();
            limitName = GasScheduleConstants.PortableLimit.DIRECT_LIST_ITEMS;
        } else {
            observed = directMemberCount(container);
            limitName = GasScheduleConstants.PortableLimit.DIRECT_OBJECT_ENTRIES;
        }
        String parent = parentPointer(patchPath);
        if (containerPath.equals(parent)) {
            FrozenNode existing = container.at(
                    JsonPointer.ROOT
                            + JsonPointer.escape(
                                    lastSegment(patchPath)));
            if (operation == JsonPatch.Op.REMOVE && existing != null) {
                observed--;
            } else if ((operation == JsonPatch.Op.ADD
                    || operation == JsonPatch.Op.REPLACE)
                    && existing == null) {
                observed++;
            }
        }
        enforcePortableLimit(limitName, observed);
    }

    private void enforceMaterializedContainerLimit(Node node) {
        if (node.getItems() != null) {
            enforcePortableLimit(
                    GasScheduleConstants.PortableLimit.DIRECT_LIST_ITEMS,
                    node.getItems().size());
        } else {
            enforcePortableLimit(
                    GasScheduleConstants.PortableLimit.DIRECT_OBJECT_ENTRIES,
                    directMemberCount(node));
        }
    }

    private void enforceMaterializedContainerLimit(FrozenNode node) {
        if (node.hasItems()) {
            enforcePortableLimit(
                    GasScheduleConstants.PortableLimit.DIRECT_LIST_ITEMS,
                    node.getItems().size());
        } else {
            enforcePortableLimit(
                    GasScheduleConstants.PortableLimit.DIRECT_OBJECT_ENTRIES,
                    directMemberCount(node));
        }
    }

    private void enforcePortableLimit(String limitName, long observed) {
        enforcePortableLimit(
                ProcessorErrorCategory.DirectNodeLimitExceeded,
                limitName,
                observed);
    }

    private void enforcePortableLimit(
            ProcessorErrorCategory category,
            String limitName,
            long observed) {
        long limit = gasMeter.schedule().portableLimit(limitName);
        if (observed > limit) {
            throw new PortableLimitExceededException(
                    category,
                    limitName,
                    observed,
                    limit);
        }
    }

    private String parentPointer(String pointer) {
        List<String> segments = JsonPointer.split(pointer);
        return segments.isEmpty()
                ? JsonPointer.ROOT
                : JsonPointer.toPointer(
                segments.subList(0, segments.size() - 1));
    }

    private String lastSegment(String pointer) {
        List<String> segments = JsonPointer.split(pointer);
        return segments.isEmpty()
                ? ""
                : segments.get(segments.size() - 1);
    }

    private long directMemberCount(Node node) {
        long members = node.getProperties() != null
                ? node.getProperties().size() : 0L;
        if (node.getName() != null) members++;
        if (node.getDescription() != null) members++;
        if (node.getType() != null) members++;
        if (node.getItemType() != null) members++;
        if (node.getKeyType() != null) members++;
        if (node.getValueType() != null) members++;
        if (node.getValue() != null) members++;
        if (node.getSchema() != null) members++;
        if (node.getContracts() != null) members++;
        if (node.getBlue() != null) members++;
        if (node.getMergePolicy() != null) members++;
        return members;
    }

    private long directMemberCount(FrozenNode node) {
        long members = node.getProperties() != null
                ? node.getProperties().size() : 0L;
        if (node.getName() != null) members++;
        if (node.getDescription() != null) members++;
        if (node.getType() != null) members++;
        if (node.getItemType() != null) members++;
        if (node.getKeyType() != null) members++;
        if (node.getValueType() != null) members++;
        if (node.getValue() != null) members++;
        if (node.getSchema() != null) members++;
        if (node.getContracts() != null) members++;
        if (node.getBlue() != null) members++;
        if (node.getMergePolicy() != null) members++;
        return members;
    }

    List<DocumentUpdateData> applyPrecomputedPatch(String originScopePath,
                                                   JsonPatch patch,
                                                   WorkingDocument.PatchPreview preview) {
        if (patch == null) {
            return Collections.emptyList();
        }
        if (!canApplyPrecomputedPatch(originScopePath, patch, preview)) {
            return applyPatches(originScopePath, Collections.singletonList(patch));
        }
        Node selectedRollback = selectedDocumentBacked ? materializedView.copyRoot() : null;
        ResolvedSnapshot snapshotRollback = snapshot;
        batchPatchCalls++;
        batchPatchEntries++;
        try {
            chargeSemanticIdentityWork(Collections.singletonList(
                    PatchInput.mutable(patch)));
            long buildUpdatesStart = System.nanoTime();
            BatchPatchResult result;
            try {
                result = usesAuthoritativeSelectedSnapshot()
                        ? preview.result()
                        : preview.result().withMaterializationMetrics(updateMaterializationMetrics());
            } finally {
                long buildUpdatesNanos = System.nanoTime() - buildUpdatesStart;
                batchPatchBuildUpdatesNanos += buildUpdatesNanos;
                metrics.addBatchPatchBuildUpdatesNanos(buildUpdatesNanos);
            }
            long commitStart = System.nanoTime();
            List<DocumentUpdateData> updates;
            try {
                updates = commitBatchPatchResult(result);
            } finally {
                long commitNanos = System.nanoTime() - commitStart;
                batchPatchCommitNanos += commitNanos;
                metrics.addBatchPatchCommitNanos(commitNanos);
                metrics.addSnapshotCommitNanos(commitNanos);
            }
            for (DocumentUpdateData update : updates) {
                changedPaths.add(PointerUtils.normalizePointer(update.path()));
            }
            return updates;
        } catch (RuntimeException ex) {
            snapshot = snapshotRollback;
            if (selectedRollback != null) {
                materializedView.replaceWith(selectedRollback);
                materializedViewStale = false;
            } else if (snapshotRollback != null) {
                materializedView.replaceWithSnapshot(snapshotRollback);
                materializedViewStale = false;
            }
            throw ex;
        }
    }

    PreparedPatchSequence preparePatchSequence(String originScopePath,
                                               List<JsonPatch> patches,
                                               WorkingDocument.Preview preview) {
        return new PreparedPatchSequence(originScopePath, PatchInput.mutableList(patches), preview);
    }

    PreparedPatchSequence prepareFrozenPatchSequence(String originScopePath,
                                                     List<FrozenJsonPatch> patches,
                                                     WorkingDocument.Preview preview) {
        return new PreparedPatchSequence(originScopePath, PatchInput.frozenList(patches), preview);
    }

    PreparedPatchSequence preparePatchInputSequence(String originScopePath,
                                                    List<PatchInput> patches,
                                                    WorkingDocument.Preview preview) {
        return new PreparedPatchSequence(originScopePath, patches, preview);
    }

    private boolean canApplyPrecomputedPatch(String originScopePath,
                                             JsonPatch patch,
                                             WorkingDocument.PatchPreview preview) {
        if (preview == null
                || !PointerUtils.normalizeScope(originScopePath).equals(preview.originScope())
                || !preview.matches(patch)) {
            return false;
        }
        ResolvedSnapshot current = snapshot();
        return current != null
                && preview.isBasedOn(
                current.frozenCanonicalRoot(),
                current.frozenResolvedRoot(),
                current.isResolutionComplete());
    }

    private UpdateMaterializationMetrics updateMaterializationMetrics() {
        return new UpdateMaterializationMetrics() {
            /** {@inheritDoc} */
            @Override
            public void recordBeforeNodeMaterialization() {
                documentUpdateBeforeNodeMaterializations++;
                metrics.incrementDocumentUpdateBeforeMaterializations();
            }

            /** {@inheritDoc} */
            @Override
            public void recordAfterNodeMaterialization() {
                documentUpdateAfterNodeMaterializations++;
                metrics.incrementDocumentUpdateAfterMaterializations();
            }
        };
    }

    private JsonPatch directWritePatch(String path, Node before, Node value) {
        if (value == null) {
            return before == null ? null : JsonPatch.remove(path);
        }
        return before == null
                ? JsonPatch.add(path, value.clone())
                : JsonPatch.replace(path, value.clone());
    }

    private PlanningContext planningContext(Node rollback) {
        ProcessingSnapshotManager currentManager = currentSnapshotManager();
        if (currentManager == null || canPlanFromSelectedWithoutSnapshot()) {
            ImmutablePatchPlanner planner = ImmutablePatchPlanner.forMaterialized(rollback);
            return new PlanningContext(
                    null,
                    planner,
                    planner,
                    false,
                    null,
                    scopes().keySet(),
                    executableBodyFieldsByType,
                    true);
        }
        ResolvedSnapshot base = snapshot != null ? snapshot : snapshotFromDocument(rollback);
        return new PlanningContext(base,
                ImmutablePatchPlanner.forSnapshot(base),
                ImmutablePatchPlanner.forFrozen(base.frozenResolvedRoot()),
                !selectedDocumentBacked,
                !selectedDocumentBacked ? currentManager : null,
                scopes().keySet(),
                executableBodyFieldsByType,
                base.isResolutionComplete());
    }

    private boolean canPlanFromSelectedWithoutSnapshot() {
        return usesAuthoritativeSelectedSnapshot()
                && snapshot == null
                && conformanceEngine == null
                && (conformancePlannerOverride == null || !conformancePlannerOverride.applies());
    }

    private boolean usesAuthoritativeSelectedSnapshot() {
        return selectedDocumentBacked && snapshotManager != null;
    }

    static PlanningContext workingPlanningContext(FrozenNode canonicalRoot,
                                                  FrozenNode resolvedRoot,
                                                  boolean exactReplacement,
                                                  ProcessingSnapshotManager snapshotManager) {
        return workingPlanningContext(
                canonicalRoot,
                resolvedRoot,
                exactReplacement,
                snapshotManager,
                Collections.emptySet(),
                Collections.emptyMap(),
                true);
    }

    static PlanningContext workingPlanningContext(
            FrozenNode canonicalRoot,
            FrozenNode resolvedRoot,
            boolean exactReplacement,
            ProcessingSnapshotManager snapshotManager,
            Iterable<String> openedScopePaths) {
        return workingPlanningContext(
                canonicalRoot,
                resolvedRoot,
                exactReplacement,
                snapshotManager,
                openedScopePaths,
                Collections.emptyMap(),
                true);
    }

    static PlanningContext workingPlanningContext(
            FrozenNode canonicalRoot,
            FrozenNode resolvedRoot,
            boolean exactReplacement,
            ProcessingSnapshotManager snapshotManager,
            Iterable<String> openedScopePaths,
            Map<String, List<String>> executableBodyFieldsByType) {
        return workingPlanningContext(
                canonicalRoot,
                resolvedRoot,
                exactReplacement,
                snapshotManager,
                openedScopePaths,
                executableBodyFieldsByType,
                true);
    }

    static PlanningContext workingPlanningContext(
            FrozenNode canonicalRoot,
            FrozenNode resolvedRoot,
            boolean exactReplacement,
            ProcessingSnapshotManager snapshotManager,
            Iterable<String> openedScopePaths,
            Map<String, List<String>> executableBodyFieldsByType,
            boolean resolutionComplete) {
        return new PlanningContext(null,
                ImmutablePatchPlanner.forFrozen(canonicalRoot),
                ImmutablePatchPlanner.forFrozen(resolvedRoot),
                exactReplacement,
                exactReplacement ? snapshotManager : null,
                openedScopePaths,
                executableBodyFieldsByType,
                resolutionComplete);
    }

    private SnapshotPatchPlan prepareSnapshotPatch(ResolvedSnapshot base, JsonPatch patch) {
        ProcessingSnapshotManager currentManager = currentSnapshotManager();
        if (currentManager == null || base == null) {
            return null;
        }
        try {
            return new SnapshotPatchPlan(currentManager.applyPatch(base, patch));
        } catch (RuntimeException ex) {
            return new SnapshotPatchPlan(null);
        }
    }

    private void commitSnapshotPatch(SnapshotPatchPlan plan, FrozenNode fallbackRoot) {
        if (snapshotManager == null || plan == null) {
            materializedView.replaceWith(fallbackRoot.toNode());
            materializedViewStale = false;
            markStateAdvanced(false);
            return;
        }
        if (plan.next != null) {
            snapshot = plan.next;
            commitMaterializedSnapshot(snapshot);
            markStateAdvanced(false);
        } else {
            snapshot = snapshotFromDocument(fallbackRoot.toNode());
            commitMaterializedSnapshot(snapshot);
            markStateAdvanced(false);
        }
    }

    private List<DocumentUpdateData> commitBatchPatchResult(BatchPatchResult result) {
        return commitBatchPatchResult(result, true, currentSnapshotManager());
    }

    private List<DocumentUpdateData> commitBatchPatchResult(BatchPatchResult result,
                                                            boolean insertSharedSnapshot) {
        return commitBatchPatchResult(result, insertSharedSnapshot, snapshotManager);
    }

    private List<DocumentUpdateData> commitBatchPatchResult(BatchPatchResult result,
                                                            boolean insertSharedSnapshot,
                                                            ProcessingSnapshotManager commitSnapshotManager) {
        if (commitSnapshotManager == null) {
            Node next = result.resolvedRoot().toNode();
            materializedView.replaceWith(next);
            snapshot = null;
            materializedViewStale = false;
            markStateAdvanced(false);
            return result.updates();
        }
        if (selectedDocumentBacked) {
            Node tentativeSelected = tentativeSelectedRoot(result);
            ResolvedSnapshot authoritative = snapshotFromDocument(
                    tentativeSelected, true, commitSnapshotManager);
            long buildUpdatesStart = System.nanoTime();
            List<DocumentUpdateData> updates;
            try {
                updates = result.updatesAgainst(authoritative.frozenResolvedRoot(),
                        updateMaterializationMetrics());
            } finally {
                long buildUpdatesNanos = System.nanoTime() - buildUpdatesStart;
                batchPatchBuildUpdatesNanos += buildUpdatesNanos;
                metrics.addBatchPatchBuildUpdatesNanos(buildUpdatesNanos);
            }
            boolean published = insertSharedSnapshot
                    && authoritative.isResolutionComplete();
            ResolvedSnapshot committed = insertSharedSnapshot
                    ? cacheSnapshotIfComplete(
                    commitSnapshotManager, authoritative)
                    : authoritative;
            materializedView.replaceWith(tentativeSelected);
            snapshot = committed;
            materializedViewStale = false;
            markStateAdvanced(published);
            return updates;
        }
        ResolvedSnapshot next = snapshotWithCompleteness(
                result.canonicalRoot(),
                result.resolvedRoot(),
                result.isResolutionComplete(),
                insertSharedSnapshot);
        boolean published = insertSharedSnapshot
                && next.isResolutionComplete();
        ResolvedSnapshot committed = insertSharedSnapshot
                ? cacheSnapshotIfComplete(
                commitSnapshotManager, next)
                : next;
        snapshot = committed;
        commitMaterializedSnapshot(committed);
        markStateAdvanced(published);
        return result.updates();
    }

    private Node tentativeSelectedRoot(BatchPatchResult result) {
        FrozenNode tentative = FrozenNode.fromResolvedNode(materializedView.copyRoot());
        for (ImmutableJsonPatch patch : result.requestedPatches()) {
            tentative = ImmutablePatchPlanner.forFrozen(tentative)
                    .plan(JsonPointer.ROOT, patch)
                    .root();
        }
        Node tentativeSelected = tentative.toNode();
        for (BatchPatchResult.GeneralizationMetadataWrite write : result.generalizationMetadataWrites()) {
            NodePathEditor.put(tentativeSelected, write.path(), write.value().toNode());
        }
        return tentativeSelected;
    }

    private void commitMaterializedSnapshot(ResolvedSnapshot committed) {
        if (lazyMaterializedCommits) {
            materializedViewStale = true;
            return;
        }
        materializedView.replaceWithSnapshot(committed);
        materializedViewStale = false;
    }

    private void syncMaterializedView() {
        if (materializedViewStale && snapshot != null) {
            materializedView.replaceWithSnapshot(snapshot);
            materializedViewStale = false;
        }
    }

    private ResolvedSnapshot snapshotFromDocument(Node document) {
        return snapshotFromDocument(document, false);
    }

    private ResolvedSnapshot snapshotFromDocumentTransient(Node document) {
        return snapshotFromDocument(document, true);
    }

    private ResolvedSnapshot snapshotFromDocument(Node document, boolean transientResolution) {
        return snapshotFromDocument(document, transientResolution, currentSnapshotManager());
    }

    private ProcessingSnapshotManager currentSnapshotManager() {
        return activeSequenceSnapshotManager != null
                ? activeSequenceSnapshotManager
                : snapshotManager;
    }

    /**
     * Captures the snapshot-manager generation that owns the current runtime
     * operation. Each opened matcher session has independent local caches; a
     * runtime without a manager can still evaluate inline-only patterns, but
     * reference demand fails inside the matcher.
     */
    ExternalChannelFunctionEvaluation.MatcherSessionFactory
    externalChannelMatcherSessions() {
        ProcessingSnapshotManager captured =
                currentSnapshotManager();
        return ExternalChannelFunctionEvaluation
                .verifiedMatcherSessions(captured);
    }

    /**
     * Opens a selected Handler's deferred executable reference through the
     * snapshot manager that owns this invocation. This deliberately avoids the
     * ContractLoader's independent matching/provider configuration: provider
     * verification, transient references, and cache-generation ownership must
     * stay on the active processing snapshot boundary.
     */
    FrozenNode materializeSelectedExecutableReference(
            FrozenNode reference) {
        ProcessingSnapshotManager manager =
                currentSnapshotManager();
        if (manager == null) {
            throw new IllegalStateException(
                    "Selected executable body materialization requires the active "
                            + "ProcessingSnapshotManager");
        }
        FrozenNode materialized =
                verifiedExactMaterialization(
                        manager,
                        reference,
                        "Selected executable body");
        return materialized;
    }

    /**
     * Captures the verified snapshot boundary for one stored checkpoint
     * subject without opening the subject. The returned materializer performs
     * the provider demand only if a channel's newness policy asks for the
     * previous exact subject through {@link ChannelCheckpointContext#lastEvent()}.
     */
    Supplier<Node> checkpointSubjectMaterializer(
            Node subjectReference) {
        final Node capturedReference =
                Objects.requireNonNull(
                        subjectReference,
                        "subjectReference")
                        .clone();
        final ProcessingSnapshotManager capturedManager =
                currentSnapshotManager();
        return () -> {
            FrozenNode reference =
                    FrozenNode.fromNode(
                            capturedReference);
            if (!reference.isReferenceOnly()) {
                throw new ProcessorFailureException(
                        ProcessorErrorCategory
                                .InvalidProcessingDocument,
                        "Checkpoint subject must be an exact pure reference");
            }
            if (capturedManager == null) {
                throw new IllegalStateException(
                        "Checkpoint subject materialization requires the active "
                                + "ProcessingSnapshotManager");
            }
            return verifiedExactMaterialization(
                    capturedManager,
                    reference,
                    "Checkpoint subject")
                    .toNode();
        };
    }

    private static FrozenNode verifiedExactMaterialization(
            ProcessingSnapshotManager manager,
            FrozenNode reference,
            String purpose) {
        FrozenNode materialized =
                manager.materializeVerifiedExactReference(
                        reference);
        if (materialized == null) {
            throw new InvalidExecutionEvidenceException(
                    purpose
                            + " provider returned no content for "
                            + reference.getReferenceBlueId());
        }
        if (materialized.isReferenceOnly()) {
            throw new ProcessorFailureException(
                    ProcessorErrorCategory
                            .InvalidProcessingDocument,
                    purpose
                            + " provider returned a reference instead of exact content for "
                            + reference.getReferenceBlueId());
        }
        if (BlueIds.hasCyclicMemberSeparator(
                reference.getReferenceBlueId())) {
            /*
             * The active manager has already required complete cyclic-set
             * evidence. A MASTER#index member is not an independently
             * hashable ordinary node.
             */
            return materialized;
        }
        Node exact = materialized.toNode();
        final String actualBlueId;
        try {
            actualBlueId =
                    BlueIdCalculator.calculateBlueId(
                            exact);
        } catch (RuntimeException invalidContent) {
            throw new ProcessorFailureException(
                    ProcessorErrorCategory
                            .InvalidProcessingDocument,
                    purpose
                            + " provider content is not exact canonical content for "
                            + reference.getReferenceBlueId(),
                    invalidContent);
        }
        if (!reference.getReferenceBlueId()
                .equals(actualBlueId)) {
            throw new ProcessorFailureException(
                    ProcessorErrorCategory
                            .InvalidProcessingDocument,
                    purpose
                            + " provider content BlueId mismatch: expected "
                            + reference.getReferenceBlueId()
                            + " but calculated "
                            + actualBlueId);
        }
        return FrozenNode.fromNode(exact);
    }

    private ConformanceEngine currentConformanceEngine() {
        return activeSequenceSnapshotManager != null
                ? activeSequenceSnapshotManager.transientConformanceEngine(conformanceEngine)
                : conformanceEngine;
    }

    private ResolvedSnapshot snapshotFromDocument(Node document,
                                                  boolean transientResolution,
                                                  ProcessingSnapshotManager manager) {
        long start = System.nanoTime();
        try {
            Set<String> preservedPaths = new LinkedHashSet<>();
            if (selectedDocumentBacked) {
                preservedPaths.addAll(
                        executableBodyPaths(
                                document,
                                scopes().keySet(),
                                executableBodyFieldsByType,
                                manager));
            }
            /*
             * A final cyclic-set member is an opaque exact edge. Ordinary
             * scope resolution may carry it but must not open it merely
             * because an unrelated contract or patch needs a snapshot.
             */
            preservedPaths.addAll(
                    opaqueCyclicMemberPaths(document));
            if (!preservedPaths.isEmpty()) {
                ResolvedSnapshot preserved =
                        transientResolution
                        ? manager
                        .fromDocumentTransientPreservingPaths(
                                document, preservedPaths)
                        : manager.fromDocumentPreservingPaths(
                                document, preservedPaths);
                return forceDeferredResolution(
                        preserved);
            }
            return transientResolution
                    ? manager.fromDocumentTransient(document)
                    : manager.fromDocument(document);
        } finally {
            metrics.incrementProcessingSnapshotFromDocumentBuilds();
            metrics.addProcessingSnapshotFromDocumentNanos(System.nanoTime() - start);
        }
    }

    static Set<String> executableBodyPaths(
            Node document,
            Iterable<String> openedScopePaths,
            Map<String, List<String>> executableBodyFieldsByType) {
        return executableBodyPaths(
                document,
                openedScopePaths,
                executableBodyFieldsByType,
                null);
    }

    private static Set<String> executableBodyPaths(
            Node document,
            Iterable<String> openedScopePaths,
            Map<String, List<String>> executableBodyFieldsByType,
            ProcessingSnapshotManager exactMaterializer) {
        Set<String> result = new LinkedHashSet<>();
        Set<String> scopes = openedScopes(openedScopePaths);
        for (String scopePath : scopes) {
            Node scope = JsonPointer.ROOT.equals(scopePath)
                    ? document
                    : NodePathEditor.getOrNull(document, scopePath);
            collectExecutableBodyPaths(
                    scope,
                    JsonPointer.split(scopePath),
                    executableBodyFieldsByType,
                    result,
                    exactMaterializer);
        }
        return result;
    }

    static Set<String> executableBodyPaths(
            FrozenNode document,
            Iterable<String> openedScopePaths,
            Map<String, List<String>> executableBodyFieldsByType) {
        Set<String> result = new LinkedHashSet<>();
        Set<String> scopes = openedScopes(openedScopePaths);
        for (String scopePath : scopes) {
            FrozenNode scope = document != null
                    ? document.at(scopePath)
                    : null;
            collectExecutableBodyPaths(
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
        ProcessingSnapshotManager checkedManager =
                Objects.requireNonNull(manager, "snapshotManager");
        FrozenNode checkedRoot =
                Objects.requireNonNull(canonicalRoot, "canonicalRoot");
        Node document = checkedRoot.toNode();
        Set<String> preservedBodies = executableBodyPaths(
                document,
                openedScopePaths,
                executableBodyFieldsByType,
                checkedManager);
        preservedBodies.addAll(
                opaqueCyclicMemberPaths(document));
        if (preservedBodies.isEmpty()) {
            return checkedManager
                    .fromDocumentTransient(document);
        }
        return forceDeferredResolution(
                checkedManager
                        .fromDocumentTransientPreservingPaths(
                                document,
                                preservedBodies));
    }

    private static Set<String> opaqueCyclicMemberPaths(
            Node document) {
        Set<String> result = new LinkedHashSet<>();
        collectOpaqueCyclicMemberPaths(
                document,
                JsonPointer.ROOT,
                result,
                new IdentityHashMap<Node, Boolean>());
        return result;
    }

    private static void collectOpaqueCyclicMemberPaths(
            Node node,
            String path,
            Set<String> result,
            IdentityHashMap<Node, Boolean> visited) {
        if (node == null
                || visited.put(node, Boolean.TRUE) != null) {
            return;
        }
        if (node.isReferenceOnly()) {
            String blueId = node.getBlueId();
            if (BlueIds.hasCyclicMemberSeparator(blueId)) {
                result.add(path);
            }
            return;
        }
        if (node.getItems() != null) {
            for (int index = 0;
                 index < node.getItems().size();
                 index++) {
                collectOpaqueCyclicMemberPaths(
                        node.getItems().get(index),
                        JsonPointer.append(
                                path,
                                String.valueOf(index)),
                        result,
                        visited);
            }
        }
        if (node.getProperties() != null) {
            for (Map.Entry<String, Node> entry
                    : node.getProperties().entrySet()) {
                collectOpaqueCyclicMemberPaths(
                        entry.getValue(),
                        JsonPointer.append(
                                path,
                                entry.getKey()),
                        result,
                        visited);
            }
        }
        collectOpaqueCyclicMemberPaths(
                node.getContracts(),
                JsonPointer.append(path, ProcessorContractConstants.KEY_CONTRACTS),
                result,
                visited);
    }

    private static ResolvedSnapshot forceDeferredResolution(
            ResolvedSnapshot snapshot) {
        ResolvedSnapshot checked =
                Objects.requireNonNull(
                        snapshot, "preservedSnapshot");
        if (!checked.isResolutionComplete()) {
            return checked;
        }
        return ResolvedSnapshot.withDeferredResolution(
                checked.frozenCanonicalRoot(),
                checked.frozenResolvedRoot());
    }

    private static Set<String> openedScopes(
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

    private static void collectExecutableBodyPaths(
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
            contracts = verifiedExactMaterialization(
                    exactMaterializer,
                    FrozenNode.fromNode(contracts),
                    "Contracts-map recognition")
                    .toNode();
        }
        if (contracts != null
                && contracts.getProperties() != null) {
            for (Map.Entry<String, Node> entry
                    : contracts.getProperties().entrySet()) {
                Node contract = entry.getValue();
                if (contract != null
                        && contract.isReferenceOnly()
                        && exactMaterializer != null) {
                    contract = verifiedExactMaterialization(
                            exactMaterializer,
                            FrozenNode.fromNode(contract),
                            "Contract-header recognition")
                            .toNode();
                }
                List<String> fields =
                        executableBodyFieldsByType.get(
                                exactTypeBlueId(contract));
                if (fields != null) {
                    addHandlerEventMatcherPath(
                            contract,
                            path,
                            entry.getKey(),
                            result);
                    for (String field : fields) {
                        addExecutableBodyPath(
                                path,
                                entry.getKey(),
                                field,
                                result);
                    }
                }
            }
        }
    }

    private static void collectExecutableBodyPaths(
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
            List<String> fields =
                    executableBodyFieldsByType.get(
                            exactTypeBlueId(contract));
            if (fields != null) {
                addHandlerEventMatcherPath(
                        contract,
                        path,
                        entry.getKey(),
                        result);
                for (String field : fields) {
                    addExecutableBodyPath(
                            path,
                            entry.getKey(),
                            field,
                            result);
                }
            }
        }
    }

    private static void addHandlerEventMatcherPath(
            Node contract,
            List<String> scopePath,
            String contractKey,
            Set<String> result) {
        if (contract != null
                && contract.getProperties() != null
                && contract.getProperties().containsKey(
                EffectiveContractSnapshotConstants.DispatchField.EVENT)) {
            addExecutableBodyPath(
                    scopePath,
                    contractKey,
                    EffectiveContractSnapshotConstants.DispatchField.EVENT,
                    result);
        }
    }

    private static void addHandlerEventMatcherPath(
            FrozenNode contract,
            List<String> scopePath,
            String contractKey,
            Set<String> result) {
        if (contract != null
                && contract.getProperties() != null
                && contract.getProperties().containsKey(
                EffectiveContractSnapshotConstants.DispatchField.EVENT)) {
            addExecutableBodyPath(
                    scopePath,
                    contractKey,
                    EffectiveContractSnapshotConstants.DispatchField.EVENT,
                    result);
        }
    }

    private static void addExecutableBodyPath(
            List<String> scopePath,
            String contractKey,
            String field,
            Set<String> result) {
        List<String> bodyPath =
                new ArrayList<>(scopePath);
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
                : BlueIdCalculator.calculateBlueId(type);
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

    private void markStateAdvanced(boolean sharedSnapshotInserted) {
        stateVersion++;
        if (sharedSnapshotInserted) {
            sharedSnapshotVersion = stateVersion;
        }
    }

    private void promoteCurrentSequenceSnapshot(ProcessingSnapshotManager manager) {
        if (manager == null
                || snapshot == null
                || !snapshot.isResolutionComplete()
                || sharedSnapshotVersion == stateVersion) {
            return;
        }
        long start = System.nanoTime();
        ResolvedSnapshot cached =
                cacheSnapshotIfComplete(manager, snapshot);
        snapshot = cached;
        sharedSnapshotVersion = stateVersion;
        if (!selectedDocumentBacked) {
            commitMaterializedSnapshot(cached);
        }
        sequenceSharedSnapshotCacheInserts++;
        sequenceFinalSnapshotCacheInserts++;
        metrics.incrementSequenceSharedSnapshotCacheInserts();
        metrics.incrementSequenceFinalSnapshotCacheInserts();
        metrics.addSequenceFinalCacheCommitNanos(System.nanoTime() - start);
    }

    /**
     * Deferred resolved lanes are invocation-local. They retain exact
     * canonical identity, but presenting them to an arbitrary host manager's
     * publication hook could make that partial lane authoritative for the same
     * canonical cache key.
     */
    private static ResolvedSnapshot cacheSnapshotIfComplete(
            ProcessingSnapshotManager manager,
            ResolvedSnapshot candidate) {
        Objects.requireNonNull(manager, "snapshotManager");
        ResolvedSnapshot checked =
                Objects.requireNonNull(candidate, "snapshot");
        if (!checked.isResolutionComplete()) {
            return checked;
        }
        return Objects.requireNonNull(
                manager.cacheSnapshot(checked),
                "cachedSnapshot");
    }

    private static ResolvedSnapshot snapshotWithCompleteness(
            FrozenNode canonicalRoot,
            FrozenNode resolvedRoot,
            boolean resolutionComplete,
            boolean eagerIdentity) {
        if (resolutionComplete) {
            return eagerIdentity
                    ? new ResolvedSnapshot(
                    canonicalRoot,
                    resolvedRoot,
                    canonicalRoot.blueId())
                    : new ResolvedSnapshot(
                    canonicalRoot,
                    resolvedRoot);
        }
        return eagerIdentity
                ? deferredSnapshotWithEagerIdentity(
                        canonicalRoot,
                        resolvedRoot)
                : ResolvedSnapshot.withDeferredResolution(
                canonicalRoot,
                resolvedRoot);
    }

    private static ResolvedSnapshot deferredSnapshotWithEagerIdentity(
            FrozenNode canonicalRoot,
            FrozenNode resolvedRoot) {
        ResolvedSnapshot snapshot =
                ResolvedSnapshot.withDeferredResolution(
                        canonicalRoot,
                        resolvedRoot);
        snapshot.blueId();
        return snapshot;
    }

    long batchPatchCallsForTest() {
        return batchPatchCalls;
    }

    long batchPatchEntriesForTest() {
        return batchPatchEntries;
    }

    long batchPatchPlanningNanosForTest() {
        return batchPatchPlanningNanos;
    }

    long batchPatchConformanceNanosForTest() {
        return batchPatchConformanceNanos;
    }

    long batchPatchBuildUpdatesNanosForTest() {
        return batchPatchBuildUpdatesNanos;
    }

    long batchPatchCommitNanosForTest() {
        return batchPatchCommitNanos;
    }

    long batchPatchRollbackCopiesForTest() {
        return batchPatchRollbackCopies;
    }

    long documentUpdateBeforeNodeMaterializationsForTest() {
        return documentUpdateBeforeNodeMaterializations;
    }

    long documentUpdateAfterNodeMaterializationsForTest() {
        return documentUpdateAfterNodeMaterializations;
    }

    long patchSequencesPreparedForTest() {
        return patchSequencesPrepared;
    }

    long singletonPatchTransactionsForTest() {
        return singletonPatchTransactions;
    }

    long sequenceIntermediateSnapshotAdvancesForTest() {
        return sequenceIntermediateSnapshotAdvances;
    }

    long sequenceSharedSnapshotCacheInsertsForTest() {
        return sequenceSharedSnapshotCacheInserts;
    }

    long sequenceFinalSnapshotCacheInsertsForTest() {
        return sequenceFinalSnapshotCacheInserts;
    }

    long sequenceSuffixRebasesForTest() {
        return sequenceSuffixRebases;
    }

    long sequenceStalePreviewFallbacksForTest() {
        return sequenceStalePreviewFallbacks;
    }

    long sequenceFallbackPatchesForTest() {
        return sequenceFallbackPatches;
    }

    /**
     * Single-use, invocation-bound transaction cursor for an ordered patch
     * sequence.
     *
     * <p>Each successful {@link #applyNext(int)} consumes one retained patch
     * and atomically advances the enclosing runtime. Intermediate results stay
     * in a sequence-local snapshot/cache boundary; the final state is promoted
     * only during the normal sequence lifecycle. {@link #close()} is
     * idempotent and mandatory: it discards unused previews and patches,
     * closes the planning session, restores the previously active transient
     * manager, promotes eligible final state, and releases sequence-owned
     * cache state. Instances are mutable and not thread-safe.</p>
     */
    final class PreparedPatchSequence implements AutoCloseable {
        private final String originScope;
        private final int patchCount;
        private final WorkingDocument.Preview preview;
        private final List<PatchInput> patches;
        private ProcessingSnapshotManager sequenceSnapshotManager;
        private ProcessingSnapshotManager previousActiveSequenceSnapshotManager;
        private boolean sequenceSnapshotManagerActivated;
        private SequentialPatchPlanningSession planningSession;
        private FrozenNode observedCanonical;
        private FrozenNode observedResolved;
        private boolean observedResolutionComplete = true;
        private long observedVersion = Long.MIN_VALUE;
        private boolean advanced;
        private boolean closed;
        private boolean counted;

        private PreparedPatchSequence(String originScope,
                                      List<PatchInput> requestedPatches,
                                      WorkingDocument.Preview preview) {
            this.originScope = PointerUtils.normalizeScope(originScope);
            this.preview = preview;
            List<PatchInput> checkedPatches = Objects.requireNonNull(requestedPatches, "patches");
            this.patches = new ArrayList<>(checkedPatches);
            this.patchCount = this.patches.size();
        }

        int size() {
            return patchCount;
        }

        JsonPatch patchForValidation(int patchIndex) {
            return patchAt(patchIndex).legacyPatch();
        }

        PatchInput patchInputForValidation(int patchIndex) {
            return patchAt(patchIndex);
        }

        List<DocumentUpdateData> applyNext(int patchIndex) {
            if (closed) {
                throw new IllegalStateException("Patch sequence is already closed");
            }
            PatchInput authoredPatch = patchAt(patchIndex);
            validateMutationPathWithoutResolution(authoredPatch);
            chargeSemanticIdentityWork(
                    Collections.singletonList(authoredPatch));
            if (!counted) {
                patchSequencesPrepared++;
                batchPatchCalls++;
                counted = true;
            }
            SequenceRoots actual = currentRoots();
            refreshInvalidSequenceSnapshotManager();
            if (planningSession == null) {
                planningSession = newPlanningSession(actual, patchIndex);
            }
            ImmutableJsonPatch patch = planningSession.preparePatch(
                    authoredPatch, actual.canonical, actual.resolved);
            WorkingDocument.PatchPreview prepared = preview != null ? preview.patch(patchIndex) : null;
            BatchPatchResult result = null;
            boolean plannedNow = false;
            if (prepared != null
                    && preview.isResolutionScopeCurrent()
                    && originScope.equals(prepared.originScope())
                    && prepared.matches(patch)
                    && prepared.isBasedOn(
                    actual.canonical,
                    actual.resolved,
                    actual.resolutionComplete)) {
                result = prepared.result();
            } else {
                if (preview != null) {
                    preview.discardFrom(patchIndex);
                    sequenceStalePreviewFallbacks++;
                    metrics.incrementSequenceStalePreviewFallbacks();
                }
                if (!planningSession.isBasedOn(
                        actual.canonical,
                        actual.resolved,
                        actual.resolutionComplete)) {
                    planningSession.rebase(
                            actual.canonical,
                            actual.resolved,
                            actual.resolutionComplete);
                    sequenceSuffixRebases++;
                    metrics.incrementSequenceSuffixRebases();
                }
                result = planningSession.planNext(patch).result();
                plannedNow = true;
            }
            if (preview != null) {
                preview.release(patchIndex);
            }

            if (plannedNow) {
                batchPatchPlanningNanos += result.patchPlanningNanos();
                batchPatchConformanceNanos += result.conformanceNanos();
            }
            batchPatchEntries++;

            long buildUpdatesStart = System.nanoTime();
            BatchPatchResult commitResult;
            try {
                commitResult = usesAuthoritativeSelectedSnapshot()
                        ? result
                        : result.withMaterializationMetrics(updateMaterializationMetrics());
            } finally {
                long buildUpdatesNanos = System.nanoTime() - buildUpdatesStart;
                batchPatchBuildUpdatesNanos += buildUpdatesNanos;
                metrics.addBatchPatchBuildUpdatesNanos(buildUpdatesNanos);
            }

            Node selectedRollback = selectedDocumentBacked ? materializedView.copyRoot() : null;
            ResolvedSnapshot snapshotRollback = snapshot;
            boolean staleRollback = materializedViewStale;
            long versionRollback = stateVersion;
            long sharedVersionRollback = sharedSnapshotVersion;
            boolean finalRequestedPatch = patchIndex == patchCount - 1;
            boolean insertSharedSnapshot = snapshotManager != null && finalRequestedPatch;
            long commitStart = System.nanoTime();
            try {
                List<DocumentUpdateData> updates =
                        commitBatchPatchResult(commitResult,
                                insertSharedSnapshot,
                                sequenceSnapshotManager());
                advanced = true;
                boolean sharedSnapshotInserted =
                        insertSharedSnapshot
                                && sharedSnapshotVersion
                                == stateVersion;
                if (sharedSnapshotInserted) {
                    sequenceSharedSnapshotCacheInserts++;
                    sequenceFinalSnapshotCacheInserts++;
                    metrics.incrementSequenceSharedSnapshotCacheInserts();
                    metrics.incrementSequenceFinalSnapshotCacheInserts();
                } else {
                    sequenceIntermediateSnapshotAdvances++;
                    metrics.incrementSequenceIntermediateSnapshotAdvances();
                }
                for (DocumentUpdateData update : updates) {
                    changedPaths.add(
                            PointerUtils.normalizePointer(update.path()));
                }
                rememberCurrentRoots(commitResult);
                patches.set(patchIndex, null);
                return updates;
            } catch (RuntimeException ex) {
                snapshot = snapshotRollback;
                materializedViewStale = staleRollback;
                stateVersion = versionRollback;
                sharedSnapshotVersion = sharedVersionRollback;
                if (selectedRollback != null) {
                    materializedView.replaceWith(selectedRollback);
                    materializedViewStale = false;
                }
                throw ex;
            } finally {
                long commitNanos = System.nanoTime() - commitStart;
                batchPatchCommitNanos += commitNanos;
                metrics.addBatchPatchCommitNanos(commitNanos);
                metrics.addSequenceCommitNanos(commitNanos);
                metrics.addSnapshotCommitNanos(commitNanos);
                if (insertSharedSnapshot) {
                    metrics.addSequenceFinalCacheCommitNanos(commitNanos);
                }
            }
        }

        private PatchInput patchAt(int patchIndex) {
            if (patchIndex < 0 || patchIndex >= patchCount) {
                throw new IndexOutOfBoundsException("Patch index outside prepared sequence: " + patchIndex);
            }
            PatchInput patch = patches.get(patchIndex);
            if (patch == null) {
                throw new IllegalStateException("Patch was already consumed: " + patchIndex);
            }
            return patch;
        }

        private SequentialPatchPlanningSession newPlanningSession(SequenceRoots roots,
                                                                  int patchIndex) {
            ProcessingSnapshotManager sequenceManager = sequenceSnapshotManager(roots, patchIndex);
            ConformanceEngine sequenceConformanceEngine = sequenceManager != null
                    ? sequenceManager.transientConformanceEngine(conformanceEngine)
                    : conformanceEngine != null ? conformanceEngine.transientView() : null;
            DocumentProcessingRuntime.PlanningContext planning = workingPlanningContext(
                    roots.canonical,
                    roots.resolved,
                    !selectedDocumentBacked,
                    sequenceManager,
                    scopes().keySet(),
                    executableBodyFieldsByType,
                    roots.resolutionComplete);
            return new SequentialPatchPlanningSession(originScope,
                    planning,
                    sequenceConformanceEngine,
                    conformancePlannerOverride,
                    updateMaterializationMetrics(),
                    metrics);
        }

        private ProcessingSnapshotManager sequenceSnapshotManager() {
            if (sequenceSnapshotManager == null && snapshotManager != null) {
                sequenceSnapshotManager = currentSnapshotManager().transientSequence();
                activateSequenceSnapshotManager();
            }
            return sequenceSnapshotManager;
        }

        private ProcessingSnapshotManager sequenceSnapshotManager(SequenceRoots roots,
                                                                  int patchIndex) {
            if (sequenceSnapshotManager != null || snapshotManager == null) {
                return sequenceSnapshotManager;
            }
            WorkingDocument.PatchPreview prepared = preview != null
                    ? preview.patch(patchIndex)
                    : null;
            if (prepared != null
                    && preview.isResolutionScopeCurrent()
                    && originScope.equals(prepared.originScope())
                    && prepared.matches(patchAt(patchIndex))
                    && prepared.isBasedOn(
                    roots.canonical,
                    roots.resolved,
                    roots.resolutionComplete)) {
                sequenceSnapshotManager = preview.takeSequenceSnapshotManager();
            }
            if (sequenceSnapshotManager == null) {
                sequenceSnapshotManager = currentSnapshotManager().transientSequence();
            }
            activateSequenceSnapshotManager();
            return sequenceSnapshotManager;
        }

        private void activateSequenceSnapshotManager() {
            if (sequenceSnapshotManager == null
                    || activeSequenceSnapshotManager == sequenceSnapshotManager) {
                return;
            }
            previousActiveSequenceSnapshotManager = activeSequenceSnapshotManager;
            activeSequenceSnapshotManager = sequenceSnapshotManager;
            sequenceSnapshotManagerActivated = true;
        }

        private void refreshInvalidSequenceSnapshotManager() {
            if (sequenceSnapshotManager == null
                    || sequenceSnapshotManager.isTransientStateCurrent()) {
                return;
            }
            ProcessingSnapshotManager invalid = sequenceSnapshotManager;
            deactivateSequenceSnapshotManager();
            closePlanningSession();
            sequenceSnapshotManager = null;
            invalid.releaseTransientState();
            sequenceSnapshotManager = snapshotManager != null
                    ? snapshotManager.transientSequence()
                    : null;
            planningSession = null;
            activateSequenceSnapshotManager();
        }

        private void deactivateSequenceSnapshotManager() {
            if (sequenceSnapshotManagerActivated
                    && activeSequenceSnapshotManager == sequenceSnapshotManager) {
                activeSequenceSnapshotManager = previousActiveSequenceSnapshotManager;
            }
            previousActiveSequenceSnapshotManager = null;
            sequenceSnapshotManagerActivated = false;
        }

        private SequenceRoots currentRoots() {
            if (observedVersion == stateVersion
                    && observedCanonical != null
                    && observedResolved != null) {
                return new SequenceRoots(
                        observedCanonical,
                        observedResolved,
                        observedResolutionComplete);
            }
            ResolvedSnapshot current = snapshot;
            if (current != null) {
                observedCanonical = current.frozenCanonicalRoot();
                observedResolved = current.frozenResolvedRoot();
                observedResolutionComplete =
                        current.isResolutionComplete();
            } else {
                PlanningContext planning = planningContext(materializedView.root());
                observedCanonical = planning.canonicalPlanner().root();
                observedResolved = planning.resolvedPlanner().root();
                observedResolutionComplete =
                        planning.isResolutionComplete();
            }
            observedVersion = stateVersion;
            return new SequenceRoots(
                    observedCanonical,
                    observedResolved,
                    observedResolutionComplete);
        }

        private void rememberCurrentRoots(BatchPatchResult result) {
            if (snapshot != null) {
                observedCanonical = snapshot.frozenCanonicalRoot();
                observedResolved = snapshot.frozenResolvedRoot();
                observedResolutionComplete =
                        snapshot.isResolutionComplete();
            } else {
                observedCanonical = result.canonicalRoot();
                observedResolved = result.resolvedRoot();
                observedResolutionComplete =
                        result.isResolutionComplete();
            }
            observedVersion = stateVersion;
        }

        /** {@inheritDoc} */
        @Override
        public void close() {
            if (closed) {
                return;
            }
            if (preview != null) {
                preview.discardFrom(0);
            }
            for (int index = 0; index < patches.size(); index++) {
                patches.set(index, null);
            }
            try {
                if (advanced) {
                    ProcessingSnapshotManager manager = sequenceSnapshotManager();
                    if (manager == null || manager.isTransientStateCurrent()) {
                        promoteCurrentSequenceSnapshot(manager);
                    }
                }
            } catch (RuntimeException | Error ex) {
                ProcessingSnapshotManager failedManager = sequenceSnapshotManager;
                deactivateSequenceSnapshotManager();
                sequenceSnapshotManager = null;
                try {
                    closePlanningSession();
                } catch (RuntimeException | Error cleanupFailure) {
                    if (ex != cleanupFailure) {
                        ex.addSuppressed(cleanupFailure);
                    }
                }
                if (failedManager != null) {
                    try {
                        failedManager.releaseTransientState();
                    } catch (RuntimeException | Error cleanupFailure) {
                        if (ex != cleanupFailure) {
                            ex.addSuppressed(cleanupFailure);
                        }
                    }
                }
                throw ex;
            }
            ProcessingSnapshotManager managerToRelease = sequenceSnapshotManager;
            deactivateSequenceSnapshotManager();
            closePlanningSession();
            sequenceSnapshotManager = null;
            observedCanonical = null;
            observedResolved = null;
            closed = true;
            if (managerToRelease != null) {
                managerToRelease.releaseTransientState();
            }
        }

        private void closePlanningSession() {
            if (planningSession != null) {
                planningSession.close();
                planningSession = null;
            }
        }
    }

    private static final class SequenceRoots {
        private final FrozenNode canonical;
        private final FrozenNode resolved;
        private final boolean resolutionComplete;

        private SequenceRoots(FrozenNode canonical,
                              FrozenNode resolved,
                              boolean resolutionComplete) {
            this.canonical = Objects.requireNonNull(canonical, "canonical");
            this.resolved = Objects.requireNonNull(resolved, "resolved");
            this.resolutionComplete = resolutionComplete;
        }
    }

    /** Receives lazy before/after document-update materialization events. */
    interface UpdateMaterializationMetrics {

        /** Records materialization of an update's pre-change node. */
        void recordBeforeNodeMaterialization();

        /** Records materialization of an update's post-change node. */
        void recordAfterNodeMaterialization();
    }

    static final class DocumentUpdateData {
        private final String path;
        private final FrozenNode beforeFrozen;
        private final FrozenNode afterFrozen;
        private Node before;
        private Node after;
        private final JsonPatch.Op op;
        private final String originScope;
        private final List<String> cascadeScopes;
        private final UpdateMaterializationMetrics materializationMetrics;

        DocumentUpdateData(String path,
                           Node before,
                           Node after,
                           JsonPatch.Op op,
                           String originScope,
                           List<String> cascadeScopes) {
            this.path = path;
            this.beforeFrozen = null;
            this.afterFrozen = null;
            this.before = before;
            this.after = after;
            this.op = op;
            this.originScope = originScope;
            this.cascadeScopes = cascadeScopes;
            this.materializationMetrics = null;
        }

        DocumentUpdateData(String path,
                           FrozenNode beforeFrozen,
                           FrozenNode afterFrozen,
                           JsonPatch.Op op,
                           String originScope,
                           List<String> cascadeScopes,
                           UpdateMaterializationMetrics materializationMetrics) {
            this.path = path;
            this.beforeFrozen = beforeFrozen;
            this.afterFrozen = afterFrozen;
            this.op = op;
            this.originScope = originScope;
            this.cascadeScopes = cascadeScopes;
            this.materializationMetrics = materializationMetrics;
        }

        String path() {
            return path;
        }

        Node before() {
            if (before == null && beforeFrozen != null) {
                before = beforeFrozen.toNode();
                if (materializationMetrics != null) {
                    materializationMetrics.recordBeforeNodeMaterialization();
                }
            }
            return before;
        }

        boolean beforePresent() {
            return before != null || beforeFrozen != null;
        }

        Node after() {
            if (op == JsonPatch.Op.REMOVE) {
                return null;
            }
            if (after == null && afterFrozen != null) {
                after = afterFrozen.toNode();
                if (materializationMetrics != null) {
                    materializationMetrics.recordAfterNodeMaterialization();
                }
            }
            return after;
        }

        boolean afterPresent() {
            return op != JsonPatch.Op.REMOVE && (after != null || afterFrozen != null);
        }

        JsonPatch.Op op() {
            return op;
        }

        DocumentUpdateData withMaterializationMetrics(UpdateMaterializationMetrics materializationMetrics) {
            if (beforeFrozen != null || afterFrozen != null) {
                return new DocumentUpdateData(path,
                        beforeFrozen,
                        afterFrozen,
                        op,
                        originScope,
                        cascadeScopes,
                        materializationMetrics);
            }
            return new DocumentUpdateData(path,
                    before != null ? before.clone() : null,
                    after != null ? after.clone() : null,
                    op,
                    originScope,
                    cascadeScopes);
        }

        String originScope() {
            return originScope;
        }

        List<String> cascadeScopes() {
            return cascadeScopes;
        }
    }

    /**
     * Immutable patch-planning inputs captured at one authoritative document
     * state.
     *
     * <p>The context keeps canonical and resolved planners aligned with the
     * same base snapshot and records which scopes and executable-body fields
     * were already admitted. Callers must replace the context after an
     * authoritative rebase rather than mutating it.</p>
     */
    static final class PlanningContext {
        private final ResolvedSnapshot baseSnapshot;
        private final ImmutablePatchPlanner canonicalPlanner;
        private final ImmutablePatchPlanner resolvedPlanner;
        private final boolean exactReplacement;
        private final ProcessingSnapshotManager authoritativeSnapshotManager;
        private final Set<String> openedScopePaths;
        private final Map<String, List<String>> executableBodyFieldsByType;
        private final boolean resolutionComplete;

        private PlanningContext(ResolvedSnapshot baseSnapshot,
                                ImmutablePatchPlanner canonicalPlanner,
                                ImmutablePatchPlanner resolvedPlanner,
                                boolean exactReplacement,
                                ProcessingSnapshotManager authoritativeSnapshotManager,
                                Iterable<String> openedScopePaths,
                                Map<String, List<String>> executableBodyFieldsByType,
                                boolean resolutionComplete) {
            this.baseSnapshot = baseSnapshot;
            this.canonicalPlanner = canonicalPlanner;
            this.resolvedPlanner = resolvedPlanner;
            this.exactReplacement = exactReplacement;
            this.authoritativeSnapshotManager = authoritativeSnapshotManager;
            this.openedScopePaths =
                    Collections.unmodifiableSet(
                            openedScopes(openedScopePaths));
            this.executableBodyFieldsByType =
                    immutableExecutableBodyFields(
                            executableBodyFieldsByType);
            this.resolutionComplete = resolutionComplete;
        }

        ResolvedSnapshot baseSnapshot() {
            return baseSnapshot;
        }

        ImmutablePatchPlanner canonicalPlanner() {
            return canonicalPlanner;
        }

        ImmutablePatchPlanner resolvedPlanner() {
            return resolvedPlanner;
        }

        boolean exactReplacement() {
            return exactReplacement;
        }

        ProcessingSnapshotManager authoritativeSnapshotManager() {
            return authoritativeSnapshotManager;
        }

        Set<String> openedScopePaths() {
            return openedScopePaths;
        }

        Map<String, List<String>> executableBodyFieldsByType() {
            return executableBodyFieldsByType;
        }

        boolean isResolutionComplete() {
            return resolutionComplete;
        }

        ResolvedSnapshot resolveCanonical(FrozenNode canonicalRoot) {
            if (!exactReplacement || authoritativeSnapshotManager == null) {
                throw new IllegalStateException("Authoritative snapshot resolution is unavailable");
            }
            return resolveCanonicalTransient(
                    authoritativeSnapshotManager,
                    canonicalRoot,
                    openedScopePaths,
                    executableBodyFieldsByType);
        }
    }

    private static final class SnapshotPatchPlan {
        private final ResolvedSnapshot next;

        private SnapshotPatchPlan(ResolvedSnapshot next) {
            this.next = next;
        }
    }
}
