package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.model.ChannelContract;
import blue.language.processor.util.ProcessorContractConstants;

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
        if (execution.shouldStopScopeWork(scopePath)) {
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
        } catch (RuntimeException ex) {
            execution.enterFatalTermination(scopePath,
                    bundle,
                    execution.fatalCategory(ex, ProcessorErrorCategory.InternalProcessorError),
                    execution.fatalReason(ex, "Channel execution failed"));
            return;
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
        Node checkpointEvent = event;
        long checkpointStart = System.nanoTime();
        CheckpointManager.CheckpointRecord checkpoint;
        String eventSignature;
        try {
            long ensureStart = System.nanoTime();
            checkpointManager.ensureCheckpointMarker(scopePath, bundle);
            metrics.addCheckpointEnsureNanos(System.nanoTime() - ensureStart);
            long findStart = System.nanoTime();
            checkpoint = checkpointManager.findCheckpoint(bundle, channel.key());
            metrics.addCheckpointFindNanos(System.nanoTime() - findStart);
            long identityStart = System.nanoTime();
            eventSignature = eventSignature(event);
            metrics.addCheckpointCurrentIdentityNanos(System.nanoTime() - identityStart);
        } catch (RuntimeException ex) {
            metrics.addCheckpointUpdateNanos(System.nanoTime() - checkpointStart);
            execution.enterFatalTermination(scopePath,
                    bundle,
                    execution.fatalCategory(ex, ProcessorErrorCategory.CheckpointError),
                    execution.fatalReason(ex, "Checkpoint error"));
            return;
        }
        boolean newer;
        long isNewerStart = System.nanoTime();
        try {
            ChannelCheckpointContext checkpointContext = new ChannelCheckpointContext(scopePath,
                    channel.key(),
                    checkpointEvent,
                    eventSignature,
                    checkpoint != null ? checkpoint.lastEventNode : null,
                    checkpoint != null ? checkpoint.lastEventSignature : null,
                    bundle.markers());
            newer = match.processor.isNewerEvent(contract, checkpointContext);
        } finally {
            metrics.addCheckpointIsNewerNanos(System.nanoTime() - isNewerStart);
        }
        if (!newer) {
            metrics.addCheckpointUpdateNanos(System.nanoTime() - checkpointStart);
            return;
        }
        boolean duplicate;
        long duplicateStart = System.nanoTime();
        try {
            duplicate = checkpointManager.isDuplicate(checkpoint, eventSignature);
        } finally {
            metrics.addCheckpointDuplicateNanos(System.nanoTime() - duplicateStart);
        }
        if (duplicate) {
            metrics.addCheckpointUpdateNanos(System.nanoTime() - checkpointStart);
            return;
        }
        metrics.addCheckpointUpdateNanos(System.nanoTime() - checkpointStart);
        if (!runHandlers(scopePath, bundle, channel.key(), eventForHandlers)) {
            return;
        }
        long checkpointPersistStart = System.nanoTime();
        try {
            checkpointManager.persist(scopePath, bundle, checkpoint, eventSignature, checkpointEvent);
        } catch (RuntimeException ex) {
            execution.enterFatalTermination(scopePath,
                    bundle,
                    execution.fatalCategory(ex, ProcessorErrorCategory.CheckpointError),
                    execution.fatalReason(ex, "Checkpoint error"));
        }
        metrics.addCheckpointPersistNanos(System.nanoTime() - checkpointPersistStart);
        metrics.addCheckpointUpdateNanos(System.nanoTime() - checkpointPersistStart);
    }

    private void runDeliveries(String scopePath,
                               ContractBundle bundle,
                               ContractBundle.ChannelBinding channel,
                               Node checkpointEvent,
                               ProcessorEngine.ChannelMatch match) {
        ProcessingMetricsSink metrics = owner.metricsSink();
        long checkpointEnsureStart = System.nanoTime();
        String fallbackSignature;
        try {
            long ensureStart = System.nanoTime();
            checkpointManager.ensureCheckpointMarker(scopePath, bundle);
            metrics.addCheckpointEnsureNanos(System.nanoTime() - ensureStart);
            long identityStart = System.nanoTime();
            fallbackSignature = eventSignature(checkpointEvent);
            metrics.addCheckpointCurrentIdentityNanos(System.nanoTime() - identityStart);
        } catch (RuntimeException ex) {
            metrics.addCheckpointUpdateNanos(System.nanoTime() - checkpointEnsureStart);
            execution.enterFatalTermination(scopePath,
                    bundle,
                    execution.fatalCategory(ex, ProcessorErrorCategory.CheckpointError),
                    execution.fatalReason(ex, "Checkpoint error"));
            return;
        }
        metrics.addCheckpointUpdateNanos(System.nanoTime() - checkpointEnsureStart);
        for (ChannelDelivery delivery : match.deliveries()) {
            if (execution.shouldStopScopeWork(scopePath)) {
                return;
            }
            String checkpointKey = delivery.checkpointKey() != null
                    ? delivery.checkpointKey()
                    : channel.key();
            long checkpointStart = System.nanoTime();
            long findStart = System.nanoTime();
            CheckpointManager.CheckpointRecord checkpoint = checkpointManager.findCheckpoint(bundle, checkpointKey);
            metrics.addCheckpointFindNanos(System.nanoTime() - findStart);
            long identityStart = System.nanoTime();
            String eventSignature = eventSignature(checkpointEvent, fallbackSignature);
            metrics.addCheckpointCurrentIdentityNanos(System.nanoTime() - identityStart);
            Boolean shouldProcess = delivery.shouldProcess();
            if (Boolean.FALSE.equals(shouldProcess)) {
                continue;
            }
            if (shouldProcess == null) {
                boolean newer;
                long isNewerStart = System.nanoTime();
                try {
                    ChannelCheckpointContext checkpointContext = new ChannelCheckpointContext(scopePath,
                            checkpointKey,
                            checkpointEvent,
                            eventSignature,
                            checkpoint != null ? checkpoint.lastEventNode : null,
                            checkpoint != null ? checkpoint.lastEventSignature : null,
                            bundle.markers());
                    newer = match.processor.isNewerEvent(channel.contract(), checkpointContext);
                } finally {
                    metrics.addCheckpointIsNewerNanos(System.nanoTime() - isNewerStart);
                }
                if (!newer) {
                    metrics.addCheckpointUpdateNanos(System.nanoTime() - checkpointStart);
                    continue;
                }
            }
            boolean duplicate;
            long duplicateStart = System.nanoTime();
            try {
                duplicate = checkpointManager.isDuplicate(checkpoint, eventSignature);
            } finally {
                metrics.addCheckpointDuplicateNanos(System.nanoTime() - duplicateStart);
            }
            if (duplicate) {
                metrics.addCheckpointUpdateNanos(System.nanoTime() - checkpointStart);
                continue;
            }
            metrics.addCheckpointUpdateNanos(System.nanoTime() - checkpointStart);
            Node eventForHandlers = delivery.eventForDelivery();
            if (eventForHandlers == null) {
                continue;
            }
            ContractBundle.ChannelBinding handlerChannel = resolveHandlerChannel(scopePath, bundle, channel, delivery);
            if (handlerChannel == null) {
                return;
            }
            String logicalDeliveryKey = delivery.logicalDeliveryKey();
            if (logicalDeliveryKey != null
                    && execution.hasSuccessfulLogicalDelivery(scopePath,
                    eventSignature,
                    handlerChannel.key(),
                    logicalDeliveryKey)) {
                metrics.incrementDeduplicatedChannelDeliveries();
                if (!persistCheckpoint(scopePath, bundle, checkpoint, eventSignature, checkpointEvent)) {
                    return;
                }
                continue;
            }
            if (delivery.handlerChannelKey() != null) {
                metrics.incrementRoutedChannelDeliveries();
            }
            if (!runHandlers(scopePath, bundle, handlerChannel.key(), eventForHandlers)) {
                return;
            }
            if (logicalDeliveryKey != null) {
                execution.recordSuccessfulLogicalDelivery(scopePath,
                        eventSignature,
                        handlerChannel.key(),
                        logicalDeliveryKey);
            }
            if (!persistCheckpoint(scopePath, bundle, checkpoint, eventSignature, checkpointEvent)) {
                return;
            }
        }
    }

    private ContractBundle.ChannelBinding resolveHandlerChannel(String scopePath,
                                                                 ContractBundle bundle,
                                                                 ContractBundle.ChannelBinding sourceChannel,
                                                                 ChannelDelivery delivery) {
        String handlerChannelKey = delivery.handlerChannelKey();
        if (handlerChannelKey == null) {
            return sourceChannel;
        }
        ContractBundle.ChannelBinding handlerChannel = bundle.channelBinding(handlerChannelKey);
        if (handlerChannel != null && (ProcessorContractConstants.isProcessorManagedChannel(handlerChannel.contract())
                || owner.registry().lookupChannel(handlerChannel.contract()).isPresent())) {
            return handlerChannel;
        }
        String normalizedScope = execution.normalizeScope(scopePath);
        execution.enterFatalTermination(scopePath,
                bundle,
                ProcessorErrorCategory.UnsupportedContract,
                "Routed delivery handler channel '" + handlerChannelKey
                        + "' is not a supported same-scope Channel at " + normalizedScope);
        return null;
    }

    private boolean persistCheckpoint(String scopePath,
                                      ContractBundle bundle,
                                      CheckpointManager.CheckpointRecord checkpoint,
                                      String eventSignature,
                                      Node checkpointEvent) {
        ProcessingMetricsSink metrics = owner.metricsSink();
        long checkpointPersistStart = System.nanoTime();
        try {
            checkpointManager.persist(scopePath, bundle, checkpoint, eventSignature, checkpointEvent);
            return true;
        } catch (RuntimeException ex) {
            execution.enterFatalTermination(scopePath,
                    bundle,
                    execution.fatalCategory(ex, ProcessorErrorCategory.CheckpointError),
                    execution.fatalReason(ex, "Checkpoint error"));
            return false;
        } finally {
            metrics.addCheckpointPersistNanos(System.nanoTime() - checkpointPersistStart);
            metrics.addCheckpointUpdateNanos(System.nanoTime() - checkpointPersistStart);
        }
    }

    private String eventSignature(Node fallbackEvent) {
        return eventSignature(fallbackEvent, null);
    }

    private String eventSignature(Node fallbackEvent, String fallbackSignature) {
        return fallbackSignature != null ? fallbackSignature : checkpointManager.eventIdentity(fallbackEvent);
    }

    boolean runHandlers(String scopePath,
                        ContractBundle bundle,
                        String channelKey,
                        Node event) {
        ProcessingMetricsSink metrics = owner.metricsSink();
        long discoveryStart = System.nanoTime();
        List<ContractBundle.HandlerBinding> handlers = bundle.handlersFor(channelKey);
        metrics.addHandlerDiscoveryNanos(System.nanoTime() - discoveryStart);
        if (handlers.isEmpty()) {
            return execution.isScopeActive(scopePath);
        }
        for (ContractBundle.HandlerBinding handler : handlers) {
            if (execution.shouldStopScopeWork(scopePath)) {
                return false;
            }
            HandlerMatchContext matchContext = new HandlerMatchContext(scopePath,
                    handler.key(),
                    channelKey,
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
                    false);
            metrics.incrementHandlersExecuted();
            long executionStart = System.nanoTime();
            try {
                ProcessorEngine.executeHandler(owner, handler.contract(), context);
                context.applyBufferedEffects();
            } catch (RunTerminationException ex) {
                throw ex;
            } catch (ProcessorFatalException ex) {
                execution.enterFatalTermination(scopePath,
                        bundle,
                        ex.errorCategory(),
                        execution.fatalReason(ex, "Handler execution failed"));
                return false;
            } catch (RuntimeException ex) {
                execution.enterFatalTermination(scopePath,
                        bundle,
                        execution.fatalCategory(ex, ProcessorErrorCategory.HandlerExecutionError),
                        execution.fatalReason(ex, "Handler execution failed"));
                return false;
            } finally {
                metrics.addHandlerExecutionNanos(System.nanoTime() - executionStart);
            }
            if (execution.shouldStopScopeWork(scopePath)) {
                return false;
            }
        }
        return execution.isScopeActive(scopePath);
    }
}
