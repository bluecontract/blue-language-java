package blue.language.conformance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

final class SemanticBaselineSupportTest {

    private static final String PLATFORM_MATRIX_METHOD =
            "shouldVerifyPublicPlatformCommitMatrixPreservesSemanticsAndStrictLocality";

    @Test
    void shouldKeepNewPlatformProofOutsideFrozenBaselineTestProjection() {
        // given
        ObjectNode evidence = SemanticBaselineSupport.JSON.createObjectNode();
        ObjectNode locality = evidence.putObject(
                "representationAndLocality");
        ArrayNode required = locality.putArray("requiredTestCases");
        for (String method : SemanticBaselineSupport
                .BASELINE_LOCALITY_TEST_METHODS) {
            addPassingTest(required, method);
        }
        addPassingTest(required, PLATFORM_MATRIX_METHOD);

        // when
        JsonNode baselineTests =
                SemanticBaselineSupport.localityRequiredTests(evidence);

        // then
        assertEquals(
                SemanticBaselineSupport.BASELINE_LOCALITY_TEST_METHODS.size(),
                baselineTests.size());
        for (JsonNode baselineTest : baselineTests) {
            assertFalse(PLATFORM_MATRIX_METHOD.equals(
                    baselineTest.path("testMethod").asText()));
        }
    }

    private static void addPassingTest(
            ArrayNode required,
            String method) {
        ObjectNode test = required.addObject();
        test.put("testMethod", method);
        test.put("executed", true);
        test.put("passed", true);
        test.putArray("records");
    }
}
