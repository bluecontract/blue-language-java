package blue.language.identity;

import blue.language.model.Node;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

import static blue.language.processor.FailureCapture.captureFailure;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;

class BlueIdentityTest {

    @Test
    void shouldRejectSourceOnlyBlueDirectiveOnDirectIdentityPath() {
        // given
        DirectBlueIdCalculator calculator = new DirectBlueIdCalculator();
        Node source = new Node()
                .blue(new Node().value("directive"))
                .value("content");

        // when
        Throwable failure = captureFailure(
                () -> calculator.directBlueId(source));

        // then
        assertInstanceOf(IllegalArgumentException.class, failure);
    }

    @Test
    void shouldCalculateSourceIdentityFromCanonicalInputThroughDirectPath() {
        // given
        DirectBlueIdCalculator direct = new DirectBlueIdCalculator();
        Node source = new Node().value("authored");
        Node canonical = new Node().value("canonical");
        AtomicInteger canonicalizationCalls = new AtomicInteger();
        SourceDocumentBlueIdCalculator sourceCalculator =
                new SourceDocumentBlueIdCalculator(node -> {
                    canonicalizationCalls.incrementAndGet();
                    return canonical.clone();
                }, direct);

        // when
        String sourceBlueId = sourceCalculator.sourceDocumentBlueId(source);

        // then
        assertEquals(direct.directBlueId(canonical), sourceBlueId);
        assertEquals(1, canonicalizationCalls.get());
    }

    @Test
    void shouldExposeCanonicalInputWithoutMutatingTheAuthoredSource() {
        // given
        Node source = new Node().value("authored");
        SourceDocumentBlueIdCalculator calculator =
                new SourceDocumentBlueIdCalculator(node -> {
                    node.value("canonical");
                    return node;
                }, new DirectBlueIdCalculator());

        // when
        Node canonical = calculator.canonicalIdentityInput(source);

        // then
        assertEquals("authored", source.getValue());
        assertEquals("canonical", canonical.getValue());
        assertNotSame(source, canonical);
    }

    @Test
    void shouldReturnTheSameJavaIdentifierTypeForDirectAndSourcePaths()
            throws NoSuchMethodException {
        // given
        Method directMethod = BlueIdentity.class.getMethod(
                "directBlueId",
                Node.class);
        Method sourceMethod = BlueIdentity.class.getMethod(
                "sourceDocumentBlueId",
                Node.class);

        // when
        Class<?> directType = directMethod.getReturnType();
        Class<?> sourceType = sourceMethod.getReturnType();

        // then
        assertEquals(String.class, directType);
        assertEquals(directType, sourceType);
    }

    @Test
    void shouldComposeDirectSourceAndCircularOperationsWithoutMutableState() {
        // given
        BlueIdentity identity = new StandardBlueIdentity(Node::clone);
        Node direct = new Node().value("content");
        Node cyclic = new Node().type(new Node().blueId("this#0"));

        // when
        String directBlueId = identity.directBlueId(direct);
        String sourceBlueId = identity.sourceDocumentBlueId(direct);
        java.util.List<String> circularBlueIds = identity.circularBlueIds(
                Collections.singletonList(cyclic));

        // then
        assertEquals(directBlueId, sourceBlueId);
        assertEquals(1, circularBlueIds.size());
        assertEquals("#0", circularBlueIds.get(0).substring(
                circularBlueIds.get(0).length() - 2));
    }
}
