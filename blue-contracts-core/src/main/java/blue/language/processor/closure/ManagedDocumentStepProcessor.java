package blue.language.processor.closure;

import blue.language.api.BlueOperationOutcome;
import blue.language.api.BlueOperationResult;
import blue.language.model.Node;
import blue.language.model.NodePathEditor;
import blue.language.processor.DocumentProcessor;
import blue.language.processor.DocumentUpdateOccurrence;
import blue.language.processor.GasChargeContext;
import blue.language.processor.GasTraceEntry;
import blue.language.processor.ManagedDocumentStepContinuation;
import blue.language.processor.ManagedDocumentStepOutcome;
import blue.language.processor.ManagedDocumentStepRequest;
import blue.language.processor.ManagedDocumentResolutionOverlay;
import blue.language.processor.ManagedDocumentStepRoute;
import blue.language.processor.ManagedDocumentStepRuntime;
import blue.language.processor.ManagedDocumentWorkKind;
import blue.language.processor.ManagedCheckpointCandidate;
import blue.language.processor.ManagedCheckpointBatchCleanupContextFactory;
import blue.language.processor.ManagedCheckpointCleanupContextFactory;
import blue.language.processor.ManagedCheckpointSettlementBatch;
import blue.language.processor.ManagedCheckpointSettlementEntry;
import blue.language.processor.ManagedCheckpointSettlementRequest;
import blue.language.processor.ManagedCheckpointSettlement;
import blue.language.processor.ManagedExternalDeliveryClassification;
import blue.language.processor.ManagedProcessEmbeddedPath;
import blue.language.processor.ManagedRootChannelOccurrence;
import blue.language.processor.ManagedRootSubscriptionSurface;
import blue.language.processor.ManagedSemanticGasBridge;
import blue.language.processor.ProcessorRuntimeAccess;
import blue.language.processor.ExecutionEvidenceUnavailableException;
import blue.language.processor.InvalidExecutionEvidenceException;
import blue.language.processor.ProcessorErrorCategory;
import blue.language.snapshot.FrozenNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Maps closed closure evidence onto the isolated processor-side runtime. */
final class ManagedDocumentStepProcessor
        implements DocumentStepProcessor, AutoCloseable {

    private final ManagedDocumentStepRuntime runtime;
    private final DocumentProcessor owner;

    ManagedDocumentStepProcessor(DocumentProcessor owner) {
        DocumentProcessor checked = Objects.requireNonNull(owner, "owner");
        this.owner = checked;
        this.runtime = new ManagedDocumentStepRuntime(
                checked);
    }

    ManagedDocumentStepProcessor(
            DocumentProcessor owner,
            ManagedDocumentStepContinuation continuation) {
        DocumentProcessor checked = Objects.requireNonNull(owner, "owner");
        this.owner = checked;
        this.runtime = new ManagedDocumentStepRuntime(
                checked,
                Objects.requireNonNull(continuation, "continuation"));
    }

    ManagedDocumentStepProcessor(
            DocumentProcessor owner,
            ExecutionPolicy policy,
            ManagedDocumentStepContinuation continuation) {
        ExecutionPolicy admitted = Objects.requireNonNull(policy, "policy");
        DocumentProcessor checked = Objects.requireNonNull(owner, "owner");
        this.owner = checked;
        this.runtime = new ManagedDocumentStepRuntime(
                checked,
                admitted.sharedLimit(),
                localLimits(admitted),
                Objects.requireNonNull(continuation, "continuation"));
    }

    @Override
    public LocalDocumentStepResult process(DocumentStepInput input) {
        return process(input, null);
    }

    LocalDocumentStepResult process(
            DocumentStepInput input,
            ManagedDocumentStepRoute selectedRoute) {
        DocumentStepInput admitted = Objects.requireNonNull(input, "input");
        ManagedDocumentSnapshot target = admitted.targetDocument();
        TentativeResolutionContext context = admitted.resolutionContext();
        Node isolatedRoot = target.document();
        for (Map.Entry<String, String> entry
                : context.targetManagedBlueIdsByPath().entrySet()) {
            if (NodePathEditor.getOrNull(isolatedRoot, entry.getKey())
                    != null) {
                NodePathEditor.put(
                        isolatedRoot,
                        entry.getKey(),
                        new Node().blueId(entry.getValue()));
            }
        }
        LinkedHashMap<String, Node> exactNodes =
                new LinkedHashMap<String, Node>();
        Map<DocumentId, Node> currentDocuments =
                context.currentDocuments();
        for (Map.Entry<DocumentId, String> entry
                : context.currentBlueIds().entrySet()) {
            Node exact = currentDocuments.get(entry.getKey());
            Node previous = exactNodes.put(entry.getValue(), exact);
            if (previous != null
                    && !blue.language.model.NodeWireForm.get(previous)
                            .equals(blue.language.model.NodeWireForm.get(exact))) {
                throw new IllegalArgumentException(
                        "One tentative BlueId identifies different documents");
            }
        }
        ManagedDocumentStepRequest request =
                new ManagedDocumentStepRequest(
                        isolatedRoot,
                        target.initialized(),
                        target.terminated(),
                        map(admitted.work().kind()),
                        admitted.work().channelKey(),
                        admitted.exactPayload(),
                        admitted.occurrenceEvent(),
                        admitted.processorPatch(),
                        GasChargeContext.closure(
                                target.documentId().value(),
                                "/",
                                Long.valueOf(0L),
                                Long.valueOf(target.componentGeneration()),
                                admitted.work().channelKey().isEmpty()
                                        ? null
                                        : admitted.work().channelKey(),
                                null,
                                admitted.work().workIdentity(),
                                "managed-document-step"),
                        new ManagedDocumentResolutionOverlay(
                                exactNodes,
                                context.targetManagedBlueIdsByPath()));
        ManagedDocumentStepOutcome outcome = selectedRoute == null
                ? runtime.execute(request)
                : runtime.executeSelectedRoute(
                        request, selectedRoute);
        return new LocalDocumentStepResult(
                target.documentId(),
                admitted.work().workIdentity(),
                target.blueId(),
                outcome.resultingBody(),
                outcome.emittedEvents(),
                outcome.orderedPatches(),
                outcome.gasBefore(),
                outcome.gasAfter(),
                outcome.identityAffecting(),
                DocumentTransitionEvidence.fromManagedStep(
                        target.documentId(),
                        admitted.work().workIdentity(),
                        target.blueId(),
                        outcome.beforeEffectiveTypeBlueId(),
                        outcome.afterEffectiveTypeBlueId(),
                        outcome.orderedPatches(),
                        outcome.orderedPatchUpdates(),
                        outcome.generatedGeneralizationWrites()));
    }

    private static ManagedDocumentWorkKind map(WorkKind kind) {
        return ManagedDocumentWorkKind.valueOf(
                Objects.requireNonNull(kind, "kind").name());
    }

    List<ManagedDocumentStepRoute> classifyTriggeredEventRoutes(
            Node exactDocument,
            Node exactEvent) {
        return runtime.classifyTriggeredEventRoutes(
                exactDocument, exactEvent);
    }

    List<ManagedDocumentStepRoute> classifyLifecycleRoutes(
            Node exactDocument,
            Node exactEvent) {
        return runtime.classifyLifecycleRoutes(exactDocument, exactEvent);
    }

    List<ManagedDocumentStepRoute> classifyEmbeddedEventRoutes(
            Node exactContainingDocument,
            String exactSourcePath,
            Node exactEvent,
            String exactEventBlueId) {
        return runtime.classifyEmbeddedEventRoutes(
                exactContainingDocument,
                exactSourcePath,
                exactEvent,
                exactEventBlueId);
    }

    List<ManagedDocumentStepRoute> classifyDocumentUpdateRoutes(
            Node exactDocument,
            DocumentUpdateOccurrence occurrence) {
        return runtime.classifyDocumentUpdateRoutes(
                exactDocument, occurrence);
    }

    List<ManagedDocumentStepRoute>
    classifyFinalizationDocumentUpdateRoutes(
            Node exactDocument,
            String absolutePath,
            Node before,
            Node after) {
        return runtime.classifyFinalizationDocumentUpdateRoutes(
                exactDocument,
                absolutePath,
                before,
                after);
    }

    void validateManagedEmbeddedPaths(
            Node exactDocument,
            Map<String, String> expectedBlueIdsByPath) {
        runtime.validateManagedEmbeddedPaths(
                exactDocument, expectedBlueIdsByPath);
    }

    List<ManagedProcessEmbeddedPath> projectManagedProcessEmbeddedSurface(
            Node exactDocument) {
        return runtime.projectManagedProcessEmbeddedSurface(exactDocument);
    }

    /**
     * Performs an unmetered, read-only availability check for an exact
     * reference needed during noncommitting demand discovery.
     */
    boolean isExactManagedReferenceAvailable(String expectedBlueId) {
        return runtime.isExactManagedReferenceAvailable(
                Objects.requireNonNull(expectedBlueId, "expectedBlueId"));
    }

    void requireExactManagedReference(String expectedBlueId) {
        String blueId = Objects.requireNonNull(
                expectedBlueId, "expectedBlueId");
        ProcessorRuntimeAccess access = owner.administration().runtimeAccess();
        BlueOperationResult<FrozenNode> result = access
                .materializeVerifiedExactReference(
                        FrozenNode.fromNode(new Node().blueId(blueId)));
        BlueOperationOutcome outcome = result.outcome();
        if (outcome == BlueOperationOutcome.ESTABLISHED) {
            return;
        }
        String diagnostic = result.reason().orElse(
                "Exact managed historical reference could not be established: "
                        + blueId);
        if (outcome == BlueOperationOutcome.INCOMPLETE) {
            throw new ExecutionEvidenceUnavailableException(
                    diagnostic, result.outstandingBlueIds());
        }
        throw new InvalidExecutionEvidenceException(
                diagnostic,
                ProcessorErrorCategory.InvalidProcessingDocument);
    }

    List<ManagedRootChannelOccurrence> projectRootChannelSurface(
            Node exactDocument) {
        return runtime.projectRootChannelSurface(exactDocument);
    }

    ManagedRootSubscriptionSurface projectRootSubscriptionSurface(
            Node exactDocument) {
        return runtime.projectRootSubscriptionSurface(exactDocument);
    }

    ManagedExternalDeliveryClassification classifyExternalDelivery(
            Node exactDocument,
            String rawChannelKey,
            Node exactEvent,
            GasChargeContext context) {
        return runtime.classifyExternalDelivery(
                exactDocument,
                rawChannelKey,
                exactEvent,
                context);
    }

    ManagedCheckpointSettlement settleCheckpoints(
            Node exactDocument,
            List<ManagedCheckpointSettlementEntry> completedEntries,
            ManagedCheckpointCleanupContextFactory cleanupContextFactory,
            GasChargeContext batchContext) {
        return runtime.settleCheckpoints(
                exactDocument,
                completedEntries,
                cleanupContextFactory,
                batchContext);
    }

    ManagedCheckpointSettlementBatch settleCheckpointBatch(
            List<ManagedCheckpointSettlementRequest> requests,
            ManagedCheckpointBatchCleanupContextFactory
                    cleanupContextFactory) {
        return runtime.settleCheckpointBatch(
                requests, cleanupContextFactory);
    }

    String runtimeRegistryIdentity() {
        return runtime.runtimeRegistryIdentity();
    }

    String gasManifestIdentity() {
        return runtime.gasManifestIdentity();
    }

    void charge(
            String namespace,
            String counter,
            long quantity,
            GasChargeContext context) {
        runtime.charge(namespace, counter, quantity, context);
    }

    ManagedSemanticGasBridge semanticGas(GasChargeContext attribution) {
        return runtime.semanticGas(attribution);
    }

    Node writeDetachedProcessorState(
            Node exactRoot,
            String path,
            Node exactValue,
            GasChargeContext attribution) {
        return runtime.writeDetachedProcessorState(
                exactRoot, path, exactValue, attribution);
    }

    long totalGas() {
        return runtime.totalGas();
    }

    List<GasTraceEntry> processorGasTrace() {
        return runtime.gasTrace();
    }

    private static Map<String, Long> localLimits(
            ExecutionPolicy policy) {
        LinkedHashMap<String, Long> result =
                new LinkedHashMap<String, Long>();
        for (Map.Entry<DocumentId, Long> entry
                : policy.localLimits().entrySet()) {
            result.put(entry.getKey().value(), entry.getValue());
        }
        return result;
    }

    @Override
    public void close() {
        runtime.close();
    }
}
