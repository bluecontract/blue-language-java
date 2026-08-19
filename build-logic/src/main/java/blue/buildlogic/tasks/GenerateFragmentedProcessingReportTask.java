package blue.buildlogic.tasks;

import blue.buildlogic.BuildLogicConstants;
import blue.buildlogic.support.CleanBuildEvidence;
import blue.buildlogic.support.DeterministicHashing;
import blue.buildlogic.support.DeterministicJson;
import blue.buildlogic.support.JUnitEvidence;
import blue.buildlogic.support.PlatformInvocationMatrixEvidence;
import blue.buildlogic.support.SourceSnapshot;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

/** Assembles exact test, fixture, artifact, locality, and provenance release evidence. */
@DisableCachingByDefault(because = "Git candidate state and toolchain metadata are invocation evidence")
public abstract class GenerateFragmentedProcessingReportTask extends DefaultTask {

    public static final String SCHEMA = "blue-language-java-release-evidence/1.4";
    private static final String RELEASE_CONFORMANCE_SCHEMA =
            "blue-language-java-release-conformance-report/1.0";
    private static final String RUNTIME_TRACE_SCHEMA =
            "blue-language-java-runtime-trace-evidence/1.0";
    private static final String STATUS_PASS = "PASS";
    private static final String STATUS_SKIP = "SKIP";
    private static final String STATUS_SKIPPED = "SKIPPED";
    private static final String SHA_256_PATTERN = "sha256:[0-9a-f]{64}";
    private static final ObjectMapper JSON = new ObjectMapper();

    public GenerateFragmentedProcessingReportTask() {
        getRequiredLocalityTests().convention(Collections.emptyList());
        getRequiredHostedRuntimeSuites().convention(Collections.emptyList());
        getBenchmarkCompilationSuccessful().convention(false);
        getCommitAutomationDiff().convention("");
        getApiBaselineDiff().convention("");
    }

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getAllTestResults();

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getFocusedTestResults();

    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getReleaseConformanceReport();

    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getRuntimeTraceReport();

    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getPlatformInvocationMatrixReport();

    @InputFile
    @Optional
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getCleanBuildEvidenceFile();

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

    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getApiBaselineFile();

    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getBinaryApiReportFile();

    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getJarReplicaReportFile();

    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getSourceReleaseReplicaReportFile();

    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getSourceReleaseVerificationFile();

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getSourceFiles();

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getLocalitySourceFiles();

    @Internal
    public abstract DirectoryProperty getSourceRoot();

    @Input
    public abstract Property<String> getSourceCommit();

    @Input
    public abstract Property<String> getSourceDateEpoch();

    @Input
    public abstract Property<String> getGitStatus();

    @Input
    public abstract Property<String> getCommitAutomationDiff();

    @Input
    public abstract Property<String> getApiBaselineDiff();

    @Input
    public abstract Property<String> getGradleVersion();

    @Input
    public abstract Property<String> getTestJavaRuntimeVersion();

    @Input
    public abstract ListProperty<String> getRequiredLocalityTests();

    @Input
    public abstract ListProperty<String> getRequiredHostedRuntimeSuites();

    @Input
    public abstract Property<Boolean> getBenchmarkCompilationSuccessful();

    @OutputFile
    public abstract RegularFileProperty getReportFile();

    @OutputFile
    public abstract RegularFileProperty getMarkdownFile();

