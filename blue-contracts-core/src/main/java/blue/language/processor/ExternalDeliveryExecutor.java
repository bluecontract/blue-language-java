package blue.language.processor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Executes one already-classified logical delivery exactly once. */
final class ExternalDeliveryExecutor {

    private final ProcessorInvocationState execution;
    private final ScopeHandlerDispatcher handlerDispatcher;
    private final HandlerChannelSelector handlerSelector;
    private final LogicalDeliveryGrouper deliveryGrouper;
    private final Map<String, ContractBundle> postInitializationDispatch =
            new LinkedHashMap<String, ContractBundle>();

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
        ContractBundle dispatchBundle = dispatchBundle(
                first, executionBundle);
        handlerSelector.requireExecutableTarget(
                scopePath,
                dispatchBundle,
                first.handlerChannelKey());
        if (!handlerDispatcher.dispatch(
                scopePath,
                dispatchBundle,
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

    private ContractBundle dispatchBundle(
            ChannelRunner.ExternalClassification classification,
            ContractBundle executionBundle) {
        String scopePath = ProcessorEngine.normalizeScope(
                classification.scopePath());
        ContractBundle frozen = postInitializationDispatch.get(scopePath);
        if (frozen != null) {
            return frozen;
        }
        /*
         * Initialization is a mandatory predecessor of the accepted external
         * transition. Freeze the refreshed post-initialization surface for
         * that transition, then retain it for every later logical group at
         * the same scope. Already-initialized scopes keep the surface frozen
         * during admission, so an earlier group cannot widen their routes.
         */
        frozen = classification.initializationPendingAtDispatchFreeze()
                ? executionBundle
                : classification.dispatchBundle();
        postInitializationDispatch.put(scopePath, frozen);
        return frozen;
    }
}
