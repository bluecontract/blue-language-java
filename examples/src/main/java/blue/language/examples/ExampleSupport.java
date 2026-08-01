package blue.language.examples;

import blue.language.model.Node;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Shared, deliberately small support code for the runnable examples. */
final class ExampleSupport {

    private ExampleSupport() {
    }

    /** Creates an exact pure reference without repeating wire construction. */
    static Node reference(String blueId) {
        return new Node().blueId(blueId);
    }

    /** Fails a command-line example when its semantic oracle does not hold. */
    static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    /** Captures an expected deterministic failure for a validation example. */
    static Throwable captureFailure(Runnable operation) {
        try {
            operation.run();
        } catch (Throwable failure) {
            return failure;
        }
        throw new AssertionError("Expected the operation to fail");
    }

    /** Returns a defensive single-node provider response for an exact ID. */
    static List<Node> lookup(
            Map<String, Node> contentByBlueId,
            String requestedBlueId) {
        Node content = contentByBlueId.get(requestedBlueId);
        return content == null
                ? Collections.<Node>emptyList()
                : Collections.singletonList(content.clone());
    }
}
