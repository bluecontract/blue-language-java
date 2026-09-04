package blue.language.runtime;

import blue.language.codec.BlueFormat;
import blue.language.model.Node;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class DefinitionValidationTest {
    @Test
    void shouldPrepareConstrainedScalarWithoutInventingPayload() {
        // given
        try (BlueLanguage language = BlueLanguage.builder().build()) {
            Node gender = language.codec().parseSource(
                    "name: Gender\ntype: Text\nschema:\n  enum: [female, male]\n", BlueFormat.YAML);
            // when
            Node canonical = assertDoesNotThrow(() -> language.identity().canonicalIdentityInput(gender));
            // then
            assertNull(canonical.getValue());
            assertNotNull(canonical.getSchema());
        }
    }

    @Test
    void shouldRejectMissingCompletedScalarRegardlessOfInlineTypeSyntax() {
        // given
        try (BlueLanguage language = BlueLanguage.builder().build()) {
            Node instance = language.codec().parseSource(
                    "type:\n  name: Gender\n  type: Text\n  schema:\n    enum: [female, male]\n", BlueFormat.YAML);
            // when / then
            assertThrows(IllegalArgumentException.class, () -> language.resolution().resolve(instance));
        }
    }
}
