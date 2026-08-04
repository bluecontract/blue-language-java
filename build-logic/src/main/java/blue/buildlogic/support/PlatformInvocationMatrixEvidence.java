package blue.buildlogic.support;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.gradle.api.GradleException;

/** Validates and summarizes the public platform-invocation locality matrix. */
public final class PlatformInvocationMatrixEvidence {

    public static final String SCHEMA =
            "blue-language-platform-invocation-matrix/1.0";
    public static final int EXPECTED_VARIANT_COUNT = 16;

    private static final List<String> REPRESENTATIONS =
            Collections.unmodifiableList(Arrays.asList(
                    "INLINE", "PURE_REFERENCE", "PARTIAL", "FRAGMENTED"));
    private static final List<String> CACHE_MODES =
            Collections.unmodifiableList(Arrays.asList("COLD", "WARM"));
    private static final List<String> BATCH_MODES =
            Collections.unmodifiableList(Arrays.asList(
                    "UNBATCHED", "BOUNDED_BATCH"));

    private PlatformInvocationMatrixEvidence() {}

    /**
     * Requires the complete 4 x 2 x 2 matrix and returns its exact observations
     * plus deterministic physical-read and semantic-demand totals.
     *
     * @param report generated matrix report
     * @return validated evidence suitable for embedding in release evidence
     */
    public static Map<String, Object> analyze(JsonNode report) {
        require(report != null && report.isObject(),
                "Platform invocation matrix must be a JSON object");
        require(SCHEMA.equals(report.path("schema").asText()),
                "Platform invocation matrix uses an unexpected schema");
        require(report.path("variantCount").isIntegralNumber()
                        && report.path("variantCount").asInt(-1)
                        == EXPECTED_VARIANT_COUNT,
                "Platform invocation matrix must declare exactly 16 variants");

        JsonNode observations = report.path("observations");
        require(observations.isArray()
                        && observations.size() == EXPECTED_VARIANT_COUNT,
                "Platform invocation matrix must contain exactly 16 observations");

        Set<String> expectedVariants = expectedVariants();
        Set<String> actualVariants = new TreeSet<>();
        List<Map<String, Object>> normalized = new ArrayList<>();
        Totals totals = new Totals();
        String resultingRootBlueId = null;
        Long totalGas = null;

        for (JsonNode observation : observations) {
            require(observation.isObject(),
                    "Platform invocation observation must be a JSON object");
            String representation = requiredText(
                    observation, "representation");
            String cacheMode = requiredText(observation, "cacheMode");
            String batchMode = requiredText(observation, "batchMode");
            require(REPRESENTATIONS.contains(representation),
                    "Unknown platform representation: " + representation);
            require(CACHE_MODES.contains(cacheMode),
                    "Unknown platform cache mode: " + cacheMode);
            require(BATCH_MODES.contains(batchMode),
                    "Unknown platform batch mode: " + batchMode);
            String expectedVariant = representation + "/"
                    + cacheMode + "/" + batchMode;
            String variant = requiredText(observation, "variant");
            require(expectedVariant.equals(variant),
                    "Platform invocation variant dimensions do not match: "
                            + variant);
            require(actualVariants.add(variant),
                    "Duplicate platform invocation variant: " + variant);

            String status = requiredText(observation, "status");
            require("SUCCESS".equals(status),
                    "Platform invocation variant did not succeed: " + variant);
            String rootBlueId = requiredText(
                    observation, "resultingRootBlueId");
            long gas = requiredNonNegativeLong(observation, "totalGas");
            if (resultingRootBlueId == null) {
                resultingRootBlueId = rootBlueId;
                totalGas = gas;
            } else {
                require(resultingRootBlueId.equals(rootBlueId),
                        "Platform invocation resulting Root identity drift: "
                                + variant);
                require(totalGas.longValue() == gas,
                        "Platform invocation logical gas drift: " + variant);
            }

            long providerRequests = requiredPositiveLong(
                    observation, "providerRequestCount");
            long backendTrips = requiredNonNegativeLong(
                    observation, "providerBackendTrips");
            long backendBytes = requiredNonNegativeLong(
                    observation, "providerBackendBytes");
            long selectedBodyDemands = requiredNonNegativeLong(
                    observation, "selectedBodyDemandCount");
            long unselectedBodyDemands = requiredNonNegativeLong(
                    observation, "unselectedBodyDemandCount");
            long unrelatedProviderRequests = requiredNonNegativeLong(
                    observation, "unrelatedProviderRequestCount");
            long constructionDeriverCalls = requiredNonNegativeLong(
                    observation, "constructionDeriverCalls");
            require(backendTrips <= providerRequests,
                    "Platform backend trips exceed provider requests: " + variant);
            require((backendTrips == 0L) == (backendBytes == 0L),
                    "Platform backend trip and byte observations disagree: "
                            + variant);
            require(selectedBodyDemands == 1L,
                    "Platform invocation must demand the selected body once: "
                            + variant);
            require(unselectedBodyDemands == 0L,
                    "Platform invocation demanded an unselected body: " + variant);
            require(unrelatedProviderRequests == 0L,
                    "Platform invocation read unrelated provider content: " + variant);
            require(constructionDeriverCalls == 0L,
                    "Platform invocation called the construction-time deriver: "
                            + variant);

            totals.add(providerRequests, backendTrips, backendBytes,
                    selectedBodyDemands, unselectedBodyDemands,
                    unrelatedProviderRequests, constructionDeriverCalls);
            normalized.add(observation(
                    variant, representation, cacheMode, batchMode, status,
                    rootBlueId, gas, providerRequests, backendTrips,
                    backendBytes, selectedBodyDemands, unselectedBodyDemands,
                    unrelatedProviderRequests, constructionDeriverCalls));
        }
        require(expectedVariants.equals(actualVariants),
                "Platform invocation matrix is missing one or more required variants");

        Map<String, Object> semanticProjection = new TreeMap<>();
        semanticProjection.put("resultingRootBlueId", resultingRootBlueId);
        semanticProjection.put("status", "SUCCESS");
        semanticProjection.put("totalGas", totalGas);
        Map<String, Object> result = new TreeMap<>();
        result.put("conformant", true);
        result.put("observations", normalized);
        result.put("schema", SCHEMA);
        result.put("semanticProjection", semanticProjection);
        result.put("totals", totals.toMap());
        result.put("variantCount", EXPECTED_VARIANT_COUNT);
        return result;
    }

