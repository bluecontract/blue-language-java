package blue.language;

import blue.language.model.Node;
import blue.language.model.Schema;
import blue.language.provider.VerifyingNodeProvider;
import blue.language.utils.BlueIdReferenceValidator;
import blue.language.utils.limits.PathLimits;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

import static blue.language.processor.FailureCapture.captureFailure;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlueIdReferenceValidatorDepthTest {

    private static final int DEEP_LEVELS = 20_000;
    private static final String MALFORMED_BLUE_ID = "symbolic-type-name";
    private static final String NEXT = "next";

    @Test
    void shouldRespectResolutionDepthLimitForDeepValidGraphWithoutStackOverflow() {
        // given
        DeepGraph graph = deepGraph(DEEP_LEVELS);

        // when
        Node resolved = new Blue().resolve(
                graph.root, PathLimits.withMaxDepth(2));

        // then
        assertEquals(2, propertyDepth(resolved));
    }

    @Test
    void shouldReportInvalidBlueIdForDeepMalformedGraphWithoutStackOverflow() {
        // given
        DeepGraph graph = deepGraph(DEEP_LEVELS);
        graph.deepest.blueId(MALFORMED_BLUE_ID);
        AtomicInteger ordinaryFetches = new AtomicInteger();
        AtomicInteger trustedFetches = new AtomicInteger();
        Blue ordinary = new Blue(countingMiss(ordinaryFetches));
        Blue trusted = new Blue(
                new VerifyingNodeProvider(countingMiss(trustedFetches)));

        // when
        Throwable ordinaryFailure = captureFailure(
                () -> ordinary.resolve(graph.root, PathLimits.withMaxDepth(2)));
        Throwable trustedFailure = captureFailure(
                () -> trusted.resolve(graph.root, PathLimits.withMaxDepth(2)));

        // then
        assertMalformedDeepFailure(ordinaryFailure);
        assertMalformedDeepFailure(trustedFailure);
        assertEquals(0, ordinaryFetches.get());
        assertEquals(0, trustedFetches.get());
    }

    @Test
    void shouldTerminateDeepObjectCycleValidationWithoutMutation() {
        // given
        DeepGraph graph = deepGraph(DEEP_LEVELS);
        Node originalRootChild = property(graph.root, NEXT);
        graph.deepest.properties("cycle", graph.midpoint);

        // when
        Throwable failure = captureFailure(
                () -> BlueIdReferenceValidator.validate(graph.root));

        // then
        assertNull(failure);
        assertSame(originalRootChild, property(graph.root, NEXT));
        assertSame(graph.midpoint, property(graph.deepest, "cycle"));
    }

    @Test
    void shouldPreserveFirstErrorOrderDuringIterativeTraversal() {
        // given
        Node source = new Node()
                .type(malformedReference())
                .properties("first", malformedReference())
                .properties("second", malformedReference())
                .schema(new Schema().enumValues(Collections.singletonList(malformedReference())));

        // when
        Throwable failure = captureFailure(
                () -> BlueIdReferenceValidator.validate(source));

        // then
        assertInstanceOf(RuntimeException.class, failure);
        assertTrue(failure.getMessage().contains("/type/blueId"), failure.getMessage());
    }

    @Test
    void shouldReportFirstDeterministicPathForSharedMalformedNode() {
        // given
        Node shared = malformedReference();
        Node source = new Node()
                .type(shared)
                .properties("later", shared)
                .schema(new Schema().enumValues(Collections.singletonList(shared)));

        // when
        Throwable failure = captureFailure(
                () -> BlueIdReferenceValidator.validate(source));

        // then
        assertInstanceOf(RuntimeException.class, failure);
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

    private static void assertMalformedDeepFailure(Throwable failure) {
        assertInstanceOf(RuntimeException.class, failure);
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
