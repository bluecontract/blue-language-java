package blue.language.processor;

import blue.language.processor.model.ChannelContract;

/**
 * Processor specialization for channel contracts.
 */
public interface ChannelProcessor<T extends ChannelContract> extends ContractProcessor<T> {

    /**
     * Exact immutable functions required to index an External Channel.
     *
     * <p>Processor-managed internal Channels need not expose these functions.
     * A registered application Channel that can become an External Channel
     * must return a non-null implementation or changed-surface validation
     * fails closed.</p>
     */
    default ExternalChannelSubscriptionFunctions<T>
    externalSubscriptionFunctions() {
        return null;
    }

    default ChannelEvaluation evaluate(T contract, ChannelEvaluationContext context) {
        boolean matches = matches(contract, context);
        if (!matches) {
            return ChannelEvaluation.noMatch();
        }
        return ChannelEvaluation.match(context.event(), eventId(contract, context));
    }

    default boolean matches(T contract, ChannelEvaluationContext context) {
        return false;
    }

    default String eventId(T contract, ChannelEvaluationContext context) {
        return null;
    }

    default boolean isNewerEvent(T contract, ChannelCheckpointContext context) {
        return true;
    }
}
