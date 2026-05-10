package blue.language.processor;

import blue.language.processor.model.HandlerContract;

/**
 * Processor specialization for handler contracts.
 */
public interface HandlerProcessor<T extends HandlerContract> extends ContractProcessor<T> {

    default String deriveChannel(T contract, HandlerRegistrationContext context) {
        return null;
    }

    default boolean matches(T contract, HandlerMatchContext context) {
        return true;
    }

    void execute(T contract, ProcessorExecutionContext context);
}
