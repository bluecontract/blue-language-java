package blue.language.conformance.contracts;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Executable gate for generated C-EVO fixtures and exact closure aliases. */
final class ContractEvolutionFixtureFamilyTest {

    private static final String ORDINARY_ROOT =
            "blue-contracts-closure-1.0/fixtures/evo/";
    private static final String CLOSURE_ROOT =
            "blue-contracts-closure-1.0/fixtures/closure/";
    private static final ObjectMapper YAML = new ObjectMapper(
            YAMLFactory.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .build());
    private static final List<String> GENERATED_FILES = Arrays.asList(
            "c-evo-01.yaml",
            "c-evo-02.yaml",
            "c-evo-03.yaml",
            "c-evo-04.yaml",
            "c-evo-05.yaml",
            "c-evo-06.yaml",
            "c-evo-07-checkpoint.yaml",
            "c-evo-07-initialized.yaml",
            "c-evo-07-terminated.yaml",
            "c-evo-08.yaml",
            "c-evo-09.yaml",
            "c-evo-10.yaml",
            "c-evo-14.yaml",
            "c-evo-15.yaml",
            "c-evo-16.yaml",
            "c-evo-17.yaml");
    private static final List<String> GENERATED_CLOSURE_FILES = Arrays.asList(
            "c-evo-18-missing-exact-node.yaml",
            "c-evo-19-missing-occurrence-evidence.yaml",
            "c-evo-20-canonical-demand-order.yaml",
            "c-evo-21-retry-determinism-missing-first.yaml",
            "c-evo-21-retry-determinism-missing-repeat.yaml",
            "c-evo-21-retry-determinism-resolved-first.yaml",
            "c-evo-21-retry-determinism-resolved-repeat.yaml",
            "c-evo-22-low-gas-expanded-evidence-demand.yaml",
            "c-evo-22-low-gas-expanded-evidence-expanded-low-gas.yaml",
            "c-evo-22-low-gas-expanded-evidence-expanded-low-gas-repeat.yaml",
            "c-evo-23-automatic-explicit-retry-parity-automatic-demand.yaml",
            "c-evo-23-automatic-explicit-retry-parity-automatic-resolved.yaml",
            "c-evo-23-automatic-explicit-retry-parity-explicit-resolved.yaml");

    @Test
    void shouldExecuteEveryGeneratedOrdinaryContractEvolutionFixture()
            throws IOException {
        // given
        Set<String> expected = new LinkedHashSet<>();
        for (int ordinal = 1; ordinal <= 10; ordinal++) {
            expected.add(String.format("C-EVO-%02d", ordinal));
        }
        for (int ordinal = 14; ordinal <= 17; ordinal++) {
            expected.add(String.format("C-EVO-%02d", ordinal));
        }

        // when
        Set<String> observed = new LinkedHashSet<>();
        Map<String, String> failures = new LinkedHashMap<>();
        for (String file : GENERATED_FILES) {
            JsonNode fixture = read(ORDINARY_ROOT + file);
            try {
                ContractsConformanceSuite.runFixture(fixture);
            } catch (AssertionError failure) {
                JsonNode diagnosticFixture = fixture.deepCopy();
                com.fasterxml.jackson.databind.node.ArrayNode assertions =
                        ((com.fasterxml.jackson.databind.node.ObjectNode)
                                diagnosticFixture.path("expected"))
                                .putArray("assertions");
                assertions.addObject()
                        .put("actual", "result.status")
                        .put("op", "present");
                ContractsConformanceProjection diagnostic =
                        new ContractsFixtureHarness().execute(
                                diagnosticFixture, false);
                failures.put(file,
                        failure.getMessage() + " [diagnostic="
                                + projectedValue(diagnostic,
                                        "result.diagnostic.category")
                                + ": "
                                + projectedValue(diagnostic,
                                        "result.diagnostic.message")
                                + ", handlerExecutionCount="
                                + projectedValue(diagnostic,
                                        "trace.handlerExecutionCount")
                                + ", generalizationSelected="
                                + projectedValue(diagnostic,
                                        "trace.generalizationSelected")
                                + ", document="
                                + projectedValue(diagnostic,
                                        "result.document")
                                + "]");
            }
            fixture.path("vectors").forEach(
                    vector -> observed.add(vector.asText()));
        }

        // then
        assertTrue(failures.isEmpty(), failures.toString());
        assertEquals(expected, observed);
        assertEquals(16, GENERATED_FILES.size());
    }

    @Test
    void shouldBindGraphEvolutionVectorsToExactClosureFixtures()
            throws IOException {
        // given
        Map<String, String> aliases = new LinkedHashMap<>();
        aliases.put("c-clo-12-frozen-edge-removal.yaml", "C-EVO-11");
        aliases.put("c-clo-11-split-into-two-cycles.yaml", "C-EVO-12");
        aliases.put("c-clo-02-dynamic-finite-cycle.yaml", "C-EVO-13");

        // when
        Map<String, JsonNode> fixtures = new LinkedHashMap<>();
        for (String file : aliases.keySet()) {
            fixtures.put(file, read(CLOSURE_ROOT + file));
        }

        // then
        for (Map.Entry<String, String> alias : aliases.entrySet()) {
            assertTrue(
                    containsText(
                            fixtures.get(alias.getKey()).path("vectors"),
                            alias.getValue()),
                    alias.getKey());
        }
    }

