package blue.language.processor;

import blue.language.Blue;
import blue.language.model.Node;
import blue.language.processor.model.JsonPatch;
import blue.language.snapshot.FrozenNode;
import blue.language.snapshot.ResolvedSnapshot;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Invocation-owned mutable state behind the deterministic processing phases.
 *
 * <p>Each instance owns all phase-local services, queues, snapshots,
 * diagnostics, and commit evidence for exactly one invocation. It is never
 * shared between invocations; synchronized/volatile members protect only lazy
 * event-snapshot publication to concurrent observers within that invocation.</p>
 */
final class ProcessorInvocationState {
    private final DocumentProcessor owner;
    private final DocumentProcessingRuntime runtime;
    private final Node inputDocument;
    private final ResolvedSnapshot inputSnapshot;
    private final ProcessingEventSnapshotBoundary processEventSnapshot;
    private final Map<String, ContractBundle> bundles = new LinkedHashMap<>();
    private final ProcessingCheckpointTransaction checkpointTransaction;
    private final TerminationService terminationService;
    private final ChannelRunner channelRunner;
    private final ScopeExecutor scopeExecutor;
    private final ContractRecognitionMeter
            contractRecognitionMeter;
    private final EvidenceClassificationView classificationView;
    private final EvidenceDeliveryOrchestrator evidenceDeliveryOrchestrator;
    private final ProcessingResultCoordinator resultCoordinator;
    private final ExecutionLifecycleCoordinator lifecycleCoordinator;
    private VerifiedExecutionEvidence executionEvidence;

    ProcessorInvocationState(DocumentProcessor owner, Node document) {
        this(owner, document, null);
    }

    ProcessorInvocationState(
            DocumentProcessor owner,
            Node document,
            Node processEventSource) {
        this(owner, document, processEventSource, FrozenNode::fromResolvedNode);
    }

    ProcessorInvocationState(
            DocumentProcessor owner,
            Node document,
            Node processEventSource,
            VerifiedExecutionEvidence executionEvidence) {
        this(owner, document, processEventSource, FrozenNode::fromResolvedNode);
        this.executionEvidence = executionEvidence;
    }

    ProcessorInvocationState(
            DocumentProcessor owner,
            Node document,
            Node processEventSource,
            ProcessorEngine.ProcessEventSnapshotFactory processEventSnapshotFactory) {
        this.owner = owner;
        this.inputDocument = document.clone();
        this.inputSnapshot = null;
        this.runtime = new DocumentProcessingRuntime(document,
                owner.conformanceEngine(),
                owner.conformancePlannerOverride(),
                owner.snapshotManager(),
                owner.observer(),
                owner.newGasMeter(),
                owner.registry()
                        .executableBodyFieldsByType());
        this.contractRecognitionMeter =
                new ContractRecognitionMeter(
                        runtime.gasMeter());
        this.processEventSnapshot =
                new ProcessingEventSnapshotBoundary(
                        processEventSource,
                        processEventSnapshotFactory,
                        owner.observer());
        this.checkpointTransaction =
                new ProcessingCheckpointTransaction(
                        runtime,
                        owner.matchingService().blue(),
                        owner.observer());
        this.terminationService = new TerminationService(runtime);
        this.channelRunner = new ChannelRunner(
                owner, this, runtime, checkpointTransaction);
        this.scopeExecutor = new ScopeExecutor(
                owner, this, runtime, bundles, channelRunner);
        this.lifecycleCoordinator = new ExecutionLifecycleCoordinator(
                this,
                runtime,
                scopeExecutor,
                terminationService);
        this.classificationView = new EvidenceClassificationView(
                owner,
                runtime,
                inputDocument,
                inputSnapshot,
                this::executionEvidence);
        this.evidenceDeliveryOrchestrator =
                new EvidenceDeliveryOrchestrator(
                        this,
                        runtime,
                        scopeExecutor,
                        bundles,
                        contractRecognitionMeter,
                        classificationView);
        this.resultCoordinator = new ProcessingResultCoordinator(
                owner,
                runtime,
                inputDocument,
                inputSnapshot,
                processEventSnapshot.isPresent(),
                this::executionEvidence);
    }

    ProcessorInvocationState(DocumentProcessor owner, ResolvedSnapshot snapshot) {
        this(owner, snapshot, null);
    }

    ProcessorInvocationState(
            DocumentProcessor owner,
            ResolvedSnapshot snapshot,
            Node processEventSource) {
        this(owner, snapshot, processEventSource, FrozenNode::fromResolvedNode);
    }

