package blue.language.processor;

import blue.language.processor.model.HandlerContract;

import java.util.Collections;
import java.util.List;

/**
 * Runtime implementation of one exact Handler contract type.
 *
 * <p>Matching occurs against immutable header data before
 * {@link #execute(HandlerContract, ProcessorExecutionContext)} is called.
 * Effects produced during execution remain buffered until the kernel commits
 * the invocation.</p>
 *
 * @param <T> exact Handler contract model handled by the processor
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
     *
     * @return immutable names of executable-body fields
     */
    default List<String> executableBodyFields() {
        return Collections.emptyList();
    }

    /**
     * Derives the channel key to which this Handler subscribes.
     *
     * @param contract immutable effective Handler contract
     * @param context same-scope registration context
     * @return derived channel key, or {@code null} when the runtime does not
     * derive a subscription
     */
    default String deriveChannel(T contract, HandlerRegistrationContext context) {
        return null;
    }

    /**
     * Determines whether this Handler accepts the current delivery.
     *
     * @param contract immutable effective Handler contract
     * @param context immutable matching context
     * @return {@code true} when the Handler should execute
     */
    default boolean matches(T contract, HandlerMatchContext context) {
        return true;
    }

    /**
     * Executes an accepted Handler against the invocation-local effect buffer.
     *
     * @param contract immutable effective Handler contract
     * @param context execution context used to emit buffered effects
     */
    void execute(T contract, ProcessorExecutionContext context);
}
