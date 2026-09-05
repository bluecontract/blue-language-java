package blue.language.runtime;

import blue.language.codec.BlueFormat;
import blue.language.model.Node;
import blue.language.model.NodeWireForm;
import blue.language.preprocess.provider.BasicNodeProvider;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class PartialPayloadMatrixTest {
    @Test
    void shouldApplyOneFixedPayloadRuleAcrossDefinitionRepresentations() {
        // given
        List<Row> rows = Arrays.asList(
                row("text omitted", "type: Text\nschema: {minLength: 2, maxLength: 3}", true),
                row("text empty", "type: Text\nvalue: ''\nschema: {minLength: 2}", false),
                row("text partial", "type: Text\nvalue: x\nschema: {minLength: 2}", false),
                row("text full", "type: Text\nvalue: xy\nschema: {minLength: 2, maxLength: 2}", true),
                row("text too long", "type: Text\nvalue: xyz\nschema: {maxLength: 2}", false),
                row("list omitted", "type: List\nschema: {minItems: 2, maxItems: 3}", true),
                row("list empty", "type: List\nitems: []\nschema: {minItems: 1}", false),
                row("list partial", "type: List\nitems: [x]\nschema: {minItems: 2}", false),
                row("list full", "type: List\nitems: [x, y]\nschema: {minItems: 2, maxItems: 2}", true),
                row("list too long", "type: List\nitems: [x, y]\nschema: {maxItems: 1}", false),
                row("object omitted", "type: Dictionary\nschema: {minFields: 2, maxFields: 3}", true),
                row("object partial", "type: Dictionary\na: x\nschema: {minFields: 2}", false),
                row("object full", "type: Dictionary\na: x\nb: y\nschema: {minFields: 2, maxFields: 2}", true),
                row("object too large", "type: Dictionary\na: x\nb: y\nschema: {maxFields: 1}", false),
                row("required declaration", "type: Dictionary\na: {type: Text, schema: {required: true}}\nschema: {maxFields: 0}", true),
                row("contradictory text", "type: Text\nschema: {minLength: 3, maxLength: 2}", false),
                row("contradictory list", "type: List\nschema: {minItems: 3, maxItems: 2}", false),
                row("contradictory object", "type: Dictionary\nschema: {minFields: 3, maxFields: 2}", false));
        // when
        // Each row is stored exactly even when it is an invalid definition,
        // exercising provider verification separately from definition admission.
        // then
        for (Row row : rows) {
            try (Fixture fixture = new Fixture(row.yaml)) {
                for (Node form : fixture.forms()) {
                    Object before = NodeWireForm.get(form);
                    if (row.valid) {
                        assertDoesNotThrow(() -> fixture.language.resolution().resolveDefinition(form), row.label);
                        assertDoesNotThrow(() -> fixture.language.identity().canonicalIdentityInput(form), row.label + " " + before);
                    } else {
                        assertThrows(IllegalArgumentException.class,
                                () -> fixture.language.resolution().resolveDefinition(form), row.label);
                        assertThrows(IllegalArgumentException.class,
                                () -> fixture.language.identity().canonicalIdentityInput(form), row.label + " " + before);
                    }
                    assertEquals(before, NodeWireForm.get(form), row.label);
                }
            }
        }
    }

    @Test
    void shouldDistinguishEmptyObjectFromOmittedPayloadAndRequiredDeclaration() {
        // given
        try (BlueLanguage language = BlueLanguage.builder().build()) {
            Node declaration = source(language, "field: {type: Dictionary, schema: {minFields: 1}}\n");
            Node empty = source(language, "type:\n  field: {type: Dictionary, schema: {minFields: 1}}\nfield: {}\n");
            Node required = source(language, "type:\n  field: {type: Dictionary, schema: {required: true, minFields: 1}}\n");
            // when
            Node supplied = empty.clone().properties("field", new Node().properties("x", new Node().value("ok")));
            // then
            assertDoesNotThrow(() -> language.resolution().resolveDefinition(declaration));
            assertDoesNotThrow(() -> language.resolution().resolve(declaration));
            assertDoesNotThrow(() -> language.resolution().resolveDefinition(required));
            assertThrows(IllegalArgumentException.class, () -> language.resolution().resolve(required));
            assertThrows(IllegalArgumentException.class, () -> language.resolution().resolveDefinition(empty));
            assertThrows(IllegalArgumentException.class, () -> language.resolution().resolve(empty));
            assertDoesNotThrow(() -> language.resolution().resolve(supplied));
        }
    }

    @Test
    void shouldSpecializeDefinitionWithoutInventingAnInstanceAndRejectInvalidFixedPrefixes() {
        // given
        try (BlueLanguage language = BlueLanguage.builder().build()) {
            Node gender = source(language, "type: Text\nschema: {enum: [female, male]}\n");
            Node list = source(language, "type: List\nschema: {minItems: 2}\n");
            Node overlay = new Node().name("Narrow Gender");
            // when
            Node specialized = language.graph().specialize(gender, overlay);
            // then
            assertNull(specialized.getValue());
            assertEquals(language.identity().sourceDocumentBlueId(overlay.clone().type(gender)),
                    language.identity().sourceDocumentBlueId(specialized));
            assertThrows(IllegalArgumentException.class, () -> language.resolution().resolve(specialized));
            assertDoesNotThrow(() -> language.resolution().resolve(specialized.clone().value("female")));
            assertThrows(IllegalArgumentException.class,
                    () -> language.graph().specialize(list, new Node().items(new Node().value("x"))));
            assertNull(overlay.getType());
        }
    }

    private static Row row(String label, String yaml, boolean valid) { return new Row(label, yaml, valid); }
    private static Node source(BlueLanguage language, String yaml) { return language.codec().parseSource(yaml, BlueFormat.YAML); }
    private static final class Row {
        private final String label;
        private final String yaml;
        private final boolean valid;
        private Row(String label, String yaml, boolean valid) { this.label = label; this.yaml = yaml; this.valid = valid; }
    }
    private static final class Fixture implements AutoCloseable {
        private final BasicNodeProvider provider = new BasicNodeProvider();
        private final BlueLanguage language = BlueLanguage.builder().nodeProvider(provider).build();
        private final Node declaration;
        private final String id;
        private Fixture(String yaml) {
            declaration = language.preprocessing().preprocess(source(language, yaml));
            id = language.identity().directBlueId(declaration);
            provider.addSingleNodes(declaration);
        }
        private List<Node> forms() {
            Node referenced = new Node().type(new Node().blueId(id));
            return Arrays.asList(declaration.clone(), new Node().type(declaration.clone()), referenced,
                    new Node().properties("nested", declaration.clone()),
                    new Node().properties("nested", referenced.clone()),
                    source(language, "blue:\n  imports:\n    D: {blueId: " + id + "}\ntype: D\n"));
        }
        public void close() { language.close(); }
    }
}
