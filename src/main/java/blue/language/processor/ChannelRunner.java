package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.model.ChannelContract;

import java.util.List;
import java.util.Objects;

/**
 * Executes channel matching and handler invocation for a scope.
 *
 * <p>Applies checkpoint gating for external channels and feeds successful
 * matches into the registered handler processors.</p>
 */
final class ChannelRunner {

    private final DocumentProcessor owner;
    private final ProcessorEngine.Execution execution;
    private final DocumentProcessingRuntime runtime;
    private final CheckpointManager checkpointManager;

    ChannelRunner(DocumentProcessor owner,
                  ProcessorEngine.Execution execution,
                  DocumentProcessingRuntime runtime,
                  CheckpointManager checkpointManager) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.execution = Objects.requireNonNull(execution, "execution");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.checkpointManager = Objects.requireNonNull(checkpointManager, "checkpointManager");
    }

    void runExternalChannel(String scopePath,
                            ContractBundle bundle,
                            ContractBundle.ChannelBinding channel,
                            Node event) {
        if (execution.isScopeInactive(scopePath)) {
            return;
        }
        runtime.chargeChannelMatchAttempt();
        ChannelContract contract = channel.contract();
        ProcessingMetricsSink metrics = owner.metricsSink();
        metrics.incrementChannelEvaluations();
        long channelMatchStart = System.nanoTime();
        ProcessorEngine.ChannelMatch match;
        try {
            match = ProcessorEngine.evaluateChannel(owner, channel, bundle, scopePath, event);
        } finally {
            metrics.addChannelMatchNanos(System.nanoTime() - channelMatchStart);
        }
        if (!match.matches) {
            return;
        }
        if (!match.deliveries().isEmpty()) {
            runDeliveries(scopePath, bundle, channel, event, match);
            return;
        }
        Node eventForHandlers = match.eventNode() != null ? match.eventNode() : event;
        Node checkpointEvent = event != null ? event.clone() : null;
        long checkpointStart = System.nanoTime();
        checkpointManager.ensureCheckpointMarker(scopePath, bundle);
        CheckpointManager.CheckpointRecord checkpoint = checkpointManager.findCheckpoint(bundle, channel.key());
        String eventSignature = match.eventId != null
                ? match.eventId
                : ProcessorEngine.canonicalSignature(checkpointEvent);
        if (checkpointManager.isDuplicate(checkpoint, eventSignature)) {
            metrics.addCheckpointUpdateNanos(System.nanoTime() - checkpointStart);
            return;
        }
        ChannelCheckpointContext checkpointContext = new ChannelCheckpointContext(scopePath,
                channel.key(),
                checkpointEvent,
                eventSignature,
                checkpoint != null ? checkpoint.lastEventNode : null,
                checkpoint != null ? checkpoint.lastEventSignature : null,
                bundle.markers());
        if (!match.processor.isNewerEvent(contract, checkpointContext)) {
            metrics.addCheckpointUpdateNanos(System.nanoTime() - checkpointStart);
            return;
        }
        metrics.addCheckpointUpdateNanos(System.nanoTime() - checkpointStart);
        runHandlers(scopePath, bundle, channel.key(), eventForHandlers, false);
        if (execution.isScopeInactive(scopePath)) {
            return;
        }
        long checkpointPersistStart = System.nanoTime();
        checkpointManager.persist(scopePath, bundle, checkpoint, eventSignature, checkpointEvent);
        metrics.addCheckpointUpdateNanos(System.nanoTime() - checkpointPersistStart);
    }

    private void runDeliveries(String scopePath,
                               ContractBundle bundle,
                               ContractBundle.ChannelBinding channel,
                               Node checkpointEvent,
                               ProcessorEngine.ChannelMatch match) {
        ProcessingMetricsSink metrics = owner.metricsSink();
        long checkpointEnsureStart = System.nanoTime();
        checkpointManager.ensureCheckpointMarker(scopePath, bundle);
        String fallbackSignature = ProcessorEngine.canonicalSignature(checkpointEvent);
        metrics.addCheckpointUpdateNanos(System.nanoTime() - checkpointEnsureStart);
        for (ChannelDelivery delivery : match.deliveries()) {
            if (execution.isScopeInactive(scopePath)) {
                return;
            }
            String checkpointKey = delivery.checkpointKey() != null
                    ? delivery.checkpointKey()
                    : channel.key();
            long checkpointStart = System.nanoTime();
            CheckpointManager.CheckpointRecord checkpoint = checkpointManager.findCheckpoint(bundle, checkpointKey);
            String eventSignature = delivery.eventId() != null ? delivery.eventId() : fallbackSignature;
            if (checkpointManager.isDuplicate(checkpoint, eventSignature)) {
                metrics.addCheckpointUpdateNanos(System.nanoTime() - checkpointStart);
                continue;
            }
            Boolean shouldProcess = delivery.shouldProcess();
            if (Boolean.FALSE.equals(shouldProcess)) {
                continue;
            }
            if (shouldProcess == null) {
                ChannelCheckpointContext checkpointContext = new ChannelCheckpointContext(scopePath,
                        checkpointKey,
                        checkpointEvent,
                        eventSignature,
                        checkpoint != null ? checkpoint.lastEventNode : null,
                        checkpoint != null ? checkpoint.lastEventSignature : null,
                        bundle.markers());
                if (!match.processor.isNewerEvent(channel.contract(), checkpointContext)) {
                    metrics.addCheckpointUpdateNanos(System.nanoTime() - checkpointStart);
                    continue;
                }
            }
            metrics.addCheckpointUpdateNanos(System.nanoTime() - checkpointStart);
            Node eventForHandlers = delivery.eventForDelivery();
            if (eventForHandlers == null) {
                continue;
            }
            runHandlers(scopePath, bundle, channel.key(), eventForHandlers, false);
            if (execution.isScopeInactive(scopePath)) {
                return;
            }
            long checkpointPersistStart = System.nanoTime();
            checkpointManager.persist(scopePath, bundle, checkpoint, eventSignature, checkpointEvent);
            metrics.addCheckpointUpdateNanos(System.nanoTime() - checkpointPersistStart);
        }
    }

    void runHandlers(String scopePath,
                     ContractBundle bundle,
                     String channelKey,
                     Node event,
                     boolean allowTerminatedWork) {
        ProcessingMetricsSink metrics = owner.metricsSink();
        long discoveryStart = System.nanoTime();
        List<ContractBundle.HandlerBinding> handlers = bundle.handlersFor(channelKey);
        metrics.addHandlerDiscoveryNanos(System.nanoTime() - discoveryStart);
        if (handlers.isEmpty()) {
            return;
        }
        for (ContractBundle.HandlerBinding handler : handlers) {
            if (!allowTerminatedWork && execution.isScopeInactive(scopePath)) {
                break;
            }
            HandlerMatchContext matchContext = new HandlerMatchContext(scopePath,
                    event,
                    bundle.markers(),
                    owner.matchingService());
            metrics.incrementHandlerMatchAttempts();
            long matchStart = System.nanoTime();
            boolean matches;
            try {
                matches = ProcessorEngine.matchesHandler(owner, handler.contract(), matchContext);
            } finally {
                metrics.addHandlerMatchNanos(System.nanoTime() - matchStart);
            }
            if (!matches) {
                continue;
            }
            runtime.chargeHandlerOverhead();
            ProcessorExecutionContext context = execution.createContext(scopePath,
                    bundle,
                    event,
                    handler.key(),
                    handler.node(),
                    allowTerminatedWork,
                    false);
            metrics.incrementHandlersExecuted();
            long executionStart = System.nanoTime();
            try {
                ProcessorEngine.executeHandler(owner, handler.contract(), context);
            } finally {
                metrics.addHandlerExecutionNanos(System.nanoTime() - executionStart);
            }
            if (execution.isScopeInactive(scopePath) && !allowTerminatedWork) {
                break;
            }
        }
    }
}