    @TaskAction
    public void generate() {
        Path root = getSourceRoot().get().getAsFile().toPath().toAbsolutePath().normalize();
        JUnitEvidence.Summary allTests = JUnitEvidence.parse(
                paths(getAllTestResults()), ":test", false);
        JUnitEvidence.Summary focused = JUnitEvidence.parse(
                paths(getFocusedTestResults()), ":fragmentedProcessingTest", true);
        JsonNode conformance = read(getReleaseConformanceReport());
        JsonNode runtimeTrace = read(getRuntimeTraceReport());
        Map<String, Object> platformInvocationMatrix =
                PlatformInvocationMatrixEvidence.analyze(
                        read(getPlatformInvocationMatrixReport()));
        requireSchema(conformance, RELEASE_CONFORMANCE_SCHEMA, "release conformance");
        requireSchema(runtimeTrace, RUNTIME_TRACE_SCHEMA, "runtime trace");

        SourceSnapshot sourceSnapshot = DeterministicHashing.snapshot(root, paths(getSourceFiles()));
        CleanBuildEvidence.Verification clean = CleanBuildEvidence.verify(
                getCleanBuildEvidenceFile().get().getAsFile().toPath(),
                root,
                paths(getSourceFiles()),
                getSourceCommit().get(),
                getSourceDateEpoch().get(),
                BuildLogicConstants.ROOT_CLEAN_TASK_PATH,
                BuildLogicConstants.ROOT_BUILD_TASK_PATH);
        Map<String, Object> cleanBuild = cleanBuild(clean, sourceSnapshot, root);
        Map<String, Object> releaseConformance = releaseConformance(conformance);
        Map<String, Object> binaryApi = binaryApi();
        JsonNode jarReplicaNode = read(getJarReplicaReportFile());
        JsonNode sourceReplicaNode = read(getSourceReleaseReplicaReportFile());
        Map<String, Object> jarReplica = plain(jarReplicaNode);
        Map<String, Object> sourceReplica = plain(sourceReplicaNode);
        Map<String, Object> sourceVerification =
                plain(read(getSourceReleaseVerificationFile()));
        List<Map<String, Object>> requiredCases = requiredCases(focused);
        if (requiredCases.size() != 5) {
            throw new GradleException(
                    "Release evidence requires exactly five locality test cases");
        }
        boolean localityConformant = requiredCases.stream().allMatch(value ->
                Boolean.TRUE.equals(value.get("executed"))
                        && Boolean.TRUE.equals(value.get("passed")))
                && Boolean.TRUE.equals(platformInvocationMatrix.get("conformant"));
        Path platformMatrixPath = getPlatformInvocationMatrixReport()
                .get().getAsFile().toPath();
        platformInvocationMatrix.put("evidenceIdentity",
                DeterministicHashing.sha256(platformMatrixPath));
        platformInvocationMatrix.put("evidencePath",
                relative(root, platformMatrixPath));
        Map<String, Object> hostedRuntime = hostedRuntime(allTests);
        boolean hostedConformant = hostedRuntime.values().stream()
                .allMatch(GenerateFragmentedProcessingReportTask::passingSuiteEvidence);
        boolean fixtureConformant = Boolean.TRUE.equals(releaseConformance.get("conformant"));
        boolean runtimeConformant = runtimeConformant(runtimeTrace);
        boolean jarRepeatable = Boolean.TRUE.equals(jarReplica.get("identical"));
        boolean sourceArchivesRepeatable = Boolean.TRUE.equals(sourceReplica.get("identical"))
                && Boolean.TRUE.equals(sourceVerification.get("valid"));
        boolean archiveConformant = jarRepeatable && sourceArchivesRepeatable;
        boolean binaryCompatible = Boolean.TRUE.equals(binaryApi.get("compatible"));
        boolean conformant = allTests.isConformant()
                && focused.isConformant()
                && fixtureConformant
                && runtimeConformant
                && archiveConformant
                && binaryCompatible
                && clean.isVerified()
                && localityConformant
                && hostedConformant
                && getBenchmarkCompilationSuccessful().get();
        String status = getGitStatus().get().trim();
        boolean workingTreeClean = status.isEmpty();
        boolean commitAutomationUntouched = getCommitAutomationDiff().get().trim().isEmpty();
        boolean apiBaselineIndependent = getApiBaselineDiff().get().trim().isEmpty();
        boolean readyToGo = conformant
                && workingTreeClean
                && commitAutomationUntouched
                && apiBaselineIndependent;

        Map<String, Object> report = new TreeMap<>();
        report.put("allTests", allTests.toMap());
        report.put("artifacts", artifacts());
        report.put("baseline", historicalBaseline());
        report.put("binaryApi", binaryApi);
        report.put("cyclicEvidence", allTests.suiteEvidence("CyclicProcessingBoundaryTest"));
        report.put("demandVocabulary", demandVocabulary());
        report.put("execution", execution(cleanBuild));
        report.put("focusedVerification", focused.toMap());
        report.put("hostedRuntime", hostedRuntime);
        report.put("jarRepeatability", jarRepeatability(jarReplicaNode));
        report.put("packages", plainObject(conformance.path("packages"), "packages"));
        report.put("release", plainObject(conformance.path("release"), "release"));
        report.put("releaseConformance", releaseConformance);
        report.put("releaseReadiness", releaseReadiness(
                readyToGo,
                conformant,
                workingTreeClean,
                commitAutomationUntouched,
                apiBaselineIndependent));
        report.put("representationAndLocality", representationAndLocality(
                root,
                focused,
                requiredCases,
                platformInvocationMatrix,
                localityConformant));
        report.put("runtimeTrace", plain(runtimeTrace));
        report.put("schema", SCHEMA);
        report.put("source", source(getSourceCommit().get(), status));
        report.put("sourceArchiveRepeatability",
                sourceArchiveRepeatability(jarReplicaNode, sourceReplicaNode));
        report.put("sourceArchiveVerification", sourceVerification);
        report.put("specifications", plainObject(
                conformance.path("specifications"), "specifications"));
        report.put("summary", summary(
                allTests,
                focused,
                releaseConformance,
                binaryCompatible,
                jarRepeatable,
                sourceArchivesRepeatable,
                clean.isVerified(),
                localityConformant,
                hostedConformant,
                runtimeConformant,
                runtimeTrace,
                commitAutomationUntouched,
                conformant));
        report.put("toolchain", toolchain());
        report.put("version", "1.4");
        write(getReportFile().get().getAsFile().toPath(), DeterministicJson.write(report));
        write(getMarkdownFile().get().getAsFile().toPath(), markdown(
                readyToGo, conformant, allTests, releaseConformance, clean, report));
    }

