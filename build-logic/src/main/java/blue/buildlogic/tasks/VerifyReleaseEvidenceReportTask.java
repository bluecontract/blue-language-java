package blue.buildlogic.tasks;

import blue.buildlogic.BuildLogicConstants;
import blue.buildlogic.support.CleanBuildEvidence;
import blue.buildlogic.support.DeterministicHashing;
import blue.buildlogic.support.DeterministicJson;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/** Validates every mandatory release-evidence gate and writes a deterministic verdict. */
@CacheableTask
public abstract class VerifyReleaseEvidenceReportTask extends DefaultTask {

    public static final String SCHEMA =
            "blue-language-java-release-evidence-verification/1.0";
    private static final String RUNTIME_TRACE_SCHEMA =
            "blue-language-java-runtime-trace-evidence/1.0";
    private static final String SHA_256_PATTERN = "sha256:[0-9a-f]{64}";
    private static final ObjectMapper JSON = new ObjectMapper();

    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getEvidenceFile();

    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getMarkdownFile();

    @InputFile
    @PathSensitive(PathSensitivity.NAME_ONLY)
    public abstract RegularFileProperty getJarFile();

    @InputFile
    @PathSensitive(PathSensitivity.NAME_ONLY)
    public abstract RegularFileProperty getSourcesJarFile();

    @InputFile
    @PathSensitive(PathSensitivity.NAME_ONLY)
    public abstract RegularFileProperty getJavadocJarFile();

    @InputFile
    @PathSensitive(PathSensitivity.NAME_ONLY)
    public abstract RegularFileProperty getSourceReleaseFile();

    @Input
    public abstract Property<Integer> getMinimumTestCount();

    @Input
    public abstract Property<String> getSourceDateEpoch();

    @OutputFile
    public abstract RegularFileProperty getVerificationReportFile();

    @TaskAction
    public void verify() {
        JsonNode report = read(getEvidenceFile().get().getAsFile().toPath());
        List<String> violations = new ArrayList<>();
        check(violations,
                GenerateFragmentedProcessingReportTask.SCHEMA.equals(
                        report.path("schema").asText()),
                "unexpected-release-evidence-schema");
        check(violations,
                report.path("source").path("commit").asText()
                        .matches("(?:[0-9a-f]{40}|[0-9a-f]{64})"),
                "invalid-source-commit");
        check(violations,
                report.path("source").path("workingTreeClean").asBoolean()
                        && report.path("source").path("modifiedPathCount").asInt(-1) == 0,
                "candidate-working-tree-not-clean");
        verifyToolchain(report, violations);
        verifyTests(report, violations);
        verifyFixtures(report, violations);
        verifyPackageIdentities(report, violations);
        verifyArtifacts(report, violations);
        verifyBinaryApi(report, violations);
        verifyArchives(report, violations);
        verifyRuntimeTrace(report, violations);
        verifyHostedRuntime(report, violations);
        verifyCyclicBoundary(report, violations);
        verifyLocality(report, violations);
        verifyCleanBuild(report, violations);
        verifyReadiness(report, violations);
        check(violations,
                report.path("execution").path("benchmarkCompilation")
                        .path("successful").asBoolean(),
                "benchmark-compilation-not-proven");
        check(violations,
                report.path("summary").path("conformant").asBoolean(),
                "release-evidence-summary-not-conformant");
        Path markdown = getMarkdownFile().get().getAsFile().toPath();
        check(violations,
                Files.isRegularFile(markdown) && size(markdown) > 0L,
                "missing-final-markdown-report");

        Collections.sort(violations);
        Map<String, Object> verification = new TreeMap<>();
        verification.put("evidenceIdentity", DeterministicHashing.sha256(
                getEvidenceFile().get().getAsFile().toPath()));
        verification.put("schema", SCHEMA);
        verification.put("verified", violations.isEmpty());
        verification.put("violations", violations);
        write(getVerificationReportFile().get().getAsFile().toPath(),
                DeterministicJson.write(verification));
        if (!violations.isEmpty()) {
            throw new GradleException(
                    "Release evidence is not conformant: " + String.join(", ", violations));
        }
    }

    private void verifyToolchain(JsonNode report, List<String> violations) {
        check(violations,
                !report.path("toolchain").path("gradle").path("version").asText().isEmpty()
                        && !report.path("toolchain").path("buildJvm")
                                .path("javaVersion").asText().isEmpty()
                        && !report.path("toolchain").path("testJvm")
                                .path("runtimeVersion").asText().isEmpty()
                        && report.path("toolchain").path("bytecodeTarget").asInt() == 8,
                "missing-or-invalid-toolchain-evidence");
    }

