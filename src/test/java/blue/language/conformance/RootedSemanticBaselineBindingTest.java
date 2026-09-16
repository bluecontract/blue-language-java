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
                "sha256:b824e1c4873bf952843341290f5fd08cda4bb1f2a1e8f47dcc9ed8ba55b88b20");
        restoreReviewedBinding(rooted, historical, "release", "contractsReleaseIdentity",
                "sha256:e3dc23d3e43325fde45a3d07e175ce79e66ead7793ebcc41a1633fb3db978044");
        restoreReviewedBinding(rooted, historical, "packages", "contractsRelease",
                "sha256:e3dc23d3e43325fde45a3d07e175ce79e66ead7793ebcc41a1633fb3db978044");
        restoreReviewedBinding(rooted, historical, "packages", "contractsFixtures",
                "sha256:9323cd0b2b4202c08d8165a99102aa6d8a52f3e718fc33647e34ba211859a60f");
        restoreReviewedBinding(rooted, historical, "gas", "oraclePackageIdentity",
                "sha256:9323cd0b2b4202c08d8165a99102aa6d8a52f3e718fc33647e34ba211859a60f");

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