    private Map<String, Object> cleanBuild(
            CleanBuildEvidence.Verification verification,
            SourceSnapshot current,
            Path root) {
        CleanBuildEvidence.Marker marker = verification.getMarker();
        Map<String, Object> value = new TreeMap<>();
        value.put("buildTask", BuildLogicConstants.ROOT_BUILD_TASK_PATH);
        value.put("cleanTask", BuildLogicConstants.ROOT_CLEAN_TASK_PATH);
        value.put("evidenceKind", CleanBuildEvidence.EVIDENCE_KIND);
        value.put("excludedTasks", marker == null
                ? Collections.emptyList() : marker.getExcludedTasks());
        value.put("invocationTasks", marker == null
                ? Collections.emptyList() : marker.getInvocationTasks());
        value.put("marker", relative(root,
                getCleanBuildEvidenceFile().get().getAsFile().toPath()));
        value.put("reason", verification.getReason());
        value.put("sourceCommit", marker == null ? null : marker.getSourceCommit());
        value.put("sourceDateEpoch", marker == null ? null : marker.getSourceDateEpoch());
        value.put("sourceFileCount", current.getEntries().size());
        value.put("sourceInputIdentity", current.getIdentity());
        value.put("verified", verification.isVerified());
        return value;
    }

    private Map<String, Object> artifacts() {
        Map<String, Object> values = new TreeMap<>();
        values.put("apiBaseline", artifact(getApiBaselineFile()));
        values.put("binaryApiReport", artifact(getBinaryApiReportFile()));
        values.put("jar", artifact(getJarFile()));
        values.put("javadocJar", artifact(getJavadocJarFile()));
        values.put("jarRepeatabilityReport", artifact(getJarReplicaReportFile()));
        values.put("releaseConformanceReport", artifact(getReleaseConformanceReport()));
        values.put("runtimeTraceEvidence", artifact(getRuntimeTraceReport()));
        values.put("platformInvocationMatrixEvidence",
                artifact(getPlatformInvocationMatrixReport()));
        values.put("sourceArchiveRepeatabilityReport",
                artifact(getSourceReleaseReplicaReportFile()));
        values.put("sourceRelease", artifact(getSourceReleaseFile()));
        values.put("sourcesJar", artifact(getSourcesJarFile()));
        return values;
    }

    private Map<String, Object> jarRepeatability(JsonNode report) {
        JsonNode archive = archiveEntry(report, getJarFile().get().getAsFile().getName());
        Map<String, Object> buildProperties = new TreeMap<>();
        buildProperties.put("sourceDateEpoch", getSourceDateEpoch().get());
        Map<String, Object> value = new TreeMap<>();
        value.put("buildProperties", buildProperties);
        value.put("primary", archiveIdentity(archive, "referenceIdentity"));
        value.put("repeatable", archive.path("identical").asBoolean());
        value.put("replica", archiveIdentity(archive, "replicaIdentity"));
        return value;
    }

    private Map<String, Object> sourceArchiveRepeatability(
            JsonNode jarReport, JsonNode sourceReleaseReport) {
        JsonNode sources = archiveEntry(
                jarReport, getSourcesJarFile().get().getAsFile().getName());
        JsonNode sourceRelease = archiveEntry(
                sourceReleaseReport, getSourceReleaseFile().get().getAsFile().getName());
        Map<String, Object> value = new TreeMap<>();
        value.put("repeatable", sources.path("identical").asBoolean()
                && sourceRelease.path("identical").asBoolean());
        value.put("sourceReleaseZip", archiveComparison(sourceRelease));
        value.put("sourcesJar", archiveComparison(sources));
        return value;
    }

