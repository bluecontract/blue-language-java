package blue.language.processor.closure;

import blue.language.processor.ManagedCheckpointDomain;
import blue.language.processor.ManagedCheckpointMutation;
import blue.language.processor.ManagedCheckpointSettlementBatch;
import blue.language.processor.ManagedCheckpointState;
import blue.language.processor.ManagedRootChannelOccurrence;
import blue.language.processor.ProcessorStatus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Assembles one genuine verified committing affected-closure result. */
final class ClosureSuccessResultAssembler {

    private static final ClosureIdentityService IDENTITIES =
            ClosureIdentityService.INSTANCE;

    private ClosureSuccessResultAssembler() {
    }

    static ClosureProcessResult assemble(
            ClosureInvocationInput input,
            ClosureExecutionState state) {
        return assemble(
                input,
                state,
                Collections.<ManagedOccurrenceEvidenceResolution>
                        emptyList());
    }

    static ClosureProcessResult assemble(
            ClosureInvocationInput input,
            ClosureExecutionState state,
            List<ManagedOccurrenceEvidenceResolution> resolutions) {
        ClosureInvocationInput invocation = Objects.requireNonNull(
                input, "input");
        ClosureExecutionState execution = Objects.requireNonNull(
                state, "state");
        if (execution.finalization() == null) {
            throw new ClosureCapabilityGapException(
                    "FINAL_COMMIT_FINALIZATION_REQUIRED",
                    "A committing closure requires exact finalization evidence");
        }

        AffectedClosureSnapshot output = committedSnapshot(
                invocation.snapshot(),
                execution.tentativeSnapshot(),
                execution.epochAdvanceDocuments());
        List<ResultingDocument> documents = resultingDocuments(
                invocation.snapshot(), output);
        List<GraphChange> graphChanges = graphChanges(
                invocation.snapshot(), output);
        List<SubscriptionDelta> subscriptionDeltas = subscriptionDeltas(
                invocation.snapshot(), output,
                execution.inputChannelSurfaces(),
                execution.resultingChannelSurfaces());
        List<CheckpointWrite> checkpointWrites = checkpointWrites(
                execution.tentativeSnapshot(),
                execution.checkpointMutations());
        List<PublicEventOccurrence> publicEvents =
                execution.publicEvents();
        List<GasTraceEntry> gasTrace =
                ClosureResultAssemblySupport.gasTrace(
                        execution.gasTrace());
        List<ManagedDocumentTransitionReceipt> managedTransitionReceipts =
                ManagedTransitionReceiptAssembler.assemble(
                        invocation,
                        documents,
                        execution.managedRootEvents(),
                        gasTrace);
        String managedTransitionReceiptsIdentity = IDENTITIES
                .managedTransitionReceiptsIdentity(
                        managedTransitionReceipts);

        String graphChangesIdentity = sequenceIdentity(
                ClosureIdentityService.Constructor.GRAPH_CHANGES,
                graphChanges);
        String subscriptionDeltasIdentity = sequenceIdentity(
                ClosureIdentityService.Constructor.SUBSCRIPTION_DELTAS,
                subscriptionDeltas);
        String checkpointWritesIdentity = sequenceIdentity(
                ClosureIdentityService.Constructor.CHECKPOINT_WRITES,
                checkpointWrites);
        String publicEventsIdentity = sequenceIdentity(
                ClosureIdentityService.Constructor.PUBLIC_EVENTS,
                publicEvents);
        String gasTraceIdentity = sequenceIdentity(
                ClosureIdentityService.Constructor.GAS_TRACE,
                gasTrace);
        ClosureCommitCompanion companion = companion(
                invocation,
                output,
                documents,
                graphChangesIdentity,
                checkpointWritesIdentity,
                subscriptionDeltasIdentity,
                publicEventsIdentity,
                gasTraceIdentity,
                managedTransitionReceiptsIdentity);

        return new ClosureProcessResult(
                invocation.snapshot(),
                ProcessorStatus.SUCCESS,
                invocation.invocationIdentity(),
                output.closureIdentity(),
                output.graphGeneration(),
                documents,
                output.components(),
                output.occurrences(),
                output.occurrenceBindingSetIdentity(),
                graphChanges,
                graphChangesIdentity,
                subscriptionDeltas,
                subscriptionDeltasIdentity,
                checkpointWrites,
                checkpointWritesIdentity,
                publicEvents,
                publicEventsIdentity,
                ClosureResultAssemblySupport.totalGas(gasTrace),
                gasTrace,
                gasTraceIdentity,
                null,
                null,
                companion,
                null,
                execution.finalization(),
                execution.transitionEvidence(),
                managedTransitionReceipts,
                Objects.requireNonNull(resolutions, "resolutions"));
    }

