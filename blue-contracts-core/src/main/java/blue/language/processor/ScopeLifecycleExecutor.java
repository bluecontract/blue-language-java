package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.model.LifecycleChannel;
import blue.language.processor.util.ProcessorContractConstants;
import blue.language.processor.util.ProcessorPointerConstants;
import blue.language.snapshot.FrozenNode;

import java.util.Collections;
import java.util.Objects;

/** Delivers lifecycle Channels and publishes processor-owned markers. */
final class ScopeLifecycleExecutor {

    private static final String INITIALIZATION_MARKER_CHARGE =
            "initialization-marker";

    private final ProcessorInvocationState execution;
    private final DocumentProcessingRuntime runtime;
    private final ScopeHandlerDispatcher handlerDispatcher;
    private final ScopePropagationChain propagationChain;

    ScopeLifecycleExecutor(
            ProcessorInvocationState execution,
            DocumentProcessingRuntime runtime,
            ScopeHandlerDispatcher handlerDispatcher,
            ScopePropagationChain propagationChain) {
        this.execution = Objects.requireNonNull(execution, "execution");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.handlerDispatcher = Objects.requireNonNull(
                handlerDispatcher, "handlerDispatcher");
        this.propagationChain = Objects.requireNonNull(
                propagationChain, "propagationChain");
    }

    void deliver(
            String scopePath,
            ContractBundle bundle,
            Node event) {
        propagationChain.beginDrainDeferral();
        try {
            runtime.chargeLifecycleDelivery();
            runtime.recordTrace(
                    ProcessingTraceRecord.Kind.LIFECYCLE,
                    scopePath,
                    null,
                    null,
                    Collections.emptyMap(),
                    event);
            if (bundle == null) {
                return;
            }
            for (ContractBundle.ChannelBinding channel
                    : bundle.channelsOfType(LifecycleChannel.class)) {
                handlerDispatcher.dispatch(
                        scopePath,
                        bundle,
                        channel.key(),
                        event,
                        true);
                if (execution.shouldStopScopeWork(scopePath)) {
                    break;
                }
            }
        } finally {
            propagationChain.endDrainDeferral();
        }
    }

    void publishInitializationMarker(
            String scopePath,
            FrozenNode initialDocument) {
        FrozenNode marker = ProcessorMarkerFactory.initialized(
                initialDocument);
        String pointer = ProcessorEngine.resolvePointer(
                scopePath,
                ProcessorPointerConstants.RELATIVE_INITIALIZED);
        runtime.chargeProcessorMarkerWritten(
                INITIALIZATION_MARKER_CHARGE);
        runtime.directWrite(pointer, marker.toNode());
        runtime.recordTrace(
                ProcessingTraceRecord.Kind.MARKER_WRITE,
                scopePath,
                ProcessorContractConstants.KEY_INITIALIZED,
                pointer);
    }
}
