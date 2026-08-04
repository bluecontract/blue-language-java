package blue.buildlogic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

final class SemanticEvidenceOrchestrationTest {

    @Test
    void shouldExcludePlatformMatrixFromFrozenSemanticBaselineInputs() {
        // given
        String platformMatrix = "platform-invocation-matrix.json";

        // when
        java.util.List<String> legacyInputs =
                SemanticEvidenceOrchestration
                        .legacySemanticLocalityEvidenceFiles();

        // then
        assertEquals(Arrays.asList(
                "deep-graph-matrix.json",
                "fragmented-matrix.json",
                "root-only-event.json"), legacyInputs);
        assertFalse(legacyInputs.contains(platformMatrix));
    }
}
