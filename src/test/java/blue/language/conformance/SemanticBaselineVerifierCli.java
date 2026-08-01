package blue.language.conformance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Verifies that a modernization candidate still satisfies the exact semantic
 * characterization captured before structural refactoring.
 *
 * <p>Verification compares exact API, gas-fixture, and locality behavior.
 * Source and artifact identities remain immutable provenance for the clean
 * characterization commit: later refactors necessarily produce different
 * bytes, so current identities are validated and reported without being
 * mistaken for semantic equality constraints.</p>
 */
public final class SemanticBaselineVerifierCli {

    private static final String COMPATIBILITY_METHOD =
            "calculateSemanticBlueId";
    private static final List<String> FORBIDDEN_PRIMARY_DOC_PHRASES =
            Arrays.asList(
                    "semantic blueid",
                    "structural blueid",
                    "blue document is a tree",
                    "canonical content: minimized content",
                    "calculateSemanticBlueId");

    private SemanticBaselineVerifierCli() {
    }

    /**
     * Verifies the tracked baseline against current generated evidence.
     *
     * @param args baseline JSON, release-conformance JSON, fragmented-evidence
     *             JSON, generated API inventory, Contracts fixture root,
     *             output report, and locality JSON files/directories
     * @throws Exception when an invariant is missing or changed
     */
    public static void main(String[] args) throws Exception {
        if (args.length < 7) {
            throw new IllegalArgumentException(
                    "Expected baseline, conformance, evidence, API, Contracts "
                            + "fixture root, output, and locality evidence paths");
        }
        Path baselinePath = Paths.get(args[0]);
        Path conformancePath = Paths.get(args[1]);
        Path evidencePath = Paths.get(args[2]);
        Path apiPath = Paths.get(args[3]);
        Path fixtureRoot = Paths.get(args[4]);
        Path outputPath = Paths.get(args[5]);
        List<Path> localityInputs =
                SemanticBaselineSupport.localityArguments(args, 6);

        JsonNode baseline = SemanticBaselineSupport.readJson(baselinePath);
        JsonNode conformance =
                SemanticBaselineSupport.readJson(conformancePath);
        JsonNode evidence = SemanticBaselineSupport.readJson(evidencePath);
        JsonNode api = SemanticBaselineSupport.readJson(apiPath);

        SemanticBaselineSupport.requireEquals(
                "baseline schema",
                SemanticBaselineSupport.BASELINE_SCHEMA,
                SemanticBaselineSupport.text(baseline, "/schema"));
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
        verifySpecificationBindings(baseline, conformance);
        verifyPackageBindings(baseline, conformance);
        verifyFixtureExecution(baseline, conformance, evidence);
        verifyGasFixtureOracle(baseline, conformance, fixtureRoot);
        int localityPayloadCount = verifyLocalityEvidence(
                baseline,
                evidence,
                localityInputs);
        verifyApiInventory(baseline, apiPath, api);
        verifyRecordedProvenance(baseline);
        ObjectNode currentEvidence = currentEvidence(evidence);
        verifySourceTerminologyAndIdentityPath();

        ObjectNode report = SemanticBaselineSupport.JSON.createObjectNode();
        report.put(
                "schema",
                SemanticBaselineSupport.VERIFICATION_SCHEMA);
        report.put("verified", true);
        report.put("baseline", baselinePath.toString());
        report.put(
                "characterizationCommit",
                SemanticBaselineSupport.text(
                        baseline,
                        "/source/commit"));
        report.put(
                "apiInventorySha256",
                SemanticBaselineSupport.sha256(apiPath));
        report.put(
                "languageFixtures",
                SemanticBaselineSupport.LANGUAGE_FIXTURE_COUNT);
        report.put(
                "contractsFixtures",
                SemanticBaselineSupport.CONTRACTS_FIXTURE_COUNT);
        report.put(
                "gasFixtures",
                SemanticBaselineSupport.GAS_FIXTURE_COUNT);
        report.put(
                "localityAssertions",
                SemanticBaselineSupport.intValue(
                        baseline,
                        "/locality/requiredAssertionCount"));
        report.put("localityPayloads", localityPayloadCount);
        report.set(
                "characterizationSource",
                SemanticBaselineSupport.required(
                        baseline,
                        "/source").deepCopy());
        report.set(
                "characterizationArtifacts",
                SemanticBaselineSupport.required(
                        baseline,
                        "/artifacts").deepCopy());
        report.set("currentEvidence", currentEvidence);
        SemanticBaselineSupport.writeJson(outputPath, report);
    }

