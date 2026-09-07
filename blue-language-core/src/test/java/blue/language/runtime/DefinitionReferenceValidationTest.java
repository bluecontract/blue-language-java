package blue.language.runtime;

import blue.language.codec.BlueFormat;
import blue.language.model.Node;
import blue.language.preprocess.provider.BasicNodeProvider;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

final class DefinitionReferenceValidationTest {
    @Test
    void shouldKeepPureRootIdentityAndMinimizationCold() {
        // given
        AtomicInteger reads = new AtomicInteger();
        try (BlueLanguage language = BlueLanguage.builder().nodeProvider(id -> {
            reads.incrementAndGet();
            throw new AssertionError("Unnecessary root fetch");
        }).build()) {
            Node value = new Node().value("content");
            String id = language.identity().directBlueId(value);
            Node reference = new Node().blueId(id);
            // when / then
            assertEquals(id, language.identity().sourceDocumentBlueId(reference));
            assertEquals(id, language.identity().canonicalIdentityInput(reference).getBlueId());
            assertEquals(id, language.resolution().minimize(reference).getBlueId());
            assertEquals(0, reads.get());
        }
    }

    @Test
    void shouldValidateTypedFixedReferencesWithoutExplicitSchema() {
        // given
        BasicNodeProvider provider = new BasicNodeProvider();
        try (BlueLanguage language = BlueLanguage.builder().nodeProvider(provider).build()) {
            Node bad = language.preprocessing().preprocess(new Node().value("bad"));
            String id = language.identity().directBlueId(bad);
            Node definition = source(language, "type:\n  field:\n    type: Integer\nfield:\n  blueId: " + id + "\n");
            // when / then: missing evidence does not validate, and later content
            // establishes a deterministic kind violation on every call.
            assertThrows(RuntimeException.class, () -> language.resolution().resolveDefinition(definition));
            provider.addSingleNodes(bad);
            for (int i = 0; i < 2; i++) {
                assertThrows(IllegalArgumentException.class, () -> language.resolution().resolveDefinition(definition));
                assertThrows(IllegalArgumentException.class, () -> language.identity().canonicalIdentityInput(definition));
            }
        }
    }

    @Test
    void shouldNotAllowCachedMetadataToBypassInvalidFixedValue() {
        // given
        BasicNodeProvider provider = new BasicNodeProvider();
        try (BlueLanguage language = BlueLanguage.builder().nodeProvider(provider).build()) {
            Node invalid = language.preprocessing().preprocess(source(language,
                    "type: Text\nvalue: bad\nschema:\n  enum: [good]\n"));
            provider.addSingleNodes(invalid);
            String id = language.identity().directBlueId(invalid);
            Node metadata = source(language, "type: List\nitemType:\n  blueId: " + id + "\n");
            // when / then
            for (int i = 0; i < 3; i++) {
                assertThrows(IllegalArgumentException.class, () -> language.resolution().resolveDefinition(metadata));
                assertThrows(IllegalArgumentException.class, () -> language.resolution().resolve(metadata));
            }
        }
    }

    @Test
    void shouldApplyDefinitionGoalToContractsAndListMetadata() {
        // given
        try (BlueLanguage language = BlueLanguage.builder().build()) {
            Node definition = source(language,
                    "contracts:\n  entry:\n    type: Text\n    schema:\n      required: true\n      enum: [x]\n");
            // when / then
            assertDoesNotThrow(() -> language.resolution().resolveDefinition(definition));
            assertThrows(IllegalArgumentException.class, () -> language.resolution().resolve(definition));
            Node list = source(language, "type: List\nitemType:\n  type: Text\n  schema:\n    enum: [x]\n");
            assertDoesNotThrow(() -> language.resolution().resolveDefinition(list));
            assertDoesNotThrow(() -> language.resolution().resolve(list));
        }
    }

    private Node source(BlueLanguage language, String yaml) {
        return language.codec().parseSource(yaml, BlueFormat.YAML);
    }
}