    private static AffectedClosureSnapshot committedSnapshot(
            AffectedClosureSnapshot input,
            AffectedClosureSnapshot tentative,
            Set<DocumentId> epochAdvanceDocuments) {
        ArrayList<ManagedDocumentSnapshot> documents =
                new ArrayList<ManagedDocumentSnapshot>();
        for (ManagedDocumentSnapshot exact
                : tentative.managedDocuments()) {
            ManagedDocumentSnapshot before = input.managedDocument(
                    exact.documentId());
            long epoch = before.epoch();
            if (!before.blueId().equals(exact.blueId())
                    && epochAdvanceDocuments.contains(
                            exact.documentId())) {
                if (epoch == ClosureValueSupport.MAX_SAFE_INTEGER) {
                    throw new IllegalArgumentException(
                            "Managed document epoch exceeds the safe-integer range");
                }
                epoch++;
            }
            documents.add(new ManagedDocumentSnapshot(
                    exact.documentId(),
                    exact.blueId(),
                    exact.document(),
                    exact.initialized(),
                    exact.terminated(),
                    exact.publicRoot(),
                    epoch,
                    exact.componentGeneration()));
        }
        AffectedClosureSnapshot provisional = new AffectedClosureSnapshot(
                provisionalIdentity(),
                tentative.graphGeneration(),
                documents,
                tentative.occurrences(),
                tentative.occurrenceBindingSetIdentity(),
                tentative.components(),
                tentative.publicRootDocumentIds());
        return new AffectedClosureSnapshot(
                IDENTITIES.affectedClosureIdentity(provisional),
                provisional.graphGeneration(),
                provisional.managedDocuments(),
                provisional.occurrences(),
                provisional.occurrenceBindingSetIdentity(),
                provisional.components(),
                provisional.publicRootDocumentIds());
    }

    private static List<ResultingDocument> resultingDocuments(
            AffectedClosureSnapshot input,
            AffectedClosureSnapshot output) {
        Map<DocumentId, ComponentSnapshot> owners = componentOwners(
                output.components());
        ArrayList<ResultingDocument> result =
                new ArrayList<ResultingDocument>();
        for (ManagedDocumentSnapshot after
                : output.managedDocuments()) {
            ManagedDocumentSnapshot before = input.managedDocument(
                    after.documentId());
            ComponentSnapshot component = owners.get(after.documentId());
            result.add(new ResultingDocument(
                    after.documentId(),
                    before.blueId(),
                    after.blueId(),
                    after.document(),
                    after.initialized(),
                    after.terminated(),
                    after.publicRoot(),
                    after.epoch(),
                    after.componentGeneration(),
                    component.componentIdentity(),
                    component.componentStateIdentity(),
                    ClosureResultAssemblySupport.memberIndex(
                            after.blueId())));
        }
        return Collections.unmodifiableList(result);
    }

