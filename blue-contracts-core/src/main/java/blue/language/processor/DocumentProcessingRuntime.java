package blue.language.processor;

import blue.language.runtime.LanguageRuntimeAccess;
import blue.language.conformance.ConformanceEngine;
import blue.language.model.Node;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.util.PointerUtils;
import blue.language.snapshot.FrozenNode;
import blue.language.merge.ResolvedSnapshot;
import blue.language.identity.CanonicalTypeIdentityLookup;
import blue.language.model.wire.JsonPointer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Compatibility composition root for exactly one PROCESS invocation.
 *
 * <p>Semantic work belongs to the named invocation components. This class
 * retains the established package-local surface while forwarding each
 * operation to its single owner.</p>
 */
final class DocumentProcessingRuntime {

    final MaterializedDocumentView materializedView;
    private final ProcessingDocumentView documentView;
    private final ProcessingMutationSession mutationSession;
    private final ProcessingScopeRegistry scopeRegistry;
    private final ProcessingEventQueue eventQueue;
    private final ProcessingOutputCollector outputCollector;
    private final ProcessingLifecycleState lifecycleState;
    private final ProcessingGasContext gasContext;
    private final ProcessingSnapshotTransaction snapshotTransaction;
    private final ProcessingConformanceRecorder conformanceRecorder;
    private final ProcessingRuntimeCounters counters;

    final Map<String, List<String>> executableBodyFieldsByType;
    final ConformanceEngine conformanceEngine;
    final ConformancePlannerOverride conformancePlannerOverride;
    final ProcessingSnapshotManager snapshotManager;
    final ProcessingObserver metrics;
    final boolean lazyMaterializedCommits;
    final boolean selectedDocumentBacked;
    final boolean strictPlatformInvocation;

    ResolvedSnapshot snapshot;
    private ResolvedSnapshot entrySnapshot;
    ProcessingSnapshotManager activeSequenceSnapshotManager;
    boolean materializedViewStale;
    long stateVersion;
    long sharedSnapshotVersion;
    final Set<String> changedPaths = new LinkedHashSet<>();
    private final Set<String> replacedEmbeddedScopePaths =
            new LinkedHashSet<>();
    private final Set<String> evidenceScopePaths =
            new LinkedHashSet<>();
    private final ProcessingCommittedEvidence committedEvidence =
            new ProcessingCommittedEvidence();

    /** Creates a node-backed invocation with default services. */
    public DocumentProcessingRuntime(Node document) {
        this(document, null, null);
    }

    /** Creates a node-backed invocation with optional conformance. */
    public DocumentProcessingRuntime(
            Node document,
            ConformanceEngine conformanceEngine) {
        this(document, conformanceEngine, null);
    }

    /** Creates a node-backed invocation with optional snapshot resolution. */
    public DocumentProcessingRuntime(
            Node document,
            ConformanceEngine conformanceEngine,
            ProcessingSnapshotManager snapshotManager) {
        this(document, conformanceEngine, snapshotManager, null);
    }

    /** Creates a node-backed invocation with optional observation. */
    public DocumentProcessingRuntime(
            Node document,
            ConformanceEngine conformanceEngine,
            ProcessingSnapshotManager snapshotManager,
            ProcessingObserver metrics) {
        this(document, conformanceEngine, null, snapshotManager, metrics);
    }

    /** Creates a fully configured node-backed invocation. */
    public DocumentProcessingRuntime(
            Node document,
            ConformanceEngine conformanceEngine,
            ConformancePlannerOverride conformancePlannerOverride,
            ProcessingSnapshotManager snapshotManager,
            ProcessingObserver metrics) {
        this(document, conformanceEngine, conformancePlannerOverride,
                snapshotManager, metrics, new GasMeter(),
                Collections.emptyMap());
    }

    DocumentProcessingRuntime(
            Node document,
            ConformanceEngine conformanceEngine,
            ConformancePlannerOverride conformancePlannerOverride,
            ProcessingSnapshotManager snapshotManager,
            ProcessingObserver metrics,
            GasMeter gasMeter) {
        this(document, conformanceEngine, conformancePlannerOverride,
                snapshotManager, metrics, gasMeter, Collections.emptyMap());
    }

    DocumentProcessingRuntime(
            Node document,
            ConformanceEngine conformanceEngine,
            ConformancePlannerOverride conformancePlannerOverride,
            ProcessingSnapshotManager snapshotManager,
            ProcessingObserver metrics,
            GasMeter gasMeter,
            Map<String, List<String>> executableBodyFieldsByType) {
        this(document, conformanceEngine, conformancePlannerOverride,
                snapshotManager, metrics, gasMeter,
                executableBodyFieldsByType, false);
    }

