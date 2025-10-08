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
        ProcessorEngine.ChannelMatch match = ProcessorEngine.evaluateChannel(owner, contract, bundle, scopePath, event);
        if (!match.matches) {
            return;
        }
        Node eventForHandlers = match.eventNode() != null ? match.eventNode() : event;
        checkpointManager.ensureCheckpointMarker(scopePath, bundle);
        CheckpointManager.CheckpointRecord checkpoint = checkpointManager.findCheckpoint(bundle, channel.key());
        String eventSignature = match.eventId != null
                ? match.eventId
                : ProcessorEngine.canonicalSignature(eventForHandlers);
        if (checkpointManager.isDuplicate(checkpoint, eventSignature)) {
            return;
        }
        runHandlers(scopePath, bundle, channel.key(), eventForHandlers, false);
        if (execution.isScopeInactive(scopePath)) {
            return;
        }
        checkpointManager.persist(scopePath, bundle, checkpoint, eventSignature, eventForHandlers);
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
            runtime.chargeHandlerOverhead();
            ProcessorExecutionContext context = execution.createContext(scopePath, bundle, event, allowTerminatedWork);
            ProcessorEngine.executeHandler(owner, handler.contract(), context);
            if (execution.isScopeInactive(scopePath) && !allowTerminatedWork) {
                break;
            }
        }
    }
}