    private static List<GraphChange> graphChanges(
            AffectedClosureSnapshot input,
            AffectedClosureSnapshot output) {
        Map<String, ManagedOccurrenceBinding> before = byOccurrence(
                input.occurrences());
        Map<String, ManagedOccurrenceBinding> after = byOccurrence(
                output.occurrences());
        Map<DocumentId, Map<String, ManagedOccurrenceBinding>>
                activeBeforeByLocation = activeByLocation(
                        input.occurrences());
        Set<String> retargetedBefore = new HashSet<String>();
        ArrayList<GraphChangeDraft> additions =
                new ArrayList<GraphChangeDraft>();
        ArrayList<GraphChangeDraft> removals =
                new ArrayList<GraphChangeDraft>();
        ArrayList<GraphChangeDraft> rebindings =
                new ArrayList<GraphChangeDraft>();
        for (ManagedOccurrenceBinding current : output.occurrences()) {
            ManagedOccurrenceBinding prior = before.get(
                    current.occurrenceIdentity());
            if (current.active()
                    && (prior == null || !prior.active())) {
                ManagedOccurrenceBinding retargeted = occurrenceAt(
                        activeBeforeByLocation,
                        current.sourceDocumentId(),
                        current.sourcePath());
                if (retargeted != null
                        && !retargeted.occurrenceIdentity().equals(
                                current.occurrenceIdentity())
                        && !retargeted.targetDocumentId().equals(
                                current.targetDocumentId())) {
                    rebindings.add(GraphChangeDraft.rebind(
                            retargeted, current));
                    retargetedBefore.add(
                            retargeted.occurrenceIdentity());
                } else {
                    additions.add(GraphChangeDraft.add(current));
                }
            } else if (current.active() && prior != null && prior.active()
                    && !sameActiveSide(prior, current)) {
                rebindings.add(GraphChangeDraft.rebind(prior, current));
            }
        }
        for (ManagedOccurrenceBinding prior : input.occurrences()) {
            ManagedOccurrenceBinding current = after.get(
                    prior.occurrenceIdentity());
            if (prior.active()
                    && !retargetedBefore.contains(
                            prior.occurrenceIdentity())
                    && (current == null || !current.active())) {
                removals.add(GraphChangeDraft.remove(prior));
            }
        }
        ArrayList<GraphChangeDraft> ordered =
                new ArrayList<GraphChangeDraft>();
        ordered.addAll(additions);
        ordered.addAll(removals);
        ordered.addAll(rebindings);
        Collections.sort(ordered, new Comparator<GraphChangeDraft>() {
            @Override
            public int compare(
                    GraphChangeDraft left,
                    GraphChangeDraft right) {
                int order = left.source.sourceDocumentId().compareTo(
                        right.source.sourceDocumentId());
                return order != 0 ? order
                        : ClosureValueSupport.comparePortableText(
                                left.source.sourcePath(),
                                right.source.sourcePath());
            }
        });
        ArrayList<GraphChange> result = new ArrayList<GraphChange>();
        appendGraphChanges(result, ordered);
        return Collections.unmodifiableList(result);
    }

    private static void appendGraphChanges(
            List<GraphChange> target,
            List<GraphChangeDraft> drafts) {
        for (GraphChangeDraft draft : drafts) {
            target.add(draft.materialize(target.size()));
        }
    }

