package blue.language.processor.closure;

import blue.language.processor.ProcessorStatus;
import blue.language.snapshot.FrozenNode;

import java.util.*;
import java.util.function.Function;

/** One quiescent driver cut, indexed once, producing separately atomic owned operation results. */
final class SameOriginOperationResultAssembler {
    private final ClosureInvocationInput input;
    private final ClosureExecutionState state;
    private final AffectedClosureSnapshot before, after;
    private final Map<DocumentId, ManagedDocumentSnapshot> beforeDocuments, afterDocuments;
    private final Map<DocumentId, ComponentSnapshot> beforeComponents, afterComponents;
    private final Map<DocumentId, List<ManagedOccurrenceBinding>> beforeBindings, afterBindings;
    private final Map<DocumentId, List<GraphChange>> changes;
    private final Map<DocumentId, List<SubscriptionDelta>> subscriptions;
    private final Map<DocumentId, List<CheckpointWrite>> checkpoints;
    private final Map<DocumentId, List<ManagedRootEventOccurrence>> events;
    private final Map<DocumentId, SourceObservationProgram.SourceState> originalSourceStates = new HashMap<>();
    private final Map<DocumentId, SourceObservationProgram.SourceState> settledSourceStates = new HashMap<>();
    private final Map<DocumentId, String> settledSourceOperations = new HashMap<>();
    private final Set<String> indexedBorrowedPrograms = new HashSet<>();
    private final Map<DocumentId, Map<String, SourceObservationProgram.SourceState>> borrowedSourceStates = new HashMap<>();
    private final Map<DocumentId, Map<String, SourceObservationProgram.SourceState>> borrowedSourcePredecessors = new HashMap<>();
    private final Map<DocumentId, Map<String, SourceObservationProgram.SourceState>> retainedFailurePredecessors = new HashMap<>();
    private final Map<DocumentId, Map<DocumentId, List<SourceObservationGap>>> observationGaps;

    SameOriginOperationResultAssembler(ClosureInvocationInput input, ClosureExecutionState state) {
        this(input, state, Collections.emptyList());
    }

    SameOriginOperationResultAssembler(ClosureInvocationInput input, ClosureExecutionState state,
            Collection<SourceOperationFailure> retainedFailures) {
        this(input, state, retainedFailures, Collections.emptyMap(), Collections.emptyList());
    }

    SameOriginOperationResultAssembler(ClosureInvocationInput input, ClosureExecutionState state,
            Collection<SourceOperationFailure> retainedFailures, Map<DocumentId, List<SourceObservationGap>> observationGaps) {
        this(input, state, retainedFailures, observationGaps, Collections.emptyList());
    }

    SameOriginOperationResultAssembler(ClosureInvocationInput input, ClosureExecutionState state,
            Collection<SourceOperationFailure> retainedFailures, Map<DocumentId, List<SourceObservationGap>> observationGaps,
            Collection<SourceObservationProgram> retainedSourcePrograms) {
        this.input = Objects.requireNonNull(input); this.state = Objects.requireNonNull(state);
        this.observationGaps = new HashMap<>();
        for (Map.Entry<DocumentId, List<SourceObservationGap>> entry : Objects.requireNonNull(observationGaps).entrySet())
            for (SourceObservationGap gap : entry.getValue()) {
                if (!entry.getKey().equals(gap.source())) throw new IllegalArgumentException("Gap index names another source");
                this.observationGaps.computeIfAbsent(entry.getKey(), ignored -> new HashMap<>())
                        .computeIfAbsent(gap.consumer(), ignored -> new ArrayList<>()).add(gap);
            }
        if (input.cause().kind() != ProcessingCause.Kind.EXTERNAL)
            throw new IllegalArgumentException("Same-origin result assembly requires one external canonical cause");
        before = input.snapshot(); after = state.tentativeSnapshot();
        beforeDocuments = documents(before); afterDocuments = documents(after);
        beforeComponents = components(before); afterComponents = components(after);
        beforeBindings = index(before.occurrences(), ManagedOccurrenceBinding::sourceDocumentId);
        afterBindings = index(after.occurrences(), ManagedOccurrenceBinding::sourceDocumentId);
        changes = index(ClosureSuccessResultAssembler.graphChanges(before, after), GraphChange::sourceDocumentId);
        subscriptions = index(ClosureSuccessResultAssembler.subscriptionDeltas(before, after,
                state.inputChannelSurfaces(), state.resultingChannelSurfaces()), value ->
                (value.afterSubscription() == null ? value.beforeSubscription() : value.afterSubscription())
                        .channelOccurrence().managedDocumentId());
        checkpoints = index(ClosureSuccessResultAssembler.checkpointWrites(after, state.checkpointMutations()),
                value -> value.targetManagedScopeKey().documentId());
        events = index(state.managedRootEvents(), ManagedRootEventOccurrence::sourceDocumentId);
        for (SourceOperationFailure failure : Objects.requireNonNull(retainedFailures)) {
            if (!input.cause().causeIdentity().equals(failure.causeIdentity()))
                throw new IllegalArgumentException("Retained failure has another source cause");
            for (SourceObservationProgram.SourceState prior : failure.sourcePredecessors())
                if (failure.ownedDocumentIds().contains(prior.documentId()))
                    retainedFailurePredecessors.computeIfAbsent(prior.documentId(), ignored -> new HashMap<>())
                            .put(failure.invocationIdentity(), prior);
        }
        borrowedStates(new ArrayList<>(Objects.requireNonNull(retainedSourcePrograms)));
    }

