package blue.language.processor;

import blue.language.processor.model.ChannelContract;

/**
 * Processor specialization for channel contracts.
 */
public interface ChannelProcessor<T extends ChannelContract> extends ContractProcessor<T> {

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