    DocumentProcessingRuntime(
            Node document,
            ConformanceEngine conformanceEngine,
            ConformancePlannerOverride conformancePlannerOverride,
            ProcessingSnapshotManager snapshotManager,
            ProcessingObserver metrics,
            GasMeter gasMeter,
            Map<String, List<String>> executableBodyFieldsByType,
            boolean strictPlatformInvocation) {
        this(document,
                conformanceEngine,
                conformancePlannerOverride,
                snapshotManager,
                metrics,
                new ProcessingGasContext(
                        Objects.requireNonNull(gasMeter, "gasMeter")),
                executableBodyFieldsByType,
                strictPlatformInvocation);
    }

    DocumentProcessingRuntime(
            Node document,
            ConformanceEngine conformanceEngine,
            ConformancePlannerOverride conformancePlannerOverride,
            ProcessingSnapshotManager snapshotManager,
            ProcessingObserver metrics,
            ProcessingGasContext gasContext,
            Map<String, List<String>> executableBodyFieldsByType,
            boolean strictPlatformInvocation) {
        this.materializedView = new MaterializedDocumentView(
                Objects.requireNonNull(document, "document"));
        this.executableBodyFieldsByType =
                ProcessingSnapshotBootstrap.immutableExecutableBodyFields(
                        executableBodyFieldsByType);
        this.conformanceEngine = conformanceEngine;
        this.conformancePlannerOverride = conformancePlannerOverride;
        this.snapshotManager = snapshotManager;
        this.metrics = metrics != null
                ? metrics : NoOpProcessingObserver.INSTANCE;
        this.lazyMaterializedCommits = false;
        this.selectedDocumentBacked = true;
        this.strictPlatformInvocation = strictPlatformInvocation;
        this.scopeRegistry = new ProcessingScopeRegistry();
        this.eventQueue = new ProcessingEventQueue();
        this.outputCollector = new ProcessingOutputCollector();
        this.lifecycleState = new ProcessingLifecycleState(scopeRegistry);
        this.gasContext = Objects.requireNonNull(
                gasContext, "gasContext");
        this.snapshotTransaction = new ProcessingSnapshotTransaction(this);
        this.documentView = new ProcessingDocumentView(this);
        this.mutationSession = new ProcessingMutationSession(this);
        this.counters = new ProcessingRuntimeCounters();
        this.conformanceRecorder =
                new ProcessingConformanceRecorder(
                        this.gasContext.meter(), this.snapshotManager);
    }

    /** Creates a snapshot-backed invocation. */
    public DocumentProcessingRuntime(
            ResolvedSnapshot snapshot,
            ConformanceEngine conformanceEngine,
            ProcessingSnapshotManager snapshotManager) {
        this(snapshot, conformanceEngine, snapshotManager, null);
    }

    /** Creates an observed snapshot-backed invocation. */
    public DocumentProcessingRuntime(
            ResolvedSnapshot snapshot,
            ConformanceEngine conformanceEngine,
            ProcessingSnapshotManager snapshotManager,
            ProcessingObserver metrics) {
        this(snapshot, conformanceEngine, null, snapshotManager, metrics);
    }

    /** Creates a fully configured snapshot-backed invocation. */
    public DocumentProcessingRuntime(
            ResolvedSnapshot snapshot,
            ConformanceEngine conformanceEngine,
            ConformancePlannerOverride conformancePlannerOverride,
            ProcessingSnapshotManager snapshotManager,
            ProcessingObserver metrics) {
        this(snapshot, conformanceEngine, conformancePlannerOverride,
                snapshotManager, metrics, new GasMeter(),
                Collections.emptyMap());
    }

    DocumentProcessingRuntime(
            ResolvedSnapshot snapshot,
            ConformanceEngine conformanceEngine,
            ConformancePlannerOverride conformancePlannerOverride,
            ProcessingSnapshotManager snapshotManager,
            ProcessingObserver metrics,
            GasMeter gasMeter) {
        this(snapshot, conformanceEngine, conformancePlannerOverride,
                snapshotManager, metrics, gasMeter, Collections.emptyMap());
    }

    DocumentProcessingRuntime(
            ResolvedSnapshot snapshot,
            ConformanceEngine conformanceEngine,
            ConformancePlannerOverride conformancePlannerOverride,
            ProcessingSnapshotManager snapshotManager,
            ProcessingObserver metrics,
            GasMeter gasMeter,
            Map<String, List<String>> executableBodyFieldsByType) {
        this(snapshot, conformanceEngine, conformancePlannerOverride,
                snapshotManager, metrics, gasMeter,
                executableBodyFieldsByType, false);
    }

