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
                "e91381c970859a6bafecdd99e46f5115ba033bf0534be84bbd5582531e9e347f");
        restoreReviewedBinding(rooted, historical, "release", "packageIdentity",
                "sha256:28b08b722e924c4d0da5b5352f37ad17fad5c425ce8281260689109162a68aea");
        restoreReviewedBinding(rooted, historical, "release", "contractsReleaseIdentity",
                "sha256:7379f9a6dff5a58faa5f9c329f3d22d494efde0f6bb3f0258eeedf9dff7c5ab8");
        restoreReviewedBinding(rooted, historical, "packages", "contractsRelease",
                "sha256:7379f9a6dff5a58faa5f9c329f3d22d494efde0f6bb3f0258eeedf9dff7c5ab8");
        restoreReviewedBinding(rooted, historical, "packages", "contractsFixtures",
                "sha256:ff6ed64b9e41e9dd6436fd81893fbccf3a4895051d26ad5e1a9c2820c4e444b3");
        restoreReviewedBinding(rooted, historical, "gas", "oraclePackageIdentity",
                "sha256:ff6ed64b9e41e9dd6436fd81893fbccf3a4895051d26ad5e1a9c2820c4e444b3");

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