    SameOriginOperationResult assemble(Set<DocumentId> owned, Map<DocumentId, String> originalSeedByMember,
            List<SameOriginGroupIdentity.Admission> admissions, Map<DocumentId, String> consumedSourceOperations,
            List<blue.language.processor.GasTraceEntry> canonicalGasTrace,
            SameOriginAttemptCoordinator.Failure failure, SourceObservationRecorder.Captured capture,
            Map<DocumentId, Set<DocumentId>> observedByConsumer) {
        SortedSet<DocumentId> owners = new TreeSet<>(Objects.requireNonNull(owned));
        if (owners.isEmpty() || !owners.equals(originalSeedByMember.keySet()))
            throw new IllegalArgumentException("Final group seed ownership is incomplete");
        if (!Collections.disjoint(owners, settledSourceOperations.keySet()))
            throw new IllegalStateException("One original cause cannot settle the same owned lineage twice");
        boolean success = failure == null;
        if (success != (capture != null)) throw new IllegalArgumentException("Only successful groups export source observations");
        if (!success && !owners.equals(failure.members)) throw new IllegalArgumentException("Failure belongs to another atomic group");
        if (!success && failure.reservedGas != 0L)
            throw new IllegalStateException("A failed group cannot settle before all child-ledger reservations are discharged");
        List<ComponentSnapshot> originalComponents = selectComponents(beforeComponents, owners);
        List<ComponentSnapshot> resultingComponents = selectComponents(success ? afterComponents : beforeComponents, owners);
        String operation = SameOriginGroupIdentity.of(originalSeedByMember, admissions, consumedSourceOperations).identity();
        List<SameOriginOperationResult.Admission> accepted = new ArrayList<>();
        for (SameOriginGroupIdentity.Admission admission : admissions)
            accepted.add(new SameOriginOperationResult.Admission(admission.canonicalSite(), admission.members()));

        List<ManagedDocumentSnapshot> predecessors = new ArrayList<>();
        List<ResultingDocument> results = new ArrayList<>();
        List<SourceObservationProgram.SourceState> sourceBefore = new ArrayList<>(), sourceAfter = new ArrayList<>();
        for (DocumentId owner : owners) {
            ManagedDocumentSnapshot prior = require(beforeDocuments, owner), current = require(afterDocuments, owner);
            predecessors.add(prior);
            if (!success && (!prior.blueId().equals(current.blueId()) || prior.initialized() != current.initialized()
                    || prior.terminated() != current.terminated()))
                throw new IllegalStateException("Failed group was not restored to its exact predecessor before settlement");
            ManagedDocumentSnapshot exact = success ? current : prior;
            ComponentSnapshot component = (success ? afterComponents : beforeComponents).get(owner);
            long epoch = prior.epoch();
            // A selected, successfully processed own operation advances once even if its body is
            // unchanged. Metadata-only cursor progress is not an operation and has no group here.
            if (success && state.epochAdvanceDocuments().contains(owner)) epoch = nextEpoch(epoch);
            results.add(new ResultingDocument(owner, prior.blueId(), exact.blueId(), exact.document(),
                    exact.initialized(), exact.terminated(), exact.publicRoot(), epoch, exact.componentGeneration(),
                    component.componentIdentity(), component.componentStateIdentity(), ClosureResultAssemblySupport.memberIndex(exact.blueId())));
            sourceBefore.add(originalSourceState(owner)); sourceAfter.add(sourceState(exact, epoch));
        }
        List<ManagedOccurrenceBinding> ownedBeforeBindings = select(beforeBindings, owners);
        List<ManagedOccurrenceBinding> ownedAfterBindings = select(success ? afterBindings : beforeBindings, owners);
        List<GraphChange> ownedChanges = new ArrayList<>();
        List<SubscriptionDelta> ownedSubscriptions = new ArrayList<>();
        List<CheckpointWrite> ownedCheckpoints = new ArrayList<>();
        List<SameOriginOperationResult.Event> ownedEvents = new ArrayList<>();
        if (success) {
            List<GraphChange> orderedChanges = select(changes, owners);
            orderedChanges.sort(Comparator.comparingLong(GraphChange::graphChangeOrdinal));
            for (GraphChange value : orderedChanges) ownedChanges.add(new GraphChange(ownedChanges.size(),
                    value.changeKind(), value.sourceDocumentId(), value.sourcePath(), value.before(), value.after()));
            List<SubscriptionDelta> orderedSubscriptions = select(subscriptions, owners);
            orderedSubscriptions.sort(Comparator.comparingLong(SubscriptionDelta::subscriptionDeltaOrdinal));
            for (SubscriptionDelta value : orderedSubscriptions) ownedSubscriptions.add(new SubscriptionDelta(ownedSubscriptions.size(),
                    value.operation(), value.targetManagedScopeIdentity(), value.channelOccurrenceIdentity(),
                    value.beforeSubscription(), value.afterSubscription()));
            List<CheckpointWrite> orderedCheckpoints = select(checkpoints, owners);
            orderedCheckpoints.sort(Comparator.comparingLong(CheckpointWrite::checkpointWriteOrdinal));
            for (CheckpointWrite value : orderedCheckpoints) ownedCheckpoints.add(new CheckpointWrite(ownedCheckpoints.size(),
                    value.targetManagedScopeKey(), value.targetManagedScopeIdentity(), value.rawChannelKey(),
                    value.beforePresent() ? new CheckpointWrite.State(value.beforeDomainBlueId(), value.beforeDomainValue(), value.beforeSubjectBlueId()) : null,
                    value.afterPresent() ? new CheckpointWrite.State(value.afterDomainBlueId(), value.afterDomainValue(), value.afterSubjectBlueId()) : null));
            List<ManagedRootEventOccurrence> orderedEvents = select(events, owners);
            orderedEvents.sort(Comparator.comparingLong(ManagedRootEventOccurrence::occurrenceOrdinal));
            Set<String> occurrenceIds = new HashSet<>();
            for (ManagedRootEventOccurrence value : orderedEvents) {
                if (!occurrenceIds.add(value.occurrenceIdentity())) throw new IllegalStateException("Original source event occurrence is duplicated");
                ownedEvents.add(new SameOriginOperationResult.Event(ownedEvents.size(), value.sourceDocumentId(),
                        value.occurrenceIdentity(), value.eventBlueId(), FrozenNode.fromResolvedNode(value.exactEvent())));
            }
        }
        List<GasTraceEntry> gas = canonicalGas(canonicalGasTrace, owners);
        long totalGas = ClosureResultAssemblySupport.totalGas(gas);
        if (!success && totalGas != failure.admittedGas)
            throw new IllegalStateException("Frozen failure prefix does not match canonical admitted group gas");
        if (success && totalGas > input.executionPolicy().sharedLimit())
            throw new IllegalStateException("Successful atomic group exceeds its shared gas policy");

        SourceObservationProgram program = null;
        if (success) {
            Map<DocumentId, Map<String, SourceObservationProgram.SourceState>> borrowedStates = borrowedStates(capture.borrowedPrograms());
            // A source observed and retired within this operation is still consumed even when
            // neither boundary contains its temporary occurrence. Read-only references are not.
            for (DocumentId dependency : new TreeSet<>(consumedSourceOperations.keySet())) {
                String consumedOperation = consumedSourceOperations.get(dependency);
                SourceObservationProgram.SourceState consumedResult = dependencyResult(dependency, consumedOperation, borrowedStates);
                if (consumedResult == null) throw new IllegalStateException("Consumed source operation has no authenticated terminal view");
                SourceObservationProgram.SourceState consumedBefore = dependencyPredecessor(dependency, consumedOperation);
                sourceBefore.add(consumedBefore); sourceAfter.add(consumedResult);
            }
            sourceBefore.sort(Comparator.comparing(SourceObservationProgram.SourceState::documentId));
            sourceAfter.sort(Comparator.comparing(SourceObservationProgram.SourceState::documentId));
            Map<String, ManagedReadPin> selectedPins = new TreeMap<>();
            // An unconsumed dependency's current host cell or optional cache pin is not a source
            // observation. Its exact occurrence reference remains in the owned topology; a later
            // interpreter can request that exact body when needed. Accepted installation views,
            // unlike physical cache inventory, are mandatory facts of the producing operation.
            for (AcceptedAttachmentView acceptedView : capture.acceptedViews())
                retainSelectedPin(selectedPins, acceptedView.selectedView());
            for (AcceptedInitializationInstallation installation : capture.acceptedInitializations())
                retainSelectedPin(selectedPins, installation.selectedView());
            for (SourceObservationProgram.Step step : capture.steps())
                if (!owners.contains(step.targetDocumentId())) throw new IllegalArgumentException("Source capture owns a foreign step");
            for (SourceObservationProgram.ReferenceProjection projection : capture.projections())
                if (!owners.contains(projection.targetDocumentId())) throw new IllegalArgumentException("Source capture owns a foreign projection");
            program = new SourceObservationProgram(operation, input.cause().kind(), input.cause().causeIdentity(),
                    (ExternalEventCause) input.cause(), input.environment(), input.executionPolicy(), sourceBefore, sourceAfter,
                    owners, capture.steps(), capture.projections(), ownedBeforeBindings, ownedAfterBindings,
                    originalComponents, resultingComponents, capture.borrowedPrograms(), new ArrayList<>(selectedPins.values()),
                    capture.acceptedViews(), input.managedReaction().orElse(null), capture.acceptedInitializations(), capture.skippedWork());
        }
        Map<DocumentId, Set<DocumentId>> consumersBySource = new HashMap<>();
        for (Map.Entry<DocumentId, Set<DocumentId>> consumer : Objects.requireNonNull(observedByConsumer).entrySet()) {
            if (!owners.contains(consumer.getKey())) throw new IllegalArgumentException("Observed consumer is outside this final group");
            for (DocumentId source : consumer.getValue()) {
                if (!consumedSourceOperations.containsKey(source)) throw new IllegalArgumentException("Observed source is not an external consumed dependency");
                consumersBySource.computeIfAbsent(source, ignored -> new TreeSet<>()).add(consumer.getKey());
            }
        }
        Set<String> dueOccurrences = new HashSet<>();
        input.managedReaction().ifPresent(context -> context.dueOccurrences().forEach(due -> dueOccurrences.add(due.occurrenceIdentity())));
        Map<DocumentId, List<ManagedOccurrenceBinding>> observationBindingsByTarget = index(ownedBeforeBindings, ManagedOccurrenceBinding::targetDocumentId);
        List<SameOriginOperationResult.ObservedSource> observedSources = new ArrayList<>();
        for (Map.Entry<DocumentId, String> dependency : consumedSourceOperations.entrySet()) {
            SourceObservationProgram.SourceState offeredBefore = dependencyPredecessor(dependency.getKey(), dependency.getValue());
            observedSources.addAll(observedSourcesForGroup(owners, offeredBefore,
                    observationBindingsByTarget.getOrDefault(dependency.getKey(), Collections.emptyList()),
                    observationGaps.getOrDefault(dependency.getKey(), Collections.emptyMap()),
                    consumersBySource.getOrDefault(dependency.getKey(), Collections.emptySet()), dueOccurrences));
        }
        SameOriginOperationResult completed = new SameOriginOperationResult(input, operation, originalSeedByMember, accepted, consumedSourceOperations, observedSources,
                success ? ProcessorStatus.SUCCESS : failure.status, predecessors, results, resultingComponents,
                ownedAfterBindings, ownedChanges, ownedSubscriptions, ownedCheckpoints, ownedEvents, gas, program,
                success ? null : failure(failure));
        for (SourceObservationProgram.SourceState settled : sourceAfter) if (owners.contains(settled.documentId())) {
            settledSourceStates.put(settled.documentId(), settled);
            settledSourceOperations.put(settled.documentId(), operation);
        }
        return completed;
    }

