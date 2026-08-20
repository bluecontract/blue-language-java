package blue.buildlogic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class FinalQualityOrchestrationTest {

    @Test
    void shouldLimitClassSizeRationalesToReviewedCohesiveBoundaries() {
        // when
        Map<String, String> rationales =
                FinalQualityOrchestration.classSizeRationales();

        // then
        assertEquals(Set.of(
                "blue-contracts-core/src/main/java/blue/language/processor/"
                        + "ManagedRootSettlementService.java",
                "blue-contracts-core/src/main/java/blue/language/processor/closure/"
                        + "ClosureExecutionSession.java",
                "blue-contracts-core/src/main/java/blue/language/processor/closure/"
                        + "ClosureFinalizationGasCharger.java",
                "blue-contracts-core/src/main/java/blue/language/processor/closure/"
                        + "ClosureIdentityService.java",
                "blue-contracts-core/src/main/java/blue/language/processor/closure/"
                        + "ClosureProcessResult.java"), rationales.keySet());
        assertTrue(rationales.values().stream()
                .allMatch(rationale -> !rationale.trim().isEmpty()));
        assertThrows(UnsupportedOperationException.class,
                () -> rationales.put("unreviewed.java", "generic exception"));
    }
}