    private static Map<String, Object> archiveComparison(JsonNode archive) {
        Map<String, Object> value = new TreeMap<>();
        value.put("byteIdentical", archive.path("identical").asBoolean());
        value.put("entriesIdentical", archive.path("identical").asBoolean());
        value.put("primaryIdentity", archive.path("referenceIdentity").asText());
        value.put("replicaIdentity", archive.path("replicaIdentity").asText());
        return value;
    }

    private static Map<String, Object> archiveIdentity(JsonNode archive, String field) {
        Map<String, Object> value = new TreeMap<>();
        value.put("identity", archive.path(field).asText());
        return value;
    }

    private static JsonNode archiveEntry(JsonNode report, String name) {
        for (JsonNode archive : report.path("archives")) {
            if (name.equals(archive.path("name").asText())) {
                return archive;
            }
        }
        throw new GradleException("Archive replica report is missing " + name);
    }

    private Map<String, Object> artifact(RegularFileProperty property) {
        Path file = property.get().getAsFile().toPath();
        Map<String, Object> value = new TreeMap<>();
        value.put("identity", DeterministicHashing.sha256(file));
        value.put("name", file.getFileName().toString());
        return value;
    }

    private Map<String, Object> releaseConformance(JsonNode report) {
        requireIdentity(report.path("release").path("packageIdentity").asText(),
                "release package");
        requireIdentity(report.path("release")
                        .path("contractsReleaseIdentity").asText(),
                "canonical Contracts release");
        JsonNode packages = report.path("packages");
        for (String key : Arrays.asList(
                "languageRegistry",
                "languageFixtures",
                "contractsRegistry",
                "contractsGas",
                "contractsFixtures",
                "contractsRelease")) {
            requireIdentity(packages.path(key).asText(), "release package " + key);
        }
        Map<String, int[]> counts = new TreeMap<>();
        JsonNode fixtures = report.path("fixtures");
        if (!fixtures.isArray()) {
            throw new GradleException("Release conformance fixtures are not an array");
        }
        for (JsonNode fixture : fixtures) {
            String suite = fixture.path("suite").asText();
            if (suite.isEmpty()) {
                throw new GradleException("Release fixture has no suite");
            }
            int[] value = counts.computeIfAbsent(suite, ignored -> new int[4]);
            value[0]++;
            String status = fixture.path("status").asText();
            if (STATUS_PASS.equals(status)) {
                value[1]++;
            } else if (STATUS_SKIP.equals(status) || STATUS_SKIPPED.equals(status)) {
                value[3]++;
            } else {
                value[2]++;
            }
        }
        List<Map<String, Object>> suites = new ArrayList<>();
        int tests = 0;
        int passed = 0;
        int failed = 0;
        int skipped = 0;
        for (Map.Entry<String, int[]> entry : counts.entrySet()) {
            int[] count = entry.getValue();
            Map<String, Object> suite = new TreeMap<>();
            suite.put("failed", count[2]);
            suite.put("name", entry.getKey());
            suite.put("passed", count[1]);
            suite.put("skipped", count[3]);
            suite.put("tests", count[0]);
            suites.add(suite);
            tests += count[0];
            passed += count[1];
            failed += count[2];
            skipped += count[3];
        }
        Map<String, Object> value = new TreeMap<>();
        JsonNode summary = report.path("summary");
        boolean countsMatchSummary = summary.path("total").asInt(-1) == tests
                && summary.path("passed").asInt(-1) == passed
                && summary.path("failed").asInt(-1) == failed
                && summary.path("skipped").asInt(-1) == skipped;
        if (!countsMatchSummary) {
            throw new GradleException(
                    "Release fixture records do not match their summary counts");
        }
        boolean conformant = tests == BuildLogicConstants.EXPECTED_RELEASE_FIXTURE_COUNT
                && passed == BuildLogicConstants.EXPECTED_RELEASE_FIXTURE_COUNT
                && failed == 0
                && skipped == 0
                && summary.path("conformant").asBoolean();
        List<String> executedSuites = counts.keySet().stream()
                .map(name -> "release-conformance:" + name)
                .collect(Collectors.toList());
        value.put("conformant", conformant);
        value.put("executedSuites", executedSuites);
        value.put("failed", failed);
        value.put("passed", passed);
        value.put("schema", RELEASE_CONFORMANCE_SCHEMA);
        value.put("skipped", skipped);
        value.put("sourceTask", ":releaseConformanceTest");
        value.put("suiteCount", suites.size());
        value.put("suites", suites);
        value.put("tests", tests);
        return value;
    }

