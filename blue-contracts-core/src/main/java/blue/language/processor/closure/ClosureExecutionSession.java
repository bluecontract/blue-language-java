package blue.language.processor.closure;

import blue.language.identity.CyclicSetFinalization;
import blue.language.model.Node;
import blue.language.model.NodePathEditor;
import blue.language.model.NodeWireForm;
import blue.language.model.wire.BlueLanguageConstants;
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
import blue.language.processor.ManagedProcessEmbeddedPath;
import blue.language.processor.ManagedRootChannelOccurrence;
import blue.language.processor.ProcessorErrorCategory;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.processor.util.ProcessorContractConstants;
import blue.language.processor.util.ProcessorPointerConstants;
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

    /** Explicit orchestration lane selected by the public processor method. */
    enum ExecutionMode {
        /** Existing external or managed-revision processing. */
        PROCESSING,
        /** Full lifecycle admission over the existing causal queues. */
        ADMISSION
    }

    private static final ClosureIdentityService IDENTITIES =
            ClosureIdentityService.INSTANCE;
    private static final String PROVISIONAL_IDENTITY =
            "sha256:0000000000000000000000000000000000000000000000000000000000000000";
    private static final String CYCLIC_CANONICAL_BYTES_LIMIT =
            "cyclicCanonicalBytesPerComponent";

    private final ClosureInvocationInput input;
    private final ExecutionMode executionMode;
    private final ClosureExecutionRecorder recorder;
    private final ManagedDocumentStepProcessor stepProcessor;
    private final ComponentFinalizationKernel finalizer =
            new ComponentFinalizationKernel();
    private final ProcessEmbeddedSurfaceReconciler
            processEmbeddedReconciler =
            new ProcessEmbeddedSurfaceReconciler();
    private final ClosureFinalizationGasCharger finalizationGas =
            new ClosureFinalizationGasCharger();
    private final ManagedDocumentGraph inputGraph;
    private final Map<DocumentId, Long> inputComponentGenerations;
    private final Set<String> existingBlueIds;
    private final Set<String> establishedBlueIds =
            new LinkedHashSet<String>();
    private final Set<List<DocumentId>> verifiedCyclicComponents =
            new LinkedHashSet<List<DocumentId>>();
    private final Set<DocumentId> epochAdvanceDocuments =
            new LinkedHashSet<DocumentId>();
    private final Set<DocumentId> initializedDocuments =
            new LinkedHashSet<DocumentId>();
    private final Set<DocumentId> initializationStartedDocuments =
            new LinkedHashSet<DocumentId>();
    private final Map<DocumentId, String> pendingInitializationCauses =
            new LinkedHashMap<DocumentId, String>();
    private final Set<DocumentId> terminatingDocuments =
            new LinkedHashSet<DocumentId>();
    private final Set<DocumentId> terminatedDocuments =
            new LinkedHashSet<DocumentId>();
    private final Set<ProcessEmbeddedSurfaceReconciler.OccurrencePath>
            processEmbeddedRetirementFences =
            new LinkedHashSet<
                    ProcessEmbeddedSurfaceReconciler.OccurrencePath>();
    private final Deque<PendingTermination> pendingTerminations =
            new ArrayDeque<PendingTermination>();
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
    private final List<ManagedRootEventOccurrence> managedRootEvents =
            new ArrayList<ManagedRootEventOccurrence>();
    private final Map<DocumentId, Long> managedRootEventCounts =
            new LinkedHashMap<DocumentId, Long>();
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
    private final List<DocumentTransitionEvidence> transitionEvidence =
            new ArrayList<DocumentTransitionEvidence>();

    private AffectedClosureSnapshot currentSnapshot;
    private List<ManagedOccurrenceBinding> currentBindings;
    private ComponentFinalizationResult currentFinalization;
    private long graphGeneration;
    private long graphChanges;
    private long nextWorkOrdinal;
    private long nextStepOrdinal;
    private long nextEventOrdinal;
    private long nextTransitionOrdinal;
    private long lastCompletedWorkOrdinal = -1L;
    private int eventDeliveryBatchDepth;
    private int importedManagedEventDeliveryDepth;
    private int terminationLifecycleBatchDepth;
    private int suspendedDocumentStepDepth;
    private boolean initializationBatchRunning;
    private boolean terminationSettlementRunning;
    private long initializationBatchLastWorkOrdinal = -1L;
    private boolean activateManagedRevision;
    private boolean managedRevisionActivationCompleted;
    private boolean managedRevisionReceiptReconciled;
    private boolean closed;

    ClosureExecutionSession(
            DocumentProcessor owner,
            ClosureInvocationInput input,
            ClosureExecutionRecorder recorder,
            ExecutionMode executionMode) {
        this.input = Objects.requireNonNull(input, "input");
        this.executionMode = Objects.requireNonNull(
                executionMode, "executionMode");
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
        if (executionMode == ExecutionMode.ADMISSION) {
            ClosureAdmissionPortableLimits.verify(input);
            requireAvailableProcessEmbeddedResources();
        }
        chargeAdmission();
        captureChannelSurfaces(inputChannelSurfaces);
        if (executionMode == ExecutionMode.ADMISSION) {
            currentFinalization = verifyCurrentFinalization();
            executeAdmissionCause((AdmissionCause) input.cause());
        } else if (input.cause().kind()
                == ProcessingCause.Kind.EXTERNAL) {
            executeExternalCause((ExternalEventCause) input.cause());
        } else {
            executeManagedRevisionCause(
                    (ManagedRevisionCause) input.cause());
        }

        completePendingTerminations();
        if (!eventQueue.isEmpty() || !workQueue.isEmpty()) {
            throw new IllegalStateException(
                    "Closure execution did not reach causal quiescence");
        }
        if (!pendingTerminations.isEmpty()) {
            throw new IllegalStateException(
                    "Closure execution did not settle pending termination");
        }
        requireProspectiveMembersActivated();
        if (executionMode == ExecutionMode.PROCESSING
                && input.cause().kind() == ProcessingCause.Kind.EXTERNAL) {
            settleCheckpointBarrier();
        }
        captureChannelSurfaces(resultingChannelSurfaces);
        return state();
    }

    private void executeAdmissionCause(AdmissionCause cause) {
        String causeIdentity = Objects.requireNonNull(
                cause, "cause").causeIdentity();
        ManagedDocumentGraph graph = ManagedDocumentGraph.fromBindings(
                inputGraph.documentIds(), currentBindings);
        for (List<DocumentId> component
                : new SccPartitioner().partition(graph)) {
            for (DocumentId documentId : component) {
                ManagedDocumentSnapshot document = currentSnapshot
                        .managedDocument(documentId);
                if (!document.initialized()) {
                    pendingInitializationCauses.put(
                            documentId, causeIdentity);
                }
            }
        }
        runPendingInitializationBatch();
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
                cause.afterBlueId(),
                cause.afterDocument(),
                cause.afterCyclicProof().orElse(null),
                cyclicCanonicalBytesLimit(),
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
        deliverManagedRevisionEvents(cause);
    }

    private void deliverManagedRevisionEvents(
            ManagedRevisionCause cause) {
        if (!cause.sourceTransitionReceipt().isPresent()) {
            return;
        }
        ManagedDocumentTransitionReceipt receipt = cause
                .sourceTransitionReceipt().get();
        ManagedOccurrenceBinding target = managedRevisionTarget(cause);
        requireManagedRevisionEventTarget(cause, target);
        for (ManagedRootEventOccurrence event
                : receipt.emittedRootEvents()) {
            eventQueue.addLast(new EmittedOccurrence(
                    event.occurrenceOrdinal(),
                    event.eventBlueId(),
                    event.occurrenceIdentity(),
                    event.sourceDocumentId(),
                    event.exactEvent(),
                    Collections.singletonList(target),
                    null,
                    true));
            charge(
                    "processor",
                    "internalEventEnqueued",
                    1L,
                    documentContext(
                            currentSnapshot.managedDocument(
                                    target.sourceDocumentId()),
                            null,
                            "managed-revision.event."
                                    + event.ordinal() + ".enqueue"));
        }
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
        if (executionMode == ExecutionMode.ADMISSION) {
            if (input.operation()
                            != ClosureInvocationInput.Operation.ADMIT_CLOSURE
                    || input.cause().kind()
                            != ProcessingCause.Kind.ADMISSION
                    || !input.directDeliveries().isEmpty()) {
                throw new IllegalArgumentException(
                        "Full lifecycle admission requires ADMIT_CLOSURE, "
                                + "AdmissionCause, and zero direct deliveries");
            }
        } else {
            if (input.operation()
                            != ClosureInvocationInput.Operation.PROCESS_CLOSURE
                    || (input.cause().kind()
                                    != ProcessingCause.Kind.EXTERNAL
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
            if (input.cause().kind()
                            == ProcessingCause.Kind.MANAGED_REVISION
                    && !input.directDeliveries().isEmpty()) {
                throw new IllegalArgumentException(
                        "Managed revision must not carry direct deliveries");
            }
        }
        for (ManagedDocumentSnapshot document
                : input.snapshot().managedDocuments()) {
            if (document.terminated()
                    && !isRetainedManagedRevisionSource(
                            document.documentId())) {
                throw new ClosureCapabilityGapException(
                        "TERMINATED_MEMBER_POLICY_REQUIRED",
                        "Terminated members require lifecycle delivery policy");
            }
            if (executionMode == ExecutionMode.PROCESSING
                    && !document.initialized()
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

    private boolean isRetainedManagedRevisionSource(
            DocumentId documentId) {
        return executionMode == ExecutionMode.PROCESSING
                && input.cause() instanceof ManagedRevisionCause
                && ((ManagedRevisionCause) input.cause())
                        .childDocumentId().equals(documentId);
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

    private ComponentFinalizationResult verifyCurrentFinalization() {
        ComponentFinalizationResult verified;
        long finalizationStarted =
                recorder.beginComponentFinalizationProof();
        try {
            verified = finalizer.finalizeComponents(
                    new ComponentFinalizationInput(
                            inputGraph,
                            inputComponentGenerations,
                            latestBodies,
                            currentBindings));
        } finally {
            recorder.endComponentFinalizationProof(finalizationStarted);
        }
        Map<DocumentId, Node> normalizedInput = cloneBodies(latestBodies);
        for (ManagedOccurrenceBinding binding : currentBindings) {
            if (binding.active()) {
                NodePathEditor.put(
                        normalizedInput.get(binding.sourceDocumentId()),
                        binding.sourcePath(),
                        new Node().blueId(
                                binding.expectedTargetBlueId()));
            }
        }
        for (ManagedDocumentSnapshot document
                : currentSnapshot.managedDocuments()) {
            FinalizedDocumentEvidence exact = verified.document(
                    document.documentId());
            if (!document.blueId().equals(exact.blueId())
                    || !sameNode(
                            normalizedInput.get(document.documentId()),
                            exact.document())) {
                throw new IllegalArgumentException(
                        "Admission snapshot does not equal its exact "
                                + "component finalization");
            }
        }
        return verified;
    }

    private void requireProspectiveMembersActivated() {
        for (ManagedDocumentSnapshot document
                : input.snapshot().managedDocuments()) {
            if (!document.initialized()
                    && !initializedDocuments.contains(
                            document.documentId())
                    && !terminatedDocuments.contains(
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
        Set<DocumentId> processedDocuments = new LinkedHashSet<DocumentId>();
        for (DocumentTransitionEvidence transition : transitionEvidence) {
            processedDocuments.add(transition.documentId());
        }
        final Map<String, ManagedDocumentSnapshot> documentsByScopeIdentity =
                new LinkedHashMap<String, ManagedDocumentSnapshot>();
        List<ManagedCheckpointSettlementRequest> requests =
                new ArrayList<ManagedCheckpointSettlementRequest>();
        for (ManagedDocumentSnapshot document
                : currentSnapshot.managedDocuments()) {
            if (!processedDocuments.contains(document.documentId())) {
                // Cohort membership is not source work. An untouched source
                // may intentionally retain a checkpoint frozen before its
                // last Channel-catalog change; another Root's operation must
                // not clean that immutable source state. Cyclic reference
                // re-encoding alone is also not owned work. Every actual
                // managed step, including a no-op, records transition evidence
                // and still receives settlement here.
                continue;
            }
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
                    null);
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
        if (executionMode == ExecutionMode.ADMISSION) {
            for (ManagedOccurrenceBinding binding : currentBindings) {
                if (binding.active()) {
                    charge(
                            "processor",
                            "componentEdgePartitioned",
                            1L,
                            documentContext(
                                    currentSnapshot.managedDocument(
                                            binding.sourceDocumentId()),
                                    null,
                                    "admission.component-edge"));
                }
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
        if (!activeFrames.isEmpty()) {
            throw new IllegalStateException(
                    "Closure work cannot begin inside an active document step");
        }
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
        frame.beginTiming();
        try {
            result = stepProcessor.process(
                    step, pending.selectedRoute);
        } finally {
            try {
                ActiveFrame removed = activeFrames.removeLast();
                if (removed != frame) {
                    throw new IllegalStateException(
                            "Document-step continuation frame order changed");
                }
            } finally {
                frame.endTiming();
            }
        }
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
                    beforeWorkBoundary);
            if (!generatedUpdates.isEmpty()) {
                throw new ClosureCapabilityGapException(
                        "UNATTRIBUTED_FINALIZATION_UPDATE_TRANSITION",
                        "A finalization-induced update requires its exact "
                                + "source patch transition");
            }
        }
        recordTransitionEvidence(result);
        recordCompletedWork(work.ordinal());
        if (activeFrames.isEmpty()
                && eventDeliveryBatchDepth == 0
                && terminationLifecycleBatchDepth == 0) {
            completePendingTerminations();
        }
        if (activeFrames.isEmpty()
                && suspendedDocumentStepDepth == 0
                && !initializationBatchRunning) {
            runPendingInitializationBatch();
        }
        if (eventDeliveryBatchDepth == 0
                && terminationLifecycleBatchDepth == 0
                && suspendedDocumentStepDepth == 0) {
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

    private void recordTransitionEvidence(LocalDocumentStepResult result) {
        if (!result.transitionEvidence().isPresent()) {
            return;
        }
        ManagedDocumentSnapshot finalized = currentSnapshot.managedDocument(
                result.documentId());
        if (finalized == null) {
            throw new IllegalStateException(
                    "Transition target left the finalized closure");
        }
        transitionEvidence.add(result.transitionEvidence().get()
                .finalizedWith(finalized.blueId()));
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
        Map<DocumentId, Node> beforeWorkBoundary =
                cloneBodies(latestBodies);
        latestBodies.put(frame.work.targetDocumentId(), admitted.clone());
        List<FinalizationUpdate> generatedUpdates = finalizeTentative(
                TentativeFinalization.Boundary.work(
                        frame.work.ordinal()),
                frame.work,
                beforeWorkBoundary);
        if (shouldActivateManagedRevision(frame.work)) {
            ArrayList<FinalizationUpdate> combined =
                    new ArrayList<FinalizationUpdate>(generatedUpdates);
            combined.addAll(activateManagedRevision(frame.work));
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
                    frame,
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
                    frame,
                    update.sourceDocumentId,
                    routes,
                    updateOrdinal,
                    transitionIdentity,
                    transitionOrdinal);
            updateOrdinal++;
        }
        currentDocument.replaceWith(
                latestBodies.get(frame.work.targetDocumentId()));
        frame.currentPrePatchBlueId = currentSnapshot.managedDocument(
                frame.work.targetDocumentId()).blueId();
    }

    private void retainActivatedInitializationRequests(
            Set<String> activatedOccurrenceIdentities,
            ClosureWorkOccurrence owner) {
        Set<String> activated = Objects.requireNonNull(
                activatedOccurrenceIdentities,
                "activatedOccurrenceIdentities");
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
            if (initializationRequired(target)) {
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
                initializationStartedDocuments.add(documentId);
                executeInitialization(
                        documentId, initial, causeIdentity);
            }
            while (!eventQueue.isEmpty()) {
                drainOneEvent();
            }
        }
        completePendingTerminations();
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
        return !initializationStartedDocuments.contains(documentId)
                && initializationCanContinue(documentId);
    }

    private boolean initializationCanContinue(DocumentId documentId) {
        if (initializedDocuments.contains(documentId)
                || terminatingDocuments.contains(documentId)
                || terminatedDocuments.contains(documentId)) {
            return false;
        }
        if (executionMode == ExecutionMode.ADMISSION) {
            ManagedDocumentSnapshot admitted = input.snapshot()
                    .managedDocument(documentId);
            return admitted != null && !admitted.initialized();
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
        boolean survivingLifecycle = initializationCanContinue(documentId)
                && !lifecycle.isEmpty();
        if (executionMode == ExecutionMode.ADMISSION
                && recorder.finalizationCount() == 0L
                && hasCyclicComponent(currentBindings)
                && (survivingLifecycle
                        || hasPendingInitializationWork())) {
            finalizeTentative(
                    TentativeFinalization.Boundary.work(
                            initialization.work.ordinal()),
                    initialization.work,
                    cloneBodies(latestBodies));
        }
        for (ManagedDocumentStepRoute route : lifecycle) {
            if (!initializationCanContinue(documentId)) {
                break;
            }
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

    private boolean hasPendingInitializationWork() {
        for (DocumentId documentId
                : pendingInitializationCauses.keySet()) {
            if (initializationRequired(documentId)) {
                return true;
            }
        }
        return false;
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
            if (!initializationCanContinue(documentId)) {
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
                null);
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
            ClosureWorkOccurrence work) {
        ManagedRevisionCause revision =
                (ManagedRevisionCause) input.cause();
        ManagedOccurrenceBinding target = managedRevisionTarget(revision);
        ManagedDocumentSnapshot child = currentSnapshot.managedDocument(
                revision.childDocumentId());
        Node authoritativeReference = new Node().blueId(child.blueId());
        Map<DocumentId, Node> beforeActivation = cloneBodies(latestBodies);
        Node activatedSource = Objects.requireNonNull(
                latestBodies.get(target.sourceDocumentId()),
                "managed revision activation source").clone();
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
                activatedSource,
                target.sourcePath(),
                authoritativeReference.clone());
        latestBodies.put(
                target.sourceDocumentId(), activatedSource);
        activateManagedRevision = true;
        try {
            return finalizeTentative(
                    TentativeFinalization.Boundary.work(work.ordinal()),
                    work,
                    beforeActivation);
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
        Long count = managedRootEventCounts.get(emitter.documentId());
        long receiptOrdinal = count == null ? 0L : count.longValue();
        managedRootEventCounts.put(
                emitter.documentId(), Long.valueOf(receiptOrdinal + 1L));
        managedRootEvents.add(new ManagedRootEventOccurrence(
                receiptOrdinal,
                eventOrdinal,
                emitter.documentId(),
                occurrenceIdentity,
                eventBlueId,
                exactEvent,
                emitter.publicRoot()));
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

        eventQueue.addLast(new EmittedOccurrence(
                eventOrdinal,
                eventBlueId,
                occurrenceIdentity,
                emitter.documentId(),
                exactEvent,
                activeContainingOccurrences(emitter.documentId())));
        charge("processor", "internalEventEnqueued", 1L,
                frame.context("event." + eventOrdinal + ".enqueue"));
    }

    @Override
    public void onTerminationRequested(
            String scopePath,
            String cause,
            String reason) {
        requireRoot(scopePath);
        if (executionMode != ExecutionMode.ADMISSION) {
            throw new ClosureCapabilityGapException(
                    "LIFECYCLE_TERMINATION_REQUIRED",
                    "Termination requires the lifecycle and marker batch lane");
        }
        ActiveFrame frame = activeFrame();
        DocumentId documentId = frame.work.targetDocumentId();
        if (terminatingDocuments.contains(documentId)
                || terminatedDocuments.contains(documentId)) {
            return;
        }
        if (cause == null || cause.isEmpty()) {
            throw new IllegalArgumentException(
                    "Termination cause must be non-empty Text");
        }
        charge(
                "processor",
                "terminationRequested",
                1L,
                frame.context(
                        "work." + frame.work.ordinal()
                                + ".termination-request"));
        terminatingDocuments.add(documentId);
        PendingTermination termination = new PendingTermination(
                documentId,
                frame.work,
                cause,
                reason,
                acceptTerminationLifecycle(
                        documentId, frame.work, cause, reason));
        pendingTerminations.addLast(termination);
    }

    private List<ClosureWorkOccurrence> acceptTerminationLifecycle(
            DocumentId documentId,
            ClosureWorkOccurrence requestWork,
            String cause,
            String reason) {
        Node lifecycleEvent = terminationValue(
                RuntimeBlueIds.DOCUMENT_PROCESSING_TERMINATED,
                cause,
                reason);
        List<ManagedDocumentStepRoute> routes = stepProcessor
                .classifyLifecycleRoutes(
                        latestBodies.get(documentId),
                        lifecycleEvent);
        ArrayList<ClosureWorkOccurrence> accepted =
                new ArrayList<ClosureWorkOccurrence>();
        for (ManagedDocumentStepRoute route : routes) {
            PendingWork lifecycle = causedWork(
                    WorkKind.LIFECYCLE,
                    documentId,
                    route,
                    null,
                    null,
                    requestWork.workIdentity(),
                    null);
            accept(
                    lifecycle,
                    "termination.lifecycle.work."
                            + lifecycle.work.ordinal() + ".enqueue");
            accepted.add(lifecycle.work);
        }
        return Collections.unmodifiableList(accepted);
    }

    private void executeTerminationLifecycle(
            PendingTermination termination) {
        terminationLifecycleBatchDepth++;
        try {
            ClosureWorkQueue immediate = new ClosureWorkQueue();
            for (ClosureWorkOccurrence work
                    : termination.lifecycleWorks) {
                immediate.enqueue(work);
            }
            while (!immediate.isEmpty()) {
                ClosureWorkOccurrence work = immediate.dequeue();
                charge(
                        "processor",
                        "closureWorkOccurrenceDequeued",
                        1L,
                        workContext(
                                work,
                                "work." + work.ordinal() + ".dequeue"));
                PendingWork lifecycle = pendingByIdentity.remove(
                        work.workIdentity());
                if (lifecycle == null) {
                    throw new IllegalStateException(
                            "Accepted termination lifecycle work has no exact payload");
                }
                executeOne(lifecycle);
            }
        } finally {
            terminationLifecycleBatchDepth--;
        }
    }

    private void completePendingTerminations() {
        if (pendingTerminations.isEmpty()
                || terminationSettlementRunning
                || suspendedDocumentStepDepth > 0) {
            return;
        }
        if (!activeFrames.isEmpty()) {
            throw new IllegalStateException(
                    "Termination completion requires a closed document step");
        }
        ArrayList<PendingTermination> readyForMarker =
                new ArrayList<PendingTermination>();
        terminationSettlementRunning = true;
        try {
            while (!pendingTerminations.isEmpty()
                    || !eventQueue.isEmpty()) {
                while (!pendingTerminations.isEmpty()) {
                    PendingTermination termination =
                            pendingTerminations.removeFirst();
                    if (!terminatingDocuments.contains(
                            termination.documentId)
                            || terminatedDocuments.contains(
                                    termination.documentId)) {
                        continue;
                    }
                    executeTerminationLifecycle(termination);
                    readyForMarker.add(termination);
                }
                if (!eventQueue.isEmpty()) {
                    drainOneEvent();
                }
            }
            if (lastCompletedWorkOrdinal < 0L
                    && !readyForMarker.isEmpty()) {
                throw new IllegalStateException(
                        "Termination marker has no completed causal work");
            }
            long afterWorkOrdinal = lastCompletedWorkOrdinal;
            for (PendingTermination termination : readyForMarker) {
                writeTerminationMarker(
                        termination, afterWorkOrdinal);
            }
        } finally {
            terminationSettlementRunning = false;
        }
    }

    private void writeTerminationMarker(
            PendingTermination termination,
            long afterWorkOrdinal) {
        if (!terminatingDocuments.contains(termination.documentId)
                || terminatedDocuments.contains(
                        termination.documentId)) {
            return;
        }
        Node body = latestBodies.get(termination.documentId).clone();
        Node contracts = body.getContracts();
        if (contracts == null) {
            contracts = new Node();
            body.contracts(contracts);
        }
        if (contracts.getProperties() != null
                && contracts.getProperties().containsKey(
                        ProcessorContractConstants.KEY_TERMINATED)) {
            throw new IllegalStateException(
                    "Termination marker appeared during its own boundary");
        }
        Node marker = terminationValue(
                RuntimeBlueIds.PROCESSING_TERMINATED_MARKER,
                termination.cause,
                termination.reason);
        ManagedDocumentSnapshot current = currentSnapshot.managedDocument(
                termination.documentId);
        GasChargeContext attribution = GasChargeContext.closure(
                termination.documentId.value(),
                "/",
                Long.valueOf(0L),
                Long.valueOf(current.componentGeneration()),
                null,
                null,
                null,
                "termination-marker.after-work."
                        + afterWorkOrdinal + ".write");
        charge(
                "processor",
                "processorMarkerWritten",
                1L,
                attribution);
        Node marked = stepProcessor.writeDetachedProcessorState(
                body,
                ProcessorPointerConstants.RELATIVE_TERMINATED,
                marker,
                attribution);
        latestBodies.put(termination.documentId, marked);
        terminatingDocuments.remove(termination.documentId);
        terminatedDocuments.add(termination.documentId);
        ManagedDocumentSnapshot admitted = input.snapshot()
                .managedDocument(termination.documentId);
        if (admitted.initialized()) {
            epochAdvanceDocuments.add(termination.documentId);
        }
        finalizeTentative(
                TentativeFinalization.Boundary.terminationMarker(
                        afterWorkOrdinal),
                null,
                null);
    }

    private static Node terminationValue(
            String typeBlueId,
            String cause,
            String reason) {
        Node value = new Node().type(new Node().blueId(typeBlueId));
        value.properties(
                ProcessorContractConstants.KEY_CAUSE,
                new Node().value(cause));
        if (reason != null && !reason.isEmpty()) {
            value.properties(
                    ProcessorContractConstants.KEY_REASON,
                    new Node().value(reason));
        }
        return value;
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
        for (RouteTarget route : classifyEventRoutes(occurrence)) {
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
        if (occurrence.imported) {
            importedManagedEventDeliveryDepth++;
        }
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
                if (unavailableForOrdinaryDelivery(
                        work.targetDocumentId())) {
                    // An earlier delivery in this accept-before-deliver batch
                    // began termination. Settle the frozen work occurrence
                    // without starting another local Handler.
                    recordCompletedWork(work.ordinal());
                    continue;
                }
                executeOne(pending);
            }
        } finally {
            if (occurrence.imported) {
                if (importedManagedEventDeliveryDepth <= 0) {
                    throw new IllegalStateException(
                            "Imported managed-event delivery depth underflow");
                }
                importedManagedEventDeliveryDepth--;
            }
            eventDeliveryBatchDepth--;
        }
        if (eventDeliveryBatchDepth == 0
                && !pendingTerminations.isEmpty()) {
            completePendingTerminations();
        }
    }

    private List<RouteTarget> classifyEventRoutes(
            EmittedOccurrence occurrence) {
        ArrayList<RouteTarget> routes = new ArrayList<RouteTarget>();
        if (!occurrence.imported) {
            ManagedDocumentSnapshot source = requireEventDocument(
                    occurrence.sourceDocumentId,
                    "event source");
            if (!unavailableForOrdinaryDelivery(source.documentId())) {
                for (ManagedDocumentStepRoute route
                        : stepProcessor.classifyTriggeredEventRoutes(
                                source.document(), occurrence.event)) {
                    routes.add(new RouteTarget(
                            source.documentId(), route));
                }
            }
        }
        for (ManagedOccurrenceBinding frozen
                : occurrence.containingTargets) {
            if (occurrence.imported) {
                requireManagedRevisionEventTarget(
                        (ManagedRevisionCause) input.cause(),
                        frozen,
                        occurrence);
            } else {
                revalidateFrozenEventTarget(
                        occurrence.sourceDocumentId, frozen);
            }
            ManagedDocumentSnapshot containing = requireEventDocument(
                    frozen.sourceDocumentId(),
                    "frozen containing target");
            if (unavailableForOrdinaryDelivery(
                    containing.documentId())) {
                continue;
            }
            for (ManagedDocumentStepRoute route
                    : stepProcessor.classifyEmbeddedEventRoutes(
                            containing.document(),
                            frozen.sourcePath(),
                            occurrence.event,
                            occurrence.eventBlueId)) {
                routes.add(new RouteTarget(
                        containing.documentId(), route));
            }
        }
        return Collections.unmodifiableList(routes);
    }

    private void requireManagedRevisionEventTarget(
            ManagedRevisionCause revision,
            ManagedOccurrenceBinding frozen) {
        requireManagedRevisionEventTarget(revision, frozen, null);
    }

    private void requireManagedRevisionEventTarget(
            ManagedRevisionCause revision,
            ManagedOccurrenceBinding frozen,
            EmittedOccurrence importedEvent) {
        verifyFrozenEventBinding(frozen);
        if (!frozen.occurrenceIdentity().equals(
                    revision.targetOccurrenceIdentity())
                || !frozen.targetDocumentId().equals(
                    revision.childDocumentId())) {
            throw eventBindingFailure(
                    "Imported managed event targets another occurrence");
        }
        if (importedEvent != null
                && !isExactManagedRevisionReceiptEvent(
                        importedEvent, revision)) {
            throw eventBindingFailure(
                    "Imported managed event is absent from its source receipt");
        }
        ManagedOccurrenceBinding current = null;
        int currentMatches = 0;
        ManagedOccurrenceBinding successor = null;
        int successorMatches = 0;
        for (ManagedOccurrenceBinding candidate : currentBindings) {
            if (candidate.occurrenceIdentity().equals(
                    frozen.occurrenceIdentity())) {
                current = candidate;
                currentMatches++;
            }
            if (isNextActivationGeneration(frozen, candidate)) {
                successor = candidate;
                successorMatches++;
            }
        }
        if (currentMatches > 1
                || successorMatches > 1
                || (current != null && successor != null)) {
            throw eventBindingFailure(
                    "Imported managed event target is ambiguous");
        }
        if (current == null) {
            if (successor != null
                    && importedEvent != null
                    && exactManagedRevisionRetirementSuccessor(
                            revision, frozen, successor)) {
                return;
            }
            throw eventBindingFailure(
                    "Imported managed event target left the closure");
        }
        verifyFrozenEventBinding(current);
        ManagedDocumentSnapshot child = currentSnapshot.managedDocument(
                revision.childDocumentId());
        boolean authoritativeActivation =
                managedRevisionActivationCompleted
                && child != null
                && child.documentId().equals(revision.childDocumentId())
                && revision.toEpoch() == child.epoch()
                && current.active()
                && current.targetDocumentId().equals(child.documentId())
                && current.expectedTargetBlueId().equals(child.blueId())
                && sameManagedLocalRepresentation(revision, child);
        boolean exactReceiptSuccessor = current.expectedTargetBlueId().equals(
                revision.afterBlueId())
                && (current.active()
                    || (current.pendingHistoricalEpoch() != null
                        && current.pendingHistoricalEpoch().longValue()
                            == revision.toEpoch()));
        if (!sameBindingLineage(frozen, current)
                || (!exactReceiptSuccessor && !authoritativeActivation)) {
            throw eventBindingFailure(
                    "Imported managed event target no longer proves the source transition");
        }
        ManagedDocumentSnapshot containing = requireEventDocument(
                current.sourceDocumentId(),
                "managed-revision containing target");
        Node exact = NodePathEditor.getOrNull(
                containing.document(), current.sourcePath());
        String installedBlueId = authoritativeActivation
                ? child.blueId()
                : revision.afterBlueId();
        if (exact == null
                || !exact.isReferenceOnly()
                || !installedBlueId.equals(exact.getBlueId())) {
            throw eventBindingFailure(
                    "Imported managed event target path no longer contains the exact source state");
        }
    }

    private boolean exactManagedRevisionRetirementSuccessor(
            ManagedRevisionCause revision,
            ManagedOccurrenceBinding frozen,
            ManagedOccurrenceBinding successor) {
        verifyFrozenEventBinding(successor);
        ManagedDocumentSnapshot child = currentSnapshot.managedDocument(
                revision.childDocumentId());
        ProcessEmbeddedSurfaceReconciler.OccurrencePath path =
                new ProcessEmbeddedSurfaceReconciler.OccurrencePath(
                        frozen.sourceDocumentId(), frozen.sourcePath());
        ManagedDocumentSnapshot containing = currentSnapshot.managedDocument(
                frozen.sourceDocumentId());
        return child != null
                && containing != null
                && processEmbeddedRetirementFences.contains(path)
                && !successor.active()
                && successor.pendingHistoricalEpoch() == null
                && successor.sourceDocumentId().equals(
                        frozen.sourceDocumentId())
                && successor.sourcePath().equals(frozen.sourcePath())
                && successor.targetDocumentId().equals(
                        frozen.targetDocumentId())
                && successor.targetDocumentId().equals(
                        revision.childDocumentId())
                && successor.bindingPolicyIdentity().equals(
                        frozen.bindingPolicyIdentity())
                && successor.expectedTargetBlueId().equals(child.blueId())
                && NodePathEditor.getOrNull(
                        containing.document(), frozen.sourcePath()) == null;
    }

    private boolean isExactManagedRevisionReceiptEvent(
            EmittedOccurrence occurrence,
            ManagedRevisionCause revision) {
        if (!occurrence.imported
                || !revision.sourceTransitionReceipt().isPresent()) {
            return false;
        }
        for (ManagedRootEventOccurrence event
                : revision.sourceTransitionReceipt().get()
                        .emittedRootEvents()) {
            if (event.sourceDocumentId().equals(
                        occurrence.sourceDocumentId)
                    && event.sourceDocumentId().equals(
                        revision.childDocumentId())
                    && event.occurrenceIdentity().equals(
                        occurrence.occurrenceIdentity)
                    && event.occurrenceOrdinal() == occurrence.ordinal
                    && event.eventBlueId().equals(occurrence.eventBlueId)
                    && sameNode(event.exactEvent(), occurrence.event)) {
                return true;
            }
        }
        return false;
    }

    private boolean sameManagedLocalRepresentation(
            ManagedRevisionCause revision,
            ManagedDocumentSnapshot authoritative) {
        Node retained = revision.afterDocument();
        Node current = authoritative.document();
        for (ManagedOccurrenceBinding binding : currentBindings) {
            if (!binding.active()
                    || !binding.sourceDocumentId().equals(
                            authoritative.documentId())) {
                continue;
            }
            Node retainedValue = NodePathEditor.getOrNull(
                    retained, binding.sourcePath());
            Node currentValue = NodePathEditor.getOrNull(
                    current, binding.sourcePath());
            if (retainedValue == null
                    || currentValue == null
                    || !retainedValue.isReferenceOnly()
                    || !currentValue.isReferenceOnly()
                    || !binding.expectedTargetBlueId().equals(
                            currentValue.getBlueId())) {
                return false;
            }
            Node normalized = new Node().blueId(
                    binding.expectedTargetBlueId());
            NodePathEditor.put(
                    retained, binding.sourcePath(), normalized.clone());
            NodePathEditor.put(
                    current, binding.sourcePath(), normalized.clone());
        }
        return sameNode(retained, current);
    }

    private boolean unavailableForOrdinaryDelivery(DocumentId documentId) {
        ManagedDocumentSnapshot document = currentSnapshot.managedDocument(
                Objects.requireNonNull(documentId, "documentId"));
        return terminatingDocuments.contains(documentId)
                || terminatedDocuments.contains(documentId)
                || (document != null && document.terminated());
    }

    private ManagedDocumentSnapshot requireEventDocument(
            DocumentId documentId,
            String role) {
        ManagedDocumentSnapshot document = currentSnapshot.managedDocument(
                documentId);
        if (document == null) {
            throw eventBindingFailure(
                    "Missing " + role + " document " + documentId);
        }
        return document;
    }

    private void revalidateFrozenEventTarget(
            DocumentId eventSourceDocumentId,
            ManagedOccurrenceBinding frozen) {
        verifyFrozenEventBinding(frozen);
        if (!frozen.active()
                || !frozen.targetDocumentId().equals(
                        eventSourceDocumentId)) {
            throw eventBindingFailure(
                    "Frozen event target is not eligible for source "
                            + eventSourceDocumentId + " at "
                            + frozen.sourcePath());
        }

        ManagedOccurrenceBinding current = null;
        int currentMatches = 0;
        ManagedOccurrenceBinding successor = null;
        int successorMatches = 0;
        for (ManagedOccurrenceBinding candidate : currentBindings) {
            if (candidate.occurrenceIdentity().equals(
                    frozen.occurrenceIdentity())) {
                current = candidate;
                currentMatches++;
            }
            if (isNextActivationGeneration(frozen, candidate)) {
                successor = candidate;
                successorMatches++;
            }
        }
        if (currentMatches > 1
                || successorMatches > 1
                || (current != null && successor != null)) {
            throw eventBindingFailure(
                    "Frozen event target has conflicting binding lineage at "
                            + frozen.sourcePath());
        }
        if (current != null) {
            verifyFrozenEventBinding(current);
            if (!current.active()
                    || !sameBindingLineage(frozen, current)) {
                throw eventBindingFailure(
                        "Frozen event target conflicts with its latest "
                                + "binding at " + frozen.sourcePath());
            }
            ManagedDocumentSnapshot source = requireEventDocument(
                    current.sourceDocumentId(),
                    "latest containing target");
            ManagedDocumentSnapshot target = requireEventDocument(
                    current.targetDocumentId(),
                    "latest event-source target");
            Node exact = NodePathEditor.getOrNull(
                    source.document(), current.sourcePath());
            if (exact == null
                    || !ManagedOccurrenceTargetVerifier
                            .establishesExactTarget(exact, target)) {
                throw eventBindingFailure(
                        "Latest event target binding is invalid at "
                                + current.sourcePath());
            }
            return;
        }
        if (successor == null) {
            throw eventBindingFailure(
                    "Frozen event target has no latest or successor binding at "
                            + frozen.sourcePath());
        }
        // A later generation proves retirement/re-add continuity only.  Its
        // target never replaces the target frozen by this event occurrence.
        verifyFrozenEventBinding(successor);
    }

    private void verifyFrozenEventBinding(
            ManagedOccurrenceBinding binding) {
        try {
            ManagedOccurrenceBinding.verified(
                    binding.occurrenceIdentity(),
                    binding.bindingIdentity(),
                    binding.bindingPolicyIdentity(),
                    binding.sourceDocumentId(),
                    binding.sourceAddress(),
                    binding.targetDocumentId(),
                    binding.expectedTargetBlueId(),
                    binding.active(),
                    binding.pendingHistoricalEpoch());
        } catch (IllegalArgumentException invalid) {
            throw eventBindingFailure(
                    "Frozen event binding identity is invalid at "
                            + binding.sourcePath());
        }
    }

    private static boolean sameBindingLineage(
            ManagedOccurrenceBinding frozen,
            ManagedOccurrenceBinding current) {
        return frozen.sourceDocumentId().equals(
                        current.sourceDocumentId())
                && frozen.sourceAddress().equals(
                        current.sourceAddress())
                && frozen.targetDocumentId().equals(
                        current.targetDocumentId())
                && frozen.bindingPolicyIdentity().equals(
                        current.bindingPolicyIdentity());
    }

    private static boolean isNextActivationGeneration(
            ManagedOccurrenceBinding frozen,
            ManagedOccurrenceBinding candidate) {
        return frozen.activationGeneration()
                        < ClosureValueSupport.MAX_SAFE_INTEGER
                && candidate.activationGeneration()
                        == frozen.activationGeneration() + 1L
                && frozen.sourceDocumentId().equals(
                        candidate.sourceDocumentId())
                && frozen.sourcePath().equals(candidate.sourcePath())
                && frozen.bindingPolicyIdentity().equals(
                        candidate.bindingPolicyIdentity());
    }

    private static InvalidExecutionEvidenceException eventBindingFailure(
            String message) {
        return new InvalidExecutionEvidenceException(
                message,
                ProcessorErrorCategory.ManagedOccurrenceBindingMissing);
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
                null,
                route);
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
    }

    private void recordCompletedWork(long workOrdinal) {
        lastCompletedWorkOrdinal = Math.max(
                lastCompletedWorkOrdinal, workOrdinal);
        if (initializationBatchRunning) {
            initializationBatchLastWorkOrdinal = Math.max(
                    initializationBatchLastWorkOrdinal,
                    workOrdinal);
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
            Map<DocumentId, Node> beforeWorkBoundary) {
        List<ManagedOccurrenceBinding> bindingsBeforeFinalization =
                currentBindings;
        ProcessEmbeddedReclassification surfaceReclassification =
                reconcileProcessEmbeddedSurfaces(owner);
        List<ManagedOccurrenceBinding> reclassified =
                surfaceReclassification.bindings;
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
                cyclicPlan = executionMode == ExecutionMode.ADMISSION
                ? ClosureFinalizationGasCharger.plan(
                        currentSnapshot.components(),
                        finalized,
                        cyclicCanonicalBytesLimit(),
                        unverifiedCyclicComponents(finalized))
                : ClosureFinalizationGasCharger.plan(
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
        } else if (boundary.kind()
                == TentativeFinalization.Boundary.Kind
                        .TERMINATION_MARKER) {
            gasFrame = finalizationGas.beginTerminationMarker(
                    stepProcessor,
                    after,
                    generations,
                    Objects.requireNonNull(
                            boundary.afterWorkOrdinal(),
                            "termination afterWorkOrdinal").longValue(),
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
                        if (executionMode == ExecutionMode.ADMISSION) {
                            verifiedCyclicComponents.add(
                                    Collections.unmodifiableList(
                                            new ArrayList<DocumentId>(
                                                    evidence.component()
                                                            .orderedMemberDocumentIds())));
                        }
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
        processEmbeddedRetirementFences.addAll(
                surfaceReclassification.retiredOccurrencePaths);
        if (owner != null
                && !surfaceReclassification
                        .activatedOccurrenceIdentities.isEmpty()) {
            retainActivatedInitializationRequests(
                    surfaceReclassification
                            .activatedOccurrenceIdentities,
                    owner);
        }
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
         * epoch.  A managed-revision boundary may retain the authoritative
         * source epoch only when the final body delta is completely explained
         * below by active occurrence-reference re-encoding.  This applies
         * while reconciling the selected historical row and, after activation,
         * while delivering that source receipt's exact imported event.  Local
         * source work and every unexplained finalizer change remain strict.
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
        boolean deliversImportedSourceReceipt =
                isImportedManagedRevisionSourceReceiptBoundary(
                        documentId, owner);
        if (!reconcilesHistoricalRow
                && !deliversImportedSourceReceipt) {
            return true;
        }

        Node explained = sourceBodies.get(documentId).clone();
        boolean reencodedCurrentSource = false;
        for (ManagedOccurrenceBinding binding
                : bindingsBeforeFinalization) {
            if (!binding.active()
                    || !binding.sourceDocumentId().equals(documentId)
                    || (!deliversImportedSourceReceipt
                            && !binding.targetDocumentId().equals(
                                    owner.targetDocumentId()))) {
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

    private boolean isImportedManagedRevisionSourceReceiptBoundary(
            DocumentId documentId,
            ClosureWorkOccurrence owner) {
        if (!(input.cause() instanceof ManagedRevisionCause)
                || !managedRevisionActivationCompleted
                || importedManagedEventDeliveryDepth <= 0
                || owner.kind() != WorkKind.EMBEDDED_EVENT) {
            return false;
        }
        ManagedRevisionCause revision =
                (ManagedRevisionCause) input.cause();
        return documentId.equals(revision.childDocumentId())
                && !documentId.equals(owner.targetDocumentId())
                && isExactManagedRevisionReceiptEvent(owner, revision);
    }

    private void drainDocumentUpdateRoutes(
            ActiveFrame suspendedFrame,
            DocumentId targetDocumentId,
            List<ManagedDocumentStepRoute> routes,
            long updateOrdinal,
            String transitionIdentity,
            long transitionOrdinal) {
        if (routes.isEmpty()) {
            return;
        }
        suspendActiveFrame(suspendedFrame);
        try {
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
                if (caused == null) {
                    throw new IllegalStateException(
                            "Accepted Document Update work has no exact payload");
                }
                executeOne(caused);
            }
        } finally {
            resumeActiveFrame(suspendedFrame);
        }
    }

    private void suspendActiveFrame(ActiveFrame frame) {
        ActiveFrame removed = activeFrames.removeLast();
        if (removed != frame) {
            throw new IllegalStateException(
                    "Document-step continuation frame order changed");
        }
        frame.endTiming();
        suspendedDocumentStepDepth++;
    }

    private void resumeActiveFrame(ActiveFrame frame) {
        try {
            frame.beginTiming();
            activeFrames.addLast(frame);
        } finally {
            suspendedDocumentStepDepth--;
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

    /**
     * Projects every current Root before replacing any occurrence evidence.
     * The returned value is tentative until component finalization and its gas
     * frame complete successfully.
     */
    private ProcessEmbeddedReclassification
    reconcileProcessEmbeddedSurfaces(ClosureWorkOccurrence owner) {
        Map<DocumentId, List<ManagedProcessEmbeddedPath>> projected =
                projectProcessEmbeddedSurfaces();
        requireAvailableProcessEmbeddedResources(projected, true);

        ArrayList<DocumentId> sources =
                new ArrayList<DocumentId>(latestBodies.keySet());
        Collections.sort(sources);
        List<ManagedDocumentSnapshot> currentDocuments =
                currentSnapshot.managedDocuments();
        ArrayList<ManagedOccurrenceBinding> working =
                new ArrayList<ManagedOccurrenceBinding>();
        ManagedRevisionCause revision = input.cause()
                instanceof ManagedRevisionCause
                ? (ManagedRevisionCause) input.cause()
                : null;
        for (ManagedOccurrenceBinding binding : currentBindings) {
            if (revision != null
                    && binding.occurrenceIdentity().equals(
                            revision.targetOccurrenceIdentity())) {
                ManagedOccurrenceBinding receiptEventRetirement =
                        managedRevisionReceiptEventRetirement(
                                binding, revision, owner);
                if (receiptEventRetirement != null) {
                    // Preserve or invocation-locally activate the exact row
                    // just long enough for the ordinary surface reconciler
                    // below to classify its removal and allocate the next
                    // inactive generation.
                    working.add(receiptEventRetirement);
                } else {
                    working.add(reconcileManagedRevision(
                            binding, revision));
                }
            } else {
                working.add(binding);
            }
        }
        Collections.sort(working);

        LinkedHashSet<String> activated =
                new LinkedHashSet<String>();
        LinkedHashSet<ProcessEmbeddedSurfaceReconciler.OccurrencePath>
                retired =
                new LinkedHashSet<
                        ProcessEmbeddedSurfaceReconciler.OccurrencePath>();
        LinkedHashSet<ProcessEmbeddedSurfaceReconciler.OccurrencePath>
                fences =
                new LinkedHashSet<
                        ProcessEmbeddedSurfaceReconciler.OccurrencePath>(
                        processEmbeddedRetirementFences);
        for (DocumentId source : sources) {
            ProcessEmbeddedSurfaceReconciler.Reconciliation result =
                    processEmbeddedReconciler.reconcileProjected(
                            source,
                            latestBodies.get(source),
                            projected.get(source),
                            working,
                            currentDocuments,
                            fences);
            working = new ArrayList<ManagedOccurrenceBinding>(
                    result.bindings());
            activated.addAll(
                    result.activatedOccurrenceIdentities());
            retired.addAll(result.retiredOccurrencePaths());
            fences.addAll(result.retiredOccurrencePaths());
        }
        return new ProcessEmbeddedReclassification(
                working, activated, retired);
    }

    private ManagedOccurrenceBinding managedRevisionReceiptEventRetirement(
            ManagedOccurrenceBinding binding,
            ManagedRevisionCause revision,
            ClosureWorkOccurrence owner) {
        if (owner == null
                || owner.kind() != WorkKind.EMBEDDED_EVENT
                || importedManagedEventDeliveryDepth <= 0
                || !managedRevisionReceiptReconciled
                || !owner.targetDocumentId().equals(
                        binding.sourceDocumentId())
                || !binding.targetDocumentId().equals(
                        revision.childDocumentId())
                || !isExactManagedRevisionReceiptEvent(owner, revision)) {
            return null;
        }
        Node containing = latestBodies.get(binding.sourceDocumentId());
        if (containing == null
                || NodePathEditor.getOrNull(
                        containing, binding.sourcePath()) != null) {
            return null;
        }

        ManagedDocumentSnapshot child = currentSnapshot.managedDocument(
                revision.childDocumentId());
        if (child == null) {
            return null;
        }
        if (managedRevisionActivationCompleted
                && binding.active()
                && binding.pendingHistoricalEpoch() == null
                && revision.toEpoch() == child.epoch()
                && binding.expectedTargetBlueId().equals(child.blueId())) {
            return binding;
        }
        if (binding.active()
                || binding.pendingHistoricalEpoch() == null
                || binding.pendingHistoricalEpoch().longValue()
                        != revision.toEpoch()
                || !binding.expectedTargetBlueId().equals(
                        revision.afterBlueId())) {
            return null;
        }
        ManagedOccurrenceBinding activated =
                ManagedOccurrenceBinding.derived(
                        binding.bindingPolicyIdentity(),
                        binding.sourceDocumentId(),
                        binding.sourceAddress(),
                        binding.targetDocumentId(),
                        revision.afterBlueId(),
                        true,
                        null);
        if (!binding.occurrenceIdentity().equals(
                activated.occurrenceIdentity())) {
            throw new IllegalStateException(
                    "Managed receipt-event retirement changed occurrence "
                            + "lineage before ordinary reconciliation");
        }
        return activated;
    }

    private boolean isExactManagedRevisionReceiptEvent(
            ClosureWorkOccurrence owner,
            ManagedRevisionCause revision) {
        if (!revision.sourceTransitionReceipt().isPresent()
                || owner.eventBlueId() == null
                || owner.occurrenceOrdinal() == null) {
            return false;
        }
        for (ManagedRootEventOccurrence event
                : revision.sourceTransitionReceipt().get()
                        .emittedRootEvents()) {
            if (event.sourceDocumentId().equals(
                        revision.childDocumentId())
                    && event.occurrenceIdentity().equals(
                        owner.sourceOccurrenceIdentity())
                    && event.occurrenceOrdinal()
                        == owner.occurrenceOrdinal().longValue()
                    && event.eventBlueId().equals(owner.eventBlueId())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Suspends admission before causal work when the current effective Process
     * Embedded surface requires exact external resources.
     */
    private void requireAvailableProcessEmbeddedResources() {
        requireAvailableProcessEmbeddedResources(
                projectProcessEmbeddedSurfaces(), false);
    }

    private void requireAvailableProcessEmbeddedResources(
            Map<DocumentId, List<ManagedProcessEmbeddedPath>> projected,
            boolean verifyHistoricalExactReferences) {
        List<ClosureResourceDemand> demands =
                processEmbeddedReconciler.resourceDemands(
                        latestBodies,
                        projected,
                        currentBindings,
                        currentSnapshot.managedDocuments(),
                        processEmbeddedDemandContext(
                                verifyHistoricalExactReferences));
        if (!demands.isEmpty()) {
            throw new ClosureResourceDemandException(demands);
        }
    }

    private Map<DocumentId, List<ManagedProcessEmbeddedPath>>
    projectProcessEmbeddedSurfaces() {
        ArrayList<DocumentId> sources =
                new ArrayList<DocumentId>(latestBodies.keySet());
        Collections.sort(sources);
        LinkedHashMap<DocumentId, List<ManagedProcessEmbeddedPath>> result =
                new LinkedHashMap<DocumentId,
                        List<ManagedProcessEmbeddedPath>>();
        for (DocumentId source : sources) {
            result.put(
                    source,
                    stepProcessor.projectManagedProcessEmbeddedSurface(
                            latestBodies.get(source)));
        }
        return result;
    }

    private ProcessEmbeddedSurfaceReconciler.DemandContext
    processEmbeddedDemandContext(boolean verifyHistoricalExactReferences) {
        return new ProcessEmbeddedSurfaceReconciler.DemandContext(
                input.cause().causeIdentity(),
                input.snapshot().closureIdentity(),
                input.snapshot().graphGeneration(),
                new ProcessEmbeddedSurfaceReconciler
                        .ExactReferenceAvailability() {
                    @Override
                    public boolean isAvailable(String blueId) {
                        return stepProcessor
                                .isExactManagedReferenceAvailable(blueId);
                    }
                },
                verifyHistoricalExactReferences);
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
        // Imported receipt events may finalize their containing document
        // after this occurrence has already reached the authoritative head.
        // Those later boundaries must retain, rather than replay, activation.
        boolean retainsCompletedActivation =
                managedRevisionActivationCompleted && binding.active();
        boolean caughtUp = activateManagedRevision
                || retainsCompletedActivation;
        String installedBlueId = caughtUp
                ? child.blueId()
                : revision.afterBlueId();
        if (value == null
                || !value.isReferenceOnly()
                || !installedBlueId.equals(value.getBlueId())) {
            throw new IllegalStateException(
                    "Managed-revision patch did not install its exact "
                            + "historical successor reference");
        }
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
                    original.terminated()
                            || terminatedDocuments.contains(
                                    original.documentId()),
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

    private Set<List<DocumentId>> unverifiedCyclicComponents(
            ComponentFinalizationResult finalized) {
        LinkedHashSet<List<DocumentId>> result =
                new LinkedHashSet<List<DocumentId>>();
        for (FinalizedComponentEvidence evidence
                : finalized.components()) {
            if (evidence.cyclicFinalization() == null) {
                continue;
            }
            List<DocumentId> members = evidence.component()
                    .orderedMemberDocumentIds();
            if (!verifiedCyclicComponents.contains(members)) {
                result.add(Collections.unmodifiableList(
                        new ArrayList<DocumentId>(members)));
            }
        }
        return Collections.unmodifiableSet(result);
    }

    private boolean hasCyclicComponent(
            List<ManagedOccurrenceBinding> bindings) {
        ManagedDocumentGraph graph = ManagedDocumentGraph.fromBindings(
                inputGraph.documentIds(), bindings);
        for (List<DocumentId> members
                : new SccPartitioner().partition(graph)) {
            if (members.size() > 1
                    || graph.hasSelfEdge(members.get(0))) {
                return true;
            }
        }
        return false;
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
            } else if (boundary.kind()
                    == TentativeFinalization.Boundary.Kind
                            .TERMINATION_MARKER) {
                finalizationGas.chargeTerminationAcyclicChangedBody(
                        stepProcessor,
                        documentId,
                        after.document(),
                        component.component().componentGeneration(),
                        Objects.requireNonNull(
                                boundary.afterWorkOrdinal(),
                                "termination afterWorkOrdinal").longValue(),
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
                epochAdvanceDocuments,
                transitionEvidence,
                managedRootEvents);
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
                    blueId, BlueLanguageConstants.OBJECT_BLUE_ID);
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
        private final ManagedDocumentStepRoute selectedRoute;

        private PendingWork(
                ClosureWorkOccurrence work,
                Node exactPayload,
                Node occurrenceEvent,
                FrozenJsonPatch processorPatch,
                ManagedCheckpointCandidate checkpointCandidate,
                Long rawOccurrenceOrder) {
            this(work,
                    exactPayload,
                    occurrenceEvent,
                    processorPatch,
                    checkpointCandidate,
                    rawOccurrenceOrder,
                    null);
        }

        private PendingWork(
                ClosureWorkOccurrence work,
                Node exactPayload,
                Node occurrenceEvent,
                FrozenJsonPatch processorPatch,
                ManagedCheckpointCandidate checkpointCandidate,
                Long rawOccurrenceOrder,
                ManagedDocumentStepRoute selectedRoute) {
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
            this.selectedRoute = selectedRoute;
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

    private static final class ProcessEmbeddedReclassification {
        private final List<ManagedOccurrenceBinding> bindings;
        private final Set<String> activatedOccurrenceIdentities;
        private final Set<ProcessEmbeddedSurfaceReconciler.OccurrencePath>
                retiredOccurrencePaths;

        private ProcessEmbeddedReclassification(
                List<ManagedOccurrenceBinding> bindings,
                Set<String> activatedOccurrenceIdentities,
                Set<ProcessEmbeddedSurfaceReconciler.OccurrencePath>
                        retiredOccurrencePaths) {
            this.bindings = Collections.unmodifiableList(
                    new ArrayList<ManagedOccurrenceBinding>(bindings));
            this.activatedOccurrenceIdentities =
                    Collections.unmodifiableSet(
                            new LinkedHashSet<String>(
                                    activatedOccurrenceIdentities));
            this.retiredOccurrencePaths = Collections.unmodifiableSet(
                    new LinkedHashSet<
                            ProcessEmbeddedSurfaceReconciler.OccurrencePath>(
                            retiredOccurrencePaths));
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

    private static final class PendingTermination {
        private final DocumentId documentId;
        private final ClosureWorkOccurrence requestWork;
        private final String cause;
        private final String reason;
        private final List<ClosureWorkOccurrence> lifecycleWorks;

        private PendingTermination(
                DocumentId documentId,
                ClosureWorkOccurrence requestWork,
                String cause,
                String reason,
                List<ClosureWorkOccurrence> lifecycleWorks) {
            this.documentId = Objects.requireNonNull(
                    documentId, "documentId");
            this.requestWork = Objects.requireNonNull(
                    requestWork, "requestWork");
            this.cause = Objects.requireNonNull(cause, "cause");
            this.reason = reason;
            this.lifecycleWorks = Collections.unmodifiableList(
                    new ArrayList<ClosureWorkOccurrence>(
                            Objects.requireNonNull(
                                    lifecycleWorks, "lifecycleWorks")));
        }
    }

    private final class EmittedOccurrence {
        private final long ordinal;
        private final String eventBlueId;
        private final String occurrenceIdentity;
        private final DocumentId sourceDocumentId;
        private final Node event;
        private final List<ManagedOccurrenceBinding> containingTargets;
        private final ActiveFrame capturedBy;
        private final boolean imported;

        private EmittedOccurrence(
                long ordinal,
                String eventBlueId,
                String occurrenceIdentity,
                DocumentId sourceDocumentId,
                Node event,
                List<ManagedOccurrenceBinding> containingTargets) {
            this(
                    ordinal,
                    eventBlueId,
                    occurrenceIdentity,
                    sourceDocumentId,
                    event,
                    containingTargets,
                    activeFrame(),
                    false);
        }

        private EmittedOccurrence(
                long ordinal,
                String eventBlueId,
                String occurrenceIdentity,
                DocumentId sourceDocumentId,
                Node event,
                List<ManagedOccurrenceBinding> containingTargets,
                ActiveFrame capturedBy,
                boolean imported) {
            this.ordinal = ordinal;
            this.eventBlueId = Objects.requireNonNull(
                    eventBlueId, "eventBlueId");
            this.occurrenceIdentity = Objects.requireNonNull(
                    occurrenceIdentity, "occurrenceIdentity");
            this.sourceDocumentId = Objects.requireNonNull(
                    sourceDocumentId, "sourceDocumentId");
            this.event = Objects.requireNonNull(event, "event").clone();
            this.containingTargets = Collections.unmodifiableList(
                    new ArrayList<ManagedOccurrenceBinding>(
                            Objects.requireNonNull(
                                    containingTargets,
                                    "containingTargets")));
            this.capturedBy = capturedBy;
            this.imported = imported;
        }

    }

    private final class ActiveFrame {
        private final ClosureWorkOccurrence work;
        private final long componentGeneration;
        private final String targetBeforeBlueId;
        private String currentPrePatchBlueId;
        private long patchCount;
        private long applicationEventCount;
        private long timingStarted;
        private boolean timingActive;

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

        private void beginTiming() {
            if (timingActive) {
                throw new IllegalStateException(
                        "Document-step timing is already active");
            }
            timingStarted = recorder.beginManagedDocumentStep();
            timingActive = true;
        }

        private void endTiming() {
            if (!timingActive) {
                throw new IllegalStateException(
                        "Document-step timing is not active");
            }
            try {
                recorder.endManagedDocumentStep(timingStarted);
            } finally {
                timingActive = false;
            }
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