    DocumentProcessingRuntime(
            ResolvedSnapshot snapshot,
            ConformanceEngine conformanceEngine,
            ConformancePlannerOverride conformancePlannerOverride,
            ProcessingSnapshotManager snapshotManager,
            ProcessingObserver metrics,
            GasMeter gasMeter,
            Map<String, List<String>> executableBodyFieldsByType,
            boolean strictPlatformInvocation) {
        this(snapshot,
                conformanceEngine,
                conformancePlannerOverride,
                snapshotManager,
                metrics,
                new ProcessingGasContext(
                        Objects.requireNonNull(gasMeter, "gasMeter")),
                executableBodyFieldsByType,
                strictPlatformInvocation);
    }

    DocumentProcessingRuntime(
            ResolvedSnapshot snapshot,
            ConformanceEngine conformanceEngine,
            ConformancePlannerOverride conformancePlannerOverride,
            ProcessingSnapshotManager snapshotManager,
            ProcessingObserver metrics,
            ProcessingGasContext gasContext,
            Map<String, List<String>> executableBodyFieldsByType,
            boolean strictPlatformInvocation) {
        this.metrics = metrics != null
                ? metrics : NoOpProcessingObserver.INSTANCE;
        this.gasContext = Objects.requireNonNull(
                gasContext, "gasContext");
        this.executableBodyFieldsByType =
                ProcessingSnapshotBootstrap.immutableExecutableBodyFields(
                        executableBodyFieldsByType);
        this.conformanceEngine = conformanceEngine;
        this.conformancePlannerOverride = conformancePlannerOverride;
        this.snapshotManager = snapshotManager;
        ResolvedSnapshot prepared = ProcessingSnapshotBootstrap.prepare(
                Objects.requireNonNull(snapshot, "snapshot"),
                this.executableBodyFieldsByType,
                this.metrics);
        this.materializedView = new MaterializedDocumentView(
                prepared.sourceRoot());
        this.snapshot = prepared;
        this.entrySnapshot = prepared;
        this.lazyMaterializedCommits = true;
        this.selectedDocumentBacked = false;
        this.strictPlatformInvocation = strictPlatformInvocation;
        this.scopeRegistry = new ProcessingScopeRegistry();
        this.eventQueue = new ProcessingEventQueue();
        this.outputCollector = new ProcessingOutputCollector();
        this.lifecycleState = new ProcessingLifecycleState(scopeRegistry);
        this.snapshotTransaction = new ProcessingSnapshotTransaction(this);
        this.documentView = new ProcessingDocumentView(this);
        this.mutationSession = new ProcessingMutationSession(this);
        this.counters = new ProcessingRuntimeCounters();
        this.conformanceRecorder =
                new ProcessingConformanceRecorder(
                        this.gasContext.meter(), this.snapshotManager);
    }

    void observe(ProcessingMetricId metricId, long value) {
        ProcessingObservations.record(metrics, metricId, value);
    }

    /** Returns the current effective document. */
    public Node document() { return documentView.document(); }
    Node selectedDocument() { return documentView.selectedDocument(); }
    /** Returns the live invocation scope map. */
    public Map<String, ScopeRuntimeContext> scopes() { return scopeRegistry.scopes(); }
    /** Returns or creates one scope occurrence. */
    public ScopeRuntimeContext scope(String scopePath) {
        ScopeRuntimeContext context = scopeRegistry.scope(scopePath);
        if (JsonPointer.ROOT.equals(PointerUtils.normalizeScope(scopePath))) {
            context.setEmbeddedDepth(0);
        }
        return context;
    }

    /** Records the feeder-selected scope ancestry for lazy body cataloging. */
    void admitEvidenceScopePath(String scopePath) {
        List<String> segments = JsonPointer.split(
                PointerUtils.normalizeScope(scopePath));
        for (int depth = 0; depth <= segments.size(); depth++) {
            evidenceScopePaths.add(JsonPointer.toPointer(
                    segments.subList(0, depth)));
        }
    }

    /** Returns the invocation-local feeder-selected scope ancestry. */
    Set<String> evidenceScopePaths() {
        return Collections.unmodifiableSet(evidenceScopePaths);
    }