    private static void verifySpecificationBindings(
            JsonNode baseline,
            JsonNode conformance) throws IOException {
        Path language = Paths.get(
                "src/main/resources/specifications/"
                        + "blue-language-specification-1.0.md");
        Path languageMirror = Paths.get(
                "src/test/resources/language/1.0/spec.md");
        Path contracts = Paths.get(
                "src/main/resources/specifications/"
                        + "blue-contracts-and-processor-specification-1.0.md");
        Path contractsMirror = Paths.get(
                "src/test/resources/contract/1.0/spec.md");

        SemanticBaselineSupport.requireEquals(
                "Language specification mirror",
                Files.readAllBytes(language),
                Files.readAllBytes(languageMirror));
        SemanticBaselineSupport.requireEquals(
                "Contracts specification mirror",
                Files.readAllBytes(contracts),
                Files.readAllBytes(contractsMirror));
        SemanticBaselineSupport.requireEquals(
                "Language specification SHA-256",
                SemanticBaselineSupport.text(
                        baseline,
                        "/specifications/languageSha256"),
                SemanticBaselineSupport.sha256Digest(language));
        SemanticBaselineSupport.requireEquals(
                "Contracts specification SHA-256",
                SemanticBaselineSupport.text(
                        baseline,
                        "/specifications/contractsSha256"),
                SemanticBaselineSupport.sha256Digest(contracts));
        SemanticBaselineSupport.requireEquals(
                "release specification identities",
                SemanticBaselineSupport.required(
                        baseline,
                        "/specifications"),
                SemanticBaselineSupport.required(
                        conformance,
                        "/specifications"));
    }

    private static void verifyPackageBindings(
            JsonNode baseline,
            JsonNode conformance) {
        SemanticBaselineSupport.requireEquals(
                "release package identities",
                SemanticBaselineSupport.required(baseline, "/packages"),
                SemanticBaselineSupport.required(conformance, "/packages"));
        SemanticBaselineSupport.requireEquals(
                "release package identity",
                SemanticBaselineSupport.text(
                        baseline,
                        "/release/packageIdentity"),
                SemanticBaselineSupport.text(
                        conformance,
                        "/release/packageIdentity"));
    }

