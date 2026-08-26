package blue.language.processor.closure;

import blue.language.processor.ManagedCheckpointSettlementBatch;
import blue.language.processor.ManagedRootChannelOccurrence;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Internal state produced by the real execution loop before result assembly. */
final class ClosureExecutionState {

    private final AffectedClosureSnapshot tentativeSnapshot;
    private final ComponentFinalizationResult finalization;
    private final List<PublicEventOccurrence> publicEvents;
    private final List<ManagedRootEventOccurrence> managedRootEvents;
    private final List<blue.language.processor.GasTraceEntry> gasTrace;
    private final Map<DocumentId, List<ManagedRootChannelOccurrence>>
            inputChannelSurfaces;
    private final Map<DocumentId, List<ManagedRootChannelOccurrence>>
            resultingChannelSurfaces;
    private final List<ManagedCheckpointSettlementBatch.Mutation>
            checkpointMutations;
    private final Set<DocumentId> epochAdvanceDocuments;
    private final List<DocumentTransitionEvidence> transitionEvidence;

    ClosureExecutionState(
            AffectedClosureSnapshot tentativeSnapshot,
            ComponentFinalizationResult finalization,
            List<PublicEventOccurrence> publicEvents,
            List<blue.language.processor.GasTraceEntry> gasTrace,
            Map<DocumentId, List<ManagedRootChannelOccurrence>>
                    inputChannelSurfaces,
            Map<DocumentId, List<ManagedRootChannelOccurrence>>
                    resultingChannelSurfaces,
            List<ManagedCheckpointSettlementBatch.Mutation>
                    checkpointMutations,
            Set<DocumentId> epochAdvanceDocuments) {
        this(
                tentativeSnapshot,
                finalization,
                publicEvents,
                gasTrace,
                inputChannelSurfaces,
                resultingChannelSurfaces,
                checkpointMutations,
                epochAdvanceDocuments,
                Collections.<DocumentTransitionEvidence>emptyList(),
                Collections.<ManagedRootEventOccurrence>emptyList());
    }

    ClosureExecutionState(
            AffectedClosureSnapshot tentativeSnapshot,
            ComponentFinalizationResult finalization,
            List<PublicEventOccurrence> publicEvents,
            List<blue.language.processor.GasTraceEntry> gasTrace,
            Map<DocumentId, List<ManagedRootChannelOccurrence>>
                    inputChannelSurfaces,
            Map<DocumentId, List<ManagedRootChannelOccurrence>>
                    resultingChannelSurfaces,
            List<ManagedCheckpointSettlementBatch.Mutation>
                    checkpointMutations,
            Set<DocumentId> epochAdvanceDocuments,
            List<DocumentTransitionEvidence> transitionEvidence) {
        this(
                tentativeSnapshot,
                finalization,
                publicEvents,
                gasTrace,
                inputChannelSurfaces,
                resultingChannelSurfaces,
                checkpointMutations,
                epochAdvanceDocuments,
                transitionEvidence,
                Collections.<ManagedRootEventOccurrence>emptyList());
    }

    ClosureExecutionState(
            AffectedClosureSnapshot tentativeSnapshot,
            ComponentFinalizationResult finalization,
            List<PublicEventOccurrence> publicEvents,
            List<blue.language.processor.GasTraceEntry> gasTrace,
            Map<DocumentId, List<ManagedRootChannelOccurrence>>
                    inputChannelSurfaces,
            Map<DocumentId, List<ManagedRootChannelOccurrence>>
                    resultingChannelSurfaces,
            List<ManagedCheckpointSettlementBatch.Mutation>
                    checkpointMutations,
            Set<DocumentId> epochAdvanceDocuments,
            List<DocumentTransitionEvidence> transitionEvidence,
            List<ManagedRootEventOccurrence> managedRootEvents) {
        this.tentativeSnapshot = Objects.requireNonNull(
                tentativeSnapshot, "tentativeSnapshot");
        this.finalization = finalization;
        this.publicEvents = Collections.unmodifiableList(
                new ArrayList<PublicEventOccurrence>(Objects.requireNonNull(
                        publicEvents, "publicEvents")));
        this.managedRootEvents = Collections.unmodifiableList(
                new ArrayList<ManagedRootEventOccurrence>(
                        Objects.requireNonNull(
                                managedRootEvents,
                                "managedRootEvents")));
        this.gasTrace = Collections.unmodifiableList(
                new ArrayList<blue.language.processor.GasTraceEntry>(
                        Objects.requireNonNull(gasTrace, "gasTrace")));
        this.inputChannelSurfaces = immutableNested(
                inputChannelSurfaces, "inputChannelSurfaces");
        this.resultingChannelSurfaces = immutableNested(
                resultingChannelSurfaces, "resultingChannelSurfaces");
        this.checkpointMutations = Collections.unmodifiableList(
                new ArrayList<ManagedCheckpointSettlementBatch.Mutation>(
                        Objects.requireNonNull(
                                checkpointMutations,
                                "checkpointMutations")));
        this.epochAdvanceDocuments = Collections.unmodifiableSet(
                new LinkedHashSet<DocumentId>(Objects.requireNonNull(
                        epochAdvanceDocuments,
                        "epochAdvanceDocuments")));
        this.transitionEvidence = Collections.unmodifiableList(
                new ArrayList<DocumentTransitionEvidence>(
                        Objects.requireNonNull(
                                transitionEvidence,
                                "transitionEvidence")));
    }

    AffectedClosureSnapshot tentativeSnapshot() {
        return tentativeSnapshot;
    }

    ComponentFinalizationResult finalization() {
        return finalization;
    }

    List<PublicEventOccurrence> publicEvents() {
        return publicEvents;
    }

    List<ManagedRootEventOccurrence> managedRootEvents() {
        return managedRootEvents;
    }

    List<blue.language.processor.GasTraceEntry> gasTrace() {
        return gasTrace;
    }

    Map<DocumentId, List<ManagedRootChannelOccurrence>>
            inputChannelSurfaces() {
        return inputChannelSurfaces;
    }

    Map<DocumentId, List<ManagedRootChannelOccurrence>>
            resultingChannelSurfaces() {
        return resultingChannelSurfaces;
    }

    List<ManagedCheckpointSettlementBatch.Mutation>
            checkpointMutations() {
        return checkpointMutations;
    }

    Set<DocumentId> epochAdvanceDocuments() {
        return epochAdvanceDocuments;
    }

    List<DocumentTransitionEvidence> transitionEvidence() {
        return transitionEvidence;
    }

    private static <T> Map<DocumentId, List<T>> immutableNested(
            Map<DocumentId, List<T>> source,
            String label) {
        LinkedHashMap<DocumentId, List<T>> copy =
                new LinkedHashMap<DocumentId, List<T>>();
        for (Map.Entry<DocumentId, List<T>> entry
                : Objects.requireNonNull(source, label).entrySet()) {
            copy.put(
                    Objects.requireNonNull(entry.getKey(), label + " key"),
                    Collections.unmodifiableList(
                            new ArrayList<T>(Objects.requireNonNull(
                                    entry.getValue(), label + " value"))));
        }
        return Collections.unmodifiableMap(copy);
    }
}
