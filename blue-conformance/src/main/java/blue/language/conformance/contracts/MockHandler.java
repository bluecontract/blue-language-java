package blue.language.conformance.contracts;

import blue.language.model.Node;
import blue.language.model.TypeBlueId;
import blue.language.processor.model.HandlerContract;

/**
 * Fixture-only handler whose declared result is returned by the conformance
 * runtime.
 */
final class MockHandler {

    private MockHandler() {
    }

    /** Public reflection carrier hidden behind this package-private holder. */
    @TypeBlueId(MockTypeBlueIds.MOCK_HANDLER)
    public static final class Value extends HandlerContract {

    private Node result;

    /** Creates an empty fixture handler for mapper population. */
    public Value() {
    }

    /**
     * Returns the declared fixture result.
     *
     * @return retained mutable result node, or {@code null}
     */
    public Node getResult() {
        return result;
    }

    /**
     * Sets the declared fixture result.
     *
     * @param result result node retained by reference, or {@code null}
     */
    public void setResult(Node result) {
        this.result = result;
    }
    }
}
