package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.model.EmbeddedNodeChannel;
import blue.language.processor.model.TriggeredEventChannel;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.processor.util.ProcessorContractConstants;
import blue.language.snapshot.FrozenNode;
import blue.language.model.wire.JsonPointer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Owns deterministic FIFO delivery along ancestor chains frozen when an event
 * occurrence is admitted.
 */
final class ScopePropagationChain {

    private final DocumentProcessor owner;
    private final ProcessorInvocationState execution;
    private final DocumentProcessingRuntime runtime;
    private final ScopeParticipationRegistry participation;
    private final ScopeFrameFactory frames;
    private final ScopeHandlerDispatcher handlerDispatcher;
    private boolean draining;
    private boolean drainRequested;
    private int drainDeferralDepth;

    ScopePropagationChain(
            DocumentProcessor owner,
            ProcessorInvocationState execution,
            DocumentProcessingRuntime runtime,
            ScopeParticipationRegistry participation,
            ScopeFrameFactory frames,
            ScopeHandlerDispatcher handlerDispatcher) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.execution = Objects.requireNonNull(execution, "execution");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.participation = Objects.requireNonNull(
                participation, "participation");
        this.frames = Objects.requireNonNull(frames, "frames");
        this.handlerDispatcher = Objects.requireNonNull(
                handlerDispatcher, "handlerDispatcher");
    }

    List<String> freezeReceivingChain(
            DocumentUpdateData update) {
        List<String> result = new ArrayList<>();
        String origin = ProcessorEngine.normalizeScope(update.originScope());
        for (String candidate : update.recipientChain()) {
            String normalized = ProcessorEngine.normalizeScope(candidate);
            boolean endpoint = normalized.equals(origin)
                    || JsonPointer.ROOT.equals(normalized);
            if (!endpoint && !participation.participates(normalized)) {
                continue;
            }
            if (!execution.shouldStopScopeWork(normalized)) {
                result.add(normalized);
            }
        }
        return Collections.unmodifiableList(result);
    }

    void requestDrain() {
        if (draining) {
            return;
        }
        drainRequested = true;
        if (drainDeferralDepth == 0) {
            drain();
        }
    }

    void drain() {
        if (draining) {
            return;
        }
        if (drainDeferralDepth > 0) {
            drainRequested = true;
            return;
        }
        drainRequested = false;
        boolean quiescent = false;
        draining = true;
        try {
            while (runtime.hasPendingEventOccurrences()
                    && !execution.hasFailure()
                    && !rootIsCutOff()) {
                EventOccurrence occurrence = runtime.pollEventOccurrence();
                if (occurrence == null) {
                    break;
                }
                runtime.chargeDrainEvent();
                recordDequeued(occurrence);
                if (occurrence.sourceMode()
                        == EventOccurrence.SourceMode.TRIGGERED
                        && execution.canDeliverOccurrenceLocally(
                        occurrence.source())) {
                    deliverTriggered(occurrence);
                }
                for (ScopeRuntimeContext ancestor
                        : occurrence.frozenAncestors()) {
                    if (execution.canDeliverOccurrenceLocally(ancestor)) {
                        deliverEmbedded(ancestor, occurrence);
                    }
                }
            }
            quiescent = !runtime.hasPendingEventOccurrences()
                    && !execution.hasFailure();
        } finally {
            draining = false;
        }
        if (quiescent) {
            execution.completePendingTerminations();
        }
    }

    void beginDrainDeferral() {
        drainDeferralDepth++;
    }

    void endDrainDeferral() {
        if (drainDeferralDepth <= 0) {
            throw new IllegalStateException(
                    "Internal event drain deferral underflow");
        }
        drainDeferralDepth--;
        if (drainDeferralDepth == 0
                && drainRequested
                && !draining) {
            drain();
        }
    }

    private void recordDequeued(EventOccurrence occurrence) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put(
                ProcessingTraceConstants.FIELD_DRAIN_OWNER,
                ProcessingTraceConstants.DRAIN_OWNER_INVOCATION_EVENT_FIFO);
        details.put(
                ProcessingTraceConstants.FIELD_SOURCE_SCOPE_PATH,
                occurrence.source().scopePath());
        runtime.recordTrace(
                ProcessingTraceRecord.Kind.EVENT_DEQUEUED,
                occurrence.source().scopePath(),
                occurrence.emittingContractKey(),
                null,
                details,
                occurrence.event());
    }

    private boolean rootIsCutOff() {
        ScopeRuntimeContext root = runtime.existingScope(JsonPointer.ROOT);
        return root != null && root.isCutOff();
    }

    private void deliverTriggered(EventOccurrence occurrence) {
        long routingStart = System.nanoTime();
        try {
            String sourcePath = occurrence.source().scopePath();
            ContractBundle currentBundle = frames.refresh(sourcePath);
            List<ContractBundle.ChannelBinding> channels =
                    currentBundle != null
                            ? currentBundle.channelsOfType(
                            TriggeredEventChannel.class)
                            : Collections.emptyList();
            ProcessingObservations.record(
                    owner.observer(),
                    ProcessingMetricId.TRIGGERED_EVENTS_ROUTED,
                    1L);
            for (ContractBundle.ChannelBinding channel : channels) {
                if (!execution.canDeliverOccurrenceLocally(
                        occurrence.source())) {
                    return;
                }
                TriggeredEventChannel triggered =
                        (TriggeredEventChannel) channel.contract();
                if (!matchesEventPattern(
                        occurrence, triggered.getEvent())) {
                    continue;
                }
                runtime.chargeTriggeredDelivery();
                Map<String, Object> details = new LinkedHashMap<>();
                details.put(
                        ProcessingTraceConstants.FIELD_MODE,
                        ProcessingTraceConstants.MODE_TRIGGERED);
                details.put(
                        ProcessingTraceConstants.FIELD_SOURCE_SCOPE_PATH,
                        sourcePath);
                runtime.recordTrace(
                        ProcessingTraceRecord.Kind.EVENT_DELIVERED,
                        sourcePath,
                        channel.key(),
                        null,
                        details,
                        occurrence.event());
                handlerDispatcher.dispatch(
                        sourcePath,
                        currentBundle,
                        channel.key(),
                        occurrence.event());
            }
        } finally {
            ProcessingObservations.record(
                    owner.observer(),
                    ProcessingMetricId.TRIGGERED_EVENT_ROUTING_NANOS,
                    System.nanoTime() - routingStart);
        }
    }

    private void deliverEmbedded(
            ScopeRuntimeContext receivingAncestor,
            EventOccurrence occurrence) {
        String receivingPath = receivingAncestor.scopePath();
        String sourcePath = ProcessorEngine.relativizePointer(
                receivingPath, occurrence.source().scopePath());
        Node wrapper = new Node()
                .type(new Node().blueId(
                        RuntimeBlueIds.EMBEDDED_EVENT_DELIVERY))
                .properties(
                        ProcessorContractConstants.KEY_SOURCE_PATH,
                        new Node().value(sourcePath))
                .properties(
                        ProcessorContractConstants.KEY_EVENT,
                        new Node().blueId(occurrence.eventBlueId()));
        ContractBundle currentBundle = frames.refresh(receivingPath);
        List<ContractBundle.ChannelBinding> channels =
                currentBundle != null
                        ? currentBundle.channelsOfType(
                        EmbeddedNodeChannel.class)
                        : Collections.emptyList();
        for (ContractBundle.ChannelBinding channel : channels) {
            if (!execution.canDeliverOccurrenceLocally(
                    receivingAncestor)) {
                return;
            }
            EmbeddedNodeChannel embedded =
                    (EmbeddedNodeChannel) channel.contract();
            if (!matchesSourcePath(
                    receivingPath,
                    occurrence.source().scopePath(),
                    embedded)
                    || !matchesEventPattern(
                    occurrence, embedded.getEvent())) {
                continue;
            }
            runtime.chargeBridge(wrapper);
            Map<String, Object> details = new LinkedHashMap<>();
            details.put(
                    ProcessingTraceConstants.FIELD_MODE,
                    ProcessingTraceConstants.MODE_EMBEDDED);
            details.put(
                    ProcessingTraceConstants.FIELD_SOURCE_SCOPE_PATH,
                    occurrence.source().scopePath());
            details.put(
                    ProcessingTraceConstants.FIELD_SOURCE_PATH,
                    sourcePath);
            runtime.recordTrace(
                    ProcessingTraceRecord.Kind.EVENT_DELIVERED,
                    receivingPath,
                    channel.key(),
                    null,
                    details,
                    wrapper);
            handlerDispatcher.dispatch(
                    receivingPath,
                    currentBundle,
                    channel.key(),
                    wrapper.clone(),
                    occurrence.event());
        }
    }

    private boolean matchesSourcePath(
            String receivingPath,
            String absoluteSourcePath,
            EmbeddedNodeChannel channel) {
        String configured = channel.getSourcePath();
        return configured == null
                || ProcessorEngine.resolvePointer(
                receivingPath, configured).equals(absoluteSourcePath);
    }

    private boolean matchesEventPattern(
            EventOccurrence occurrence,
            Node pattern) {
        return pattern == null
                || owner.matchingService().matches(
                occurrence.frozenEvent(),
                FrozenNode.fromResolvedNode(pattern));
    }
}
