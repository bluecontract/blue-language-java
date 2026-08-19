package blue.language.conformance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class SemanticBaselineCaptureCliTest {

    private static final String IMMUTABLE_INVENTORY_IDENTITY =
            "sha256:87793b21667784da0c30b3dc03c74c677d43fac771e1c2bc93cd96a02a25060e";

    @Test
    void shouldPreserveImmutablePublicApiDuringSemanticRefresh() {
        // given
        ObjectNode baseline = baselineWithPublicApi();
        JsonNode originalPublicApi = baseline.path("publicApi").deepCopy();

        // when
        JsonNode preserved =
                SemanticBaselineCaptureCli.preservedPublicApi(baseline);

        // then
        assertEquals(originalPublicApi, preserved);
        assertNotSame(baseline.path("publicApi"), preserved);
        assertEquals(
                IMMUTABLE_INVENTORY_IDENTITY,
                preserved.path("inventorySha256").asText());
        assertEquals(
                "blue.language.LegacyApi",
                preserved.path("inventory").path("classes")
                        .path(0).path("name").asText());
    }

    @Test
    void shouldRejectMissingImmutablePublicApiInsteadOfRebasing() {
        // given
        ObjectNode baseline = SemanticBaselineSupport.JSON.createObjectNode();
        baseline.put("schema", SemanticBaselineSupport.BASELINE_SCHEMA);

        // when
        Executable preservation = () ->
                SemanticBaselineCaptureCli.preservedPublicApi(baseline);

        // then
        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                preservation);
        assertEquals(
                "Missing required JSON value: /publicApi",
                failure.getMessage());
    }

    private static ObjectNode baselineWithPublicApi() {
        ObjectNode baseline = SemanticBaselineSupport.JSON.createObjectNode();
        baseline.put("schema", SemanticBaselineSupport.BASELINE_SCHEMA);
        ObjectNode publicApi = baseline.putObject("publicApi");
        publicApi.put("inventorySha256", IMMUTABLE_INVENTORY_IDENTITY);
        ObjectNode inventory = publicApi.putObject("inventory");
        inventory.put("schema", SemanticBaselineSupport.API_INVENTORY_SCHEMA);
        inventory.putArray("classes")
                .addObject()
                .put("name", "blue.language.LegacyApi");
        return baseline;
    }
}