    ProcessorInvocationState(
            DocumentProcessor owner,
            ResolvedSnapshot snapshot,
            Node processEventSource,
            ProcessorEngine.ProcessEventSnapshotFactory processEventSnapshotFactory) {
        this.owner = owner;
        this.inputDocument = snapshot.canonicalRoot();
        this.inputSnapshot = snapshot;
        this.runtime = new DocumentProcessingRuntime(snapshot,
                owner.conformanceEngine(),
                owner.conformancePlannerOverride(),
                owner.snapshotManager(),
                owner.observer(),
                owner.newGasMeter(),
                owner.registry()
                        .executableBodyFieldsByType());
        this.contractRecognitionMeter =
                new ContractRecognitionMeter(
                        runtime.gasMeter());
        this.processEventSnapshot =
                new ProcessingEventSnapshotBoundary(
                        processEventSource,
                        processEventSnapshotFactory,
                        owner.observer());
        this.checkpointTransaction =
                new ProcessingCheckpointTransaction(
                        runtime,
                        owner.matchingService().blue(),
                        owner.observer());
        this.terminationService = new TerminationService(runtime);
        this.channelRunner = new ChannelRunner(
                owner, this, runtime, checkpointTransaction);
        this.scopeExecutor = new ScopeExecutor(
                owner, this, runtime, bundles, channelRunner);
        this.lifecycleCoordinator = new ExecutionLifecycleCoordinator(
                this,
                runtime,
                scopeExecutor,
                terminationService);
        this.classificationView = new EvidenceClassificationView(
                owner,
                runtime,
                inputDocument,
                inputSnapshot,
                this::executionEvidence);
        this.evidenceDeliveryOrchestrator =
                new EvidenceDeliveryOrchestrator(
                        this,
                        runtime,
                        scopeExecutor,
                        bundles,
                        contractRecognitionMeter,
                        classificationView);
        this.resultCoordinator = new ProcessingResultCoordinator(
                owner,
                runtime,
                inputDocument,
                inputSnapshot,
                processEventSnapshot.isPresent(),
                this::executionEvidence);
    }

    ProcessorInvocationState(
            DocumentProcessor owner,
            ResolvedSnapshot snapshot,
            Node processEventSource,
            VerifiedExecutionEvidence executionEvidence) {
        this(owner,
                snapshot,
                processEventSource,
                FrozenNode::fromResolvedNode);
        this.executionEvidence = executionEvidence;
    }

    void initializeScope(String scopePath, boolean chargeScopeEntry) {
        scopeExecutor.initializeScope(scopePath, chargeScopeEntry);
    }

    void preflightScope(String scopePath) {
        scopeExecutor.preflightEvidenceScope(scopePath);
    }

    /** Applies deterministic processor-owned cleanup before final validation. */
    void performFinalSoundnessValidation() {
        resultCoordinator.performFinalSoundnessValidation(
                scopeExecutor);
    }

    /** Validates the committing subscription surface and freezes its delta. */
    void validateSubscriptionDelta() {
        resultCoordinator.validateSubscriptionDelta();
    }

    ProcessingCheckpointTransaction checkpointTransaction() {
        return checkpointTransaction;
    }

    boolean admitDirectRootState() {
        return resultCoordinator.admitDirectRootState();
    }

    void admitEvidence() {
        evidenceDeliveryOrchestrator.admitEvidence();
    }

    boolean hasExecutionEvidence() {
        return executionEvidence != null;
    }

    VerifiedExecutionEvidence executionEvidence() {
        return executionEvidence;
    }

    void preflightOpaqueProcessEmbeddedBoundaries() {
        classificationView
                .preflightOpaqueProcessEmbeddedBoundaries();
    }

    FrozenNode classificationSelectedAt(String scopePath) {
        return classificationView.selectedAt(scopePath);
    }

    FrozenNode classificationResolvedAt(String scopePath) {
        return classificationView.resolvedAt(scopePath);
    }

    SubscriptionDelta.Entry activeSubscriptionInterval(
            String scopePath,
            String channelKey) {
        return classificationView.activeSubscriptionInterval(
                scopePath,
                channelKey);
    }

    void classifyExternalDeliveries(Node event) {
        evidenceDeliveryOrchestrator.classify(event);
    }

    void preflightParticipatingClosure() {
        evidenceDeliveryOrchestrator
                .preflightParticipatingClosure();
    }

    void prepareLogicalDeliveries() {
        evidenceDeliveryOrchestrator
                .prepareLogicalDeliveries();
    }

    void executeLogicalDeliveries() {
        evidenceDeliveryOrchestrator
                .executeLogicalDeliveries();
    }