    private static void verifyFixtureExecution(
            JsonNode baseline,
            JsonNode conformance,
            JsonNode evidence) {
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
        SemanticBaselineSupport.requireEquals(
                "Language fixture total",
                SemanticBaselineSupport.LANGUAGE_FIXTURE_COUNT,
                countFixtures(conformance, "language", null));
        SemanticBaselineSupport.requireEquals(
                "Contracts fixture total",
                SemanticBaselineSupport.CONTRACTS_FIXTURE_COUNT,
                countFixtures(conformance, "contracts", null));
        SemanticBaselineSupport.requireEquals(
                "Contracts gas fixture total",
                SemanticBaselineSupport.intValue(
                        baseline,
                        "/gas/fixtureCount"),
                countFixtures(
                        conformance,
                        "contracts",
                        "gas-fixture"));
        SemanticBaselineSupport.requireEquals(
                "gas oracle fixture package",
                SemanticBaselineSupport.text(
                        baseline,
                        "/gas/oraclePackageIdentity"),
                SemanticBaselineSupport.text(
                        conformance,
                        "/packages/contractsFixtures"));

        int currentTests = SemanticBaselineSupport.intValue(
                evidence,
                "/allTests/tests");
        SemanticBaselineSupport.requireEquals(
                "all test passes",
                currentTests,
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
        if (currentTests < SemanticBaselineSupport.intValue(
                baseline,
                "/tests/all/minimumTests")) {
            throw new IllegalStateException(
                    "The current test inventory is smaller than the baseline");
        }
    }

    private static int countFixtures(
            JsonNode conformance,
            String suite,
            String role) {
        int count = 0;
        for (JsonNode fixture : SemanticBaselineSupport.required(
                conformance,
                "/fixtures")) {
            if (suite.equals(fixture.path("suite").asText())
                    && (role == null
                    || role.equals(fixture.path("role").asText()))
                    && "PASS".equals(fixture.path("status").asText())) {
                count++;
            }
        }
        return count;
    }

    private static void verifyGasFixtureOracle(
            JsonNode baseline,
            JsonNode conformance,
            Path fixtureRoot) throws IOException {
        JsonNode expected = SemanticBaselineSupport.required(
                baseline,
                "/gas/fixtures");
        JsonNode actual = SemanticBaselineSupport.gasFixtureOracle(
                conformance,
                fixtureRoot);
        SemanticBaselineSupport.requireEquals(
                "exact Contracts gas-fixture oracle",
                expected,
                actual);
    }

    private static int verifyLocalityEvidence(
            JsonNode baseline,
            JsonNode evidence,
            List<Path> localityInputs) throws IOException {
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

        JsonNode sourceFiles =
                SemanticBaselineSupport.localitySourceFiles(evidence);
        JsonNode requiredTests =
                SemanticBaselineSupport.localityRequiredTests(evidence);
        JsonNode payloads =
                SemanticBaselineSupport.localityPayloads(localityInputs);
        validateRecordedLocalitySources(
                SemanticBaselineSupport.required(
                        baseline,
                        "/locality/sourceFiles"));
        validateRecordedLocalitySources(sourceFiles);
        SemanticBaselineSupport.requireEquals(
                "required locality tests",
                SemanticBaselineSupport.required(
                        baseline,
                        "/locality/requiredTests"),
                requiredTests);
        SemanticBaselineSupport.requireEquals(
                "exact locality evidence payloads",
                SemanticBaselineSupport.required(
                        baseline,
                        "/locality/payloads"),
                payloads);
        SemanticBaselineSupport.requireEquals(
                "required locality assertion count",
                SemanticBaselineSupport.intValue(
                        baseline,
                        "/locality/requiredAssertionCount"),
                requiredTests.size());
        return payloads.size();
    }

    private static void verifyApiInventory(
            JsonNode baseline,
            Path apiPath,
            JsonNode api) throws IOException {
        SemanticBaselineSupport.requireEquals(
                "public API inventory SHA-256",
                SemanticBaselineSupport.text(
                        baseline,
                        "/publicApi/inventorySha256"),
                SemanticBaselineSupport.sha256(apiPath));
        SemanticBaselineSupport.requireEquals(
                "exact public API inventory",
                SemanticBaselineSupport.required(
                        baseline,
                        "/publicApi/inventory"),
                api);
    }

    private static void verifyRecordedProvenance(JsonNode baseline) {
        JsonNode source = SemanticBaselineSupport.required(
                baseline,
                "/source");
        SemanticBaselineSupport.requireSourceRevision(
                SemanticBaselineSupport.text(source, "/commit"),
                "characterization source commit");
        SemanticBaselineSupport.requireIdentity(
                SemanticBaselineSupport.text(
                        source,
                        "/sourceInputIdentity"),
                "characterization source input identity");
        JsonNode artifacts = SemanticBaselineSupport.required(
                baseline,
                "/artifacts");
        for (String key : SemanticBaselineSupport.ARTIFACT_KEYS) {
            SemanticBaselineSupport.requireIdentity(
                    SemanticBaselineSupport.text(
                            artifacts,
                            "/" + key),
                    "characterization artifact " + key);
        }
    }

    private static ObjectNode currentEvidence(JsonNode evidence) {
        ObjectNode current = SemanticBaselineSupport.JSON.createObjectNode();
        current.set(
                "source",
                SemanticBaselineSupport.sourceIdentities(evidence));
        current.set(
                "artifacts",
                SemanticBaselineSupport.artifactIdentities(evidence));
        return current;
    }

    private static void validateRecordedLocalitySources(JsonNode sourceFiles) {
        if (!sourceFiles.isArray() || sourceFiles.size() == 0) {
            throw new IllegalStateException(
                    "Locality source provenance must be a non-empty array");
        }
        for (JsonNode sourceFile : sourceFiles) {
            String path = sourceFile.path("path").asText();
            if (path.isEmpty()) {
                throw new IllegalStateException(
                        "Locality source provenance has no path");
            }
            SemanticBaselineSupport.requireIdentity(
                    sourceFile.path("identity").asText(),
                    "locality source " + path);
        }
    }

    private static void verifySourceTerminologyAndIdentityPath()
            throws IOException {
        Path sourceRoot = Paths.get("src/main/java");
        int compatibilityDeclarations = 0;
        try (Stream<Path> paths = Files.walk(sourceRoot)) {
            for (Path path : (Iterable<Path>) paths
                    .filter(candidate -> candidate.toString()
                            .endsWith(".java"))
                    ::iterator) {
                List<String> lines = Files.readAllLines(
                        path,
                        StandardCharsets.UTF_8);
                for (String line : lines) {
                    if (!line.contains(COMPATIBILITY_METHOD)) {
                        continue;
                    }
                    if (!path.endsWith("Blue.java")
                            || !line.trim().startsWith("public String ")) {
                        throw new IllegalStateException(
                                "Production use of compatibility identity API: "
                                        + path + ": " + line.trim());
                    }
                    compatibilityDeclarations++;
                }
            }
        }
        if (compatibilityDeclarations > 2) {
            throw new IllegalStateException(
                    "Unexpected compatibility identity descriptors: "
                            + compatibilityDeclarations);
        }

        String blueSource = new String(
                Files.readAllBytes(Paths.get(
                        "src/main/java/blue/language/Blue.java")),
                StandardCharsets.UTF_8);
        int sourceMethod = blueSource.indexOf(
                "calculateSourceDocumentBlueId(Node node)");
        int nextMethod = blueSource.indexOf(
                "calculateSourceDocumentBlueId(Object object)",
                sourceMethod);
        if (sourceMethod < 0
                || nextMethod < 0
                || blueSource.substring(sourceMethod, nextMethod)
                .contains("minimize(")) {
            throw new IllegalStateException(
                    "Source Document BlueId path is absent or invokes "
                            + "minimization");
        }

        try (Stream<Path> paths = Files.walk(Paths.get("docs"))) {
            for (Path path : (Iterable<Path>) paths
                    .filter(candidate -> candidate.toString()
                            .endsWith(".md"))
                    ::iterator) {
                verifyPrimaryDocument(path);
            }
        }
        verifyPrimaryDocument(Paths.get("README.md"));
    }

    private static void verifyPrimaryDocument(Path path) throws IOException {
        String source = new String(
                Files.readAllBytes(path),
                StandardCharsets.UTF_8);
        String lower = source.toLowerCase(Locale.ROOT);
        for (String phrase : FORBIDDEN_PRIMARY_DOC_PHRASES) {
            if (lower.contains(phrase.toLowerCase(Locale.ROOT))) {
                throw new IllegalStateException(
                        "Superseded terminology in " + path + ": " + phrase);
            }
        }
    }
}
