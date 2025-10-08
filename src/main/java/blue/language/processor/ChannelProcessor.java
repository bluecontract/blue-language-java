package blue.language.processor;

import blue.language.processor.model.ChannelContract;

/**
 * Processor specialization for channel contracts.
 */
public interface ChannelProcessor<T extends ChannelContract> extends ContractProcessor<T> {

    boolean matches(T contract, ChannelEvaluationContext context);

    default String eventId(T contract, ChannelEvaluationContext context) {
        return null;
    }
}
