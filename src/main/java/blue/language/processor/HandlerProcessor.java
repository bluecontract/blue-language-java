package blue.language.processor;

import blue.language.processor.model.HandlerContract;

import java.util.Collections;
import java.util.List;

/**
 * Processor specialization for handler contracts.
 */
public interface HandlerProcessor<T extends HandlerContract> extends ContractProcessor<T> {

    /**
     * Direct fields whose values are executable bodies for this exact runtime
     * type.
     *
     * <p>The generic Contracts processor keeps these fields collapsed through
     * preflight and opens them only after this Handler's matcher succeeds.
     * Runtime implementations that do not declare an executable body retain
     * the historical behavior through the empty default.</p>
     */
    default List<String> executableBodyFields() {
        return Collections.emptyList();
    }

    default String deriveChannel(T contract, HandlerRegistrationContext context) {
        return null;
    }

    default boolean matches(T contract, HandlerMatchContext context) {
        return true;
    }

    void execute(T contract, ProcessorExecutionContext context);
}
