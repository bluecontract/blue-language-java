package blue.language;

import blue.language.model.Node;
import blue.language.model.Schema;
import blue.language.provider.VerifyingNodeProvider;
import blue.language.utils.BlueIdReferenceValidator;
import blue.language.utils.limits.PathLimits;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlueIdReferenceValidatorDepthTest {

    private static final int DEEP_LEVELS = 20_000;
    private static final String MALFORMED_BLUE_ID = "symbolic-type-name";
    private static final String NEXT = "next";

    @Test
    void deepValidGraphRespectsResolutionDepthLimitWithoutStackOverflow() {
        DeepGraph graph = deepGraph(DEEP_LEVELS);

        Node resolved = assertDoesNotThrow(
                () -> new Blue().resolve(graph.root, PathLimits.withMaxDepth(2)));

        assertEquals(2, propertyDepth(resolved));
    }

    @Test
    void deepMalformedGraphReportsInvalidBlueIdWithoutStackOverflow() {
        DeepGraph graph = deepGraph(DEEP_LEVELS);
        graph.deepest.blueId(MALFORMED_BLUE_ID);
        AtomicInteger ordinaryFetches = new AtomicInteger();
        AtomicInteger trustedFetches = new AtomicInteger();
        Blue ordinary = new Blue(countingMiss(ordinaryFetches));
        Blue trusted = new Blue(
                new VerifyingNodeProvider(countingMiss(trustedFetches)));

        RuntimeException ordinaryFailure = assertThrows(RuntimeException.class,
                () -> ordinary.resolve(graph.root, PathLimits.withMaxDepth(2)));
        RuntimeException trustedFailure = assertThrows(RuntimeException.class,
                () -> trusted.resolve(graph.root, PathLimits.withMaxDepth(2)));

        assertMalformedDeepFailure(ordinaryFailure);
        assertMalformedDeepFailure(trustedFailure);
        assertEquals(0, ordinaryFetches.get());
        assertEquals(0, trustedFetches.get());
    }

    @Test
    void deepObjectCycleTerminatesWithoutMutation() {
        DeepGraph graph = deepGraph(DEEP_LEVELS);
        Node originalRootChild = property(graph.root, NEXT);
        graph.deepest.properties("cycle", graph.midpoint);

        assertDoesNotThrow(() -> BlueIdReferenceValidator.validate(graph.root));

        assertSame(originalRootChild, property(graph.root, NEXT));
        assertSame(graph.midpoint, property(graph.deepest, "cycle"));
    }

    @Test
    void iterativeTraversalPreservesFirstErrorOrder() {
        Node source = new Node()
                .type(malformedReference())
                .properties("first", malformedReference())
                .properties("second", malformedReference())
                .schema(new Schema().enumValues(Collections.singletonList(malformedReference())));

        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> BlueIdReferenceValidator.validate(source));

        assertTrue(failure.getMessage().contains("/type/blueId"), failure.getMessage());
    }

    @Test
    void sharedMalformedNodeReportsItsFirstDeterministicPath() {
        Node shared = malformedReference();
        Node source = new Node()
                .type(shared)
                .properties("later", shared)
                .schema(new Schema().enumValues(Collections.singletonList(shared)));

        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> BlueIdReferenceValidator.validate(source));

        assertTrue(failure.getMessage().contains("/type/blueId"), failure.getMessage());
    }

    private static DeepGraph deepGraph(int levels) {
        Node root = new Node();
        Node current = root;
        Node midpoint = root;
        for (int level = 0; level < levels; level++) {
            Node child = new Node();
            current.properties(NEXT, child);
            current = child;
            if (level + 1 == levels / 2) {
                midpoint = current;
            }
        }
        return new DeepGraph(root, current, midpoint);
    }

    private static int propertyDepth(Node root) {
        int depth = 0;
        Node current = root;
        while (property(current, NEXT) != null) {
            current = property(current, NEXT);
            depth++;
        }
        return depth;
    }

    private static Node property(Node node, String name) {
        return node.getProperties() == null ? null : node.getProperties().get(name);
    }

    private static Node malformedReference() {
        return new Node().blueId(MALFORMED_BLUE_ID);
    }

    private static NodeProvider countingMiss(AtomicInteger fetches) {
        return blueId -> {
            fetches.incrementAndGet();
            return null;
        };
    }

    private static void assertMalformedDeepFailure(RuntimeException failure) {
        assertEquals(BlueLanguageErrorCategory.InvalidBlueId,
                BlueLanguageErrorClassifier.classify(failure));
        String message = failure.getMessage();
        assertTrue(message.endsWith("/blueId.") || message.endsWith("/blueId"), message);
    }

    private static final class DeepGraph {
        private final Node root;
        private final Node deepest;
        private final Node midpoint;

        private DeepGraph(Node root, Node deepest, Node midpoint) {
            this.root = root;
            this.deepest = deepest;
            this.midpoint = midpoint;
        }
    }
}
