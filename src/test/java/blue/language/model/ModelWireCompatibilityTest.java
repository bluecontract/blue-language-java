package blue.language.model;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static blue.language.model.wire.BlueLanguageConstants.OBJECT_NAME;
import static blue.language.model.wire.BlueLanguageConstants.OBJECT_SCHEMA;
import static blue.language.model.wire.SchemaPropertyConstants.KEY_ENUM;
import static blue.language.model.wire.SchemaPropertyConstants.KEY_REQUIRED;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ModelWireCompatibilityTest {

    @Test
    void shouldProjectOfficialNodeAndSchemaWireFields() {
        // given
        Node node = new Node()
                .name("Subject")
                .schema(new Schema()
                        .required(true)
                        .enumValues(Arrays.asList(
                                new Node().value("open"),
                                new Node().value("closed"))))
                .properties("status", new Node().value("open"));

        // when
        @SuppressWarnings("unchecked")
        Map<String, Object> wire =
                (Map<String, Object>) NodeWireForm.get(node);
        @SuppressWarnings("unchecked")
        Map<String, Object> schema =
                (Map<String, Object>) wire.get(OBJECT_SCHEMA);

        // then
        assertEquals("Subject", wire.get(OBJECT_NAME));
        assertEquals(Boolean.TRUE, schema.get(KEY_REQUIRED));
        assertEquals(
                Arrays.asList("open", "closed"),
                schema.get(KEY_ENUM));
    }

    @Test
    void shouldProjectSimpleListWireValues() {
        // given
        Node node = new Node().items(
                new Node().value("first"),
                new Node().value("second"));

        // when
        @SuppressWarnings("unchecked")
        List<Object> wire = (List<Object>) NodeWireForm.get(
                node, NodeWireForm.Strategy.SIMPLE);

        // then
        assertEquals(Arrays.asList("first", "second"), wire);
    }
}
