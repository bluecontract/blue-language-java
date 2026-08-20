package blue.language.processor.closure;

import blue.language.identity.CyclicSetFinalization;
import blue.language.model.Node;
import blue.language.model.NodePathEditor;
import blue.language.model.NodeWireForm;
import blue.language.processor.DocumentProcessor;
import blue.language.processor.DocumentUpdateOccurrence;
import blue.language.processor.FrozenJsonPatch;
import blue.language.processor.GasChargeContext;
import blue.language.processor.InvalidExecutionEvidenceException;
import blue.language.processor.ManagedCheckpointBatchCleanupContextFactory;
import blue.language.processor.ManagedCheckpointCandidate;
import blue.language.processor.ManagedCheckpointSettlementBatch;
import blue.language.processor.ManagedCheckpointSettlementEntry;
import blue.language.processor.ManagedCheckpointSettlementRequest;
import blue.language.processor.ManagedDocumentStepContinuation;
import blue.language.processor.ManagedDocumentStepRoute;
import blue.language.processor.ManagedExternalDeliveryClassification;
import blue.language.processor.ManagedRootChannelOccurrence;
import blue.language.processor.ProcessorErrorCategory;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.processor.util.ProcessorContractConstants;
import blue.language.processor.util.ProcessorPointerConstants;
import blue.language.processor.util.PointerUtils;
import blue.language.snapshot.FrozenNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * One real affected-closure execution loop over independent document Roots.
 *
 * <p>The session owns graph and identity state.  Application execution is
 * always delegated to the single {@link ManagedDocumentStepProcessor}; the
 * same function and shared gas context therefore process acyclic and cyclic
 * documents.  Reverse occurrence traversal happens only here and never enters
 * a document runtime context.</p>
 */
