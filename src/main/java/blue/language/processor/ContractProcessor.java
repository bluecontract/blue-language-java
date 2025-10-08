package blue.language.processor;

import blue.language.processor.model.Contract;

/**
 * Base contract processor marker interface shared by specialized processor types.
 */
public interface ContractProcessor<T extends Contract> {

    Class<T> contractType();
}
