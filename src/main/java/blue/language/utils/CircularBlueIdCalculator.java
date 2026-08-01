package blue.language.utils;

import blue.language.identity.CircularSetIdentityCalculator;
import blue.language.model.Node;

import java.util.List;

/**
 * Compatibility facade for cyclic-set identity calculation.
 *
 * <p>New code should use {@link CircularSetIdentityCalculator}. This class is
 * retained for the frozen 1.x source and binary surface.</p>
 */
public final class CircularBlueIdCalculator {

    private static final CircularSetIdentityCalculator DELEGATE =
            new CircularSetIdentityCalculator();

    private CircularBlueIdCalculator() {
    }

    /**
     * Returns member identifiers in the same order as {@code documents}.
     *
     * @param documents non-empty cyclic document set
     * @return calculated member BlueIds
     */
    public static List<String> calculateCircularSetBlueIds(
            List<Node> documents) {
        return DELEGATE.circularBlueIds(documents);
    }
}
