package blue.language.conformance.contracts;

import blue.language.model.Node;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins legacy soundness vectors to released type-generalization semantics. */
final class ContractsSoundnessGeneralizationFixtureTest {

    private static final String ROOT =
            "blue-contracts-closure-1.0/fixtures/snd/";

    @Test
    void shouldSelectNearestValidAncestorThroughReleasedConformance()
            throws IOException {
        ContractsConformanceProjection projection = execute("c-snd-02.yaml");

        assertEquals("Parent", value(
                projection, "trace.generalizationSelected"));
        assertEquals(Arrays.asList("Specific", "Parent"), value(
                projection, "trace.generalizationTestOrder"));
    }

    @Test
    void shouldEmitAndRecognizeGeneratedTypeUpdate()
            throws IOException {
        ContractsConformanceProjection projection = execute("c-snd-03.yaml");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> updates =
                (List<Map<String, Object>>) value(
                        projection, "trace.documentUpdates");
        assertTrue(updates.stream().anyMatch(
                update -> "/type".equals(update.get("path"))));
        assertEquals(Boolean.TRUE, value(
                projection, "trace.reRecognitionAfterGeneralization"));
    }

    @Test
    void shouldEncodeFixtureValidityAsExactProviderTypeContent() {
        ObjectNode root = ContractsFixtureHarnessDataSupport.YAML
                .createObjectNode();
        root.put("value", 0);
        ObjectNode runtime = ContractsFixtureHarnessDataSupport.YAML
                .createObjectNode();
        runtime.putArray("generalizationCandidates")
                .add("Specific")
                .add("Parent")
                .add("Any");
        runtime.put("validCandidate", "Parent");

        ContractsFixtureHarnessDataSupport.FixtureGeneralization definition =
                ContractsFixtureHarnessDataSupport.FixtureGeneralization
                        .create(root, runtime);
        Node specific = candidate(definition, "Specific");
        Node parent = candidate(definition, "Parent");

        assertEquals(BigInteger.ZERO, specific.getValue());
        assertNull(parent.getRawValue());
    }

    private static Node candidate(
            ContractsFixtureHarnessDataSupport.FixtureGeneralization definition,
            String name) {
        String blueId = definition.blueIdByCandidate.get(name);
        Node candidate = definition.nodesByBlueId.get(blueId);
        assertNotNull(candidate, name);
        return candidate;
    }

    private static ContractsConformanceProjection execute(String name)
            throws IOException {
        JsonNode fixture = read(ROOT + name);
        ContractsFixtureHarness harness = new ContractsFixtureHarness();
        ContractsFixtureHarnessDataSupport.PreparedInput prepared =
                harness.prepare(
                        fixture.path("input"),
                        null,
                        null,
                        true,
                        ContractsFixtureInputPreparer.hasVector(
                                fixture, "C-LOOP-01"));
        ContractsFixtureHarnessDataSupport.ProcessExecution execution =
                harness.runProcess(prepared);
        ContractsConformanceProjection projection =
                harness.projectProcess(prepared, execution);
        assertEquals(
                "success",
                value(projection, "result.status"),
                diagnostic(projection));
        ContractsConformanceSuite.runFixture(fixture);
        return projection;
    }

    private static String diagnostic(
            ContractsConformanceProjection projection) {
        ContractsConformanceProjection.Presence projected =
                projection.project("result.diagnostic");
        return projected.isPresent()
                ? String.valueOf(projected.getValue())
                : "no processor diagnostic";
    }

    private static JsonNode read(String resource) throws IOException {
        InputStream input = ContractsSoundnessGeneralizationFixtureTest.class
                .getClassLoader().getResourceAsStream(resource);
        assertNotNull(input, resource);
        try (InputStream closeable = input) {
            return ContractsFixtureHarnessDataSupport.YAML.readTree(closeable);
        }
    }

    private static Object value(
            ContractsConformanceProjection projection,
            String path) {
        ContractsConformanceProjection.Presence projected =
                projection.project(path);
        assertTrue(projected.isPresent(), path);
        return projected.getValue();
    }
}