    private Map<String, Object> binaryApi() {
        Path report = getBinaryApiReportFile().get().getAsFile().toPath();
        Map<String, String> values = new LinkedHashMap<>();
        List<String> additive = new ArrayList<>();
        boolean additions = false;
        try {
            for (String line : Files.readAllLines(report, StandardCharsets.UTF_8)) {
                int separator = line.indexOf('=');
                if (separator > 0) {
                    values.put(line.substring(0, separator), line.substring(separator + 1));
                }
                if ("Additive changes:".equals(line)) {
                    additions = true;
                } else if (additions && !line.trim().isEmpty()) {
                    additive.add(line.trim());
                }
            }
        } catch (IOException exception) {
            throw new GradleException("Cannot read binary API report: " + report, exception);
        }
        int incompatible = integer(values, "incompatibleChanges");
        int additiveCount = integer(values, "additiveChanges");
        String versions = required(values, "currentClassMajorVersions");
        List<Integer> classMajorVersions = Arrays.stream(versions.split(","))
                .filter(value -> !value.isEmpty())
                .map(Integer::parseInt)
                .collect(Collectors.toList());
        boolean javaEight = !classMajorVersions.isEmpty()
                && classMajorVersions.stream().allMatch(version -> version <= 52);
        boolean compatible = incompatible == 0
                && "true".equals(values.get("migrationLedgerVerified"))
                && additive.size() == additiveCount
                && javaEight;
        Map<String, Object> result = new TreeMap<>();
        result.put("additiveApi", additive);
        result.put("additiveChanges", additiveCount);
        result.put("baseline", required(values, "baseline"));
        result.put("baselineApiClasses", integer(values, "baselineApiClasses"));
        result.put("baselineUnmodifiedFromHead", getApiBaselineDiff().get().trim().isEmpty());
        result.put("compatible", compatible);
        result.put("current", required(values, "current"));
        result.put("currentApiClasses", integer(values, "currentApiClasses"));
        result.put("currentClassMajorVersions", classMajorVersions);
        result.put("incompatibleChanges", incompatible);
        result.put("migrationLedgerVerified", "true".equals(values.get("migrationLedgerVerified")));
        result.put("sourceTask", ":verifySemanticApiMigration");
        return result;
    }

    private List<Map<String, Object>> requiredCases(JUnitEvidence.Summary focused) {
        List<Map<String, Object>> cases = new ArrayList<>();
        for (String identity : getRequiredLocalityTests().get()) {
            int separator = identity.indexOf('#');
            if (separator <= 0 || separator == identity.length() - 1) {
                throw new GradleException("Invalid required locality test identity: " + identity);
            }
            String className = identity.substring(0, separator);
            String method = identity.substring(separator + 1);
            List<Map<String, Object>> records = focused.records(className, method);
            boolean passed = !records.isEmpty() && records.stream()
                    .allMatch(record -> "PASSED".equals(record.get("status")));
            Map<String, Object> value = new TreeMap<>();
            value.put("executed", !records.isEmpty());
            value.put("passed", passed);
            value.put("records", records);
            value.put("testMethod", method);
            cases.add(value);
        }
        return cases;
    }

    private Map<String, Object> hostedRuntime(JUnitEvidence.Summary allTests) {
        Map<String, Object> values = new TreeMap<>();
        for (String suite : getRequiredHostedRuntimeSuites().get()) {
            values.put(hostedEvidenceKey(suite), allTests.suiteEvidence(suite));
        }
        return values;
    }

    private static String hostedEvidenceKey(String suite) {
        switch (suite) {
            case "RuntimeWorkSessionTest":
                return "runtimeWorkSession";
            case "RuntimeWorkSessionProcessorPhaseIntegrationTest":
                return "runtimePhaseIntegration";
            case "SemanticOutputBoundaryTest":
                return "semanticIdentityBoundary";
            case "DocumentProcessorHandlerFailureTest":
                return "gasExhaustion";
            case "ExternalChannelDependencyContextTest":
                return "subtypeCatalog";
            case "SubtypeAssignablePredicateTest":
                return "subtypePredicate";
            case "ContractContributionResolverTest":
                return "executableBodySource";
            case "SelectedExecutableBodyCapabilityTest":
                return "selectedBodyMaterializer";
            case "ExternalChannelHostedOutputAdmissionTest":
                return "hostedOutputAdmission";
            default:
                throw new GradleException("Unknown hosted-runtime suite: " + suite);
        }
    }

