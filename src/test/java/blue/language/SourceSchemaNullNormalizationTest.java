package blue.language;

import blue.language.codec.BlueFormat;
import blue.language.model.Node;
import blue.language.model.NodeWireForm;
import blue.language.model.Nodes;
import blue.language.runtime.BlueLanguage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SourceSchemaNullNormalizationTest {
    @ParameterizedTest
    @ValueSource(strings = {
            "required", "uniqueItems", "minLength", "maxLength",
            "minItems", "maxItems", "minFields", "maxFields", "minimum",
            "maximum", "exclusiveMinimum", "exclusiveMaximum", "multipleOf", "enum"
    })
    void shouldOmitNullSchemaKeywordsFromJsonAndYamlSource(String keyword) {
        // given
        try (BlueLanguage language = BlueLanguage.builder().build()) {
            Node expected = language.codec().parseSource(
                    "name: Nullable constraint\nschema: {}", BlueFormat.YAML);
            Node expectedPreprocessed = language.preprocessing().preprocess(expected);
            String expectedBlueId = language.identity().sourceDocumentBlueId(expected);
            String yaml = "name: Nullable constraint\nschema:\n  " + keyword + ": null";
            String json = "{\"name\":\"Nullable constraint\",\"schema\":{\"" + keyword + "\":null}}";

            // when
            Node yamlSource = language.codec().parseSource(yaml, BlueFormat.YAML);
            Node jsonSource = language.codec().parseSource(json, BlueFormat.JSON);
            Node yamlPreprocessed = language.preprocessing().preprocess(yamlSource);
            Node jsonPreprocessed = language.preprocessing().preprocess(jsonSource);

            // then: null omits only the keyword, retaining its schema object.
            assertNotNull(yamlPreprocessed.getSchema());
            assertEquals(NodeWireForm.get(expectedPreprocessed), NodeWireForm.get(yamlPreprocessed));
            assertEquals(NodeWireForm.get(expectedPreprocessed), NodeWireForm.get(jsonPreprocessed));
            assertEquals(expectedBlueId, language.identity().sourceDocumentBlueId(yamlSource));
            assertEquals(expectedBlueId, language.identity().sourceDocumentBlueId(jsonSource));
        }
    }

    @Test
    void shouldPreserveEmptySchemaAcrossSourceSnapshotAndExactGraphIdentity() {
        // given
        try (BlueLanguage language = BlueLanguage.builder().build()) {
            Node source = language.codec().parseSource(
                    "type: Integer\nschema: {}\nvalue: 3\n", BlueFormat.YAML);
            Node canonical = language.identity().canonicalIdentityInput(source);
            String expected = "4SrR9s5T8u24vn5vLB5nPh3eSUtmeGR9uD2uDxLyvpzt";
            // when
            blue.language.merge.ResolvedSnapshot snapshot = language.snapshots().resolve(source);

            // then
            assertEquals(expected, language.identity().sourceDocumentBlueId(source));
            assertEquals(expected, language.identity().directBlueId(canonical));
            assertEquals(NodeWireForm.get(canonical), NodeWireForm.get(snapshot.canonicalRoot()));
            assertNotNull(snapshot.canonicalRoot().getSchema());
            assertEquals(expected, snapshot.blueId());
            assertEquals(expected, language.identity().directBlueId(snapshot.canonicalRoot()));
            assertEquals(expected, language.identity().sourceDocumentBlueId(
                    language.resolution().minimize(source)));
            Node expanded = language.graph().expand(canonical);
            assertNotNull(expanded.getSchema());
            assertEquals(expected, language.graph().collapse(expanded).getBlueId());
        }
    }

    @Test
    void shouldRetainScalarKeywordSourceNullUntilMandatoryPreprocessing() {
        // given
        try (BlueLanguage language = BlueLanguage.builder().build()) {
            Node source = language.codec().parseSource(
                    "schema:\n  minLength: null", BlueFormat.YAML);

            // when
            Node preprocessed = language.preprocessing().preprocess(source);

            // then
            assertTrue(Nodes.isSourceNullLiteral(source.getSchema().getMinLength()));
            assertNull(preprocessed.getSchema().getMinLength());
        }
    }

    @Test
    void shouldOmitNullEnumKeywordFromDirectInputWithoutAcceptingNullListElements() {
        // given
        try (BlueLanguage language = BlueLanguage.builder().build()) {
            Node emptySchema = language.codec().parseBlueIdInput(
                    "schema: {}", BlueFormat.YAML);

            // when
            Node nullKeyword = language.codec().parseBlueIdInput(
                    "schema: {enum: null}", BlueFormat.YAML);

            // then: direct identity omits null object fields under Language §14.2.
            assertEquals(language.identity().directBlueId(emptySchema),
                    language.identity().directBlueId(nullKeyword));
            assertThrows(RuntimeException.class,
                    () -> language.codec().parseBlueIdInput(
                            "schema: {enum: [null]}", BlueFormat.YAML));
        }
    }

    @Test
    void shouldRejectNullEnumElementsAndRetainedWrongKeywordShapes() {
        // given
        String[] invalidSchemas = {
                "enum: [null]", "enum: [{}]", "enum: wrong",
                "required: 1", "uniqueItems: not-a-boolean", "minLength: {}",
                "minItems: -1", "minimum: wrong", "unknown: 1"
        };
        try (BlueLanguage language = BlueLanguage.builder().build()) {
            // when
            for (String invalidSchema : invalidSchemas) {
                String source = "schema:\n  " + invalidSchema;

                // then: Source null omission does not relax retained constraints.
                assertThrows(RuntimeException.class,
                        () -> language.codec().parseSource(source, BlueFormat.YAML), invalidSchema);
            }
        }
    }
}
