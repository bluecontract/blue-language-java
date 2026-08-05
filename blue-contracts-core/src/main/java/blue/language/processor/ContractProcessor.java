package blue.language.processor;

import blue.language.processor.model.Contract;

/**
 * Base registration contract for a Java implementation of one runtime type.
 *
 * <p>The returned class is the conversion boundary for canonical contract
 * headers. Runtime dispatch is still keyed by the exact registered BlueId,
 * not by display names or Java-class discovery.</p>
 *
 * @param <T> exact contract model handled by the processor
 */
public interface ContractProcessor<T extends Contract> {

    /**
     * Returns the concrete contract model accepted by this processor.
     *
     * @return exact registered contract class
     */
    Class<T> contractType();
}