    private void verifyTests(JsonNode report, List<String> violations) {
        JsonNode all = report.path("allTests");
        int tests = all.path("tests").asInt(-1);
        check(violations,
                tests >= getMinimumTestCount().get()
                        && all.path("passed").asInt(-1) == tests
                        && all.path("failed").asInt(-1) == 0
                        && all.path("skipped").asInt(-1) == 0,
                "incomplete-or-failing-main-test-evidence");
        JsonNode focused = report.path("focusedVerification");
        check(violations,
                focused.path("tests").asInt() > 0
                        && focused.path("failed").asInt(-1) == 0
                        && focused.path("skipped").asInt(-1) == 0,
                "incomplete-or-failing-focused-test-evidence");
    }

    private static void verifyFixtures(JsonNode report, List<String> violations) {
        Map<String, JsonNode> suites = new HashMap<>();
        for (JsonNode suite : report.path("releaseConformance").path("suites")) {
            suites.put(suite.path("name").asText(), suite);
        }
        JsonNode language = suites.get("language");
        JsonNode contracts = suites.get("contracts");
        check(violations,
                language != null
                        && language.path("tests").asInt()
                        == BuildLogicConstants.EXPECTED_LANGUAGE_FIXTURE_COUNT
                        && language.path("passed").asInt()
                        == BuildLogicConstants.EXPECTED_LANGUAGE_FIXTURE_COUNT
                        && contracts != null
                        && contracts.path("tests").asInt()
                        == BuildLogicConstants.EXPECTED_CONTRACTS_FIXTURE_COUNT
                        && contracts.path("passed").asInt()
                        == BuildLogicConstants.EXPECTED_CONTRACTS_FIXTURE_COUNT
                        && report.path("releaseConformance").path("failed").asInt(-1) == 0
                        && report.path("releaseConformance").path("skipped").asInt(-1) == 0,
                "release-fixture-counts-not-exact");
    }

    private static void verifyPackageIdentities(JsonNode report, List<String> violations) {
        boolean valid = report.path("release").path("packageIdentity")
                .asText().matches(SHA_256_PATTERN);
        for (String key : new String[] {
                "languageRegistry",
                "languageFixtures",
                "contractsRegistry",
                "contractsGas",
                "contractsFixtures"
        }) {
            valid &= report.path("packages").path(key).asText().matches(SHA_256_PATTERN);
        }
        check(violations, valid, "release-package-identities-invalid");
    }

    private void verifyArtifacts(JsonNode report, List<String> violations) {
        JsonNode artifacts = report.path("artifacts");
        if (!artifacts.isObject()) {
            violations.add("missing-artifact-evidence");
            return;
        }
        java.util.Iterator<String> artifactNames = artifacts.fieldNames();
        while (artifactNames.hasNext()) {
            String name = artifactNames.next();
            check(violations,
                    artifacts.path(name).path("identity").asText().matches(SHA_256_PATTERN),
                    "invalid-artifact-identity:" + name);
        }
        compareArtifact(report, violations, "jar", getJarFile());
        compareArtifact(report, violations, "sourcesJar", getSourcesJarFile());
        compareArtifact(report, violations, "javadocJar", getJavadocJarFile());
        compareArtifact(report, violations, "sourceRelease", getSourceReleaseFile());
    }

    private static void compareArtifact(
            JsonNode report,
            List<String> violations,
            String key,
            RegularFileProperty actual) {
        String recorded = report.path("artifacts").path(key).path("identity").asText();
        String current = DeterministicHashing.sha256(actual.get().getAsFile().toPath());
        check(violations, current.equals(recorded), "artifact-identity-mismatch:" + key);
    }

    private static void verifyBinaryApi(JsonNode report, List<String> violations) {
        JsonNode binary = report.path("binaryApi");
        boolean javaEight = javaEightVersions(binary.path("currentClassMajorVersions"));
        check(violations,
                binary.path("compatible").asBoolean()
                        && binary.path("incompatibleChanges").asInt(-1) == 0
                        && binary.path("migrationLedgerVerified").asBoolean()
                        && javaEight
                        && binary.path("additiveApi").isArray()
                        && binary.path("additiveApi").size()
                                == binary.path("additiveChanges").asInt(-1),
                "binary-api-migration-not-proven");
    }

