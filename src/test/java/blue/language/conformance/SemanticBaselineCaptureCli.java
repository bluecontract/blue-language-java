package blue.language.conformance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Captures the exact pre-modernization semantic characterization consumed by
 * {@link SemanticBaselineVerifierCli}.
 *
 * <p>The capture is intentionally explicit: it preserves the immutable
 * pre-modernization public-API section already present in the tracked
 * baseline while refreshing all Contracts gas-fixture expected trees,
 * exported locality payloads, the locality proof source/test inventory, and
 * release artifact/source identities. Re-running capture is therefore a
 * deliberate semantic-baseline update, not an API-baseline replacement and
 * not part of ordinary verification.</p>
 */
public final class SemanticBaselineCaptureCli {

    private SemanticBaselineCaptureCli() {
    }

    /**
     * Captures one semantic baseline.
     *
     * @param args release-conformance JSON, fragmented-evidence JSON,
     *             generated API inventory (validation only), Contracts
     *             fixture root, existing baseline/output, and one or more
     *             locality JSON files/directories
     * @throws Exception when supplied evidence is incomplete or inconsistent
     */
    public static void main(String[] args) throws Exception {
        if (args.length < 6) {
            throw new IllegalArgumentException(
                    "Expected conformance, evidence, API, Contracts fixture "
                            + "root, output, and locality evidence paths");
        }
        Path conformancePath = Paths.get(args[0]);
        Path evidencePath = Paths.get(args[1]);
        Path apiPath = Paths.get(args[2]);
        Path fixtureRoot = Paths.get(args[3]);
        Path outputPath = Paths.get(args[4]);
        List<Path> localityInputs =
                SemanticBaselineSupport.localityArguments(args, 5);

        JsonNode existingBaseline =
                SemanticBaselineSupport.readJson(outputPath);
        JsonNode conformance =
                SemanticBaselineSupport.readJson(conformancePath);
        JsonNode evidence = SemanticBaselineSupport.readJson(evidencePath);
        JsonNode api = SemanticBaselineSupport.readJson(apiPath);
        SemanticBaselineSupport.requireEquals(
                "release conformance schema",
                SemanticBaselineSupport.RELEASE_CONFORMANCE_SCHEMA,
                SemanticBaselineSupport.text(conformance, "/schema"));
        SemanticBaselineSupport.requireEquals(
                "fragmented release-evidence schema",
                SemanticBaselineSupport.RELEASE_EVIDENCE_SCHEMA,
                SemanticBaselineSupport.text(evidence, "/schema"));
        SemanticBaselineSupport.requireEquals(
                "public API inventory schema",
                SemanticBaselineSupport.API_INVENTORY_SCHEMA,
                SemanticBaselineSupport.text(api, "/schema"));
        validateReleaseEvidence(conformance, evidence);

        JsonNode gasFixtures = SemanticBaselineSupport.gasFixtureOracle(
                conformance,
                fixtureRoot);
        JsonNode sourceFiles =
                SemanticBaselineSupport.localitySourceFiles(evidence);
        JsonNode requiredTests =
                SemanticBaselineSupport.localityRequiredTests(evidence);
        JsonNode localityPayloads =
                SemanticBaselineSupport.localityPayloads(localityInputs);

        ObjectNode baseline = SemanticBaselineSupport.JSON.createObjectNode();
        baseline.put("schema", SemanticBaselineSupport.BASELINE_SCHEMA);
        baseline.set(
                "source",
                SemanticBaselineSupport.sourceIdentities(evidence));
        baseline.set(
                "specifications",
                SemanticBaselineSupport.required(
                        conformance,
                        "/specifications").deepCopy());

        ObjectNode release = baseline.putObject("release");
        release.put(
                "packageIdentity",
                SemanticBaselineSupport.text(
                        conformance,
                        "/release/packageIdentity"));
        release.put(
                "contractsReleaseIdentity",
                SemanticBaselineSupport.text(
                        conformance,
                        "/release/contractsReleaseIdentity"));
        baseline.set(
                "packages",
                SemanticBaselineSupport.required(
                        conformance,
                        "/packages").deepCopy());

        ObjectNode allTests = baseline.putObject("tests")
                .putObject("all");
        int testCount = SemanticBaselineSupport.intValue(
                evidence,
                "/allTests/tests");
        allTests.put("minimumTests", testCount);
        allTests.put("tests", testCount);
        allTests.put(
                "passed",
                SemanticBaselineSupport.intValue(
                        evidence,
                        "/allTests/passed"));
        allTests.put("failed", 0);
        allTests.put("skipped", 0);

        ObjectNode gas = baseline.putObject("gas");
        gas.put("fixtureCount", SemanticBaselineSupport.GAS_FIXTURE_COUNT);
        gas.put(
                "oraclePackageIdentity",
                SemanticBaselineSupport.text(
                        conformance,
                        "/packages/contractsFixtures"));
        gas.set("fixtures", gasFixtures);

        ObjectNode locality = baseline.putObject("locality");
        locality.put("requiredAssertionCount", requiredTests.size());
        locality.set("sourceFiles", sourceFiles);
        locality.set("requiredTests", requiredTests);
        locality.set("payloads", localityPayloads);

        baseline.set(
                "publicApi",
                preservedPublicApi(existingBaseline));
        baseline.set(
                "artifacts",
                SemanticBaselineSupport.artifactIdentities(evidence));

        SemanticBaselineSupport.writeJson(outputPath, baseline);
    }