    private SourceObservationProgram.SourceState dependencyPredecessor(DocumentId dependency, String operation) {
        if (operation.equals(settledSourceOperations.get(dependency))) return originalSourceState(dependency);
        SourceObservationProgram.SourceState value = borrowedSourcePredecessors.getOrDefault(dependency, Collections.emptyMap()).get(operation);
        if (value == null) value = retainedFailurePredecessors.getOrDefault(dependency, Collections.emptyMap()).get(operation);
        if (value == null) throw new IllegalStateException("Consumed source operation has no authenticated predecessor");
        return value;
    }

    static List<SameOriginOperationResult.ObservedSource> observedSourcesForGroup(Set<DocumentId> owners,
            SourceObservationProgram.SourceState offeredBefore, List<ManagedOccurrenceBinding> ownedBindings,
            Map<DocumentId, List<SourceObservationGap>> gapsByConsumer, Set<DocumentId> actualConsumers, Set<String> dueOccurrences) {
        List<SameOriginOperationResult.ObservedSource> selected = new ArrayList<>();
        Map<DocumentId, List<ManagedOccurrenceBinding>> bindingsByConsumer = index(ownedBindings, ManagedOccurrenceBinding::sourceDocumentId);
        for (DocumentId consumer : new TreeSet<>(actualConsumers)) {
            if (!owners.contains(consumer)) throw new IllegalArgumentException("Observed consumer is outside this group");
            List<ManagedOccurrenceBinding> eligible = new ArrayList<>();
            for (ManagedOccurrenceBinding binding : bindingsByConsumer.getOrDefault(consumer, Collections.emptyList()))
                if (binding.sourceDocumentId().equals(consumer) && binding.targetDocumentId().equals(offeredBefore.documentId())
                        && (binding.active() || dueOccurrences.contains(binding.occurrenceIdentity()))) eligible.add(binding);
            // A newly attached then failed occurrence has no successful pre-existing import lane.
            // The group still consumes its dependency, without inventing a rollback gap for it.
            if (eligible.isEmpty()) continue;
            selected.add(observedSource(consumer, offeredBefore, eligible, gapsByConsumer.getOrDefault(consumer, Collections.emptyList())));
        }
        return Collections.unmodifiableList(selected);
    }

