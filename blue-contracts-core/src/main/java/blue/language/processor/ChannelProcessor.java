package blue.language.processor;

import blue.language.processor.model.ChannelContract;

/**
 * Processor specialization for contracts that source delivery occurrences.
 *
 * <p>Implementations are registered by exact runtime type. Processor-managed
 * channels may rely on kernel behavior; application channels expose immutable
 * subscription functions so their delivery surface can be derived and
 * verified without executing application code.</p>
 *
 * @param <T> exact Channel contract model handled by the processor
 */
public interface ChannelProcessor<T extends ChannelContract> extends ContractProcessor<T> {

    /**
     * Exact immutable functions required to index an External Channel.
     *
     * <p>Processor-managed internal Channels need not expose these functions.
     * A registered application Channel that can become an External Channel
     * must return a non-null implementation or changed-surface validation
     * fails closed.</p>
     *
     * @return deterministic subscription functions, or {@code null} for a
     * processor-managed Channel
     */
    default ExternalChannelSubscriptionFunctions<T>
    externalSubscriptionFunctions() {
        return null;
    }

    /**
     * Evaluates an event against this Channel.
     *
     * @param contract immutable effective Channel contract
     * @param context immutable evaluation context
     * @return complete match result
     */
    default ChannelEvaluation evaluate(T contract, ChannelEvaluationContext context) {
        boolean matches = matches(contract, context);
        if (!matches) {
            return ChannelEvaluation.noMatch();
        }
        return ChannelEvaluation.match(context.event(), eventId(contract, context));
    }

    /**
     * Determines whether the event in {@code context} matches this Channel.
     *
     * @param contract immutable effective Channel contract
     * @param context immutable evaluation context
     * @return {@code true} when the event matches
     */
    default boolean matches(T contract, ChannelEvaluationContext context) {
        return false;
    }

    /**
     * Derives an optional runtime event identifier.
     *
     * @param contract immutable effective Channel contract
     * @param context immutable evaluation context
     * @return event identifier, or {@code null} when the runtime does not
     * expose one
     */
    default String eventId(T contract, ChannelEvaluationContext context) {
        return null;
    }

    /**
     * Compares the current event with this Channel's checkpoint.
     *
     * @param contract immutable effective Channel contract
     * @param context immutable checkpoint-comparison context
     * @return {@code true} when the event is newer than the checkpoint
     */
    default boolean isNewerEvent(T contract, ChannelCheckpointContext context) {
        return true;
    }
}
