package blue.language.utils;

import blue.language.merge.NodeResolver;
import blue.language.model.Node;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static blue.language.processor.FailureCapture.captureFailure;
import static blue.language.utils.Properties.INTEGER_TYPE_BLUE_ID;
import static blue.language.utils.Properties.TEXT_TYPE_BLUE_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Pins specialization as a validated authoring operation rather than
 * identity-preserving reference expansion.
 */
final class NodeSpecializerTest {

    @Test
    void shouldValidateIndependentSpecializationWithoutMutatingInputs() {
        // given
        Node type = new Node().blueId(TEXT_TYPE_BLUE_ID);
        Node overlay = new Node().value("hello");
        AtomicReference<Node> validated = new AtomicReference<>();
        NodeResolver resolver = (candidate, limits) -> {
            validated.set(candidate);
            return candidate;
        };
        NodeSpecializer specializer = new NodeSpecializer(resolver);

        // when
        Node specialization = specializer.specialize(type, overlay);

        // then
        assertEquals(TEXT_TYPE_BLUE_ID,
                specialization.getType().getBlueId());
        assertEquals("hello", specialization.getValue());
        assertNull(overlay.getType());
        assertNotSame(type, specialization.getType());
        assertNotSame(specialization, validated.get());
    }

    @Test
    void shouldRejectOverlayThatAlreadyDeclaresTypeBeforeResolution() {
        // given
        Node type = new Node().blueId(TEXT_TYPE_BLUE_ID);
        Node overlay = new Node()
                .type(new Node().blueId(INTEGER_TYPE_BLUE_ID))
                .value("ambiguous");
        NodeResolver resolver = (candidate, limits) -> {
            throw new AssertionError("invalid overlay must not be resolved");
        };
        NodeSpecializer specializer = new NodeSpecializer(resolver);

        // when
        Throwable failure = captureFailure(
                () -> specializer.specialize(type, overlay));

        // then
        assertInstanceOf(IllegalArgumentException.class, failure);
        assertEquals(
                "specialization overlay must not already declare type",
                failure.getMessage());
    }
}