    private static SameOriginOperationResult.ObservedSource observedSource(DocumentId consumer,
            SourceObservationProgram.SourceState offeredBefore, List<ManagedOccurrenceBinding> ownedBindings, List<SourceObservationGap> gaps) {
        DocumentId source = offeredBefore.documentId();
        String observedBlueId = offeredBefore.blueId(); long observedEpoch = offeredBefore.epoch();
        if (!gaps.isEmpty()) {
            SourceObservationGap first = gaps.get(0);
            observedBlueId = first.observedBlueId(); observedEpoch = first.observedEpoch();
            String nextBlueId = observedBlueId; long nextEpoch = observedEpoch;
            for (SourceObservationGap gap : gaps) {
                if (!consumer.equals(gap.consumer()) || !source.equals(gap.source())
                        || !observedBlueId.equals(gap.observedBlueId()) || observedEpoch != gap.observedEpoch()
                        || !nextBlueId.equals(gap.offeredBefore().blueId()) || nextEpoch != gap.offeredBefore().epoch())
                    throw new IllegalArgumentException("Consumed source observation gaps do not form one authenticated successful-pin chain");
                nextBlueId = gap.offeredAfter().blueId(); nextEpoch = gap.offeredAfter().epoch();
            }
            if (!offeredBefore.blueId().equals(nextBlueId) || offeredBefore.epoch() != nextEpoch)
                throw new IllegalArgumentException("Consumed source observation gaps do not reach the offered predecessor");
        }
        // This is the canonical external observation prestate, not every read-only alias.
        // In particular a new pending initialization view must not replace an existing live pin.
        boolean liveObservation = false, matches = false;
        for (ManagedOccurrenceBinding binding : ownedBindings) {
            if (!consumer.equals(binding.sourceDocumentId()) || !binding.targetDocumentId().equals(source)) continue;
            liveObservation = true;
            if (binding.expectedTargetBlueId().equals(observedBlueId)) matches = true;
        }
        if (liveObservation && !matches)
            throw new IllegalArgumentException("Consumed source observation does not match an original active selected reference");
        return new SameOriginOperationResult.ObservedSource(consumer, source, observedBlueId, observedEpoch);
    }

