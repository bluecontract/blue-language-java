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
import blue.language.processor.GasLimitExceededException;
import blue.language.processor.ProcessorDiagnostic;
import blue.language.processor.ProcessorFailureException;
import blue.language.processor.ProcessorStatus;
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
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

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
    private final Map<String, ManagedOccurrenceEvidenceResolution>
            managedOccurrenceResolutions =
                    new LinkedHashMap<String,
                            ManagedOccurrenceEvidenceResolution>();
    private final Set<String> consumedManagedOccurrenceResolutions =
            new LinkedHashSet<String>();
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
    private final Map<String, ProcessEmbeddedSurfaceReconciler.OccurrenceTransition> occurrenceRetirements = new LinkedHashMap<>();
    private final Deque<PendingTermination> pendingTerminations =
            new ArrayDeque<PendingTermination>();
    private final Map<DocumentId, Node> latestBodies =
            new LinkedHashMap<DocumentId, Node>();
    private final Map<DocumentId, Set<String>>
            finalizedBlueIdsByDocument =
            new LinkedHashMap<DocumentId, Set<String>>();
    private final ClosureWorkQueue workQueue = new ClosureWorkQueue();
    private final Deque<ClosureWorkOccurrence> sameOriginWorkQueue = new ArrayDeque<ClosureWorkOccurrence>();
    private final Map<ClosureWorkOccurrence, PendingWork> pendingByIdentity =
            new java.util.IdentityHashMap<ClosureWorkOccurrence, PendingWork>();
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
    private final Map<DocumentId, PendingTermination> terminationRequests = new LinkedHashMap<>();
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
    private final Map<DocumentId, SourceCursor> sourcesByDocument = new LinkedHashMap<DocumentId, SourceCursor>();
    // Invocation-local continuation ownership, never a semantic identity or a persisted host key.
    // Different interpretations of one lineage may be queued together; accepting later source
    // evidence must not retarget an already accepted work item to another source operation.
    private final Map<ClosureWorkOccurrence, SourceCursor> sourceContextsByWork = new java.util.IdentityHashMap<>();
    private final Map<DocumentId, SourceOperationFailure> failedSources = new LinkedHashMap<DocumentId, SourceOperationFailure>();
    private final Set<DocumentId> initializingSources = new LinkedHashSet<DocumentId>();
    private final Map<DocumentId, SourceInitialization> offeredInitializations = new LinkedHashMap<>();
    private final Map<String, InitializationInstallation> pendingInitializationInstallations = new LinkedHashMap<>();
    private final List<SourceCursor> sourceCursors = new ArrayList<SourceCursor>();
    private final Map<String, SourceCursor> sourceCursorsByInvocation = new LinkedHashMap<String, SourceCursor>();
    private Set<DocumentId> ownedDocuments;
    private Map<DocumentId, List<SourceObservationGap>> observationGaps = Collections.emptyMap();
    private Map<DocumentId, String> expectedSourceBases = Collections.emptyMap();
    private boolean requireExplicitSourceBases;
    private Set<DocumentId> admittedFreshSources;
    private String replayedEventOccurrenceIdentity;
    private String replayedPatchTransitionIdentity;
    private String replayedPatchSiteIdentity;
    private List<ManagedOccurrenceBinding> replayedPatchBindings;
    private final java.util.SortedSet<ManagedReadPin> retainedReadPins = new java.util.TreeSet<ManagedReadPin>();
    private final Map<String, List<SourceObservationProgram.ReferenceProjection>> referenceProjections =
            new LinkedHashMap<String, List<SourceObservationProgram.ReferenceProjection>>();
    private final Set<String> consumedReferenceProjections = new LinkedHashSet<String>();
    private List<SourceObservationProgram.ReferenceProjection> boundaryReferenceProjections = Collections.emptyList();
    private String observationSiteIdentity;
    private final Deque<UpdatePlacementFrame> updatePlacementFrames = new ArrayDeque<UpdatePlacementFrame>();
    private final Map<String, String> projectedReferenceValues = new LinkedHashMap<String, String>();
    private final Map<DocumentId, Set<String>> projectedReferenceTargets = new LinkedHashMap<DocumentId, Set<String>>();
    private SameOriginAttemptCoordinator sameOrigin;
    private SameOriginAttachmentPolicy attachmentPolicy = SameOriginAttachmentPolicy.empty();
    private SameOriginSeedIdentity.Factory sameOriginSeedFactory;
    private final Map<String, AcceptedAttachmentView> retainedAttachmentViews = new LinkedHashMap<>();
    private final Map<String, AttemptAttachmentView> acceptedAttachmentViews = new LinkedHashMap<>();
    private final Set<String> consumedRetainedAttachmentViews = new LinkedHashSet<>();
    private final Map<DocumentId, String> sourcePrefixSites = new LinkedHashMap<>();
    private final Map<SourceCursor, Set<String>> assignedRetainedWork = new java.util.IdentityHashMap<>();
    private final Map<Object, Map<DocumentId, String>> consumedRetainedOperations = new java.util.IdentityHashMap<>();
    private final Map<Object, Set<SameOriginGroupEvidence.SourceEvidence>> interpretedSourceEvidence = new java.util.IdentityHashMap<>();
    private final Map<Object, Map<DocumentId, Set<DocumentId>>> observedSourcesByAttempt = new java.util.IdentityHashMap<>();
    private final Map<DocumentId, ManagedReactionContext> managedReactionByConsumer = new LinkedHashMap<>();
    private final Map<String, ManagedReactionContext.DueOccurrence> dueReactionOccurrences = new LinkedHashMap<>();
    private final Map<String, ManagedOccurrenceBinding> pendingReactionBindings = new LinkedHashMap<>();
    private final Map<String, ManagedReadPin> nonDueReactionPins = new LinkedHashMap<>();
    private final Map<String, ManagedReadPin> initializationPlacementPins = new LinkedHashMap<>();
    private InitializationRealm projectingInitialization;
    private final Map<String, SourceFrontierView> offeredFrontierViews = new LinkedHashMap<>();
    private final Map<String, SourceInitialization> dormantInitializations = new LinkedHashMap<>();
    private final Map<String, AcceptedInitializationInstallation> retainedInitializationInstallations = new LinkedHashMap<>();
    private boolean executionComplete;
    private final Set<DocumentId> sameOriginRolledBack = new LinkedHashSet<DocumentId>();
    private final Map<DocumentId, SeedStream> seedStreams = new LinkedHashMap<DocumentId, SeedStream>();
    private final Set<Object> invalidatedSeedTokens = Collections.newSetFromMap(new java.util.IdentityHashMap<Object, Boolean>());
    private final Set<Object> admittedSeedTokens = Collections.newSetFromMap(new java.util.IdentityHashMap<Object, Boolean>());
    private final Map<DocumentId, ComponentSnapshot> originalSeedComponents = new HashMap<>();
    private final Set<Object> settledCheckpointTokens = Collections.newSetFromMap(new java.util.IdentityHashMap<Object, Boolean>());
    private boolean retryCheckpointSettlement;
    private final Map<Long, String> seedWorkSites = new HashMap<Long, String>();
    private final Map<Long, String> seedEventSites = new HashMap<Long, String>();
    private final Map<Long, String> seedTransitionSites = new HashMap<Long, String>();
    private final Map<String, String> seedDirectSites = new HashMap<String, String>();
    private final Set<DocumentId> sameOriginReconstruction = new LinkedHashSet<DocumentId>();
    private final List<ClosureWorkOccurrence> originalDirectSeeds = new ArrayList<ClosureWorkOccurrence>();
    private final Map<String, DirectLogicalDelivery> originalDirectDeliveries = new LinkedHashMap<String, DirectLogicalDelivery>();
    private static final java.util.regex.Pattern GLOBAL_SITE_REASON = java.util.regex.Pattern.compile("(work|event|transition)\\.(\\d+)(?=\\.|$)");

    /** Internal driver mode; publication requires separate complete group results. */
    SameOriginAttemptCoordinator useSameOriginGroups(DocumentProcessor owner) {
        return useSameOriginGroups(owner, SameOriginAttachmentPolicy.empty());
    }

    SameOriginAttemptCoordinator useSameOriginGroups(DocumentProcessor owner, SameOriginAttachmentPolicy policy) {
        if (sameOrigin != null || ownedDocuments != null || executionMode != ExecutionMode.PROCESSING
                || input.cause().kind() != ProcessingCause.Kind.EXTERNAL || input.managedReaction().isPresent()) {
            throw new IllegalStateException("Same-origin groups require a fresh external processing driver");
        }
        attachmentPolicy = Objects.requireNonNull(policy, "policy");
        attachmentPolicy.verifyBasis(input.snapshot());
        sameOriginSeedFactory = new SameOriginSeedIdentity.Factory(input, attachmentPolicy);
        sameOrigin = new SameOriginAttemptCoordinator(owner, input.executionPolicy(), this, input.snapshot().components());
        for (ComponentSnapshot component : input.snapshot().components()) {
            Map<DocumentId, Node> initialBodies = new LinkedHashMap<DocumentId, Node>();
            for (DocumentId member : component.orderedMemberDocumentIds()) {
                ManagedDocumentSnapshot state = input.snapshot().managedDocument(member);
                if (state.hasResidentBody()) initialBodies.put(member, state.document());
            }
            SeedStream stream = new SeedStream(sameOriginSeedFactory.of(component.orderedMemberDocumentIds()),
                    finalizationGas.existingIdentities(initialBodies));
            for (DocumentId member : component.orderedMemberDocumentIds()) {
                seedStreams.put(member, stream); originalSeedComponents.put(member, component);
            }
        }
        stepProcessor.useSameOriginGroups(sameOrigin, this::sameOriginContext, this::ensureSameOriginAdmission);
        return sameOrigin;
    }

    private static final class SeedStream {
        final SameOriginSeedIdentity identity;
        final Object originalAttemptToken = new Object();
        final Set<String> establishedBlueIds = new LinkedHashSet<String>();
        final Set<String> existingBlueIds;
        long workOrdinal, eventOrdinal, transitionOrdinal;
        SeedStream(SameOriginSeedIdentity identity, Set<String> existingBlueIds) { this.identity = identity; this.existingBlueIds = existingBlueIds; }
    }

    SourceObservationRecorder.Captured captureSameOriginGroup(DocumentId owner) {
        if (sameOrigin == null || recorder.sourceObservation() == null) throw new IllegalStateException("No same-origin source capture");
        SameOriginAttemptCoordinator.Attempt attempt = sameOrigin.attempt(owner);
        if (attempt.failure() != null) throw new IllegalStateException("Failed attempts cannot export source programs");
        Set<Object> tokens = Collections.newSetFromMap(new java.util.IdentityHashMap<Object, Boolean>());
        for (DocumentId member : attempt.members()) {
            Object token = seedStreams.get(member).originalAttemptToken;
            if (invalidatedSeedTokens.contains(token)) throw new IllegalStateException("Discarded source attempt survived into a group");
            tokens.add(token);
        }
        return recorder.sourceObservation().captureOwned(attempt.members(), tokens);
    }

    /** Freezes each surviving atomic operation in dependency-first canonical order. */
    List<SameOriginOperationResult> sameOriginOperations() {
        if (sameOrigin == null || !executionComplete || recorder.sourceObservation() == null)
            throw new IllegalStateException("Same-origin results require a quiescent captured interpreter execution");
        List<SameOriginAttemptCoordinator.Attempt> attempts = sameOrigin.activeAttempts();
        Map<SameOriginAttemptCoordinator.Attempt, Map<DocumentId, SameOriginAttemptCoordinator.Attempt>> dependencies = new java.util.IdentityHashMap<>();
        Map<SameOriginAttemptCoordinator.Attempt, Integer> unresolved = new java.util.IdentityHashMap<>();
        Map<SameOriginAttemptCoordinator.Attempt, Set<SameOriginAttemptCoordinator.Attempt>> dependents = new java.util.IdentityHashMap<>();
        java.util.SortedSet<SameOriginAttemptCoordinator.Attempt> ready = new java.util.TreeSet<>(
                Comparator.comparing(SameOriginAttemptCoordinator.Attempt::firstDocument));
        for (SameOriginAttemptCoordinator.Attempt attempt : attempts) {
            verifyFreshSourceAdmission(attempt.members());
            if (attempt.firstRuntime().groupReservedGas() != 0L)
                throw new IllegalStateException("A source result cannot contain unsettled runtime reservations");
            if (attempt.failure() == null) {
                for (DocumentId member : attempt.members()) if (input.snapshot().managedDocument(member).initialized()) epochAdvanceDocuments.add(member);
                sameOrigin.successful(attempt.firstDocument());
            }
            Map<DocumentId, SameOriginAttemptCoordinator.Attempt> sources = attempt.consumedIndependentSources();
            dependencies.put(attempt, sources);
            Set<SameOriginAttemptCoordinator.Attempt> unique = Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            unique.addAll(sources.values()); unresolved.put(attempt, unique.size());
            if (unique.isEmpty()) ready.add(attempt);
            for (SameOriginAttemptCoordinator.Attempt source : unique)
                dependents.computeIfAbsent(source, ignored -> Collections.newSetFromMap(new java.util.IdentityHashMap<>())).add(attempt);
        }
        SameOriginOperationResultAssembler assembler = new SameOriginOperationResultAssembler(input, state(),
                new LinkedHashSet<>(failedSources.values()), observationGaps, sourceCursors.stream()
                        .map(cursor -> cursor.program).collect(java.util.stream.Collectors.toList()), attachmentPolicy);
        Map<SameOriginAttemptCoordinator.Attempt, SameOriginOperationResult> completed = new java.util.IdentityHashMap<>();
        List<SameOriginOperationResult> operations = new ArrayList<>();
        while (!ready.isEmpty()) {
            SameOriginAttemptCoordinator.Attempt attempt = ready.first(); ready.remove(attempt);
            Map<DocumentId, String> consumed = new java.util.TreeMap<>();
            for (DocumentId member : attempt.members()) {
                Map<DocumentId, String> retained = consumedRetainedOperations.get(seedStreams.get(member).originalAttemptToken);
                if (retained != null) consumed.putAll(retained);
            }
            if (attempt.failure() == null) {
                for (DocumentId source : consumed.keySet()) {
                    SourceCursor cursor = sourcesByDocument.get(source);
                    if (cursor != null) recorder.sourceObservation().borrowedProgram(cursor.program,
                            seedStreams.get(attempt.firstDocument()).originalAttemptToken);
                }
            }
            for (Map.Entry<DocumentId, SameOriginAttemptCoordinator.Attempt> source : dependencies.get(attempt).entrySet()) {
                SameOriginOperationResult operation = completed.get(source.getValue());
                if (operation == null) throw new IllegalStateException("Independent source result was not completed before its consumer");
                consumed.put(source.getKey(), operation.operationIdentity());
                if (attempt.failure() == null && operation.sourceProgram().isPresent())
                    recorder.sourceObservation().borrowedProgram(operation.sourceProgram().get(),
                            seedStreams.get(attempt.firstDocument()).originalAttemptToken);
            }
            Map<DocumentId, String> seeds = new java.util.TreeMap<>();
            for (DocumentId member : attempt.members()) seeds.put(member, seedStreams.get(member).identity.identity());
            List<SameOriginGroupIdentity.Admission> admissions = new ArrayList<>();
            for (SameOriginAttemptCoordinator.Admission admission : attempt.admissions())
                admissions.add(new SameOriginGroupIdentity.Admission(admission.canonicalSite, admission.members));
            Map<DocumentId, Set<DocumentId>> observedByConsumer = new java.util.TreeMap<>();
            for (DocumentId consumer : attempt.members()) {
                Map<DocumentId, Set<DocumentId>> original = observedSourcesByAttempt.get(seedStreams.get(consumer).originalAttemptToken);
                if (original == null || !original.containsKey(consumer)) continue;
                Set<DocumentId> external = new java.util.TreeSet<>(original.get(consumer));
                external.retainAll(consumed.keySet());
                if (!external.isEmpty()) observedByConsumer.put(consumer, external);
            }
            SameOriginOperationResult operation = assembler.assemble(attempt.members(), seeds, admissions, consumed,
                    attempt.failure() == null ? attempt.canonicalGasTrace() : attempt.failure().canonicalGasTrace,
                    attempt.failure(), attempt.failure() == null ? captureSameOriginGroup(attempt.firstDocument()) : null,
                    observedByConsumer, interpretedSourceEvidence(attempt.members()));
            completed.put(attempt, operation); operations.add(operation);
            for (SameOriginAttemptCoordinator.Attempt consumer : dependents.getOrDefault(attempt, Collections.emptySet())) {
                int remaining = unresolved.get(consumer) - 1; unresolved.put(consumer, remaining);
                if (remaining == 0) ready.add(consumer);
            }
        }
        if (completed.size() != attempts.size()) throw new IllegalStateException("Independent operation dependencies contain an unadmitted feedback cycle");
        sameOrigin.settle();
        return Collections.unmodifiableList(operations);
    }

    private String ownDirectIdentity(DirectLogicalDelivery delivery) {
        SourceCursor source = sourcesByDocument.get(delivery.targetDocumentId());
        if (source != null) for (SourceObservationProgram.SkippedWork skipped : source.program.skippedWork())
            if (skipped.kind() == WorkKind.EXTERNAL_DELIVERY && skipped.targetDocumentId().equals(delivery.targetDocumentId())
                    && skipped.channelKey().equals(delivery.channelKey())) return skipped.sourceOccurrenceIdentity();
        return sameOrigin == null ? IDENTITIES.directDeliveryIdentity(delivery)
                : seedStreams.get(delivery.targetDocumentId()).identity.directDeliveryIdentity(delivery);
    }

    private Set<DocumentId> consumerOwnershipExcluding(Set<DocumentId> offered) {
        if (ownedDocuments != null) return ownedDocuments;
        Set<DocumentId> result = new LinkedHashSet<>();
        for (ManagedDocumentSnapshot document : input.snapshot().managedDocuments()) {
            if (!offered.contains(document.documentId()) && !sourcesByDocument.containsKey(document.documentId())
                    && !failedSources.containsKey(document.documentId())) result.add(document.documentId());
        }
        return result;
    }

    /** Committed evidence is a dependency, never a provisional attempt or a newly published operation. */
    private void observesSource(DocumentId consumer, DocumentId producer, String site) {
        if (projectingInitialization != null && projectingInitialization.source(producer) != null) {
            if (projectingInitialization.source(consumer) == null)
                consultInitialization(consumer, projectingInitialization.installation.initialization);
            if (sameOrigin != null && projectingInitialization.source(consumer) == null && recorder.sourceObservation() != null)
                recorder.sourceObservation().borrowedProgram(projectingInitialization.installation.initialization.program(),
                        seedStreams.get(consumer).originalAttemptToken);
            return;
        }
        if (sameOrigin == null || isRetainedSource(consumer) || failedSources.containsKey(consumer)) return;
        SourceCursor source = sourcesByDocument.get(producer);
        SourceOperationFailure failure = failedSources.get(producer);
        if (source != null && source.program.causeKind() == ProcessingCause.Kind.ADMISSION) {
            consultSourceEvidence(consumer, SameOriginGroupEvidence.SourceEvidence.Kind.INITIALIZATION, source.program.invocationIdentity());
            // Initialization is an immutable preparation fact. Its publication is authorized by
            // a surviving installation, not by pretending it is a previously committed external
            // source operation (create-then-retire must not publish an orphan source).
            if (recorder.sourceObservation() != null) recorder.sourceObservation().borrowedProgram(source.program,
                    seedStreams.get(consumer).originalAttemptToken);
            return;
        }
        if (source == null && failure == null) {
            sameOrigin.observes(consumer, producer, site);
            recordSourceObservation(consumer, producer);
            return;
        }
        String operation = source != null ? source.program.invocationIdentity() : failure.invocationIdentity();
        Map<DocumentId, String> consumed = consumedRetainedOperations.computeIfAbsent(
                seedStreams.get(consumer).originalAttemptToken, ignored -> new java.util.TreeMap<>());
        String previous = consumed.putIfAbsent(producer, operation);
        if (previous != null && !previous.equals(operation)) throw new IllegalArgumentException("Source position changed within one valid attempt");
        recordSourceObservation(consumer, producer);
    }

    /** Consumer membership follows actual continuation use and the original attempt lifetime. */
    private void recordSourceObservation(DocumentId consumer, DocumentId producer) {
        observedSourcesByAttempt.computeIfAbsent(seedStreams.get(consumer).originalAttemptToken,
                ignored -> new java.util.TreeMap<>()).computeIfAbsent(consumer,
                ignored -> new java.util.TreeSet<>()).add(producer);
    }

    private void consultInitialization(DocumentId consumer, SourceInitialization initialization) {
        consultSourceEvidence(consumer, SameOriginGroupEvidence.SourceEvidence.Kind.INITIALIZATION, initialization.program().invocationIdentity());
        if (sameOrigin != null && !isRetainedSource(consumer) && recorder.sourceObservation() != null)
            recorder.sourceObservation().borrowedProgram(initialization.program(), seedStreams.get(consumer).originalAttemptToken);
    }

    private void consultSourceEvidence(DocumentId consumer, SameOriginGroupEvidence.SourceEvidence.Kind kind, String identity) {
        if (sameOrigin == null || isRetainedSource(consumer)) return;
        SeedStream seed = seedStreams.get(consumer);
        if (seed == null || invalidatedSeedTokens.contains(seed.originalAttemptToken)) return;
        interpretedSourceEvidence.computeIfAbsent(seed.originalAttemptToken, ignored -> new TreeSet<>())
                .add(new SameOriginGroupEvidence.SourceEvidence(kind, identity));
    }

    private List<SameOriginGroupEvidence.SourceEvidence> interpretedSourceEvidence(Set<DocumentId> owners) {
        Set<SameOriginGroupEvidence.SourceEvidence> evidence = new TreeSet<>();
        for (DocumentId owner : owners) {
            Object token = seedStreams.get(owner).originalAttemptToken;
            if (invalidatedSeedTokens.contains(token)) throw new IllegalStateException("Discarded evidence cannot settle");
            evidence.addAll(interpretedSourceEvidence.getOrDefault(token, Collections.emptySet()));
        }
        return new ArrayList<>(evidence);
    }

    private String ownWorkIdentity(long globalOrdinal, WorkKind kind, DocumentId target, String channel, String source) {
        return ownWorkIdentity(globalOrdinal, kind, target, channel, source, sourcesByDocument.get(target));
    }

    private String ownWorkIdentity(long globalOrdinal, WorkKind kind, DocumentId target, String channel, String source, SourceCursor retained) {
        ManagedScopeKey scope = ManagedScopeKey.root(target);
        if (retained != null) {
            Set<String> assigned = assignedRetainedWork.computeIfAbsent(retained, ignored -> new LinkedHashSet<>());
            for (SourceObservationProgram.Step step : retained.steps) {
                if (step.targetDocumentId().equals(target) && step.kind() == kind && step.channelKey().equals(channel)
                        && assigned.add(step.workIdentity())) {
                    seedWorkSites.put(Long.valueOf(globalOrdinal), step.workIdentity());
                    return step.workIdentity();
                }
            }
            for (SourceObservationProgram.SkippedWork skipped : retained.program.skippedWork()) {
                if (skipped.targetDocumentId().equals(target) && skipped.kind() == kind && skipped.channelKey().equals(channel)
                        && skipped.sourceOccurrenceIdentity().equals(source) && assigned.add(skipped.workIdentity())) {
                    seedWorkSites.put(Long.valueOf(globalOrdinal), skipped.workIdentity());
                    return skipped.workIdentity();
                }
            }
            throw new IllegalArgumentException("Source work has no retained original identity");
        }
        if (sameOrigin == null) return IDENTITIES.workOccurrenceIdentity(input.invocationIdentity(), globalOrdinal, kind,
                IDENTITIES.managedScopeKeyIdentity(scope), source);
        SeedStream stream = seedStreams.get(target);
        String identity = stream.identity.workIdentity(stream.workOrdinal++, kind, scope, source);
        seedWorkSites.put(Long.valueOf(globalOrdinal), identity);
        return identity;
    }

    private GasChargeContext sameOriginContext(GasChargeContext original) {
        if (sameOrigin == null) return original;
        String reason = original.reason();
        if (ClosureValueSupport.matchesGasAttributionPrefix(reason,
                ClosureValueSupport.GasAttributionPrefix.DIRECT_ADMISSION) && original.workOccurrenceId() != null) {
            reason = "direct-admission." + original.workOccurrenceId() + ".classification";
        }
        if (ClosureValueSupport.matchesGasAttributionPrefix(reason,
                ClosureValueSupport.GasAttributionPrefix.CHECKPOINT_SETTLEMENT_WRITE)) {
            String site = seedDirectSites.get(original.documentId() + ":" + reason.substring("checkpoint-settlement.0.write.".length()));
            if (site == null) throw new IllegalStateException("Checkpoint gas attribution lost its seed-local direct source");
            reason = "checkpoint-settlement.0.write." + site;
        }
        java.util.regex.Matcher matcher = GLOBAL_SITE_REASON.matcher(reason);
        StringBuffer normalized = new StringBuffer();
        while (matcher.find()) {
            Map<Long, String> sites = "work".equals(matcher.group(1)) ? seedWorkSites
                    : "event".equals(matcher.group(1)) ? seedEventSites : seedTransitionSites;
            String exact = sites.get(Long.valueOf(matcher.group(2)));
            if (exact == null) throw new IllegalStateException("Semantic gas attribution names an unestablished seed-local site: " + matcher.group());
            matcher.appendReplacement(normalized, java.util.regex.Matcher.quoteReplacement(matcher.group(1) + "." + exact));
        }
        matcher.appendTail(normalized);
        String path = original.logicalPath();
        if (path != null && ClosureValueSupport.matchesGasAttributionPrefix(path,
                ClosureValueSupport.GasAttributionPrefix.WORK)) {
            String exact = seedWorkSites.get(Long.valueOf(path.substring(5)));
            if (exact == null) throw new IllegalStateException("Semantic gas path names an unestablished seed-local work");
            path = "work/" + exact;
        }
        GasChargeContext result = GasChargeContext.closure(original.documentId(), original.scopePath(), original.activationGeneration(),
                original.componentGeneration() == null ? null : Long.valueOf(0L), original.contractKey(), path, original.workOccurrenceId(), normalized.toString());
        if (original.finalizationOrdinal() != null) result = result.withFinalizationOwner(original.finalizationOrdinal().longValue(),
                original.finalizationComponentIdentity(), original.finalizationComponentGeneration().longValue());
        return result;
    }

    void ownDocuments(Set<DocumentId> documents) {
        if (documents.isEmpty()) throw new IllegalArgumentException("An atomic scope must own at least one lineage");
        for (DocumentId document : documents) {
            if (input.snapshot().managedDocument(document) == null) {
                throw new IllegalArgumentException("Owned lineage absent from exact input");
            }
        }
        ownedDocuments = Collections.unmodifiableSet(new LinkedHashSet<DocumentId>(documents));
        Set<DocumentId> readOnly = new LinkedHashSet<DocumentId>();
        for (ManagedDocumentSnapshot document : input.snapshot().managedDocuments()) {
            if (!ownedDocuments.contains(document.documentId())) readOnly.add(document.documentId());
        }
        stepProcessor.retainSourceDocuments(readOnly);
    }

    void failedObservationGaps(Map<DocumentId, List<SourceObservationGap>> gaps) {
        Map<DocumentId, List<SourceObservationGap>> copy = new LinkedHashMap<DocumentId, List<SourceObservationGap>>();
        for (Map.Entry<DocumentId, List<SourceObservationGap>> entry : gaps.entrySet()) {
            copy.put(entry.getKey(), Collections.unmodifiableList(new ArrayList<SourceObservationGap>(entry.getValue())));
        }
        observationGaps = Collections.unmodifiableMap(copy);
    }

    void expectedSourceBases(Map<DocumentId, String> bases) {
        expectedSourceBases = Collections.unmodifiableMap(new LinkedHashMap<DocumentId, String>(Objects.requireNonNull(bases, "bases")));
    }

    void requireExplicitSourceBases() { requireExplicitSourceBases = true; }

    void admittedFreshSources(Set<DocumentId> sources) {
        admittedFreshSources = sources == null ? null : Collections.unmodifiableSet(new LinkedHashSet<>(sources));
        if (admittedFreshSources != null) for (DocumentId source : admittedFreshSources)
            if (source == null || input.snapshot().managedDocument(source) == null)
                throw new IllegalArgumentException("Fresh source admission is outside the original invocation");
    }

    private void verifyFreshSourceAdmission(Collection<DocumentId> members) {
        if (admittedFreshSources != null) {
            Set<DocumentId> missing = new TreeSet<>(members);
            missing.removeAll(admittedFreshSources);
            if (!missing.isEmpty()) throw new SameOriginProcessAttempt.SourceAdmissionNeed(missing);
        }
        verifySourceContext(new LinkedHashSet<>(members), input.environment(), input.executionPolicy());
    }

    private void verifySourceContext(Set<DocumentId> owners, ClosureEnvironment environment, ExecutionPolicy policy) {
        SourceExecutionBasis.requireProducerBases(owners, input.environment(), environment, policy, sourceBases(owners));
    }

    private Map<DocumentId, String> sourceBases(Set<DocumentId> owners) {
        if (requireExplicitSourceBases) return expectedSourceBases;
        Map<DocumentId, String> bases = new LinkedHashMap<DocumentId, String>();
        // Compatibility calls fix absent producer entries to the invocation policy. Core verifies
        // complete independently admitted authority before invoking this boundary.
        for (DocumentId source : owners) bases.put(source, expectedSourceBases.containsKey(source)
                ? expectedSourceBases.get(source) : SourceExecutionBasis.identity(source, input.environment(), input.executionPolicy()));
        return bases;
    }

    private Map<DocumentId, String> initializationBases(SourceInitialization initialization) {
        return sourceBases(initialization.ownedDocumentIds());
    }

    void substituteSource(SourceObservationProgram program) {
        SourceObservationProgram selected = Objects.requireNonNull(program, "program");
        if (executionMode != ExecutionMode.PROCESSING
                || selected.causeKind() != ProcessingCause.Kind.EXTERNAL
                || !input.cause().causeIdentity().equals(selected.causeIdentity())) {
            throw new IllegalArgumentException("Source observation cause or runtime mismatch");
        }
        verifySourceContext(selected.ownedDocumentIds(), selected.environment(), selected.executionPolicy());
        for (SourceObservationProgram.SourceState source : selected.sourcePredecessors()) {
            if (!selected.ownedDocumentIds().contains(source.documentId())) continue;
            ManagedDocumentSnapshot present = input.snapshot().managedDocument(source.documentId());
            // The host may already hold this exact completed source operation. Its
            // retained predecessor remains the observation basis; an arbitrary later
            // head is not authority to rewind or substitute a different source cut.
            boolean exactCompletedView = false;
            if (present != null) for (SourceObservationProgram.SourceState after : selected.sourceResults()) {
                if (after.documentId().equals(source.documentId()) && after.epoch() == present.epoch()
                        && after.blueId().equals(present.blueId()) && after.initialized() == present.initialized()) {
                    exactCompletedView = true;
                    break;
                }
            }
            if (exactCompletedView) continue;
            if (present == null || source.epoch() < present.epoch()
                    || source.epoch() == present.epoch() && !source.blueId().equals(present.blueId())) {
                throw new IllegalArgumentException("Source observation predecessor mismatch");
            }
            if (!source.blueId().equals(present.blueId()) || source.epoch() != present.epoch()) {
                SourceObservationGap.verifyContinuity(consumerOwnershipExcluding(selected.ownedDocumentIds()), present, source,
                        observationGaps.containsKey(source.documentId()) ? observationGaps.get(source.documentId())
                                : Collections.<SourceObservationGap>emptyList());
            }
        }
        registerSourceCursor(selected);
    }

    void substituteInitialization(SourceInitialization initialization) {
        Objects.requireNonNull(initialization, "initialization").verifyInstallationBasis(
                input.snapshot(), ownedDocuments, input.environment(), initializationBases(initialization));
        if (executionMode != ExecutionMode.ADMISSION) throw new IllegalArgumentException("Initialization evidence requires admission mode");
        registerSourceCursor(initialization.program());
        initializingSources.addAll(initialization.ownedDocumentIds());
    }

    /** Preparation is not activation: no source cursor, body, lifecycle work or gas is installed here. */
    void offerInitialization(SourceInitialization initialization) {
        Objects.requireNonNull(initialization, "initialization");
        if (executionMode != ExecutionMode.PROCESSING || sameOrigin == null)
            throw new IllegalStateException("Prospective initialization requires the grouped external interpreter");
        initialization.verifySourceBasis(input.snapshot(), consumerOwnershipExcluding(initialization.ownedDocumentIds()),
                input.environment(), initializationBases(initialization));
        for (DocumentId source : initialization.ownedDocumentIds()) {
            SourceInitialization previous = offeredInitializations.putIfAbsent(source, initialization);
            if (previous != null && !sourceProgramDigest(previous.program()).equals(sourceProgramDigest(initialization.program())))
                throw new IllegalArgumentException("Conflicting canonical initialization offered for one source");
        }
    }

    void offerFrontierView(SourceFrontierView frontier) {
        Objects.requireNonNull(frontier, "frontier").verifyInvocation(input, sourceBases(Collections.singleton(frontier.selection().targetLineage())));
        if (sameOrigin == null || !attachmentPolicy.selection(frontier.selection().occurrenceIdentity())
                .map(selection -> selection.identity().equals(frontier.selection().identity())).orElse(false))
            throw new IllegalArgumentException("Frontier evidence has no frozen selected attachment policy");
        SourceFrontierView previous = offeredFrontierViews.putIfAbsent(frontier.selection().occurrenceIdentity(), frontier);
        if (previous != null && !previous.identity().equals(frontier.identity()))
            throw new IllegalArgumentException("Conflicting exact frontier evidence for one placement");
    }

    private static final class InitializationInstallation {
        final SameOriginAttachmentPolicy.Selection selection;
        final String creatorSeed, creatorSite;
        final Object creatorToken;
        final SourceInitialization initialization;
        AcceptedInitializationInstallation retained;
        InitializationRealm realm;
        InitializationInstallation(SameOriginAttachmentPolicy.Selection selection, String creatorSeed, String creatorSite,
                Object creatorToken, SourceInitialization initialization) {
            this.selection = selection; this.creatorSeed = creatorSeed; this.creatorSite = creatorSite;
            this.creatorToken = creatorToken; this.initialization = initialization;
        }
    }

    /** An observation realm is not another authoritative copy of any member lineage. */
    private final class InitializationRealm {
        final InitializationInstallation installation;
        final Map<DocumentId, SourceCursor> sources = new LinkedHashMap<>();
        final Map<String, SourceCursor> programs = new LinkedHashMap<>();
        final Set<SourceCursor> completed = Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        final Set<String> projected = new LinkedHashSet<>();
        InitializationRealm(InitializationInstallation installation) {
            this.installation = installation;
            add(installation.initialization);
        }
        private SourceCursor add(SourceInitialization initialization) {
            SourceObservationProgram program = initialization.program();
            verifySourceContext(program.ownedDocumentIds(), program.environment(), program.executionPolicy());
            SourceCursor previous = programs.get(program.invocationIdentity());
            if (previous != null) return previous;
            for (SourceObservationProgram borrowed : program.borrowedPrograms()) add(SourceInitialization.fromProgram(borrowed));
            SourceCursor cursor = new SourceCursor(program);
            cursor.view = SourceInterpretationView.from(initialization); cursor.realm = this;
            programs.put(program.invocationIdentity(), cursor);
            for (DocumentId member : program.ownedDocumentIds()) {
                if (sources.put(member, cursor) != null) throw new IllegalArgumentException("Conflicting original initialization realm ownership");
            }
            return cursor;
        }
        SourceCursor source(DocumentId member) { return sources.get(member); }
        List<ManagedOccurrenceBinding> routes() {
            List<ManagedOccurrenceBinding> result = new ArrayList<>();
            for (SourceCursor cursor : programs.values()) result.addAll(cursor.view.bindings());
            for (ManagedOccurrenceBinding binding : currentBindings) {
                if (binding.occurrenceIdentity().equals(installation.selection.occurrenceIdentity())
                        || !sources.containsKey(binding.sourceDocumentId()) && !sources.containsKey(binding.targetDocumentId()))
                    result.add(binding);
            }
            Collections.sort(result); return result;
        }
        void projectRetained(String site) {
            for (SourceCursor cursor : programs.values()) for (SourceObservationProgram.ReferenceProjection projection : cursor.program.referenceProjections()) {
                if (cursor.program.ownedDocumentIds().contains(projection.targetDocumentId())
                        && projection.siteIdentity().equals(site) && projected.add(projectionKey(projection))) cursor.view.projectReference(projection);
            }
        }
        String placementSite(String originalSite) {
            return IDENTITIES.observationInitializationPlacementSiteIdentity(originalSite,
                    installation.creatorSeed, installation.creatorSite, installation.selection.occurrenceIdentity());
        }
        void run() {
            for (SourceCursor cursor : programs.values()) {
                if (completed.contains(cursor)) continue;
                for (ComponentSnapshot component : cursor.program.sourceBeforeComponents()) {
                    Map<DocumentId, FrozenInitialization> frozen = new LinkedHashMap<>();
                    for (DocumentId member : component.orderedMemberDocumentIds()) frozen.put(member,
                            new FrozenInitialization(cursor.view.blueId(member), cursor.view.document(member)));
                    for (Map.Entry<DocumentId, FrozenInitialization> entry : frozen.entrySet()) {
                        Node event = lifecycleEvent(entry.getValue().blueId);
                        List<ManagedDocumentStepRoute> lifecycle = stepProcessor.classifyLifecycleRoutes(entry.getValue().document, event);
                        PendingWork initial = pendingWork(WorkKind.INITIALIZATION, entry.getKey(),
                                lifecycle.isEmpty() ? "lifecycle" : "initialization", installation.creatorSite, event, null, null, cursor);
                        executeImmediate(initial, "work." + initial.work.ordinal() + ".initialization-enqueue");
                        for (ManagedDocumentStepRoute route : lifecycle) {
                            PendingWork pending = causedWork(WorkKind.LIFECYCLE, entry.getKey(), route, null, null,
                                    installation.creatorSite, null, cursor);
                            executeImmediate(pending, "work." + pending.work.ordinal() + ".lifecycle-enqueue");
                        }
                    }
                    while (!eventQueue.isEmpty()) drainOneEvent();
                }
                if (cursor.nextStep != cursor.steps.size()) throw new IllegalArgumentException("Initialization realm omitted original source work");
                cursor.view.complete(); completed.add(cursor);
                String completion = IDENTITIES.observationInitializationCompletionSiteIdentity(cursor.program.invocationIdentity());
                projectRetained(completion);
                projectSelected(completion, cursor.program.ownedDocumentIds().iterator().next(), null);
            }
        }
        List<FinalizationUpdate> projectSelected(String originalSite, DocumentId originalSource, ClosureWorkOccurrence work) {
            ManagedOccurrenceBinding selected = null;
            for (ManagedOccurrenceBinding binding : currentBindings)
                if (binding.occurrenceIdentity().equals(installation.selection.occurrenceIdentity()) && binding.active()) { selected = binding; break; }
            if (selected == null) return Collections.emptyList();
            ManagedReadPin pin = source(selected.targetDocumentId()).view.selectedPin(selected.targetDocumentId());
            Map<DocumentId, Node> before = cloneBodies(latestBodies);
            initializationPlacementPins.put(selected.occurrenceIdentity(), pin); retainedReadPins.add(pin);
            if (!updatePlacementFrames.isEmpty()) updatePlacementFrames.getLast().pins.put(selected.occurrenceIdentity(), pin);
            if (!isRetainedSource(selected.sourceDocumentId())) {
                Node body = latestBodies.get(selected.sourceDocumentId());
                NodePathEditor.put(body, selected.sourcePath(), new Node().blueId(pin.blueId()));
                currentBindings.set(currentBindings.indexOf(selected), ManagedOccurrenceBinding.derived(selected.bindingPolicyIdentity(),
                        selected.sourceDocumentId(), selected.sourceAddress(), selected.targetDocumentId(), pin.blueId(), true, null));
            }
            InitializationRealm previous = projectingInitialization; projectingInitialization = this;
            try {
                return finalizeObservationSite(placementSite(originalSite), originalSource,
                        work == null ? TentativeFinalization.Boundary.initializationBatch(initializationBatchLastWorkOrdinal)
                                : TentativeFinalization.Boundary.work(work.ordinal()), work, before);
            } finally { projectingInitialization = previous; }
        }
    }

    private void registerSourceCursor(SourceObservationProgram selected) {
        verifySourceContext(selected.ownedDocumentIds(), selected.environment(), selected.executionPolicy());
        SourceCursor previousProgram = sourceCursorsByInvocation.get(selected.invocationIdentity());
        if (previousProgram != null) {
            if (previousProgram.program != selected && !sourceProgramDigest(previousProgram.program).equals(sourceProgramDigest(selected))) {
                throw new IllegalArgumentException("The same source operation has conflicting retained program evidence");
            }
            return;
        }
        for (SourceObservationProgram borrowed : selected.borrowedPrograms()) {
            SourceExecutionBasis.requireCompatibleEnvironment(selected.environment(), borrowed.environment());
            // Independent children are checked against their own authenticated producer basis
            // by registerSourceCursor, not against their importing parent's gas budget.
            if (borrowed.causeKind() == ProcessingCause.Kind.ADMISSION && selected.causeKind() == ProcessingCause.Kind.EXTERNAL) {
                retainDormantInitialization(borrowed);
                continue;
            }
            if (borrowed.causeKind() != selected.causeKind()) throw new IllegalArgumentException("Borrowed source has a different canonical cause kind");
            if (borrowed.causeKind() == ProcessingCause.Kind.ADMISSION) {
                SourceInitialization.fromProgram(borrowed);
                initializingSources.addAll(borrowed.ownedDocumentIds());
            } else if (!borrowed.causeIdentity().equals(selected.causeIdentity())) {
                throw new IllegalArgumentException("Borrowed source has a different external origin");
            }
            registerSourceCursor(borrowed);
        }
        retainedReadPins.addAll(selected.sourceReadPins());
        for (AcceptedInitializationInstallation installation : selected.acceptedInitializations()) {
            SourceInitialization source = dormantInitializations.get(installation.sourceInitializationOperationIdentity());
            if (source == null) throw new IllegalArgumentException("Retained initialization placement lacks its original borrowed program");
            installation.verifySourceInitialization(source);
            String key = attachmentViewKey(installation.creatorPatchSite(), installation.selection().occurrenceIdentity());
            AcceptedInitializationInstallation previous = retainedInitializationInstallations.putIfAbsent(key, installation);
            if (previous != null && !previous.identity().equals(installation.identity())) throw new IllegalArgumentException("Conflicting retained initialization placement");
        }
        for (AcceptedAttachmentView view : selected.acceptedViews()) {
            String key = attachmentViewKey(view.creatorPatchSite(), view.selection().occurrenceIdentity());
            AcceptedAttachmentView previous = retainedAttachmentViews.putIfAbsent(key, view);
            if (previous != null && !previous.identity().equals(view.identity()))
                throw new IllegalArgumentException("Conflicting retained attachment view at one canonical site");
            retainedReadPins.add(view.selectedView());
        }
        if (recorder.sourceObservation() != null && ownedDocuments != null
                && Collections.disjoint(ownedDocuments, selected.ownedDocumentIds())) recorder.sourceObservation().borrowedProgram(selected);
        SourceCursor cursor = new SourceCursor(selected);
        for (DocumentId source : selected.ownedDocumentIds()) {
            if (ownedDocuments != null && ownedDocuments.contains(source)) {
                throw new IllegalArgumentException("Owned lineage cannot also be a retained source");
            }
            if (sourcesByDocument.putIfAbsent(source, cursor) != null) {
                throw new IllegalArgumentException("Two retained programs own the same source lineage");
            }
        }
        sourceCursors.add(cursor);
        sourceCursorsByInvocation.put(selected.invocationIdentity(), cursor);
        for (SourceObservationProgram.ReferenceProjection projection : selected.referenceProjections()) {
            if (!selected.ownedDocumentIds().contains(projection.targetDocumentId())) {
                throw new IllegalArgumentException("A retained projection cannot write outside its producer's ownership");
            }
            List<SourceObservationProgram.ReferenceProjection> atSite = referenceProjections.get(projection.siteIdentity());
            if (atSite == null) {
                atSite = new ArrayList<SourceObservationProgram.ReferenceProjection>();
                referenceProjections.put(projection.siteIdentity(), atSite);
            }
            for (SourceObservationProgram.ReferenceProjection previous : atSite) {
                if (previous.targetDocumentId().equals(projection.targetDocumentId())) {
                    throw new IllegalArgumentException("Duplicate retained reference projection at source site");
                }
            }
            atSite.add(projection);
        }
        if (ownedDocuments == null) stepProcessor.retainSourceDocuments(sourcesByDocument.keySet());
    }

    private static String sourceProgramDigest(SourceObservationProgram program) {
        return SourceObservationProgramCodec.encode(program, (identity, bytes) -> { }, FrozenNodeEvidenceCodec.Limits.defaults());
    }

    private void retainDormantInitialization(SourceObservationProgram program) {
        verifySourceContext(program.ownedDocumentIds(), program.environment(), program.executionPolicy());
        SourceInitialization initialization = SourceInitialization.fromProgram(program);
        SourceInitialization previous = dormantInitializations.putIfAbsent(program.invocationIdentity(), initialization);
        if (previous != null && previous.program() != program && !sourceProgramDigest(previous.program()).equals(sourceProgramDigest(program)))
            throw new IllegalArgumentException("Conflicting dormant initialization program");
        if (previous != null) return;
        for (SourceObservationProgram borrowed : program.borrowedPrograms()) retainDormantInitialization(borrowed);
    }

    void retainSourceFailure(SourceOperationFailure failure) {
        Objects.requireNonNull(failure, "sourceFailure");
        if (executionMode != ExecutionMode.PROCESSING || failure.causeKind() != ProcessingCause.Kind.EXTERNAL
                || !input.cause().causeIdentity().equals(failure.causeIdentity())) {
            throw new IllegalArgumentException("Failed source operation has a different canonical cause or environment");
        }
        verifySourceContext(failure.ownedDocumentIds(), failure.environment(), failure.executionPolicy());
        if (ownedDocuments == null && sameOrigin == null) throw new IllegalArgumentException("Failed source observation requires explicit consumer ownership");
        failure.verifyObservationBasis(input.snapshot(), consumerOwnershipExcluding(failure.ownedDocumentIds()), observationGaps);
        for (DocumentId source : failure.ownedDocumentIds()) {
            if (sourcesByDocument.containsKey(source) || failedSources.containsKey(source)) {
                throw new IllegalArgumentException("Failed source operation has conflicting ownership");
            }
            failedSources.put(source, failure);
        }
    }

    ClosureExecutionSession(
            DocumentProcessor owner,
            ClosureInvocationInput input,
            ClosureExecutionRecorder recorder,
            ExecutionMode executionMode) {
        this(
                owner,
                input,
                recorder,
                executionMode,
                Collections.<ManagedOccurrenceEvidenceResolution>
                        emptyList());
    }

    ClosureExecutionSession(
            DocumentProcessor owner,
            ClosureInvocationInput input,
            ClosureExecutionRecorder recorder,
            ExecutionMode executionMode,
            List<ManagedOccurrenceEvidenceResolution> resolutions) {
        this.input = Objects.requireNonNull(input, "input");
        this.executionMode = Objects.requireNonNull(
                executionMode, "executionMode");
        this.recorder = Objects.requireNonNull(recorder, "recorder");
        this.currentSnapshot = input.snapshot();
        this.retainedReadPins.addAll(input.snapshot().readPins());
        this.currentBindings = new ArrayList<ManagedOccurrenceBinding>(
                currentSnapshot.occurrences());
        this.graphGeneration = currentSnapshot.graphGeneration();
        ArrayList<DocumentId> documentIds = new ArrayList<DocumentId>();
        this.inputComponentGenerations =
                new LinkedHashMap<DocumentId, Long>();
        for (ManagedDocumentSnapshot document
                : currentSnapshot.managedDocuments()) {
            documentIds.add(document.documentId());
            if (document.hasResidentBody()) latestBodies.put(document.documentId(), document.document());
            recordFinalizedBlueId(
                    document.documentId(), document.blueId());
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
        for (ManagedOccurrenceEvidenceResolution resolution
                : Objects.requireNonNull(resolutions, "resolutions")) {
            ManagedOccurrenceEvidenceResolution selected =
                    Objects.requireNonNull(
                            resolution, "managed occurrence resolution");
            if (managedOccurrenceResolutions.put(
                    selected.demand().demandIdentity(), selected) != null) {
                throw new IllegalArgumentException(
                        "Duplicate managed-occurrence resolution demand");
            }
        }
    }

    /** Executes one supported affected-closure lane. */
    ClosureExecutionState execute() {
        ensureSupportedInvocation();
        prepareManagedReactionViews();
        prepareSourceInitializationView();
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
        if (!eventQueue.isEmpty() || !causalWorkEmpty()) {
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
            do {
                retryCheckpointSettlement = false;
                settleCheckpointBarrier();
                if (retryCheckpointSettlement) drainCausalWork();
            } while (retryCheckpointSettlement);
        }
        captureChannelSurfaces(resultingChannelSurfaces);
        finishManagedReactionViews();
        requireEveryManagedOccurrenceResolutionConsumed();
        for (SourceCursor cursor : sourceCursors) {
            if (cursor.nextStep != cursor.steps.size()) {
                throw new IllegalArgumentException("Source observation has unconsumed steps");
            }
            if (cursor.consumedSkippedWork.size() != cursor.program.skippedWork().size())
                throw new IllegalArgumentException("Source observation has unconsumed skipped work");
        }
        for (List<SourceObservationProgram.ReferenceProjection> atSite : referenceProjections.values()) {
            for (SourceObservationProgram.ReferenceProjection projection : atSite) {
                if (!consumedReferenceProjections.contains(projectionKey(projection))) {
                    throw new IllegalArgumentException("Retained reference projection has no canonical consumed source site");
                }
            }
        }
        for (AcceptedAttachmentView view : retainedAttachmentViews.values())
            if (!consumedRetainedAttachmentViews.contains(view.identity()))
                throw new IllegalArgumentException("Retained attachment view has no consumed canonical creation site");
        if (sameOrigin == null && !input.managedReaction().isPresent()) {
            // Classic closure epochs describe a net state transition. A temporary
            // change that was reverted must not inherit processed-operation authority.
            epochAdvanceDocuments.removeIf(document -> input.snapshot().managedDocument(document).blueId()
                    .equals(currentSnapshot.managedDocument(document).blueId()));
        }
        executionComplete = true;
        return state();
    }

    /** Explicit, operation-local delivery eligibility; pending lanes are never made live in the result. */
    private void prepareManagedReactionViews() {
        List<SourceObservationProgram> programs = new ArrayList<>();
        for (SourceCursor source : sourceCursors) programs.add(source.program);
        List<SourceOperationFailure> failures = new ArrayList<>(new LinkedHashSet<>(failedSources.values()));
        if (input.managedReaction().isPresent()) {
            if (ownedDocuments == null || sameOrigin != null) throw new IllegalArgumentException("A managed reaction requires explicit consumer ownership");
            registerManagedReaction(input.managedReaction().get(), ownedDocuments, programs, failures);
        }
        for (SourceCursor source : sourceCursors) if (source.program.managedReaction().isPresent())
            registerManagedReaction(source.program.managedReaction().get(), source.program.ownedDocumentIds(), programs, failures);
        if (managedReactionByConsumer.isEmpty()) return;
        for (int index = 0; index < currentBindings.size(); index++) {
            ManagedOccurrenceBinding binding = currentBindings.get(index);
            if (!managedReactionByConsumer.containsKey(binding.sourceDocumentId())) continue;
            if (dueReactionOccurrences.containsKey(binding.occurrenceIdentity())) {
                if (!binding.active()) {
                    pendingReactionBindings.put(binding.occurrenceIdentity(), binding);
                    currentBindings.set(index, ManagedOccurrenceBinding.derived(binding.bindingPolicyIdentity(), binding.sourceDocumentId(),
                            binding.sourceAddress(), binding.targetDocumentId(), binding.expectedTargetBlueId(), true, null));
                }
            } else if (binding.active() || binding.pendingHistoricalEpoch() != null) {
                ManagedReadPin pin = exactReadPin(binding);
                nonDueReactionPins.put(binding.occurrenceIdentity(), pin); retainedReadPins.add(pin);
            }
        }
    }

    private void registerManagedReaction(ManagedReactionContext context, Set<DocumentId> consumers,
            List<SourceObservationProgram> programs, List<SourceOperationFailure> failures) {
        context.verifyBasis(input.snapshot(), consumers, programs, failures);
        for (DocumentId consumer : consumers) {
            ManagedReactionContext prior = managedReactionByConsumer.putIfAbsent(consumer, context);
            if (prior != null && !prior.identity().equals(context.identity())) throw new IllegalArgumentException("One consumer has conflicting reaction positions");
        }
        for (ManagedReactionContext.DueOccurrence due : context.dueOccurrences()) {
            ManagedReactionContext.DueOccurrence prior = dueReactionOccurrences.putIfAbsent(due.occurrenceIdentity(), due);
            if (prior != null && !prior.sourceOperationIdentity().equals(due.sourceOperationIdentity()))
                throw new IllegalArgumentException("One occurrence has conflicting source positions");
        }
    }

    private ManagedReadPin exactReadPin(ManagedOccurrenceBinding binding) {
        ManagedReadPin pin = currentSnapshot.readPin(binding.targetDocumentId(), binding.expectedTargetBlueId());
        if (pin != null) return pin;
        ManagedDocumentSnapshot target = currentSnapshot.managedDocument(binding.targetDocumentId());
        if (target == null || !target.blueId().equals(binding.expectedTargetBlueId()))
            throw new IllegalArgumentException("Reaction lane lacks an exact non-due source view");
        blue.language.provider.CyclicSetProof proof = null;
        for (ComponentSnapshot component : currentSnapshot.components()) if (component.orderedMemberDocumentIds().contains(target.documentId())) {
            proof = component.completeCyclicProof(); break;
        }
        return ManagedReadPin.fromExactEvidence(target.documentId(), target.blueId(), target.document(), proof);
    }

    private boolean reactionDeliveryEligible(ManagedOccurrenceBinding binding) {
        return !managedReactionByConsumer.containsKey(binding.sourceDocumentId())
                || dueReactionOccurrences.containsKey(binding.occurrenceIdentity())
                || managedReactionByConsumer.get(binding.sourceDocumentId()) == managedReactionByConsumer.get(binding.targetDocumentId());
    }

    private void finishManagedReactionViews() {
        if (pendingReactionBindings.isEmpty()) return;
        for (int index = 0; index < currentBindings.size(); index++) {
            ManagedOccurrenceBinding binding = currentBindings.get(index);
            ManagedOccurrenceBinding before = pendingReactionBindings.get(binding.occurrenceIdentity());
            if (before == null || !binding.active()) continue;
            SourceCursor source = sourcesByDocument.get(binding.targetDocumentId());
            long consumedEpoch = source == null ? before.pendingHistoricalEpoch() : source.after.get(binding.targetDocumentId()).epoch();
            currentBindings.set(index, ManagedOccurrenceBinding.derived(binding.bindingPolicyIdentity(), binding.sourceDocumentId(),
                    binding.sourceAddress(), binding.targetDocumentId(), binding.expectedTargetBlueId(), false, Long.valueOf(consumedEpoch)));
        }
        ComponentFinalizationResult restored = finalizer.finalizeComponents(new ComponentFinalizationInput(
                inputGraph, inputComponentGenerations, latestBodies, currentBindings, retainedPins(currentBindings), currentSnapshot.reusableComponents()));
        for (FinalizedDocumentEvidence document : restored.documents().values())
            if (!document.blueId().equals(currentSnapshot.managedDocument(document.documentId()).blueId()))
                throw new IllegalArgumentException("Historical route eligibility cannot replace exact component identity evidence");
        currentFinalization = restored; currentBindings = new ArrayList<>(restored.finalizedGraph().bindings());
        graphGeneration = ClosureGraphGenerationTransition.assign(input.snapshot().graphGeneration(), inputGraph, restored.finalizedGraph());
        currentSnapshot = snapshot(restored);
    }

    private void prepareSourceInitializationView() {
        if (initializingSources.isEmpty()) return;
        currentBindings.removeIf(binding -> initializingSources.contains(binding.sourceDocumentId()));
        for (SourceCursor cursor : sourceCursors) {
            if (cursor.program.causeKind() != ProcessingCause.Kind.ADMISSION) continue;
            currentBindings.addAll(cursor.program.sourceBeforeBindings());
        }
        Collections.sort(currentBindings);
        for (DocumentId source : initializingSources) {
            latestBodies.put(source, sourcesByDocument.get(source).before.get(source).document());
            initializedDocuments.remove(source);
        }
        ComponentFinalizationResult before = finalizer.finalizeComponents(new ComponentFinalizationInput(
                inputGraph, inputComponentGenerations, latestBodies, currentBindings, retainedPins(currentBindings), currentSnapshot.reusableComponents()));
        for (DocumentId source : initializingSources) {
            if (!before.document(source).blueId().equals(sourcesByDocument.get(source).before.get(source).blueId())) {
                throw new InvalidExecutionEvidenceException("Canonical initialization requires the exact authored source graph", ProcessorErrorCategory.InvalidProcessingDocument);
            }
        }
        currentBindings = new ArrayList<ManagedOccurrenceBinding>(before.finalizedGraph().bindings());
        latestBodies.clear();
        for (FinalizedDocumentEvidence document : before.documents().values())
            if (document.hasResidentBody()) latestBodies.put(document.documentId(), document.document());
        currentFinalization = before;
        currentSnapshot = snapshot(before);
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
        List<DirectLogicalDelivery> effectiveDeliveries = new ArrayList<DirectLogicalDelivery>();
        for (DirectLogicalDelivery delivery : input.directDeliveries()) {
            if (!failedSources.containsKey(delivery.targetDocumentId())
                    && !managedReactionByConsumer.containsKey(delivery.targetDocumentId())) effectiveDeliveries.add(delivery);
        }
        List<ClosureWorkOccurrence> directSeeds =
                ClosureDirectSeedPlanner.plan(
                        input.invocationIdentity(),
                        cause.eventBlueId(),
                        directSeedComponents(),
                        effectiveDeliveries);
        if (sameOrigin != null) {
            Map<String, DirectLogicalDelivery> originalDeliveries = new HashMap<String, DirectLogicalDelivery>();
            for (DirectLogicalDelivery delivery : effectiveDeliveries) originalDeliveries.put(IDENTITIES.directDeliveryIdentity(delivery), delivery);
            List<ClosureWorkOccurrence> stableSeeds = new ArrayList<ClosureWorkOccurrence>();
            for (ClosureWorkOccurrence seed : directSeeds) {
                DirectLogicalDelivery delivery = originalDeliveries.get(seed.sourceOccurrenceIdentity());
                String source = ownDirectIdentity(delivery);
                seedDirectSites.put(delivery.targetDocumentId().value() + ":" + delivery.rawOccurrenceOrder(), source);
                stableSeeds.add(new ClosureWorkOccurrence(seed.ordinal(), seed.kind(), seed.targetDocumentId(), seed.channelKey(),
                        cause.eventBlueId(), Long.valueOf(0L), seed.targetManagedScopeIdentity(), source,
                        ownWorkIdentity(seed.ordinal(), seed.kind(), seed.targetDocumentId(), seed.channelKey(), source)));
            }
            directSeeds = stableSeeds;
            originalDirectSeeds.addAll(directSeeds);
            for (DirectLogicalDelivery delivery : effectiveDeliveries) originalDirectDeliveries.put(ownDirectIdentity(delivery), delivery);
            requireWorkOccurrenceCount(directSeeds.size());
            nextWorkOrdinal = directSeeds.size();
            // Planning the exact canonical seed order is uncharged. Admission, frozen Phase-B
            // classification and queue charges belong to each seed's first actual execution site.
            for (ClosureWorkOccurrence seed : directSeeds) {
                recorder.accepted(seed);
                pendingByIdentity.put(seed, new PendingWork(seed, cause.event(), null, null, null, null));
                sameOriginWorkQueue.addLast(seed);
            }
            drainCausalWork();
            return;
        }
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
        for (DirectLogicalDelivery delivery : effectiveDeliveries) {
            String sourceIdentity = ownDirectIdentity(delivery);
            ClosureWorkOccurrence seed = seedBySource.get(sourceIdentity);
            if (seed == null) {
                throw new IllegalStateException(
                        "Direct-seed plan lost a frozen logical delivery");
            }
            ManagedExternalDeliveryClassification classification =
                    stepProcessor.classifyExternalDelivery(
                            sourceAdmissionBody(delivery.targetDocumentId()),
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
                    seed,
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

    /** Planning reads the original source cut, never the provider's completed SCC shape. */
    private List<ComponentSnapshot> directSeedComponents() {
        if (sourceCursors.isEmpty()) return currentSnapshot.components();
        Set<DocumentId> retainedOwners = new HashSet<>();
        Map<DocumentId, ComponentSnapshot> componentsByMember = new HashMap<>();
        List<ManagedOccurrenceBinding> bindings = new ArrayList<>();
        for (SourceCursor cursor : sourceCursors) {
            if (cursor.program.sourceBeforeComponents().isEmpty()) continue;
            retainedOwners.addAll(cursor.program.ownedDocumentIds());
            bindings.addAll(cursor.program.sourceBeforeBindings());
            for (ComponentSnapshot component : cursor.program.sourceBeforeComponents())
                for (DocumentId member : component.orderedMemberDocumentIds()) componentsByMember.put(member, component);
        }
        if (retainedOwners.isEmpty()) return currentSnapshot.components();
        for (ManagedOccurrenceBinding binding : currentBindings)
            if (!retainedOwners.contains(binding.sourceDocumentId())) bindings.add(binding);
        for (ComponentSnapshot component : currentSnapshot.components()) {
            if (Collections.disjoint(component.orderedMemberDocumentIds(), retainedOwners))
                for (DocumentId member : component.orderedMemberDocumentIds()) componentsByMember.put(member, component);
        }
        List<ComponentSnapshot> ordered = new ArrayList<>();
        for (List<DocumentId> members : new SccPartitioner().partition(ManagedDocumentGraph.fromBindings(inputGraph.documentIds(), bindings))) {
            ComponentSnapshot component = componentsByMember.get(members.get(0));
            if (component == null || !component.orderedMemberDocumentIds().equals(members))
                throw new InvalidExecutionEvidenceException("Retained sources do not establish one exact original direct-seed graph",
                        ProcessorErrorCategory.InvalidProcessingDocument);
            ordered.add(component);
        }
        return ordered;
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
                work,
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
                            document.documentId())
                    && !sourcesByDocument.containsKey(document.documentId())) {
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
                            currentBindings, currentSnapshot.pinnedOccurrences(), currentSnapshot.reusableComponents()));
        } finally {
            recorder.endComponentFinalizationProof(finalizationStarted);
        }
        Map<DocumentId, Node> normalizedInput = cloneBodies(latestBodies);
        for (ManagedOccurrenceBinding binding : currentBindings) {
            if (binding.active() && normalizedInput.containsKey(binding.sourceDocumentId())) {
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
                    || document.hasResidentBody() != exact.hasResidentBody()
                    || document.hasResidentBody() && !sameNode(
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
            // The grouped API may carry exact preparation evidence for a potential creation
            // that its workflow never reaches (or rolls back). A frozen selection is not an
            // instruction to initialize/publish that lineage independently of its creator.
            if (sameOrigin != null && isInactiveProspectiveTarget(document.documentId())
                    && attachmentPolicy.entries().stream().anyMatch(selection -> selection.targetLineage().equals(document.documentId()))) {
                boolean survivingActivation = currentBindings.stream().anyMatch(binding -> binding.active()
                        && binding.targetDocumentId().equals(document.documentId())
                        && !sameOrigin.failedCandidate(binding.sourceDocumentId()));
                if (!survivingActivation) continue;
            }
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
            Node resident = latestBodies.get(document.documentId());
            target.put(document.documentId(), resident != null
                    ? stepProcessor.projectRootChannelSurface(resident)
                    : document.rootMetadata().orElseThrow(() -> new blue.language.processor.ExecutionEvidenceUnavailableException(
                            "Exact Root channel metadata is not resident", Collections.singletonList(document.blueId())))
                            .surface().channelOccurrences());
        }
    }

    private void settleCheckpointBarrier() {
        Set<DocumentId> processedDocuments = new LinkedHashSet<DocumentId>();
        for (DocumentTransitionEvidence transition : transitionEvidence) {
            processedDocuments.add(transition.documentId());
        }
        installRetainedSourceSettlement();
        final Map<String, ManagedDocumentSnapshot> documentsByScopeIdentity =
                new LinkedHashMap<String, ManagedDocumentSnapshot>();
        List<ManagedCheckpointSettlementRequest> requests =
                new ArrayList<ManagedCheckpointSettlementRequest>();
        for (ManagedDocumentSnapshot document
                : currentSnapshot.managedDocuments()) {
            if (!processedDocuments.contains(document.documentId()) || isRetainedSource(document.documentId()) || document.terminated()) {
                // Cohort membership is not source work. An untouched source
                // may intentionally retain a checkpoint frozen before its
                // last Channel-catalog change; another Root's operation must
                // not clean that immutable source state. Cyclic reference
                // re-encoding alone is also not owned work. Every actual
                // managed step, including a no-op, records transition evidence
                // and still receives settlement here.
                continue;
            }
            if (sameOrigin != null && (sameOrigin.failedCandidate(document.documentId())
                    || settledCheckpointTokens.contains(seedStreams.get(document.documentId()).originalAttemptToken))) continue;
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
        ManagedCheckpointBatchCleanupContextFactory cleanup =
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
                        };
        List<List<ManagedCheckpointSettlementRequest>> batches = new ArrayList<List<ManagedCheckpointSettlementRequest>>();
        if (sameOrigin == null) {
            batches.add(requests);
        } else {
            Map<SameOriginAttemptCoordinator.Attempt, List<ManagedCheckpointSettlementRequest>> byGroup =
                    new LinkedHashMap<SameOriginAttemptCoordinator.Attempt, List<ManagedCheckpointSettlementRequest>>();
            for (ManagedCheckpointSettlementRequest request : requests) {
                SameOriginAttemptCoordinator.Attempt group = sameOrigin.attempt(new DocumentId(request.revalidationAttribution().documentId()));
                byGroup.computeIfAbsent(group, ignored -> new ArrayList<ManagedCheckpointSettlementRequest>()).add(request);
            }
            batches.addAll(byGroup.values());
            Map<SameOriginAttemptCoordinator.Attempt, Integer> order = new java.util.IdentityHashMap<>();
            int ordinal = 0;
            for (SameOriginAttemptCoordinator.Attempt attempt : dependencyOrderedAttempts()) order.put(attempt, ordinal++);
            batches.sort(Comparator.comparingInt(batch -> order.get(sameOrigin.attempt(
                    new DocumentId(batch.get(0).revalidationAttribution().documentId())))));
        }
        boolean mutated = false;
        for (List<ManagedCheckpointSettlementRequest> batch : batches) {
        SameOriginAttemptCoordinator.Attempt checkpointGroup = sameOrigin == null || batch.isEmpty() ? null
                : sameOrigin.attempt(new DocumentId(batch.get(0).revalidationAttribution().documentId()));
        try {
        ManagedCheckpointSettlementBatch settlement = stepProcessor.settleCheckpointBatch(batch, cleanup);
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
        mutated |= !settlement.mutations().isEmpty();
        if (sameOrigin != null && !settlement.mutations().isEmpty())
            finalizeTentative(TentativeFinalization.Boundary.checkpointSettlement(), null, null);
        if (checkpointGroup != null) markCheckpointSettled(checkpointGroup);
        } catch (GasLimitExceededException rejected) {
            if (sameOrigin == null || rejected.chargeContext().documentId() == null) throw rejected;
            DocumentId failed = new DocumentId(rejected.chargeContext().documentId());
            failSameOriginAttempt(failed, checkpointFailureSite(failed), ProcessorStatus.GAS_LIMIT_EXCEEDED,
                    ProcessorDiagnostic.of(ProcessorErrorCategory.GasLimitExceeded, rejected.getMessage()), null, rejected);
            // A dependent reference's finalization may fail after the producer's own checkpoint
            // has fully finalized. Exact rollback reconstructs the failed scope only; its valid
            // producer is retained and must not repeat admitted checkpoint work on the retry.
            if (checkpointGroup != null && checkpointGroup.state() != SameOriginAttemptCoordinator.State.INVALIDATED
                    && checkpointGroup.failure() == null) markCheckpointSettled(checkpointGroup);
            retryCheckpointSettlement = true;
            return;
        }
        }
        if (mutated && sameOrigin == null) {
            finalizeTentative(
                    TentativeFinalization.Boundary
                            .checkpointSettlement(),
                    null,
                    null);
        }
    }

    private void markCheckpointSettled(SameOriginAttemptCoordinator.Attempt group) {
        for (DocumentId member : group.members()) settledCheckpointTokens.add(seedStreams.get(member).originalAttemptToken);
    }

    private String checkpointFailureSite(DocumentId owner) {
        for (int index = transitionEvidence.size() - 1; index >= 0; index--) {
            DocumentTransitionEvidence transition = transitionEvidence.get(index);
            if (transition.documentId().equals(owner))
                return IDENTITIES.observationEntrySiteIdentity(transition.workOccurrenceIdentity());
        }
        return IDENTITIES.observationEntrySiteIdentity(seedStreams.get(owner).identity.identity());
    }

    private List<SameOriginAttemptCoordinator.Attempt> dependencyOrderedAttempts() {
        List<SameOriginAttemptCoordinator.Attempt> attempts = sameOrigin.activeAttempts();
        Map<SameOriginAttemptCoordinator.Attempt, Integer> counts = new java.util.IdentityHashMap<>();
        Map<SameOriginAttemptCoordinator.Attempt, List<SameOriginAttemptCoordinator.Attempt>> reverse = new java.util.IdentityHashMap<>();
        java.util.SortedSet<SameOriginAttemptCoordinator.Attempt> ready = new java.util.TreeSet<>(Comparator.comparing(SameOriginAttemptCoordinator.Attempt::firstDocument));
        for (SameOriginAttemptCoordinator.Attempt attempt : attempts) {
            Set<SameOriginAttemptCoordinator.Attempt> sources = Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            sources.addAll(attempt.consumedIndependentSources().values()); counts.put(attempt, sources.size());
            if (sources.isEmpty()) ready.add(attempt);
            for (SameOriginAttemptCoordinator.Attempt source : sources) reverse.computeIfAbsent(source, ignored -> new ArrayList<>()).add(attempt);
        }
        List<SameOriginAttemptCoordinator.Attempt> ordered = new ArrayList<>();
        while (!ready.isEmpty()) {
            SameOriginAttemptCoordinator.Attempt next = ready.first(); ready.remove(next); ordered.add(next);
            for (SameOriginAttemptCoordinator.Attempt consumer : reverse.getOrDefault(next, Collections.emptyList())) {
                int remaining = counts.get(consumer) - 1; counts.put(consumer, remaining);
                if (remaining == 0) ready.add(consumer);
            }
        }
        if (ordered.size() != attempts.size()) throw new IllegalStateException("Checkpoint settlement found an unadmitted independent feedback cycle");
        return ordered;
    }

    private void installRetainedSourceSettlement() {
        boolean changed = false;
        Map<DocumentId, SourceObservationProgram.SourceState> authoritative =
                new LinkedHashMap<DocumentId, SourceObservationProgram.SourceState>();
        for (SourceCursor cursor : sourceCursors) {
            for (SourceObservationProgram.SourceState document : cursor.program.sourceResults()) {
                if (sourcesByDocument.get(document.documentId()) != cursor) continue;
                authoritative.put(document.documentId(), document);
                if (!sameNode(latestBodies.get(document.documentId()), document.document())) {
                    latestBodies.put(document.documentId(), document.document());
                    changed = true;
                }
            }
        }
        if (changed) {
            try {
                for (ManagedOccurrenceBinding binding : currentBindings) {
                    if (!binding.active() || !authoritative.containsKey(binding.sourceDocumentId())) continue;
                    Node value = NodePathEditor.getOrNull(latestBodies.get(binding.sourceDocumentId()), binding.sourcePath());
                    SourceObservationProgram.SourceState target = authoritative.get(binding.targetDocumentId());
                    if (value != null && value.isReferenceOnly() && target != null
                            && target.blueId().equals(value.getBlueId())) {
                        projectedReferenceValues.put(binding.occurrenceIdentity(), value.getBlueId());
                        Set<String> values = projectedReferenceTargets.get(binding.targetDocumentId());
                        if (values == null) {
                            values = new LinkedHashSet<String>();
                            projectedReferenceTargets.put(binding.targetDocumentId(), values);
                        }
                        values.add(value.getBlueId());
                    }
                }
                finalizeTentative(TentativeFinalization.Boundary.checkpointSettlement(), null, null);
                for (SourceObservationProgram.SourceState source : authoritative.values()) {
                    ManagedDocumentSnapshot exact = currentSnapshot.managedDocument(source.documentId());
                    if (!source.blueId().equals(exact.blueId()) || !sameNode(source.document(), exact.document())) {
                        throw new IllegalArgumentException("Retained source settlement does not match canonical component evidence");
                    }
                }
            } finally {
                projectedReferenceValues.clear();
                projectedReferenceTargets.clear();
            }
        }
    }

    private GasChargeContext settlementContext(
            ManagedDocumentSnapshot document) {
        ManagedDocumentSnapshot current = currentSnapshot.managedDocument(
                document.documentId());
        return sameOriginContext(GasChargeContext.closure(
                document.documentId().value(),
                "/",
                Long.valueOf(0L),
                Long.valueOf(current.componentGeneration()),
                null,
                null,
                null,
                "checkpoint-settlement.0"));
    }

    private GasChargeContext checkpointWriteContext(
            ManagedDocumentSnapshot document,
            String rawChannelKey,
            long rawOccurrenceOrder) {
        ManagedDocumentSnapshot current = currentSnapshot.managedDocument(
                document.documentId());
        return sameOriginContext(GasChargeContext.closure(
                document.documentId().value(),
                "/",
                Long.valueOf(0L),
                Long.valueOf(current.componentGeneration()),
                rawChannelKey,
                null,
                null,
                "checkpoint-settlement.0.write."
                                    + rawOccurrenceOrder));
    }

    private GasChargeContext checkpointCleanupContext(
            ManagedDocumentSnapshot document,
            String rawChannelKey,
            long cleanupOrdinal) {
        ManagedDocumentSnapshot current = currentSnapshot.managedDocument(
                document.documentId());
        return sameOriginContext(GasChargeContext.closure(
                document.documentId().value(),
                "/",
                Long.valueOf(0L),
                Long.valueOf(current.componentGeneration()),
                rawChannelKey,
                null,
                null,
                "checkpoint-settlement.0.cleanup."
                        + cleanupOrdinal));
    }

    private void chargeSameOriginAdmission(ComponentSnapshot component) {
        Set<DocumentId> members = new LinkedHashSet<DocumentId>(component.orderedMemberDocumentIds());
        ManagedDocumentSnapshot representative = input.snapshot().managedDocument(component.orderedMemberDocumentIds().get(0));
        charge("processor", "processInvocation", 1L, documentContext(representative, null, "admission.process"));
        charge("processor", "closureInvocation", 1L, documentContext(representative, null, "admission.closure"));
        for (DirectLogicalDelivery delivery : input.directDeliveries()) {
            if (members.contains(delivery.targetDocumentId())) charge("processor", "deliverySnapshotEntry", 1L,
                    documentContext(input.snapshot().managedDocument(delivery.targetDocumentId()), delivery.channelKey(), "admission.direct-deliveries"));
        }
        for (DocumentId member : component.orderedMemberDocumentIds()) {
            charge("processor", "managedDocumentOpened", 1L,
                    documentContext(input.snapshot().managedDocument(member), null, "admission.document." + member.value()));
        }
        for (ManagedOccurrenceBinding binding : input.snapshot().occurrences()) {
            if (!members.contains(binding.sourceDocumentId())) continue;
            ManagedDocumentSnapshot source = input.snapshot().managedDocument(binding.sourceDocumentId());
            charge("processor", "managedOccurrenceBindingVerified", 1L, documentContext(source, null, "admission.binding." + binding.occurrenceIdentity()));
            if (binding.active()) charge("processor", "processEmbeddedEdgeExamined", 1L, documentContext(source, null, "admission.edge." + binding.occurrenceIdentity()));
        }
        for (DocumentId member : component.orderedMemberDocumentIds()) charge("processor", "componentMemberPartitioned", 1L,
                documentContext(input.snapshot().managedDocument(member), null, "admission.component-member"));
    }

    private void ensureSameOriginAdmission(DocumentId document) {
        SeedStream stream = seedStreams.get(document);
        if (stream == null) throw new IllegalArgumentException("Semantic charge has no original admitted seed");
        if (!admittedSeedTokens.contains(stream.originalAttemptToken)) {
            verifyFreshSourceAdmission(originalSeedComponents.get(document).orderedMemberDocumentIds());
            admittedSeedTokens.add(stream.originalAttemptToken);
            chargeSameOriginAdmission(originalSeedComponents.get(document));
        }
    }

    private void chargeAdmission() {
        if (sameOrigin != null) return; // First canonical touch owns each original attempt's admission.
        if (sameOrigin == null) {
            charge("processor", "processInvocation", 1L,
                    GasChargeContext.reason("admission.process"));
            charge("processor", "closureInvocation", 1L,
                    GasChargeContext.reason("admission.closure"));
        } else {
            for (ComponentSnapshot component : currentSnapshot.components()) {
                ManagedDocumentSnapshot representative = currentSnapshot.managedDocument(component.orderedMemberDocumentIds().get(0));
                charge("processor", "processInvocation", 1L, documentContext(representative, null, "admission.process"));
                charge("processor", "closureInvocation", 1L, documentContext(representative, null, "admission.closure"));
            }
        }
        long ownDeliveries = input.directDeliveries().stream()
                .filter(d -> !isRetainedSource(d.targetDocumentId()) && !failedSources.containsKey(d.targetDocumentId())).count();
        if (sameOrigin != null) {
            for (DirectLogicalDelivery delivery : input.directDeliveries()) {
                charge("processor", "deliverySnapshotEntry", 1L,
                        documentContext(currentSnapshot.managedDocument(delivery.targetDocumentId()), delivery.channelKey(), "admission.direct-deliveries"));
            }
        } else if (ownDeliveries != 0L) {
            charge("processor", "deliverySnapshotEntry",
                    ownDeliveries,
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
        while (!causalWorkEmpty() || !eventQueue.isEmpty() || !sameOriginReconstruction.isEmpty()) {
            reconstructSameOriginOwnSeeds();
            if (!causalWorkEmpty()) {
                ClosureWorkOccurrence work = sameOrigin == null ? workQueue.dequeue() : sameOriginWorkQueue.removeFirst();
                if (sameOrigin != null && sameOrigin.failedCandidate(work.targetDocumentId())) {
                    pendingByIdentity.remove(work);
                    continue;
                }
                if (sameOrigin == null) charge("processor", "closureWorkOccurrenceDequeued", 1L,
                        workContext(work, "work." + work.ordinal() + ".dequeue"));
                PendingWork pending = pendingByIdentity.remove(
                        work);
                if (pending == null) {
                    throw new IllegalStateException(
                            "Accepted closure work has no exact payload");
                }
                if (skipTerminatedWork(pending)) continue;
                executeOne(pending);
            } else if (!eventQueue.isEmpty()) {
                drainOneEvent();
            }
        }
    }

    private boolean causalWorkEmpty() {
        return sameOrigin == null ? workQueue.isEmpty() : sameOriginWorkQueue.isEmpty();
    }

    /** Reconstructs whole discarded attempts; no charge or memo is refunded within the old attempt. */
    private void reconstructSameOriginOwnSeeds() {
        if (sameOriginReconstruction.isEmpty()) return;
        Set<DocumentId> discarded = new LinkedHashSet<DocumentId>(sameOriginReconstruction);
        sameOriginReconstruction.clear();
        Set<DocumentId> due = new LinkedHashSet<DocumentId>();
        for (ClosureWorkOccurrence seed : originalDirectSeeds) if (discarded.contains(seed.targetDocumentId())) due.add(seed.targetDocumentId());
        if (due.isEmpty()) return;
        for (ComponentSnapshot component : input.snapshot().components()) {
            if (!intersects(component.orderedMemberDocumentIds(), due)) continue;
            Map<DocumentId, Node> bodies = new LinkedHashMap<DocumentId, Node>();
            for (DocumentId member : component.orderedMemberDocumentIds()) bodies.put(member, input.snapshot().managedDocument(member).document());
            SeedStream stream = new SeedStream(sameOriginSeedFactory.of(component.orderedMemberDocumentIds()),
                    finalizationGas.existingIdentities(bodies));
            for (DocumentId member : component.orderedMemberDocumentIds()) {
                seedStreams.put(member, stream);
                sameOriginRolledBack.remove(member);
            }
        }
        ExternalEventCause cause = (ExternalEventCause) input.cause();
        for (ClosureWorkOccurrence original : originalDirectSeeds) {
            if (!due.contains(original.targetDocumentId())) continue;
            DirectLogicalDelivery delivery = originalDirectDeliveries.get(original.sourceOccurrenceIdentity());
            long ordinal = nextWorkOrdinal();
            ClosureWorkOccurrence seed = new ClosureWorkOccurrence(ordinal, original.kind(), original.targetDocumentId(), original.channelKey(),
                    original.eventBlueId(), original.occurrenceOrdinal(), original.targetManagedScopeIdentity(), original.sourceOccurrenceIdentity(),
                    ownWorkIdentity(ordinal, original.kind(), original.targetDocumentId(), original.channelKey(), original.sourceOccurrenceIdentity()));
            recorder.accepted(seed);
            pendingByIdentity.put(seed, new PendingWork(seed, cause.event(), null, null, null, null));
            sameOriginWorkQueue.addLast(seed);
        }
        Map<String, Integer> canonicalOrder = new HashMap<String, Integer>();
        for (int index = 0; index < originalDirectSeeds.size(); index++) canonicalOrder.put(originalDirectSeeds.get(index).sourceOccurrenceIdentity(), index);
        List<ClosureWorkOccurrence> pending = new ArrayList<ClosureWorkOccurrence>(sameOriginWorkQueue);
        pending.sort(Comparator.comparingInt(work -> canonicalOrder.get(work.sourceOccurrenceIdentity())));
        sameOriginWorkQueue.clear(); sameOriginWorkQueue.addAll(pending);
    }

    private void executeOne(PendingWork pending) {
        if (sameOrigin == null) {
            executeOneBody(pending);
            return;
        }
        if (sourceCursorFor(pending.work) != null) {
            if (pending.work.kind() == WorkKind.EXTERNAL_DELIVERY && pending.checkpointCandidate == null) {
                DirectLogicalDelivery delivery = originalDirectDeliveries.get(pending.work.sourceOccurrenceIdentity());
                ManagedCheckpointCandidate candidate = requireAcceptedDirect(delivery, stepProcessor.classifyExternalDelivery(
                        sourceAdmissionBody(delivery.targetDocumentId()), delivery.channelKey(), ((ExternalEventCause) input.cause()).event(),
                        workContext(pending.work, "direct-admission." + delivery.rawOccurrenceOrder() + ".classification")));
                pending = new PendingWork(pending.work, candidate.exactPayload(), null, null, candidate, Long.valueOf(delivery.rawOccurrenceOrder()));
            }
            executeOneBody(pending);
            return;
        }
        if (sameOrigin.failedCandidate(pending.work.targetDocumentId())) return;
        for (ManagedOccurrenceBinding binding : currentBindings) {
            if (binding.active() && binding.sourceDocumentId().equals(pending.work.targetDocumentId())
                    && failedSources.containsKey(binding.targetDocumentId()))
                observesSource(binding.sourceDocumentId(), binding.targetDocumentId(), entrySite(pending.work));
            if (binding.active() && binding.sourceDocumentId().equals(pending.work.targetDocumentId())
                    && sameOrigin.failedCandidate(binding.targetDocumentId())) {
                sameOrigin.consumesFailedDisposition(pending.work.targetDocumentId(), binding.targetDocumentId(), entrySite(pending.work));
                recordSourceObservation(pending.work.targetDocumentId(), binding.targetDocumentId());
            }
        }
        Object executingToken = seedStreams.get(pending.work.targetDocumentId()).originalAttemptToken;
        try {
            if (pending.work.kind() == WorkKind.EXTERNAL_DELIVERY && pending.checkpointCandidate == null) {
                DirectLogicalDelivery delivery = originalDirectDeliveries.get(pending.work.sourceOccurrenceIdentity());
                ManagedCheckpointCandidate candidate = requireAcceptedDirect(delivery, stepProcessor.classifyExternalDelivery(
                        input.snapshot().managedDocument(delivery.targetDocumentId()).document(), delivery.channelKey(),
                        ((ExternalEventCause) input.cause()).event(),
                        workContext(pending.work, "direct-admission." + delivery.rawOccurrenceOrder() + ".classification")));
                pending = new PendingWork(pending.work, candidate.exactPayload(), null, null, candidate, Long.valueOf(delivery.rawOccurrenceOrder()));
                charge("processor", "closureWorkOccurrenceEnqueued", 1L, workContext(pending.work,
                        "work." + pending.work.ordinal() + ".seed-enqueue"));
            }
            charge("processor", "closureWorkOccurrenceDequeued", 1L,
                    workContext(pending.work, "work." + pending.work.ordinal() + ".dequeue"));
            executeOneBody(pending);
        } catch (GasLimitExceededException rejection) {
            if (invalidatedSeedTokens.contains(executingToken)) return;
            String chargedOwner = rejection.chargeContext().documentId();
            if (chargedOwner != null && !sameOrigin.attempt(pending.work.targetDocumentId()).members().contains(new DocumentId(chargedOwner))) {
                throw rejection;
            }
            failSameOriginAttempt(pending.work.targetDocumentId(), entrySite(pending.work), ProcessorStatus.GAS_LIMIT_EXCEEDED,
                    ProcessorDiagnostic.of(ProcessorErrorCategory.GasLimitExceeded, rejection.getMessage()), null, rejection);
        } catch (ProcessorFailureException failure) {
            if (invalidatedSeedTokens.contains(executingToken)) return;
            SameOriginAdmissionRejection admission = null;
            Throwable cause = failure;
            Set<Throwable> visited = Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable, Boolean>());
            while (cause != null && visited.add(cause)) {
                if (cause instanceof SameOriginAdmissionRejection) { admission = (SameOriginAdmissionRejection) cause; break; }
                cause = cause.getCause();
            }
            if (admission != null) {
                failSameOriginAttempt(pending.work.targetDocumentId(), admission.site, ProcessorStatus.RUNTIME_FATAL,
                        ProcessorDiagnostic.of(ProcessorErrorCategory.AtomicScopeGasAdmissionFailure, admission.getMessage()), admission.result);
            } else {
                failSameOriginAttempt(pending.work, ProcessorStatus.RUNTIME_FATAL,
                        ProcessorDiagnostic.of(failure.errorCategory(), failure.getMessage()));
            }
        }
    }

    private void failSameOriginAttempt(ClosureWorkOccurrence work, ProcessorStatus status, ProcessorDiagnostic diagnostic) {
        failSameOriginAttempt(work, status, diagnostic, null);
    }

    private void failSameOriginAttempt(ClosureWorkOccurrence work, ProcessorStatus status, ProcessorDiagnostic diagnostic,
            blue.language.processor.GasMeter.MultiGroupJoinResult rejectedAdmission) {
        failSameOriginAttempt(work.targetDocumentId(), entrySite(work), status, diagnostic, rejectedAdmission);
    }

    private void failSameOriginAttempt(DocumentId owner, String site, ProcessorStatus status, ProcessorDiagnostic diagnostic,
            blue.language.processor.GasMeter.MultiGroupJoinResult rejectedAdmission) {
        failSameOriginAttempt(owner, site, status, diagnostic, rejectedAdmission, null);
    }

    private void failSameOriginAttempt(DocumentId owner, String site, ProcessorStatus status, ProcessorDiagnostic diagnostic,
            blue.language.processor.GasMeter.MultiGroupJoinResult rejectedAdmission, GasLimitExceededException rejectedCharge) {
        Set<DocumentId> restore = new LinkedHashSet<DocumentId>(sameOrigin.attempt(owner).members());
        for (SameOriginAttemptCoordinator.Invalidation invalidated : sameOrigin.failed(owner, status, diagnostic, site, rejectedAdmission, rejectedCharge)) {
            for (DocumentId discarded : invalidated.reconstructOwnSeeds)
                invalidatedSeedTokens.add(seedStreams.get(discarded).originalAttemptToken);
            restore.addAll(invalidated.reconstructOwnSeeds);
            sameOriginReconstruction.addAll(invalidated.reconstructOwnSeeds);
        }
        sameOriginRolledBack.addAll(restore);
        restoreSameOriginPredecessors(restore);
    }

    /** Physical reconstruction of exact predecessors; it admits no replacement semantic work. */
    private void restoreSameOriginPredecessors(Set<DocumentId> documents) {
        terminatingDocuments.removeAll(documents);
        terminatedDocuments.removeAll(documents);
        pendingTerminations.removeIf(termination -> documents.contains(termination.documentId));
        terminationRequests.keySet().removeAll(documents);
        for (DocumentId document : documents) {
            latestBodies.put(document, input.snapshot().managedDocument(document).document());
            epochAdvanceDocuments.remove(document);
            completedCheckpointCandidates.remove(document);
            managedRootEventCounts.remove(document);
            sourcePrefixSites.remove(document);
        }
        currentBindings.removeIf(binding -> documents.contains(binding.sourceDocumentId()));
        for (ManagedOccurrenceBinding binding : input.snapshot().occurrences()) {
            if (documents.contains(binding.sourceDocumentId())) currentBindings.add(binding);
        }
        Collections.sort(currentBindings);
        publicEvents.removeIf(event -> documents.contains(event.publicRootDocumentId()));
        for (int index = 0; index < publicEvents.size(); index++) {
            PublicEventOccurrence event = publicEvents.get(index);
            publicEvents.set(index, new PublicEventOccurrence(index, event.eventOccurrenceOrdinal(), event.publicRootDocumentId(),
                    event.eventOccurrenceIdentity(), event.eventBlueId(), event.event()));
        }
        managedRootEvents.removeIf(event -> documents.contains(event.sourceDocumentId()));
        eventQueue.removeIf(event -> documents.contains(event.sourceDocumentId));
        transitionEvidence.removeIf(transition -> documents.contains(transition.documentId()));
        occurrenceRetirements.values().removeIf(transition -> documents.contains(transition.sourceDocumentId()));
        Set<String> restoredScopes = new LinkedHashSet<>();
        for (DocumentId document : documents) restoredScopes.add(IDENTITIES.managedScopeKeyIdentity(ManagedScopeKey.root(document)));
        checkpointMutations.removeIf(mutation -> restoredScopes.contains(mutation.targetManagedScopeIdentity()));
        sameOriginWorkQueue.removeIf(work -> documents.contains(work.targetDocumentId()));
        pendingByIdentity.values().removeIf(pending -> documents.contains(pending.work.targetDocumentId()));
        Map<String, ManagedReadPin> pins = new LinkedHashMap<String, ManagedReadPin>(retainedPins(currentBindings));
        for (ManagedOccurrenceBinding binding : currentBindings) {
            if (!binding.active() || !sameOriginRolledBack.contains(binding.sourceDocumentId())
                    || sameOriginRolledBack.contains(binding.targetDocumentId())) continue;
            if (binding.expectedTargetBlueId().equals(currentSnapshot.managedDocument(binding.targetDocumentId()).blueId())) continue;
            ManagedReadPin pin = rollbackPin(binding);
            pins.put(binding.occurrenceIdentity(), pin);
        }
        ComponentFinalizationResult restored = finalizer.finalizeComponents(new ComponentFinalizationInput(
                inputGraph, inputComponentGenerations, latestBodies, currentBindings, pins, currentSnapshot.reusableComponents()));
        currentBindings = new ArrayList<ManagedOccurrenceBinding>(restored.finalizedGraph().bindings());
        latestBodies.clear();
        for (FinalizedDocumentEvidence document : restored.documents().values())
            if (document.hasResidentBody()) latestBodies.put(document.documentId(), document.document());
        currentFinalization = restored;
        graphGeneration = ClosureGraphGenerationTransition.assign(input.snapshot().graphGeneration(), inputGraph, restored.finalizedGraph());
        currentSnapshot = snapshot(restored);
    }

    private ManagedReadPin rollbackPin(ManagedOccurrenceBinding binding) {
        ManagedReadPin pin = input.snapshot().readPin(binding.targetDocumentId(), binding.expectedTargetBlueId());
        if (pin == null) {
            ManagedDocumentSnapshot exact = input.snapshot().managedDocument(binding.targetDocumentId());
            if (!exact.blueId().equals(binding.expectedTargetBlueId())) throw new IllegalStateException("Rollback lost its exact predecessor read pin");
            blue.language.provider.CyclicSetProof proof = null;
            for (ComponentSnapshot component : input.snapshot().components()) {
                if (component.orderedMemberDocumentIds().contains(exact.documentId())) proof = component.completeCyclicProof();
            }
            pin = ManagedReadPin.fromExactEvidence(exact.documentId(), exact.blueId(), exact.document(), proof);
        }
        retainedReadPins.add(pin);
        return pin;
    }

    private void executeOneBody(PendingWork pending) {
        if (!activeFrames.isEmpty()) {
            throw new IllegalStateException(
                    "Closure work cannot begin inside an active document step");
        }
        ClosureWorkOccurrence work = pending.work;
        SourceCursor selectedSource = sourceCursorFor(work);
        if (selectedSource != null) for (SourceObservationProgram.SkippedWork skipped : selectedSource.program.skippedWork())
            if (skipped.workIdentity().equals(work.workIdentity()))
                throw new IllegalArgumentException("Retained skipped work reached a live document step");
        String entrySite = entrySite(work);
        alignSourceAtEntry(work, entrySite);
        if (sourceCursorFor(work) == null || sourceCursorFor(work).view == null) sourcePrefixSites.put(work.targetDocumentId(), entrySite);
        AffectedClosureSnapshot executionSnapshot = sourceSnapshotFor(work);
        ManagedDocumentSnapshot target = executionSnapshot.managedDocument(
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
                        input, executionSnapshot, work.targetDocumentId()));
        recorder.step(step);
        if (recorder.sourceObservation() != null) {
            if (sameOrigin == null) recorder.sourceObservation().enter(step, entrySite);
            else {
                SourceCursor source = sourceCursorFor(work);
                recorder.sourceObservation().enter(step, entrySite, source != null && source.realm != null
                        ? source.realm.installation.creatorToken : seedStreams.get(work.targetDocumentId()).originalAttemptToken);
            }
        }
        ActiveFrame frame = new ActiveFrame(
                work,
                target.componentGeneration());
        frame.entrySiteIdentity = entrySite;
        activeFrames.addLast(frame);
        LocalDocumentStepResult result;
        boolean observationCompleted = false;
        frame.beginTiming();
        try {
            if (ownedDocuments != null && !ownedDocuments.contains(target.documentId())
                    && sourceCursorFor(work) == null) {
                throw new InvalidExecutionEvidenceException(
                        "Independent dependency requires its retained source program", ProcessorErrorCategory.InvalidProcessingDocument);
            }
            result = sourceCursorFor(work) != null
                    ? interpretSourceStep(step)
                    : stepProcessor.process(step, pending.selectedRoute);
            if ((sameOrigin != null || input.managedReaction().isPresent())
                    && ownedDocuments != null && ownedDocuments.contains(target.documentId())
                    && input.snapshot().managedDocument(target.documentId()).initialized()) {
                epochAdvanceDocuments.add(target.documentId());
            }
            if (recorder.sourceObservation() != null) {
                recorder.sourceObservation().exit(result);
                observationCompleted = true;
            }
        } finally {
            if (recorder.sourceObservation() != null && !observationCompleted) recorder.sourceObservation().abortStep();
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

        SourceCursor executingSource = sourceCursorFor(work);
        boolean privateSourceView = executingSource != null && executingSource.view != null;
        Node staged = latestBodies.get(work.targetDocumentId());
        if (!privateSourceView && !sameNode(staged, result.resultingBody())) {
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
        if (!privateSourceView) recordTransitionEvidence(result);
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

    private boolean isRetainedSource(DocumentId document) {
        return sourcesByDocument.containsKey(document);
    }

    private SourceCursor sourceCursorFor(ClosureWorkOccurrence work) {
        return sourceContextsByWork.containsKey(work) ? sourceContextsByWork.get(work)
                : sourcesByDocument.get(work.targetDocumentId());
    }

    private AffectedClosureSnapshot sourceSnapshotFor(ClosureWorkOccurrence work) {
        SourceCursor cursor = sourceCursorFor(work);
        return cursor != null && cursor.view != null ? cursor.view.snapshot() : currentSnapshot;
    }

    private void bindSourceContext(ClosureWorkOccurrence work, SourceCursor cursor) {
        if (cursor != null && !cursor.program.ownedDocumentIds().contains(work.targetDocumentId()))
            throw new IllegalArgumentException("Retained work context does not own its target lineage");
        if (sourceContextsByWork.containsKey(work) && sourceContextsByWork.get(work) != cursor)
            throw new IllegalStateException("Accepted work cannot change its source interpretation context");
        sourceContextsByWork.put(work, cursor);
        if (cursor != null && cursor.view != null) stepProcessor.retainSourceWork(work.workIdentity());
    }

    /** Read-only source admission does not move any observer's visible pin. */
    private Node sourceAdmissionBody(DocumentId document) {
        SourceCursor cursor = sourcesByDocument.get(document);
        return cursor == null ? latestBodies.get(document) : cursor.before.get(document).document();
    }

    /**
     * A failed observation leaves its old successful pin. The gap is aligned
     * only when this source actually enters the ordinary FIFO, never as a
     * global prefix step before earlier own seeds or another producer.
     */
    private String entrySite(ClosureWorkOccurrence work) {
        SourceCursor cursor = sourceCursorFor(work);
        if (cursor == null) return IDENTITIES.observationEntrySiteIdentity(work.workIdentity());
        if (cursor.nextStep >= cursor.steps.size()) throw new IllegalArgumentException("Source entry has no retained step");
        return cursor.steps.get(cursor.nextStep).entrySiteIdentity();
    }

    private void alignSourceAtEntry(ClosureWorkOccurrence work, String entrySite) {
        SourceCursor cursor = sourceCursorFor(work);
        if (cursor != null && cursor.view != null) return;
        boolean restoreCompletedCut = cursor != null && cursor.entered.isEmpty()
                && cursor.before.keySet().stream().anyMatch(member -> {
                    SourceObservationProgram.SourceState before = cursor.before.get(member);
                    SourceObservationProgram.SourceState after = cursor.after.get(member);
                    ManagedDocumentSnapshot present = input.snapshot().managedDocument(member);
                    return present != null && present.epoch() == after.epoch() && present.blueId().equals(after.blueId())
                            && (before.epoch() != after.epoch() || !before.blueId().equals(after.blueId()));
                });
        boolean firstEntry = cursor != null && cursor.entered.add(work.targetDocumentId());
        SourceObservationProgram.SourceState before = firstEntry ? cursor.before.get(work.targetDocumentId()) : null;
        boolean align = before != null && !sameNode(latestBodies.get(work.targetDocumentId()), before.document());
        if (!align && !restoreCompletedCut && !referenceProjections.containsKey(entrySite)) return;
        ActiveFrame alignment = new ActiveFrame(work, currentSnapshot.managedDocument(work.targetDocumentId()).componentGeneration());
        alignment.entrySiteIdentity = entrySite;
        activeFrames.addLast(alignment);
        alignment.beginTiming();
        try {
            Map<DocumentId, Node> previous = cloneBodies(latestBodies);
            if (restoreCompletedCut) {
                // Restore the authenticated original owning cut together, at its first
                // actual source entry. A final SCC may have different membership and
                // outgoing rows; mixing its final topology with one predecessor body
                // is not a valid interpretation of the retained program.
                currentBindings.removeIf(binding -> cursor.before.containsKey(binding.sourceDocumentId()));
                currentBindings.addAll(cursor.program.sourceBeforeBindings());
                Collections.sort(currentBindings);
                for (SourceObservationProgram.SourceState member : cursor.before.values())
                    latestBodies.put(member.documentId(), member.document());
            } else if (align) latestBodies.put(work.targetDocumentId(), before.document());
            long transitionOrdinal = nextTransitionOrdinal++;
            String transitionIdentity = transitionIdentity(work, alignment.currentPrePatchBlueId, transitionOrdinal);
            List<FinalizationUpdate> updates = finalizeObservationSite(entrySite, work, previous);
            long updateOrdinal = 0L;
            for (FinalizationUpdate update : updates) {
                drainDocumentUpdateRoutes(alignment, update.sourceDocumentId,
                        stepProcessor.classifyFinalizationDocumentUpdateRoutes(
                                latestBodies.get(update.sourceDocumentId), update.sourcePath, update.before, update.after),
                        updateOrdinal++, transitionIdentity, transitionOrdinal);
            }
        } finally {
            ActiveFrame removed = activeFrames.removeLast();
            alignment.endTiming();
            if (removed != alignment) throw new IllegalStateException("Source alignment lost continuation ownership");
        }
    }

    private LocalDocumentStepResult interpretSourceStep(DocumentStepInput current) {
        SourceCursor cursor = sourceCursorFor(current.work());
        if (cursor.nextStep >= cursor.steps.size()) {
            throw new IllegalArgumentException("Source execution requested an unretained step");
        }
        SourceObservationProgram.Step retained =
                cursor.steps.get(cursor.nextStep++);
        if (!retained.targetDocumentId().equals(current.work().targetDocumentId())
                || retained.kind() != current.work().kind()
                || !retained.channelKey().equals(current.work().channelKey())
                || !sameNode(retained.exactPayload(), current.exactPayload())
                || !sameNode(retained.beforeBody(), current.targetDocument().document())) {
            throw new IllegalArgumentException("Retained source step does not match its canonical delivery: expected "
                    + retained.targetDocumentId() + "/" + retained.kind() + "/" + retained.channelKey()
                    + ", actual " + current.work().targetDocumentId() + "/" + current.work().kind() + "/" + current.work().channelKey()
                    + ", payloadMatches=" + sameNode(retained.exactPayload(), current.exactPayload())
                    + ", beforeMatches=" + sameNode(retained.beforeBody(), current.targetDocument().document()));
        }
        if (cursor.view != null) cursor.view.enterStep(retained);
        for (SourceObservationProgram.Action action : retained.actions()) {
            if (cursor.view != null) {
                if (action instanceof SourceObservationProgram.Patch) {
                    SourceObservationProgram.Patch patch = (SourceObservationProgram.Patch) action;
                    List<ManagedOccurrenceBinding> rows = new ArrayList<>();
                    for (ManagedOccurrenceBinding row : cursor.view.bindings()) if (!row.sourceDocumentId().equals(retained.targetDocumentId())) rows.add(row);
                    rows.addAll(Objects.requireNonNull(patch.resultingBindings(), "Original patch topology"));
                    cursor.view.consumeAction(action, rows);
                } else cursor.view.consumeAction(action);
            }
            if (action instanceof SourceObservationProgram.Patch) {
                SourceObservationProgram.Patch patch = (SourceObservationProgram.Patch) action;
                if (!IDENTITIES.observationPatchSiteIdentity(activeFrame().entrySiteIdentity, activeFrame().patchCount + 1L)
                        .equals(patch.siteIdentity())) throw new IllegalArgumentException("Retained patch has a different canonical source site");
                String previousTransition = replayedPatchTransitionIdentity;
                String previousPatchSite = replayedPatchSiteIdentity;
                List<ManagedOccurrenceBinding> previousBindings = replayedPatchBindings;
                replayedPatchTransitionIdentity = patch.transitionIdentity();
                replayedPatchSiteIdentity = patch.siteIdentity();
                replayedPatchBindings = patch.resultingBindings();
                try {
                    afterPatch("/", patch.document(), patch.patch(), patch.updates());
                } finally {
                    replayedPatchTransitionIdentity = previousTransition;
                    replayedPatchSiteIdentity = previousPatchSite;
                    replayedPatchBindings = previousBindings;
                }
            } else if (action instanceof SourceObservationProgram.Enqueue) {
                SourceObservationProgram.Enqueue event = (SourceObservationProgram.Enqueue) action;
                replayedEventOccurrenceIdentity = event.occurrenceIdentity();
                try {
                    onApplicationEvent("/", event.contractKey(), event.event(), event.eventBlueId());
                } finally {
                    replayedEventOccurrenceIdentity = null;
                }
            } else if (action instanceof SourceObservationProgram.TerminationRequest) {
                SourceObservationProgram.TerminationRequest termination = (SourceObservationProgram.TerminationRequest) action;
                onTerminationRequested("/", termination.cause(), termination.reason());
            } else {
                throw new IllegalArgumentException("Unrecognized source observation action");
            }
        }
        if (cursor.view != null) cursor.view.finishStep(retained);
        return new LocalDocumentStepResult(current.work().targetDocumentId(),
                current.work().workIdentity(), current.targetDocument().blueId(),
                retained.resultingBody(), retained.emittedEvents(),
                retained.orderedPatches(), 0L, 0L, retained.identityAffecting());
    }

    private static String projectionKey(SourceObservationProgram.ReferenceProjection projection) {
        return projection.siteIdentity() + ":" + projection.targetDocumentId().value();
    }

    /** Applies only authenticated reference-only mutations at their original producer boundary. */
    private List<FinalizationUpdate> finalizeObservationSite(String siteIdentity, ClosureWorkOccurrence work,
                                                            Map<DocumentId, Node> beforeWorkBoundary) {
        return finalizeObservationSite(siteIdentity, work.targetDocumentId(), TentativeFinalization.Boundary.work(work.ordinal()),
                work, beforeWorkBoundary);
    }

    private List<FinalizationUpdate> finalizeObservationSite(String siteIdentity, DocumentId sourceDocument,
            TentativeFinalization.Boundary boundary, ClosureWorkOccurrence work, Map<DocumentId, Node> beforeWorkBoundary) {
        if (observationSiteIdentity != null) throw new IllegalStateException("Nested uncompleted source finalization site");
        observationSiteIdentity = siteIdentity;
        boundaryReferenceProjections = referenceProjections.containsKey(siteIdentity)
                ? referenceProjections.get(siteIdentity) : Collections.<SourceObservationProgram.ReferenceProjection>emptyList();
        AffectedClosureSnapshot predecessor = currentSnapshot;
        try {
            for (SourceObservationProgram.ReferenceProjection projection : boundaryReferenceProjections) {
                if (!projection.sourceDocumentId().equals(sourceDocument)
                        || !sourcesByDocument.containsKey(projection.targetDocumentId())
                        || !consumedReferenceProjections.add(projectionKey(projection))) {
                    throw new IllegalArgumentException("Reference projection has no unique owning source site");
                }
                ManagedDocumentSnapshot target = currentSnapshot.managedDocument(projection.targetDocumentId());
                if (target == null || !target.blueId().equals(projection.beforeBlueId())
                        || !sameNode(latestBodies.get(target.documentId()), projection.beforeBody().toNode())) {
                    throw new IllegalArgumentException("Reference projection does not continue its exact retained parent view");
                }
                Node reconstructed = projection.beforeBody().toNode();
                for (SourceObservationProgram.ReferenceChange change : projection.changes()) {
                    if (!sameNode(NodePathEditor.getOrNull(reconstructed, change.path()), change.before().toNode())) {
                        throw new IllegalArgumentException("Retained reference projection predecessor mismatch");
                    }
                    ManagedOccurrenceBinding binding = null;
                    for (ManagedOccurrenceBinding candidate : currentBindings) {
                        if (candidate.active() && candidate.sourceDocumentId().equals(target.documentId())
                                && candidate.sourcePath().equals(change.path())) { binding = candidate; break; }
                    }
                    if (binding == null || !binding.expectedTargetBlueId().equals(change.before().getReferenceBlueId())) {
                        throw new IllegalArgumentException("Retained reference projection has no exact active occurrence");
                    }
                    String afterBlueId = change.after().getReferenceBlueId();
                    projectedReferenceValues.put(binding.occurrenceIdentity(), afterBlueId);
                    Set<String> exactTargets = projectedReferenceTargets.get(binding.targetDocumentId());
                    if (exactTargets == null) {
                        exactTargets = new LinkedHashSet<String>();
                        projectedReferenceTargets.put(binding.targetDocumentId(), exactTargets);
                    }
                    exactTargets.add(afterBlueId);
                    NodePathEditor.put(reconstructed, change.path(), change.after().toNode());
                }
                if (!sameNode(reconstructed, projection.afterBody().toNode())) {
                    throw new IllegalArgumentException("A retained reference projection cannot contain business mutations");
                }
                latestBodies.put(target.documentId(), reconstructed);
                // The parent has entered this source operation through its
                // reference continuation before any own handler is entered.
                sourcesByDocument.get(target.documentId()).entered.add(target.documentId());
            }
            List<FinalizationUpdate> updates = finalizeTentative(
                    boundary, work, boundary.kind() == TentativeFinalization.Boundary.Kind.WORK ? beforeWorkBoundary : null);
            if (boundary.kind() != TentativeFinalization.Boundary.Kind.WORK || projectingInitialization != null)
                updates = finalizationUpdates(beforeWorkBoundary);
            for (SourceObservationProgram.ReferenceProjection projection : boundaryReferenceProjections) {
                ManagedDocumentSnapshot target = currentSnapshot.managedDocument(projection.targetDocumentId());
                if (!target.blueId().equals(projection.afterBlueId())
                        || !sameNode(target.document(), projection.afterBody().toNode())) {
                    throw new IllegalArgumentException("Retained reference projection disagrees with exact component finalization");
                }
            }
            captureReferenceProjections(siteIdentity, sourceDocument, predecessor, beforeWorkBoundary, updates);
            for (FinalizationUpdate update : updates) sourcePrefixSites.put(update.sourceDocumentId, siteIdentity);
            return updates;
        } finally {
            observationSiteIdentity = null;
            boundaryReferenceProjections = Collections.emptyList();
            projectedReferenceValues.clear();
            projectedReferenceTargets.clear();
        }
    }

    private void captureReferenceProjections(String siteIdentity, DocumentId source,
            AffectedClosureSnapshot beforeSnapshot, Map<DocumentId, Node> beforeBodies,
            List<FinalizationUpdate> updates) {
        if (recorder.sourceObservation() == null
                || sameOrigin == null && (ownedDocuments == null || ownedDocuments.contains(source))) return;
        Map<DocumentId, List<SourceObservationProgram.ReferenceChange>> byTarget =
                new java.util.TreeMap<DocumentId, List<SourceObservationProgram.ReferenceChange>>();
        for (FinalizationUpdate update : updates) {
            if (update.sourceDocumentId.equals(source)
                    || sameOrigin == null && !ownedDocuments.contains(update.sourceDocumentId)) continue;
            List<SourceObservationProgram.ReferenceChange> changes = byTarget.get(update.sourceDocumentId);
            if (changes == null) {
                changes = new ArrayList<SourceObservationProgram.ReferenceChange>();
                byTarget.put(update.sourceDocumentId, changes);
            }
            changes.add(new SourceObservationProgram.ReferenceChange(update.sourcePath,
                    blue.language.snapshot.FrozenNode.fromResolvedNode(update.before),
                    blue.language.snapshot.FrozenNode.fromResolvedNode(update.after)));
        }
        for (Map.Entry<DocumentId, List<SourceObservationProgram.ReferenceChange>> entry : byTarget.entrySet()) {
            SourceObservationProgram.ReferenceProjection projection = new SourceObservationProgram.ReferenceProjection(
                    siteIdentity, source, entry.getKey(), beforeSnapshot.managedDocument(entry.getKey()).blueId(),
                    currentSnapshot.managedDocument(entry.getKey()).blueId(),
                    blue.language.snapshot.FrozenNode.fromResolvedNode(beforeBodies.get(entry.getKey())),
                    blue.language.snapshot.FrozenNode.fromResolvedNode(latestBodies.get(entry.getKey())), entry.getValue());
            if (sameOrigin == null) recorder.sourceObservation().referenceProjection(projection);
            else recorder.sourceObservation().referenceProjection(projection, seedStreams.get(entry.getKey()).originalAttemptToken);
        }
    }

    private static final class SourceCursor {
        final SourceObservationProgram program;
        SourceInterpretationView view;
        InitializationRealm realm;
        final List<SourceObservationProgram.Step> steps = new ArrayList<SourceObservationProgram.Step>();
        final Map<DocumentId, SourceObservationProgram.SourceState> before = new LinkedHashMap<DocumentId, SourceObservationProgram.SourceState>();
        final Map<DocumentId, SourceObservationProgram.SourceState> after = new LinkedHashMap<DocumentId, SourceObservationProgram.SourceState>();
        final Set<DocumentId> entered = new LinkedHashSet<DocumentId>();
        final Set<String> consumedSkippedWork = new LinkedHashSet<>();
        int nextStep;
        SourceCursor(SourceObservationProgram program) {
            this.program = program;
            for (SourceObservationProgram.SourceState state : program.sourcePredecessors()) {
                if (program.ownedDocumentIds().contains(state.documentId())) before.put(state.documentId(), state);
            }
            for (SourceObservationProgram.SourceState state : program.sourceResults()) {
                if (program.ownedDocumentIds().contains(state.documentId())) after.put(state.documentId(), state);
            }
            for (SourceObservationProgram.Step step : program.steps()) {
                if (program.ownedDocumentIds().contains(step.targetDocumentId())) steps.add(step);
            }
        }
    }

    @Override
    public blue.language.processor.ManagedDocumentResolutionOverlay currentResolutionOverlay(String scopePath) {
        requireRoot(scopePath);
        TentativeResolutionContext context = TentativeResolutionContext.from(input, currentSnapshot,
                activeFrame().work.targetDocumentId());
        Map<DocumentId, Node> resident = context.currentDocuments();
        Map<String, Node> exact = new LinkedHashMap<>();
        for (Map.Entry<DocumentId, String> entry : context.currentBlueIds().entrySet()) {
            Node body = resident.get(entry.getKey());
            if (body != null) exact.put(entry.getValue(), body);
        }
        exact.putAll(context.managedReadExactNodesByBlueId());
        return new blue.language.processor.ManagedDocumentResolutionOverlay(exact,
                context.targetManagedBlueIdsByPath(), context.currentBlueIds().values());
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
        String patchSite = IDENTITIES.observationPatchSiteIdentity(frame.entrySiteIdentity, frame.patchCount);
        if (frame.sourceContext == null || frame.sourceContext.view == null) sourcePrefixSites.put(frame.work.targetDocumentId(), patchSite);
        chargeManagedRevisionReceiptPatch(frame.work);
        long transitionOrdinal = nextTransitionOrdinal++;
        String transitionIdentity = patchSite.equals(replayedPatchSiteIdentity) ? replayedPatchTransitionIdentity : transitionIdentity(
                frame.work,
                frame.currentPrePatchBlueId,
                transitionOrdinal);
        seedTransitionSites.put(Long.valueOf(transitionOrdinal), transitionIdentity);
        if (recorder.sourceObservation() != null) {
            recorder.sourceObservation().action(
                    new SourceObservationProgram.Patch(patchSite, transitionIdentity, currentDocument, patch, updates));
        }
        if (frame.sourceContext != null && frame.sourceContext.view != null) {
            if (recorder.sourceObservation() != null) recorder.sourceObservation().completePatchBindings(
                    Objects.requireNonNull(replayedPatchBindings, "Original initialization patch topology"));
            afterInitializationPatch(frame, patchSite, updates, transitionIdentity, transitionOrdinal);
            currentDocument.replaceWith(frame.sourceContext.view.document(frame.work.targetDocumentId()));
            frame.currentPrePatchBlueId = frame.sourceContext.view.blueId(frame.work.targetDocumentId());
            return;
        }
        Node admitted = Objects.requireNonNull(
                currentDocument, "currentDocument");
        Map<DocumentId, Node> beforeWorkBoundary =
                cloneBodies(latestBodies);
        UpdatePlacementFrame placements = new UpdatePlacementFrame(frame.work.targetDocumentId(), currentSnapshot, currentBindings, beforeWorkBoundary);
        updatePlacementFrames.addLast(placements);
        try {
        latestBodies.put(frame.work.targetDocumentId(), admitted.clone());
        frame.patchBindings = null;
        List<FinalizationUpdate> generatedUpdates = finalizeObservationSite(patchSite, frame.work, beforeWorkBoundary);
        if (recorder.sourceObservation() != null) recorder.sourceObservation().completePatchBindings(
                Objects.requireNonNull(frame.patchBindings, "Owning patch reconciliation rows"));
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
        deliverPlacedDocumentUpdates(frame, patchSite, placements, updates, transitionIdentity, transitionOrdinal, updateOrdinal);
        if (!pendingInitializationInstallations.isEmpty() && !initializationBatchRunning) {
            suspendActiveFrame(frame);
            try {
                runPendingInitializationBatch();
                finishInitializationInstallations();
            } finally {
                resumeActiveFrame(frame);
            }
        }
        currentDocument.replaceWith(
                latestBodies.get(frame.work.targetDocumentId()));
        frame.currentPrePatchBlueId = currentSnapshot.managedDocument(
                frame.work.targetDocumentId()).blueId();
        } finally {
            if (updatePlacementFrames.removeLast() != placements) throw new IllegalStateException("Update placement frames lost nesting order");
        }
    }

    /**
     * One original occurrence uses vertex-simple authored routes (kernel §4),
     * nearest first. New callback patches create independent fresh frames;
     * this is not a receiver-global or business-feedback visited set.
     */
    private void afterInitializationPatch(ActiveFrame frame, String patchSite, List<DocumentUpdateOccurrence> updates,
            String transitionIdentity, long transitionOrdinal) {
        InitializationRealm realm = frame.sourceContext.realm;
        Map<DocumentId, Node> before = cloneBodies(latestBodies);
        for (SourceCursor cursor : realm.programs.values()) for (DocumentId member : cursor.program.ownedDocumentIds())
            before.put(member, cursor.view.document(member));
        Map<DocumentId, List<ManagedOccurrenceBinding>> reverse = new LinkedHashMap<>();
        for (ManagedOccurrenceBinding binding : realm.routes()) if (binding.active())
            reverse.computeIfAbsent(binding.targetDocumentId(), ignored -> new ArrayList<>()).add(binding);
        for (List<ManagedOccurrenceBinding> edges : reverse.values()) edges.sort(ClosureExecutionSession::compareContainingOccurrences);
        long ordinal = 0;
        for (DocumentUpdateOccurrence update : updates) drainDocumentUpdateRoutes(frame, frame.work.targetDocumentId(),
                stepProcessor.classifyDocumentUpdateRoutes(frame.sourceContext.view.document(frame.work.targetDocumentId()), update),
                ordinal++, transitionIdentity, transitionOrdinal);
        realm.projectRetained(patchSite);
        realm.projectSelected(patchSite, frame.work.targetDocumentId(), frame.work);
        Deque<UpdatePlacement> paths = new ArrayDeque<>();
        paths.add(new UpdatePlacement(frame.work.targetDocumentId(), "", Collections.emptyList(), Collections.singleton(frame.work.targetDocumentId())));
        while (!paths.isEmpty()) {
            UpdatePlacement path = paths.removeFirst();
            if (!path.path.isEmpty()) {
                if (!eligibleRealmRoute(realm, path)) continue;
                String sourceSite = IDENTITIES.observationPlacementSiteIdentity(patchSite, path.occurrenceIdentities());
                realm.projectRetained(sourceSite);
                if (realm.source(path.document) == null) charge("processor", "processEmbeddedEdgeExamined", 1L,
                        documentContext(currentSnapshot.managedDocument(path.document), null, "observation-placement." + realm.placementSite(sourceSite)));
                for (DocumentUpdateOccurrence update : updates) {
                    DocumentUpdateOccurrence composed = DocumentUpdateOccurrence.fromRetainedFrozenEvidence(
                            composePath(path.prefix, update.path()), update.frozenBefore(), update.frozenAfter(), update.op(),
                            composePath(path.prefix, update.originScope()), Collections.singletonList("/"), null);
                    drainDocumentUpdateRoutes(frame, path.document,
                            stepProcessor.classifyDocumentUpdateRoutes(before.get(path.document), composed), ordinal++, transitionIdentity, transitionOrdinal);
                }
            }
            for (ManagedOccurrenceBinding edge : reverse.getOrDefault(path.document, Collections.emptyList())) {
                if (path.visited.contains(edge.sourceDocumentId())) continue;
                List<ManagedOccurrenceBinding> extended = new ArrayList<>(path.path); extended.add(edge);
                Set<DocumentId> visited = new LinkedHashSet<>(path.visited); visited.add(edge.sourceDocumentId());
                paths.addLast(new UpdatePlacement(edge.sourceDocumentId(), composePath(edge.sourcePath(), path.prefix), extended, visited));
            }
        }
    }

    private boolean eligibleRealmRoute(InitializationRealm realm, UpdatePlacement path) {
        if (realm.source(path.document) == null && unavailableForOrdinaryDelivery(path.document)) return false;
        List<ManagedOccurrenceBinding> current = realm.routes();
        for (ManagedOccurrenceBinding frozen : path.path) {
            boolean found = false;
            for (ManagedOccurrenceBinding binding : current) if (binding.active()
                    && binding.occurrenceIdentity().equals(frozen.occurrenceIdentity())) { found = true; break; }
            if (!found) return false;
        }
        return true;
    }

    private void deliverPlacedDocumentUpdates(ActiveFrame frame, String patchSite, UpdatePlacementFrame placements,
            List<DocumentUpdateOccurrence> updates, String transitionIdentity, long transitionOrdinal, long updateOrdinal) {
        Deque<UpdatePlacement> routes = new ArrayDeque<UpdatePlacement>();
        routes.add(new UpdatePlacement(frame.work.targetDocumentId(), "", Collections.<ManagedOccurrenceBinding>emptyList(),
                Collections.singleton(frame.work.targetDocumentId())));
        while (!routes.isEmpty()) {
            UpdatePlacement route = routes.removeFirst();
            if (!route.path.isEmpty()) {
                if (!stillEligibleUpdateRoute(route)) continue;
                ManagedOccurrenceBinding incoming = route.path.get(route.path.size() - 1);
                String site = IDENTITIES.observationPlacementSiteIdentity(patchSite, route.occurrenceIdentities());
                try {
                charge("processor", "processEmbeddedEdgeExamined", 1L,
                        documentContext(currentSnapshot.managedDocument(route.document), null, "observation-placement." + site));
                placements.pins.remove(incoming.occurrenceIdentity());
                placements.advanced.add(incoming.occurrenceIdentity());
                Map<DocumentId, Node> before = cloneBodies(latestBodies);
                List<FinalizationUpdate> changes = finalizeObservationSite(site, frame.work, before);
                // The exact managed-revision lane already attributes its ancestor
                // writes in finalization. A placement must not charge that same write twice.
                long placementWrites = changes.stream().filter(change -> !(input.cause() instanceof ManagedRevisionCause)
                        || activateManagedRevision || change.sourceDocumentId.equals(frame.work.targetDocumentId())).count();
                if (placementWrites != 0L) charge("processor", "containingReferenceUpdated", placementWrites,
                        documentContext(currentSnapshot.managedDocument(route.document), null, "observation-placement." + site));
                for (DocumentUpdateOccurrence update : updates) {
                    DocumentUpdateOccurrence composed = DocumentUpdateOccurrence.fromRetainedFrozenEvidence(
                            composePath(route.prefix, update.path()), update.frozenBefore(), update.frozenAfter(), update.op(),
                            composePath(route.prefix, update.originScope()), Collections.singletonList("/"), null);
                    List<ManagedDocumentStepRoute> classified = stepProcessor.classifyDocumentUpdateRoutes(
                            placements.beforeBodies.get(route.document), composed);
                    drainDocumentUpdateRoutes(frame, route.document, classified, updateOrdinal++, transitionIdentity, transitionOrdinal);
                }
                } catch (GasLimitExceededException rejected) {
                    if (!containObservationGasFailure(frame, route.document, site, rejected)) throw rejected;
                    continue;
                }
            }
            List<ManagedOccurrenceBinding> next = placements.reverse.get(route.document);
            if (next == null) continue;
            for (ManagedOccurrenceBinding edge : next) {
                if (route.visited.contains(edge.sourceDocumentId())) continue;
                if (sameOrigin != null && sameOrigin.failedCandidate(edge.sourceDocumentId())) continue;
                List<String> nextCoordinates = route.occurrenceIdentities(); nextCoordinates.add(edge.occurrenceIdentity());
                String site = IDENTITIES.observationPlacementSiteIdentity(patchSite, nextCoordinates);
                observesSource(edge.sourceDocumentId(), edge.targetDocumentId(), site);
                // Expansion is admitted before allocating another authored route prefix.
                try {
                charge("processor", "processEmbeddedEdgeExamined", 1L,
                        documentContext(currentSnapshot.managedDocument(edge.sourceDocumentId()), null,
                                "observation-route." + patchSite + "." + edge.occurrenceIdentity()));
                } catch (GasLimitExceededException rejected) {
                    if (!containObservationGasFailure(frame, edge.sourceDocumentId(), site, rejected)) throw rejected;
                    continue;
                }
                List<ManagedOccurrenceBinding> path = new ArrayList<ManagedOccurrenceBinding>(route.path); path.add(edge);
                Set<DocumentId> visited = new LinkedHashSet<DocumentId>(route.visited); visited.add(edge.sourceDocumentId());
                routes.addLast(new UpdatePlacement(edge.sourceDocumentId(), composePath(edge.sourcePath(), route.prefix), path, visited));
            }
        }
    }

    private boolean containObservationGasFailure(ActiveFrame producer, DocumentId receiver, String site,
            GasLimitExceededException rejection) {
        if (sameOrigin == null) return false;
        String charged = rejection.chargeContext().documentId();
        if (charged != null && !sameOrigin.attempt(receiver).members().contains(new DocumentId(charged))) return false;
        boolean joinedProducer = !isRetainedSource(producer.work.targetDocumentId())
                && sameOrigin.attempt(receiver) == sameOrigin.attempt(producer.work.targetDocumentId());
        failSameOriginAttempt(receiver, site, ProcessorStatus.GAS_LIMIT_EXCEEDED,
                ProcessorDiagnostic.of(ProcessorErrorCategory.GasLimitExceeded, rejection.getMessage()), null, rejection);
        return !joinedProducer;
    }

    private boolean stillEligibleUpdateRoute(UpdatePlacement route) {
        if (unavailableForOrdinaryDelivery(route.document)) return false;
        for (ManagedOccurrenceBinding frozen : route.path) {
            boolean found = false;
            for (ManagedOccurrenceBinding current : currentBindings) {
                if (current.active() && current.occurrenceIdentity().equals(frozen.occurrenceIdentity())
                        && !processEmbeddedRetirementFences.contains(new ProcessEmbeddedSurfaceReconciler.OccurrencePath(
                        current.sourceDocumentId(), current.sourcePath()))) { found = true; break; }
            }
            if (!found) return false;
        }
        return true;
    }

    private static String composePath(String prefix, String suffix) {
        if (prefix.isEmpty() || "/".equals(prefix)) return suffix.isEmpty() ? "/" : suffix;
        return suffix.isEmpty() || "/".equals(suffix) ? prefix : prefix + suffix;
    }

    private final class UpdatePlacementFrame {
        final Map<String, ManagedReadPin> pins = new LinkedHashMap<String, ManagedReadPin>();
        final Set<String> advanced = new LinkedHashSet<String>();
        final Map<DocumentId, List<ManagedOccurrenceBinding>> reverse = new LinkedHashMap<DocumentId, List<ManagedOccurrenceBinding>>();
        final Map<DocumentId, Node> beforeBodies;
        UpdatePlacementFrame(DocumentId source, AffectedClosureSnapshot before, List<ManagedOccurrenceBinding> bindings,
                Map<DocumentId, Node> beforeBodies) {
            this.beforeBodies = beforeBodies;
            Map<DocumentId, ComponentSnapshot> components = new HashMap<DocumentId, ComponentSnapshot>();
            for (ComponentSnapshot component : before.components()) for (DocumentId member : component.orderedMemberDocumentIds()) components.put(member, component);
            for (ManagedOccurrenceBinding binding : bindings) {
                if (!binding.active()) continue;
                ManagedDocumentSnapshot target = before.managedDocument(binding.targetDocumentId());
                if (reactionDeliveryEligible(binding) && binding.expectedTargetBlueId().equals(target.blueId())) {
                    reverse.computeIfAbsent(binding.targetDocumentId(), ignored -> new ArrayList<ManagedOccurrenceBinding>()).add(binding);
                }
            }
            // Only placements reached by this original mutation need sequential alias pins.
            // Unrelated dependency components remain certified headers, not eagerly opened bodies.
            Set<DocumentId> affected = new LinkedHashSet<DocumentId>();
            java.util.ArrayDeque<DocumentId> pending = new java.util.ArrayDeque<DocumentId>();
            affected.add(source); pending.add(source);
            while (!pending.isEmpty()) {
                for (ManagedOccurrenceBinding binding : reverse.getOrDefault(pending.removeFirst(), Collections.emptyList())) {
                    if (affected.add(binding.sourceDocumentId())) pending.addLast(binding.sourceDocumentId());
                }
            }
            for (ManagedOccurrenceBinding binding : bindings) {
                if (!binding.active() || !affected.contains(binding.targetDocumentId())) continue;
                ManagedDocumentSnapshot target = before.managedDocument(binding.targetDocumentId());
                if (components.get(binding.sourceDocumentId()) == components.get(binding.targetDocumentId())) continue;
                ManagedReadPin pin = before.readPin(binding.targetDocumentId(), binding.expectedTargetBlueId());
                if (pin == null && binding.expectedTargetBlueId().equals(target.blueId())) pin = ManagedReadPin.fromExactEvidence(
                        target.documentId(), target.blueId(), target.document(), components.get(target.documentId()).completeCyclicProof());
                if (pin != null) { pins.put(binding.occurrenceIdentity(), pin); retainedReadPins.add(pin); }
            }
            for (List<ManagedOccurrenceBinding> edges : reverse.values()) edges.sort(ClosureExecutionSession::compareContainingOccurrences);
        }

        boolean retainsSelectedView(ManagedOccurrenceBinding binding, ManagedReadPin pin) {
            if (pin == null || !pin.documentId().equals(binding.targetDocumentId())) return false;
            Node current = NodePathEditor.getOrNull(latestBodies.get(binding.sourceDocumentId()), binding.sourcePath());
            if (current == null) return false;
            if (current.isReferenceOnly()) return pin.blueId().equals(current.getBlueId());
            // The original snapshot already verified this materialized placement against
            // the selected exact target. Its unchanged inline representation cannot skip
            // the same sequential alias barrier imposed on an equivalent pure reference.
            Node original = NodePathEditor.getOrNull(beforeBodies.get(binding.sourceDocumentId()), binding.sourcePath());
            return sameNode(current, original);
        }
    }

    private static final class UpdatePlacement {
        final DocumentId document; final String prefix;
        final List<ManagedOccurrenceBinding> path; final Set<DocumentId> visited;
        UpdatePlacement(DocumentId document, String prefix, List<ManagedOccurrenceBinding> path, Set<DocumentId> visited) {
            this.document = document; this.prefix = prefix; this.path = path; this.visited = visited;
        }
        List<String> occurrenceIdentities() {
            List<String> result = new ArrayList<String>();
            for (ManagedOccurrenceBinding binding : path) result.add(binding.occurrenceIdentity());
            return result;
        }
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
            AcceptedInitializationInstallation retained = observationSiteIdentity == null ? null : retainedInitializationInstallations.get(
                    attachmentViewKey(observationSiteIdentity, binding.occurrenceIdentity()));
            SameOriginAttachmentPolicy.Selection selection = retained != null ? retained.selection() : sameOrigin == null ? null
                    : attachmentPolicy.selection(binding.occurrenceIdentity()).orElse(null);
            if (selection != null && selection.mode() == SameOriginAttachmentPolicy.Mode.FULL_HISTORY) {
                String site = Objects.requireNonNull(observationSiteIdentity, "initialization creation site");
                SeedStream creator = sameOrigin == null ? null : seedStreams.get(binding.sourceDocumentId());
                SourceInitialization initialization = retained == null ? offeredInitializations.get(target)
                        : dormantInitializations.get(retained.sourceInitializationOperationIdentity());
                if (initialization == null) throw new ClosureResourceDemandException(Collections.singletonList(
                        new SourceInitializationDemand(selection, binding.sourcePath(), creator.identity.identity(), site,
                                input.environment(), input.executionPolicy())));
                initialization.verifyInstallationOccurrence(currentSnapshot, retained == null ? sameOrigin.attempt(binding.sourceDocumentId()).members()
                                : sourcesByDocument.get(binding.sourceDocumentId()).program.ownedDocumentIds(),
                        binding.occurrenceIdentity(), input.environment(), initializationBases(initialization));
                consultInitialization(binding.sourceDocumentId(), initialization);
                InitializationInstallation installation = new InitializationInstallation(selection,
                        retained == null ? creator.identity.identity() : retained.creatorSeedIdentity(),
                        site, creator == null ? null : creator.originalAttemptToken, initialization);
                installation.retained = retained;
                installation.realm = new InitializationRealm(installation);
                pendingInitializationInstallations.putIfAbsent(binding.occurrenceIdentity(), installation);
                continue;
            }
            if (initializationRequired(target)) {
                pendingInitializationCauses.putIfAbsent(
                        target, causeIdentity);
            }
        }
    }

    private void runPendingInitializationBatch() {
        if (pendingInitializationCauses.isEmpty() && pendingInitializationInstallations.isEmpty()) {
            return;
        }
        if (initializationBatchRunning) {
            throw new IllegalStateException(
                    "Initialization batch re-entered its own work boundary");
        }
        initializationBatchRunning = true;
        try {
            for (InitializationInstallation installation : new ArrayList<>(pendingInitializationInstallations.values()))
                if (installation.realm != null) installation.realm.run();
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

    private void finishInitializationInstallations() {
        if (pendingInitializationInstallations.isEmpty()) return;
        List<InitializationInstallation> completed = new ArrayList<>(pendingInitializationInstallations.values());
        for (InitializationInstallation installation : completed) {
            if (invalidatedSeedTokens.contains(installation.creatorToken)
                    || sameOrigin != null && sameOrigin.failedCandidate(installation.selection.creatorLineage())) continue;
            ManagedOccurrenceBinding selected = null;
            for (ManagedOccurrenceBinding binding : currentBindings)
                if (binding.occurrenceIdentity().equals(installation.selection.occurrenceIdentity())) { selected = binding; break; }
            if (selected == null || !selected.active()) continue; // Retired within the creating operation: no publication/lane.
            DocumentId target = selected.targetDocumentId();
            ManagedReadPin pin = installation.realm.source(target).view.selectedPin(target);
            AcceptedInitializationInstallation accepted = AcceptedInitializationInstallation.fromCanonicalSite(
                    installation.selection, installation.creatorSeed, installation.creatorSite,
                    installation.initialization.program().invocationIdentity(),
                    IDENTITIES.observationInitializationCompletionSiteIdentity(installation.initialization.program().invocationIdentity()), pin);
            accepted.verifySourceInitialization(installation.initialization);
            if (installation.retained != null && !installation.retained.identity().equals(accepted.identity()))
                throw new IllegalArgumentException("Replayed initialization differs from its accepted original placement");
            retainedReadPins.add(pin);
            if (installation.retained == null) {
                recorder.sourceObservation().acceptedInitialization(accepted, installation.creatorToken);
                recorder.sourceObservation().borrowedProgram(installation.initialization.program(), installation.creatorToken);
            }
            int index = currentBindings.indexOf(selected);
            currentBindings.set(index, ManagedOccurrenceBinding.derived(selected.bindingPolicyIdentity(), selected.sourceDocumentId(),
                    selected.sourceAddress(), target, pin.blueId(), false, Long.valueOf(0L)));
        }
        pendingInitializationInstallations.clear();
        // Converting the temporary initialization delivery overlay into an inactive history lane
        // changes eligibility, not the already installed exact reference or business value.
        ComponentFinalizationResult restored = finalizer.finalizeComponents(new ComponentFinalizationInput(
                inputGraph, inputComponentGenerations, latestBodies, currentBindings, retainedPins(currentBindings), currentSnapshot.reusableComponents()));
        for (FinalizedDocumentEvidence document : restored.documents().values())
            if (!document.blueId().equals(currentSnapshot.managedDocument(document.documentId()).blueId()))
                throw new InvalidExecutionEvidenceException("Initialization lane eligibility changed exact document state", ProcessorErrorCategory.InvalidProcessingDocument);
        currentFinalization = restored;
        currentBindings = new ArrayList<>(restored.finalizedGraph().bindings());
        graphGeneration = ClosureGraphGenerationTransition.assign(input.snapshot().graphGeneration(), inputGraph, restored.finalizedGraph());
        currentSnapshot = snapshot(restored);
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
        if (initializingSources.contains(documentId)) return true;
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
        return pendingWork(kind, targetDocumentId, channelKey, sourceOccurrenceIdentity, exactPayload,
                occurrenceEvent, processorPatch, sourcesByDocument.get(targetDocumentId));
    }

    private PendingWork pendingWork(WorkKind kind, DocumentId targetDocumentId, String channelKey,
            String sourceOccurrenceIdentity, Node exactPayload, Node occurrenceEvent,
            FrozenJsonPatch processorPatch, SourceCursor sourceContext) {
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
                ownWorkIdentity(ordinal, kind, targetDocumentId, channelKey, sourceOccurrenceIdentity, sourceContext));
        bindSourceContext(work, sourceContext);
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
        PendingWork exact = pendingByIdentity.remove(work);
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
        Map<DocumentId, Node> beforeMarkers = cloneBodies(latestBodies);
        latestBodies.putAll(markedBodies);
        initializedDocuments.addAll(markedBodies.keySet());
        if (initializationBatchLastWorkOrdinal < 0L) {
            throw new IllegalStateException(
                    "Initialization marker batch has no accepted work owner");
        }
        DocumentId completionSource = markedBodies.keySet().iterator().next();
        SourceCursor completionCursor = sourcesByDocument.get(completionSource);
        String sourceInvocation = completionCursor == null ? input.invocationIdentity() : completionCursor.program.invocationIdentity();
        finalizeObservationSite(IDENTITIES.observationInitializationCompletionSiteIdentity(sourceInvocation), completionSource,
                TentativeFinalization.Boundary.initializationBatch(initializationBatchLastWorkOrdinal), null, beforeMarkers);
        for (DocumentId documentId : markedBodies.keySet()) {
            if (!initializingSources.contains(documentId)) continue;
            SourceCursor cursor = sourcesByDocument.get(documentId);
            SourceObservationProgram.SourceState expected = null;
            for (SourceObservationProgram.SourceState state : cursor.program.sourceResults()) {
                if (state.documentId().equals(documentId)) expected = state;
            }
            if (expected == null || !expected.blueId().equals(currentSnapshot.managedDocument(documentId).blueId())
                    || !sameNode(expected.document(), latestBodies.get(documentId))) {
                throw new IllegalArgumentException("Retained canonical initialization did not reach its authenticated component result");
            }
            List<ManagedOccurrenceBinding> expectedBindings = new ArrayList<ManagedOccurrenceBinding>();
            List<ManagedOccurrenceBinding> actualBindings = new ArrayList<ManagedOccurrenceBinding>();
            for (ManagedOccurrenceBinding binding : cursor.program.sourceAfterBindings()) {
                if (binding.sourceDocumentId().equals(documentId)) expectedBindings.add(binding);
            }
            for (ManagedOccurrenceBinding binding : currentBindings) {
                if (binding.sourceDocumentId().equals(documentId)) actualBindings.add(binding);
            }
            if (!IDENTITIES.occurrenceBindingSetIdentity(expectedBindings).equals(IDENTITIES.occurrenceBindingSetIdentity(actualBindings))) {
                throw new IllegalArgumentException("Retained canonical initialization did not reach its authenticated source topology");
            }
        }
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
        String occurrenceIdentity = replayedEventOccurrenceIdentity != null
                ? replayedEventOccurrenceIdentity
                : IDENTITIES.eventOccurrenceIdentity(
                        input.invocationIdentity(), eventOrdinal, eventBlueId);
        if (sameOrigin != null && replayedEventOccurrenceIdentity == null) {
            SeedStream stream = seedStreams.get(frame.work.targetDocumentId());
            occurrenceIdentity = stream.identity.eventIdentity(stream.eventOrdinal++, frame.work.targetDocumentId(), frame.work.workIdentity(), eventBlueId);
        }
        seedEventSites.put(Long.valueOf(eventOrdinal), occurrenceIdentity);
        if (frame.sourceContext == null || frame.sourceContext.view == null) sourcePrefixSites.put(frame.work.targetDocumentId(), occurrenceIdentity);
        if (recorder.sourceObservation() != null) {
            recorder.sourceObservation().action(new SourceObservationProgram.Enqueue(
                    originContractKey, exactEvent, eventBlueId, occurrenceIdentity));
        }
        ManagedDocumentSnapshot emitter = sourceSnapshotFor(frame.work).managedDocument(
                frame.work.targetDocumentId());
        Long count = managedRootEventCounts.get(emitter.documentId());
        long receiptOrdinal = count == null ? 0L : count.longValue();
        if (frame.sourceContext == null) managedRootEventCounts.put(
                emitter.documentId(), Long.valueOf(receiptOrdinal + 1L));
        if (frame.sourceContext == null) {
            managedRootEvents.add(new ManagedRootEventOccurrence(
                    receiptOrdinal,
                    eventOrdinal,
                    emitter.documentId(),
                    occurrenceIdentity,
                    eventBlueId,
                    exactEvent,
                    sameOrigin != null || emitter.publicRoot()));
        }
        if ((sameOrigin != null || emitter.publicRoot()) && frame.sourceContext == null) {
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
        ActiveFrame frame = activeFrame();
        SourceCursor sourceContext = sourceCursorFor(frame.work);
        if (sourceContext != null && sourceContext.view != null) {
            throw new InvalidExecutionEvidenceException(
                    "A usable canonical initialization cannot contain a termination request",
                    ProcessorErrorCategory.InvalidProcessingDocument);
        }
        DocumentId documentId = frame.work.targetDocumentId();
        if (terminatingDocuments.contains(documentId)
                || terminatedDocuments.contains(documentId)) {
            return;
        }
        if (cause == null || cause.isEmpty()) {
            throw new IllegalArgumentException(
                    "Termination cause must be non-empty Text");
        }
        if (recorder.sourceObservation() != null)
            recorder.sourceObservation().action(new SourceObservationProgram.TerminationRequest(cause, reason));
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
                sourceContext,
                sameOrigin == null || sourceContext != null ? null : seedStreams.get(documentId).originalAttemptToken,
                acceptTerminationLifecycle(
                        documentId, frame.work, cause, reason));
        pendingTerminations.addLast(termination);
        terminationRequests.put(documentId, termination);
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
                    null, sourceCursorFor(requestWork));
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
                if (!validTermination(termination)) break;
                ClosureWorkOccurrence work = immediate.dequeue();
                if (sameOrigin == null) charge(
                        "processor",
                        "closureWorkOccurrenceDequeued",
                        1L,
                        workContext(
                                work,
                                "work." + work.ordinal() + ".dequeue"));
                PendingWork lifecycle = pendingByIdentity.remove(
                        work);
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
                                    termination.documentId)
                            || !validTermination(termination)) {
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
                settleTerminationMarker(termination, afterWorkOrdinal);
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
                        termination.documentId)
                || !validTermination(termination)) {
            return;
        }
        Map<DocumentId, Node> beforeMarker = cloneBodies(latestBodies);
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
        String site = IDENTITIES.observationTerminationCompletionSiteIdentity(termination.requestWork.workIdentity());
        finalizeObservationSite(site, termination.documentId,
                TentativeFinalization.Boundary.terminationMarker(
                        afterWorkOrdinal),
                termination.requestWork, beforeMarker);
        sourcePrefixSites.put(termination.documentId, site);
    }

    /** A deferred marker still belongs to its initiating attempt, not the outer event's producer. */
    private void settleTerminationMarker(PendingTermination termination, long afterWorkOrdinal) {
        if (sameOrigin == null || termination.sourceContext != null) {
            writeTerminationMarker(termination, afterWorkOrdinal);
            return;
        }
        if (!validTermination(termination)) return;
        String site = IDENTITIES.observationTerminationCompletionSiteIdentity(termination.requestWork.workIdentity());
        try {
            writeTerminationMarker(termination, afterWorkOrdinal);
        } catch (GasLimitExceededException rejected) {
            String chargedOwner = rejected.chargeContext().documentId();
            DocumentId owner = chargedOwner == null ? termination.documentId : new DocumentId(chargedOwner);
            if (!seedStreams.containsKey(owner) || sourcesByDocument.containsKey(owner)) throw rejected;
            failSameOriginAttempt(owner, site, ProcessorStatus.GAS_LIMIT_EXCEEDED,
                    ProcessorDiagnostic.of(ProcessorErrorCategory.GasLimitExceeded, rejected.getMessage()), null, rejected);
        } catch (ProcessorFailureException failure) {
            failSameOriginAttempt(termination.documentId, site, ProcessorStatus.RUNTIME_FATAL,
                    ProcessorDiagnostic.of(failure.errorCategory(), failure.getMessage()), null);
        }
    }

    private boolean validTermination(PendingTermination termination) {
        if (termination.sourceContext != null || sameOrigin == null) return true;
        return !sameOrigin.failedCandidate(termination.documentId)
                && seedStreams.get(termination.documentId).originalAttemptToken == termination.originalAttemptToken
                && !invalidatedSeedTokens.contains(termination.originalAttemptToken);
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
        if (occurrence.sourceContext == null || occurrence.sourceContext.view == null) charge("processor", "internalEventDequeued", 1L,
                sameOrigin == null ? GasChargeContext.reason("event." + occurrence.ordinal + ".dequeue")
                        : documentContext(currentSnapshot.managedDocument(occurrence.sourceDocumentId), null, "event." + occurrence.ordinal + ".dequeue"));
        if (!occurrence.imported) {
            drainOriginalEvent(occurrence);
            return;
        }
        long deliveryOrdinal = 0L;
        ClosureWorkQueue immediate = new ClosureWorkQueue();
        Map<String, Object> immediateTokens = new LinkedHashMap<>();
        for (RouteTarget route : classifyEventRoutes(occurrence)) {
            if (sameOrigin != null && sameOrigin.failedCandidate(route.targetDocumentId)) continue;
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
            try {
                accept(pending, "event." + occurrence.ordinal + ".delivery." + deliveryOrdinal + ".enqueue");
            } catch (GasLimitExceededException rejected) {
                if (sameOrigin == null) throw rejected;
                failSameOriginAttempt(route.targetDocumentId, entrySite(pending.work), ProcessorStatus.GAS_LIMIT_EXCEEDED,
                        ProcessorDiagnostic.of(ProcessorErrorCategory.GasLimitExceeded, rejected.getMessage()), null, rejected);
                continue;
            }
            immediate.enqueue(pending.work);
            if (sameOrigin != null) immediateTokens.put(pending.work.workIdentity(), seedStreams.get(route.targetDocumentId).originalAttemptToken);
            deliveryOrdinal++;
        }
        eventDeliveryBatchDepth++;
        if (occurrence.imported) {
            importedManagedEventDeliveryDepth++;
        }
        try {
            while (!immediate.isEmpty()) {
                ClosureWorkOccurrence work = immediate.dequeue();
                if (sameOrigin != null && (sameOrigin.failedCandidate(work.targetDocumentId())
                        || invalidatedSeedTokens.contains(immediateTokens.get(work.workIdentity())))) continue;
                if (sameOrigin == null) charge("processor", "closureWorkOccurrenceDequeued", 1L,
                        workContext(work, "work." + work.ordinal() + ".dequeue"));
                PendingWork pending = pendingByIdentity.remove(
                        work);
                if (pending == null) {
                    throw new IllegalStateException(
                            "Accepted event delivery has no exact payload");
                }
                if (unavailableForOrdinaryDelivery(
                        work.targetDocumentId())) {
                    // An earlier delivery in this accept-before-deliver batch
                    // began termination. Settle the frozen work occurrence
                    // without starting another local Handler.
                    if (!skipTerminatedWork(pending)) recordCompletedWork(work.ordinal());
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

    /** ScopePropagationChain refreshes each receiving scope at its actual FIFO visit. */
    private void drainOriginalEvent(EmittedOccurrence occurrence) {
        eventDeliveryBatchDepth++;
        long[] deliveryOrdinal = {0L};
        try {
            InitializationRealm realm = occurrence.sourceContext == null ? null : occurrence.sourceContext.realm;
            if (realm != null || !unavailableForOrdinaryDelivery(occurrence.sourceDocumentId))
                deliverCurrentEventRoutes(occurrence, occurrence.sourceDocumentId, stepProcessor.classifyTriggeredEventRoutes(
                        eventReceiverBody(occurrence, occurrence.sourceDocumentId), occurrence.event), deliveryOrdinal);
            // Kernel §4: vertex-simple authored route prefixes, nearest first. The
            // membership/activations are fixed at Enqueue; each receiver's dispatch
            // is selected now, after earlier receiving scopes have finished.
            Deque<UpdatePlacement> paths = new ArrayDeque<>();
            paths.add(new UpdatePlacement(occurrence.sourceDocumentId, "", Collections.emptyList(), Collections.singleton(occurrence.sourceDocumentId)));
            while (!paths.isEmpty()) {
                UpdatePlacement path = paths.removeFirst();
                if ((realm == null || realm.source(path.document) == null) && unavailableForOrdinaryDelivery(path.document)
                        && (!path.path.isEmpty() || failedSources.containsKey(path.document)
                                || sameOrigin != null && sameOrigin.failedCandidate(path.document))) continue;
                if (!path.path.isEmpty()) {
                    if (realm == null) for (ManagedOccurrenceBinding edge : path.path) revalidateFrozenEventTarget(edge.targetDocumentId(), edge);
                    else if (!eligibleRealmRoute(realm, path)) continue;
                    deliverCurrentEventRoutes(occurrence, path.document, stepProcessor.classifyEmbeddedEventRoutes(
                            eventReceiverBody(occurrence, path.document), path.prefix, occurrence.event, occurrence.eventBlueId), deliveryOrdinal);
                    if ((realm == null || realm.source(path.document) == null) && unavailableForOrdinaryDelivery(path.document)) continue;
                }
                for (ManagedOccurrenceBinding edge : occurrence.frozenReverse.getOrDefault(path.document, Collections.emptyList())) {
                    if (path.visited.contains(edge.sourceDocumentId()) || (realm == null || realm.source(edge.sourceDocumentId()) == null)
                            && unavailableForOrdinaryDelivery(edge.sourceDocumentId())) continue;
                    List<String> coordinates = path.occurrenceIdentities(); coordinates.add(edge.occurrenceIdentity());
                    String site = IDENTITIES.observationPlacementSiteIdentity(occurrence.occurrenceIdentity, coordinates);
                    if (realm == null) observesSource(edge.sourceDocumentId(), edge.targetDocumentId(), site);
                    try {
                        if (realm == null || realm.source(edge.sourceDocumentId()) == null) charge("processor", "processEmbeddedEdgeExamined", 1L,
                                documentContext(currentSnapshot.managedDocument(edge.sourceDocumentId()), null,
                                        "event-route." + (realm == null ? site : realm.placementSite(site))));
                    } catch (GasLimitExceededException rejection) {
                        if (sameOrigin == null) throw rejection;
                        failSameOriginAttempt(edge.sourceDocumentId(), site, ProcessorStatus.GAS_LIMIT_EXCEEDED,
                                ProcessorDiagnostic.of(ProcessorErrorCategory.GasLimitExceeded, rejection.getMessage()), null, rejection);
                        continue;
                    }
                    List<ManagedOccurrenceBinding> extended = new ArrayList<>(path.path); extended.add(edge);
                    Set<DocumentId> visited = new LinkedHashSet<>(path.visited); visited.add(edge.sourceDocumentId());
                    paths.addLast(new UpdatePlacement(edge.sourceDocumentId(), composePath(edge.sourcePath(), path.prefix), extended, visited));
                }
            }
        } finally {
            eventDeliveryBatchDepth--;
        }
        if (eventDeliveryBatchDepth == 0 && !pendingTerminations.isEmpty()) completePendingTerminations();
    }

    private SourceCursor eventReceiverSource(EmittedOccurrence occurrence, DocumentId receiver) {
        if (occurrence.sourceContext != null && occurrence.sourceContext.realm != null) {
            SourceCursor source = occurrence.sourceContext.realm.source(receiver);
            if (source != null) return source;
        }
        return receiver.equals(occurrence.sourceDocumentId) ? occurrence.sourceContext : sourcesByDocument.get(receiver);
    }

    private Node eventReceiverBody(EmittedOccurrence occurrence, DocumentId receiver) {
        SourceCursor source = eventReceiverSource(occurrence, receiver);
        return source != null && source.view != null ? source.view.document(receiver) : currentSnapshot.managedDocument(receiver).document();
    }

    private void deliverCurrentEventRoutes(EmittedOccurrence occurrence, DocumentId receiver,
            List<ManagedDocumentStepRoute> routes, long[] deliveryOrdinal) {
        List<PendingWork> accepted = new ArrayList<>();
        SourceCursor receiverSource = eventReceiverSource(occurrence, receiver);
        Object originalToken = sameOrigin == null ? null : seedStreams.get(receiver).originalAttemptToken;
        for (ManagedDocumentStepRoute route : routes) {
            PendingWork pending = causedWork(WorkKind.valueOf(route.workKind().name()), receiver, route,
                    occurrence.eventBlueId, Long.valueOf(occurrence.ordinal), occurrence.occurrenceIdentity, null,
                    receiverSource);
            try {
                accept(pending, "event." + occurrence.ordinal + ".delivery." + deliveryOrdinal[0]++ + ".enqueue");
            } catch (GasLimitExceededException rejection) {
                if (sameOrigin == null) throw rejection;
                failSameOriginAttempt(receiver, entrySite(pending.work), ProcessorStatus.GAS_LIMIT_EXCEEDED,
                        ProcessorDiagnostic.of(ProcessorErrorCategory.GasLimitExceeded, rejection.getMessage()), null, rejection);
                return;
            }
            accepted.add(pending);
        }
        for (PendingWork pending : accepted) {
            if (receiverSource == null && sameOrigin != null && (sameOrigin.failedCandidate(receiver) || invalidatedSeedTokens.contains(originalToken))) return;
            pendingByIdentity.remove(pending.work);
            if (skipTerminatedWork(pending)) continue;
            if (receiverSource == null && unavailableForOrdinaryDelivery(receiver)) { recordCompletedWork(pending.work.ordinal()); continue; }
            if (sameOrigin == null) charge("processor", "closureWorkOccurrenceDequeued", 1L,
                    workContext(pending.work, "work." + pending.work.ordinal() + ".dequeue"));
            executeOne(pending);
        }
    }

    private List<RouteTarget> classifyEventRoutes(
            EmittedOccurrence occurrence) {
        ArrayList<RouteTarget> routes = new ArrayList<RouteTarget>();
        if (!occurrence.imported) throw new IllegalArgumentException("Original events use per-receiver FIFO routing");
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
                && successor.expectedTargetBlueId().equals(frozen.expectedTargetBlueId())
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
        return failedSources.containsKey(documentId) || sameOrigin != null && sameOrigin.failedCandidate(documentId) || terminatingDocuments.contains(documentId)
                || terminatedDocuments.contains(documentId)
                || (document != null && document.terminated());
    }

    /** Accepted identity evidence is consumed only after a real, still-valid request cuts off this target. */
    private boolean skipTerminatedWork(PendingWork pending) {
        ClosureWorkOccurrence work = pending.work;
        if (work.kind() == WorkKind.LIFECYCLE || work.kind() == WorkKind.INITIALIZATION) return false;
        PendingTermination termination = terminationRequests.get(work.targetDocumentId());
        if (termination == null || !validTermination(termination)) return false;
        SourceCursor source = sourceCursorFor(work);
        if (source != termination.sourceContext) return false;
        String request = termination.requestWork.workIdentity();
        if (source != null) {
            SourceObservationProgram.SkippedWork selected = null;
            for (SourceObservationProgram.SkippedWork skipped : source.program.skippedWork())
                if (skipped.workIdentity().equals(work.workIdentity())) { selected = skipped; break; }
            if (selected == null || !selected.requestWorkIdentity().equals(request)
                    || !selected.targetDocumentId().equals(work.targetDocumentId()) || selected.kind() != work.kind()
                    || !selected.channelKey().equals(work.channelKey())
                    || !selected.sourceOccurrenceIdentity().equals(work.sourceOccurrenceIdentity())
                    || !source.consumedSkippedWork.add(work.workIdentity()))
                throw new IllegalArgumentException("Source cutoff has no matching original skipped work");
        }
        if (recorder.sourceObservation() != null) recorder.sourceObservation().skippedWork(work, request,
                sameOrigin == null ? null : seedStreams.get(work.targetDocumentId()).originalAttemptToken);
        recorder.skippedWork(work, termination.requestWork);
        recordCompletedWork(work.ordinal());
        return true;
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
        return causedWork(kind, targetDocumentId, route, eventBlueId, occurrenceOrdinal, sourceOccurrenceIdentity,
                processorPatch, sourcesByDocument.get(targetDocumentId));
    }

    private PendingWork causedWork(WorkKind kind, DocumentId targetDocumentId, ManagedDocumentStepRoute route,
            String eventBlueId, Long occurrenceOrdinal, String sourceOccurrenceIdentity,
            FrozenJsonPatch processorPatch, SourceCursor sourceContext) {
        long ordinal = nextWorkOrdinal();
        ManagedScopeKey scope = ManagedScopeKey.root(targetDocumentId);
        String scopeIdentity = IDENTITIES.managedScopeKeyIdentity(scope);
        String workIdentity = ownWorkIdentity(ordinal, kind, targetDocumentId, route.channelKey(), sourceOccurrenceIdentity, sourceContext);
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
        bindSourceContext(work, sourceContext);
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
        if (!sourceContextsByWork.containsKey(pending.work))
            bindSourceContext(pending.work, sourcesByDocument.get(pending.work.targetDocumentId()));
        recorder.accepted(pending.work);
        if (pendingByIdentity.put(
                pending.work, pending) != null) {
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
        if (sameOrigin == null) workQueue.enqueue(work); else sameOriginWorkQueue.addLast(work);
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
        if (owner != null && !activeFrames.isEmpty() && activeFrame().work == owner && activeFrame().patchBindings == null) {
            List<ManagedOccurrenceBinding> patchRows = new ArrayList<>();
            for (ManagedOccurrenceBinding binding : reclassified) if (binding.sourceDocumentId().equals(owner.targetDocumentId())) patchRows.add(binding);
            activeFrame().patchBindings = Collections.unmodifiableList(patchRows);
        }
        if (!initializationPlacementPins.isEmpty()) {
            List<ManagedOccurrenceBinding> selectedRows = new ArrayList<>();
            for (ManagedOccurrenceBinding binding : reclassified) {
                ManagedReadPin pin = initializationPlacementPins.get(binding.occurrenceIdentity());
                Node reference = NodePathEditor.getOrNull(latestBodies.get(binding.sourceDocumentId()), binding.sourcePath());
                if (binding.active() && pin != null && reference != null && reference.isReferenceOnly()
                        && pin.blueId().equals(reference.getBlueId())) {
                    AcceptedAttachmentView accepted = acceptedAttachmentView(binding);
                    SourceFrontierView frontier = accepted == null ? null : accepted.frontierView().orElse(null);
                    binding = ManagedOccurrenceBinding.derived(binding.bindingPolicyIdentity(), binding.sourceDocumentId(), binding.sourceAddress(),
                            binding.targetDocumentId(), pin.blueId(), frontier == null,
                            frontier == null ? null : Long.valueOf(frontier.successfulEpoch()));
                }
                selectedRows.add(binding);
            }
            reclassified = selectedRows;
        }
        if (!updatePlacementFrames.isEmpty()) {
            List<ManagedOccurrenceBinding> staged = new ArrayList<ManagedOccurrenceBinding>();
            for (ManagedOccurrenceBinding binding : reclassified) {
                UpdatePlacementFrame placement = updatePlacementFrames.getLast();
                ManagedReadPin pin = placement.pins.get(binding.occurrenceIdentity());
                if (binding.active() && placement.retainsSelectedView(binding, pin)) {
                    binding = ManagedOccurrenceBinding.derived(binding.bindingPolicyIdentity(), binding.sourceDocumentId(),
                            binding.sourceAddress(), binding.targetDocumentId(), pin.blueId(), true, null);
                }
                staged.add(binding);
            }
            reclassified = staged;
        }
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
            if (sameOrigin != null) admitSameOriginFeedback(after, owner);
        }
        if (!updatePlacementFrames.isEmpty()) {
            Map<DocumentId, Integer> componentByDocument = new HashMap<DocumentId, Integer>();
            int componentIndex = 0;
            for (List<DocumentId> component : new SccPartitioner().partition(after)) {
                for (DocumentId member : component) componentByDocument.put(member, componentIndex);
                componentIndex++;
            }
            for (ManagedOccurrenceBinding binding : after.activeBindings()) {
                if (componentByDocument.get(binding.sourceDocumentId()).equals(componentByDocument.get(binding.targetDocumentId()))) {
                    // A true feedback component has one joint exact identity;
                    // deferred independent-placement pins cannot split it.
                    for (UpdatePlacementFrame frame : updatePlacementFrames) frame.pins.remove(binding.occurrenceIdentity());
                }
            }
        }
        graphGeneration = ClosureGraphGenerationTransition.assign(
                input.snapshot().graphGeneration(), inputGraph, after);
        Map<DocumentId, Long> generations =
                ComponentGenerationTransition.assign(
                        inputGraph,
                        inputComponentGenerations,
                        after);
        Map<DocumentId, Node> sourceBodies = cloneBodies(latestBodies);
        for (SourceObservationProgram.ReferenceProjection projection : boundaryReferenceProjections) {
            sourceBodies.put(projection.targetDocumentId(), projection.beforeBody().toNode());
        }
        ComponentFinalizationResult finalized;
        long finalizationStarted =
                recorder.beginComponentFinalizationProof();
        try {
            finalized = finalizer.finalizeComponents(
                    new ComponentFinalizationInput(
                            inputGraph,
                            inputComponentGenerations,
                            latestBodies,
                            reclassified, retainedPins(reclassified), currentSnapshot.reusableComponents()));
        } finally {
            recorder.endComponentFinalizationProof(finalizationStarted);
        }
        finalized = rebindInactiveProspectiveRows(finalized);
        if (sameOrigin != null && owner != null) {
            String site = observationSiteIdentity == null ? entrySite(owner) : observationSiteIdentity;
            for (ManagedOccurrenceBinding binding : finalized.finalizedGraph().activeBindings()) {
                if (!finalized.document(binding.sourceDocumentId()).hasResidentBody()) continue;
                Node beforeReference = NodePathEditor.getOrNull(sourceBodies.get(binding.sourceDocumentId()), binding.sourcePath());
                Node afterReference = NodePathEditor.getOrNull(finalized.document(binding.sourceDocumentId()).document(), binding.sourcePath());
                if (!sameNode(beforeReference, afterReference)) observesSource(binding.sourceDocumentId(), binding.targetDocumentId(), site);
            }
        }
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
        ClosureFinalizationGasCharger.ComponentCompletion componentCompletion =
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
                };
        if (sameOrigin == null) {
            finalizationGas.finishFinalization(gasFrame, sourceBodies, finalized,
                    establishedBlueIds, existingBlueIds, componentCompletion);
        } else {
            finalizationGas.finishFinalization(gasFrame, sourceBodies, finalized,
                    document -> {
                        SeedStream seed = seedStreams.get(document);
                        return new ClosureFinalizationGasCharger.IdentityLedger(
                                seed.establishedBlueIds, seed.existingBlueIds);
                    }, componentCompletion);
        }
        chargeChangedAcyclicComponents(
                finalized, boundary, owner);
        recordFinalizedBlueIds(finalized);
        currentBindings = new ArrayList<ManagedOccurrenceBinding>(
                finalized.finalizedGraph().bindings());
        latestBodies.clear();
        for (FinalizedDocumentEvidence document
                : finalized.documents().values()) {
            if (document.hasResidentBody()) latestBodies.put(document.documentId(), document.document());
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

    private void admitSameOriginFeedback(ManagedDocumentGraph proposedGraph, ClosureWorkOccurrence owner) {
        for (List<DocumentId> component : new SccPartitioner().partition(proposedGraph)) {
            if (!component.contains(owner.targetDocumentId())) continue;
            boolean retained = component.stream().anyMatch(this::isRetainedSource);
            if (retained) {
                if (!component.stream().allMatch(this::isRetainedSource))
                    throw new InvalidExecutionEvidenceException("A committed source cannot be joined retroactively; its complete canonical group is required",
                            ProcessorErrorCategory.InvalidProcessingDocument);
                return;
            }
            verifyFreshSourceAdmission(component);
            Set<SameOriginAttemptCoordinator.Attempt> groups = Collections.newSetFromMap(
                    new java.util.IdentityHashMap<SameOriginAttemptCoordinator.Attempt, Boolean>());
            for (DocumentId member : component) groups.add(sameOrigin.attempt(member));
            if (groups.size() <= 1) return;
            String site = observationSiteIdentity == null ? entrySite(owner) : observationSiteIdentity;
            blue.language.processor.GasMeter.MultiGroupJoinResult result = sameOrigin.admitFeedback(owner.targetDocumentId(), component, site);
            if (!result.joined()) {
                SameOriginAdmissionRejection failure = new SameOriginAdmissionRejection(site, result);
                // The ordinary interpreter carries semantic diagnostics, not the private
                // Java exception subtype. Freeze the owning rejection before unwinding,
                // while every prospective participant still has its canonical prefix.
                failSameOriginAttempt(owner.targetDocumentId(), site, ProcessorStatus.RUNTIME_FATAL,
                        ProcessorDiagnostic.of(failure.errorCategory(), failure.getMessage()), result);
                throw failure;
            }
            SameOriginAttemptCoordinator.Failure prior = sameOrigin.attempt(owner.targetDocumentId()).failure();
            if (prior != null) throw new ProcessorFailureException(prior.diagnostic.category(), prior.diagnostic.message());
            return;
        }
    }

    private static final class SameOriginAdmissionRejection extends ProcessorFailureException {
        final String site;
        final blue.language.processor.GasMeter.MultiGroupJoinResult result;
        SameOriginAdmissionRejection(String site, blue.language.processor.GasMeter.MultiGroupJoinResult result) {
            super(ProcessorErrorCategory.AtomicScopeGasAdmissionFailure, admissionDiagnostic(result));
            this.site = site; this.result = result;
        }
        private static String admissionDiagnostic(blue.language.processor.GasMeter.MultiGroupJoinResult result) {
            java.math.BigInteger required = java.math.BigInteger.ZERO;
            for (blue.language.processor.GasMeter.GroupContribution contribution : result.contributions())
                required = required.add(java.math.BigInteger.valueOf(contribution.admitted())).add(java.math.BigInteger.valueOf(contribution.reserved()));
            String diagnostic = "Atomic scope admission rejected: initiating admitted " + result.contributions().get(0).admitted()
                    + ", required " + required + ", shared limit " + result.limit();
            if (result.localDocumentId() != null) diagnostic += ", local owner " + result.localDocumentId() + ", local limit " + result.localLimit();
            return diagnostic;
        }
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
        Map<DocumentId, Node> originalPatchBodies = updatePlacementFrames.isEmpty()
                ? beforeWorkBoundary : updatePlacementFrames.getLast().beforeBodies;
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
                    originalPatchBodies.get(binding.sourceDocumentId()),
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
        SourceCursor receiverSource = suspendedFrame.sourceContext != null && suspendedFrame.sourceContext.realm != null
                ? suspendedFrame.sourceContext.realm.source(targetDocumentId) : sourcesByDocument.get(targetDocumentId);
        if (routes.isEmpty() || receiverSource == null && (failedSources.containsKey(targetDocumentId)
                || sameOrigin != null && sameOrigin.failedCandidate(targetDocumentId))) {
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
                        null, receiverSource);
                accept(caused,
                        "transition." + transitionOrdinal
                                + ".update." + updateOrdinal
                                + ".enqueue");
                immediate.enqueue(caused.work);
            }
            while (!immediate.isEmpty()) {
                ClosureWorkOccurrence work = immediate.dequeue();
                if (sameOrigin == null) charge("processor", "closureWorkOccurrenceDequeued", 1L,
                        workContext(work, "work." + work.ordinal() + ".dequeue"));
                PendingWork caused = pendingByIdentity.remove(
                        work);
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
        if (frame.sourceContext == null && sameOrigin != null && sameOrigin.failedCandidate(frame.work.targetDocumentId())) {
            ProcessorDiagnostic failure = sameOrigin.attempt(frame.work.targetDocumentId()).failure().diagnostic;
            throw new ProcessorFailureException(failure.category(), failure.message());
        }
        if (frame.sourceContext == null && sameOrigin != null && invalidatedSeedTokens.contains(frame.originalAttemptToken)) {
            // An invalidated physical stack has no candidate outcome. The owning executeOne
            // recognizes its discarded token and abandons this unwind without publishing it.
            throw new ProcessorFailureException(ProcessorErrorCategory.RuntimeExecutionFailure,
                    "Discarded conditional continuation cannot resume");
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
        prepareAcceptedAttachmentViews(projected);
        ProcessEmbeddedSurfaceReconciler.DemandContext demandContext =
                processEmbeddedDemandContext(true);
        requireAvailableProcessEmbeddedResources(
                projected, demandContext);

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
        boolean receiptEventReclassification = false;
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
                    // inactive generation, including an exact retarget.
                    working.add(receiptEventRetirement);
                    receiptEventReclassification = true;
                } else {
                    working.add(reconcileManagedRevision(
                            binding, revision));
                }
            } else {
                working.add(pendingDeclarationRetirement(binding, projected));
            }
        }
        Collections.sort(working);
        if (receiptEventReclassification) {
            // Historical rows are not ordinarily active candidates. Only the
            // authenticated receipt-event boundary above can expose one to
            // the same demand/resolution preflight as an ordinary retarget.
            // Do not publish this invocation-local activation or make it the
            // before graph used for finalization and topology accounting.
            List<ManagedOccurrenceBinding> before = currentBindings;
            currentBindings = working;
            try {
                requireAvailableProcessEmbeddedResources(
                        projected, demandContext);
                working = new ArrayList<ManagedOccurrenceBinding>(currentBindings);
            } finally {
                currentBindings = before;
            }
        }

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
                    processEmbeddedReconciler
                            .reconcileProjectedAfterDemandAggregation(
                            source,
                            latestBodies.get(source),
                            projected.get(source),
                            working,
                            currentDocuments,
                            fences,
                            demandContext
                                    .priorFinalizedReferenceAvailability());
            working = new ArrayList<ManagedOccurrenceBinding>(
                    result.bindings());
            for (ProcessEmbeddedSurfaceReconciler.OccurrenceTransition transition : result.transitions()) {
                if (transition.kind() == ProcessEmbeddedSurfaceReconciler.OccurrenceTransition.Kind.REMOVE)
                    occurrenceRetirements.put(transition.before().occurrenceIdentity(), transition);
            }
            activated.addAll(
                    result.activatedOccurrenceIdentities());
            retired.addAll(result.retiredOccurrencePaths());
            fences.addAll(result.retiredOccurrencePaths());
        }
        return new ProcessEmbeddedReclassification(
                working, activated, retired);
    }

    /** A pending lane retires only when an actually existing declaration disappears at this patch. */
    private ManagedOccurrenceBinding pendingDeclarationRetirement(ManagedOccurrenceBinding binding,
            Map<DocumentId, List<ManagedProcessEmbeddedPath>> projected) {
        if (binding.active() || binding.pendingHistoricalEpoch() == null) return binding;
        List<ManagedProcessEmbeddedPath> after = projected.get(binding.sourceDocumentId());
        if (after == null) return binding;
        for (ManagedProcessEmbeddedPath path : after) if (path.absolutePath().equals(binding.sourcePath())) return binding;
        ManagedDocumentSnapshot before = currentSnapshot.managedDocument(binding.sourceDocumentId());
        if (!before.hasResidentBody()) return binding;
        for (ManagedProcessEmbeddedPath path : stepProcessor.projectManagedProcessEmbeddedSurface(before.document())) {
            if (path.absolutePath().equals(binding.sourcePath())) return ManagedOccurrenceBinding.derived(binding.bindingPolicyIdentity(),
                    binding.sourceDocumentId(), binding.sourceAddress(), binding.targetDocumentId(), binding.expectedTargetBlueId(), true, null);
        }
        return binding;
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
        if (containing == null) {
            return null;
        }

        ManagedDocumentSnapshot child = currentSnapshot.managedDocument(
                revision.childDocumentId());
        if (child == null) {
            return null;
        }
        Node value = NodePathEditor.getOrNull(containing, binding.sourcePath());
        String installedBlueId = managedRevisionActivationCompleted && binding.active()
                ? child.blueId() : revision.afterBlueId();
        if (value != null && value.isReferenceOnly()
                && installedBlueId.equals(value.getBlueId())) {
            // Unchanged successor references still take the strict managed
            // revision reconciliation path; this lane owns event changes only.
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
        requireAvailableProcessEmbeddedResources(
                projected,
                processEmbeddedDemandContext(
                        verifyHistoricalExactReferences));
    }

    private void requireAvailableProcessEmbeddedResources(
            Map<DocumentId, List<ManagedProcessEmbeddedPath>> projected,
            ProcessEmbeddedSurfaceReconciler.DemandContext demandContext) {
        List<ClosureResourceDemand> demands =
                processEmbeddedReconciler.resourceDemands(
                        latestBodies,
                        projected,
                        currentBindings,
                        currentSnapshot.managedDocuments(),
                        demandContext);
        if (demands.isEmpty()) {
            return;
        }
        ArrayList<ManagedOccurrenceEvidenceResolution> selected =
                new ArrayList<ManagedOccurrenceEvidenceResolution>();
        for (ClosureResourceDemand demand : demands) {
            ManagedOccurrenceEvidenceResolution resolution =
                    demand instanceof ManagedOccurrenceEvidenceDemand
                            ? managedOccurrenceResolutions.get(
                                    demand.demandIdentity())
                            : null;
            if (resolution == null) {
                throw new ClosureResourceDemandException(demands);
            }
            selected.add(resolution);
        }
        applyManagedOccurrenceResolutions(selected);
        List<ClosureResourceDemand> remaining =
                processEmbeddedReconciler.resourceDemands(
                        latestBodies,
                        projected,
                        currentBindings,
                        currentSnapshot.managedDocuments(),
                        demandContext);
        if (!remaining.isEmpty()) {
            throw new IllegalArgumentException(
                    "Exact managed-occurrence resolutions did not close "
                            + "their demand boundary");
        }
    }

    private void applyManagedOccurrenceResolutions(
            List<ManagedOccurrenceEvidenceResolution> resolutions) {
        ArrayList<ManagedOccurrenceBinding> replacement =
                new ArrayList<ManagedOccurrenceBinding>(currentBindings);
        LinkedHashSet<String> consumed = new LinkedHashSet<String>();
        for (ManagedOccurrenceEvidenceResolution resolution : resolutions) {
            ManagedOccurrenceEvidenceDemand demand = resolution.demand();
            int match = -1;
            for (int index = 0; index < replacement.size(); index++) {
                ManagedOccurrenceBinding row = replacement.get(index);
                if (row.sourceDocumentId().equals(
                                demand.sourceDocumentId())
                        && row.sourcePath().equals(demand.sourcePath())) {
                    if (match >= 0) {
                        throw new IllegalStateException(
                                "Managed occurrence resolution source is "
                                        + "ambiguous");
                    }
                    match = index;
                }
            }
            if (match < 0) {
                throw new IllegalArgumentException(
                        "Managed occurrence resolution has no current source "
                                + "row");
            }
            ManagedOccurrenceBinding before = replacement.get(match);
            if (!before.active()
                    || before.targetDocumentId().equals(
                            resolution.targetDocumentId())
                    || currentSnapshot.managedDocument(
                            resolution.targetDocumentId()) == null
                    || before.activationGeneration()
                            == ClosureValueSupport.MAX_SAFE_INTEGER) {
                throw new IllegalArgumentException(
                        "Managed occurrence resolution is not an active "
                                + "different-lineage historical retarget");
            }
            ManagedOccurrenceBinding after =
                    ManagedOccurrenceBinding.derived(
                            before.bindingPolicyIdentity(),
                            before.sourceDocumentId(),
                            ScopeAddress.embedded(
                                    before.sourcePath(),
                                    before.activationGeneration() + 1L),
                            resolution.targetDocumentId(),
                            demand.suppliedValueBlueId(),
                            false,
                            Long.valueOf(
                                    resolution.pendingHistoricalEpoch()));
            replacement.set(match, after);
            consumed.add(demand.demandIdentity());
        }
        Collections.sort(replacement);
        currentBindings = replacement;
        consumedManagedOccurrenceResolutions.addAll(consumed);
    }

    private void requireEveryManagedOccurrenceResolutionConsumed() {
        if (managedOccurrenceResolutions.size()
                != consumedManagedOccurrenceResolutions.size()
                || !consumedManagedOccurrenceResolutions.containsAll(
                        managedOccurrenceResolutions.keySet())) {
            throw new IllegalArgumentException(
                    "Process retry contains unused managed-occurrence "
                            + "resolution evidence");
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
        // These are already verified current member/pin identities, including reusable
        // cyclic headers. Discovery must not reopen their bodies/proofs through a provider.
        // Actual reads still demand a nonresident member at the exact read site.
        final Set<String> admittedExact = new HashSet<>();
        for (ManagedDocumentSnapshot member : currentSnapshot.managedDocuments()) admittedExact.add(member.blueId());
        for (ManagedReadPin pin : currentSnapshot.readPins()) admittedExact.add(pin.blueId());
        for (ManagedReadPin pin : initializationPlacementPins.values()) admittedExact.add(pin.blueId());
        if (input.cause() instanceof ManagedRevisionCause) {
            // The admitted revision already carries and verifies this exact historical
            // body/proof, even when the immutable current source cell is further ahead.
            admittedExact.add(((ManagedRevisionCause) input.cause()).afterBlueId());
        }
        return new ProcessEmbeddedSurfaceReconciler.DemandContext(
                input.cause().causeIdentity(),
                input.snapshot().closureIdentity(),
                input.snapshot().graphGeneration(),
                new ProcessEmbeddedSurfaceReconciler
                        .ExactReferenceAvailability() {
                    @Override
                    public boolean isAvailable(String blueId) {
                        if (admittedExact.contains(blueId)) return true;
                        for (Set<String> values : projectedReferenceTargets.values()) {
                            if (values.contains(blueId)) return true;
                        }
                        for (ManagedReadPin pin : currentSnapshot.readPins()) {
                            if (pin.blueId().equals(blueId)) return true;
                        }
                        return stepProcessor
                                .isExactManagedReferenceAvailable(blueId);
                    }
                },
                priorFinalizedReferenceAvailabilitySnapshot(),
                verifyHistoricalExactReferences);
    }

    private ProcessEmbeddedSurfaceReconciler
            .PriorFinalizedReferenceAvailability
    priorFinalizedReferenceAvailabilitySnapshot() {
        final Map<DocumentId, Set<String>> exact =
                new LinkedHashMap<DocumentId, Set<String>>();
        for (Map.Entry<DocumentId, Set<String>> entry
                : finalizedBlueIdsByDocument.entrySet()) {
            exact.put(entry.getKey(), Collections.unmodifiableSet(
                    new LinkedHashSet<String>(entry.getValue())));
        }
        return new ProcessEmbeddedSurfaceReconciler
                .PriorFinalizedReferenceAvailability() {
            @Override
            public boolean isAvailable(
                    DocumentId documentId, String blueId) {
                for (ManagedReadPin pin : initializationPlacementPins.values())
                    if (pin.documentId().equals(documentId) && pin.blueId().equals(blueId)) return true;
                Set<String> finalized = exact.get(documentId);
                return finalized != null && finalized.contains(blueId)
                        || currentSnapshot.readPin(documentId, blueId) != null
                        || projectedReferenceTargets.containsKey(documentId) && projectedReferenceTargets.get(documentId).contains(blueId);
            }
            @Override public boolean retainsExactBinding(ManagedOccurrenceBinding binding, String blueId) {
                return initializationPlacementPins.containsKey(binding.occurrenceIdentity())
                        && initializationPlacementPins.get(binding.occurrenceIdentity()).blueId().equals(blueId)
                        || nonDueReactionPins.containsKey(binding.occurrenceIdentity())
                        && nonDueReactionPins.get(binding.occurrenceIdentity()).blueId().equals(blueId)
                        || blueId.equals(projectedReferenceValues.get(binding.occurrenceIdentity()))
                        || (ownedDocuments == null || !ownedDocuments.contains(binding.sourceDocumentId()))
                        && currentSnapshot.readPin(binding.targetDocumentId(), blueId) != null
                        && !currentSnapshot.managedDocument(binding.targetDocumentId()).blueId().equals(blueId);
            }
            @Override public boolean acceptsProspectiveView(ManagedOccurrenceBinding binding, String suppliedBlueId, String selectedBlueId) {
                ManagedReadPin initializationPin = initializationPlacementPins.get(binding.occurrenceIdentity());
                if (initializationPin != null && initializationPin.blueId().equals(suppliedBlueId)) return true;
                AcceptedAttachmentView view = acceptedAttachmentView(binding);
                return view != null && view.selection().suppliedExactRefBlueId().equals(suppliedBlueId)
                        && view.selectedView().blueId().equals(selectedBlueId);
            }
        };
    }

    private static String attachmentViewKey(String site, String occurrence) { return site + ":" + occurrence; }

    private AcceptedAttachmentView acceptedAttachmentView(ManagedOccurrenceBinding binding) {
        if (observationSiteIdentity == null) return null;
        String key = attachmentViewKey(observationSiteIdentity, binding.occurrenceIdentity());
        if (isRetainedSource(binding.sourceDocumentId())) return retainedAttachmentViews.get(key);
        AttemptAttachmentView current = acceptedAttachmentViews.get(key);
        return current != null && sameOrigin != null
                && current.token == seedStreams.get(binding.sourceDocumentId()).originalAttemptToken ? current.view : null;
    }

    /** Selects a declared FROM_NOW view at this actual interpreter site, never from a host head. */
    private void prepareAcceptedAttachmentViews(Map<DocumentId, List<ManagedProcessEmbeddedPath>> projected) {
        if (observationSiteIdentity == null) return;
        for (ManagedOccurrenceBinding binding : currentBindings) {
            if (binding.active() || binding.pendingHistoricalEpoch() != null) continue;
            boolean declared = false;
            for (ManagedProcessEmbeddedPath path : projected.getOrDefault(binding.sourceDocumentId(), Collections.emptyList()))
                if (binding.sourcePath().equals(path.absolutePath())) { declared = true; break; }
            if (!declared) continue;
            Node supplied = NodePathEditor.getOrNull(latestBodies.get(binding.sourceDocumentId()), binding.sourcePath());
            if (supplied == null || !supplied.isReferenceOnly()) continue;
            String key = attachmentViewKey(observationSiteIdentity, binding.occurrenceIdentity());
            AcceptedAttachmentView selected = acceptedAttachmentView(binding);
            if (isRetainedSource(binding.sourceDocumentId())) {
                AcceptedInitializationInstallation initialization = retainedInitializationInstallations.get(key);
                if (initialization != null) {
                    SourceInterpretationView initial = SourceInterpretationView.from(dormantInitializations.get(initialization.sourceInitializationOperationIdentity()));
                    ManagedReadPin pin = initial.selectedPin(binding.targetDocumentId());
                    if (!pin.blueId().equals(supplied.getBlueId())) throw new IllegalArgumentException("Retained initializer placement starts at a different authored reference");
                    initializationPlacementPins.put(binding.occurrenceIdentity(), pin); retainedReadPins.add(pin);
                    continue;
                }
                if (selected == null) continue;
                if (selected.frontierView().isPresent()) {
                    if (!selected.selection().suppliedExactRefBlueId().equals(supplied.getBlueId()))
                        throw new IllegalArgumentException("Retained frontier placement starts at another exact authored reference");
                    selected.frontierView().get().verifyInvocation(input, sourceBases(Collections.singleton(binding.targetDocumentId())));
                    initializationPlacementPins.put(binding.occurrenceIdentity(), selected.selectedView());
                    retainedReadPins.add(selected.selectedView());
                    NodePathEditor.put(latestBodies.get(binding.sourceDocumentId()), binding.sourcePath(), new Node().blueId(selected.selectedView().blueId()));
                    consumedRetainedAttachmentViews.add(selected.identity());
                    continue;
                }
                ManagedDocumentSnapshot exact = currentSnapshot.managedDocument(binding.targetDocumentId());
                if (!selected.selection().suppliedExactRefBlueId().equals(supplied.getBlueId())
                        || !selected.selectedView().blueId().equals(exact.blueId())
                        || !sameNode(selected.selectedView().document(), exact.document())
                        || !selected.sourceSiteIdentity().equals(sourcePrefixSites.getOrDefault(binding.targetDocumentId(),
                        IDENTITIES.observationSourcePredecessorSiteIdentity(selected.sourceSeedIdentity()))))
                    throw new IllegalArgumentException("Retained attachment view differs from its actual canonical source prefix");
                consumedRetainedAttachmentViews.add(selected.identity());
                continue;
            }
            if (sameOrigin == null || selected != null) continue;
            SameOriginAttachmentPolicy.Selection selection = attachmentPolicy.selection(binding.occurrenceIdentity()).orElse(null);
            if (selection != null && selection.mode() == SameOriginAttachmentPolicy.Mode.FROM_FRONTIER
                    && selection.suppliedExactRefBlueId().equals(supplied.getBlueId())) {
                SourceFrontierView frontier = offeredFrontierViews.get(binding.occurrenceIdentity());
                if (frontier == null) throw new IllegalArgumentException("FROM_FRONTIER requires exact selected frontier evidence before execution");
                consultSourceEvidence(binding.sourceDocumentId(), SameOriginGroupEvidence.SourceEvidence.Kind.FRONTIER, frontier.identity());
                SeedStream creator = seedStreams.get(binding.sourceDocumentId());
                selected = AcceptedAttachmentView.fromFrontierSite(selection, creator.identity.identity(), observationSiteIdentity, frontier);
                acceptedAttachmentViews.put(key, new AttemptAttachmentView(selected, creator.originalAttemptToken));
                initializationPlacementPins.put(binding.occurrenceIdentity(), selected.selectedView());
                retainedReadPins.add(selected.selectedView());
                recorder.sourceObservation().acceptedView(selected, creator.originalAttemptToken);
                NodePathEditor.put(latestBodies.get(binding.sourceDocumentId()), binding.sourcePath(), new Node().blueId(selected.selectedView().blueId()));
                continue;
            }
            if (selection != null && selection.mode() == SameOriginAttachmentPolicy.Mode.FULL_HISTORY
                    && selection.suppliedExactRefBlueId().equals(supplied.getBlueId())) {
                SourceInitialization initialization = offeredInitializations.get(binding.targetDocumentId());
                if (initialization == null) {
                    SeedStream creator = seedStreams.get(binding.sourceDocumentId());
                    throw new ClosureResourceDemandException(Collections.singletonList(new SourceInitializationDemand(selection,
                            binding.sourcePath(), creator.identity.identity(), observationSiteIdentity, input.environment(), input.executionPolicy())));
                }
                consultInitialization(binding.sourceDocumentId(), initialization);
                SourceInterpretationView initial = SourceInterpretationView.from(initialization);
                ManagedReadPin pin = initial.selectedPin(binding.targetDocumentId());
                if (!pin.blueId().equals(supplied.getBlueId())) throw new IllegalArgumentException("Initialization placement must begin at its authored exact source");
                initializationPlacementPins.put(binding.occurrenceIdentity(), pin); retainedReadPins.add(pin);
                continue;
            }
            if (selection == null || selection.mode() != SameOriginAttachmentPolicy.Mode.FROM_NOW
                    || !selection.suppliedExactRefBlueId().equals(supplied.getBlueId())) continue;
            ManagedDocumentSnapshot exact = currentSnapshot.managedDocument(binding.targetDocumentId());
            blue.language.provider.CyclicSetProof proof = null;
            for (ComponentSnapshot component : currentSnapshot.components())
                if (component.orderedMemberDocumentIds().contains(exact.documentId())) { proof = component.completeCyclicProof(); break; }
            ManagedReadPin pin = ManagedReadPin.fromExactEvidence(exact.documentId(), exact.blueId(), exact.document(), proof);
            SeedStream creator = seedStreams.get(binding.sourceDocumentId()), source = seedStreams.get(binding.targetDocumentId());
            selected = AcceptedAttachmentView.fromCanonicalSite(selection, creator.identity.identity(), observationSiteIdentity,
                    source.identity.identity(), sourcePrefixSites.getOrDefault(exact.documentId(),
                            IDENTITIES.observationSourcePredecessorSiteIdentity(source.identity.identity())), pin);
            acceptedAttachmentViews.put(key, new AttemptAttachmentView(selected, creator.originalAttemptToken));
            retainedReadPins.add(pin);
            if (recorder.sourceObservation() != null) recorder.sourceObservation().acceptedView(selected, creator.originalAttemptToken);
        }
    }

    private static final class AttemptAttachmentView {
        final AcceptedAttachmentView view; final Object token;
        AttemptAttachmentView(AcceptedAttachmentView view, Object token) { this.view = view; this.token = token; }
    }

    private void recordFinalizedBlueIds(
            ComponentFinalizationResult finalized) {
        for (FinalizedDocumentEvidence document
                : finalized.documents().values()) {
            recordFinalizedBlueId(
                    document.documentId(), document.blueId());
        }
    }

    private void recordFinalizedBlueId(
            DocumentId documentId, String blueId) {
        Set<String> finalized = finalizedBlueIdsByDocument.get(documentId);
        if (finalized == null) {
            finalized = new LinkedHashSet<String>();
            finalizedBlueIdsByDocument.put(documentId, finalized);
        }
        finalized.add(ClosureValueSupport.requireBlueId(
                blueId, "finalizedBlueId"));
    }

    private ComponentFinalizationResult rebindInactiveProspectiveRows(
            ComponentFinalizationResult finalized) {
        // A dormant identity reservation is not a subscription to an ambient source head.
        // The accepted creation site alone selects its eventual exact installed view.
        return finalized;
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

    private Map<String, ManagedReadPin> retainedPins(List<ManagedOccurrenceBinding> bindings) {
        Map<String, ManagedReadPin> result = new LinkedHashMap<String, ManagedReadPin>();
        Map<DocumentId, Integer> proposedComponents = new HashMap<>();
        int componentOrdinal = 0;
        for (List<DocumentId> component : new SccPartitioner().partition(ManagedDocumentGraph.fromBindings(inputGraph.documentIds(), bindings))) {
            for (DocumentId member : component) proposedComponents.put(member, componentOrdinal);
            componentOrdinal++;
        }
        for (ManagedOccurrenceBinding binding : bindings) {
            ManagedReadPin initializationPin = initializationPlacementPins.get(binding.occurrenceIdentity());
            if (initializationPin != null && binding.active()) {
                Node reference = NodePathEditor.getOrNull(latestBodies.get(binding.sourceDocumentId()), binding.sourcePath());
                if (reference != null && reference.isReferenceOnly() && initializationPin.blueId().equals(reference.getBlueId())) {
                    result.put(binding.occurrenceIdentity(), initializationPin); continue;
                }
            }
            ManagedReadPin fixedReactionPin = nonDueReactionPins.get(binding.occurrenceIdentity());
            if (fixedReactionPin != null && binding.active()) {
                Node reference = NodePathEditor.getOrNull(latestBodies.get(binding.sourceDocumentId()), binding.sourcePath());
                if (reference != null && reference.isReferenceOnly() && fixedReactionPin.blueId().equals(reference.getBlueId())) {
                    result.put(binding.occurrenceIdentity(), fixedReactionPin); continue;
                }
            }
            // Pin retention follows the proposed exact graph, not its old partition. A newly
            // admitted SCC cannot fall back to historical independent-placement pins.
            if (binding.active() && proposedComponents.get(binding.sourceDocumentId()).equals(proposedComponents.get(binding.targetDocumentId()))) continue;
            if (!updatePlacementFrames.isEmpty() && binding.active()) {
                UpdatePlacementFrame placement = updatePlacementFrames.getLast();
                ManagedReadPin staged = placement.pins.get(binding.occurrenceIdentity());
                if (placement.retainsSelectedView(binding, staged)) {
                    result.put(binding.occurrenceIdentity(), staged);
                    continue;
                }
                if (updatePlacementFrames.getLast().advanced.contains(binding.occurrenceIdentity())) continue;
            }
            if (binding.active() && sameOrigin != null && sameOrigin.failedCandidate(binding.sourceDocumentId())
                    && !sameOrigin.failedCandidate(binding.targetDocumentId())) {
                // Even if the producer still matches now, this finalization may advance its
                // checkpoint. A failed consumer keeps its exact original selection throughout.
                result.put(binding.occurrenceIdentity(), rollbackPin(binding));
                continue;
            }
            if (!binding.active() || ownedDocuments != null && ownedDocuments.contains(binding.sourceDocumentId())) continue;
            if (sameOrigin != null && sameOriginRolledBack.contains(binding.sourceDocumentId())
                    && !sameOriginRolledBack.contains(binding.targetDocumentId())) {
                if (binding.expectedTargetBlueId().equals(currentSnapshot.managedDocument(binding.targetDocumentId()).blueId())) continue;
                result.put(binding.occurrenceIdentity(), rollbackPin(binding));
                continue;
            }
            if (projectedReferenceValues.containsKey(binding.occurrenceIdentity())) continue;
            ManagedReadPin pin = currentSnapshot.readPin(binding.targetDocumentId(), binding.expectedTargetBlueId());
            ManagedDocumentSnapshot target = currentSnapshot.managedDocument(binding.targetDocumentId());
            if (pin == null && ownedDocuments != null && target.blueId().equals(binding.expectedTargetBlueId())
                    && (ownedDocuments.contains(target.documentId()) || isRetainedSource(target.documentId())
                            || initializingSources.contains(target.documentId()))) {
                blue.language.provider.CyclicSetProof proof = null;
                for (ComponentSnapshot component : currentSnapshot.components()) {
                    if (component.orderedMemberDocumentIds().contains(target.documentId())) { proof = component.completeCyclicProof(); break; }
                }
                pin = ManagedReadPin.fromExactEvidence(target.documentId(), target.blueId(), target.document(), proof);
                retainedReadPins.add(pin);
            }
            if (pin != null && (ownedDocuments != null || !target.blueId().equals(pin.blueId()))) {
                result.put(binding.occurrenceIdentity(), pin);
            }
        }
        return result;
    }

    private AffectedClosureSnapshot snapshot(
            ComponentFinalizationResult finalized) {
        ArrayList<ManagedDocumentSnapshot> documents =
                new ArrayList<ManagedDocumentSnapshot>();
        for (ManagedDocumentSnapshot original
                : input.snapshot().managedDocuments()) {
            FinalizedDocumentEvidence document = finalized.document(
                    original.documentId());
            if (document.reusableAuthority().isPresent()) {
                documents.add(document.reusableAuthority().get().retainingBody(
                        currentSnapshot.managedDocument(original.documentId()), document.hasResidentBody()));
                continue;
            }
            documents.add(new ManagedDocumentSnapshot(
                    original.documentId(),
                    document.blueId(),
                    document.document(),
                    initializedDocuments.contains(
                            original.documentId()),
                    (sourcesByDocument.containsKey(original.documentId())
                            ? NodePathEditor.getOrNull(document.document(), ProcessorPointerConstants.RELATIVE_TERMINATED) != null
                            : original.terminated())
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
                input.snapshot().publicRootDocumentIds(), new ArrayList<ManagedReadPin>(retainedReadPins));
        String closureIdentity = IDENTITIES.affectedClosureIdentity(
                provisional);
        return new AffectedClosureSnapshot(
                closureIdentity,
                graphGeneration,
                documents,
                currentBindings,
                bindingSetIdentity,
                components,
                input.snapshot().publicRootDocumentIds(), new ArrayList<ManagedReadPin>(retainedReadPins));
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
            Set<String> ownedEstablishedBlueIds = sameOrigin == null ? establishedBlueIds : seedStreams.get(documentId).establishedBlueIds;
            Set<String> ownedExistingBlueIds = sameOrigin == null ? existingBlueIds : seedStreams.get(documentId).existingBlueIds;
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
                        ownedEstablishedBlueIds,
                        ownedExistingBlueIds);
            } else if (boundary.kind()
                    == TentativeFinalization.Boundary.Kind
                            .INITIALIZATION_BATCH) {
                finalizationGas.chargeInitializationAcyclicChangedBody(
                        stepProcessor,
                        documentId,
                        after.document(),
                        component.component().componentGeneration(),
                        ownedEstablishedBlueIds,
                        ownedExistingBlueIds);
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
                        ownedEstablishedBlueIds,
                        ownedExistingBlueIds);
            } else {
                finalizationGas.chargeCheckpointAcyclicChangedBody(
                        stepProcessor,
                        documentId,
                        after.document(),
                        component.component().componentGeneration(),
                        0L,
                        ownedEstablishedBlueIds,
                        ownedExistingBlueIds);
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
                            targetDocumentId) && reactionDeliveryEligible(binding)) {
                result.add(binding);
            }
        }
        result.sort(ClosureExecutionSession::compareContainingOccurrences);
        return result;
    }

    private static int compareContainingOccurrences(ManagedOccurrenceBinding left, ManagedOccurrenceBinding right) {
        int order = left.sourceDocumentId().compareTo(right.sourceDocumentId());
        if (order != 0) return order;
        order = ClosureValueSupport.comparePortableText(left.sourcePath(), right.sourcePath());
        if (order != 0) return order;
        order = Long.compare(left.activationGeneration(), right.activationGeneration());
        return order != 0 ? order : ClosureValueSupport.comparePortableText(left.occurrenceIdentity(), right.occurrenceIdentity());
    }

    private String transitionIdentity(
            ClosureWorkOccurrence work,
            String beforeBlueId,
            long transitionOrdinal) {
        if (sameOrigin != null) {
            SeedStream stream = seedStreams.get(work.targetDocumentId());
            String identity = stream.identity.transitionIdentity(stream.transitionOrdinal++, work.targetDocumentId(), beforeBlueId, work.workIdentity());
            seedTransitionSites.put(Long.valueOf(transitionOrdinal), identity);
            return identity;
        }
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
                managedRootEvents,
                occurrenceRetirements);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            stepProcessor.close();
        } finally {
            if (sameOrigin != null) sameOrigin.close();
        }
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
        private final SourceCursor sourceContext;
        private final Object originalAttemptToken;
        private final List<ClosureWorkOccurrence> lifecycleWorks;

        private PendingTermination(
                DocumentId documentId,
                ClosureWorkOccurrence requestWork,
                String cause,
                String reason,
                SourceCursor sourceContext,
                Object originalAttemptToken,
                List<ClosureWorkOccurrence> lifecycleWorks) {
            this.documentId = Objects.requireNonNull(
                    documentId, "documentId");
            this.requestWork = Objects.requireNonNull(
                    requestWork, "requestWork");
            this.cause = Objects.requireNonNull(cause, "cause");
            this.reason = reason;
            this.sourceContext = sourceContext;
            this.originalAttemptToken = originalAttemptToken;
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
        private final Map<DocumentId, List<ManagedOccurrenceBinding>> frozenReverse = new LinkedHashMap<>();
        private final ActiveFrame capturedBy;
        private final SourceCursor sourceContext;
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
            this.sourceContext = capturedBy == null ? null : capturedBy.sourceContext;
            this.imported = imported;
            if (!imported) {
                for (ManagedOccurrenceBinding binding : sourceContext != null && sourceContext.realm != null
                        ? sourceContext.realm.routes() : currentBindings)
                    if (binding.active() && reactionDeliveryEligible(binding))
                        frozenReverse.computeIfAbsent(binding.targetDocumentId(), ignored -> new ArrayList<>()).add(binding);
                for (List<ManagedOccurrenceBinding> edges : frozenReverse.values()) edges.sort(ClosureExecutionSession::compareContainingOccurrences);
            }
        }


    }

    private final class ActiveFrame {
        private final ClosureWorkOccurrence work;
        private final SourceCursor sourceContext;
        private final Object originalAttemptToken;
        private final long componentGeneration;
        private final String targetBeforeBlueId;
        private String currentPrePatchBlueId;
        private String entrySiteIdentity;
        private long patchCount;
        private List<ManagedOccurrenceBinding> patchBindings;
        private long applicationEventCount;
        private long timingStarted;
        private boolean timingActive;

        private ActiveFrame(
                ClosureWorkOccurrence work,
                long componentGeneration) {
            this.work = work;
            this.sourceContext = sourceCursorFor(work);
            this.originalAttemptToken = sameOrigin == null ? null : seedStreams.get(work.targetDocumentId()).originalAttemptToken;
            this.componentGeneration = componentGeneration;
            ManagedDocumentSnapshot target = sourceSnapshotFor(work).managedDocument(
                    work.targetDocumentId());
            this.targetBeforeBlueId = target.blueId();
            this.currentPrePatchBlueId = this.targetBeforeBlueId;
            this.entrySiteIdentity = IDENTITIES.observationEntrySiteIdentity(work.workIdentity());
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