    private static List<SubscriptionDelta> subscriptionDeltas(
            AffectedClosureSnapshot input,
            AffectedClosureSnapshot output,
            Map<DocumentId, List<ManagedRootChannelOccurrence>> before,
            Map<DocumentId, List<ManagedRootChannelOccurrence>> after) {
        ArrayList<SubscriptionDelta> result =
                new ArrayList<SubscriptionDelta>();
        for (ManagedDocumentSnapshot inputDocument
                : input.managedDocuments()) {
            DocumentId documentId = inputDocument.documentId();
            ManagedDocumentSnapshot outputDocument =
                    output.managedDocument(documentId);
            List<ManagedRootChannelOccurrence> beforeSurface =
                    surface(before, documentId);
            List<ManagedRootChannelOccurrence> afterSurface =
                    surface(after, documentId);
            Map<String, SurfaceState> afterByIdentity =
                    surfaceByIdentity(documentId, afterSurface);
            Set<String> consumed = new HashSet<String>();
            for (ManagedRootChannelOccurrence item : beforeSurface) {
                SurfaceState oldState = new SurfaceState(
                        documentId, item);
                SurfaceState newState = afterByIdentity.get(
                        oldState.occurrence.channelOccurrenceIdentity());
                SubscriptionState oldSubscription = oldState.subscription(
                        inputDocument,
                        input.graphGeneration());
                if (newState == null) {
                    result.add(new SubscriptionDelta(
                            result.size(),
                            SubscriptionDelta.Operation.REMOVE,
                            scopeIdentity(documentId),
                            oldState.occurrence
                                    .channelOccurrenceIdentity(),
                            oldSubscription,
                            null));
                    continue;
                }
                consumed.add(newState.occurrence
                        .channelOccurrenceIdentity());
                SubscriptionState newSubscription = newState.subscription(
                        outputDocument,
                        output.graphGeneration());
                if (!oldSubscription.subscriptionIdentity().equals(
                            newSubscription.subscriptionIdentity())) {
                    result.add(new SubscriptionDelta(
                            result.size(),
                            SubscriptionDelta.Operation.REPLACE,
                            scopeIdentity(documentId),
                            oldState.occurrence
                                    .channelOccurrenceIdentity(),
                            oldSubscription,
                            newSubscription));
                }
            }
            for (ManagedRootChannelOccurrence item : afterSurface) {
                SurfaceState newState = new SurfaceState(documentId, item);
                if (consumed.contains(newState.occurrence
                        .channelOccurrenceIdentity())) {
                    continue;
                }
                result.add(new SubscriptionDelta(
                        result.size(),
                        SubscriptionDelta.Operation.ADD,
                        scopeIdentity(documentId),
                        newState.occurrence.channelOccurrenceIdentity(),
                        null,
                        newState.subscription(
                                outputDocument,
                                output.graphGeneration())));
            }
        }
        return Collections.unmodifiableList(result);
    }

    private static List<CheckpointWrite> checkpointWrites(
            AffectedClosureSnapshot snapshot,
            List<ManagedCheckpointSettlementBatch.Mutation> values) {
        ArrayList<CheckpointWrite> result =
                new ArrayList<CheckpointWrite>();
        Map<String, ManagedScopeKey> scopesByIdentity =
                new HashMap<String, ManagedScopeKey>();
        for (ManagedDocumentSnapshot document
                : snapshot.managedDocuments()) {
            ManagedScopeKey scope = ManagedScopeKey.root(
                    document.documentId());
            String identity = IDENTITIES.managedScopeKeyIdentity(scope);
            if (scopesByIdentity.put(identity, scope) != null) {
                throw new IllegalArgumentException(
                        "Managed Root scope identities are not unique");
            }
        }
        for (ManagedCheckpointSettlementBatch.Mutation item : values) {
            ManagedScopeKey scope = scopesByIdentity.get(
                    item.targetManagedScopeIdentity());
            if (scope == null) {
                throw new IllegalArgumentException(
                        "Checkpoint mutation targets an unknown managed Root");
            }
            ManagedCheckpointMutation mutation = item.mutation();
            result.add(new CheckpointWrite(
                    result.size(),
                    scope,
                    item.targetManagedScopeIdentity(),
                    mutation.rawChannelKey(),
                    checkpointState(mutation.beforeState()),
                    checkpointState(mutation.afterState())));
        }
        return Collections.unmodifiableList(result);
    }

    private static CheckpointWrite.State checkpointState(
            ManagedCheckpointState state) {
        if (!state.present()) {
            return null;
        }
        ManagedCheckpointDomain domain = state.domain();
        CheckpointDomainValue value = new CheckpointDomainValue(
                domain.effectiveTypeBlueId(),
                domain.sourceContributionNodeBlueIds(),
                domain.deterministicDependencyNodeBlueIds(),
                domain.runtimeDiscriminator());
        return new CheckpointWrite.State(
                domain.blueId(),
                value,
                state.subjectBlueId());
    }