    private SourceObservationProgram.SourceState originalSourceState(DocumentId id) {
        return originalSourceStates.computeIfAbsent(id, key -> {
            ManagedDocumentSnapshot prior = require(beforeDocuments, key);
            return sourceState(prior, prior.epoch());
        });
    }

    private SourceObservationProgram.SourceState dependencyResult(DocumentId id, String consumedOperation,
            Map<DocumentId, Map<String, SourceObservationProgram.SourceState>> borrowed) {
        ManagedDocumentSnapshot current = require(afterDocuments, id);
        SourceObservationProgram.SourceState settled = settledSourceStates.get(id);
        if (settled != null && Objects.equals(consumedOperation, settledSourceOperations.get(id))) {
            if (!settled.blueId().equals(current.blueId())) throw new IllegalStateException("Settled producer changed outside its atomic group");
            return settled;
        }
        // Cold already-authoritative dependencies expose their real epoch in authenticated source
        // programs; a driver's tentative document epoch is not a settlement assertion.
        SourceObservationProgram.SourceState evidence = borrowed.getOrDefault(id, Collections.emptyMap()).get(consumedOperation);
        if (evidence != null) {
            if (!evidence.blueId().equals(current.blueId())) throw new IllegalStateException("Borrowed source result disagrees with final exact dependency view");
            return evidence;
        }
        SourceObservationProgram.SourceState failedPredecessor = retainedFailurePredecessors
                .getOrDefault(id, Collections.emptyMap()).get(consumedOperation);
        if (failedPredecessor != null) {
            if (!failedPredecessor.blueId().equals(current.blueId()))
                throw new IllegalStateException("Consumed failure disagrees with its unchanged exact source view");
            return failedPredecessor;
        }
        if (state.epochAdvanceDocuments().contains(id) && consumedOperation != null)
            throw new IllegalStateException("Consumed same-origin producer must be assembled before its observer");
        // In particular, do not turn an ambient read-only cell into a receipt-bound epoch.
        // A consumed failure's unchanged view must come from its authenticated disposition above.
        return null;
    }

