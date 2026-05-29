package blue.language.processor;

import blue.language.Blue;
import blue.language.model.Node;
import blue.language.utils.BlueIdCalculator;
import org.junit.jupiter.api.Test;

import static blue.language.utils.Properties.TEXT_TYPE_BLUE_ID;
import static blue.language.utils.UncheckedObjectMapper.YAML_MAPPER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class CheckpointIdentityCalculatorTest {

    @Test
    void checkpointUsesNodeBlueIdForBlueIdInputEvent() {
        Node event = new Node().properties("kind", new Node().value("direct"));

        assertEquals(BlueIdCalculator.calculateBlueId(event), CheckpointIdentityCalculator.identity(event));
    }

    @Test
    void checkpointUsesContentBlueIdForSourceEvent() {
        Blue blue = ProcessorTestSupport.blue();
        Node source = YAML_MAPPER.readValue(
                "blue:\n" +
                "  imports:\n" +
                "    TextAlias:\n" +
                "      blueId: " + TEXT_TYPE_BLUE_ID + "\n" +
                "type: TextAlias\n" +
                "value: hello", Node.class);

        assertThrows(IllegalStateException.class, () -> CheckpointIdentityCalculator.identity(source));
        assertEquals(blue.calculateSemanticBlueId(source.clone()), CheckpointIdentityCalculator.identity(source, blue));
    }

    @Test
    void sameContentDifferentSourceShapeIsStale() {
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

        assertEquals(CheckpointIdentityCalculator.identity(direct, blue),
                CheckpointIdentityCalculator.identity(aliased, blue));
    }

    @Test
    void differentContentSameEventIdStillNewByDefault() {
        Blue blue = ProcessorTestSupport.blue();
        Node first = new Node()
                .properties("eventId", new Node().value("same-id"))
                .properties("amount", new Node().value(1));
        Node second = new Node()
                .properties("eventId", new Node().value("same-id"))
                .properties("amount", new Node().value(2));

        assertEquals("same-id", first.getAsText("/eventId"));
        assertEquals("same-id", second.getAsText("/eventId"));
        org.junit.jupiter.api.Assertions.assertNotEquals(
                CheckpointIdentityCalculator.identity(first, blue),
                CheckpointIdentityCalculator.identity(second, blue));
    }

    @Test
    void checkpointIdentityFailureRequiresDeterministicLanguageIdentity() {
        Blue blue = ProcessorTestSupport.blue();
        Node event = new Node().blue(new Node().value("not-a-blueid")).value("payload");

        String identity = CheckpointIdentityCalculator.identity(event, blue);
        assertNotNull(identity);
        assertEquals(identity, CheckpointIdentityCalculator.identity(event, blue));
    }
}