    private Map<String, Object> representationAndLocality(
            Path root,
            JUnitEvidence.Summary focused,
            List<Map<String, Object>> cases,
            Map<String, Object> platformInvocationMatrix,
            boolean conformant) {
        List<Map<String, Object>> sources = new ArrayList<>();
        List<Path> sorted = paths(getLocalitySourceFiles());
        sorted.sort(java.util.Comparator.comparing(path -> relative(root, path)));
        for (Path source : sorted) {
            Map<String, Object> value = new TreeMap<>();
            value.put("identity", DeterministicHashing.sha256(source));
            value.put("path", relative(root, source));
            sources.add(value);
        }
        Map<String, Object> measurements = new TreeMap<>();
        List<Map<String, Object>> representationAndDeep =
                Arrays.asList(cases.get(0), cases.get(1));
        measurements.put("exactRequestedBlueIds", assertionEvidence(representationAndDeep));
        measurements.put("forbiddenDemands", assertionEvidence(representationAndDeep));
        measurements.put("representationNeutralSemanticsGas",
                assertionEvidence(representationAndDeep));
        measurements.put("semanticDemandSet", assertionEvidence(representationAndDeep));
        measurements.put("structuralSharing",
                assertionEvidence(Collections.singletonList(cases.get(1))));
        measurements.put("transferredProviderBytes", assertionEvidence(representationAndDeep));
        Map<String, Object> export = new TreeMap<>();
        export.put("exactValuesAvailable", false);
        export.put("reason", "JUnit XML proves assertion outcomes but does not export "
                + "per-variant requested-BlueId, byte, or semantic-demand values.");
        Map<String, Object> value = new TreeMap<>();
        value.put("conformant", conformant);
        value.put("deepPhysicalLocality", focused.suiteEvidence(
                "DeepGraphPhysicalLocalityIntegrationTest", true));
        value.put("evidenceSource", ":fragmentedProcessingTest JUnit XML");
        value.put("exactFragmentAdmission", focused.suiteEvidence(
                "ExactNodeGraphFragmentsTest", true));
        value.put("measurementEvidence", measurements);
        value.put("measurementExport", export);
        value.put("publicPlatformInvocationMatrix",
                platformInvocationMatrix);
        value.put("providerFailureMatrix", focused.suiteEvidence(
                "FragmentedProcessingFailureMatrixTest", true));
        value.put("representationMatrix", focused.suiteEvidence(
                "FragmentedProcessingLocalityIntegrationTest", true));
        value.put("requiredTestCases", cases);
        value.put("sourceFiles", sources);
        return value;
    }

    private static Map<String, Object> assertionEvidence(
            List<Map<String, Object>> cases) {
        boolean asserted = cases.stream().allMatch(value ->
                Boolean.TRUE.equals(value.get("executed"))
                        && Boolean.TRUE.equals(value.get("passed")));
        Map<String, Object> value = new TreeMap<>();
        value.put("asserted", asserted);
        value.put("evidenceKind", "passing-junit-assertions");
        value.put("testCases", cases);
        value.put("valuesExported", false);
        return value;
    }

    private Map<String, Object> execution(Map<String, Object> cleanBuild) {
        Map<String, Object> benchmark = new TreeMap<>();
        benchmark.put("scope", "compilation-only; benchmarks were not executed");
        benchmark.put("successful", getBenchmarkCompilationSuccessful().get());
        benchmark.put("task", ":benchmarkClasses");
        Map<String, Object> value = new TreeMap<>();
        value.put("benchmarkCompilation", benchmark);
        value.put("cleanBuild", cleanBuild);
        return value;
    }

    private Map<String, Object> summary(
            JUnitEvidence.Summary all,
            JUnitEvidence.Summary focused,
            Map<String, Object> fixtures,
            boolean binary,
            boolean jarRepeatable,
            boolean sourceArchivesRepeatable,
            boolean clean,
            boolean locality,
            boolean hosted,
            boolean runtime,
            JsonNode runtimeTrace,
            boolean commitAutomationUntouched,
            boolean conformant) {
        Map<String, Object> value = new TreeMap<>();
        value.put("allTestSuites", all.getSuites().size());
        value.put("allTests", all.getTests());
        value.put("binaryApiCompatible", binary);
        value.put("cleanBuildVerified", clean);
        value.put("commitAutomationUntouched", commitAutomationUntouched);
        value.put("conformant", conformant);
        value.put("focusedEvidenceSuites", focused.getSuites().size());
        value.put("focusedEvidenceTests", focused.getTests());
        value.put("hostedRuntimeEvidencePassed", hosted);
        value.put("jarRepeatable", jarRepeatable);
        value.put("localityEvidencePassed", locality);
        value.put("releaseFixtures", fixtures.get("tests"));
        value.put("releaseFixtureSuites", fixtures.get("suiteCount"));
        value.put("runtimeTraceEvidencePassed", runtime);
        value.put("maximumObservedRuntimeTraceEntries",
                runtimeTrace.path("summary").path("maximumObservedOrderedEntries").asInt(-1));
        value.put("sourceArchivesRepeatable", sourceArchivesRepeatable);
        return value;
    }