    private static boolean javaEightVersions(JsonNode versions) {
        if (versions.isArray()) {
            if (versions.size() == 0) {
                return false;
            }
            for (JsonNode value : versions) {
                if (!value.canConvertToInt() || value.asInt() > 52) {
                    return false;
                }
            }
            return true;
        }
        String encoded = versions.asText();
        if (encoded.isEmpty()) {
            return false;
        }
        try {
            for (String value : encoded.split(",")) {
                if (Integer.parseInt(value) > 52) {
                    return false;
                }
            }
            return true;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private static void verifyArchives(JsonNode report, List<String> violations) {
        JsonNode jar = report.path("jarRepeatability");
        JsonNode source = report.path("sourceArchiveRepeatability");
        check(violations,
                jar.path("repeatable").asBoolean()
                        && jar.path("primary").path("identity").asText().matches(SHA_256_PATTERN)
                        && jar.path("primary").path("identity").asText().equals(
                                jar.path("replica").path("identity").asText()),
                "jar-repeatability-not-proven");
        check(violations,
                source.path("repeatable").asBoolean()
                        && source.path("sourcesJar").path("byteIdentical").asBoolean()
                        && source.path("sourcesJar").path("entriesIdentical").asBoolean()
                        && source.path("sourceReleaseZip").path("byteIdentical").asBoolean()
                        && source.path("sourceReleaseZip").path("entriesIdentical").asBoolean()
                        && report.path("sourceArchiveVerification").path("valid").asBoolean(),
                "source-archive-repeatability-not-proven");
    }

    private static void verifyRuntimeTrace(JsonNode report, List<String> violations) {
        JsonNode runtime = report.path("runtimeTrace");
        JsonNode summary = runtime.path("summary");
        Map<String, JsonNode> scenarios = new HashMap<>();
        for (JsonNode scenario : runtime.path("scenarios")) {
            scenarios.put(scenario.path("id").asText(), scenario);
        }
        check(violations,
                RUNTIME_TRACE_SCHEMA.equals(runtime.path("schemaVersion").asText())
                        && ":runtimeTraceEvidence".equals(runtime.path("sourceTask").asText())
                        && summary.path("executed").asInt() == 8
                        && summary.path("passed").asInt() == 8
                        && summary.path("failed").asInt(-1) == 0
                        && summary.path("skipped").asInt(-1) == 0
                        && summary.path("minimumRequiredOrderedEntries").asInt() == 516
                        && summary.path("maximumObservedOrderedEntries").asInt() == 4096
                        && summary.path("conformant").asBoolean()
                        && runtime.path("failures").isArray()
                        && runtime.path("failures").size() == 0
                        && scenarios.size() == 8
                        && scenarioInt(scenarios, "long-trace-success", "observedOrderedEntries") >= 516
                        && scenarioBool(scenarios, "long-trace-success", "exactOrderVerified")
                        && scenarioInt(scenarios, "known-entry-gas-exhaustion", "observedOrderedEntries") == 515
                        && scenarioBool(scenarios, "known-entry-gas-exhaustion", "rejectedChargeAbsent")
                        && scenarioBool(scenarios, "known-entry-gas-exhaustion", "laterWorkPrevented")
                        && scenarioBool(scenarios, "known-entry-gas-exhaustion", "exactPrefixVerified")
                        && scenarioInt(scenarios, "bounded-member-visits", "boundedMemberVisits") == 1024
                        && scenarioInt(scenarios, "bounded-member-visits", "observedOrderedEntries") == 4096
                        && scenarioBool(scenarios, "counter-catalog-overflow", "rejectedBeforeAdmission")
                        && scenarioBool(scenarios, "combined-multiple-namespaces", "combinedEntriesExceed256")
                        && scenarioBool(scenarios, "deterministic-namespace-order", "canonicalOrderVerified")
                        && scenarioBool(scenarios, "deterministic-failure-retention", "exactPrefixRetained")
                        && scenarioBool(scenarios, "transient-suspension-discard", "portableTraceDiscarded")
                        && scenarioInt(scenarios, "transient-suspension-discard", "committedEntries") == 0,
                "runtime-trace-contract-not-proven");
    }

    private static void verifyHostedRuntime(JsonNode report, List<String> violations) {
        JsonNode hosted = report.path("hostedRuntime");
        boolean valid = hosted.isObject() && hosted.size() > 0;
        if (valid) {
            java.util.Iterator<JsonNode> values = hosted.elements();
            while (values.hasNext()) {
                JsonNode value = values.next();
                valid &= value.path("executed").asBoolean()
                        && value.path("tests").asInt() > 0
                        && value.path("failed").asInt(-1) == 0
                        && value.path("skipped").asInt(-1) == 0;
            }
        }
        check(violations, valid, "hosted-runtime-evidence-incomplete");
    }

    private static void verifyCyclicBoundary(JsonNode report, List<String> violations) {
        JsonNode cyclic = report.path("cyclicEvidence");
        check(violations,
                cyclic.path("executed").asBoolean()
                        && cyclic.path("tests").asInt() > 0
                        && cyclic.path("failed").asInt(-1) == 0
                        && cyclic.path("skipped").asInt(-1) == 0,
                "cyclic-boundary-evidence-incomplete");
    }

    private static void verifyLocality(JsonNode report, List<String> violations) {
        JsonNode locality = report.path("representationAndLocality");
        boolean cases = locality.path("requiredTestCases").isArray()
                && locality.path("requiredTestCases").size() == 4;
        for (JsonNode value : locality.path("requiredTestCases")) {
            cases &= value.path("executed").asBoolean() && value.path("passed").asBoolean();
        }
        boolean measurements = locality.path("measurementEvidence").isObject()
                && locality.path("measurementEvidence").size() == 6;
        java.util.Iterator<JsonNode> values = locality.path("measurementEvidence").elements();
        while (values.hasNext()) {
            JsonNode value = values.next();
            measurements &= value.path("asserted").asBoolean()
                    && value.path("valuesExported").isBoolean()
                    && !value.path("valuesExported").asBoolean();
        }
        check(violations,
                locality.path("conformant").asBoolean()
                        && locality.path("representationMatrix").path("executed").asBoolean()
                        && locality.path("deepPhysicalLocality").path("executed").asBoolean()
                        && locality.path("sourceFiles").isArray()
                        && locality.path("sourceFiles").size() == 4
                        && cases
                        && measurements,
                "representation-locality-evidence-incomplete");
    }

    private void verifyCleanBuild(JsonNode report, List<String> violations) {
        JsonNode clean = report.path("execution").path("cleanBuild");
        check(violations,
                clean.path("verified").asBoolean()
                        && CleanBuildEvidence.EVIDENCE_KIND.equals(
                                clean.path("evidenceKind").asText())
                        && BuildLogicConstants.ROOT_CLEAN_TASK_PATH.equals(
                                clean.path("cleanTask").asText())
                        && BuildLogicConstants.ROOT_BUILD_TASK_PATH.equals(
                                clean.path("buildTask").asText())
                        && clean.path("excludedTasks").isArray()
                        && clean.path("excludedTasks").size() == 0
                        && getSourceDateEpoch().get().equals(
                                clean.path("sourceDateEpoch").asText())
                        && clean.path("sourceDateEpoch").asText().equals(
                                report.path("jarRepeatability").path("buildProperties")
                                        .path("sourceDateEpoch").asText())
                        && clean.path("sourceCommit").asText().equals(
                                report.path("source").path("commit").asText())
                        && clean.path("sourceInputIdentity").asText()
                                .matches(SHA_256_PATTERN),
                "clean-build-evidence-not-bound-to-candidate");
    }

    private static void verifyReadiness(JsonNode report, List<String> violations) {
        JsonNode readiness = report.path("releaseReadiness");
        boolean expected = readiness.path("implementationGatesPassed").asBoolean()
                && readiness.path("exactCandidateCommit").asBoolean()
                && readiness.path("commitAutomationUntouched").asBoolean()
                && readiness.path("independentApiBaseline").asBoolean();
        check(violations,
                readiness.path("readyToGo").asBoolean() && expected,
                "release-readiness-false-or-inconsistent");
    }

    private static boolean scenarioBool(
            Map<String, JsonNode> scenarios, String id, String field) {
        JsonNode scenario = scenarios.get(id);
        return scenario != null && scenario.path(field).asBoolean();
    }

    private static int scenarioInt(
            Map<String, JsonNode> scenarios, String id, String field) {
        JsonNode scenario = scenarios.get(id);
        return scenario == null ? -1 : scenario.path(field).asInt(-1);
    }

    private static void check(List<String> violations, boolean condition, String violation) {
        if (!condition) {
            violations.add(violation);
        }
    }

    private static JsonNode read(Path file) {
        try {
            return JSON.readTree(file.toFile());
        } catch (IOException exception) {
            throw new GradleException("Cannot read release evidence: " + file, exception);
        }
    }

    private static long size(Path file) {
        try {
            return Files.size(file);
        } catch (IOException exception) {
            throw new GradleException("Cannot read report size: " + file, exception);
        }
    }

    private static void write(Path output, String value) {
        try {
            Files.createDirectories(output.toAbsolutePath().getParent());
            Files.writeString(output, value, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new GradleException("Cannot write release-evidence verification: " + output,
                    exception);
        }
    }
}
