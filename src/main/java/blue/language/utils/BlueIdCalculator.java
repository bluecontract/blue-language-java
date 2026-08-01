package blue.language.utils;

import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Compatibility facade for the focused direct identity calculator.
 *
 * <p>New code should depend on {@link DirectBlueIdCalculator}. All methods in
 * this class delegate to that one implementation path.</p>
 */
public class BlueIdCalculator {

    /** Shared compatibility calculator using the normative hash function. */
    public static final BlueIdCalculator INSTANCE = new BlueIdCalculator(
            DirectBlueIdCalculator.INSTANCE);

    private final DirectBlueIdCalculator delegate;

    /**
     * Creates a compatibility calculator with an injected hash function.
     *
     * @param hashProvider deterministic canonical-value hash function
     */
    public BlueIdCalculator(Function<Object, String> hashProvider) {
        this(new DirectBlueIdCalculator(Objects.requireNonNull(
                hashProvider,
                "hashProvider")));
    }

    private BlueIdCalculator(DirectBlueIdCalculator delegate) {
        this.delegate = delegate;
    }

    /**
     * Calculates the strict canonical identity of one node.
     *
     * @param node exact node
     * @return canonical BlueId
     */
    public static String calculateBlueId(Node node) {
        return INSTANCE.delegate.directBlueId(node);
    }

    /**
     * Calculates legacy structural identity without strict validation.
     *
     * @param node source node
     * @return unchecked direct BlueId
     */
    public static String calculateUncheckedBlueId(Node node) {
        return INSTANCE.delegate.uncheckedBlueId(node);
    }

    /**
     * Calculates strict identity while accepting cyclic placeholders.
     *
     * @param node exact node
     * @return canonical BlueId
     */
    public static String calculateBlueIdAllowingCyclicPlaceholders(Node node) {
        return INSTANCE.delegate
                .directBlueIdAllowingCyclicPlaceholders(node);
    }

    /**
     * Calculates strict ordered identity for node elements.
     *
     * @param nodes ordered elements
     * @return canonical list BlueId
     */
    public static String calculateBlueId(List<Node> nodes) {
        return INSTANCE.delegate.directBlueId(nodes);
    }

    /**
     * Calculates legacy structural identity for a node list.
     *
     * @param nodes ordered elements
     * @return unchecked list BlueId
     */
    public static String calculateUncheckedBlueId(List<Node> nodes) {
        return INSTANCE.delegate.uncheckedBlueId(nodes);
    }

    /**
     * Calculates ordered list identity while accepting cyclic placeholders.
     *
     * @param nodes ordered elements
     * @return canonical list BlueId
     */
    public static String calculateBlueIdAllowingCyclicPlaceholders(
            List<Node> nodes) {
        return INSTANCE.delegate
                .directBlueIdAllowingCyclicPlaceholders(nodes);
    }

    /**
     * Calculates identity from an already projected map/list/scalar value.
     *
     * @param object projected identity input
     * @return calculated BlueId
     */
    public String calculate(Object object) {
        return delegate.directBlueIdFromCanonicalInput(object);
    }
}