    void handlePatch(String scopePath,
                     ContractBundle bundle,
                     JsonPatch patch,
                     boolean allowReservedMutation) {
        if (patch == null) {
            return;
        }
        handlePatches(scopePath,
                bundle,
                Collections.singletonList(patch),
                allowReservedMutation);
    }

    void handlePatches(String scopePath,
                       ContractBundle bundle,
                       List<JsonPatch> patches,
                       boolean allowReservedMutation) {
        scopeExecutor.handlePatches(scopePath, bundle, patches, allowReservedMutation);
    }

    void handlePatches(String scopePath,
                       ContractBundle bundle,
                       List<JsonPatch> patches,
                       boolean allowReservedMutation,
                       WorkingDocument.Preview preview) {
        scopeExecutor.handlePatches(scopePath, bundle, patches, allowReservedMutation, preview);
    }

    void handlePatchInputs(String scopePath,
                           ContractBundle bundle,
                           List<PatchInput> patches,
                           boolean allowReservedMutation,
                           WorkingDocument.Preview preview) {
        scopeExecutor.handlePatchInputs(scopePath, bundle, patches, allowReservedMutation, preview);
    }

    ProcessorExecutionContext createContext(String scopePath,
                                            ContractBundle bundle,
                                            Node event) {
        return createContext(scopePath, bundle, event, false);
    }

    ProcessorExecutionContext createContext(String scopePath,
                                            ContractBundle bundle,
                                            Node event,
                                            boolean allowReservedMutation) {
        return createContext(scopePath, bundle, event, null, null, allowReservedMutation);
    }

    ProcessorExecutionContext createContext(String scopePath,
                                            ContractBundle bundle,
                                            Node event,
                                            String contractKey,
                                            FrozenNode contractNode,
                                            boolean allowReservedMutation) {
        return createContext(
                scopePath,
                bundle,
                event,
                event,
                contractKey,
                contractNode,
                allowReservedMutation);
    }

    ProcessorExecutionContext createContext(String scopePath,
                                            ContractBundle bundle,
                                            Node event,
                                            Node occurrenceEvent,
                                            String contractKey,
                                            FrozenNode contractNode,
                                            boolean allowReservedMutation) {
        return new ProcessorExecutionContext(this, bundle, scopePath,
                contractKey, contractNode,
                cloneEvent(event),
                cloneEvent(occurrenceEvent),
                allowReservedMutation);
    }

    DocumentProcessingResult result() {
        return resultCoordinator.result();
    }

    ProcessingDebugResult debugResult() {
        return resultCoordinator.debugResult();
    }

    void fail(
            ProcessorStatus status,
            ProcessorDiagnostic diagnostic) {
        resultCoordinator.fail(status, diagnostic);
    }

    void recordAcceptedDelivery(
            String scopePath,
            String channelKey) {
        resultCoordinator.recordAcceptedDelivery();
        runtime.chargeChannelAccepted(scopePath, channelKey);
        evidenceDeliveryOrchestrator.recordAcceptanceProof(
                scopePath,
                channelKey);
    }

    void recordStaleDelivery() {
        resultCoordinator.recordStaleDelivery();
    }

    void recordCompletedDelivery() {
        resultCoordinator.recordCompletedDelivery();
    }

    void recordRootTermination() {
        resultCoordinator.recordCompletedDelivery();
    }

    ExternalDeliverySnapshot deliveryEvidence(
            String scopePath,
            String channelKey) {
        return evidenceDeliveryOrchestrator.deliveryEvidence(
                scopePath,
                channelKey);
    }

    String checkpointSubject(
            String scopePath,
            String channelKey,
            Node event) {
        return evidenceDeliveryOrchestrator.checkpointSubject(
                scopePath,
                channelKey,
                event);
    }

    ContractBundle initializeAcceptedScope(String scopePath) {
        List<String> path = frozenEvidenceScopeChain(scopePath);
        ContractBundle current = null;
        for (String participatingScope : path) {
            current = scopeExecutor.initializeEvidenceScope(
                    participatingScope);
            if (current == null
                    || shouldStopScopeWork(participatingScope)) {
                return null;
            }
        }
        return bundles.get(normalizeScope(scopePath));
    }

    List<String> frozenEvidenceScopeChain(String scopePath) {
        return evidenceDeliveryOrchestrator
                .frozenScopeChain(scopePath);
    }

    String checkpointDomain(
            ContractBundle.ChannelBinding channel,
            String scopePath) {
        return evidenceDeliveryOrchestrator.checkpointDomain(
                channel,
                scopePath);
    }

