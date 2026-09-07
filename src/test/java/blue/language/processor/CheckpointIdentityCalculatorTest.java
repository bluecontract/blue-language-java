package blue.language.processor;

import blue.language.Blue;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import static blue.language.processor.FailureCapture.captureFailure;
import static blue.language.model.wire.BlueLanguageConstants.TEXT_TYPE_BLUE_ID;
import static blue.language.codec.jackson.UncheckedObjectMapper.YAML_MAPPER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CheckpointIdentityCalculatorTest {

    @Test
    void shouldVerifyCheckpointUsesNodeBlueIdForBlueIdInputEvent() {
        // given
        Node event = new Node().properties("kind", new Node().value("direct"));

        // when
        String identity = CheckpointIdentityCalculator.identity(event);

        // then
        assertEquals(DirectBlueIdCalculator.calculateBlueId(event), identity);
    }

    @Test
    void shouldVerifyCheckpointUsesContentBlueIdForSourceEvent() {
        // given
        Blue blue = ProcessorTestSupport.blue();
        Node source = YAML_MAPPER.readValue(
                "blue:\n" +
                "  imports:\n" +
                "    TextAlias:\n" +
                "      blueId: " + TEXT_TYPE_BLUE_ID + "\n" +
                "type: TextAlias\n" +
                "value: hello", Node.class);

        // when
        Throwable failure = captureFailure(
                () -> CheckpointIdentityCalculator.identity(source));
        String expectedIdentity =
                blue.calculateSourceDocumentBlueId(source.clone());
        String actualIdentity =
                CheckpointIdentityCalculator.identity(source, blue);

        // then
        assertTrue(failure instanceof IllegalStateException);
        assertEquals(expectedIdentity, actualIdentity);
    }

    @Test
    void shouldVerifySameContentDifferentSourceShapeIsStale() {
        // given
        Blue blue = ProcessorTestSupport.blue();
        Node aliased = YAML_MAPPER.readValue(
                "blue:\n" +
                "  imports:\n" +
                "    TextAlias:\n" +
                "      blueId: " + TEXT_TYPE_BLUE_ID + "\n" +
                "type: TextAlias\n" +
                "value: hello", Node.class);
        Node direct = YAML_MAPPER.readValue(
                "type:\n" +
                "  blueId: " + TEXT_TYPE_BLUE_ID + "\n" +
                "value: hello", Node.class);

        // when
        String directIdentity =
                CheckpointIdentityCalculator.identity(direct, blue);
        String aliasedIdentity =
                CheckpointIdentityCalculator.identity(aliased, blue);

        // then
        assertEquals(directIdentity, aliasedIdentity);
    }

    @Test
    void shouldCanonicalizeInlineReferenceAndMixedTypeSourceEqually() {
        // given
        Blue blue = ProcessorTestSupport.blue();
        Node authoredType = new Node()
                .name("Checkpoint Event Type");
        String typeBlueId = DirectBlueIdCalculator.calculateBlueId(
                authoredType);
        String unrelatedAnnotation = DirectBlueIdCalculator.calculateBlueId(
                new Node().name("Unrelated annotation"));
        Node inline = new Node()
                .type(authoredType.clone())
                .properties("payload", new Node().value("same"));
        Node reference = new Node()
                .type(new Node().blueId(typeBlueId))
                .properties("payload", new Node().value("same"));
        Node mixed = new Node()
                .type(authoredType.clone().blueId(unrelatedAnnotation))
                .properties("payload", new Node().value("same"));

        // when
        String inlineIdentity = CheckpointIdentityCalculator.identity(
                inline, blue);
        String referenceIdentity = CheckpointIdentityCalculator.identity(
                reference, blue);
        String mixedIdentity = CheckpointIdentityCalculator.identity(
                mixed, blue);

        // then
        assertEquals(
                blue.calculateSourceDocumentBlueId(inline.clone()),
                inlineIdentity);
        assertEquals(referenceIdentity, inlineIdentity);
        assertEquals(inlineIdentity, mixedIdentity);
    }

    @Test
    void shouldFailClosedForInlineTypeSourceWithoutLanguageRuntime() {
        // given
        Node event = new Node()
                .type(new Node().name("Checkpoint Event Type"))
                .properties("payload", new Node().value("value"));

        // when
        Executable calculateIdentity =
                () -> CheckpointIdentityCalculator.identity(event);

        // then
        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                calculateIdentity);
        assertTrue(failure.getMessage().contains(
                "requires a Blue canonicalization context"));
    }

    @Test
    void shouldVerifyDifferentContentSameEventIdStillNewByDefault() {
        // given
        Blue blue = ProcessorTestSupport.blue();
        Node first = new Node()
                .properties("eventId", new Node().value("same-id"))
                .properties("amount", new Node().value(1));
        Node second = new Node()
                .properties("eventId", new Node().value("same-id"))
                .properties("amount", new Node().value(2));

        // when
        String firstIdentity =
                CheckpointIdentityCalculator.identity(first, blue);
        String secondIdentity =
                CheckpointIdentityCalculator.identity(second, blue);

        // then
        assertEquals("same-id", first.getAsText("/eventId"));
        assertEquals("same-id", second.getAsText("/eventId"));
        org.junit.jupiter.api.Assertions.assertNotEquals(
                firstIdentity,
                secondIdentity);
    }

    @Test
    void shouldVerifyCheckpointIdentityFailureRequiresDeterministicLanguageIdentity() {
        // given
        Blue blue = ProcessorTestSupport.blue();
        Node event = new Node().blue(new Node().value("not-a-blueid")).value("payload");

        // when
        String identity = CheckpointIdentityCalculator.identity(event, blue);
        String repeatedIdentity =
                CheckpointIdentityCalculator.identity(event, blue);

        // then
        assertNotNull(identity);
        assertEquals(identity, repeatedIdentity);
    }
}