final class ClosureExecutionSession
        implements ManagedDocumentStepContinuation, AutoCloseable {

    private static final ClosureIdentityService IDENTITIES =
            ClosureIdentityService.INSTANCE;
    private static final String PROVISIONAL_IDENTITY =
            "sha256:0000000000000000000000000000000000000000000000000000000000000000";
    private static final String CYCLIC_CANONICAL_BYTES_LIMIT =
            "cyclicCanonicalBytesPerComponent";

    private final ClosureInvocationInput input;
    private final ClosureExecutionRecorder recorder;
    private final ManagedDocumentStepProcessor stepProcessor;
    private final ComponentFinalizationKernel finalizer =
            new ComponentFinalizationKernel();
    private final ClosureFinalizationGasCharger finalizationGas =
            new ClosureFinalizationGasCharger();
    private final ManagedDocumentGraph inputGraph;
    private final Map<DocumentId, Long> inputComponentGenerations;
    private final Set<String> existingBlueIds;
    private final Set<String> establishedBlueIds =
            new LinkedHashSet<String>();
    private final Set<DocumentId> epochAdvanceDocuments =
            new LinkedHashSet<DocumentId>();
    private final Set<DocumentId> initializedDocuments =
            new LinkedHashSet<DocumentId>();
    private final Map<DocumentId, String> pendingInitializationCauses =
            new LinkedHashMap<DocumentId, String>();
    private final Map<DocumentId, Node> latestBodies =
            new LinkedHashMap<DocumentId, Node>();
    private final ClosureWorkQueue workQueue = new ClosureWorkQueue();
    private final Map<String, PendingWork> pendingByIdentity =
            new HashMap<String, PendingWork>();
    private final Deque<EmittedOccurrence> eventQueue =
            new ArrayDeque<EmittedOccurrence>();
    private final Deque<ActiveFrame> activeFrames =
            new ArrayDeque<ActiveFrame>();
    private final List<PublicEventOccurrence> publicEvents =
            new ArrayList<PublicEventOccurrence>();
    private final Map<DocumentId, List<ManagedRootChannelOccurrence>>
            inputChannelSurfaces =
            new LinkedHashMap<DocumentId,
                    List<ManagedRootChannelOccurrence>>();
    private final Map<DocumentId, List<ManagedRootChannelOccurrence>>
            resultingChannelSurfaces =
            new LinkedHashMap<DocumentId,
                    List<ManagedRootChannelOccurrence>>();
    private final Map<DocumentId, List<CompletedCheckpointCandidate>>
            completedCheckpointCandidates =
            new LinkedHashMap<DocumentId,
                    List<CompletedCheckpointCandidate>>();
    private final List<ManagedCheckpointSettlementBatch.Mutation>
            checkpointMutations =
            new ArrayList<ManagedCheckpointSettlementBatch.Mutation>();

    private AffectedClosureSnapshot currentSnapshot;
    private List<ManagedOccurrenceBinding> currentBindings;
    private ComponentFinalizationResult currentFinalization;
    private long graphGeneration;
    private long graphChanges;
    private long nextWorkOrdinal;
    private long nextStepOrdinal;
    private long nextEventOrdinal;
    private long nextTransitionOrdinal;
    private int eventDeliveryBatchDepth;
    private boolean initializationBatchRunning;
    private long initializationBatchLastWorkOrdinal = -1L;
    private boolean activateManagedRevision;
    private boolean managedRevisionActivationCompleted;
    private boolean managedRevisionReceiptReconciled;
    private boolean closed;

    ClosureExecutionSession(
            DocumentProcessor owner,
            ClosureInvocationInput input,
            ClosureExecutionRecorder recorder) {
        this.input = Objects.requireNonNull(input, "input");
        this.recorder = Objects.requireNonNull(recorder, "recorder");
        this.currentSnapshot = input.snapshot();
        this.currentBindings = new ArrayList<ManagedOccurrenceBinding>(
                currentSnapshot.occurrences());
        this.graphGeneration = currentSnapshot.graphGeneration();
        ArrayList<DocumentId> documentIds = new ArrayList<DocumentId>();
        this.inputComponentGenerations =
                new LinkedHashMap<DocumentId, Long>();
        for (ManagedDocumentSnapshot document
                : currentSnapshot.managedDocuments()) {
            documentIds.add(document.documentId());
            latestBodies.put(document.documentId(), document.document());
            inputComponentGenerations.put(
                    document.documentId(),
                    Long.valueOf(document.componentGeneration()));
            if (document.initialized()) {
                initializedDocuments.add(document.documentId());
            }
        }
        this.inputGraph = ManagedDocumentGraph.fromBindings(
                documentIds, currentBindings);
        this.existingBlueIds = finalizationGas.existingIdentities(
                latestBodies);
        this.stepProcessor = new ManagedDocumentStepProcessor(
                Objects.requireNonNull(owner, "owner"),
                input.executionPolicy(),
                this);
    }

    /** Executes one supported affected-closure lane. */
    ClosureExecutionState execute() {
        ensureSupportedInvocation();
        chargeAdmission();
        captureChannelSurfaces(inputChannelSurfaces);
        if (input.cause().kind() == ProcessingCause.Kind.EXTERNAL) {
            executeExternalCause((ExternalEventCause) input.cause());
        } else {
            executeManagedRevisionCause(
                    (ManagedRevisionCause) input.cause());
        }

        if (!eventQueue.isEmpty() || !workQueue.isEmpty()) {
            throw new IllegalStateException(
                    "Closure execution did not reach causal quiescence");
        }
        requireProspectiveMembersActivated();
        if (input.cause().kind() == ProcessingCause.Kind.EXTERNAL) {
            settleCheckpointBarrier();
        }
        captureChannelSurfaces(resultingChannelSurfaces);
        return state();
    }

    private void executeExternalCause(ExternalEventCause cause) {
        List<ClosureWorkOccurrence> directSeeds =
                ClosureDirectSeedPlanner.plan(
                        input.invocationIdentity(),
                        cause.eventBlueId(),
                        currentSnapshot.components(),
                        input.directDeliveries());
        requireWorkOccurrenceCount(directSeeds.size());
        nextWorkOrdinal = directSeeds.size();
        Map<String, ClosureWorkOccurrence> seedBySource =
                new HashMap<String, ClosureWorkOccurrence>();
        for (ClosureWorkOccurrence seed : directSeeds) {
            seedBySource.put(seed.sourceOccurrenceIdentity(), seed);
        }

        Map<String, ManagedCheckpointCandidate> candidateBySource =
                new HashMap<String, ManagedCheckpointCandidate>();
        Map<String, DirectLogicalDelivery> deliveryBySource =
                new HashMap<String, DirectLogicalDelivery>();
        for (DirectLogicalDelivery delivery : input.directDeliveries()) {
            String sourceIdentity = IDENTITIES.directDeliveryIdentity(
                    delivery);
            ClosureWorkOccurrence seed = seedBySource.get(sourceIdentity);
            if (seed == null) {
                throw new IllegalStateException(
                        "Direct-seed plan lost a frozen logical delivery");
            }
            ManagedExternalDeliveryClassification classification =
                    stepProcessor.classifyExternalDelivery(
                            latestBodies.get(delivery.targetDocumentId()),
                            delivery.channelKey(),
                            cause.event(),
                            workContext(seed,
                                    "direct-admission."
                                            + delivery.rawOccurrenceOrder()
                                            + ".classification"));
            candidateBySource.put(
                    sourceIdentity,
                    requireAcceptedDirect(
                            delivery, classification));
            deliveryBySource.put(sourceIdentity, delivery);
        }
        for (ClosureWorkOccurrence seed : directSeeds) {
            ManagedCheckpointCandidate candidate = candidateBySource.get(
                    seed.sourceOccurrenceIdentity());
            if (candidate == null) {
                throw new IllegalStateException(
                        "Accepted direct seed has no Phase-B candidate");
            }
            DirectLogicalDelivery delivery = deliveryBySource.get(
                    seed.sourceOccurrenceIdentity());
            recorder.accepted(seed);
            pendingByIdentity.put(
                    seed.workIdentity(),
                    new PendingWork(
                            seed,
                            candidate.exactPayload(),
                            null,
                            null,
                            candidate,
                            Long.valueOf(
                                    delivery.rawOccurrenceOrder())));
        }
        for (ClosureWorkOccurrence seed : directSeeds) {
            enqueueAlreadyAccepted(seed, "work."
                    + seed.ordinal() + ".seed-enqueue");
        }
        drainCausalWork();
    }

    private void executeManagedRevisionCause(
            ManagedRevisionCause cause) {
        requireWorkOccurrenceCount(1L);
        ManagedOccurrenceBinding target = managedRevisionTarget(cause);
        ManagedDocumentSnapshot source = currentSnapshot.managedDocument(
                target.sourceDocumentId());
        String scopeIdentity = IDENTITIES.managedScopeKeyIdentity(
                ManagedScopeKey.root(source.documentId()));
        ClosureWorkOccurrence work = new ClosureWorkOccurrence(
                0L,
                WorkKind.CONTAINING_REFERENCE_UPDATE,
                source.documentId(),
                "managed-revision",
                null,
                null,
                scopeIdentity,
                cause.causeIdentity(),
                IDENTITIES.workOccurrenceIdentity(
                        input.invocationIdentity(),
                        0L,
                        WorkKind.CONTAINING_REFERENCE_UPDATE,
                        scopeIdentity,
                        cause.causeIdentity()));
        nextWorkOrdinal = 1L;
        String afterBlueId = finalizationGas
                .chargeManagedRevisionAfterDocument(
                stepProcessor,
                cause.childDocumentId(),
                cause.afterDocument(),
                establishedBlueIds,
                existingBlueIds);
        if (!cause.afterBlueId().equals(afterBlueId)) {
            throw new IllegalStateException(
                    "Managed-revision gas admission changed afterBlueId");
        }
        FrozenJsonPatch patch = FrozenJsonPatch.replace(
                target.sourcePath(),
                FrozenNode.fromNode(
                        new Node().blueId(cause.afterBlueId())));
        recorder.accepted(work);
        pendingByIdentity.put(
                work.workIdentity(),
                new PendingWork(
                        work,
                        cause.afterDocument(),
                        null,
                        patch,
                        null,
                        null));
        enqueueAlreadyAccepted(work, "work.0.seed-enqueue");
        drainCausalWork();
    }

    private ManagedOccurrenceBinding managedRevisionTarget(
            ManagedRevisionCause cause) {
        for (ManagedOccurrenceBinding binding : currentBindings) {
            if (binding.occurrenceIdentity().equals(
                    cause.targetOccurrenceIdentity())) {
                return binding;
            }
        }
        throw new IllegalStateException(
                "Managed revision target left the affected closure");
    }

    private void ensureSupportedInvocation() {
        if (input.operation()
                != ClosureInvocationInput.Operation.PROCESS_CLOSURE
                || (input.cause().kind() != ProcessingCause.Kind.EXTERNAL
                && input.cause().kind()
                        != ProcessingCause.Kind.MANAGED_REVISION)) {
            throw new ClosureCapabilityGapException(
                    "PROCESS_CAUSE_UNSUPPORTED",
                    "The concrete engine accepts external or one managed "
                            + "revision cause");
        }
        if (input.cause().kind() == ProcessingCause.Kind.EXTERNAL
                && input.directDeliveries().isEmpty()) {
            throw new ClosureCapabilityGapException(
                    "EXTERNAL_DIRECT_DELIVERY_REQUIRED",
                    "The first concrete engine lane requires one accepted direct delivery");
        }
        if (input.cause().kind() == ProcessingCause.Kind.MANAGED_REVISION
                && !input.directDeliveries().isEmpty()) {
            throw new IllegalArgumentException(
                    "Managed revision must not carry direct deliveries");
        }
        for (ManagedDocumentSnapshot document
                : input.snapshot().managedDocuments()) {
            if (document.terminated()) {
                throw new ClosureCapabilityGapException(
                        "TERMINATED_MEMBER_POLICY_REQUIRED",
                        "Terminated members require lifecycle delivery policy");
            }
            if (!document.initialized()
                    && !isInactiveProspectiveTarget(
                            document.documentId())) {
                throw new ClosureCapabilityGapException(
                        "INITIALIZATION_BATCH_REQUIRED",
                        "A PROCESS_CLOSURE input may retain an uninitialized "
                                + "member only behind explicit inactive "
                                + "prospective occurrence evidence");
            }
        }
    }

    private boolean isInactiveProspectiveTarget(DocumentId documentId) {
        boolean prospective = false;
        for (ManagedOccurrenceBinding binding
                : input.snapshot().occurrences()) {
            if (!binding.targetDocumentId().equals(documentId)) {
                continue;
            }
            if (binding.active()
                    || binding.pendingHistoricalEpoch() != null) {
                return false;
            }
            prospective = true;
        }
        if (!prospective) {
            return false;
        }
        for (DirectLogicalDelivery delivery : input.directDeliveries()) {
            if (delivery.targetDocumentId().equals(documentId)) {
                return false;
            }
        }
        return true;
    }

    private void requireProspectiveMembersActivated() {
        for (ManagedDocumentSnapshot document
                : input.snapshot().managedDocuments()) {
            if (!document.initialized()
                    && !initializedDocuments.contains(
                            document.documentId())) {
                throw new InvalidExecutionEvidenceException(
                        "Expected managed occurrence activation did not "
                                + "initialize prospective document "
                                + document.documentId(),
                        ProcessorErrorCategory
                                .ManagedOccurrenceBindingMissing);
            }
        }
    }

    private ManagedCheckpointCandidate requireAcceptedDirect(
            DirectLogicalDelivery delivery,
            ManagedExternalDeliveryClassification classification) {
        ManagedExternalDeliveryClassification admitted =
                Objects.requireNonNull(classification, "classification");
        ManagedCheckpointCandidate candidate = admitted.candidate();
        if (admitted.state()
                        != ManagedExternalDeliveryClassification.State
                                .ACCEPTED_NEW
                || !admitted.preselected()
                || !admitted.accepted()
                || !admitted.handlerMatched()
                || candidate == null) {
            throw new ClosureCapabilityGapException(
                    "DIRECT_DELIVERY_RECLASSIFIED",
                    "A supplied direct logical delivery is not an accepted-new exact source");
        }
        if (!candidate.rawChannelKey().equals(delivery.channelKey())
                || !candidate.handlerChannelKey().equals(
                        delivery.channelKey())) {
            throw new ClosureCapabilityGapException(
                    "DELEGATED_EXTERNAL_SOURCE_REQUIRED",
                    "The first concrete lane requires source and handler Channel keys to coincide");
        }
        if (!candidate.logicalDeliveryKey().equals(
                    delivery.logicalDeliveryKey())) {
            throw new IllegalArgumentException(
                    "Frozen logical-delivery key disagrees with the configured runtime");
        }
        return candidate;
    }

    private void captureChannelSurfaces(
            Map<DocumentId, List<ManagedRootChannelOccurrence>> target) {
        target.clear();
        for (ManagedDocumentSnapshot document
                : currentSnapshot.managedDocuments()) {
            target.put(
                    document.documentId(),
                    stepProcessor.projectRootChannelSurface(
                            latestBodies.get(document.documentId())));
        }
    }

    private void settleCheckpointBarrier() {
        final Map<String, ManagedDocumentSnapshot> documentsByScopeIdentity =
                new LinkedHashMap<String, ManagedDocumentSnapshot>();
        List<ManagedCheckpointSettlementRequest> requests =
                new ArrayList<ManagedCheckpointSettlementRequest>();
        for (ManagedDocumentSnapshot document
                : currentSnapshot.managedDocuments()) {
            String targetManagedScopeIdentity = IDENTITIES
                    .managedScopeKeyIdentity(
                            ManagedScopeKey.root(document.documentId()));
            documentsByScopeIdentity.put(
                    targetManagedScopeIdentity, document);
            List<CompletedCheckpointCandidate> candidates =
                    completedCheckpointCandidates.get(
                            document.documentId());
            if (candidates == null) {
                candidates = Collections.emptyList();
            }
            ArrayList<ManagedCheckpointSettlementEntry> entries =
                    new ArrayList<ManagedCheckpointSettlementEntry>();
            for (CompletedCheckpointCandidate completed : candidates) {
                entries.add(new ManagedCheckpointSettlementEntry(
                        completed.candidate,
                        completed.rawOccurrenceOrder,
                        checkpointWriteContext(
                                document,
                                completed.candidate.rawChannelKey(),
                                completed.rawOccurrenceOrder)));
            }
            requests.add(new ManagedCheckpointSettlementRequest(
                    targetManagedScopeIdentity,
                    latestBodies.get(document.documentId()),
                    entries,
                    settlementContext(document)));
        }
        ManagedCheckpointSettlementBatch settlement =
                stepProcessor.settleCheckpointBatch(
                        requests,
                        new ManagedCheckpointBatchCleanupContextFactory() {
                            @Override
                            public GasChargeContext contextFor(
                                    String targetManagedScopeIdentity,
                                    String rawChannelKey,
                                    long checkpointWriteOrdinal) {
                                ManagedDocumentSnapshot target =
                                        documentsByScopeIdentity.get(
                                                targetManagedScopeIdentity);
                                if (target == null) {
                                    throw new IllegalArgumentException(
                                            "Unknown checkpoint settlement target");
                                }
                                return checkpointCleanupContext(
                                        target,
                                        rawChannelKey,
                                        checkpointWriteOrdinal);
                            }
                        });
        Set<String> changedScopes = new LinkedHashSet<String>();
        for (ManagedCheckpointSettlementBatch.Mutation mutation
                : settlement.mutations()) {
            changedScopes.add(mutation.targetManagedScopeIdentity());
            ManagedDocumentSnapshot target = documentsByScopeIdentity.get(
                    mutation.targetManagedScopeIdentity());
            if (target == null) {
                throw new IllegalStateException(
                        "Checkpoint batch returned an unknown target");
            }
        }
        for (ManagedCheckpointSettlementBatch.TargetResult target
                : settlement.targets()) {
            if (changedScopes.contains(
                    target.targetManagedScopeIdentity())) {
                ManagedDocumentSnapshot document = documentsByScopeIdentity
                        .get(target.targetManagedScopeIdentity());
                latestBodies.put(
                        document.documentId(),
                        target.resultingBody());
            }
        }
        checkpointMutations.addAll(settlement.mutations());
        if (!settlement.mutations().isEmpty()) {
            finalizeTentative(
                    TentativeFinalization.Boundary
                            .checkpointSettlement(),
                    null,
                    null,
                    Collections.<String>emptySet());
        }
    }

    private GasChargeContext settlementContext(
            ManagedDocumentSnapshot document) {
        ManagedDocumentSnapshot current = currentSnapshot.managedDocument(
                document.documentId());
        return GasChargeContext.closure(
                document.documentId().value(),
                "/",
                Long.valueOf(0L),
                Long.valueOf(current.componentGeneration()),
                null,
                null,
                null,
                "checkpoint-settlement.0");
    }

    private GasChargeContext checkpointWriteContext(
            ManagedDocumentSnapshot document,
            String rawChannelKey,
            long rawOccurrenceOrder) {
        ManagedDocumentSnapshot current = currentSnapshot.managedDocument(
                document.documentId());
        return GasChargeContext.closure(
                document.documentId().value(),
                "/",
                Long.valueOf(0L),
                Long.valueOf(current.componentGeneration()),
                rawChannelKey,
                null,
                null,
                "checkpoint-settlement.0.write."
                        + rawOccurrenceOrder);
    }

    private GasChargeContext checkpointCleanupContext(
            ManagedDocumentSnapshot document,
            String rawChannelKey,
            long cleanupOrdinal) {
        ManagedDocumentSnapshot current = currentSnapshot.managedDocument(
                document.documentId());
        return GasChargeContext.closure(
                document.documentId().value(),
                "/",
                Long.valueOf(0L),
                Long.valueOf(current.componentGeneration()),
                rawChannelKey,
                null,
                null,
                "checkpoint-settlement.0.cleanup."
                        + cleanupOrdinal);
    }

    private void chargeAdmission() {
        charge("processor", "processInvocation", 1L,
                GasChargeContext.reason("admission.process"));
        charge("processor", "closureInvocation", 1L,
                GasChargeContext.reason("admission.closure"));
        if (!input.directDeliveries().isEmpty()) {
            charge("processor", "deliverySnapshotEntry",
                    input.directDeliveries().size(),
                    GasChargeContext.reason("admission.direct-deliveries"));
        }
        for (ManagedDocumentSnapshot document
                : currentSnapshot.managedDocuments()) {
            charge("processor", "managedDocumentOpened", 1L,
                    documentContext(document, null,
                            "admission.document."
                                    + document.documentId().value()));
        }
        for (ManagedOccurrenceBinding binding
                : currentSnapshot.occurrences()) {
            ManagedDocumentSnapshot source = currentSnapshot
                    .managedDocument(binding.sourceDocumentId());
            charge("processor", "managedOccurrenceBindingVerified", 1L,
                    documentContext(source, null,
                            "admission.binding."
                                    + binding.occurrenceIdentity()));
            if (binding.active()) {
                charge("processor", "processEmbeddedEdgeExamined", 1L,
                        documentContext(source, null,
                                "admission.edge."
                                        + binding.occurrenceIdentity()));
            }
        }
        for (ComponentSnapshot component : currentSnapshot.components()) {
            for (DocumentId memberId
                    : component.orderedMemberDocumentIds()) {
                charge("processor", "componentMemberPartitioned", 1L,
                        documentContext(
                                currentSnapshot.managedDocument(memberId),
                                null,
                                "admission.component-member"));
            }
        }
    }

    private void drainCausalWork() {
        while (!workQueue.isEmpty() || !eventQueue.isEmpty()) {
            if (!workQueue.isEmpty()) {
                ClosureWorkOccurrence work = workQueue.dequeue();
                charge("processor", "closureWorkOccurrenceDequeued", 1L,
                        workContext(work,
                                "work." + work.ordinal() + ".dequeue"));
                PendingWork pending = pendingByIdentity.remove(
                        work.workIdentity());
                if (pending == null) {
                    throw new IllegalStateException(
                            "Accepted closure work has no exact payload");
                }
                executeOne(pending);
            } else {
                drainOneEvent();
            }
        }
    }

    private void executeOne(PendingWork pending) {
        ClosureWorkOccurrence work = pending.work;
        ManagedDocumentSnapshot target = currentSnapshot.managedDocument(
                work.targetDocumentId());
        if (target == null) {
            throw new IllegalStateException(
                    "Accepted work target left the affected closure");
        }
        DocumentStepInput step = new DocumentStepInput(
                nextStepOrdinal++,
                work,
                target,
                pending.exactPayload,
                pending.occurrenceEvent,
                pending.processorPatch,
                TentativeResolutionContext.from(
                        input, currentSnapshot, work.targetDocumentId()));
        recorder.step(step);
        ActiveFrame frame = new ActiveFrame(
                work,
                target.componentGeneration());
        activeFrames.addLast(frame);
        LocalDocumentStepResult result;
        long stepStarted = recorder.beginManagedDocumentStep();
        try {
            result = stepProcessor.process(step);
        } finally {
            try {
                ActiveFrame removed = activeFrames.removeLast();
                if (removed != frame) {
                    throw new IllegalStateException(
                            "Document-step continuation frame order changed");
                }
            } finally {
                recorder.endManagedDocumentStep(stepStarted);
            }
        }
        requireExactManagedReferences(frame);
        if (!result.documentId().equals(work.targetDocumentId())
                || !result.workOccurrenceIdentity().equals(
                        work.workIdentity())
                || !result.beforeBlueId().equals(target.blueId())) {
            throw new IllegalStateException(
                    "Local document-step result lost its owning evidence");
        }
        if (frame.applicationEventCount
                != result.emittedEvents().size()) {
            throw new IllegalStateException(
                    "Application events bypassed the closure continuation");
        }

        Node staged = latestBodies.get(work.targetDocumentId());
        if (!sameNode(staged, result.resultingBody())) {
            Map<DocumentId, Node> beforeWorkBoundary =
                    cloneBodies(latestBodies);
            latestBodies.put(
                    work.targetDocumentId(), result.resultingBody());
            List<FinalizationUpdate> generatedUpdates = finalizeTentative(
                    TentativeFinalization.Boundary.work(work.ordinal()),
                    work,
                    beforeWorkBoundary,
                    Collections.<String>emptySet());
            if (!generatedUpdates.isEmpty()) {
                throw new ClosureCapabilityGapException(
                        "UNATTRIBUTED_FINALIZATION_UPDATE_TRANSITION",
                        "A finalization-induced update requires its exact "
                                + "source patch transition");
            }
        }
        if (activeFrames.isEmpty() && !initializationBatchRunning) {
            runPendingInitializationBatch();
        }
        if (eventDeliveryBatchDepth == 0) {
            drainEventsCapturedBy(frame);
        }
        if (pending.checkpointCandidate != null) {
            List<CompletedCheckpointCandidate> candidates =
                    completedCheckpointCandidates.get(
                            work.targetDocumentId());
            if (candidates == null) {
                candidates =
                        new ArrayList<CompletedCheckpointCandidate>();
                completedCheckpointCandidates.put(
                        work.targetDocumentId(), candidates);
            }
            candidates.add(new CompletedCheckpointCandidate(
                    pending.checkpointCandidate,
                    pending.rawOccurrenceOrder.longValue()));
        }
    }

    @Override
    public void afterPatch(
            String scopePath,
            Node currentDocument,
            FrozenJsonPatch patch,
            List<DocumentUpdateOccurrence> updates) {
        requireRoot(scopePath);
        ActiveFrame frame = activeFrame();
        frame.patchCount++;
        chargeManagedRevisionReceiptPatch(frame.work);
        long transitionOrdinal = nextTransitionOrdinal++;
        String transitionIdentity = transitionIdentity(
                frame.work,
                frame.currentPrePatchBlueId,
                transitionOrdinal);
        Node admitted = Objects.requireNonNull(
                currentDocument, "currentDocument");
        Set<String> prospectiveActivations =
                prospectiveActivations(
                        frame.work.targetDocumentId(),
                        patch,
                        admitted);
        Map<DocumentId, Node> beforeWorkBoundary =
                cloneBodies(latestBodies);
        latestBodies.put(frame.work.targetDocumentId(), admitted.clone());
        List<FinalizationUpdate> generatedUpdates = finalizeTentative(
                TentativeFinalization.Boundary.work(
                        frame.work.ordinal()),
                frame.work,
                beforeWorkBoundary,
                prospectiveActivations);
        retainActivatedInitializationRequests(
                prospectiveActivations, frame.work);
        if (shouldActivateManagedRevision(frame.work)) {
            ArrayList<FinalizationUpdate> combined =
                    new ArrayList<FinalizationUpdate>(generatedUpdates);
            combined.addAll(activateManagedRevision(
                    frame.work, admitted));
            generatedUpdates = Collections.unmodifiableList(combined);
        }
        synchronizeManagedReferences(
                frame.work.targetDocumentId(), admitted);
        frame.currentPrePatchBlueId = currentSnapshot.managedDocument(
                frame.work.targetDocumentId()).blueId();

        long updateOrdinal = 0L;
        for (DocumentUpdateOccurrence update
                : Objects.requireNonNull(updates, "updates")) {
            List<ManagedDocumentStepRoute> localRoutes =
                    stepProcessor.classifyDocumentUpdateRoutes(
                            latestBodies.get(frame.work.targetDocumentId()),
                            update);
            drainDocumentUpdateRoutes(
                    frame.work.targetDocumentId(),
                    localRoutes,
                    updateOrdinal,
                    transitionIdentity,
                    transitionOrdinal);
            updateOrdinal++;
        }
        for (FinalizationUpdate update : generatedUpdates) {
            List<ManagedDocumentStepRoute> routes =
                    stepProcessor.classifyFinalizationDocumentUpdateRoutes(
                            latestBodies.get(update.sourceDocumentId),
                            update.sourcePath,
                            update.before,
                            update.after);
            drainDocumentUpdateRoutes(
                    update.sourceDocumentId,
                    routes,
                    updateOrdinal,
                    transitionIdentity,
                    transitionOrdinal);
            updateOrdinal++;
        }
    }

    private void retainActivatedInitializationRequests(
            Set<String> prospectiveActivations,
            ClosureWorkOccurrence owner) {
        Set<String> activated = Objects.requireNonNull(
                prospectiveActivations, "prospectiveActivations");
        if (activated.isEmpty()) {
            return;
        }
        String causeIdentity = Objects.requireNonNull(
                owner, "owner").workIdentity();
        for (ManagedOccurrenceBinding binding : currentBindings) {
            if (!binding.active()
                    || !activated.contains(binding.occurrenceIdentity())) {
                continue;
            }
            DocumentId target = binding.targetDocumentId();
            if (!initializedDocuments.contains(target)) {
                pendingInitializationCauses.putIfAbsent(
                        target, causeIdentity);
            }
        }
    }

    private void runPendingInitializationBatch() {
        if (pendingInitializationCauses.isEmpty()) {
            return;
        }
        if (initializationBatchRunning) {
            throw new IllegalStateException(
                    "Initialization batch re-entered its own work boundary");
        }
        initializationBatchRunning = true;
        try {
            while (true) {
                List<DocumentId> component =
                        nextPendingInitializationComponent();
                if (component.isEmpty()) {
                    if (eventQueue.isEmpty()) {
                        break;
                    }
                    drainOneEvent();
                    continue;
                }
                initializationBatchLastWorkOrdinal = -1L;
                runPendingInitializationComponent(component);
            }
        } finally {
            initializationBatchRunning = false;
            initializationBatchLastWorkOrdinal = -1L;
        }
    }

    private void runPendingInitializationComponent(
            List<DocumentId> initialMembers) {
        LinkedHashSet<DocumentId> batchMembers =
                new LinkedHashSet<DocumentId>(
                        Objects.requireNonNull(
                                initialMembers, "initialMembers"));
        LinkedHashMap<DocumentId, FrozenInitialization> frozen =
                new LinkedHashMap<DocumentId, FrozenInitialization>();
        while (true) {
            List<DocumentId> selected =
                    pendingMembersInCurrentInitializationComponent(
                            batchMembers);
            if (selected.isEmpty()) {
                if (eventQueue.isEmpty()) {
                    break;
                }
                drainOneEvent();
                continue;
            }
            batchMembers.addAll(selected);
            for (DocumentId documentId : selected) {
                if (!initializationRequired(documentId)) {
                    pendingInitializationCauses.remove(documentId);
                    continue;
                }
                ManagedDocumentSnapshot document = currentSnapshot
                        .managedDocument(documentId);
                if (document == null || document.initialized()
                        || initializedDocuments.contains(documentId)) {
                    pendingInitializationCauses.remove(documentId);
                    continue;
                }
                frozen.putIfAbsent(
                        documentId,
                        new FrozenInitialization(
                                document.blueId(),
                                document.document()));
            }
            for (DocumentId documentId : selected) {
                FrozenInitialization initial = frozen.get(documentId);
                String causeIdentity = pendingInitializationCauses
                        .remove(documentId);
                if (initial == null || causeIdentity == null
                        || !initializationRequired(documentId)) {
                    continue;
                }
                executeInitialization(
                        documentId, initial, causeIdentity);
            }
            while (!eventQueue.isEmpty()) {
                drainOneEvent();
            }
        }
        installInitializationMarkers(frozen);
    }

    private List<DocumentId>
            pendingMembersInCurrentInitializationComponent(
                    Set<DocumentId> batchMembers) {
        pendingInitializationCauses.keySet().removeIf(documentId ->
                initializedDocuments.contains(documentId)
                        || !initializationRequired(documentId));
        if (pendingInitializationCauses.isEmpty()) {
            return Collections.emptyList();
        }
        ManagedDocumentGraph graph = ManagedDocumentGraph.fromBindings(
                inputGraph.documentIds(), currentBindings);
        ArrayList<DocumentId> selected = new ArrayList<DocumentId>();
        for (List<DocumentId> component
                : new SccPartitioner().partition(graph)) {
            if (!intersects(component, batchMembers)) {
                continue;
            }
            for (DocumentId documentId : component) {
                if (pendingInitializationCauses.containsKey(documentId)) {
                    selected.add(documentId);
                }
            }
        }
        Collections.sort(selected);
        return Collections.unmodifiableList(selected);
    }

    private static boolean intersects(
            List<DocumentId> component,
            Set<DocumentId> members) {
        for (DocumentId documentId : component) {
            if (members.contains(documentId)) {
                return true;
            }
        }
        return false;
    }

    private List<DocumentId> nextPendingInitializationComponent() {
        pendingInitializationCauses.keySet().removeIf(documentId ->
                initializedDocuments.contains(documentId)
                        || !initializationRequired(documentId));
        if (pendingInitializationCauses.isEmpty()) {
            return Collections.emptyList();
        }
        ManagedDocumentGraph graph = ManagedDocumentGraph.fromBindings(
                inputGraph.documentIds(), currentBindings);
        for (List<DocumentId> component
                : new SccPartitioner().partition(graph)) {
            ArrayList<DocumentId> selected = new ArrayList<DocumentId>();
            for (DocumentId documentId : component) {
                if (pendingInitializationCauses.containsKey(documentId)) {
                    selected.add(documentId);
                }
            }
            if (!selected.isEmpty()) {
                return Collections.unmodifiableList(selected);
            }
        }
        throw new IllegalStateException(
                "Pending initialization target left the affected closure");
    }

    private boolean initializationRequired(DocumentId documentId) {
        if (initializedDocuments.contains(documentId)) {
            return false;
        }
        for (ManagedOccurrenceBinding binding : currentBindings) {
            if (binding.active()
                    && binding.targetDocumentId().equals(documentId)) {
                return true;
            }
        }
        return false;
    }

    private void executeInitialization(
            DocumentId documentId,
            FrozenInitialization initial,
            String causeIdentity) {
        Node lifecycleEvent = lifecycleEvent(initial.blueId);
        List<ManagedDocumentStepRoute> lifecycle = stepProcessor
                .classifyLifecycleRoutes(
                        initial.document, lifecycleEvent);
        String initializationChannel = lifecycle.isEmpty()
                ? "lifecycle" : "initialization";
        PendingWork initialization = pendingWork(
                WorkKind.INITIALIZATION,
                documentId,
                initializationChannel,
                causeIdentity,
                lifecycleEvent,
                null,
                null);
        executeImmediate(
                initialization,
                "work." + initialization.work.ordinal()
                        + ".initialization-enqueue");
        for (ManagedDocumentStepRoute route : lifecycle) {
            PendingWork work = causedWork(
                    WorkKind.LIFECYCLE,
                    documentId,
                    route,
                    null,
                    null,
                    causeIdentity,
                    null);
            executeImmediate(
                    work,
                    "work." + work.work.ordinal()
                            + ".lifecycle-enqueue");
        }
    }

    private PendingWork pendingWork(
            WorkKind kind,
            DocumentId targetDocumentId,
            String channelKey,
            String sourceOccurrenceIdentity,
            Node exactPayload,
            Node occurrenceEvent,
            FrozenJsonPatch processorPatch) {
        long ordinal = nextWorkOrdinal();
        ManagedScopeKey scope = ManagedScopeKey.root(targetDocumentId);
        String scopeIdentity = IDENTITIES.managedScopeKeyIdentity(scope);
        ClosureWorkOccurrence work = new ClosureWorkOccurrence(
                ordinal,
                kind,
                targetDocumentId,
                channelKey,
                null,
                null,
                scopeIdentity,
                sourceOccurrenceIdentity,
                IDENTITIES.workOccurrenceIdentity(
                        input.invocationIdentity(),
                        ordinal,
                        kind,
                        scopeIdentity,
                        sourceOccurrenceIdentity));
        return new PendingWork(
                work,
                exactPayload,
                occurrenceEvent,
                processorPatch,
                null,
                null);
    }

    private void executeImmediate(PendingWork pending, String reason) {
        accept(pending, reason);
        ClosureWorkQueue immediate = new ClosureWorkQueue();
        immediate.enqueue(pending.work);
        ClosureWorkOccurrence work = immediate.dequeue();
        charge("processor", "closureWorkOccurrenceDequeued", 1L,
                workContext(work,
                        "work." + work.ordinal() + ".dequeue"));
        PendingWork exact = pendingByIdentity.remove(work.workIdentity());
        if (exact == null) {
            throw new IllegalStateException(
                    "Accepted initialization work has no exact payload");
        }
        executeOne(exact);
    }

    private void installInitializationMarkers(
            Map<DocumentId, FrozenInitialization> frozen) {
        if (frozen.isEmpty()) {
            return;
        }
        ArrayList<DocumentId> documentIds = new ArrayList<DocumentId>(
                frozen.keySet());
        Collections.sort(documentIds);
        LinkedHashMap<DocumentId, PreparedMarker> prepared =
                new LinkedHashMap<DocumentId, PreparedMarker>();
        for (DocumentId documentId : documentIds) {
            if (!initializationRequired(documentId)) {
                continue;
            }
            Node body = latestBodies.get(documentId).clone();
            Node contracts = body.getContracts();
            if (contracts == null) {
                contracts = new Node();
                body.contracts(contracts);
            }
            if (contracts.getProperties() != null
                    && contracts.getProperties().containsKey(
                            ProcessorContractConstants.KEY_INITIALIZED)) {
                throw new IllegalStateException(
                        "Initialization marker appeared during its own batch");
            }
            Node marker = new Node()
                    .type(new Node().blueId(
                            RuntimeBlueIds.PROCESSING_INITIALIZED_MARKER))
                    .properties(
                            ProcessorContractConstants.KEY_DOCUMENT,
                            new Node().blueId(
                                    frozen.get(documentId).blueId));
            prepared.put(
                    documentId,
                    new PreparedMarker(
                            body,
                            marker,
                            documentContext(
                                    currentSnapshot.managedDocument(
                                            documentId),
                                    null,
                                    "initialization-batch.marker."
                                            + documentId.value())));
        }
        if (prepared.isEmpty()) {
            return;
        }
        LinkedHashMap<DocumentId, Node> markedBodies =
                new LinkedHashMap<DocumentId, Node>();
        for (Map.Entry<DocumentId, PreparedMarker> entry
                : prepared.entrySet()) {
            PreparedMarker marker = entry.getValue();
            charge("processor", "processorMarkerWritten", 1L,
                    marker.attribution);
            markedBodies.put(
                    entry.getKey(),
                    stepProcessor.writeDetachedProcessorState(
                            marker.inputBody,
                            ProcessorPointerConstants.RELATIVE_INITIALIZED,
                            marker.markerValue,
                            marker.attribution));
        }
        latestBodies.putAll(markedBodies);
        initializedDocuments.addAll(markedBodies.keySet());
        if (initializationBatchLastWorkOrdinal < 0L) {
            throw new IllegalStateException(
                    "Initialization marker batch has no accepted work owner");
        }
        finalizeTentative(
                TentativeFinalization.Boundary.initializationBatch(
                        initializationBatchLastWorkOrdinal),
                null,
                null,
                Collections.<String>emptySet());
    }

    private static Node lifecycleEvent(String preInitializationBlueId) {
        return new Node()
                .type(new Node().blueId(
                        RuntimeBlueIds.DOCUMENT_PROCESSING_INITIATED))
                .properties(
                        ProcessorContractConstants.KEY_DOCUMENT,
                        new Node().blueId(preInitializationBlueId));
    }

    private void chargeManagedRevisionReceiptPatch(
            ClosureWorkOccurrence work) {
        if (!(input.cause() instanceof ManagedRevisionCause)
                || managedRevisionReceiptReconciled
                || work.kind()
                        != WorkKind.CONTAINING_REFERENCE_UPDATE) {
            return;
        }
        ManagedRevisionCause revision =
                (ManagedRevisionCause) input.cause();
        if (!work.sourceOccurrenceIdentity().equals(
                revision.causeIdentity())) {
            return;
        }
        charge(
                "processor",
                "containingReferenceUpdated",
                1L,
                managedRevisionWorkContext(
                        work,
                        work.targetDocumentId(),
                        true,
                        "managed-revision.receipt-reference"));
        charge(
                "processor",
                "managedOccurrenceBindingVerified",
                1L,
                managedRevisionWorkContext(
                        work,
                        work.targetDocumentId(),
                        false,
                        "managed-revision.receipt-binding"));
        managedRevisionReceiptReconciled = true;
    }

    private boolean shouldActivateManagedRevision(
            ClosureWorkOccurrence work) {
        if (!(input.cause() instanceof ManagedRevisionCause)
                || managedRevisionActivationCompleted) {
            return false;
        }
        ManagedRevisionCause revision =
                (ManagedRevisionCause) input.cause();
        return work.kind() == WorkKind.CONTAINING_REFERENCE_UPDATE
                && work.sourceOccurrenceIdentity().equals(
                        revision.causeIdentity())
                && revision.toEpoch() == currentSnapshot.managedDocument(
                        revision.childDocumentId()).epoch();
    }

    private List<FinalizationUpdate> activateManagedRevision(
            ClosureWorkOccurrence work,
            Node runtimeDocument) {
        ManagedRevisionCause revision =
                (ManagedRevisionCause) input.cause();
        ManagedOccurrenceBinding target = managedRevisionTarget(revision);
        ManagedDocumentSnapshot child = currentSnapshot.managedDocument(
                revision.childDocumentId());
        Node authoritativeReference = new Node().blueId(child.blueId());
        Map<DocumentId, Node> beforeActivation = cloneBodies(latestBodies);
        charge(
                "processor",
                "containingReferenceUpdated",
                1L,
                managedRevisionWorkContext(
                        work,
                        target.sourceDocumentId(),
                        true,
                        "managed-revision.authoritative-reconciliation"));
        NodePathEditor.put(
                runtimeDocument,
                target.sourcePath(),
                authoritativeReference.clone());
        latestBodies.put(
                target.sourceDocumentId(), runtimeDocument.clone());
        activateManagedRevision = true;
        try {
            return finalizeTentative(
                    TentativeFinalization.Boundary.work(work.ordinal()),
                    work,
                    beforeActivation,
                    Collections.<String>emptySet());
        } finally {
            activateManagedRevision = false;
            managedRevisionActivationCompleted = true;
        }
    }

    @Override
    public void onApplicationEvent(
            String scopePath,
            String originContractKey,
            Node event,
            String eventBlueId) {
        requireRoot(scopePath);
        ActiveFrame frame = activeFrame();
        frame.applicationEventCount++;
        Node exactEvent = Objects.requireNonNull(event, "event").clone();
        long eventOrdinal = nextEventOrdinal++;
        String occurrenceIdentity = IDENTITIES.eventOccurrenceIdentity(
                input.invocationIdentity(), eventOrdinal, eventBlueId);
        ManagedDocumentSnapshot emitter = currentSnapshot.managedDocument(
                frame.work.targetDocumentId());
        if (emitter.publicRoot()) {
            publicEvents.add(new PublicEventOccurrence(
                    publicEvents.size(),
                    eventOrdinal,
                    emitter.documentId(),
                    occurrenceIdentity,
                    eventBlueId,
                    exactEvent));
            charge("processor", "rootEventRecorded", 1L,
                    frame.context("event." + eventOrdinal
                            + ".public-record"));
        }

        ArrayList<RouteTarget> routes = new ArrayList<RouteTarget>();
        for (ManagedDocumentStepRoute route
                : stepProcessor.classifyTriggeredEventRoutes(
                        emitter.document(), exactEvent)) {
            routes.add(new RouteTarget(
                    emitter.documentId(), route));
        }
        for (ManagedOccurrenceBinding binding
                : activeContainingOccurrences(emitter.documentId())) {
            ManagedDocumentSnapshot containing = currentSnapshot
                    .managedDocument(binding.sourceDocumentId());
            for (ManagedDocumentStepRoute route
                    : stepProcessor.classifyEmbeddedEventRoutes(
                            containing.document(),
                            binding.sourcePath(),
                            exactEvent,
                            eventBlueId)) {
                routes.add(new RouteTarget(
                        containing.documentId(), route));
            }
        }
        eventQueue.addLast(new EmittedOccurrence(
                eventOrdinal,
                eventBlueId,
                occurrenceIdentity,
                exactEvent,
                routes));
        charge("processor", "internalEventEnqueued", 1L,
                frame.context("event." + eventOrdinal + ".enqueue"));
    }

    @Override
    public void onTerminationRequested(
            String scopePath,
            String cause,
            String reason) {
        requireRoot(scopePath);
        throw new ClosureCapabilityGapException(
                "LIFECYCLE_TERMINATION_REQUIRED",
                "Termination requires the lifecycle and marker batch lane");
    }

    private void drainEventsCapturedBy(ActiveFrame completedFrame) {
        while (!eventQueue.isEmpty()
                && eventQueue.peekFirst().capturedBy == completedFrame) {
            drainOneEvent();
        }
    }

    private void drainOneEvent() {
        EmittedOccurrence occurrence = eventQueue.removeFirst();
        charge("processor", "internalEventDequeued", 1L,
                GasChargeContext.reason(
                        "event." + occurrence.ordinal + ".dequeue"));
        long deliveryOrdinal = 0L;
        ClosureWorkQueue immediate = new ClosureWorkQueue();
        for (RouteTarget route : occurrence.routes) {
            WorkKind kind = WorkKind.valueOf(
                    route.route.workKind().name());
            PendingWork pending = causedWork(
                    kind,
                    route.targetDocumentId,
                    route.route,
                    occurrence.eventBlueId,
                    Long.valueOf(occurrence.ordinal),
                    occurrence.occurrenceIdentity,
                    null);
            accept(pending,
                    "event." + occurrence.ordinal + ".delivery."
                            + deliveryOrdinal + ".enqueue");
            immediate.enqueue(pending.work);
            deliveryOrdinal++;
        }
        eventDeliveryBatchDepth++;
        try {
            while (!immediate.isEmpty()) {
                ClosureWorkOccurrence work = immediate.dequeue();
                charge("processor", "closureWorkOccurrenceDequeued", 1L,
                        workContext(work,
                                "work." + work.ordinal() + ".dequeue"));
                PendingWork pending = pendingByIdentity.remove(
                        work.workIdentity());
                if (pending == null) {
                    throw new IllegalStateException(
                            "Accepted event delivery has no exact payload");
                }
                executeOne(pending);
            }
        } finally {
            eventDeliveryBatchDepth--;
        }
    }

    private PendingWork causedWork(
            WorkKind kind,
            DocumentId targetDocumentId,
            ManagedDocumentStepRoute route,
            String eventBlueId,
            Long occurrenceOrdinal,
            String sourceOccurrenceIdentity,
            FrozenJsonPatch processorPatch) {
        long ordinal = nextWorkOrdinal();
        ManagedScopeKey scope = ManagedScopeKey.root(targetDocumentId);
        String scopeIdentity = IDENTITIES.managedScopeKeyIdentity(scope);
        String workIdentity = IDENTITIES.workOccurrenceIdentity(
                input.invocationIdentity(),
                ordinal,
                kind,
                scopeIdentity,
                sourceOccurrenceIdentity);
        ClosureWorkOccurrence work = new ClosureWorkOccurrence(
                ordinal,
                kind,
                targetDocumentId,
                route.channelKey(),
                eventBlueId,
                occurrenceOrdinal,
                scopeIdentity,
                sourceOccurrenceIdentity,
                workIdentity);
        return new PendingWork(
                work,
                route.exactPayload(),
                route.occurrenceEvent(),
                processorPatch,
                null,
                null);
    }

    private long nextWorkOrdinal() {
        long observed = Math.addExact(nextWorkOrdinal, 1L);
        requireWorkOccurrenceCount(observed);
        return nextWorkOrdinal++;
    }

    private void requireWorkOccurrenceCount(long observed) {
        long limit = ClosureAdmissionPortableLimits.limit(
                input, "closureWorkOccurrencesPerInvocation");
        if (observed > limit) {
            throw ClosureAdmissionPortableLimits.exceeded(
                    "closureWorkOccurrencesPerInvocation",
                    observed,
                    limit);
        }
    }

    private void accept(PendingWork pending, String reason) {
        recorder.accepted(pending.work);
        if (pendingByIdentity.put(
                pending.work.workIdentity(), pending) != null) {
            throw new IllegalStateException(
                    "Duplicate pending work payload identity");
        }
        charge("processor", "closureWorkOccurrenceEnqueued", 1L,
                workContext(pending.work, reason));
        if (initializationBatchRunning) {
            initializationBatchLastWorkOrdinal = Math.max(
                    initializationBatchLastWorkOrdinal,
                    pending.work.ordinal());
        }
    }

    private void enqueueAlreadyAccepted(
            ClosureWorkOccurrence work,
            String reason) {
        workQueue.enqueue(work);
        charge("processor", "closureWorkOccurrenceEnqueued", 1L,
                workContext(work, reason));
    }

    private List<FinalizationUpdate> finalizeTentative(
            TentativeFinalization.Boundary boundary,
            ClosureWorkOccurrence owner,
            Map<DocumentId, Node> beforeWorkBoundary,
            Set<String> prospectiveActivations) {
        List<ManagedOccurrenceBinding> bindingsBeforeFinalization =
                currentBindings;
        List<ManagedOccurrenceBinding> reclassified =
                reclassifyKnownOccurrences(prospectiveActivations);
        ManagedDocumentGraph before = ManagedDocumentGraph.fromBindings(
                inputGraph.documentIds(), currentBindings);
        ManagedDocumentGraph after = ManagedDocumentGraph.fromBindings(
                inputGraph.documentIds(), reclassified);
        long activeOccurrenceChanges = ClosureGraphGenerationTransition
                .activeOccurrenceChangeCount(before, after);
        if (activeOccurrenceChanges > 0L) {
            if (graphChanges > ClosureValueSupport.MAX_SAFE_INTEGER
                    - activeOccurrenceChanges) {
                throw new IllegalArgumentException(
                        "Closure graph-change count exceeds the safe-integer range");
            }
            graphChanges += activeOccurrenceChanges;
            long topologyLimit = ClosureAdmissionPortableLimits.limit(
                    input, "closureGraphChangesPerInvocation");
            if (graphChanges > topologyLimit) {
                throw ClosureAdmissionPortableLimits.exceeded(
                        "closureGraphChangesPerInvocation",
                        graphChanges,
                        topologyLimit);
            }
        }
        boolean topologyChanged = !before.adjacency().equals(
                after.adjacency());
        if (topologyChanged) {
            chargeTopologyChange(
                    before,
                    after,
                    Objects.requireNonNull(owner, "owner"));
        }
        graphGeneration = ClosureGraphGenerationTransition.assign(
                input.snapshot().graphGeneration(), inputGraph, after);
        Map<DocumentId, Long> generations =
                ComponentGenerationTransition.assign(
                        inputGraph,
                        inputComponentGenerations,
                        after);
        Map<DocumentId, Node> sourceBodies = cloneBodies(latestBodies);
        ComponentFinalizationResult finalized;
        long finalizationStarted =
                recorder.beginComponentFinalizationProof();
        try {
            finalized = finalizer.finalizeComponents(
                    new ComponentFinalizationInput(
                            inputGraph,
                            inputComponentGenerations,
                            latestBodies,
                            reclassified));
        } finally {
            recorder.endComponentFinalizationProof(finalizationStarted);
        }
        finalized = rebindInactiveProspectiveRows(finalized);
        final ClosureFinalizationGasCharger.CyclicFinalizationPlan
                cyclicPlan = ClosureFinalizationGasCharger.plan(
                        currentSnapshot.components(),
                        finalized,
                        cyclicCanonicalBytesLimit());
        requireFinalizationCapacity(cyclicPlan.size());
        ClosureFinalizationGasCharger.FinalizationFrame gasFrame;
        if (boundary.kind()
                == TentativeFinalization.Boundary.Kind
                        .CHECKPOINT_SETTLEMENT) {
            gasFrame = finalizationGas.beginCheckpointSettlement(
                    stepProcessor,
                    after,
                    generations,
                    0L,
                    recorder.finalizationCount(),
                    cyclicPlan.memberSets());
        } else if (boundary.kind()
                == TentativeFinalization.Boundary.Kind.WORK) {
            gasFrame = finalizationGas.beginFinalization(
                    stepProcessor, after, generations,
                    Objects.requireNonNull(owner, "owner"),
                    recorder.finalizationCount(),
                    cyclicPlan.memberSets());
        } else if (boundary.kind()
                == TentativeFinalization.Boundary.Kind
                        .INITIALIZATION_BATCH) {
            gasFrame = finalizationGas.beginInitializationBatch(
                    stepProcessor,
                    after,
                    generations,
                    recorder.finalizationCount(),
                    cyclicPlan.memberSets());
        } else {
            throw new IllegalStateException(
                    "Unknown tentative finalization boundary "
                            + boundary.kind());
        }
        finalizationGas.finishFinalization(
                gasFrame,
                sourceBodies,
                finalized,
                establishedBlueIds,
                existingBlueIds,
                new ClosureFinalizationGasCharger.ComponentCompletion() {
                    @Override
                    public void completed(
                            FinalizedComponentEvidence evidence) {
                        recordCyclicFinalization(
                                evidence,
                                boundary,
                                owner,
                                cyclicPlan.canonicalBytes(evidence));
                    }
                });
        chargeChangedAcyclicComponents(
                finalized, boundary, owner);
        currentBindings = new ArrayList<ManagedOccurrenceBinding>(
                finalized.finalizedGraph().bindings());
        latestBodies.clear();
        for (FinalizedDocumentEvidence document
                : finalized.documents().values()) {
            latestBodies.put(document.documentId(), document.document());
        }
        currentFinalization = finalized;
        currentSnapshot = snapshot(finalized);
        if (boundary.kind()
                == TentativeFinalization.Boundary.Kind.WORK) {
            Map<DocumentId, Node> beforeBodies = Objects.requireNonNull(
                    beforeWorkBoundary, "beforeWorkBoundary");
            for (Map.Entry<DocumentId, Node> entry
                    : latestBodies.entrySet()) {
                if (!sameNode(
                        beforeBodies.get(entry.getKey()),
                        entry.getValue())
                        && shouldAdvanceEpoch(
                                entry.getKey(),
                                Objects.requireNonNull(owner, "owner"),
                                bindingsBeforeFinalization,
                                beforeBodies,
                                sourceBodies,
                                latestBodies)) {
                    ManagedDocumentSnapshot inputDocument = input.snapshot()
                            .managedDocument(entry.getKey());
                    if (inputDocument != null
                            && inputDocument.initialized()) {
                        epochAdvanceDocuments.add(entry.getKey());
                    }
                }
            }
        }
        if (boundary.kind()
                != TentativeFinalization.Boundary.Kind.WORK) {
            return Collections.<FinalizationUpdate>emptyList();
        }
        List<FinalizationUpdate> updates =
                finalizationUpdates(sourceBodies);
        chargeManagedRevisionAncestorRewrites(
                Objects.requireNonNull(owner, "owner"), updates);
        return updates;
    }

    private void chargeTopologyChange(
            ManagedDocumentGraph before,
            ManagedDocumentGraph after,
            ClosureWorkOccurrence owner) {
        Set<String> priorActive = new LinkedHashSet<String>();
        for (ManagedOccurrenceBinding binding : before.activeBindings()) {
            priorActive.add(binding.occurrenceIdentity());
        }
        long activatedEdges = 0L;
        for (ManagedOccurrenceBinding binding : after.activeBindings()) {
            if (!priorActive.contains(binding.occurrenceIdentity())) {
                activatedEdges++;
            }
        }
        GasChargeContext context = managedRevisionWorkContext(
                owner,
                owner.targetDocumentId(),
                false,
                input.cause() instanceof ManagedRevisionCause
                        ? "managed-revision.topology-change"
                        : "work." + owner.ordinal()
                                + ".topology-change");
        if (activatedEdges > 0L) {
            charge(
                    "processor",
                    "processEmbeddedEdgeExamined",
                    activatedEdges,
                    context);
            charge(
                    "processor",
                    "managedOccurrenceBindingVerified",
                    activatedEdges,
                    context);
        }
        if (ClosureGraphGenerationTransition.componentPartitionChanged(
                before, after)) {
            charge(
                    "processor", "componentPartitionChanged", 1L, context);
        }
        charge(
                "processor",
                "componentMemberPartitioned",
                after.documentIds().size(),
                context);
        if (!after.activeBindings().isEmpty()) {
            charge(
                    "processor",
                    "componentEdgePartitioned",
                    after.activeBindings().size(),
                    context);
        }
    }

    private void chargeManagedRevisionAncestorRewrites(
            ClosureWorkOccurrence owner,
            List<FinalizationUpdate> updates) {
        if (!(input.cause() instanceof ManagedRevisionCause)
                || activateManagedRevision) {
            return;
        }
        for (FinalizationUpdate update : updates) {
            if (update.sourceDocumentId.equals(
                    owner.targetDocumentId())) {
                continue;
            }
            charge(
                    "processor",
                    "containingReferenceUpdated",
                    1L,
                    managedRevisionWorkContext(
                            owner,
                            update.sourceDocumentId,
                            false,
                            "managed-revision.ancestor-reference."
                                    + update.sourcePath));
        }
    }

    private boolean shouldAdvanceEpoch(
            DocumentId documentId,
            ClosureWorkOccurrence owner,
            List<ManagedOccurrenceBinding> bindingsBeforeFinalization,
            Map<DocumentId, Node> beforeWorkBoundary,
            Map<DocumentId, Node> sourceBodies,
            Map<DocumentId, Node> finalizedBodies) {
        boolean changedLocally = !sameNode(
                beforeWorkBoundary.get(documentId),
                sourceBodies.get(documentId));
        if (changedLocally) {
            return true;
        }

        /*
         * Finalizer-owned changes normally advance the changed document's
         * epoch.  The sole work-boundary exception is the authoritative
         * target of an inactive historical row while the current source work
         * reconciles that exact row.  Its containing/reference churn is
         * representation-only evidence of the historical cursor advancing;
         * the authoritative target head itself did not advance.
         */
        boolean reconcilesHistoricalRow = false;
        for (ManagedOccurrenceBinding binding
                : bindingsBeforeFinalization) {
            if (binding.active()
                    || binding.pendingHistoricalEpoch() == null
                    || !binding.sourceDocumentId().equals(
                            owner.targetDocumentId())
                    || !binding.targetDocumentId().equals(documentId)) {
                continue;
            }
            Node before = NodePathEditor.getOrNull(
                    beforeWorkBoundary.get(binding.sourceDocumentId()),
                    binding.sourcePath());
            Node staged = NodePathEditor.getOrNull(
                    sourceBodies.get(binding.sourceDocumentId()),
                    binding.sourcePath());
            boolean selectedActivation = activateManagedRevision
                    && input.cause() instanceof ManagedRevisionCause
                    && binding.occurrenceIdentity().equals(
                            ((ManagedRevisionCause) input.cause())
                                    .targetOccurrenceIdentity());
            if (!sameNode(before, staged) || selectedActivation) {
                reconcilesHistoricalRow = true;
                break;
            }
        }
        if (!reconcilesHistoricalRow) {
            return true;
        }

        Node explained = sourceBodies.get(documentId).clone();
        boolean reencodedCurrentSource = false;
        for (ManagedOccurrenceBinding binding
                : bindingsBeforeFinalization) {
            if (!binding.active()
                    || !binding.sourceDocumentId().equals(documentId)
                    || !binding.targetDocumentId().equals(
                            owner.targetDocumentId())) {
                continue;
            }
            Node before = NodePathEditor.getOrNull(
                    sourceBodies.get(documentId), binding.sourcePath());
            Node after = NodePathEditor.getOrNull(
                    finalizedBodies.get(documentId), binding.sourcePath());
            if (sameNode(before, after)) {
                continue;
            }
            if (after == null) {
                return true;
            }
            NodePathEditor.put(
                    explained, binding.sourcePath(), after.clone());
            reencodedCurrentSource = true;
        }
        return !reencodedCurrentSource
                || !sameNode(explained, finalizedBodies.get(documentId));
    }

    private void drainDocumentUpdateRoutes(
            DocumentId targetDocumentId,
            List<ManagedDocumentStepRoute> routes,
            long updateOrdinal,
            String transitionIdentity,
            long transitionOrdinal) {
        ClosureWorkQueue immediate = new ClosureWorkQueue();
        for (ManagedDocumentStepRoute route : routes) {
            PendingWork caused = causedWork(
                    WorkKind.DOCUMENT_UPDATE,
                    targetDocumentId,
                    route,
                    null,
                    Long.valueOf(updateOrdinal),
                    transitionIdentity,
                    null);
            accept(caused,
                    "transition." + transitionOrdinal
                            + ".update." + updateOrdinal
                            + ".enqueue");
            immediate.enqueue(caused.work);
        }
        while (!immediate.isEmpty()) {
            ClosureWorkOccurrence work = immediate.dequeue();
            charge("processor", "closureWorkOccurrenceDequeued", 1L,
                    workContext(work,
                            "work." + work.ordinal() + ".dequeue"));
            PendingWork caused = pendingByIdentity.remove(
                    work.workIdentity());
            executeOne(caused);
        }
    }

    private List<FinalizationUpdate> finalizationUpdates(
            Map<DocumentId, Node> beforeFinalization) {
        ArrayList<ManagedOccurrenceBinding> bindings =
                new ArrayList<ManagedOccurrenceBinding>();
        for (ManagedOccurrenceBinding binding : currentBindings) {
            if (binding.active()) {
                bindings.add(binding);
            }
        }
        Collections.sort(bindings,
                new Comparator<ManagedOccurrenceBinding>() {
                    @Override
                    public int compare(
                            ManagedOccurrenceBinding left,
                            ManagedOccurrenceBinding right) {
                        int order = left.sourceDocumentId().compareTo(
                                right.sourceDocumentId());
                        return order != 0
                                ? order
                                : ClosureValueSupport.comparePortableText(
                                        left.sourcePath(),
                                        right.sourcePath());
                    }
                });
        ArrayList<FinalizationUpdate> result =
                new ArrayList<FinalizationUpdate>();
        for (ManagedOccurrenceBinding binding : bindings) {
            Node before = NodePathEditor.getOrNull(
                    beforeFinalization.get(binding.sourceDocumentId()),
                    binding.sourcePath());
            Node after = NodePathEditor.getOrNull(
                    latestBodies.get(binding.sourceDocumentId()),
                    binding.sourcePath());
            if (!sameNode(before, after)) {
                result.add(new FinalizationUpdate(
                        binding.sourceDocumentId(),
                        binding.sourcePath(),
                        before,
                        after));
            }
        }
        return Collections.unmodifiableList(result);
    }

    private List<ManagedOccurrenceBinding> reclassifyKnownOccurrences(
            Set<String> prospectiveActivations) {
        Set<String> eligible = Objects.requireNonNull(
                prospectiveActivations, "prospectiveActivations");
        ManagedRevisionCause revision = input.cause()
                instanceof ManagedRevisionCause
                ? (ManagedRevisionCause) input.cause()
                : null;
        ArrayList<ManagedOccurrenceBinding> result =
                new ArrayList<ManagedOccurrenceBinding>();
        for (ManagedOccurrenceBinding binding : currentBindings) {
            if (revision != null
                    && binding.occurrenceIdentity().equals(
                            revision.targetOccurrenceIdentity())) {
                result.add(reconcileManagedRevision(binding, revision));
                continue;
            }
            if (!binding.active()
                    && !eligible.contains(binding.occurrenceIdentity())) {
                result.add(binding);
                continue;
            }
            Node value = NodePathEditor.getOrNull(
                    latestBodies.get(binding.sourceDocumentId()),
                    binding.sourcePath());
            ManagedDocumentSnapshot target = currentSnapshot.managedDocument(
                    binding.targetDocumentId());
            if (value == null) {
                if (binding.active()) {
                    ManagedOccurrenceBinding successor =
                            retirementSuccessor(binding, target);
                    result.add(successor);
                    continue;
                }
                result.add(binding);
                continue;
            }
            boolean exactTarget = ManagedOccurrenceTargetVerifier
                    .establishesExactTarget(value, target);
            if (!exactTarget) {
                throw new ClosureCapabilityGapException(
                        "NEW_OCCURRENCE_ADMISSION_REQUIRED",
                        "A changed occurrence target requires affected-closure admission");
            }
            String reference = value.isReferenceOnly()
                    ? value.getBlueId()
                    : target.blueId();
            if (!reference.equals(target.blueId())
                    && !reference.equals(binding.expectedTargetBlueId())) {
                throw new ClosureCapabilityGapException(
                        "NEW_OCCURRENCE_ADMISSION_REQUIRED",
                        "A changed occurrence target requires affected-closure admission");
            }
            String expectedTarget = target.blueId();
            String bindingIdentity = IDENTITIES
                    .managedOccurrenceBindingIdentity(
                            binding.sourceDocumentId(),
                            binding.sourceAddress(),
                            binding.targetDocumentId(),
                            expectedTarget,
                            binding.bindingPolicyIdentity());
            result.add(new ManagedOccurrenceBinding(
                    binding.occurrenceIdentity(),
                    bindingIdentity,
                    binding.bindingPolicyIdentity(),
                    binding.sourceDocumentId(),
                    binding.sourceAddress(),
                    binding.targetDocumentId(),
                    expectedTarget,
                    true,
                    null));
        }
        Collections.sort(result);
        return result;
    }

    private ManagedOccurrenceBinding retirementSuccessor(
            ManagedOccurrenceBinding removed,
            ManagedDocumentSnapshot target) {
        long generation = removed.activationGeneration();
        if (generation == ClosureValueSupport.MAX_SAFE_INTEGER) {
            throw new IllegalArgumentException(
                    "Occurrence activation generation cannot overflow");
        }
        return ManagedOccurrenceBinding.derived(
                removed.bindingPolicyIdentity(),
                removed.sourceDocumentId(),
                ScopeAddress.embedded(
                        removed.sourcePath(), generation + 1L),
                removed.targetDocumentId(),
                target.blueId(),
                false,
                null);
    }

    private ComponentFinalizationResult rebindInactiveProspectiveRows(
            ComponentFinalizationResult finalized) {
        ArrayList<ManagedOccurrenceBinding> rebound =
                new ArrayList<ManagedOccurrenceBinding>();
        boolean changed = false;
        for (ManagedOccurrenceBinding binding
                : finalized.finalizedGraph().bindings()) {
            if (binding.active()
                    || binding.pendingHistoricalEpoch() != null) {
                rebound.add(binding);
                continue;
            }
            String targetBlueId = finalized.document(
                    binding.targetDocumentId()).blueId();
            ManagedOccurrenceBinding current =
                    ManagedOccurrenceBinding.derived(
                            binding.bindingPolicyIdentity(),
                            binding.sourceDocumentId(),
                            binding.sourceAddress(),
                            binding.targetDocumentId(),
                            targetBlueId,
                            false,
                            null);
            if (!current.occurrenceIdentity().equals(
                    binding.occurrenceIdentity())) {
                throw new IllegalStateException(
                        "Inactive occurrence lineage changed during rebinding");
            }
            rebound.add(current);
            changed |= !current.bindingIdentity().equals(
                    binding.bindingIdentity());
        }
        if (!changed) {
            return finalized;
        }
        ManagedDocumentGraph graph = ManagedDocumentGraph.fromBindings(
                finalized.finalizedGraph().documentIds(), rebound);
        return new ComponentFinalizationResult(
                graph,
                finalized.componentGenerations(),
                finalized.components(),
                finalized.documents());
    }

    private ManagedOccurrenceBinding reconcileManagedRevision(
            ManagedOccurrenceBinding binding,
            ManagedRevisionCause revision) {
        Node value = NodePathEditor.getOrNull(
                latestBodies.get(binding.sourceDocumentId()),
                binding.sourcePath());
        ManagedDocumentSnapshot child = currentSnapshot.managedDocument(
                revision.childDocumentId());
        String installedBlueId = activateManagedRevision
                ? child.blueId()
                : revision.afterBlueId();
        if (value == null
                || !value.isReferenceOnly()
                || !installedBlueId.equals(value.getBlueId())) {
            throw new IllegalStateException(
                    "Managed-revision patch did not install its exact "
                            + "historical successor reference");
        }
        boolean caughtUp = activateManagedRevision;
        if (caughtUp && revision.toEpoch() != child.epoch()) {
            throw new IllegalStateException(
                    "Managed-revision activation precedes the authoritative "
                            + "child head");
        }
        ManagedOccurrenceBinding reconciled =
                ManagedOccurrenceBinding.derived(
                binding.bindingPolicyIdentity(),
                binding.sourceDocumentId(),
                binding.sourceAddress(),
                binding.targetDocumentId(),
                installedBlueId,
                caughtUp,
                caughtUp ? null : Long.valueOf(revision.toEpoch()));
        if (!binding.occurrenceIdentity().equals(
                reconciled.occurrenceIdentity())) {
            throw new IllegalStateException(
                    "Managed-revision reconciliation changed occurrence "
                            + "lineage");
        }
        return reconciled;
    }

    private Set<String> prospectiveActivations(
            DocumentId sourceDocumentId,
            FrozenJsonPatch patch,
            Node resultingDocument) {
        ActiveFrame frame = activeFrame();
        LinkedHashSet<String> eligible = new LinkedHashSet<String>();
        LinkedHashMap<String, String> expectedByPath =
                new LinkedHashMap<String, String>();
        Node validationDocument = resultingDocument.clone();
        ArrayList<ManagedOccurrenceBinding> sourceBindings =
                new ArrayList<ManagedOccurrenceBinding>();
        for (ManagedOccurrenceBinding binding : currentBindings) {
            if (binding.sourceDocumentId().equals(sourceDocumentId)) {
                sourceBindings.add(binding);
            }
        }
        Collections.sort(sourceBindings,
                new Comparator<ManagedOccurrenceBinding>() {
                    @Override
                    public int compare(
                            ManagedOccurrenceBinding left,
                            ManagedOccurrenceBinding right) {
                        return ClosureValueSupport.comparePortableText(
                                left.sourcePath(), right.sourcePath());
                    }
                });
        ManagedRevisionCause revision = input.cause()
                instanceof ManagedRevisionCause
                ? (ManagedRevisionCause) input.cause()
                : null;
        for (ManagedOccurrenceBinding binding : sourceBindings) {
            Node value = NodePathEditor.getOrNull(
                    resultingDocument, binding.sourcePath());
            if (value == null) {
                continue;
            }
            boolean currentManaged = binding.active()
                    || binding.pendingHistoricalEpoch() != null;
            boolean mutationEligible = !binding.active()
                    && binding.pendingHistoricalEpoch() == null
                    && mutationTouches(
                            patch.getPath(), binding.sourcePath());
            if (!currentManaged && !mutationEligible) {
                continue;
            }
            boolean revisionTarget = revision != null
                    && binding.occurrenceIdentity().equals(
                            revision.targetOccurrenceIdentity());
            String validationBlueId = revisionTarget
                    ? revision.afterBlueId()
                    : binding.expectedTargetBlueId();
            if (expectedByPath.put(
                    binding.sourcePath(),
                    validationBlueId) != null) {
                throw new IllegalStateException(
                        "Managed Root has duplicate current occurrence paths");
            }
            if (binding.pendingHistoricalEpoch() != null
                    && mutationTouches(
                            patch.getPath(), binding.sourcePath())) {
                if (!revisionTarget) {
                    frame.requiredExactManagedBlueIds.add(
                            binding.expectedTargetBlueId());
                }
            }
            if (mutationEligible) {
                ManagedDocumentSnapshot target = currentSnapshot
                        .managedDocument(binding.targetDocumentId());
                if (target == null
                        || !binding.expectedTargetBlueId().equals(
                                target.blueId())
                        || !ManagedOccurrenceTargetVerifier
                                .establishesExactTarget(value, target)) {
                    throw new InvalidExecutionEvidenceException(
                            "Prospective managed occurrence did not install "
                                    + "its exact expected target at "
                                    + binding.sourcePath(),
                            ProcessorErrorCategory
                                    .ManagedOccurrenceBindingMissing);
                }
                /*
                 * The mutation runtime may hold the already-authenticated
                 * target as a materialized cursor.  Managed declaration
                 * validation is identity-based and intentionally keeps child
                 * documents opaque, so validate the equivalent authoritative
                 * reference representation after exact body equality has
                 * been established against the invocation snapshot.
                 */
                NodePathEditor.put(
                        validationDocument,
                        binding.sourcePath(),
                        new Node().blueId(binding.expectedTargetBlueId()));
                eligible.add(binding.occurrenceIdentity());
            }
        }
        stepProcessor.validateManagedEmbeddedPaths(
                validationDocument, expectedByPath);
        return Collections.unmodifiableSet(eligible);
    }

    private void requireExactManagedReferences(ActiveFrame frame) {
        ArrayList<String> required = new ArrayList<String>(
                frame.requiredExactManagedBlueIds);
        Collections.sort(required, new Comparator<String>() {
            @Override
            public int compare(String left, String right) {
                return ClosureValueSupport.comparePortableText(left, right);
            }
        });
        for (String blueId : required) {
            stepProcessor.requireExactManagedReference(blueId);
        }
    }

    private static boolean mutationTouches(
            String patchPath,
            String occurrencePath) {
        String mutation = Objects.requireNonNull(
                patchPath, "patchPath");
        String occurrence = Objects.requireNonNull(
                occurrencePath, "occurrencePath");
        return PointerUtils.descendantOrEqual(
                occurrence,
                mutation);
    }

    private AffectedClosureSnapshot snapshot(
            ComponentFinalizationResult finalized) {
        ArrayList<ManagedDocumentSnapshot> documents =
                new ArrayList<ManagedDocumentSnapshot>();
        for (ManagedDocumentSnapshot original
                : input.snapshot().managedDocuments()) {
            FinalizedDocumentEvidence document = finalized.document(
                    original.documentId());
            documents.add(new ManagedDocumentSnapshot(
                    original.documentId(),
                    document.blueId(),
                    document.document(),
                    initializedDocuments.contains(
                            original.documentId()),
                    original.terminated(),
                    original.publicRoot(),
                    original.epoch(),
                    document.componentGeneration()));
        }
        ArrayList<ComponentSnapshot> components =
                new ArrayList<ComponentSnapshot>();
        for (FinalizedComponentEvidence component
                : finalized.components()) {
            components.add(component.component());
        }
        String bindingSetIdentity = IDENTITIES
                .occurrenceBindingSetIdentity(currentBindings);
        AffectedClosureSnapshot provisional = new AffectedClosureSnapshot(
                PROVISIONAL_IDENTITY,
                graphGeneration,
                documents,
                currentBindings,
                bindingSetIdentity,
                components,
                input.snapshot().publicRootDocumentIds());
        String closureIdentity = IDENTITIES.affectedClosureIdentity(
                provisional);
        return new AffectedClosureSnapshot(
                closureIdentity,
                graphGeneration,
                documents,
                currentBindings,
                bindingSetIdentity,
                components,
                input.snapshot().publicRootDocumentIds());
    }

    private void recordCyclicFinalization(
            FinalizedComponentEvidence evidence,
            TentativeFinalization.Boundary boundary,
            ClosureWorkOccurrence owner,
            long canonicalBytes) {
        CyclicSetFinalization exact = Objects.requireNonNull(
                evidence, "evidence").cyclicFinalization();
        if (exact == null) {
            throw new IllegalArgumentException(
                    "Tentative receipt requires cyclic finalization evidence");
        }
        LinkedHashMap<DocumentId, String> members =
                new LinkedHashMap<DocumentId, String>();
        ComponentSnapshot component = evidence.component();
        for (int index = 0;
                index < component.orderedMemberDocumentIds().size();
                index++) {
            members.put(
                    component.orderedMemberDocumentIds().get(index),
                    component.orderedMemberBlueIds().get(index));
        }
        recorder.finalization(new TentativeFinalization(
                recorder.finalizationCount(),
                boundary,
                exact.masterBlueId(),
                members,
                canonicalBytes));
    }

    private void requireFinalizationCapacity(int additional) {
        long limit = ClosureAdmissionPortableLimits.limit(
                input, "closureTentativeFinalizationsPerInvocation");
        long observed = recorder.finalizationCount() + (long) additional;
        if (observed > limit) {
            throw ClosureAdmissionPortableLimits.exceeded(
                    "closureTentativeFinalizationsPerInvocation",
                    observed,
                    limit);
        }
    }

    private long cyclicCanonicalBytesLimit() {
        Long value = input.environment()
                .portableLimitPolicy()
                .limits()
                .get(CYCLIC_CANONICAL_BYTES_LIMIT);
        if (value == null) {
            throw new IllegalArgumentException(
                    "Portable policy omits "
                            + CYCLIC_CANONICAL_BYTES_LIMIT);
        }
        return value.longValue();
    }

    private void chargeChangedAcyclicComponents(
            ComponentFinalizationResult finalized,
            TentativeFinalization.Boundary boundary,
            ClosureWorkOccurrence owner) {
        for (FinalizedComponentEvidence component
                : finalized.components()) {
            if (component.component().kind() != ComponentKind.ACYCLIC) {
                continue;
            }
            DocumentId documentId = component.component()
                    .orderedMemberDocumentIds().get(0);
            FinalizedDocumentEvidence after = finalized.document(documentId);
            ManagedDocumentSnapshot before = currentSnapshot
                    .managedDocument(documentId);
            if (before.blueId().equals(after.blueId())) {
                continue;
            }
            boolean managedRevisionWork = input.cause()
                    instanceof ManagedRevisionCause
                    && boundary.kind()
                            == TentativeFinalization.Boundary.Kind.WORK;
            if (managedRevisionWork) {
                charge(
                        "processor",
                        "tentativeComponentFinalization",
                        1L,
                        managedRevisionWorkContext(
                                Objects.requireNonNull(owner, "owner"),
                                documentId,
                                false,
                                "managed-revision.acyclic-finalization"));
                if (documentId.equals(owner.targetDocumentId())) {
                    // The ordinary isolated patch runtime already established
                    // this exact acyclic Root identity on the shared meter.
                    continue;
                }
            }
            if (boundary.kind()
                    == TentativeFinalization.Boundary.Kind.WORK) {
                finalizationGas.chargeAcyclicChangedBody(
                        stepProcessor,
                        documentId,
                        after.document(),
                        component.component().componentGeneration(),
                        Objects.requireNonNull(owner, "owner"),
                        establishedBlueIds,
                        existingBlueIds);
            } else if (boundary.kind()
                    == TentativeFinalization.Boundary.Kind
                            .INITIALIZATION_BATCH) {
                finalizationGas.chargeInitializationAcyclicChangedBody(
                        stepProcessor,
                        documentId,
                        after.document(),
                        component.component().componentGeneration(),
                        establishedBlueIds,
                        existingBlueIds);
            } else {
                finalizationGas.chargeCheckpointAcyclicChangedBody(
                        stepProcessor,
                        documentId,
                        after.document(),
                        component.component().componentGeneration(),
                        0L,
                        establishedBlueIds,
                        existingBlueIds);
            }
        }
    }

    private static Map<DocumentId, Node> cloneBodies(
            Map<DocumentId, Node> source) {
        LinkedHashMap<DocumentId, Node> result =
                new LinkedHashMap<DocumentId, Node>();
        for (Map.Entry<DocumentId, Node> entry : source.entrySet()) {
            result.put(entry.getKey(), entry.getValue().clone());
        }
        return result;
    }

    private void synchronizeManagedReferences(
            DocumentId sourceDocumentId,
            Node runtimeDocument) {
        Node finalized = latestBodies.get(sourceDocumentId);
        for (ManagedOccurrenceBinding binding : currentBindings) {
            if (!binding.active()
                    || !binding.sourceDocumentId().equals(
                            sourceDocumentId)) {
                continue;
            }
            Node exact = NodePathEditor.getOrNull(
                    finalized, binding.sourcePath());
            if (exact != null) {
                NodePathEditor.put(
                        runtimeDocument,
                        binding.sourcePath(),
                        exact.clone());
            }
        }
    }

    private List<ManagedOccurrenceBinding> activeContainingOccurrences(
            DocumentId targetDocumentId) {
        ArrayList<ManagedOccurrenceBinding> result =
                new ArrayList<ManagedOccurrenceBinding>();
        for (ManagedOccurrenceBinding binding : currentBindings) {
            if (binding.active()
                    && binding.targetDocumentId().equals(
                            targetDocumentId)) {
                result.add(binding);
            }
        }
        Collections.sort(result,
                new Comparator<ManagedOccurrenceBinding>() {
                    @Override
                    public int compare(
                            ManagedOccurrenceBinding left,
                            ManagedOccurrenceBinding right) {
                        int order = left.sourceDocumentId().compareTo(
                                right.sourceDocumentId());
                        if (order != 0) {
                            return order;
                        }
                        order = ClosureValueSupport.comparePortableText(
                                left.sourcePath(), right.sourcePath());
                        if (order != 0) {
                            return order;
                        }
                        order = Long.compare(
                                left.activationGeneration(),
                                right.activationGeneration());
                        return order != 0 ? order
                                : ClosureValueSupport.comparePortableText(
                                        left.occurrenceIdentity(),
                                        right.occurrenceIdentity());
                    }
                });
        return result;
    }

    private String transitionIdentity(
            ClosureWorkOccurrence work,
            String beforeBlueId,
            long transitionOrdinal) {
        LinkedHashMap<String, Object> value =
                new LinkedHashMap<String, Object>();
        value.put("invocationIdentity", input.invocationIdentity());
        value.put("transitionOrdinal", Long.valueOf(transitionOrdinal));
        value.put("targetDocumentId", work.targetDocumentId().value());
        value.put("beforeBlueId", beforeBlueId);
        value.put("causingWorkOccurrenceIdentity", work.workIdentity());
        return IDENTITIES.identity(
                ClosureIdentityService.Constructor.TRANSITION_OCCURRENCE,
                value);
    }

    private GasChargeContext workContext(
            ClosureWorkOccurrence work,
            String reason) {
        ManagedDocumentSnapshot target = currentSnapshot.managedDocument(
                work.targetDocumentId());
        long generation = target == null
                ? inputComponentGenerations.get(
                        work.targetDocumentId()).longValue()
                : target.componentGeneration();
        return GasChargeContext.closure(
                work.targetDocumentId().value(),
                "/",
                Long.valueOf(0L),
                Long.valueOf(generation),
                work.channelKey().isEmpty() ? null : work.channelKey(),
                "work/" + work.ordinal(),
                work.workIdentity(),
                reason);
    }

    private GasChargeContext managedRevisionWorkContext(
            ClosureWorkOccurrence work,
            DocumentId documentId,
            boolean contractOwned,
            String reason) {
        ManagedDocumentSnapshot target = currentSnapshot.managedDocument(
                documentId);
        long generation = target == null
                ? inputComponentGenerations.get(documentId).longValue()
                : target.componentGeneration();
        return GasChargeContext.closure(
                documentId.value(),
                "/",
                Long.valueOf(0L),
                Long.valueOf(generation),
                contractOwned ? work.channelKey() : null,
                "work/" + work.ordinal(),
                work.workIdentity(),
                reason);
    }

    private static GasChargeContext documentContext(
            ManagedDocumentSnapshot document,
            String contractKey,
            String reason) {
        return GasChargeContext.closure(
                document.documentId().value(),
                null,
                null,
                null,
                contractKey,
                null,
                null,
                reason);
    }

    private void charge(
            String namespace,
            String counter,
            long quantity,
            GasChargeContext context) {
        stepProcessor.charge(
                namespace, counter, quantity, context);
    }

    private ActiveFrame activeFrame() {
        ActiveFrame frame = activeFrames.peekLast();
        if (frame == null) {
            throw new IllegalStateException(
                    "Closure continuation has no active document step");
        }
        return frame;
    }

    private static boolean sameNode(Node left, Node right) {
        if (left == right) {
            return true;
        }
        return left != null && right != null
                && NodeWireForm.get(left).equals(NodeWireForm.get(right));
    }

    private static void requireRoot(String scopePath) {
        if (!"/".equals(scopePath)) {
            throw new IllegalStateException(
                    "Managed closure continuation escaped Root scope");
        }
    }

    ClosureExecutionState state() {
        ensureOpen();
        return new ClosureExecutionState(
                currentSnapshot,
                currentFinalization,
                publicEvents,
                stepProcessor.processorGasTrace(),
                inputChannelSurfaces,
                resultingChannelSurfaces,
                checkpointMutations,
                epochAdvanceDocuments);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        stepProcessor.close();
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException(
                    "Closure execution session is closed");
        }
    }

    private static final class FrozenInitialization {
        private final String blueId;
        private final Node document;

        private FrozenInitialization(String blueId, Node document) {
            this.blueId = Objects.requireNonNull(
                    blueId, "blueId");
            this.document = Objects.requireNonNull(
                    document, "document").clone();
        }
    }

    private static final class PreparedMarker {
        private final Node inputBody;
        private final Node markerValue;
        private final GasChargeContext attribution;

        private PreparedMarker(
                Node inputBody,
                Node markerValue,
                GasChargeContext attribution) {
            this.inputBody = Objects.requireNonNull(
                    inputBody, "inputBody").clone();
            this.markerValue = Objects.requireNonNull(
                    markerValue, "markerValue").clone();
            this.attribution = Objects.requireNonNull(
                    attribution, "attribution");
        }
    }

    private static final class PendingWork {
        private final ClosureWorkOccurrence work;
        private final Node exactPayload;
        private final Node occurrenceEvent;
        private final FrozenJsonPatch processorPatch;
        private final ManagedCheckpointCandidate checkpointCandidate;
        private final Long rawOccurrenceOrder;

        private PendingWork(
                ClosureWorkOccurrence work,
                Node exactPayload,
                Node occurrenceEvent,
                FrozenJsonPatch processorPatch,
                ManagedCheckpointCandidate checkpointCandidate,
                Long rawOccurrenceOrder) {
            this.work = Objects.requireNonNull(work, "work");
            this.exactPayload = Objects.requireNonNull(
                    exactPayload, "exactPayload").clone();
            this.occurrenceEvent = occurrenceEvent == null
                    ? null : occurrenceEvent.clone();
            this.processorPatch = processorPatch;
            this.checkpointCandidate = checkpointCandidate;
            if ((checkpointCandidate == null)
                    != (rawOccurrenceOrder == null)) {
                throw new IllegalArgumentException(
                        "Checkpoint candidate and raw order must appear together");
            }
            this.rawOccurrenceOrder = rawOccurrenceOrder;
        }
    }

    private static final class CompletedCheckpointCandidate {
        private final ManagedCheckpointCandidate candidate;
        private final long rawOccurrenceOrder;

        private CompletedCheckpointCandidate(
                ManagedCheckpointCandidate candidate,
                long rawOccurrenceOrder) {
            this.candidate = Objects.requireNonNull(
                    candidate, "candidate");
            this.rawOccurrenceOrder = rawOccurrenceOrder;
        }
    }

    private static final class FinalizationUpdate {
        private final DocumentId sourceDocumentId;
        private final String sourcePath;
        private final Node before;
        private final Node after;

        private FinalizationUpdate(
                DocumentId sourceDocumentId,
                String sourcePath,
                Node before,
                Node after) {
            this.sourceDocumentId = Objects.requireNonNull(
                    sourceDocumentId, "sourceDocumentId");
            this.sourcePath = Objects.requireNonNull(
                    sourcePath, "sourcePath");
            this.before = before == null ? null : before.clone();
            this.after = after == null ? null : after.clone();
        }
    }

    private static final class RouteTarget {
        private final DocumentId targetDocumentId;
        private final ManagedDocumentStepRoute route;

        private RouteTarget(
                DocumentId targetDocumentId,
                ManagedDocumentStepRoute route) {
            this.targetDocumentId = Objects.requireNonNull(
                    targetDocumentId, "targetDocumentId");
            this.route = Objects.requireNonNull(route, "route");
        }
    }

    private final class EmittedOccurrence {
        private final long ordinal;
        private final String eventBlueId;
        private final String occurrenceIdentity;
        private final List<RouteTarget> routes;
        private final ActiveFrame capturedBy;

        private EmittedOccurrence(
                long ordinal,
                String eventBlueId,
                String occurrenceIdentity,
                Node event,
                List<RouteTarget> routes) {
            this.ordinal = ordinal;
            this.eventBlueId = eventBlueId;
            this.occurrenceIdentity = occurrenceIdentity;
            Objects.requireNonNull(event, "event");
            this.routes = Collections.unmodifiableList(
                    new ArrayList<RouteTarget>(routes));
            this.capturedBy = activeFrame();
        }
    }

    private final class ActiveFrame {
        private final ClosureWorkOccurrence work;
        private final long componentGeneration;
        private final String targetBeforeBlueId;
        private final Set<String> requiredExactManagedBlueIds =
                new LinkedHashSet<String>();
        private String currentPrePatchBlueId;
        private long patchCount;
        private long applicationEventCount;

        private ActiveFrame(
                ClosureWorkOccurrence work,
                long componentGeneration) {
            this.work = work;
            this.componentGeneration = componentGeneration;
            ManagedDocumentSnapshot target = currentSnapshot.managedDocument(
                    work.targetDocumentId());
            this.targetBeforeBlueId = target.blueId();
            this.currentPrePatchBlueId = this.targetBeforeBlueId;
        }

        private GasChargeContext context(String reason) {
            return GasChargeContext.closure(
                    work.targetDocumentId().value(),
                    "/",
                    Long.valueOf(0L),
                    Long.valueOf(componentGeneration),
                    work.channelKey().isEmpty()
                            ? null : work.channelKey(),
                    "work/" + work.ordinal(),
                    work.workIdentity(),
                    reason);
        }
    }
}