    /** Returns an existing scope occurrence, or {@code null}. */
    public ScopeRuntimeContext existingScope(String scopePath) {
        return scopeRegistry.existingScope(scopePath); }
    /** Returns Root emissions in deterministic FIFO order. */
    public List<Node> rootEmissions() { return outputCollector.rootEvents(); }
    /** Admits one Root emission within the portable output limit. */
    public void recordRootEmission(Node emission) {
        mutationSession.enforcePortableLimit(
                ProcessorErrorCategory.InternalEventLimitExceeded,
                GasScheduleConstants.PortableLimit.ROOT_EVENTS_RETURNED,
                outputCollector.nextRootEventCount());
        outputCollector.recordRootEvent(emission);
    }

    void attachScopeOccurrence(String parentScopePath, String childScopePath) {
        scope(PointerUtils.normalizeScope(childScopePath))
                .attachToParentOccurrence(
                        scope(PointerUtils.normalizeScope(parentScopePath)));
    }

    void enqueueEventOccurrence(EventOccurrence occurrence) {
        mutationSession.enforcePortableLimit(
                ProcessorErrorCategory.InternalEventLimitExceeded,
                GasScheduleConstants.PortableLimit.INTERNAL_EVENT_OCCURRENCES,
                eventQueue.nextAdmittedCount());
        eventQueue.enqueue(occurrence);
    }

    EventOccurrence pollEventOccurrence() { return eventQueue.poll(); }
    boolean hasPendingEventOccurrences() { return eventQueue.hasPendingOccurrences(); }
    int pendingEventOccurrenceCount() { return eventQueue.pendingOccurrenceCount(); }
    /** Opens a detached runtime gas ledger. */
    public GasMeter.ChildGasLedger newRuntimeGasLedger(
            String namespace,
            Map<String, Long> counterWeights) {
        return gasContext.newChildLedger(namespace, counterWeights);
    }

    RuntimeWorkSession newRuntimeWorkSession(
            LanguageRuntimeAccess languageRuntime) {
        return gasContext.newRuntimeWorkSession(languageRuntime,
                currentSnapshotManager());
    }

    RuntimeWorkSession newRuntimeWorkSession(
            LanguageRuntimeAccess languageRuntime, ContractProcessorRegistry registry) {
        return gasContext.newRuntimeWorkSession(languageRuntime,
                currentSnapshotManager(), registry);
    }

