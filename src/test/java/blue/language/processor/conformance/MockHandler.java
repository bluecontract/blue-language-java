package blue.language.processor.conformance;

import blue.language.model.Node;
import blue.language.model.TypeBlueId;
import blue.language.processor.model.HandlerContract;

/**
 * Fixture-only handler whose declared result is returned by the conformance
 * runtime.
 */
@TypeBlueId(MockTypeBlueIds.MOCK_HANDLER)
public final class MockHandler extends HandlerContract {

    private Node result;

    /** Creates an empty fixture handler for mapper population. */
    public MockHandler() {
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
