package blue.language.runtime;

import blue.language.codec.BlueFormat;
import blue.language.model.Node;
import blue.language.preprocess.provider.BasicNodeProvider;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class DefinitionPresenceContractTest {
    @Test
    void shouldRetainRequiredChildObligationWhenPreparingDefinition() {
        // given
        BasicNodeProvider provider = new BasicNodeProvider();
        try (BlueLanguage language = BlueLanguage.builder().nodeProvider(provider).build()) {
            Node definition = source(language, "name: Record\nfield:\n  type: Text\n  schema:\n    required: true\n    minLength: 2\n");
            // when
            Node prepared = language.resolution().resolveDefinition(definition);
            Node canonical = language.identity().canonicalIdentityInput(definition);
            provider.addSingleNodes(canonical);
            String id = language.identity().directBlueId(canonical);
            // then
            assertNull(prepared.getProperties().get("field").getValue());
            assertTrue(prepared.getProperties().get("field").getSchema().getRequiredValue());
            assertThrows(IllegalArgumentException.class, () -> language.resolution().resolve(new Node().type(new Node().blueId(id))));
            assertThrows(IllegalArgumentException.class, () -> language.resolution().resolve(new Node().type(canonical.clone())));
            assertDoesNotThrow(() -> language.resolution().resolve(new Node().type(new Node().blueId(id)).properties("field", new Node().value("ok"))));
            assertThrows(IllegalArgumentException.class, () -> language.resolution().resolve(new Node().type(canonical.clone()).properties("field", new Node().type("Text"))));
        }
    }

    @Test
    void shouldKeepOptionalDeclarationAbsentUntilObjectPayloadArrives() {
        // given
        try (BlueLanguage language = BlueLanguage.builder().build()) {
            Node definition = source(language, "branch:\n  child:\n    type: Text\n    schema:\n      required: true\n");
            // when / then
            assertDoesNotThrow(() -> language.resolution().resolveDefinition(definition));
            Node absent = assertDoesNotThrow(() -> language.resolution().resolve(new Node().type(definition.clone())));
            assertNull(absent.getProperties().get("branch").getProperties().get("child").getValue());
            assertThrows(IllegalArgumentException.class, () -> language.resolution().resolve(source(language,
                    "type:\n  branch:\n    child:\n      type: Text\n      schema:\n        required: true\nbranch: {}\n")));
        }
    }

    @Test
    void shouldTreatEmptyObjectAsFixedPayloadAndRootRequiredAsTrivial() {
        // given
        try (BlueLanguage language = BlueLanguage.builder().build()) {
            // when / then
            assertDoesNotThrow(() -> language.resolution().resolve(source(language, "name: Metadata\nschema:\n  required: true\n")));
            assertDoesNotThrow(() -> language.resolution().resolveDefinition(source(language, "field: {}\n")));
            assertThrows(IllegalArgumentException.class, () -> language.resolution().resolveDefinition(source(language,
                    "type:\n  field: {}\nfield:\n  schema:\n    minFields: 1\n")));
        }
    }

    @Test
    void shouldRejectKnownIncompatibleSchemaKindsWithoutSampleValues() {
        // given
        try (BlueLanguage language = BlueLanguage.builder().build()) {
            String[] definitions = {
                    "type: Integer\nschema:\n  minLength: 1\n",
                    "type: Text\nschema:\n  minimum: 1\n",
                    "type: Text\nschema:\n  minItems: 1\n",
                    "type: List\nschema:\n  minFields: 1\n",
                    "type: Dictionary\nschema:\n  enum: [x]\n",
                    "type: Text\nschema:\n  enum: []\n",
                    "type: Text\nschema:\n  minLength: 3\n  maxLength: 2\n"};
            // when / then
            for (String definition : definitions) {
                assertThrows(IllegalArgumentException.class,
                        () -> language.resolution().resolveDefinition(source(language, definition)), definition);
                assertThrows(IllegalArgumentException.class,
                        () -> language.identity().canonicalIdentityInput(source(language, definition)), definition);
            }
        }
    }

    @Test
    void shouldValidateAuthoredAndInheritedFixedPayloadsInsideDefinitions() {
        // given
        try (BlueLanguage language = BlueLanguage.builder().build()) {
            String[] definitions = {
                    "type: Text\nvalue: bad\nschema:\n  enum: [good]\n",
                    "field:\n  type: Text\n  value: x\n  schema:\n    minLength: 2\n",
                    "type:\n  field:\n    value: x\nfield:\n  schema:\n    minLength: 2\n"};
            // when / then
            for (String definition : definitions) {
                assertThrows(IllegalArgumentException.class,
                        () -> language.resolution().resolveDefinition(source(language, definition)), definition);
                assertThrows(IllegalArgumentException.class,
                        () -> language.identity().canonicalIdentityInput(source(language, definition)), definition);
            }
        }
    }

    @Test
    void shouldDeferUnknownPayloadKindWithoutSuppressingCompletedValidation() {
        // given
        try (BlueLanguage language = BlueLanguage.builder().build()) {
            Node definition = source(language, "schema:\n  minLength: 2\n");
            // when
            Node prepared = language.resolution().resolveDefinition(definition);
            // then
            assertNotNull(prepared.getSchema().getMinLength());
            assertNull(prepared.getValue());
            assertThrows(IllegalArgumentException.class, () -> language.resolution().resolve(definition));
            assertDoesNotThrow(() -> language.resolution().resolve(new Node().type(definition.clone()).value("yes")));
        }
    }

    @Test
    void shouldNotLetPreparationChangeTheIdentityOfAnAcceptedValue() {
        // given
        try (BlueLanguage language = BlueLanguage.builder().build()) {
            Node value = source(language, "type: Text\nvalue: hello\nschema:\n  minLength: 2\n");
            String before = language.identity().sourceDocumentBlueId(value);
            // when
            Node prepared = language.resolution().resolveDefinition(value);
            Node completed = language.resolution().resolve(value);
            // then
            assertEquals(before, language.identity().sourceDocumentBlueId(prepared));
            assertEquals(before, language.identity().sourceDocumentBlueId(completed));
        }
    }

    private Node source(BlueLanguage language, String yaml) {
        return language.codec().parseSource(yaml, BlueFormat.YAML);
    }
}