    private Map<DocumentId, Map<String, SourceObservationProgram.SourceState>> borrowedStates(List<SourceObservationProgram> roots) {
        Deque<SourceObservationProgram> pending = new ArrayDeque<>(roots);
        while (!pending.isEmpty()) {
            SourceObservationProgram program = pending.removeFirst();
            if (!indexedBorrowedPrograms.add(program.invocationIdentity())) continue;
            for (SourceObservationProgram.SourceState value : program.sourceResults())
                if (program.ownedDocumentIds().contains(value.documentId()))
                    borrowedSourceStates.computeIfAbsent(value.documentId(), ignored -> new HashMap<>()).put(program.invocationIdentity(), value);
            for (SourceObservationProgram.SourceState value : program.sourcePredecessors())
                if (program.ownedDocumentIds().contains(value.documentId()))
                    borrowedSourcePredecessors.computeIfAbsent(value.documentId(), ignored -> new HashMap<>()).put(program.invocationIdentity(), value);
            pending.addAll(program.borrowedPrograms());
        }
        return borrowedSourceStates;
    }

    private static void retainSelectedPin(Map<String, ManagedReadPin> pins, ManagedReadPin pin) {
        pins.put(pin.documentId().value() + "\u0000" + pin.blueId(), pin);
    }

    private static SameOriginOperationResult.Failure failure(SameOriginAttemptCoordinator.Failure value) {
        SameOriginOperationResult.ChargeRejection charge = value.rejectedCharge == null ? null
                : new SameOriginOperationResult.ChargeRejection(value.rejectedCharge);
        SameOriginOperationResult.JoinRejection join = null;
        if (value.rejectedAdmission != null) {
            List<SameOriginOperationResult.Contribution> contributions = new ArrayList<>();
            for (SameOriginAttemptCoordinator.AdmissionContribution contribution : value.rejectedAdmissionContributions)
                contributions.add(new SameOriginOperationResult.Contribution(contribution.members,
                        contribution.gas.admitted(), contribution.gas.reserved(), contribution.gas.admittedLocal(), contribution.gas.reservedLocal()));
            if (contributions.isEmpty()) throw new IllegalStateException("Rejected admission has no canonical participant ownership");
            join = new SameOriginOperationResult.JoinRejection(value.rejectedAdmission.limit(),
                    value.rejectedAdmission.localDocumentId(), value.rejectedAdmission.localLimit(), contributions);
        }
        if (value.status == ProcessorStatus.GAS_LIMIT_EXCEEDED && (charge == null || join != null))
            throw new IllegalStateException("Gas exhaustion requires its real ordinary charge rejection");
        if (value.status == ProcessorStatus.RUNTIME_FATAL && charge != null)
            throw new IllegalStateException("Runtime fatal cannot fabricate an ordinary gas rejection");
        return new SameOriginOperationResult.Failure(value.diagnostic, value.canonicalSite, charge, join);
    }

