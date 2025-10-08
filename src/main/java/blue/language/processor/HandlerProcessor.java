package blue.language.processor;

import blue.language.processor.model.HandlerContract;

/**
 * Processor specialization for handler contracts.
 */
public interface HandlerProcessor<T extends HandlerContract> extends ContractProcessor<T> {

    void execute(T contract, ProcessorExecutionContext context);
}