    private static ClosureCommitCompanion companion(
            ClosureInvocationInput input,
            AffectedClosureSnapshot output,
            List<ResultingDocument> documents,
            String graphChangesIdentity,
            String checkpointWritesIdentity,
            String subscriptionDeltasIdentity,
            String publicEventsIdentity,
            String gasTraceIdentity,
            String managedTransitionReceiptsIdentity) {
        ArrayList<ClosureCommitCompanion.InputDocument> inputDocuments =
                new ArrayList<ClosureCommitCompanion.InputDocument>();
        for (ManagedDocumentSnapshot document
                : input.snapshot().managedDocuments()) {
            inputDocuments.add(new ClosureCommitCompanion.InputDocument(
                    document.documentId(), document.blueId()));
        }
        ArrayList<ClosureCommitCompanion.InputComponent> inputComponents =
                new ArrayList<ClosureCommitCompanion.InputComponent>();
        for (ComponentSnapshot component : input.snapshot().components()) {
            inputComponents.add(new ClosureCommitCompanion.InputComponent(
                    component.componentIdentity(),
                    component.componentStateIdentity(),
                    component.componentGeneration(),
                    component.masterBlueId()));
        }
        ArrayList<ClosureCommitCompanion.DocumentDelta> documentDeltas =
                new ArrayList<ClosureCommitCompanion.DocumentDelta>();
        for (ResultingDocument document : documents) {
            documentDeltas.add(
                    new ClosureCommitCompanion.DocumentDelta(
                            document.documentId(),
                            document.beforeBlueId(),
                            document.afterBlueId()));
        }
        ArrayList<ClosureCommitCompanion.ResultComponent> resultComponents =
                new ArrayList<ClosureCommitCompanion.ResultComponent>();
        for (ComponentSnapshot component : output.components()) {
            resultComponents.add(
                    new ClosureCommitCompanion.ResultComponent(
                            component.componentIdentity(),
                            component.componentStateIdentity(),
                            component.cyclicProofIdentity()));
        }
        return ClosureCommitCompanion.identifiedWithManagedTransitions(
                input.invocationIdentity(),
                input.snapshot().closureIdentity(),
                output.closureIdentity(),
                input.snapshot().graphGeneration(),
                inputDocuments,
                inputComponents,
                input.snapshot().occurrenceBindingSetIdentity(),
                output.graphGeneration(),
                documentDeltas,
                resultComponents,
                output.occurrenceBindingSetIdentity(),
                graphChangesIdentity,
                checkpointWritesIdentity,
                subscriptionDeltasIdentity,
                publicEventsIdentity,
                gasTraceIdentity,
                managedTransitionReceiptsIdentity,
                input.environment());
    }

    private static Map<DocumentId, ComponentSnapshot> componentOwners(
            List<ComponentSnapshot> components) {
        HashMap<DocumentId, ComponentSnapshot> result =
                new HashMap<DocumentId, ComponentSnapshot>();
        for (ComponentSnapshot component : components) {
            for (DocumentId documentId
                    : component.orderedMemberDocumentIds()) {
                result.put(documentId, component);
            }
        }
        return result;
    }

    private static Map<String, ManagedOccurrenceBinding> byOccurrence(
            List<ManagedOccurrenceBinding> values) {
        HashMap<String, ManagedOccurrenceBinding> result =
                new HashMap<String, ManagedOccurrenceBinding>();
        for (ManagedOccurrenceBinding value : values) {
            result.put(value.occurrenceIdentity(), value);
        }
        return result;
    }

    private static Map<DocumentId, Map<String, ManagedOccurrenceBinding>>
            activeByLocation(List<ManagedOccurrenceBinding> values) {
        HashMap<DocumentId, Map<String, ManagedOccurrenceBinding>> result =
                new HashMap<DocumentId,
                        Map<String, ManagedOccurrenceBinding>>();
        for (ManagedOccurrenceBinding value : values) {
            if (!value.active()) {
                continue;
            }
            Map<String, ManagedOccurrenceBinding> byPath = result.get(
                    value.sourceDocumentId());
            if (byPath == null) {
                byPath = new HashMap<String, ManagedOccurrenceBinding>();
                result.put(value.sourceDocumentId(), byPath);
            }
            byPath.put(value.sourcePath(), value);
        }
        return result;
    }

    private static ManagedOccurrenceBinding occurrenceAt(
            Map<DocumentId, Map<String, ManagedOccurrenceBinding>> values,
            DocumentId sourceDocumentId,
            String sourcePath) {
        Map<String, ManagedOccurrenceBinding> byPath = values.get(
                sourceDocumentId);
        return byPath == null ? null : byPath.get(sourcePath);
    }

