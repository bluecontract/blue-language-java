package blue.language.processor;

import java.util.Objects;

/** Validates the immutable same-scope handler target selected by a source. */
final class HandlerChannelSelector {

    private final ProcessorInvocationState execution;

    HandlerChannelSelector(ProcessorInvocationState execution) {
        this.execution = Objects.requireNonNull(execution, "execution");
    }

    ChannelMemberSnapshot frozenTarget(
            ExternalChannelFunctionEvaluation evaluation,
            SubscriptionDelta.Entry activeInterval,
            String scopePath,
            String sourceChannelKey) {
        ChannelMemberSnapshot target = evaluation.handlerChannel();
        if (evaluation.accepts()
                && activeInterval != null
                && target == null) {
            throw new InvalidExecutionEvidenceException(
                    "External Channel handler target was not frozen by "
                            + "the retained Phase-B dependency surface at "
                            + scopePath + "/" + sourceChannelKey);
        }
        return target;
    }

    void requireExecutableTarget(
            String scopePath,
            ContractBundle bundle,
            String handlerChannelKey) {
        SameScopeChannelCatalog catalog =
                new SameScopeChannelCatalog(
                        Objects.requireNonNull(bundle, "bundle"));
        if (catalog.handlerTarget(handlerChannelKey) == null) {
            execution.abortRuntimeFailure(
                    scopePath,
                    bundle,
                    ProcessorErrorCategory.RuntimeExecutionFailure,
                    "External Channel handler target is not an existing "
                            + "same-scope Channel at " + scopePath + "/"
                            + handlerChannelKey);
        }
    }
}