    void mergeRuntimeGasLedger(GasMeter.ChildGasLedger ledger) {
        gasContext.merge(ledger); }
    /** Returns the invocation-owned gas ledger. */
    public GasMeter gasMeter() { return gasContext.meter(); }
    ProcessingDocumentView documentViewComponent() { return documentView; }
    ProcessingMutationSession mutationSessionComponent() { return mutationSession; }
    ProcessingGasContext gasContextComponent() { return gasContext; }
    ProcessingScopeRegistry scopeRegistryComponent() { return scopeRegistry; }
    ProcessingEventQueue eventQueueComponent() { return eventQueue; }
    ProcessingOutputCollector outputCollectorComponent() { return outputCollector; }
    ProcessingLifecycleState lifecycleStateComponent() { return lifecycleState; }
    ProcessingSnapshotTransaction snapshotTransactionComponent() {
        return snapshotTransaction; }
    ProcessingRuntimeCounters counters() { return counters; }
    /** Returns committed changed paths in first-change order. */
    public Set<String> changedPaths() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(changedPaths));
    }

    /** Captures one whole embedded occurrence replacement for commit delta. */
    void recordReplacedEmbeddedScope(String scopePath) {
        replacedEmbeddedScopePaths.add(
                PointerUtils.normalizeScope(scopePath));
    }

    /** Returns whole occurrence replacements in first-observed order. */
    Set<String> replacedEmbeddedScopePaths() {
        return Collections.unmodifiableSet(
                new LinkedHashSet<>(replacedEmbeddedScopePaths));
    }

    /** Returns all successfully frozen current-event embedded plans. */
    Map<String, EmbeddedScopePlan> entryEmbeddedScopePlans() {
        Map<String, EmbeddedScopePlan> plans = new LinkedHashMap<>();
        for (Map.Entry<String, ScopeRuntimeContext> entry
                : scopeRegistry.scopes().entrySet()) {
            ScopeRuntimeContext context = entry.getValue();
            if (context.hasEntryEmbeddedScopePlan()
                    && context.entryEmbeddedScopePlan() != null) {
                plans.put(entry.getKey(), context.entryEmbeddedScopePlan());
            }
        }
        return Collections.unmodifiableMap(plans);
    }

    /** Returns an immutable conformance-trace snapshot. */
    public ProcessingConformanceTrace conformanceTrace() {
        return conformanceRecorder.snapshot(); }
    /** Records one representation-independent semantic demand. */
    public void recordSemanticDemand(String demand) {
        conformanceRecorder.semanticDemand(demand); }
    void recordSelectedExecutableBodyDemand(
            FrozenNode body,
            String scopePath,
            String contractKey,
            String logicalPath) {
        conformanceRecorder.selectedExecutableBodyDemand(
                body, scopePath, contractKey, logicalPath);
    }

    void recordPatchSemanticDemands(String patchPath) {
        conformanceRecorder.patchSemanticDemands(patchPath); }
    void recordContractSnapshot(EffectiveContractSnapshot contractSnapshot) {
        conformanceRecorder.contractSnapshot(contractSnapshot); }
    void recordTrace(
            ProcessingTraceRecord.Kind kind,
            String scopePath,
            String contractKey,
            String logicalPath,
            Map<String, ?> details,
            Node node) {
        conformanceRecorder.record(kind, scopePath, contractKey,
                logicalPath, details, node);
    }

    void recordTrace(
            ProcessingTraceRecord.Kind kind,
            String scopePath,
            String contractKey,
            String logicalPath) {
        conformanceRecorder.record(kind, scopePath, contractKey, logicalPath);
    }

    /** Returns total admitted gas. */
    public long totalGas() { return gasContext.meter().totalGas(); }
    /** Charges one PROCESS invocation. */
    public void chargeProcessInvocation() {
        gasContext.processMeter().invocation(); }
    /** Returns the invocation semantic gas meter. */
    public SemanticGasMeter semanticGas() { return gasContext.meter().semantic(); }
    public void chargeDeliverySnapshotEntry(String scopePath, String key) {
        gasContext.processMeter().deliverySnapshotEntry(scopePath, key); }
    public void chargeScopeEntry(String scopePath) {
        gasContext.processMeter().scopeEntry(scopePath); }
    public void chargeParticipatingClosure(long quantity) {
        gasContext.processMeter().participatingClosure(quantity); }
    public void chargeContractHeaderRecognized(
            String scopePath, String key, String reason) {
        gasContext.processMeter().contractHeader(scopePath, key, reason); }
    public void chargeContractHeadersRecognized(long quantity, String reason) {
        gasContext.processMeter().contractHeaders(quantity, reason); }
    public void chargeEmbeddedPathEntryRead(
            String scopePath, String logicalPath) {
        gasContext.processMeter().embeddedPathEntry(scopePath, logicalPath); }
    public void chargeEmbeddedPathSegmentsValidated(
            String scopePath, String logicalPath, long quantity) {
        gasContext.processMeter().embeddedPathSegments(
                scopePath, logicalPath, quantity); }
    public void setScopeEmbeddedDepth(String scopePath, int depth) {
        scope(scopePath).setEmbeddedDepth(depth); }
    public int scopeEmbeddedDepth(String scopePath) {
        return scope(scopePath).embeddedDepth(); }
    public void chargeInitialization(String scopePath) {
        gasContext.processMeter().initialization(scopePath); }
    public void chargeChannelMatchAttempt(String scopePath, String key) {
        gasContext.processMeter().channelMatch(scopePath, key); }
    public void chargeChannelAccepted(String scopePath, String key) {
        gasContext.processMeter().channelAccepted(scopePath, key); }
    public void chargeHandlerCandidateTested(String scopePath, String key) {
        gasContext.processMeter().handlerCandidate(scopePath, key); }
    public void chargeHandlerOverhead(String scopePath, String key) {
        gasContext.processMeter().handlerOverhead(scopePath, key); }
    public void chargeBoundaryCheck() {
        gasContext.processMeter().boundaryCheck(); }
    public void chargePatchAddOrReplace(Node value) {
        gasContext.processMeter().patchAddOrReplace(value); }
    public void chargeFrozenPatchAddOrReplace(FrozenNode value) {
        gasContext.processMeter().frozenPatchAddOrReplace(value); }
    public void chargeFrozenPatchAddOrReplace(long canonicalSizeBytes) {
        gasContext.processMeter().frozenPatchAddOrReplace(canonicalSizeBytes); }
    public void chargePatchRemove() {
        gasContext.processMeter().patchRemove(); }
    public void chargeCascadeRouting(int scopeCount) {
        gasContext.processMeter().cascadeRouting(scopeCount); }
    public void chargeEmitEvent(Node event) {
        gasContext.processMeter().emitEvent(event); }
    public void chargeRootEventRecorded() {
        gasContext.processMeter().rootEventRecorded(); }
    public void chargeBridge(Node event) {
        gasContext.processMeter().bridge(event); }
    public void chargeTriggeredDelivery() {
        gasContext.processMeter().triggeredDelivery(); }
    public void chargeDrainEvent() {
        gasContext.processMeter().drainEvent(); }
    public void chargeCheckpointUpdate() {
        gasContext.processMeter().checkpointUpdate(); }
    public void chargeCheckpointUpdate(GasChargeContext context) {
        gasContext.processMeter().checkpointUpdate(context); }
    public void chargeCheckpointCompared() {
        gasContext.processMeter().checkpointCompared(); }
    public void chargeCheckpointCompared(GasChargeContext context) {
        gasContext.processMeter().checkpointCompared(context); }
    public void chargeProcessorMarkerWritten(String reason) {
        gasContext.processMeter().processorMarker(reason); }
    public void chargeTerminationRequest() {
        gasContext.processMeter().terminationRequest(); }
    public void chargeTerminationMarker() {
        gasContext.processMeter().terminationMarker(); }
    public void chargeLifecycleDelivery() {
        gasContext.processMeter().lifecycleDelivery(); }

    public boolean isRunTerminated() {
        return lifecycleState.isRunTerminated();
    }

    public void markRunTerminated() {
        lifecycleState.terminateRun();
    }

    public boolean isScopeTerminated(String scopePath) {
        return lifecycleState.isScopeTerminated(scopePath);
    }

    public ResolvedSnapshot snapshot() {
        return documentView.snapshot();
    }

    CanonicalTypeIdentityLookup canonicalTypeIdentities() {
        return ProcessingSnapshotEvidence.identities(snapshot());
    }

    ResolvedSnapshot entrySnapshot() {
        return entrySnapshot;
    }

    void retainEntrySnapshot(ResolvedSnapshot candidate) {
        if (entrySnapshot == null && stateVersion == 0L) {
            entrySnapshot = Objects.requireNonNull(
                    candidate, "entry snapshot");
        }
    }

    public Node resolvedNodeAt(String path) {
        return documentView.resolvedNodeAt(path);
    }

    public FrozenNode resolvedFrozenAt(String path) {
        return documentView.resolvedFrozenAt(path);
    }
    ResolvedScopeView scopeViewAt(String path) {
        return documentView.scopeViewAt(path);
    }

    FrozenNode selectedFrozenAt(String path) {
        return documentView.selectedFrozenAt(path); }
    ResolvedScopeView contractRecognitionScope(
            ResolvedScopeView scope) {
        return documentView.contractRecognitionScope(
                scope); }
    ResolvedScopeView contractRecognitionScope(
            ResolvedScopeView scope,
            Set<String> recognizedContractKeys) {
        return documentView.contractRecognitionScope(
                scope,
                recognizedContractKeys); }
    public Node canonicalNodeAt(String path) {
        return documentView.canonicalNodeAt(path); }
    public FrozenNode canonicalFrozenAt(String path) {
        return documentView.canonicalFrozenAt(path); }
    public FrozenNode capturePreInitializationScopeDocument(String scopePath) {
        return documentView.capturePreInitializationScopeDocument(scopePath); }
    public WorkingDocument workingDocument(String originScopePath) {
        return workingDocument(originScopePath, PatchSource.LEGACY_PUBLIC_API); }
    WorkingDocument workingDocument(
            String originScopePath, PatchSource mutablePatchSource) {
        return documentView.workingDocument(
                originScopePath, mutablePatchSource); }
    public Node nodeAt(String path) { return documentView.nodeAt(path); }
    public boolean contains(String path) { return documentView.contains(path); }
    public boolean hasInitializationMarker(String scopePath) {
        return documentView.hasInitializationMarker(scopePath); }
    public ProcessorEngine.TerminationMarker terminationMarker(
            String scopePath) {
        return documentView.terminationMarker(scopePath); }
    public boolean hasTerminationMarker(String scopePath) {
        return terminationMarker(scopePath) != null; }
    public void markScopeTerminatedFromMarker(String scopePath) {
        ProcessorEngine.TerminationMarker marker =
                terminationMarker(scopePath);
        if (marker != null) {
            scope(scopePath).finalizeTermination(marker.reason);
        }
    }

    public void directWrite(String path, Node value) {
        mutationSession.writeProcessorState(path, value); }
    public DocumentUpdateData applyPatch(
            String originScopePath, JsonPatch patch) {
        return mutationSession.applyPatch(
                originScopePath, patch, PatchSource.LEGACY_PUBLIC_API); }
    public DocumentUpdateData applyPatch(
            String originScopePath, JsonPatch patch, PatchSource source) {
        return mutationSession.applyPatch(originScopePath, patch, source); }
    public List<DocumentUpdateData> applyPatches(
            String originScopePath, List<JsonPatch> patches) {
        return mutationSession.applyPatches(
                originScopePath, patches, PatchSource.LEGACY_PUBLIC_API); }
    public List<DocumentUpdateData> applyPatches(
            String originScopePath,
            List<JsonPatch> patches,
            PatchSource source) {
        return mutationSession.applyPatches(originScopePath, patches, source); }
    public DocumentUpdateData applyFrozenPatch(
            String originScopePath, FrozenJsonPatch patch) {
        return mutationSession.applyFrozenPatch(originScopePath, patch); }
    public List<DocumentUpdateData> applyFrozenPatches(
            String originScopePath, List<FrozenJsonPatch> patches) {
        return mutationSession.applyFrozenPatches(originScopePath, patches); }
    void chargeSemanticIdentityWork(
            String originScopePath,
            List<PatchInput> patches) {
        mutationSession.chargeSemanticIdentityWork(
                originScopePath, patches); }
    void validateMutationPathWithoutResolution(PatchInput patch) {
        mutationSession.validateMutationPathWithoutResolution(patch); }
    void validateProcessEmbeddedTraversalWithoutResolution(String path) {
        mutationSession.validateProcessEmbeddedTraversalWithoutResolution(path); }
    List<DocumentUpdateData> applyPrecomputedPatch(
            String originScopePath,
            JsonPatch patch,
            WorkingDocument.PatchPreview preview) {
        return mutationSession.applyPrecomputedPatch(
                originScopePath, patch, preview); }
    PreparedPatchTransaction preparePatchSequence(
            String originScopePath,
            List<JsonPatch> patches,
            WorkingDocument.Preview preview) {
        return new PreparedPatchTransaction(this, originScopePath,
                PatchInput.mutableList(patches), preview); }
    PreparedPatchTransaction prepareFrozenPatchSequence(
            String originScopePath,
            List<FrozenJsonPatch> patches,
            WorkingDocument.Preview preview) {
        return new PreparedPatchTransaction(this, originScopePath,
                PatchInput.frozenList(patches), preview); }
    PreparedPatchTransaction preparePatchInputSequence(
            String originScopePath,
            List<PatchInput> patches,
            WorkingDocument.Preview preview) {
        return new PreparedPatchTransaction(
                this, originScopePath, patches, preview); }
    UpdateMaterializationMetrics updateMaterializationMetrics() {
        return mutationSession.updateMaterializationMetrics(); }
    FrozenNode selectedRootWithoutResolution() {
        return documentView.selectedRootWithoutResolution(); }
    FrozenNode identityChargeCanonicalRoot() {
        return documentView.identityChargeCanonicalRoot(); }
    FrozenNode resolvedRootWithoutResolution() {
        return documentView.resolvedRootWithoutResolution(); }
    PatchPlanningContext planningContext(Node rollback) {
        return snapshotTransaction.planningContext(rollback); }
    boolean usesAuthoritativeSelectedSnapshot() {
        return selectedDocumentBacked && snapshotManager != null; }
    static PatchPlanningContext workingPlanningContext(
            FrozenNode canonicalRoot,
            FrozenNode resolvedRoot,
            boolean exactReplacement,
            ProcessingSnapshotManager snapshotManager,
            Iterable<String> openedScopePaths,
            Map<String, List<String>> executableBodyFieldsByType,
            Map<String, EmbeddedScopePlan> entryEmbeddedScopePlans,
            boolean resolutionComplete,
            boolean strictPlatformInvocation,
            CanonicalTypeIdentityLookup canonicalTypeIdentities,
            boolean sourceBacked) {
        return ProcessingSnapshotEvidence.planningContext(
                canonicalRoot, resolvedRoot, exactReplacement,
                snapshotManager, openedScopePaths,
                executableBodyFieldsByType, entryEmbeddedScopePlans,
                resolutionComplete, strictPlatformInvocation,
                canonicalTypeIdentities, sourceBacked);
    }

    List<DocumentUpdateData> commitBatchPatchResult(
            BatchPatchResult result,
            boolean insertSharedSnapshot,
            ProcessingSnapshotManager commitSnapshotManager) {
        return snapshotTransaction.commitBatchPatchResult(
                result, insertSharedSnapshot, commitSnapshotManager);
    }

    void recordCommittedPatchEvidence(BatchPatchResult result) {
        committedEvidence.record(result);
    }

    List<ManagedGeneralizationWrite> committedGeneralizationWrites() {
        return committedEvidence.generalizationWrites();
    }

    void commitMaterializedSnapshot(ResolvedSnapshot committed) {
        snapshotTransaction.commitMaterializedSnapshot(committed); }
    void syncMaterializedView() {
        snapshotTransaction.syncMaterializedView(); }
    void synchronizeSelectedDocumentAfterContinuation(
            FrozenNode selectedBeforeContinuation) {
        snapshotTransaction.synchronizeSelectedDocumentAfterContinuation(
                selectedBeforeContinuation); }
    ResolvedSnapshot snapshotFromDocument(Node document) {
        return snapshotTransaction.snapshotFromDocument(document); }
    ResolvedSnapshot snapshotFromDocumentTransient(Node document) {
        return snapshotTransaction.snapshotFromDocumentTransient(document); }
    ProcessingSnapshotManager currentSnapshotManager() {
        return snapshotTransaction.currentManager(); }
    ConformanceEngine currentConformanceEngine() {
        return snapshotTransaction.currentConformanceEngine(); }
    ExternalChannelFunctionEvaluation.MatcherSessionFactory
    externalChannelMatcherSessions() {
        return snapshotTransaction.externalChannelMatcherSessions(); }
    FrozenNode materializeSelectedExecutableReference(FrozenNode reference) {
        return snapshotTransaction
                .materializeSelectedExecutableReference(reference); }
    ResolvedSnapshot resolveSelectedExecutableReference(
            FrozenNode reference) {
        return snapshotTransaction
                .resolveSelectedExecutableReference(reference); }
    Supplier<Node> checkpointSubjectMaterializer(Node subjectReference) {
        return snapshotTransaction
                .checkpointSubjectMaterializer(subjectReference); }
    static Set<String> executableBodyPaths(
            Node document,
            Iterable<String> openedScopePaths,
            Map<String, List<String>> executableBodyFieldsByType) {
        return ExecutableBodyPathCatalog.fromNode(document,
                openedScopePaths, executableBodyFieldsByType, null);
    }

    static Set<String> executableBodyPaths(
            FrozenNode document,
            Iterable<String> openedScopePaths,
            Map<String, List<String>> executableBodyFieldsByType,
            CanonicalTypeIdentityLookup canonicalTypeIdentities) {
        return ExecutableBodyPathCatalog.fromFrozen(document,
                openedScopePaths,
                executableBodyFieldsByType,
                canonicalTypeIdentities);
    }

    static ResolvedSnapshot resolveCanonicalTransient(
            ProcessingSnapshotManager manager,
            FrozenNode canonicalRoot,
            Iterable<String> openedScopePaths,
            Map<String, List<String>> executableBodyFieldsByType) {
        return ProcessingSnapshotEvidence.resolveTransient(
                manager, canonicalRoot, openedScopePaths,
                executableBodyFieldsByType, false);
    }

    static ResolvedSnapshot resolveCanonicalTransientIncludingTypeContracts(
            ProcessingSnapshotManager manager,
            FrozenNode canonicalRoot,
            Iterable<String> openedScopePaths,
            Map<String, List<String>> executableBodyFieldsByType) {
        return ProcessingSnapshotEvidence.resolveTransient(
                manager, canonicalRoot, openedScopePaths,
                executableBodyFieldsByType, true);
    }

    void markStateAdvanced(boolean sharedSnapshotInserted) {
        snapshotTransaction.markStateAdvanced(sharedSnapshotInserted); }
    void promoteCurrentSequenceSnapshot(ProcessingSnapshotManager manager) {
        snapshotTransaction.promoteCurrentSequenceSnapshot(manager); }
    static ResolvedSnapshot cacheSnapshotIfComplete(
            ProcessingSnapshotManager manager,
            ResolvedSnapshot candidate) {
        return ProcessingSnapshotEvidence.cacheIfComplete(
                manager, candidate);
    }

    static ResolvedSnapshot snapshotWithCompleteness(
            FrozenNode selectedRoot,
            FrozenNode resolvedRoot,
            boolean resolutionComplete,
            boolean sourceBacked,
            boolean eagerIdentity,
            CanonicalTypeIdentityLookup canonicalTypeIdentities) {
        return ProcessingSnapshotEvidence.create(
                selectedRoot,
                resolvedRoot,
                resolutionComplete,
                sourceBacked,
                eagerIdentity,
                canonicalTypeIdentities);
    }

    ProcessingRuntimeCounters countersForTest() { return counters; }
}
