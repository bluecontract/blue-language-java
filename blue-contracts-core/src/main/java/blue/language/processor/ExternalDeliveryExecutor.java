package blue.language.processor;

import java.util.List;
import java.util.Objects;

/** Executes one already-classified logical delivery exactly once. */
final class ExternalDeliveryExecutor {

    private final ProcessorInvocationState execution;
    private final ScopeHandlerDispatcher handlerDispatcher;
    private final HandlerChannelSelector handlerSelector;
    private final LogicalDeliveryGrouper deliveryGrouper;

    ExternalDeliveryExecutor(
            ProcessorInvocationState execution,
            ScopeHandlerDispatcher handlerDispatcher,
            HandlerChannelSelector handlerSelector,
            LogicalDeliveryGrouper deliveryGrouper) {
        this.execution = Objects.requireNonNull(execution, "execution");
        this.handlerDispatcher = Objects.requireNonNull(
                handlerDispatcher, "handlerDispatcher");
        this.handlerSelector = Objects.requireNonNull(
                handlerSelector, "handlerSelector");
        this.deliveryGrouper = Objects.requireNonNull(
                deliveryGrouper, "deliveryGrouper");
    }

    ContractBundle execute(
            List<ChannelRunner.ExternalClassification> classifications) {
        ChannelRunner.ExternalClassification first =
                deliveryGrouper.requireCoherent(classifications);
        String scopePath = first.scopePath();
        if (execution.shouldStopScopeWork(scopePath)) {
            return null;
        }
        ContractBundle executionBundle =
                execution.initializeAcceptedScope(scopePath);
        if (executionBundle == null) {
            if (!execution.hasFailure()) {
                execution.recordCompletedDelivery();
            }
            return null;
        }
        handlerSelector.requireExecutableTarget(
                scopePath,
                executionBundle,
                first.handlerChannelKey());
        if (!handlerDispatcher.dispatch(
                scopePath,
                executionBundle,
                first.handlerChannelKey(),
                first.payload(),
                first.carriedExactValues())) {
            if (!execution.hasFailure()) {
                execution.recordCompletedDelivery();
            }
            return null;
        }
        execution.recordCompletedDelivery();
        return executionBundle;
    }
}
