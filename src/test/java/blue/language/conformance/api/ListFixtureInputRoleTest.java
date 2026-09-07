package blue.language.conformance.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static blue.language.codec.jackson.UncheckedObjectMapper.YAML_MAPPER;
import static org.junit.jupiter.api.Assertions.*;

/** Fixture input roles retain independent outputs, invalid controls and provider proofs. */
final class ListFixtureInputRoleTest {
    @Test
    void shouldDistinguishAuthoredAppendFromCanonicalReorder() throws Exception {
        // given
        JsonNode fixture = fixture("R_positional_reorder_or_remove_rejected");
        // when
        BlueConformanceSuiteRunner.runFixtureForTest(fixture);
        // then
        assertEquals(3, fixture.path("variants").size());
        assertEquals("ListControlViolation", fixture.at("/variants/0/expectedErrorCategory").asText());
        assertEquals("FixedValueConflict", fixture.at("/variants/2/expectedErrorCategory").asText());
    }

    @Test
    void shouldConsumeVerifiedControlsAndKeepTheOriginalInvalidSource() throws Exception {
        // given
        JsonNode positive = fixture("R_canonical_overlay_no_previous_no_pos");
        JsonNode negative = fixture("R_pos_without_inherited_prefix_rejected");
        // when
        BlueConformanceSuiteRunner.runFixtureForTest(positive);
        BlueConformanceSuiteRunner.runFixtureForTest(negative);
        // then
        assertFalse(positive.path("expectedCanonicalContainsControls").asBoolean());
        assertEquals("ListControlViolation", negative.path("expectedErrorCategory").asText());
    }

    @Test
    void shouldRejectWrongPrefixAndAlteredCanonicalExpectedValue() throws Exception {
        // given
        ObjectNode badPrefix = fixture("R_canonical_overlay_no_previous_no_pos");
        ((ObjectNode) badPrefix.at("/source/items/0/$previous"))
                .put("blueId", "GhNUbi6oXA1HArr2uTqwpcgegPv8kxUuj11riBtoMJXz");
        ObjectNode wrongExpected = fixture("R_canonical_overlay_no_previous_no_pos");
        ((ObjectNode) wrongExpected.at("/expectedCanonicalOverlay/items/1"))
                .put("value", "changed");
        // when
        Throwable prefixFailure = failure(badPrefix);
        Throwable outputFailure = failure(wrongExpected);
        // then
        assertNotNull(prefixFailure);
        assertInstanceOf(AssertionError.class, outputFailure);
    }

    @Test
    void shouldRejectCanonicalReorderDeclaredValidAndForgedProviderBytes() throws Exception {
        // given
        ObjectNode acceptedReorder = fixture("R_positional_reorder_or_remove_rejected");
        ObjectNode variant = (ObjectNode) acceptedReorder.at("/variants/0");
        variant.remove("expectedErrorCategory");
        variant.put("expectedValid", true);
        ObjectNode forged = fixture("R_positional_reorder_or_remove_rejected");
        ((ArrayNode) forged.at("/provider/0/node/items")).set(0,
                YAML_MAPPER.readTree("forged"));
        // when
        Throwable reorderFailure = failure(acceptedReorder);
        Throwable providerFailure = failure(forged);
        // then
        assertNotNull(reorderFailure);
        assertNotNull(providerFailure);
    }

    @Test
    void shouldRejectAmbiguousInputAndSourceAliasesInCanonicalInput() throws Exception {
        // given
        ObjectNode ambiguous = fixture("R_positional_reorder_or_remove_rejected");
        ((ObjectNode) ambiguous.at("/variants/0")).putObject("source");
        ObjectNode alias = fixture("R_positional_reorder_or_remove_rejected");
        ObjectNode first = (ObjectNode) alias.at("/variants/0");
        first.remove("expectedErrorCategory");
        first.put("expectedValid", true);
        ((ObjectNode) first.path("canonicalInput")).put("type", "List");
        // when
        Throwable ambiguityFailure = failure(ambiguous);
        Throwable aliasFailure = failure(alias);
        // then
        assertInstanceOf(IllegalArgumentException.class, ambiguityFailure);
        assertNotNull(aliasFailure);
    }

    private static Throwable failure(JsonNode fixture) {
        try {
            BlueConformanceSuiteRunner.runFixtureForTest(fixture);
            return null;
        } catch (RuntimeException | AssertionError error) {
            return error;
        }
    }

    private static ObjectNode fixture(String id) throws Exception {
        String path = "blue-language-1.0/fixtures/resolver/" + id + ".yaml";
        try (java.io.InputStream stream = ListFixtureInputRoleTest.class.getClassLoader().getResourceAsStream(path)) {
            if (stream == null) throw new IllegalStateException("Missing fixture " + path);
            return (ObjectNode) YAML_MAPPER.readTree(stream);
        }
    }
}