    private Map<String, Object> source(String commit, String status) {
        Map<String, Object> value = new TreeMap<>();
        value.put("commit", commit.trim());
        value.put("modifiedPathCount", status.isEmpty() ? 0 : status.split("\\R").length);
        value.put("workingTreeClean", status.isEmpty());
        return value;
    }

    private Map<String, Object> historicalBaseline() {
        Map<String, Object> value = new TreeMap<>();
        value.put("failed", 0);
        value.put("passed", 1765);
        value.put("skipped", 0);
        value.put("sourceTask", ":test before generic-kernel changes");
        value.put("suites", 171);
        value.put("tests", 1765);
        return value;
    }

    private Map<String, Object> releaseReadiness(
            boolean ready,
            boolean implementation,
            boolean cleanTree,
            boolean commitAutomationUntouched,
            boolean apiBaselineIndependent) {
        Map<String, Object> value = new TreeMap<>();
        value.put("commitAutomationUntouched", commitAutomationUntouched);
        value.put("exactCandidateCommit", cleanTree);
        value.put("implementationGatesPassed", implementation);
        value.put("independentApiBaseline", apiBaselineIndependent);
        List<String> limitations = new ArrayList<>();
        if (!cleanTree) {
            limitations.add("HEAD is not the exact candidate source identity because the working tree is not clean.");
        }
        if (!commitAutomationUntouched) {
            limitations.add(".cz.toml differs from HEAD.");
        }
        if (!apiBaselineIndependent) {
            limitations.add("The legacy JVM API baseline differs from HEAD.");
        }
        if (!implementation) {
            limitations.add("One or more implementation gates failed.");
        }
        value.put("knownLimitations", limitations);
        value.put("readyToGo", ready);
        return value;
    }

    private Map<String, Object> demandVocabulary() {
        Map<String, Object> value = new TreeMap<>();
        value.put("invarianceContract",
                "Equivalent representations preserve semantic demands and logical gas; "
                        + "physical provider calls and bytes may vary.");
        value.put("logicalGasTrace", vocabulary(
                "logical-consensus", true,
                "Deterministic gas-counter sequence for semantic work."));
        value.put("providerBytes", vocabulary(
                "physical-observation", false,
                "Runtime provider bytes transferred; never a gas input."));
        value.put("providerCalls", vocabulary(
                "physical-observation", false,
                "Runtime provider acquisition calls; never a gas input."));
        value.put("semanticDemands", vocabulary(
                "logical-consensus", true,
                "Exact semantic identities demanded by processing."));
        return value;
    }

    private static Map<String, Object> vocabulary(
            String category, boolean portable, String meaning) {
        Map<String, Object> value = new TreeMap<>();
        value.put("category", category);
        value.put("meaning", meaning);
        value.put("portable", portable);
        return value;
    }

    private Map<String, Object> toolchain() {
        Map<String, Object> buildJvm = new TreeMap<>();
        buildJvm.put("javaRuntime", System.getProperty("java.runtime.version"));
        buildJvm.put("javaVendor", System.getProperty("java.vendor"));
        buildJvm.put("javaVersion", System.getProperty("java.version"));
        buildJvm.put("vmVersion", System.getProperty("java.vm.version"));
        Map<String, Object> gradle = new TreeMap<>();
        gradle.put("version", getGradleVersion().get());
        Map<String, Object> testJvm = new TreeMap<>();
        testJvm.put("languageVersion", "8");
        testJvm.put("runtimeVersion", getTestJavaRuntimeVersion().get());
        Map<String, Object> value = new TreeMap<>();
        value.put("buildJvm", buildJvm);
        value.put("bytecodeTarget", 8);
        value.put("gradle", gradle);
        value.put("testJvm", testJvm);
        return value;
    }