    @Test
    void shouldCompareProtectedMarkerByCanonicalWitnessAndDirectIdentity()
            throws IOException {
        // given
        JsonNode fixture = read(ORDINARY_ROOT + "c-evo-08.yaml");
        JsonNode assertions = fixture.path("expected").path("assertions");

        // when / then
        ContractsConformanceSuite.runFixture(fixture);
        assertTrue(containsProjectionAssertion(
                assertions,
                "result.document.contracts.initialized.document",
                "input.root.contracts.initialized.document"));
        assertTrue(containsProjectionAssertion(
                assertions,
                "result.document.contracts.initialized.directBlueId",
                "input.root.contracts.initialized.directBlueId"));
        assertTrue(!containsProjectionAssertion(
                assertions,
                "result.document.contracts.initialized",
                "input.root.contracts.initialized"),
                "Authored inline and admitted pure-reference witnesses must not "
                        + "be compared as physical marker representations");
    }

    @Test
    void shouldInventoryEveryGeneratedTypedDemandAndRetryFixture()
            throws IOException {
        Set<String> expected = new LinkedHashSet<>();
        for (int ordinal = 18; ordinal <= 23; ordinal++) {
            expected.add(String.format("C-EVO-%02d", ordinal));
        }

        Set<String> observed = new LinkedHashSet<>();
        int needsResources = 0;
        int complete = 0;
        for (String file : GENERATED_CLOSURE_FILES) {
            JsonNode fixture = read(CLOSURE_ROOT + file);
            fixture.path("vectors").forEach(
                    vector -> observed.add(vector.asText()));
            JsonNode expectedAttempt = fixture.path("expected");
            if ("NeedsResources".equals(
                    expectedAttempt.path("attemptOutcome").asText())) {
                needsResources++;
                assertTrue(expectedAttempt.path("resourceDemands").isArray(),
                        file);
                assertTrue(expectedAttempt.path("resourceDemands").size() > 0,
                        file);
                assertTrue(expectedAttempt.path("requiredBlueIds").isArray(),
                        file);
            } else {
                complete++;
                assertEquals("Complete",
                        expectedAttempt.path("attemptOutcome").asText(), file);
            }
        }

        assertEquals(expected, observed);
        assertEquals(7, needsResources);
        assertEquals(6, complete);
        assertEquals(13, GENERATED_CLOSURE_FILES.size());
    }

    @Test
    void shouldUseDistinctRegisteredOperationAdapter()
            throws IOException {
        // given
        JsonNode selfRemoving = read(ORDINARY_ROOT + "c-evo-02.yaml");
        JsonNode creatingUpdate = read(ORDINARY_ROOT + "c-evo-03.yaml");

        // when
        JsonNode operation = selfRemoving.path("input").path("root")
                .path("contracts").path("operation");
        JsonNode addedOperation = creatingUpdate.path("input").path("root")
                .path("contracts").path("creator").path("result")
                .path("patches").path(0).path("val");

        // then
        assertEquals(MockTypeBlueIds.MOCK_OPERATION,
                operation.path("type").path("blueId").asText());
        assertNotEquals(MockTypeBlueIds.MOCK_HANDLER,
                operation.path("type").path("blueId").asText());
        assertEquals(
                selfRemoving.path("input").path("event")
                        .path("id").asText(),
                operation.path("operationId").asText());
        assertEquals(MockTypeBlueIds.MOCK_OPERATION,
                addedOperation.path("type").path("blueId").asText());
        assertEquals(
                creatingUpdate.path("input").path("event")
                        .path("id").asText(),
                addedOperation.path("operationId").asText());
    }

    private static JsonNode read(String resource) throws IOException {
        InputStream input = ContractEvolutionFixtureFamilyTest.class
                .getClassLoader().getResourceAsStream(resource);
        assertNotNull(input, resource);
        try (InputStream closeable = input) {
            return YAML.readTree(closeable);
        }
    }

    private static boolean containsText(
            JsonNode values,
            String expected) {
        for (JsonNode value : values) {
            if (expected.equals(value.asText())) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsProjectionAssertion(
            JsonNode assertions,
            String actual,
            String expectedProjection) {
        for (JsonNode assertion : assertions) {
            if (actual.equals(assertion.path("actual").asText())
                    && "equalsProjection".equals(
                            assertion.path("op").asText())
                    && expectedProjection.equals(
                            assertion.path("expectedProjection").asText())) {
                return true;
            }
        }
        return false;
    }

    private static Object projectedValue(
            ContractsConformanceProjection projection,
            String path) {
        ContractsConformanceProjection.Presence presence =
                projection.project(path);
        return presence.isPresent() ? presence.getValue() : "<absent>";
    }
}