    boolean hasFailure() {
        return resultCoordinator.hasFailure();
    }

    DocumentProcessingResult partialResult() {
        return resultCoordinator.partialResult();
    }

    DocumentProcessingRuntime runtime() {
        return runtime;
    }

    ContractRecognitionMeter contractRecognitionMeter() {
        return contractRecognitionMeter;
    }

    Blue blue() {
        return owner.matchingService().blue();
    }

    boolean hasProcessEvent() {
        return processEventSnapshot.isPresent();
    }

    FrozenNode frozenProcessEvent() {
        return processEventSnapshot.frozenEvent();
    }

    boolean shouldStopScopeWork(String scopePath) {
        return lifecycleCoordinator.shouldStopScopeWork(scopePath);
    }

    boolean isScopeActive(String scopePath) {
        return lifecycleCoordinator.isScopeActive(scopePath);
    }

    boolean canDeliverOccurrenceLocally(
            ScopeRuntimeContext context) {
        return lifecycleCoordinator
                .canDeliverOccurrenceLocally(context);
    }

    boolean canCompleteTermination(String scopePath) {
        return lifecycleCoordinator
                .canCompleteTermination(scopePath);
    }

    void enterGracefulTermination(
            String scopePath,
            ContractBundle bundle,
            String reason) {
        enterGracefulTermination(
                scopePath,
                bundle,
                "graceful",
                reason);
    }

    void enterGracefulTermination(
            String scopePath,
            ContractBundle bundle,
            String cause,
            String reason) {
        lifecycleCoordinator.enterGracefulTermination(
                scopePath,
                bundle,
                cause,
                reason);
    }

    void abortRuntimeFailure(
            String scopePath,
            ContractBundle bundle,
            String reason) {
        abortRuntimeFailure(
                scopePath,
                bundle,
                ProcessorErrorCategory.RuntimeExecutionFailure,
                reason);
    }

    void abortRuntimeFailure(
            String scopePath,
            ContractBundle bundle,
            ProcessorErrorCategory errorCategory,
            String reason) {
        lifecycleCoordinator.abortRuntimeFailure(
                scopePath,
                errorCategory,
                reason);
    }

    ContractBundle bundleForScope(String scopePath) {
        return bundles.get(scopePath);
    }

    void markCutOff(String scopePath) {
        lifecycleCoordinator.markCutOff(scopePath);
    }

    String normalizeScope(String scopePath) {
        return ProcessorEngine.normalizeScope(scopePath);
    }

    String resolvePointer(String scopePath, String relativePointer) {
        return ProcessorEngine.resolvePointer(scopePath, relativePointer);
    }

    String fatalReason(Throwable throwable, String defaultReason) {
        String message = throwable != null ? throwable.getMessage() : null;
        return message != null ? message : defaultReason;
    }

    ProcessorErrorCategory fatalCategory(Throwable throwable, ProcessorErrorCategory defaultCategory) {
        if (throwable instanceof ProcessorFailureException) {
            return ((ProcessorFailureException) throwable).errorCategory();
        }
        if (throwable instanceof ProcessorFatalException) {
            return ((ProcessorFatalException) throwable).errorCategory();
        }
        if (throwable instanceof MustUnderstandFailureException) {
            return ((MustUnderstandFailureException) throwable).errorCategory();
        }
        return defaultCategory != null ? defaultCategory : ProcessorErrorCategory.RuntimeExecutionFailure;
    }

    void deliverLifecycle(
            String scopePath,
            ContractBundle bundle,
            Node event,
            boolean finalizeAfter) {
        lifecycleCoordinator.deliverLifecycle(
                scopePath,
                bundle,
                event,
                finalizeAfter);
    }

    void deliverTerminationLifecycle(
            String scopePath,
            ContractBundle bundle,
            Node event) {
        lifecycleCoordinator.deliverTerminationLifecycle(
                scopePath,
                bundle,
                event);
    }

    void enqueueApplicationEvent(
            String scopePath,
            String contractKey,
            Node event,
            String eventBlueId) {
        lifecycleCoordinator.enqueueApplicationEvent(
                scopePath,
                contractKey,
                event,
                eventBlueId);
    }

    void drainInternalEvents() {
        lifecycleCoordinator.drainInternalEvents();
    }

    void requestInternalEventDrain() {
        lifecycleCoordinator.requestInternalEventDrain();
    }

    void completePendingTerminations() {
        lifecycleCoordinator.completePendingTerminations();
    }

    private Node cloneEvent(Node event) {
        return event != null ? event.clone() : null;
    }
}
