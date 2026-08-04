package blue.buildlogic.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;
import org.gradle.api.GradleException;
import org.junit.jupiter.api.Test;

final class PlatformInvocationMatrixEvidenceTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void shouldValidateAndSummarizeCompletePublicPlatformMatrix() {
        // given
        JsonNode matrix = completeMatrix();

        // when
        Map<String, Object> evidence =
                PlatformInvocationMatrixEvidence.analyze(matrix);

        // then
        assertEquals(true, evidence.get("conformant"));
        assertEquals(16, evidence.get("variantCount"));
        @SuppressWarnings("unchecked")
        Map<String, Object> totals =
                (Map<String, Object>) evidence.get("totals");
        assertEquals(32L, totals.get("providerRequestCount"));
        assertEquals(16L, totals.get("selectedBodyDemandCount"));
        assertEquals(0L, totals.get("unselectedBodyDemandCount"));
        assertEquals(0L, totals.get("unrelatedProviderRequestCount"));
        assertEquals(0L, totals.get("constructionDeriverCalls"));
    }

    @Test
    void shouldRejectDuplicateCellInPublicPlatformMatrix() {
        // given
        ObjectNode matrix = completeMatrix();
        ArrayNode observations = (ArrayNode) matrix.path("observations");
        observations.set(15, observations.get(0).deepCopy());

        // when
        GradleException failure = assertThrows(
                GradleException.class,
                () -> PlatformInvocationMatrixEvidence.analyze(matrix));

        // then
        assertTrue(failure.getMessage().contains(
                "Duplicate platform invocation variant"));
    }

    @Test
    void shouldRejectConstructionDeriverCallInPublicPlatformMatrix() {
        // given
        ObjectNode matrix = completeMatrix();
        ((ObjectNode) matrix.path("observations").get(0))
                .put("constructionDeriverCalls", 1L);

        // when
        GradleException failure = assertThrows(
                GradleException.class,
                () -> PlatformInvocationMatrixEvidence.analyze(matrix));

        // then
        assertTrue(failure.getMessage().contains(
                "called the construction-time deriver"));
    }

    private static ObjectNode completeMatrix() {
        ObjectNode matrix = JSON.createObjectNode();
        matrix.put("schema", PlatformInvocationMatrixEvidence.SCHEMA);
        matrix.put("variantCount",
                PlatformInvocationMatrixEvidence.EXPECTED_VARIANT_COUNT);
        ArrayNode observations = matrix.putArray("observations");
        for (String representation : new String[]{
                "INLINE", "PURE_REFERENCE", "PARTIAL", "FRAGMENTED"}) {
            for (String cacheMode : new String[]{"COLD", "WARM"}) {
                for (String batchMode : new String[]{
                        "UNBATCHED", "BOUNDED_BATCH"}) {
                    ObjectNode observation = observations.addObject();
                    observation.put("variant", representation + "/"
                            + cacheMode + "/" + batchMode);
                    observation.put("representation", representation);
                    observation.put("cacheMode", cacheMode);
                    observation.put("batchMode", batchMode);
                    observation.put("status", "SUCCESS");
                    observation.put("resultingRootBlueId", "root-blue-id");
                    observation.put("totalGas", 1936L);
                    observation.put("providerRequestCount", 2L);
                    boolean cold = "COLD".equals(cacheMode);
                    observation.put("providerBackendTrips", cold ? 1L : 0L);
                    observation.put("providerBackendBytes", cold ? 10L : 0L);
                    observation.put("selectedBodyDemandCount", 1L);
                    observation.put("unselectedBodyDemandCount", 0L);
                    observation.put("unrelatedProviderRequestCount", 0L);
                    observation.put("constructionDeriverCalls", 0L);
                }
            }
        }
        return matrix;
    }
}