    private String markdown(
            boolean ready,
            boolean conformant,
            JUnitEvidence.Summary all,
            Map<String, Object> fixtures,
            CleanBuildEvidence.Verification clean,
            Map<String, Object> report) {
        return "# Blue Language final generic-kernel report\n\n"
                + "- Ready to go: **" + ready + "**\n"
                + "- Implementation gates passed: **" + conformant + "**\n"
                + "- Candidate source commit: `" + getSourceCommit().get() + "`\n"
                + "- Main tests: `" + all.getPassed() + "/" + all.getTests() + "`\n"
                + "- Release fixtures: `" + fixtures.get("passed") + "/"
                + fixtures.get("tests") + "`\n"
                + "- Clean-build evidence: `" + clean.isVerified() + "`\n"
                + "- Main JAR: `" + artifactIdentity(report, "jar") + "`\n"
                + "- Source release: `" + artifactIdentity(report, "sourceRelease") + "`\n";
    }

    @SuppressWarnings("unchecked")
    private static String artifactIdentity(Map<String, Object> report, String name) {
        Map<String, Object> artifacts = (Map<String, Object>) report.get("artifacts");
        return String.valueOf(((Map<String, Object>) artifacts.get(name)).get("identity"));
    }

    private static boolean runtimeConformant(JsonNode runtime) {
        JsonNode summary = runtime.path("summary");
        Map<String, JsonNode> scenarios = new TreeMap<>();
        for (JsonNode scenario : runtime.path("scenarios")) {
            scenarios.put(scenario.path("id").asText(), scenario);
        }
        return RUNTIME_TRACE_SCHEMA.equals(runtime.path("schemaVersion").asText())
                && ":runtimeTraceEvidence".equals(runtime.path("sourceTask").asText())
                && summary.path("executed").asInt() == 8
                && summary.path("passed").asInt() == 8
                && summary.path("failed").asInt() == 0
                && summary.path("skipped").asInt() == 0
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
                && scenarioInt(scenarios, "transient-suspension-discard", "committedEntries") == 0;
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

    @SuppressWarnings("unchecked")
    private static boolean passingSuiteEvidence(Object evidence) {
        Map<String, Object> value = (Map<String, Object>) evidence;
        return Boolean.TRUE.equals(value.get("executed"))
                && ((Number) value.get("tests")).intValue() > 0
                && ((Number) value.get("failed")).intValue() == 0
                && ((Number) value.get("skipped")).intValue() == 0;
    }

    private static Map<String, Object> plainObject(JsonNode node, String label) {
        if (!node.isObject()) {
            throw new GradleException("Release conformance is missing " + label);
        }
        return plain(node);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> plain(JsonNode node) {
        return JSON.convertValue(node, TreeMap.class);
    }

    private static JsonNode read(RegularFileProperty property) {
        Path file = property.get().getAsFile().toPath();
        try {
            return JSON.readTree(file.toFile());
        } catch (IOException exception) {
            throw new GradleException("Cannot read JSON evidence: " + file, exception);
        }
    }

    private static void requireSchema(JsonNode node, String expected, String label) {
        String schema = node.path("schema").asText();
        if (schema.isEmpty()) {
            schema = node.path("schemaVersion").asText();
        }
        if (!expected.equals(schema)) {
            throw new GradleException(label + " uses unexpected schema: " + schema);
        }
    }

    private static int integer(Map<String, String> values, String key) {
        try {
            return Integer.parseInt(required(values, key));
        } catch (NumberFormatException exception) {
            throw new GradleException("Binary API report has invalid integer " + key, exception);
        }
    }

    private static String required(Map<String, String> values, String key) {
        String value = values.get(key);
        if (value == null || value.isEmpty()) {
            throw new GradleException("Binary API report is missing " + key);
        }
        return value;
    }

    private static void requireIdentity(String value, String label) {
        if (!value.matches(SHA_256_PATTERN)) {
            throw new GradleException(label + " is not a SHA-256 identity");
        }
    }

    private static List<Path> paths(ConfigurableFileCollection files) {
        return files.getFiles().stream().map(File::toPath).collect(Collectors.toList());
    }

    private static String relative(Path root, Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        return normalized.startsWith(root)
                ? root.relativize(normalized).toString().replace(File.separatorChar, '/')
                : normalized.toString().replace(File.separatorChar, '/');
    }

    private static void write(Path output, String value) {
        try {
            Files.createDirectories(output.toAbsolutePath().getParent());
            Files.writeString(output, value, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new GradleException("Cannot write fragmented processing evidence: " + output,
                    exception);
        }
    }
}