    private static Set<String> expectedVariants() {
        Set<String> variants = new TreeSet<>();
        for (String representation : REPRESENTATIONS) {
            for (String cacheMode : CACHE_MODES) {
                for (String batchMode : BATCH_MODES) {
                    variants.add(representation + "/"
                            + cacheMode + "/" + batchMode);
                }
            }
        }
        return variants;
    }

    private static Map<String, Object> observation(
            String variant,
            String representation,
            String cacheMode,
            String batchMode,
            String status,
            String resultingRootBlueId,
            long totalGas,
            long providerRequests,
            long backendTrips,
            long backendBytes,
            long selectedBodyDemands,
            long unselectedBodyDemands,
            long unrelatedProviderRequests,
            long constructionDeriverCalls) {
        Map<String, Object> value = new TreeMap<>();
        value.put("batchMode", batchMode);
        value.put("cacheMode", cacheMode);
        value.put("constructionDeriverCalls", constructionDeriverCalls);
        value.put("providerBackendBytes", backendBytes);
        value.put("providerBackendTrips", backendTrips);
        value.put("providerRequestCount", providerRequests);
        value.put("representation", representation);
        value.put("resultingRootBlueId", resultingRootBlueId);
        value.put("selectedBodyDemandCount", selectedBodyDemands);
        value.put("status", status);
        value.put("totalGas", totalGas);
        value.put("unrelatedProviderRequestCount", unrelatedProviderRequests);
        value.put("unselectedBodyDemandCount", unselectedBodyDemands);
        value.put("variant", variant);
        return value;
    }

    private static String requiredText(JsonNode node, String field) {
        JsonNode value = node.path(field);
        require(value.isTextual() && !value.asText().isEmpty(),
                "Platform invocation observation is missing " + field);
        return value.asText();
    }

    private static long requiredPositiveLong(JsonNode node, String field) {
        long value = requiredNonNegativeLong(node, field);
        require(value > 0L,
                "Platform invocation observation requires positive " + field);
        return value;
    }

    private static long requiredNonNegativeLong(JsonNode node, String field) {
        JsonNode value = node.path(field);
        require(value.isIntegralNumber() && value.canConvertToLong(),
                "Platform invocation observation has invalid " + field);
        long result = value.longValue();
        require(result >= 0L,
                "Platform invocation observation has negative " + field);
        return result;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new GradleException(message);
        }
    }

    /** Exact sums exported for the release report. */
    private static final class Totals {
        private long providerRequests;
        private long backendTrips;
        private long backendBytes;
        private long selectedBodyDemands;
        private long unselectedBodyDemands;
        private long unrelatedProviderRequests;
        private long constructionDeriverCalls;

        private void add(
                long requests,
                long trips,
                long bytes,
                long selected,
                long unselected,
                long unrelated,
                long deriverCalls) {
            providerRequests = exactAdd(providerRequests, requests);
            backendTrips = exactAdd(backendTrips, trips);
            backendBytes = exactAdd(backendBytes, bytes);
            selectedBodyDemands = exactAdd(selectedBodyDemands, selected);
            unselectedBodyDemands = exactAdd(
                    unselectedBodyDemands, unselected);
            unrelatedProviderRequests = exactAdd(
                    unrelatedProviderRequests, unrelated);
            constructionDeriverCalls = exactAdd(
                    constructionDeriverCalls, deriverCalls);
        }

        private Map<String, Object> toMap() {
            Map<String, Object> value = new TreeMap<>();
            value.put("constructionDeriverCalls", constructionDeriverCalls);
            value.put("providerBackendBytes", backendBytes);
            value.put("providerBackendTrips", backendTrips);
            value.put("providerRequestCount", providerRequests);
            value.put("selectedBodyDemandCount", selectedBodyDemands);
            value.put("unrelatedProviderRequestCount",
                    unrelatedProviderRequests);
            value.put("unselectedBodyDemandCount", unselectedBodyDemands);
            return value;
        }

        private static long exactAdd(long left, long right) {
            try {
                return Math.addExact(left, right);
            } catch (ArithmeticException overflow) {
                throw new GradleException(
                        "Platform invocation matrix totals overflowed", overflow);
            }
        }
    }
}
