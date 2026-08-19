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
 * <p>Verification compares exact gas-fixture and locality behavior. The JVM
 * API is compared with the captured inventory through a checked-in exact
 * migration ledger, so an intentional refactor does not weaken any non-API
 * semantic assertion. Source and artifact identities remain immutable
 * provenance for the clean characterization commit: later refactors
 * necessarily produce different bytes, so current identities are validated
 * and reported without being mistaken for semantic equality constraints.</p>
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
     *             output report, migration ledger, binary API baseline,
     *             binary API report, and locality JSON files/directories
     * @throws Exception when an invariant is missing or changed
     */
    public static void main(String[] args) throws Exception {
        if (args.length < 10) {
            throw new IllegalArgumentException(
                    "Expected baseline, conformance, evidence, API, Contracts "
                            + "fixture root, output, API migration ledger, "
                            + "binary baseline, binary report, and locality "
                            + "evidence paths");
        }
        Path baselinePath = Paths.get(args[0]);
        Path conformancePath = Paths.get(args[1]);
        Path evidencePath = Paths.get(args[2]);
        Path apiPath = Paths.get(args[3]);
        Path fixtureRoot = Paths.get(args[4]);
        Path outputPath = Paths.get(args[5]);
        Path apiMigrationLedgerPath = Paths.get(args[6]);
        Path binaryApiBaselinePath = Paths.get(args[7]);
        Path binaryApiReportPath = Paths.get(args[8]);
        List<Path> localityInputs =
                SemanticBaselineSupport.localityArguments(args, 9);

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
        ObjectNode apiMigrationEvidence = ApiMigrationLedgerVerifier.verify(
                baseline,
                api,
                apiPath,
                apiMigrationLedgerPath,
                binaryApiBaselinePath,
                binaryApiReportPath);
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
        report.set("apiMigration", apiMigrationEvidence);
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
        SemanticBaselineSupport.requireEquals(
                "canonical Contracts release identity",
                SemanticBaselineSupport.text(
                        baseline,
                        "/release/contractsReleaseIdentity"),
                SemanticBaselineSupport.text(
                        conformance,
                        "/release/contractsReleaseIdentity"));
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

        verifySourceDocumentIdentityPath();

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

    private static void verifySourceDocumentIdentityPath()
            throws IOException {
        String facade = readSource(
                "src/main/java/blue/language/Blue.java");
        requireIdentityMethod(
                facade,
                "public String calculateSourceDocumentBlueId(Node source)",
                "aggregate Source Document identity",
                "runtime.language().identity()",
                ".sourceDocumentBlueId(source)");

        String runtime = readSource(
                "src/main/java/blue/language/runtime/BlueLanguageRuntime.java");
        requireMethodContent(
                runtime,
                "private BlueLanguageRuntime(NodeProvider nodeProvider,",
                "Language runtime identity wiring",
                "new StandardBlueIdentity(this::canonicalize)");
        requireOrderedIdentityMethod(
                runtime,
                "public Node canonicalize(Node source)",
                "Language runtime canonicalization",
                "rawPreprocess(",
                "rawResolve(",
                "new CanonicalIdentityInputBuilder().build(");

        String runtimeServices = readSource(
                "src/main/java/blue/language/runtime/LanguageRuntimeServices.java");
        requireIdentityMethod(
                runtimeServices,
                "public String sourceDocumentBlueId(Node sourceDocument)",
                "runtime Source Document identity adapter",
                "runtime.admitted(",
                "delegate.sourceDocumentBlueId(sourceDocument)");

        String standardIdentity = readSource(
                "src/main/java/blue/language/identity/StandardBlueIdentity.java");
        requireIdentityMethod(
                standardIdentity,
                "public StandardBlueIdentity(\n"
                        + "            DirectBlueIdCalculator directCalculator,",
                "standard Source Document identity wiring",
                "new SourceDocumentBlueIdCalculator(",
                "canonicalIdentityInput",
                "directCalculator");
        requireIdentityMethod(
                standardIdentity,
                "public String sourceDocumentBlueId(Node sourceDocument)",
                "standard Source Document identity",
                "sourceCalculator.sourceDocumentBlueId(sourceDocument)");

        String sourceCalculator = readSource(
                "src/main/java/blue/language/identity/SourceDocumentBlueIdCalculator.java");
        requireIdentityMethod(
                sourceCalculator,
                "public Node canonicalIdentityInput(Node sourceDocument)",
                "Source Document canonical-input function",
                "canonicalIdentityInput.apply(Objects.requireNonNull(",
                "sourceDocument");
        requireIdentityMethod(
                sourceCalculator,
                "public String sourceDocumentBlueId(Node sourceDocument)",
                "Source Document identity calculator",
                "directCalculator.directBlueId(",
                "canonicalIdentityInput(sourceDocument)");

        String directCalculator = readSource(
                "src/main/java/blue/language/identity/DirectBlueIdCalculator.java");
        requireIdentityMethod(
                directCalculator,
                "public String directBlueId(Node node)",
                "direct BlueId calculator",
                "calculateNormalized(normalizer.normalize(node))");
    }

    private static String readSource(String path) throws IOException {
        return new String(
                Files.readAllBytes(Paths.get(path)),
                StandardCharsets.UTF_8);
    }

    private static void requireIdentityMethod(
            String source,
            String signature,
            String label,
            String... requiredContent) {
        String body = requireMethodContent(
                source,
                signature,
                label,
                requiredContent);
        requireNoMinimization(body, label);
    }

    private static void requireOrderedIdentityMethod(
            String source,
            String signature,
            String label,
            String... requiredContent) {
        String body = requireOrderedMethodContent(
                source,
                signature,
                label,
                requiredContent);
        requireNoMinimization(body, label);
    }

    private static void requireNoMinimization(
            String body,
            String label) {
        if (body.contains("minimize(")
                || body.contains("MinimizedOverlayBuilder")) {
            throw new IllegalStateException(
                    label + " invokes minimization");
        }
    }

    private static String requireMethodContent(
            String source,
            String signature,
            String label,
            String... requiredContent) {
        String body = methodBody(source, signature, label);
        for (String required : requiredContent) {
            if (!body.contains(required)) {
                throw new IllegalStateException(
                        label + " is missing required identity step: "
                                + required);
            }
        }
        return body;
    }

    private static String requireOrderedMethodContent(
            String source,
            String signature,
            String label,
            String... requiredContent) {
        String body = methodBody(source, signature, label);
        int previousEnd = 0;
        for (String required : requiredContent) {
            int occurrence = body.indexOf(required, previousEnd);
            if (occurrence < 0) {
                throw new IllegalStateException(
                        label + " is missing or reorders identity step: "
                                + required);
            }
            previousEnd = occurrence + required.length();
        }
        return body;
    }

    private static String methodBody(
            String source,
            String signature,
            String label) {
        int signatureStart = source.indexOf(signature);
        if (signatureStart < 0) {
            throw new IllegalStateException(label + " method is absent");
        }
        int bodyStart = source.indexOf('{', signatureStart);
        if (bodyStart < 0) {
            throw new IllegalStateException(label + " body is absent");
        }
        int depth = 0;
        for (int index = bodyStart; index < source.length(); index++) {
            char current = source.charAt(index);
            if (current == '{') {
                depth++;
            } else if (current == '}' && --depth == 0) {
                return source.substring(bodyStart + 1, index);
            }
        }
        throw new IllegalStateException(label + " body is not closed");
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