    /**
     * Returns the immutable public-API characterization from an existing
     * semantic baseline.
     *
     * <p>API evolution is approved separately by the migration ledger. A
     * semantic refresh must therefore fail closed when the tracked baseline
     * does not contain a valid API characterization instead of silently
     * rebasing that characterization to the current distribution.</p>
     */
    static JsonNode preservedPublicApi(JsonNode existingBaseline) {
        SemanticBaselineSupport.requireEquals(
                "existing semantic baseline schema",
                SemanticBaselineSupport.BASELINE_SCHEMA,
                SemanticBaselineSupport.text(existingBaseline, "/schema"));
        JsonNode publicApi = SemanticBaselineSupport.required(
                existingBaseline,
                "/publicApi");
        SemanticBaselineSupport.requireIdentity(
                SemanticBaselineSupport.text(
                        publicApi,
                        "/inventorySha256"),
                "existing semantic baseline public API inventory");
        JsonNode inventory = SemanticBaselineSupport.required(
                publicApi,
                "/inventory");
        SemanticBaselineSupport.requireEquals(
                "existing semantic baseline public API schema",
                SemanticBaselineSupport.API_INVENTORY_SCHEMA,
                SemanticBaselineSupport.text(inventory, "/schema"));
        JsonNode classes = SemanticBaselineSupport.required(
                inventory,
                "/classes");
        if (!classes.isArray() || classes.size() == 0) {
            throw new IllegalStateException(
                    "Existing semantic baseline public API has no classes");
        }
        return publicApi.deepCopy();
    }

    private static void validateReleaseEvidence(
            JsonNode conformance,
            JsonNode evidence) {
        SemanticBaselineSupport.requireEquals(
                "clean characterization working tree",
                true,
                SemanticBaselineSupport.required(
                        evidence,
                        "/source/workingTreeClean").asBoolean());
        SemanticBaselineSupport.requireEquals(
                "clean characterization modified path count",
                0,
                SemanticBaselineSupport.intValue(
                        evidence,
                        "/source/modifiedPathCount"));
        SemanticBaselineSupport.requireEquals(
                "verified characterization clean build",
                true,
                SemanticBaselineSupport.required(
                        evidence,
                        "/execution/cleanBuild/verified").asBoolean());
        SemanticBaselineSupport.requireEquals(
                "release fixture total",
                SemanticBaselineSupport.RELEASE_FIXTURE_COUNT,
                SemanticBaselineSupport.intValue(
                        conformance,
                        "/summary/total"));
        SemanticBaselineSupport.requireEquals(
                "release fixture passes",
                SemanticBaselineSupport.RELEASE_FIXTURE_COUNT,
                SemanticBaselineSupport.intValue(
                        conformance,
                        "/summary/passed"));
        SemanticBaselineSupport.requireEquals(
                "release fixture failures",
                0,
                SemanticBaselineSupport.intValue(
                        conformance,
                        "/summary/failed"));
        SemanticBaselineSupport.requireEquals(
                "release fixture skips",
                0,
                SemanticBaselineSupport.intValue(
                        conformance,
                        "/summary/skipped"));

        int tests = SemanticBaselineSupport.intValue(
                evidence,
                "/allTests/tests");
        SemanticBaselineSupport.requireEquals(
                "all test passes",
                tests,
                SemanticBaselineSupport.intValue(
                        evidence,
                        "/allTests/passed"));
        SemanticBaselineSupport.requireEquals(
                "all test failures",
                0,
                SemanticBaselineSupport.intValue(
                        evidence,
                        "/allTests/failed"));
        SemanticBaselineSupport.requireEquals(
                "all test skips",
                0,
                SemanticBaselineSupport.intValue(
                        evidence,
                        "/allTests/skipped"));
        SemanticBaselineSupport.requireEquals(
                "focused locality failures",
                0,
                SemanticBaselineSupport.intValue(
                        evidence,
                        "/focusedVerification/failed"));
        SemanticBaselineSupport.requireEquals(
                "focused locality skips",
                0,
                SemanticBaselineSupport.intValue(
                        evidence,
                        "/focusedVerification/skipped"));
    }
}
