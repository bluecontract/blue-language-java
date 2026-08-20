package blue.language.processor.closure;

import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.model.NodePathEditor;
import blue.language.model.NodeWireForm;
import blue.language.processor.DocumentProcessor;
import blue.language.processor.DocumentUpdateOccurrence;
import blue.language.processor.FrozenJsonPatch;
import blue.language.processor.GasChargeContext;
import blue.language.processor.ManagedCheckpointSettlementBatch;
import blue.language.processor.ManagedDocumentStepContinuation;
import blue.language.processor.ManagedDocumentStepRoute;
import blue.language.processor.ManagedRootChannelOccurrence;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.processor.util.ProcessorContractConstants;
import blue.language.processor.util.ProcessorPointerConstants;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Genuine Phase-B initialization over independently processed Roots. */
final class ClosureAdmissionExecutionSession
        implements ManagedDocumentStepContinuation, AutoCloseable {

    private static final ClosureIdentityService IDENTITIES =
            ClosureIdentityService.INSTANCE;
    private static final String PROVISIONAL_IDENTITY =
            "sha256:0000000000000000000000000000000000000000000000000000000000000000";

    private final ClosureInvocationInput input;
    private final ClosureExecutionRecorder recorder;
    private final ManagedDocumentStepProcessor stepProcessor;
    private final ComponentFinalizationKernel finalizer =
            new ComponentFinalizationKernel();
    private final ClosureFinalizationGasCharger finalizationGas =
            new ClosureFinalizationGasCharger();
    private final ManagedDocumentGraph inputGraph;
    private final Map<DocumentId, Long> inputGenerations =
            new LinkedHashMap<DocumentId, Long>();
    private final Map<DocumentId, Node> latestBodies =
            new LinkedHashMap<DocumentId, Node>();
    private final Map<DocumentId, FrozenInitialization> frozen =
            new LinkedHashMap<DocumentId, FrozenInitialization>();
    private final Set<DocumentId> initialized =
            new LinkedHashSet<DocumentId>();
    private final Set<String> existingBlueIds;
    private final Set<String> establishedBlueIds =
            new LinkedHashSet<String>();
    private final Set<List<DocumentId>> verifiedCyclicComponents =
            new LinkedHashSet<List<DocumentId>>();
    private final Map<DocumentId, List<ManagedRootChannelOccurrence>>
            inputChannelSurfaces =
            new LinkedHashMap<DocumentId,
                    List<ManagedRootChannelOccurrence>>();
    private final Map<DocumentId, List<ManagedRootChannelOccurrence>>
            resultingChannelSurfaces =
            new LinkedHashMap<DocumentId,
                    List<ManagedRootChannelOccurrence>>();

    private AffectedClosureSnapshot currentSnapshot;
    private List<ManagedOccurrenceBinding> currentBindings;
    private ComponentFinalizationResult currentFinalization;
    private ClosureWorkOccurrence activeWork;
    private ClosureWorkOccurrence activeFinalizationOwner;
    private long graphGeneration;
    private long graphChanges;
    private long stepOrdinal;
    private long finalizationsInActiveStep;
    private boolean closed;

    ClosureAdmissionExecutionSession(
            DocumentProcessor owner,
            ClosureInvocationInput input,
            ClosureExecutionRecorder recorder) {
        this.input = Objects.requireNonNull(input, "input");
        this.recorder = Objects.requireNonNull(recorder, "recorder");
        this.currentSnapshot = input.snapshot();
        this.currentBindings = new ArrayList<ManagedOccurrenceBinding>(
                currentSnapshot.occurrences());
        this.graphGeneration = currentSnapshot.graphGeneration();
        ArrayList<DocumentId> ids = new ArrayList<DocumentId>();
        for (ManagedDocumentSnapshot document
                : currentSnapshot.managedDocuments()) {
            ids.add(document.documentId());
            inputGenerations.put(
                    document.documentId(),
                    Long.valueOf(document.componentGeneration()));
            latestBodies.put(document.documentId(), document.document());
            if (document.initialized()) {
                initialized.add(document.documentId());
            } else {
                frozen.put(document.documentId(), new FrozenInitialization(
                        document.blueId(), document.document()));
            }
        }
        this.inputGraph = ManagedDocumentGraph.fromBindings(
                ids, currentBindings);
        this.existingBlueIds = finalizationGas.existingIdentities(
                latestBodies);
        this.stepProcessor = new ManagedDocumentStepProcessor(
                Objects.requireNonNull(owner, "owner"),
                input.executionPolicy(),
                this);
    }

    ClosureExecutionState execute() {
        requireAdmission();
        chargeAdmission();
        captureSurfaces(inputChannelSurfaces);
        currentFinalization = verifyCurrentFinalization();

        List<PlannedWork> plan = planInitialization();
        long workLimit = ClosureAdmissionPortableLimits.limit(
                input, "closureWorkOccurrencesPerInvocation");
        if (plan.size() > workLimit) {
            throw ClosureAdmissionPortableLimits.exceeded(
                    "closureWorkOccurrencesPerInvocation",
                    plan.size(),
                    workLimit);
        }
        for (int index = 0; index < plan.size(); index++) {
            PlannedWork planned = plan.get(index);
            executeOne(planned);
            boolean moreWork = index + 1 < plan.size();
            if (finalizationsInActiveStep == 0L
                    && moreWork
                    && recorder.finalizationCount() == 0L
                    && planned.work.kind() == WorkKind.INITIALIZATION
                    && hasCyclicComponent(currentBindings)) {
                finalizeTentative(
                        TentativeFinalization.Boundary.work(
                                planned.finalizationOwner.ordinal()),
                        planned.finalizationOwner);
            }
        }

        if (!frozen.isEmpty()) {
            installMarkerBatch(plan);
        }
        captureSurfaces(resultingChannelSurfaces);
        return state();
    }

    private void requireAdmission() {
        if (input.operation() != ClosureInvocationInput.Operation.ADMIT_CLOSURE
                || input.cause().kind() != ProcessingCause.Kind.ADMISSION
                || !input.directDeliveries().isEmpty()) {
            throw new IllegalArgumentException(
                    "Admission Phase B requires ADMIT_CLOSURE and zero direct deliveries");
        }
        for (ManagedDocumentSnapshot document
                : input.snapshot().managedDocuments()) {
            if (document.terminated()) {
                throw new ClosureCapabilityGapException(
                        "TERMINATED_MEMBER_POLICY_REQUIRED",
                        "A terminated member cannot be admitted for initialization");
            }
        }
    }

    private void chargeAdmission() {
        charge("processor", "processInvocation", 1L,
                GasChargeContext.reason("admission.process"));
        charge("processor", "closureInvocation", 1L,
                GasChargeContext.reason("admission.closure"));
        for (ManagedDocumentSnapshot document
                : currentSnapshot.managedDocuments()) {
            charge("processor", "managedDocumentOpened", 1L,
                    documentContext(document, "admission.document."
                            + document.documentId().value()));
        }
        for (ManagedOccurrenceBinding binding : currentBindings) {
            ManagedDocumentSnapshot source = currentSnapshot
                    .managedDocument(binding.sourceDocumentId());
            charge("processor", "managedOccurrenceBindingVerified", 1L,
                    documentContext(source, "admission.binding."
                            + binding.occurrenceIdentity()));
            if (binding.active()) {
                charge("processor", "processEmbeddedEdgeExamined", 1L,
                        documentContext(source, "admission.edge."
                                + binding.occurrenceIdentity()));
            }
        }
        for (ComponentSnapshot component : currentSnapshot.components()) {
            for (DocumentId memberId
                    : component.orderedMemberDocumentIds()) {
                charge("processor", "componentMemberPartitioned", 1L,
                        documentContext(
                                currentSnapshot.managedDocument(memberId),
                                "admission.component-member"));
            }
        }
        for (ManagedOccurrenceBinding binding : currentBindings) {
            if (binding.active()) {
                charge("processor", "componentEdgePartitioned", 1L,
                        documentContext(currentSnapshot.managedDocument(
                                binding.sourceDocumentId()),
                                "admission.component-edge"));
            }
        }
    }

    private ComponentFinalizationResult verifyCurrentFinalization() {
        ComponentFinalizationResult verified;
        long finalizationStarted =
                recorder.beginComponentFinalizationProof();
        try {
            verified = finalizer.finalizeComponents(
                    new ComponentFinalizationInput(
                            inputGraph,
                            inputGenerations,
                            latestBodies,
                            currentBindings));
        } finally {
            recorder.endComponentFinalizationProof(finalizationStarted);
        }
        Map<DocumentId, Node> normalizedInput =
                cloneBodies(latestBodies);
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
                    || !sameNode(normalizedInput.get(document.documentId()),
                            exact.document())) {
                throw new IllegalArgumentException(
                        "Admission snapshot does not equal its exact component finalization");
            }
        }
        return verified;
    }

    private List<PlannedWork> planInitialization() {
        ArrayList<PlannedWork> result = new ArrayList<PlannedWork>();
        AdmissionCause cause = (AdmissionCause) input.cause();
        ManagedDocumentGraph graph = ManagedDocumentGraph.fromBindings(
                inputGraph.documentIds(), currentBindings);
        for (List<DocumentId> component
                : new SccPartitioner().partition(graph)) {
            for (DocumentId documentId : component) {
                FrozenInitialization initial = frozen.get(documentId);
                if (initial == null) {
                    continue;
                }
                Node lifecycleEvent = lifecycleEvent(initial.blueId);
                List<ManagedDocumentStepRoute> lifecycle = stepProcessor
                        .classifyLifecycleRoutes(
                                initial.document, lifecycleEvent);
                String initializationChannel = lifecycle.isEmpty()
                        ? "lifecycle" : "initialization";
                ClosureWorkOccurrence initialization = work(
                        result.size(), WorkKind.INITIALIZATION,
                        documentId, initializationChannel,
                        cause.causeIdentity());
                result.add(new PlannedWork(
                        initialization,
                        lifecycleEvent,
                        initialization));
                for (ManagedDocumentStepRoute route : lifecycle) {
                    result.add(new PlannedWork(
                            work(result.size(), WorkKind.LIFECYCLE,
                                    documentId, route.channelKey(),
                                    cause.causeIdentity()),
                            route.exactPayload(),
                            initialization));
                }
            }
        }
        return Collections.unmodifiableList(result);
    }

    private ClosureWorkOccurrence work(
            long ordinal,
            WorkKind kind,
            DocumentId documentId,
            String channelKey,
            String sourceOccurrenceIdentity) {
        String scopeIdentity = IDENTITIES.managedScopeKeyIdentity(
                ManagedScopeKey.root(documentId));
        String workIdentity = IDENTITIES.workOccurrenceIdentity(
                input.invocationIdentity(), ordinal, kind,
                scopeIdentity, sourceOccurrenceIdentity);
        return new ClosureWorkOccurrence(
                ordinal,
                kind,
                documentId,
                channelKey,
                null,
                null,
                scopeIdentity,
                sourceOccurrenceIdentity,
                workIdentity);
    }

    private void executeOne(PlannedWork planned) {
        ClosureWorkOccurrence work = planned.work;
        recorder.accepted(work);
        charge("processor", "closureWorkOccurrenceEnqueued", 1L,
                workContext(work, "work." + work.ordinal() + ".enqueue"));
        charge("processor", "closureWorkOccurrenceDequeued", 1L,
                workContext(work, "work." + work.ordinal() + ".dequeue"));
        ManagedDocumentSnapshot target = currentSnapshot.managedDocument(
                work.targetDocumentId());
        DocumentStepInput step = new DocumentStepInput(
                stepOrdinal++,
                work,
                target,
                planned.payload,
                TentativeResolutionContext.from(
                        input, currentSnapshot, work.targetDocumentId()));
        recorder.step(step);
        activeWork = work;
        activeFinalizationOwner = planned.finalizationOwner;
        finalizationsInActiveStep = 0L;
        LocalDocumentStepResult result;
        long stepStarted = recorder.beginManagedDocumentStep();
        try {
            result = stepProcessor.process(step);
        } finally {
            try {
                activeWork = null;
                activeFinalizationOwner = null;
            } finally {
                recorder.endManagedDocumentStep(stepStarted);
            }
        }
        Node expected = latestBodies.get(work.targetDocumentId());
        if (!sameNode(expected, result.resultingBody())) {
            latestBodies.put(work.targetDocumentId(),
                    result.resultingBody());
        }
    }

    @Override
    public void afterPatch(
            String scopePath,
            Node currentDocument,
            FrozenJsonPatch patch,
            List<DocumentUpdateOccurrence> updates) {
        requireRoot(scopePath);
        ClosureWorkOccurrence work = requireActiveWork();
        ClosureWorkOccurrence finalizationOwner =
                requireActiveFinalizationOwner();
        latestBodies.put(
                work.targetDocumentId(),
                Objects.requireNonNull(currentDocument,
                        "currentDocument").clone());
        finalizeTentative(
                TentativeFinalization.Boundary.work(
                        finalizationOwner.ordinal()),
                finalizationOwner);
        synchronizeManagedReferences(
                work.targetDocumentId(), currentDocument);
        for (DocumentUpdateOccurrence update
                : Objects.requireNonNull(updates, "updates")) {
            if (!stepProcessor.classifyDocumentUpdateRoutes(
                    currentDocument, update).isEmpty()) {
                throw new ClosureCapabilityGapException(
                        "INITIALIZATION_DOCUMENT_UPDATE_QUEUE_REQUIRED",
                        "Initialization-caused Document Update routes are outside the bounded admission lane");
            }
        }
    }

    @Override
    public void onApplicationEvent(
            String scopePath,
            String originContractKey,
            Node event,
            String eventBlueId) {
        requireRoot(scopePath);
        throw new ClosureCapabilityGapException(
                "INITIALIZATION_EVENT_QUEUE_REQUIRED",
                "Initialization-caused application events require the full event queue lane");
    }

    @Override
    public void onTerminationRequested(
            String scopePath,
            String cause,
            String reason) {
        requireRoot(scopePath);
        throw new ClosureCapabilityGapException(
                "INITIALIZATION_TERMINATION_REQUIRED",
                "Initialization-caused termination requires lifecycle settlement");
    }

    private void installMarkerBatch(List<PlannedWork> plan) {
        LinkedHashMap<DocumentId, PreparedMarker> prepared =
                new LinkedHashMap<DocumentId, PreparedMarker>();
        for (Map.Entry<DocumentId, FrozenInitialization> entry
                : frozen.entrySet()) {
            DocumentId documentId = entry.getKey();
            ManagedDocumentSnapshot current = currentSnapshot.managedDocument(
                    documentId);
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
                            new Node().blueId(entry.getValue().blueId));
            prepared.put(documentId, new PreparedMarker(
                    body,
                    marker,
                    documentContext(current,
                            "initialization-batch.marker."
                                    + documentId.value())));
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
        initialized.addAll(markedBodies.keySet());
        long afterWork = plan.isEmpty()
                ? 0L : plan.get(plan.size() - 1).work.ordinal();
        finalizeTentative(
                TentativeFinalization.Boundary.initializationBatch(
                        afterWork),
                null);
    }

    private void finalizeTentative(
            TentativeFinalization.Boundary boundary,
            ClosureWorkOccurrence owner) {
        List<ManagedOccurrenceBinding> reclassified =
                reclassifyBindings();
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
        if (!before.adjacency().equals(after.adjacency())) {
            chargeTopologyChange(before, after, owner);
        }
        graphGeneration = ClosureGraphGenerationTransition.assign(
                input.snapshot().graphGeneration(), inputGraph, after);
        Map<DocumentId, Long> generations =
                ComponentGenerationTransition.assign(
                        inputGraph, inputGenerations, after);
        Map<DocumentId, Node> sourceBodies = cloneBodies(latestBodies);
        ComponentFinalizationResult finalized;
        long finalizationStarted =
                recorder.beginComponentFinalizationProof();
        try {
            finalized = finalizer.finalizeComponents(
                    new ComponentFinalizationInput(
                            inputGraph,
                            inputGenerations,
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
                        ClosureAdmissionPortableLimits.limit(
                                input,
                                "cyclicCanonicalBytesPerComponent"),
                        unverifiedCyclicComponents(finalized));
        requireFinalizationCapacity(cyclicPlan.size());
        ClosureFinalizationGasCharger.FinalizationFrame gasFrame =
                boundary.kind() == TentativeFinalization.Boundary.Kind.WORK
                ? finalizationGas.beginFinalization(
                        stepProcessor, after, generations,
                        Objects.requireNonNull(owner, "owner"),
                        recorder.finalizationCount(),
                        cyclicPlan.memberSets())
                : finalizationGas.beginInitializationBatch(
                        stepProcessor, after, generations,
                        recorder.finalizationCount(),
                        cyclicPlan.memberSets());
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
                                cyclicPlan.canonicalBytes(evidence));
                        verifiedCyclicComponents.add(
                                Collections.unmodifiableList(
                                        new ArrayList<DocumentId>(
                                                evidence.component()
                                                        .orderedMemberDocumentIds())));
                    }
                });
        chargeChangedAcyclic(finalized, boundary, owner);
        currentBindings = new ArrayList<ManagedOccurrenceBinding>(
                finalized.finalizedGraph().bindings());
        latestBodies.clear();
        for (FinalizedDocumentEvidence document
                : finalized.documents().values()) {
            latestBodies.put(document.documentId(), document.document());
        }
        currentFinalization = finalized;
        currentSnapshot = snapshot(finalized);
        if (activeWork != null) {
            finalizationsInActiveStep++;
        }
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

    private List<ManagedOccurrenceBinding> reclassifyBindings() {
        ArrayList<ManagedOccurrenceBinding> result =
                new ArrayList<ManagedOccurrenceBinding>();
        for (ManagedOccurrenceBinding binding : currentBindings) {
            Node value = NodePathEditor.getOrNull(
                    latestBodies.get(binding.sourceDocumentId()),
                    binding.sourcePath());
            if (value == null) {
                result.add(binding);
                continue;
            }
            ManagedDocumentSnapshot target = currentSnapshot.managedDocument(
                    binding.targetDocumentId());
            if (!sameExactTarget(value, target)) {
                if (binding.active()) {
                    throw new IllegalArgumentException(
                            "Active occurrence no longer identifies its managed target");
                }
                result.add(binding);
                continue;
            }
            if (!binding.active()) {
                LinkedHashMap<String, String> expected =
                        new LinkedHashMap<String, String>();
                expected.put(binding.sourcePath(), target.blueId());
                stepProcessor.validateManagedEmbeddedPaths(
                        latestBodies.get(binding.sourceDocumentId()),
                        expected);
            }
            result.add(ManagedOccurrenceBinding.derived(
                    binding.bindingPolicyIdentity(),
                    binding.sourceDocumentId(),
                    binding.sourceAddress(),
                    binding.targetDocumentId(),
                    target.blueId(),
                    true,
                    null));
        }
        Collections.sort(result);
        return result;
    }

    private void chargeTopologyChange(
            ManagedDocumentGraph before,
            ManagedDocumentGraph after,
            ClosureWorkOccurrence owner) {
        Set<String> prior = new HashSet<String>();
        for (ManagedOccurrenceBinding binding : before.activeBindings()) {
            prior.add(binding.occurrenceIdentity());
        }
        long activated = 0L;
        for (ManagedOccurrenceBinding binding : after.activeBindings()) {
            if (!prior.contains(binding.occurrenceIdentity())) {
                activated++;
            }
        }
        GasChargeContext context = owner != null
                ? workContext(owner, "work." + owner.ordinal()
                        + ".topology-change")
                : GasChargeContext.reason(
                        "initialization-batch.topology-change");
        if (activated > 0L) {
            charge("processor", "managedOccurrenceBindingVerified",
                    activated, context);
            charge("processor", "processEmbeddedEdgeExamined",
                    activated, context);
        }
        if (ClosureGraphGenerationTransition.componentPartitionChanged(
                before, after)) {
            charge("processor", "componentPartitionChanged", 1L, context);
        }
        charge("processor", "componentMemberPartitioned",
                after.documentIds().size(), context);
        if (!after.activeBindings().isEmpty()) {
            charge("processor", "componentEdgePartitioned",
                    after.activeBindings().size(), context);
        }
    }

    private void chargeChangedAcyclic(
            ComponentFinalizationResult finalized,
            TentativeFinalization.Boundary boundary,
            ClosureWorkOccurrence owner) {
        for (FinalizedComponentEvidence component : finalized.components()) {
            if (component.component().kind() != ComponentKind.ACYCLIC) {
                continue;
            }
            DocumentId documentId = component.component()
                    .orderedMemberDocumentIds().get(0);
            FinalizedDocumentEvidence after = finalized.document(documentId);
            ManagedDocumentSnapshot before = currentSnapshot.managedDocument(
                    documentId);
            if (before.blueId().equals(after.blueId())) {
                continue;
            }
            if (boundary.kind()
                    == TentativeFinalization.Boundary.Kind.WORK) {
                finalizationGas.chargeAcyclicChangedBody(
                        stepProcessor, documentId, after.document(),
                        after.componentGeneration(),
                        Objects.requireNonNull(owner, "owner"),
                        establishedBlueIds, existingBlueIds);
            } else {
                finalizationGas.chargeInitializationAcyclicChangedBody(
                        stepProcessor, documentId, after.document(),
                        after.componentGeneration(),
                        establishedBlueIds, existingBlueIds);
            }
        }
    }

    private void recordCyclicFinalization(
            FinalizedComponentEvidence evidence,
            TentativeFinalization.Boundary boundary,
            long canonicalBytes) {
        if (evidence.cyclicFinalization() == null) {
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
                evidence.cyclicFinalization().masterBlueId(),
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

    private AffectedClosureSnapshot snapshot(
            ComponentFinalizationResult finalized) {
        ArrayList<ManagedDocumentSnapshot> documents =
                new ArrayList<ManagedDocumentSnapshot>();
        for (ManagedDocumentSnapshot original
                : input.snapshot().managedDocuments()) {
            FinalizedDocumentEvidence exact = finalized.document(
                    original.documentId());
            documents.add(new ManagedDocumentSnapshot(
                    original.documentId(),
                    exact.blueId(),
                    exact.document(),
                    initialized.contains(original.documentId()),
                    original.terminated(),
                    original.publicRoot(),
                    original.epoch(),
                    exact.componentGeneration()));
        }
        ArrayList<ComponentSnapshot> components =
                new ArrayList<ComponentSnapshot>();
        for (FinalizedComponentEvidence component
                : finalized.components()) {
            components.add(component.component());
        }
        String bindingIdentity = IDENTITIES.occurrenceBindingSetIdentity(
                currentBindings);
        AffectedClosureSnapshot provisional = new AffectedClosureSnapshot(
                PROVISIONAL_IDENTITY,
                graphGeneration,
                documents,
                currentBindings,
                bindingIdentity,
                components,
                input.snapshot().publicRootDocumentIds());
        return new AffectedClosureSnapshot(
                IDENTITIES.affectedClosureIdentity(provisional),
                graphGeneration,
                documents,
                currentBindings,
                bindingIdentity,
                components,
                input.snapshot().publicRootDocumentIds());
    }

    ClosureExecutionState state() {
        return new ClosureExecutionState(
                currentSnapshot,
                currentFinalization,
                Collections.<PublicEventOccurrence>emptyList(),
                stepProcessor.processorGasTrace(),
                inputChannelSurfaces,
                resultingChannelSurfaces,
                Collections.<ManagedCheckpointSettlementBatch.Mutation>
                        emptyList(),
                Collections.<DocumentId>emptySet());
    }

    private void captureSurfaces(
            Map<DocumentId, List<ManagedRootChannelOccurrence>> target) {
        target.clear();
        for (ManagedDocumentSnapshot document
                : currentSnapshot.managedDocuments()) {
            target.put(document.documentId(),
                    stepProcessor.projectRootChannelSurface(
                            latestBodies.get(document.documentId())));
        }
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
            Node value = NodePathEditor.getOrNull(
                    finalized, binding.sourcePath());
            if (value != null) {
                NodePathEditor.put(runtimeDocument,
                        binding.sourcePath(), value.clone());
            }
        }
    }

    private static boolean sameExactTarget(
            Node value,
            ManagedDocumentSnapshot target) {
        if (value.isReferenceOnly()) {
            return value.getBlueId().equals(target.blueId());
        }
        return sameNode(value, target.document());
    }

    private boolean hasCyclicComponent(
            List<ManagedOccurrenceBinding> bindings) {
        ManagedDocumentGraph graph = ManagedDocumentGraph.fromBindings(
                inputGraph.documentIds(), bindings);
        for (List<DocumentId> members
                : new SccPartitioner().partition(graph)) {
            if (members.size() > 1 || graph.hasSelfEdge(members.get(0))) {
                return true;
            }
        }
        return false;
    }

    private GasChargeContext documentContext(
            ManagedDocumentSnapshot document,
            String reason) {
        return GasChargeContext.closure(
                document.documentId().value(),
                "/",
                Long.valueOf(0L),
                Long.valueOf(document.componentGeneration()),
                null,
                null,
                null,
                reason);
    }

    private GasChargeContext workContext(
            ClosureWorkOccurrence work,
            String reason) {
        ManagedDocumentSnapshot target = currentSnapshot.managedDocument(
                work.targetDocumentId());
        return GasChargeContext.closure(
                work.targetDocumentId().value(),
                "/",
                Long.valueOf(0L),
                Long.valueOf(target.componentGeneration()),
                work.channelKey().isEmpty() ? null : work.channelKey(),
                "work/" + work.ordinal(),
                work.workIdentity(),
                reason);
    }

    private void charge(
            String namespace,
            String counter,
            long quantity,
            GasChargeContext context) {
        stepProcessor.charge(namespace, counter, quantity, context);
    }

    private ClosureWorkOccurrence requireActiveWork() {
        if (activeWork == null) {
            throw new IllegalStateException(
                    "Admission continuation has no active document step");
        }
        return activeWork;
    }

    private ClosureWorkOccurrence requireActiveFinalizationOwner() {
        if (activeFinalizationOwner == null) {
            throw new IllegalStateException(
                    "Admission continuation has no finalization owner");
        }
        return activeFinalizationOwner;
    }

    private static Node lifecycleEvent(String preInitializationBlueId) {
        return new Node()
                .type(new Node().blueId(
                        RuntimeBlueIds.DOCUMENT_PROCESSING_INITIATED))
                .properties(
                        ProcessorContractConstants.KEY_DOCUMENT,
                        new Node().blueId(preInitializationBlueId));
    }

    private static void requireRoot(String scopePath) {
        if (!"/".equals(scopePath)) {
            throw new IllegalStateException(
                    "Managed admission continuation escaped Root scope");
        }
    }

    private static boolean sameNode(Node left, Node right) {
        return left == right || (left != null && right != null
                && NodeWireForm.get(left).equals(NodeWireForm.get(right)));
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

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            stepProcessor.close();
        }
    }

    private static final class FrozenInitialization {
        private final String blueId;
        private final Node document;

        private FrozenInitialization(String blueId, Node document) {
            this.blueId = blueId;
            this.document = document.clone();
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

    private static final class PlannedWork {
        private final ClosureWorkOccurrence work;
        private final Node payload;
        private final ClosureWorkOccurrence finalizationOwner;

        private PlannedWork(
                ClosureWorkOccurrence work,
                Node payload,
                ClosureWorkOccurrence finalizationOwner) {
            this.work = Objects.requireNonNull(work, "work");
            this.payload = Objects.requireNonNull(payload, "payload").clone();
            this.finalizationOwner = Objects.requireNonNull(
                    finalizationOwner, "finalizationOwner");
            if (!this.work.targetDocumentId().equals(
                    this.finalizationOwner.targetDocumentId())) {
                throw new IllegalArgumentException(
                        "Admission work and finalization owner target different documents");
            }
        }
    }
}