    private static boolean sameActiveSide(
            ManagedOccurrenceBinding left,
            ManagedOccurrenceBinding right) {
        return left.activationGeneration()
                        == right.activationGeneration()
                && left.bindingIdentity().equals(right.bindingIdentity())
                && left.targetDocumentId().equals(
                        right.targetDocumentId())
                && left.expectedTargetBlueId().equals(
                        right.expectedTargetBlueId());
    }

    private static List<ManagedRootChannelOccurrence> surface(
            Map<DocumentId, List<ManagedRootChannelOccurrence>> surfaces,
            DocumentId documentId) {
        List<ManagedRootChannelOccurrence> result = surfaces.get(documentId);
        return result == null
                ? Collections.<ManagedRootChannelOccurrence>emptyList()
                : result;
    }

    private static Map<String, SurfaceState> surfaceByIdentity(
            DocumentId documentId,
            List<ManagedRootChannelOccurrence> values) {
        LinkedHashMap<String, SurfaceState> result =
                new LinkedHashMap<String, SurfaceState>();
        for (ManagedRootChannelOccurrence value : values) {
            SurfaceState state = new SurfaceState(documentId, value);
            result.put(state.occurrence.channelOccurrenceIdentity(), state);
        }
        return result;
    }

    private static String scopeIdentity(DocumentId documentId) {
        return IDENTITIES.managedScopeKeyIdentity(
                ManagedScopeKey.root(documentId));
    }

    private static String sequenceIdentity(
            ClosureIdentityService.Constructor constructor,
            List<?> values) {
        return ClosureResultAssemblySupport.sequenceIdentity(
                constructor, values);
    }

    private static String provisionalIdentity() {
        return "sha256:0000000000000000000000000000000000000000000000000000000000000000";
    }

    private static GraphChange.Side side(
            ManagedOccurrenceBinding binding) {
        return new GraphChange.Side(
                binding.activationGeneration(),
                binding.occurrenceIdentity(),
                binding.bindingIdentity(),
                binding.targetDocumentId(),
                binding.expectedTargetBlueId());
    }

    private static final class SurfaceState {
        private final ChannelOccurrence occurrence;

        private SurfaceState(
                DocumentId documentId,
                ManagedRootChannelOccurrence value) {
            this.occurrence = ChannelOccurrence.root(
                    documentId,
                    value.rawChannelKey(),
                    value.effectiveRuntimeContributionBlueId(),
                    value.subscriptionHeaderBlueId());
        }

        private SubscriptionState subscription(
                ManagedDocumentSnapshot document,
                long graphGeneration) {
            return SubscriptionState.identified(
                    occurrence,
                    document.blueId(),
                    graphGeneration,
                    document.componentGeneration());
        }
    }

    private static final class GraphChangeDraft {
        private final GraphChange.Kind kind;
        private final ManagedOccurrenceBinding source;
        private final GraphChange.Side before;
        private final GraphChange.Side after;

        private GraphChangeDraft(
                GraphChange.Kind kind,
                ManagedOccurrenceBinding source,
                GraphChange.Side before,
                GraphChange.Side after) {
            this.kind = kind;
            this.source = source;
            this.before = before;
            this.after = after;
        }

        private static GraphChangeDraft add(
                ManagedOccurrenceBinding after) {
            return new GraphChangeDraft(
                    GraphChange.Kind.ADD, after, null, side(after));
        }

        private static GraphChangeDraft remove(
                ManagedOccurrenceBinding before) {
            return new GraphChangeDraft(
                    GraphChange.Kind.REMOVE,
                    before,
                    side(before),
                    null);
        }

        private static GraphChangeDraft rebind(
                ManagedOccurrenceBinding before,
                ManagedOccurrenceBinding after) {
            return new GraphChangeDraft(
                    GraphChange.Kind.REBIND,
                    after,
                    side(before),
                    side(after));
        }

        private GraphChange materialize(long ordinal) {
            return new GraphChange(
                    ordinal,
                    kind,
                    source.sourceDocumentId(),
                    source.sourcePath(),
                    before,
                    after);
        }
    }
}
