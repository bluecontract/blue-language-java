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
        ProcessorEngine.ChannelMatch match = ProcessorEngine.evaluateChannel(owner, channel, bundle, scopePath, event);
        if (!match.matches) {
            return;
        }
        if (!match.deliveries().isEmpty()) {
            runDeliveries(scopePath, bundle, channel, event, match);
            return;
        }
        Node eventForHandlers = match.eventNode() != null ? match.eventNode() : event;
        Node checkpointEvent = event != null ? event.clone() : null;
        checkpointManager.ensureCheckpointMarker(scopePath, bundle);
        CheckpointManager.CheckpointRecord checkpoint = checkpointManager.findCheckpoint(bundle, channel.key());
        String eventSignature = match.eventId != null
                ? match.eventId
                : ProcessorEngine.canonicalSignature(checkpointEvent);
        if (checkpointManager.isDuplicate(checkpoint, eventSignature)) {
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
            return;
        }
        runHandlers(scopePath, bundle, channel.key(), eventForHandlers, false);
        if (execution.isScopeInactive(scopePath)) {
            return;
        }
        checkpointManager.persist(scopePath, bundle, checkpoint, eventSignature, checkpointEvent);
    }

    private void runDeliveries(String scopePath,
                               ContractBundle bundle,
                               ContractBundle.ChannelBinding channel,
                               Node checkpointEvent,
                               ProcessorEngine.ChannelMatch match) {
        checkpointManager.ensureCheckpointMarker(scopePath, bundle);
        String fallbackSignature = ProcessorEngine.canonicalSignature(checkpointEvent);
        for (ChannelDelivery delivery : match.deliveries()) {
            if (execution.isScopeInactive(scopePath)) {
                return;
            }
            String checkpointKey = delivery.checkpointKey() != null
                    ? delivery.checkpointKey()
                    : channel.key();
            CheckpointManager.CheckpointRecord checkpoint = checkpointManager.findCheckpoint(bundle, checkpointKey);
            String eventSignature = delivery.eventId() != null ? delivery.eventId() : fallbackSignature;
            if (checkpointManager.isDuplicate(checkpoint, eventSignature)) {
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
                    continue;
                }
            }
            Node eventForHandlers = delivery.eventForDelivery();
            if (eventForHandlers == null) {
                continue;
            }
            runHandlers(scopePath, bundle, channel.key(), eventForHandlers, false);
            if (execution.isScopeInactive(scopePath)) {
                return;
            }
            checkpointManager.persist(scopePath, bundle, checkpoint, eventSignature, checkpointEvent);
        }
    }

    void runHandlers(String scopePath,
                     ContractBundle bundle,
                     String channelKey,
                     Node event,
                     boolean allowTerminatedWork) {
        List<ContractBundle.HandlerBinding> handlers = bundle.handlersFor(channelKey);
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
            if (!ProcessorEngine.matchesHandler(owner, handler.contract(), matchContext)) {
                continue;
            }
            runtime.chargeHandlerOverhead();
            ProcessorExecutionContext context = execution.createContext(scopePath, bundle, event, allowTerminatedWork);
            ProcessorEngine.executeHandler(owner, handler.contract(), context);
            if (execution.isScopeInactive(scopePath) && !allowTerminatedWork) {
                break;
            }
        }
    }
}