    private static List<GasTraceEntry> canonicalGas(List<blue.language.processor.GasTraceEntry> trace, Set<DocumentId> owners) {
        List<GasTraceEntry> result = new ArrayList<>();
        for (blue.language.processor.GasTraceEntry entry : trace) {
            DocumentId owner = entry.documentId() == null ? null : new DocumentId(entry.documentId());
            if (owner == null || !owners.contains(owner))
                throw new IllegalStateException("Atomic group gas must have its exact original owned document attribution");
            result.add(new GasTraceEntry(result.size(), ClosureResultAssemblySupport.namespace(entry.namespace()),
                    entry.counter(), entry.quantity(), entry.weight(), entry.subtotal(), owner, entry.scopePath(),
                    entry.activationGeneration(), entry.componentGeneration(), entry.contractKey(), entry.logicalPath(),
                    entry.workOccurrenceId(), entry.reason()));
        }
        return result;
    }
    private static long nextEpoch(long value) {
        if (value == ClosureValueSupport.MAX_SAFE_INTEGER) throw new IllegalArgumentException("Managed epoch exceeds safe-integer range");
        return value + 1;
    }
    private static SourceObservationProgram.SourceState sourceState(ManagedDocumentSnapshot document, long epoch) {
        return new SourceObservationProgram.SourceState(document.documentId(), document.blueId(), epoch,
                document.initialized(), FrozenNode.fromResolvedNode(document.document()));
    }
    private static ManagedDocumentSnapshot require(Map<DocumentId, ManagedDocumentSnapshot> values, DocumentId id) {
        ManagedDocumentSnapshot value = values.get(id);
        if (value == null) throw new IllegalArgumentException("Canonical group state is missing: " + id.value()); return value;
    }
    private static Map<DocumentId, ManagedDocumentSnapshot> documents(AffectedClosureSnapshot snapshot) {
        Map<DocumentId, ManagedDocumentSnapshot> result = new HashMap<>();
        for (ManagedDocumentSnapshot value : snapshot.managedDocuments()) result.put(value.documentId(), value); return result;
    }
    private static Map<DocumentId, ComponentSnapshot> components(AffectedClosureSnapshot snapshot) {
        Map<DocumentId, ComponentSnapshot> result = new HashMap<>();
        for (ComponentSnapshot component : snapshot.components())
            for (DocumentId id : component.orderedMemberDocumentIds()) result.put(id, component); return result;
    }
    private static List<ComponentSnapshot> selectComponents(Map<DocumentId, ComponentSnapshot> components, Set<DocumentId> owners) {
        Map<String, ComponentSnapshot> result = new TreeMap<>();
        for (DocumentId owner : owners) {
            ComponentSnapshot component = components.get(owner);
            if (component == null || !owners.containsAll(component.orderedMemberDocumentIds()))
                throw new IllegalArgumentException("Atomic result cannot split an original or resulting component");
            result.put(component.componentIdentity(), component);
        }
        return new ArrayList<>(result.values());
    }
    private static <T> Map<DocumentId, List<T>> index(List<T> values, Function<T, DocumentId> owner) {
        Map<DocumentId, List<T>> result = new HashMap<>();
        for (T value : values) result.computeIfAbsent(owner.apply(value), ignored -> new ArrayList<>()).add(value); return result;
    }
    private static <T> List<T> select(Map<DocumentId, List<T>> index, Collection<DocumentId> owners) {
        List<T> result = new ArrayList<>();
        for (DocumentId owner : owners) result.addAll(index.getOrDefault(owner, Collections.emptyList())); return result;
    }
}
