package blue.language.conformance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RootedSemanticBaselineBindingTest {

    @Test
    void shouldPreserveHistoricalEvidenceOutsideSixRootedProfileBindings()
            throws Exception {
        // given
        Path historicalPath = Paths.get("api/semantic-baseline-1.0.json");
        JsonNode historical = SemanticBaselineSupport.readJson(historicalPath);
        ObjectNode rooted = (ObjectNode) SemanticBaselineSupport.readJson(
                Paths.get("api/semantic-baseline-rooted-1.0.json"));

        // when
        String historicalIdentity = SemanticBaselineSupport.sha256(historicalPath);
        restoreReviewedBinding(rooted, historical, "specifications", "contractsSha256",
                "5cc29e91cd8d4aa4d3dca98214da5ceb49b2daa82554ac561260bd98ce5063b8");
        restoreReviewedBinding(rooted, historical, "release", "packageIdentity",
                "sha256:4ff4aca93baf241f3141626e05d5a58d99aa5373b0654957ca81ee3e9a0df139");
        restoreReviewedBinding(rooted, historical, "release", "contractsReleaseIdentity",
                "sha256:06ca8273a28f9fb0298ec6aae672c2558b613582b3e61d4c651e86399fe67631");
        restoreReviewedBinding(rooted, historical, "packages", "contractsRelease",
                "sha256:06ca8273a28f9fb0298ec6aae672c2558b613582b3e61d4c651e86399fe67631");
        restoreReviewedBinding(rooted, historical, "packages", "contractsFixtures",
                "sha256:7bd3a699d1649bd20c715ef001d61a39547942fa7db3ab65c434a480e025db47");
        restoreReviewedBinding(rooted, historical, "gas", "oraclePackageIdentity",
                "sha256:7bd3a699d1649bd20c715ef001d61a39547942fa7db3ab65c434a480e025db47");

        // then
        assertEquals(
                "sha256:2d276c19364df048362ee990522991b9e31a6803ab59ca5c4405b0a1309a27a8",
                historicalIdentity);
        assertEquals(historical, rooted,
                "Every gas, locality, API and provenance field must remain unchanged");
    }

    private static void restoreReviewedBinding(
            ObjectNode rooted,
            JsonNode historical,
            String section,
            String field,
            String expected) {
        ObjectNode bindings = (ObjectNode) rooted.path(section);
        assertEquals(expected, bindings.path(field).asText(), section + "/" + field);
        bindings.set(field, historical.path(section).path(field));
    }
}
